use base64::{engine::general_purpose::STANDARD, Engine};
use image::{DynamicImage, ImageDecoder, ImageFormat, ImageReader, Limits};
use std::{io::Cursor, sync::Arc, time::Duration};
use tokio::sync::Semaphore;

const MAX_SOURCE_BYTES: usize = 12 * 1024 * 1024;
const MAX_PREVIEW_BYTES: usize = 600 * 1024;
const USER_AGENT: &str = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36";

pub struct Previewer {
    client: reqwest::Client,
    slots: Arc<Semaphore>,
}

impl Previewer {
    pub async fn local_media(
        &self,
        path: &std::path::Path,
        kind: &str,
        ffmpeg: &str,
    ) -> Result<String, String> {
        if !path.is_absolute() || !path.is_file() || !matches!(kind, "image" | "video" | "audio") {
            return Err("This library media file is unavailable".into());
        }
        let permit = self
            .slots
            .clone()
            .acquire_owned()
            .await
            .map_err(|_| "Image previews are unavailable")?;
        if kind == "image" {
            let path = path.to_owned();
            return tokio::task::spawn_blocking(move || {
                let _permit = permit;
                thumbnail(&crate::storage::read_bounded(&path, MAX_SOURCE_BYTES)?)
            })
            .await
            .map_err(|_| "Could not decode library image")?;
        }
        if !std::path::Path::new(ffmpeg).is_absolute() || !std::path::Path::new(ffmpeg).is_file() {
            if kind == "audio" {
                return retained_artwork(path);
            }
            return Err("Set up FFmpeg to preview media artwork".into());
        }
        let directory =
            std::env::temp_dir().join(format!("torikomi-preview-{}", uuid::Uuid::new_v4()));
        let builder = {
            let builder = std::fs::DirBuilder::new();
            #[cfg(unix)]
            let builder = {
                let mut builder = builder;
                use std::os::unix::fs::DirBuilderExt;
                builder.mode(0o700);
                builder
            };
            builder
        };
        builder.create(&directory).map_err(|e| e.to_string())?;
        let output = TemporaryFrame(directory.join("frame.png"));
        let args = if kind == "audio" {
            audio_args(path, &output.0)?
        } else {
            video_args(path, &output.0)?
        };
        let job = Arc::new(crate::process::Job::default());
        let result = tokio::time::timeout(
            Duration::from_secs(15),
            crate::process::execute(ffmpeg, &args, job.clone(), 64 * 1024, |_| {}),
        )
        .await;
        let extraction = match result {
            Ok(result) => result.map(|_| ()),
            Err(_) => {
                job.cancel();
                Err("Media preview timed out".into())
            }
        };
        if let Err(error) = extraction {
            if kind == "audio" {
                return retained_artwork(path);
            }
            return Err(error);
        }
        let result = thumbnail(&crate::storage::read_bounded(&output.0, MAX_PREVIEW_BYTES)?);
        drop(permit);
        result
    }

    pub fn new() -> Result<Self, String> {
        let client = reqwest::Client::builder()
            .user_agent(USER_AGENT)
            .referer(false)
            .timeout(Duration::from_secs(15))
            .redirect(reqwest::redirect::Policy::custom(|attempt| {
                if attempt.previous().len() > 5 {
                    attempt.error("Too many preview redirects")
                } else if crate::core::media_url(attempt.url().as_str()).is_err() {
                    attempt.error("Invalid preview redirect")
                } else {
                    attempt.follow()
                }
            }))
            .build()
            .map_err(|_| "Could not initialize image previews")?;
        Ok(Self {
            client,
            slots: Arc::new(Semaphore::new(3)),
        })
    }

    pub async fn fetch(&self, url: &str, referer: &str) -> Result<String, String> {
        let url = crate::core::media_url(url)?;
        let referer = crate::core::media_url(referer)?;
        let permit = self
            .slots
            .clone()
            .acquire_owned()
            .await
            .map_err(|_| "Image previews are unavailable")?;
        let mut response = self
            .client
            .get(url)
            .header(reqwest::header::REFERER, referer)
            .send()
            .await
            .map_err(|_| "Could not fetch image preview")?;
        if !response.status().is_success() {
            return Err(format!("Image preview returned HTTP {}", response.status()));
        }
        if response
            .content_length()
            .is_some_and(|n| n > MAX_SOURCE_BYTES as u64)
        {
            return Err("Image preview source exceeds 12 MiB".into());
        }
        let mut bytes = Vec::new();
        while let Some(chunk) = response
            .chunk()
            .await
            .map_err(|_| "Could not read image preview")?
        {
            if chunk.len() > MAX_SOURCE_BYTES - bytes.len() {
                return Err("Image preview source exceeds 12 MiB".into());
            }
            bytes.extend_from_slice(&chunk);
        }
        tokio::task::spawn_blocking(move || {
            // Keep the slot until decoding ends, including when the caller cancels.
            let _permit = permit;
            thumbnail(&bytes)
        })
        .await
        .map_err(|_| "Could not decode image preview")?
    }
}

fn retained_artwork(path: &std::path::Path) -> Result<String, String> {
    for extension in ["png", "jpg", "jpeg", "webp"] {
        let cover = path.with_extension(extension);
        if cover != path && cover.is_file() {
            if let Ok(preview) = crate::storage::read_bounded(&cover, MAX_SOURCE_BYTES)
                .and_then(|bytes| thumbnail(&bytes))
            {
                return Ok(preview);
            }
        }
    }
    Err("No local audio artwork is available".into())
}

struct TemporaryFrame(std::path::PathBuf);
impl Drop for TemporaryFrame {
    fn drop(&mut self) {
        let _ = std::fs::remove_file(&self.0);
        if let Some(parent) = self.0.parent() {
            let _ = std::fs::remove_dir(parent);
        }
    }
}

pub fn video_args(path: &std::path::Path, output: &std::path::Path) -> Result<Vec<String>, String> {
    if !path.is_absolute() || !output.is_absolute() {
        return Err("Invalid video preview path".into());
    }
    Ok([
        "-hide_banner",
        "-loglevel",
        "error",
        "-nostdin",
        "-n",
        "-protocol_whitelist",
        "file,pipe",
        "-i",
        path.to_str().ok_or("Invalid video path")?,
        "-an",
        "-frames:v",
        "1",
        "-vf",
        "scale=320:320:force_original_aspect_ratio=decrease",
        "-f",
        "image2",
        output.to_str().ok_or("Invalid preview path")?,
    ]
    .map(String::from)
    .to_vec())
}

pub fn audio_args(path: &std::path::Path, output: &std::path::Path) -> Result<Vec<String>, String> {
    let mut args = video_args(path, output)?;
    let index = args
        .iter()
        .position(|arg| arg == "-an")
        .ok_or("Invalid media preview arguments")?;
    args.splice(index..index, ["-map".into(), "0:v:0".into()]);
    Ok(args)
}

pub fn thumbnail(bytes: &[u8]) -> Result<String, String> {
    if bytes.len() > MAX_SOURCE_BYTES {
        return Err("Image preview source exceeds 12 MiB".into());
    }
    let mut reader = ImageReader::new(Cursor::new(bytes))
        .with_guessed_format()
        .map_err(|_| "Could not identify image preview")?;
    if !matches!(
        reader.format(),
        Some(
            ImageFormat::Jpeg
                | ImageFormat::Png
                | ImageFormat::WebP
                | ImageFormat::Gif
                | ImageFormat::Bmp
                | ImageFormat::Tiff
        )
    ) {
        return Err("Unsupported image preview format".into());
    }
    let mut limits = Limits::default();
    limits.max_image_width = Some(8192);
    limits.max_image_height = Some(8192);
    limits.max_alloc = Some(96 * 1024 * 1024);
    reader.limits(limits.clone());
    let mut decoder = reader
        .into_decoder()
        .map_err(|_| "Invalid or oversized image preview")?;
    limits
        .reserve(decoder.total_bytes())
        .map_err(|_| "Invalid or oversized image preview")?;
    decoder
        .set_limits(limits)
        .map_err(|_| "Invalid or oversized image preview")?;
    let orientation = decoder
        .orientation()
        .map_err(|_| "Invalid image orientation")?;
    let mut image =
        DynamicImage::from_decoder(decoder).map_err(|_| "Invalid or oversized image preview")?;
    image.apply_orientation(orientation);
    let image = DynamicImage::ImageRgba8(image.thumbnail(320, 320).to_rgba8());
    let mut encoded = Cursor::new(Vec::new());
    image
        .write_to(&mut encoded, ImageFormat::Png)
        .map_err(|_| "Could not encode image preview")?;
    let result = format!(
        "data:image/png;base64,{}",
        STANDARD.encode(encoded.into_inner())
    );
    if result.len() > MAX_PREVIEW_BYTES {
        return Err("Image preview exceeds size limit".into());
    }
    Ok(result)
}
