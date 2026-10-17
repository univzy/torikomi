use crate::core::MediaMetadata;
use serde_json::{json, Value};
use std::{
    fs::File,
    io::{Read, Seek, SeekFrom},
    path::{Path, PathBuf},
    sync::Arc,
    time::Duration,
};
use tauri::http::Response;
use tokio::sync::Semaphore;

const MAX_RESPONSE: u64 = 2 * 1024 * 1024;
const MAX_IMAGE_RESPONSE: u64 = 32 * 1024 * 1024;
static READS: Semaphore = Semaphore::const_new(6);
static IMAGE_READS: Semaphore = Semaphore::const_new(2);
static PROBES: Semaphore = Semaphore::const_new(3);
static COPIES: Semaphore = Semaphore::const_new(1);

fn text(value: &Value) -> Option<String> {
    let value = value.as_str()?.trim();
    if value.is_empty() || value.chars().any(char::is_control) {
        return None;
    }
    Some(value.chars().take(250).collect())
}

fn names(raw: &Value, single: &str, multiple: &str) -> Option<String> {
    text(&raw[single]).or_else(|| {
        let joined = raw[multiple]
            .as_array()?
            .iter()
            .filter_map(text)
            .collect::<Vec<_>>()
            .join(", ");
        text(&Value::String(joined))
    })
}

fn number(value: &Value) -> Option<u32> {
    let value = value
        .as_u64()
        .or_else(|| value.as_str()?.split('/').next()?.parse().ok())?;
    u32::try_from(value).ok().filter(|n| *n > 0)
}

pub fn source_metadata(raw: &Value) -> (String, Option<MediaMetadata>) {
    let title = text(&raw["track"])
        .or_else(|| text(&raw["title"]))
        .unwrap_or_else(|| "Untitled".into());
    let year = number(&raw["release_year"])
        .or_else(|| raw["release_date"].as_str()?.get(..4)?.parse().ok())
        .filter(|y| (1..=9999).contains(y));
    let metadata = MediaMetadata {
        artist: names(raw, "artist", "artists"),
        album: text(&raw["album"]),
        album_artist: names(raw, "album_artist", "album_artists"),
        track_number: number(&raw["track_number"]).filter(|n| *n <= 99999),
        genre: names(raw, "genre", "genres"),
        year,
        duration: raw["duration"]
            .as_f64()
            .filter(|n| n.is_finite() && *n >= 0.0 && *n <= 31_536_000.0),
    };
    let present = metadata.artist.is_some()
        || metadata.album.is_some()
        || metadata.album_artist.is_some()
        || metadata.track_number.is_some()
        || metadata.genre.is_some()
        || metadata.year.is_some()
        || metadata.duration.is_some();
    (title, present.then_some(metadata))
}

pub fn audio_arguments(formats: &[Value], id: &str, output: &str) -> Vec<String> {
    let Some(selected) = formats
        .iter()
        .find(|f| f["id"] == id && f["kind"] == "audio")
    else {
        return Vec::new();
    };
    let extension = if output == "original" {
        selected["ext"].as_str().unwrap_or("")
    } else {
        output
    };
    let mut args = vec![
        "--embed-metadata".into(),
        "--write-thumbnail".into(),
        "--convert-thumbnails".into(),
        "png".into(),
        "--print".into(),
        "post_process:torikomi-transfer:%(filepath)j".into(),
    ];
    // Empty source fields stop inferred fallbacks without clearing existing file tags.
    for mapping in [
        "%(artist,artists|)l:(?P<artist>.*)",
        "%(album|)s:(?P<album>.*)",
        "%(album_artist,album_artists|)l:(?P<album_artist>.*)",
        "%(genre,genres|)l:(?P<genre>.*)",
        "%(release_year,release_date|)s:(?P<upload_date>.*)",
    ] {
        args.extend(["--parse-metadata".into(), format!("post_process:{mapping}")]);
    }
    if matches!(extension, "mp3" | "m4a" | "flac" | "opus" | "ogg") {
        args.push("--embed-thumbnail".into());
    }
    args
}

pub fn direct_audio_arguments(raw: &Value) -> Vec<String> {
    let generic = raw["extractor_key"] == "Generic" || raw["extractor"] == "generic";
    if generic
        && text(&raw["track"]).is_none()
        && raw["ext"]
            .as_str()
            .and_then(mime)
            .is_some_and(|m| m.starts_with("audio/"))
    {
        // Run after filename selection so preserving embedded titles cannot rename the download.
        vec![
            "--parse-metadata".into(),
            "post_process:%(track|)s:(?P<title>.*)".into(),
        ]
    } else {
        Vec::new()
    }
}

pub fn recover_audio_path(
    output: &str,
    requested_output: &str,
    folder: &Path,
) -> Result<PathBuf, String> {
    if !["original", "mp3", "m4a", "flac", "opus", "wav"].contains(&requested_output)
        || !folder.is_absolute()
    {
        return Err("Invalid audio recovery request".into());
    }
    let mut markers = output
        .lines()
        .filter_map(|line| line.strip_prefix("torikomi-transfer:"));
    let marker = markers
        .next()
        .ok_or("No completed media transfer was reported")?;
    if markers.next().is_some() {
        return Err("Ambiguous completed transfer".into());
    }
    let source = PathBuf::from(
        serde_json::from_str::<String>(marker).map_err(|_| "Invalid completed transfer path")?,
    );
    if !source.is_absolute() {
        return Err("Invalid completed transfer path".into());
    }
    let root = folder
        .canonicalize()
        .map_err(|_| "Download folder is unavailable")?;
    let parent = source
        .parent()
        .ok_or("Invalid completed transfer path")?
        .canonicalize()
        .map_err(|_| "Download folder is unavailable")?;
    if !parent.starts_with(&root) {
        return Err("Completed transfer is outside the download folder".into());
    }
    let candidate = if requested_output == "original" {
        source.clone()
    } else {
        let converted = source.with_extension(requested_output);
        if converted.is_file() {
            converted
        } else {
            source.clone()
        }
    };
    extension(&candidate)?;
    if candidate
        .file_stem()
        .and_then(|s| s.to_str())
        .is_some_and(|s| s.ends_with(".part") || s.ends_with(".temp") || s.ends_with(".ytdl"))
    {
        return Err("Incomplete media cannot be recovered".into());
    }
    let candidate = candidate
        .canonicalize()
        .map_err(|_| "Completed transfer is unavailable")?;
    if !candidate.starts_with(&root) {
        return Err("Completed transfer is outside the download folder".into());
    }
    Ok(candidate)
}

pub fn mime(extension: &str) -> Option<&'static str> {
    Some(match extension {
        "mp3" => "audio/mpeg",
        "m4a" => "audio/mp4",
        "aac" => "audio/aac",
        "flac" => "audio/flac",
        "wav" => "audio/wav",
        "opus" => "audio/ogg",
        "ogg" | "oga" => "audio/ogg",
        "weba" => "audio/webm",
        "aiff" | "aif" => "audio/aiff",
        "wma" => "audio/x-ms-wma",
        "mp4" | "m4v" => "video/mp4",
        "webm" => "video/webm",
        "mkv" => "video/x-matroska",
        "mov" => "video/quicktime",
        "avi" => "video/x-msvideo",
        "ts" => "video/mp2t",
        "png" => "image/png",
        "jpg" | "jpeg" => "image/jpeg",
        "webp" => "image/webp",
        "gif" => "image/gif",
        "bmp" => "image/bmp",
        "tif" | "tiff" => "image/tiff",
        "heic" => "image/heic",
        "avif" => "image/avif",
        _ => return None,
    })
}

fn extension(path: &Path) -> Result<String, String> {
    if !path.is_absolute() || !path.is_file() {
        return Err("This library media file is unavailable".into());
    }
    let ext = path
        .extension()
        .and_then(|v| v.to_str())
        .unwrap_or("")
        .to_ascii_lowercase();
    mime(&ext).ok_or("Unsupported library media format")?;
    Ok(ext)
}

pub fn probe_info(raw: &Value, extension: &str) -> Result<Value, String> {
    let mime = mime(extension).ok_or("Unsupported library media format")?;
    let streams = raw["streams"]
        .as_array()
        .ok_or("No readable media streams")?;
    let video = streams.iter().any(|s| {
        s["codec_type"] == "video" && s["disposition"]["attached_pic"].as_u64().unwrap_or(0) == 0
    });
    let audio = streams.iter().any(|s| s["codec_type"] == "audio");
    let kind = if mime.starts_with("image/") {
        "image"
    } else if video {
        "video"
    } else if audio {
        "audio"
    } else {
        return Err("No readable media streams".into());
    };
    let mut tags = serde_json::Map::new();
    for source in std::iter::once(&raw["format"]["tags"]).chain(
        streams
            .iter()
            .filter(|s| s["codec_type"] == "audio")
            .map(|s| &s["tags"]),
    ) {
        if let Some(source) = source.as_object() {
            for (key, value) in source {
                tags.entry(key.to_ascii_lowercase())
                    .or_insert_with(|| value.clone());
            }
        }
    }
    let duration = raw["format"]["duration"]
        .as_str()
        .and_then(|s| s.parse::<f64>().ok())
        .or_else(|| raw["format"]["duration"].as_f64());
    let source = json!({"title":tags.get("title"),"artist":tags.get("artist"),"album":tags.get("album"),"album_artist":tags.get("album_artist").or_else(|| tags.get("albumartist")),"track_number":tags.get("track"),"genre":tags.get("genre"),"release_date":tags.get("date").or_else(|| tags.get("year")),"duration":duration});
    let (_, metadata) = source_metadata(&source);
    let has_artwork = streams.iter().any(|s| {
        s["codec_type"] == "video" && s["disposition"]["attached_pic"].as_u64() == Some(1)
    });
    Ok(
        json!({"kind":kind,"title":text(&source["title"]),"metadata":metadata,"duration":metadata.as_ref().and_then(|m|m.duration),"extension":extension,"mime":mime,"hasArtwork":has_artwork}),
    )
}

pub async fn file_info(path: &Path, ffmpeg: &str) -> Result<Value, String> {
    let ext = extension(path)?;
    if mime(&ext).is_some_and(|m| m.starts_with("image/")) {
        return Ok(
            json!({"kind":"image","title":null,"metadata":null,"duration":null,"extension":ext,"mime":mime(&ext)}),
        );
    }
    let engine = Path::new(ffmpeg);
    if !engine.is_absolute() || !engine.is_file() {
        return Err("Set up FFmpeg to read media information".into());
    }
    let probe = engine.with_file_name(if cfg!(windows) {
        "ffprobe.exe"
    } else {
        "ffprobe"
    });
    if !probe.is_file() {
        return Err("FFprobe is missing beside the selected FFmpeg".into());
    }
    let _permit = PROBES
        .acquire()
        .await
        .map_err(|_| "Media information is unavailable")?;
    let args = ["-v","error","-protocol_whitelist","file,pipe","-probesize","5242880","-analyzeduration","5000000","-show_entries","format=duration:format_tags=title,artist,album,album_artist,albumartist,track,genre,date,year:stream=codec_type,duration:stream_disposition=attached_pic:stream_tags=title,artist,album,album_artist,albumartist,track,genre,date,year","-of","json","-i",path.to_str().ok_or("Invalid media path")?].map(String::from).to_vec();
    let job = Arc::new(crate::process::Job::default());
    let output = match tokio::time::timeout(
        Duration::from_secs(15),
        crate::process::execute(
            probe.to_str().ok_or("Invalid FFprobe path")?,
            &args,
            job.clone(),
            256 * 1024,
            |_| {},
        ),
    )
    .await
    {
        Ok(result) => result?,
        Err(_) => {
            job.cancel();
            return Err("Media information timed out".into());
        }
    };
    probe_info(
        &serde_json::from_str::<Value>(&output)
            .map_err(|_| "FFprobe returned invalid media information")?,
        &ext,
    )
}

fn byte_range(value: &str, size: u64) -> Option<(u64, u64)> {
    let (start, end) = value.strip_prefix("bytes=")?.split_once('-')?;
    if size == 0 || (start.is_empty() && end.is_empty()) {
        return None;
    }
    let digits = |s: &str| !s.is_empty() && s.bytes().all(|c| c.is_ascii_digit());
    let (start, end) = if start.is_empty() {
        if !digits(end) {
            return None;
        }
        let suffix = end.parse::<u64>().ok()?;
        if suffix == 0 {
            return None;
        }
        (size.saturating_sub(suffix), size - 1)
    } else {
        if !digits(start) || (!end.is_empty() && !digits(end)) {
            return None;
        }
        (
            start.parse::<u64>().ok()?,
            if end.is_empty() {
                size - 1
            } else {
                end.parse::<u64>().ok()?.min(size - 1)
            },
        )
    };
    if start >= size || end < start {
        return None;
    }
    Some((start, end.min(start.saturating_add(MAX_RESPONSE - 1))))
}

pub fn response(
    path: &Path,
    method: &str,
    range: Option<&str>,
) -> Result<Response<Vec<u8>>, String> {
    let _permit = READS
        .try_acquire()
        .map_err(|_| "Too many simultaneous media reads")?;
    let ext = extension(path)?;
    let mut file = File::open(path).map_err(|e| e.to_string())?;
    let size = file.metadata().map_err(|e| e.to_string())?.len();
    // Image elements do not request subsequent ranges after a partial response.
    let full_image =
        method == "GET" && range.is_none() && mime(&ext).is_some_and(|m| m.starts_with("image/"));
    let _image_permit = if full_image {
        if size > MAX_IMAGE_RESPONSE {
            return Err("Image exceeds the 32 MiB viewer limit; open it in your system app".into());
        }
        Some(
            IMAGE_READS
                .try_acquire()
                .map_err(|_| "Too many simultaneous image reads")?,
        )
    } else {
        None
    };
    let base = || {
        Response::builder()
            .header("Accept-Ranges", "bytes")
            .header(
                "Content-Type",
                mime(&ext).unwrap_or("application/octet-stream"),
            )
            .header("X-Content-Type-Options", "nosniff")
            .header("Cache-Control", "no-store")
            .header("Access-Control-Allow-Origin", "*")
    };
    if !matches!(method, "GET" | "HEAD") {
        return base()
            .status(405)
            .header("Allow", "GET, HEAD")
            .header("Content-Length", "0")
            .body(Vec::new())
            .map_err(|e| e.to_string());
    }
    let bounds = match range {
        Some(value) => match byte_range(value, size) {
            Some(v) => Some(v),
            None => {
                return base()
                    .status(416)
                    .header("Content-Range", format!("bytes */{size}"))
                    .header("Content-Length", "0")
                    .body(Vec::new())
                    .map_err(|e| e.to_string())
            }
        },
        None if method == "GET" && !full_image && size > MAX_RESPONSE => {
            Some((0, MAX_RESPONSE - 1))
        }
        None => None,
    };
    let (start, length) = bounds.map(|(s, e)| (s, e - s + 1)).unwrap_or((0, size));
    let mut builder = base()
        .status(if bounds.is_some() { 206 } else { 200 })
        .header("Content-Length", length.to_string());
    if let Some((start, end)) = bounds {
        builder = builder.header("Content-Range", format!("bytes {start}-{end}/{size}"));
    }
    let mut body = Vec::new();
    if method == "GET" && length > 0 {
        file.seek(SeekFrom::Start(start))
            .map_err(|e| e.to_string())?;
        body.resize(length as usize, 0);
        file.read_exact(&mut body)
            .map_err(|_| "Media file changed while reading")?;
    }
    builder.body(body).map_err(|e| e.to_string())
}

pub fn playback_arguments(path: &Path, output: &Path) -> Result<Vec<String>, String> {
    if !path.is_absolute() || !output.is_absolute() || path == output {
        return Err("Invalid playback copy paths".into());
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
        path.to_str().ok_or("Invalid media path")?,
        "-map",
        "0:a:0",
        "-vn",
        "-map_metadata",
        "-1",
        "-c:a",
        "libmp3lame",
        "-b:a",
        "128k",
        "-fs",
        "134217728",
        "-f",
        "mp3",
        output.to_str().ok_or("Invalid playback copy path")?,
    ]
    .map(String::from)
    .to_vec())
}

struct IncompleteCopy(PathBuf);
impl Drop for IncompleteCopy {
    fn drop(&mut self) {
        let _ = std::fs::remove_file(&self.0);
    }
}

pub async fn playback_copy(
    path: &Path,
    cache: &Path,
    ffmpeg: &str,
    job: Arc<crate::process::Job>,
) -> Result<PathBuf, String> {
    use sha2::{Digest, Sha256};
    extension(path)?;
    if !cache.is_absolute() {
        return Err("Invalid private playback cache".into());
    }
    let _permit = COPIES
        .acquire()
        .await
        .map_err(|_| "Playback conversion is unavailable")?;
    if job.cancelled.load(std::sync::atomic::Ordering::SeqCst) {
        return Err("Operation cancelled".into());
    }
    let original = file_info(path, ffmpeg).await?;
    if original["kind"] != "audio" {
        return Err("Playback copies require an audio file".into());
    }
    let metadata = std::fs::metadata(path).map_err(|e| e.to_string())?;
    let identity = format!(
        "{}:{}:{:?}",
        path.canonicalize().map_err(|e| e.to_string())?.display(),
        metadata.len(),
        metadata.modified().map_err(|e| e.to_string())?
    );
    let key = format!("{:x}", Sha256::digest(identity.as_bytes()));
    std::fs::create_dir_all(cache).map_err(|e| e.to_string())?;
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        std::fs::set_permissions(cache, std::fs::Permissions::from_mode(0o700))
            .map_err(|e| e.to_string())?;
    }
    let target = cache.join(format!("{key}.mp3"));
    if target.is_file() {
        return Ok(target);
    }
    // This directory is app-owned; only our hash-named completed copies are evicted.
    let mut cached = Vec::new();
    for entry in std::fs::read_dir(cache).map_err(|e| e.to_string())? {
        let entry = entry.map_err(|e| e.to_string())?;
        let name = entry.file_name().to_string_lossy().into_owned();
        if name.len() == 68
            && name.ends_with(".mp3")
            && name[..64].bytes().all(|c| c.is_ascii_hexdigit())
        {
            let metadata = entry.metadata().map_err(|e| e.to_string())?;
            if metadata.is_file() {
                cached.push((
                    metadata.modified().map_err(|e| e.to_string())?,
                    metadata.len(),
                    entry.path(),
                ));
            }
        }
    }
    cached.sort_by_key(|(time, _, _)| *time);
    let mut total = cached.iter().map(|(_, len, _)| *len).sum::<u64>();
    for (_, length, path) in cached {
        if total <= 128 * 1024 * 1024 {
            break;
        }
        std::fs::remove_file(path).map_err(|e| e.to_string())?;
        total = total.saturating_sub(length);
    }
    let incomplete = IncompleteCopy(cache.join(format!("{key}-{}.part.mp3", uuid::Uuid::new_v4())));
    let args = playback_arguments(path, &incomplete.0)?;
    match tokio::time::timeout(
        Duration::from_secs(600),
        crate::process::execute(ffmpeg, &args, job.clone(), 64 * 1024, |_| {}),
    )
    .await
    {
        Ok(result) => {
            result?;
        }
        Err(_) => {
            job.cancel();
            return Err("Playback conversion timed out".into());
        }
    }
    if std::fs::metadata(&incomplete.0)
        .map_err(|e| e.to_string())?
        .len()
        >= 128 * 1024 * 1024
    {
        return Err("Playback copy exceeds the 128 MiB limit".into());
    }
    let generated = file_info(&incomplete.0, ffmpeg).await?;
    if let (Some(a), Some(b)) = (
        original["duration"].as_f64(),
        generated["duration"].as_f64(),
    ) {
        if (a - b).abs() > 2.0 {
            return Err("Playback conversion did not produce the complete track".into());
        }
    }
    if job.cancelled.load(std::sync::atomic::Ordering::SeqCst) {
        return Err("Operation cancelled".into());
    }
    let current = std::fs::metadata(path).map_err(|e| e.to_string())?;
    if metadata.len() != current.len() || metadata.modified().ok() != current.modified().ok() {
        return Err("Media file changed during conversion".into());
    }
    std::fs::rename(&incomplete.0, &target).map_err(|e| e.to_string())?;
    Ok(target)
}
