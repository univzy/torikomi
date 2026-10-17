use base64::{engine::general_purpose::STANDARD, Engine};
use image::{DynamicImage, GenericImageView, ImageFormat};
use std::{
    io::{Cursor, Read, Write},
    net::TcpListener,
    thread,
    time::Duration,
};
use torikomi_desktop::preview::{thumbnail, Previewer};

fn png(width: u32, height: u32) -> Vec<u8> {
    let mut data = Cursor::new(Vec::new());
    DynamicImage::new_rgba8(width, height)
        .write_to(&mut data, ImageFormat::Png)
        .unwrap();
    data.into_inner()
}

fn fixture(
    requests: usize,
    reply: impl Fn(usize, &str) -> Vec<u8> + Send + 'static,
) -> (String, thread::JoinHandle<()>) {
    let listener = TcpListener::bind("127.0.0.1:0").unwrap();
    let url = format!("http://{}/photo", listener.local_addr().unwrap());
    let worker = thread::spawn(move || {
        listener.set_nonblocking(true).unwrap();
        for index in 0..requests {
            let deadline = std::time::Instant::now() + Duration::from_secs(10);
            let mut stream = loop {
                match listener.accept() {
                    Ok((stream, _)) => break stream,
                    Err(error) if error.kind() == std::io::ErrorKind::WouldBlock => {
                        assert!(
                            std::time::Instant::now() < deadline,
                            "Missing fixture request"
                        );
                        thread::sleep(Duration::from_millis(5));
                    }
                    Err(error) => panic!("{error}"),
                }
            };
            stream.set_nonblocking(false).unwrap();
            stream
                .set_read_timeout(Some(Duration::from_secs(5)))
                .unwrap();
            let mut request = Vec::new();
            while !request.ends_with(b"\r\n\r\n") {
                let mut byte = [0];
                stream.read_exact(&mut byte).unwrap();
                request.push(byte[0]);
                assert!(request.len() < 16384);
            }
            let response = reply(index, &String::from_utf8(request).unwrap());
            // A size rejection can close the connection before the fixture finishes writing.
            let _ = stream.write_all(&response);
        }
    });
    (url, worker)
}

fn response(status: &str, headers: &str, body: &[u8]) -> Vec<u8> {
    let mut result =
        format!("HTTP/1.1 {status}\r\nConnection: close\r\n{headers}\r\n").into_bytes();
    result.extend_from_slice(body);
    result
}

fn fetch(url: &str, referer: &str) -> Result<String, String> {
    tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()
        .unwrap()
        .block_on(Previewer::new().unwrap().fetch(url, referer))
}

#[test]
fn thumbnail_resizes_with_aspect_ratio_and_outputs_bounded_png() {
    let result = thumbnail(&png(640, 320)).unwrap();
    assert!(result.len() <= 600 * 1024);
    let bytes = STANDARD
        .decode(result.strip_prefix("data:image/png;base64,").unwrap())
        .unwrap();
    assert_eq!(image::guess_format(&bytes).unwrap(), ImageFormat::Png);
    assert_eq!(
        image::load_from_memory(&bytes).unwrap().dimensions(),
        (320, 160)
    );
}

#[test]
fn thumbnail_honors_jpeg_exif_orientation() {
    let mut jpeg = Cursor::new(Vec::new());
    DynamicImage::new_rgb8(640, 320)
        .write_to(&mut jpeg, ImageFormat::Jpeg)
        .unwrap();
    let jpeg = jpeg.into_inner();
    let exif = b"Exif\0\0II\x2a\0\x08\0\0\0\x01\0\x12\x01\x03\0\x01\0\0\0\x06\0\0\0\0\0\0\0";
    let mut oriented = jpeg[..2].to_vec();
    oriented.extend_from_slice(b"\xff\xe1");
    oriented.extend_from_slice(&((exif.len() + 2) as u16).to_be_bytes());
    oriented.extend_from_slice(exif);
    oriented.extend_from_slice(&jpeg[2..]);
    let result = thumbnail(&oriented).unwrap();
    let bytes = STANDARD
        .decode(result.strip_prefix("data:image/png;base64,").unwrap())
        .unwrap();
    assert_eq!(
        image::load_from_memory(&bytes).unwrap().dimensions(),
        (160, 320)
    );
}

#[test]
fn thumbnail_rejects_non_images_oversized_sources_and_dimensions() {
    for bytes in [
        b"<html>not an image</html>".as_slice(),
        b"<svg xmlns='http://www.w3.org/2000/svg'/>",
        b"{\"image\":true}",
        b"\x89PNG\r\n\x1a\ninvalid",
    ] {
        assert!(thumbnail(bytes).is_err());
    }
    assert!(thumbnail(&vec![0; 12 * 1024 * 1024 + 1]).is_err());
    assert!(thumbnail(&png(8193, 1)).is_err());
    assert!(thumbnail(&png(1, 8193)).is_err());
    // A tiny BMP header can declare a decoded buffer above the allocation limit.
    let mut bmp = vec![0; 54];
    bmp[..2].copy_from_slice(b"BM");
    bmp[2..6].copy_from_slice(&54u32.to_le_bytes());
    bmp[10..14].copy_from_slice(&54u32.to_le_bytes());
    bmp[14..18].copy_from_slice(&40u32.to_le_bytes());
    bmp[18..22].copy_from_slice(&6000u32.to_le_bytes());
    bmp[22..26].copy_from_slice(&6000u32.to_le_bytes());
    bmp[26..28].copy_from_slice(&1u16.to_le_bytes());
    bmp[28..30].copy_from_slice(&24u16.to_le_bytes());
    assert!(thumbnail(&bmp).is_err());
}

#[test]
fn fetch_sends_album_referer_and_browser_user_agent_and_sniffs_image_content() {
    let (url, server) = fixture(1, |_, request| {
        let request = request.to_ascii_lowercase();
        assert!(request.contains("referer: https://example.org/album?id=1\r\n"));
        assert!(request.contains("user-agent: mozilla/5.0"));
        assert!(!request.contains("\r\ncookie:"));
        assert!(!request.contains("\r\nauthorization:"));
        response(
            "200 OK",
            "Content-Type: application/octet-stream\r\n",
            &png(640, 320),
        )
    });
    assert!(fetch(&url, "https://example.org/album?id=1")
        .unwrap()
        .starts_with("data:image/png;base64,"));
    server.join().unwrap();
}

#[test]
fn fetch_rejects_bad_urls_and_referers_without_network_requests() {
    for invalid in [
        "file:///photo.png",
        "https://user:secret@example.org/a",
        "https://example.org/a\n",
    ] {
        assert!(fetch(invalid, "https://example.org/album").is_err());
        assert!(fetch("http://127.0.0.1:1/photo", invalid).is_err());
    }
}

#[test]
fn fetch_rejects_http_errors_and_mislabeled_html() {
    for status in ["403 Forbidden", "200 OK"] {
        let (url, server) = fixture(1, move |_, _| {
            response(
                status,
                "Content-Type: image/png\r\n",
                b"<html>forbidden</html>",
            )
        });
        assert!(fetch(&url, "https://example.org/album").is_err());
        server.join().unwrap();
    }
}

#[test]
fn fetch_bounds_both_declared_and_undeclared_response_sizes() {
    let (url, server) = fixture(1, |_, _| {
        response("200 OK", "Content-Length: 12582913\r\n", &[])
    });
    assert!(fetch(&url, "https://example.org/album")
        .unwrap_err()
        .contains("12 MiB"));
    server.join().unwrap();
    let (url, server) = fixture(1, |_, _| {
        response("200 OK", "", &vec![0; 12 * 1024 * 1024 + 1])
    });
    assert!(fetch(&url, "https://example.org/album")
        .unwrap_err()
        .contains("12 MiB"));
    server.join().unwrap();
}

#[test]
fn fetch_follows_safe_redirects_and_preserves_album_referer() {
    let (url, server) = fixture(2, |index, request| {
        assert!(request.contains("referer: https://example.org/album\r\n"));
        if index == 0 {
            response("302 Found", "Location: /redirected\r\n", &[])
        } else {
            assert!(request.starts_with("GET /redirected HTTP/1.1"));
            response("200 OK", "", &png(10, 20))
        }
    });
    assert!(fetch(&url, "https://example.org/album").is_ok());
    server.join().unwrap();
}

#[test]
fn fetch_rejects_unsafe_and_excessive_redirects() {
    for location in ["file:///photo.png", "http://user:secret@127.0.0.1/photo"] {
        let (url, server) = fixture(1, move |_, _| {
            response("302 Found", &format!("Location: {location}\r\n"), &[])
        });
        assert!(fetch(&url, "https://example.org/album").is_err());
        server.join().unwrap();
    }
    let (url, server) = fixture(6, |_, _| response("302 Found", "Location: /loop\r\n", &[]));
    assert!(fetch(&url, "https://example.org/album").is_err());
    server.join().unwrap();
}

#[test]
fn library_preview_only_accepts_active_local_image_and_video_entries() {
    use torikomi_desktop::storage::LocalState;
    let base =
        std::env::temp_dir().join(format!("torikomi-library-preview-{}", uuid::Uuid::new_v4()));
    std::fs::create_dir(&base).unwrap();
    let file = base.join("photo.png");
    std::fs::write(&file, png(20, 10)).unwrap();
    let mut local = LocalState::fresh(&base);
    let entry = local
        .add_link("https://example.org/album", "Photo", "image")
        .unwrap();
    assert!(local.preview_file(&entry.id).is_err());
    local
        .files
        .insert(entry.id.clone(), file.to_string_lossy().into_owned());
    assert_eq!(
        local.preview_file(&entry.id).unwrap(),
        (file.clone(), "image".into())
    );
    assert!(local.preview_file(&file.to_string_lossy()).is_err());
    let runtime = tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()
        .unwrap();
    let preview = runtime
        .block_on(Previewer::new().unwrap().local_media(&file, "image", ""))
        .unwrap();
    assert!(preview.starts_with("data:image/png;base64,"));
    local.document.entries[0].deleted_at = 1;
    assert!(local.preview_file(&entry.id).is_err());
    std::fs::remove_file(file).unwrap();
    std::fs::remove_dir(base).unwrap();
}

#[test]
fn video_preview_uses_literal_local_paths_and_one_scaled_frame() {
    let base = std::env::temp_dir();
    let input = base.join("video $(nothing) & one.mp4");
    let output = base.join("preview.png");
    let args = torikomi_desktop::preview::video_args(&input, &output).unwrap();
    assert!(args
        .windows(2)
        .any(|pair| pair == ["-i", input.to_str().unwrap()]));
    assert!(args.windows(2).any(|pair| pair == ["-frames:v", "1"]));
    assert!(args
        .windows(2)
        .any(|pair| pair == ["-protocol_whitelist", "file,pipe"]));
    assert!(args
        .iter()
        .any(|arg| arg == "scale=320:320:force_original_aspect_ratio=decrease"));
    assert!(
        torikomi_desktop::preview::video_args(std::path::Path::new("relative.mp4"), &output)
            .is_err()
    );
}

#[test]
fn audio_preview_only_extracts_embedded_cover() {
    let input = std::env::temp_dir().join("audio.mp3");
    let output = std::env::temp_dir().join("cover.png");
    let args = torikomi_desktop::preview::audio_args(&input, &output).unwrap();
    assert!(args.windows(2).any(|a| a == ["-map", "0:v:0"]));
    assert!(args.windows(2).any(|a| a == ["-frames:v", "1"]));
    assert!(args
        .windows(2)
        .any(|a| a == ["-protocol_whitelist", "file,pipe"]));
}

#[test]
fn retained_audio_artwork_is_available_without_an_installed_ffmpeg() {
    let base = std::env::temp_dir().join(format!("torikomi-sidecar-{}", uuid::Uuid::new_v4()));
    std::fs::create_dir(&base).unwrap();
    let input = base.join("track.wav");
    let cover = base.join("track.png");
    std::fs::write(&input, b"fixture").unwrap();
    std::fs::write(&cover, png(20, 10)).unwrap();
    let runtime = tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()
        .unwrap();
    assert!(runtime
        .block_on(Previewer::new().unwrap().local_media(&input, "audio", ""))
        .is_ok());
    std::fs::remove_file(input).unwrap();
    std::fs::remove_file(cover).unwrap();
    std::fs::remove_dir(base).unwrap();
}

#[test]
#[ignore = "requires TORIKOMI_FFMPEG pointing to a prepared engine"]
fn local_audio_cover_and_metadata_with_installed_ffmpeg() {
    let ffmpeg = std::env::var("TORIKOMI_FFMPEG").unwrap();
    let base =
        std::env::temp_dir().join(format!("torikomi-audio-preview-{}", uuid::Uuid::new_v4()));
    std::fs::create_dir(&base).unwrap();
    let cover = base.join("cover.png");
    std::fs::write(&cover, png(640, 320)).unwrap();
    let runtime = tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()
        .unwrap();
    for ext in ["mp3", "m4a", "flac"] {
        let input = base.join(format!("track.{ext}"));
        let codec = match ext {
            "mp3" => "libmp3lame",
            "m4a" => "aac",
            _ => "flac",
        };
        let result = std::process::Command::new(&ffmpeg)
            .args([
                "-hide_banner",
                "-loglevel",
                "error",
                "-nostdin",
                "-f",
                "lavfi",
                "-i",
                "sine=duration=0.2",
                "-i",
            ])
            .arg(&cover)
            .args([
                "-map",
                "0:a",
                "-map",
                "1:v",
                "-c:a",
                codec,
                "-c:v",
                "copy",
                "-disposition:v",
                "attached_pic",
                "-metadata",
                "artist=Fixture Artist",
                "-metadata",
                "album=Fixture Album",
                "-metadata",
                "title=Fixture Track",
            ])
            .arg(&input)
            .output()
            .unwrap();
        assert!(
            result.status.success(),
            "{}",
            String::from_utf8_lossy(&result.stderr)
        );
        let info = runtime
            .block_on(torikomi_desktop::media::file_info(&input, &ffmpeg))
            .unwrap();
        assert_eq!(info["kind"], "audio");
        assert_eq!(info["metadata"]["artist"], "Fixture Artist");
        let result = runtime
            .block_on(
                Previewer::new()
                    .unwrap()
                    .local_media(&input, "audio", &ffmpeg),
            )
            .unwrap();
        let bytes = STANDARD
            .decode(result.strip_prefix("data:image/png;base64,").unwrap())
            .unwrap();
        assert_eq!(
            image::load_from_memory(&bytes).unwrap().dimensions(),
            (320, 160)
        );
        std::fs::remove_file(&input).unwrap();
    }
    std::fs::remove_file(&cover).unwrap();
    std::fs::remove_dir(&base).unwrap();
}

#[test]
#[ignore = "requires TORIKOMI_FFMPEG pointing to a prepared engine"]
fn local_video_preview_with_installed_ffmpeg() {
    let ffmpeg = std::env::var_os("TORIKOMI_FFMPEG")
        .expect("Set TORIKOMI_FFMPEG for the video preview integration test");
    let ffmpeg = std::path::PathBuf::from(ffmpeg);
    let base =
        std::env::temp_dir().join(format!("torikomi-video-preview-{}", uuid::Uuid::new_v4()));
    std::fs::create_dir(&base).unwrap();
    let input = base.join("clip.mp4");
    let output = std::process::Command::new(&ffmpeg)
        .args([
            "-hide_banner",
            "-loglevel",
            "error",
            "-nostdin",
            "-f",
            "lavfi",
            "-i",
            "color=c=purple:s=640x360:d=0.1",
            "-c:v",
            "mpeg4",
        ])
        .arg(&input)
        .output()
        .unwrap();
    assert!(
        output.status.success(),
        "{}",
        String::from_utf8_lossy(&output.stderr)
    );
    let runtime = tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()
        .unwrap();
    let result = runtime
        .block_on(
            Previewer::new()
                .unwrap()
                .local_media(&input, "video", ffmpeg.to_str().unwrap()),
        )
        .unwrap();
    let image = image::load_from_memory(
        &STANDARD
            .decode(result.strip_prefix("data:image/png;base64,").unwrap())
            .unwrap(),
    )
    .unwrap();
    assert_eq!(image.dimensions(), (320, 180));
    std::fs::remove_file(input).unwrap();
    std::fs::remove_dir(base).unwrap();
}
