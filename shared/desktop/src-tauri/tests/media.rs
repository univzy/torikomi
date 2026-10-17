use serde_json::json;
use torikomi_desktop::media;

#[test]
fn source_metadata_uses_real_track_fields_without_inventing_artist() {
    let (title, metadata) = media::source_metadata(
        &json!({"title":"Video", "track":" Song ", "artists":["A", "B"], "album":"Album", "release_year":2024,"track_number":2,"duration":42.5}),
    );
    assert_eq!(title, "Song");
    let metadata = metadata.unwrap();
    assert_eq!(metadata.artist.as_deref(), Some("A, B"));
    assert_eq!(metadata.year, Some(2024));
    assert_eq!(metadata.track_number, Some(2));
    let (title, metadata) =
        media::source_metadata(&json!({"title":"A - Song", "uploader":"Someone"}));
    assert_eq!(title, "A - Song");
    assert!(metadata.is_none());
}

#[test]
fn audio_postprocessing_preserves_container_and_overrides_uploader_fallback() {
    let formats = vec![
        json!({"id":"a", "kind":"audio", "ext":"aac"}),
        json!({"id":"v", "kind":"video","ext":"mp4"}),
    ];
    for output in ["mp3", "m4a", "flac", "opus"] {
        let args = media::audio_arguments(&formats, "a", output);
        assert!(args.iter().any(|a| a == "--embed-thumbnail"));
        assert!(args.iter().any(|a| a == "--embed-metadata"));
        assert!(args.iter().any(|a| a.contains("(?P<artist>.*)")));
        assert!(!args.iter().any(|a| a == "--ignore-errors"));
    }
    for output in ["original", "wav"] {
        let args = media::audio_arguments(&formats, "a", output);
        assert!(!args.iter().any(|a| a == "--embed-thumbnail"));
        assert!(args.iter().any(|a| a == "--write-thumbnail"));
    }
    assert!(media::audio_arguments(&formats, "v", "original").is_empty());
    assert!(media::direct_audio_arguments(
        &json!({"extractor_key":"Generic","ext":"mp3","track":"Known track"})
    )
    .is_empty());
    assert!(
        media::direct_audio_arguments(&json!({"extractor_key":"Youtube","ext":"m4a"})).is_empty()
    );
    assert!(
        media::direct_audio_arguments(&json!({"extractor_key":"Generic","ext":"mp4"})).is_empty()
    );
}

#[test]
fn media_ranges_seek_large_files_and_reject_malformed_ranges() {
    let path = std::env::temp_dir().join(format!("torikomi-range-{}.mp3", uuid::Uuid::new_v4()));
    std::fs::write(&path, b"0123456789").unwrap();
    let r = media::response(&path, "GET", Some("bytes=3-6")).unwrap();
    assert_eq!(r.status(), 206);
    assert_eq!(r.headers()["content-range"], "bytes 3-6/10");
    assert_eq!(r.body(), b"3456");
    assert_eq!(
        media::response(&path, "GET", Some("bytes=-3"))
            .unwrap()
            .body(),
        b"789"
    );
    let head = media::response(&path, "HEAD", None).unwrap();
    assert_eq!(head.headers()["content-length"], "10");
    assert!(head.body().is_empty());
    for range in [
        "bytes=10-",
        "bytes=4-2",
        "bytes=-0",
        "bytes=0-1,3-4",
        "bytes=wat-",
        "items=0-1",
        "bytes=18446744073709551616-",
    ] {
        let response = media::response(&path, "GET", Some(range)).unwrap();
        assert_eq!(response.status(), 416, "{range}");
        assert_eq!(response.headers()["content-range"], "bytes */10");
    }
    assert_eq!(media::response(&path, "POST", None).unwrap().status(), 405);
    let file = std::fs::OpenOptions::new().write(true).open(&path).unwrap();
    file.set_len(5 * 1024 * 1024 * 1024).unwrap();
    let response = media::response(&path, "GET", Some("bytes=4294967296-")).unwrap();
    assert_eq!(response.status(), 206);
    assert!(response.body().len() <= 2 * 1024 * 1024);
    drop(file);
    std::fs::remove_file(path).unwrap();
}

#[test]
fn original_images_use_bounded_complete_responses_instead_of_truncated_ranges() {
    let path = std::env::temp_dir().join(format!("torikomi-image-{}.bmp", uuid::Uuid::new_v4()));
    let mut encoded = std::io::Cursor::new(Vec::new());
    image::DynamicImage::new_rgb8(1024, 1024)
        .write_to(&mut encoded, image::ImageFormat::Bmp)
        .unwrap();
    let bytes = encoded.into_inner();
    assert!(bytes.len() > 2 * 1024 * 1024);
    std::fs::write(&path, &bytes).unwrap();
    let response = media::response(&path, "GET", None).unwrap();
    assert_eq!(response.status(), 200);
    assert_eq!(response.headers()["content-type"], "image/bmp");
    assert_eq!(
        response.headers()["content-length"],
        bytes.len().to_string()
    );
    assert_eq!(response.body(), &bytes);
    assert!(!response.headers().contains_key("content-range"));
    let ranged = media::response(&path, "GET", Some("bytes=0-")).unwrap();
    assert_eq!(ranged.status(), 206);
    assert_eq!(ranged.body().len(), 2 * 1024 * 1024);
    let file = std::fs::OpenOptions::new().write(true).open(&path).unwrap();
    file.set_len(32 * 1024 * 1024 + 1).unwrap();
    assert!(media::response(&path, "GET", None)
        .unwrap_err()
        .contains("32 MiB"));
    let head = media::response(&path, "HEAD", None).unwrap();
    assert_eq!(
        head.headers()["content-length"],
        (32 * 1024 * 1024 + 1_u64).to_string()
    );
    assert!(head.body().is_empty());
    drop(file);
    std::fs::remove_file(path).unwrap();
}

#[test]
fn probe_does_not_confuse_album_art_with_video() {
    let audio = json!({"streams":[{"codec_type":"audio"},{"codec_type":"video","disposition":{"attached_pic":1}}],"format":{"duration":"3.5","tags":{"artist":"Artist","album":"Album","title":"Track","track":"2/10","date":"2024-03-01"}}});
    let info = media::probe_info(&audio, "mp3").unwrap();
    assert_eq!(info["kind"], "audio");
    assert_eq!(info["metadata"]["artist"], "Artist");
    assert_eq!(info["metadata"]["trackNumber"], 2);
    assert_eq!(info["metadata"]["year"], 2024);
    assert_eq!(info["duration"], 3.5);
}

#[test]
fn compatible_audio_copy_is_local_bounded_and_never_overwrites_input() {
    let base = std::env::temp_dir();
    let input = base.join("unsupported audio.flac");
    let output = base.join("private-playback.part.mp3");
    let args = media::playback_arguments(&input, &output).unwrap();
    assert!(args
        .windows(2)
        .any(|a| a == ["-protocol_whitelist", "file,pipe"]));
    assert!(args.windows(2).any(|a| a == ["-c:a", "libmp3lame"]));
    assert!(args.windows(2).any(|a| a == ["-fs", "134217728"]));
    assert!(args.iter().any(|a| a == "-n"));
    assert!(media::playback_arguments(&input, &input).is_err());
}

#[test]
fn recovery_requires_completed_transfer_marker_and_a_file_inside_download_folder() {
    let base = std::env::temp_dir().join(format!("torikomi-recovery-{}", uuid::Uuid::new_v4()));
    std::fs::create_dir(&base).unwrap();
    let source = base.join("track.wav");
    let converted = base.join("track.mp3");
    std::fs::write(&converted, b"fixture").unwrap();
    let marker = format!(
        "torikomi-transfer:{}\n",
        serde_json::to_string(source.to_str().unwrap()).unwrap()
    );
    assert_eq!(
        media::recover_audio_path(&marker, "mp3", &base).unwrap(),
        converted.canonicalize().unwrap()
    );
    assert!(media::recover_audio_path("ERROR download failed", "mp3", &base).is_err());
    assert!(media::recover_audio_path(&marker, "wav", &base).is_err());
    let outside = std::env::temp_dir().join("private.mp3");
    let forged = format!(
        "torikomi-transfer:{}\n",
        serde_json::to_string(outside.to_str().unwrap()).unwrap()
    );
    assert!(media::recover_audio_path(&forged, "mp3", &base).is_err());
    let relative = "torikomi-transfer:\"../track.wav\"";
    assert!(media::recover_audio_path(relative, "mp3", &base).is_err());
    std::fs::remove_file(converted).unwrap();
    std::fs::remove_dir(base).unwrap();
}

#[test]
#[ignore = "requires TORIKOMI_FFMPEG and TORIKOMI_YTDLP prepared engines"]
fn ytdlp_embeds_real_tags_and_artwork_without_changing_requested_container() {
    let ffmpeg = std::env::var("TORIKOMI_FFMPEG").unwrap();
    let ytdlp = std::env::var("TORIKOMI_YTDLP").unwrap();
    let base = std::env::temp_dir().join(format!("torikomi-tags-{}", uuid::Uuid::new_v4()));
    std::fs::create_dir(&base).unwrap();
    let input = base.join("source.wav");
    let cover = base.join("source.png");
    let mut png = std::io::Cursor::new(Vec::new());
    image::DynamicImage::new_rgb8(64, 64)
        .write_to(&mut png, image::ImageFormat::Png)
        .unwrap();
    std::fs::write(&cover, png.into_inner()).unwrap();
    let result = std::process::Command::new(&ffmpeg)
        .args([
            "-hide_banner",
            "-loglevel",
            "error",
            "-nostdin",
            "-f",
            "lavfi",
            "-i",
            "sine=duration=0.3",
        ])
        .arg(&input)
        .output()
        .unwrap();
    assert!(result.status.success());
    let fixture = json!({"id":"fixture","extractor":"generic","extractor_key":"Generic","webpage_url":"https://example.org/fixture","url":url::Url::from_file_path(&input).unwrap().as_str(),"ext":"wav","format_id":"a","vcodec":"none","acodec":"pcm_s16le","title":"Source video title","track":"Fixture Track","artist":"Fixture Artist","album":"Fixture Album","album_artist":"Fixture Album Artist","genre":"Fixture Genre","track_number":2,"release_year":2024,"duration":0.3,"thumbnail":url::Url::from_file_path(&cover).unwrap().as_str()});
    let info = base.join("info.json");
    std::fs::write(&info, serde_json::to_vec(&fixture).unwrap()).unwrap();
    let runtime = tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()
        .unwrap();
    for ext in ["mp3", "m4a", "flac", "opus", "wav"] {
        let target = base.join(format!("out-{ext}.%(ext)s"));
        let mut args = vec![
            "--ignore-config".into(),
            "--enable-file-urls".into(),
            "--no-simulate".into(),
            "--load-info-json".into(),
            info.to_str().unwrap().into(),
            "--ffmpeg-location".into(),
            ffmpeg.clone(),
            "-o".into(),
            target.to_str().unwrap().into(),
            "-x".into(),
            "--audio-format".into(),
            ext.into(),
        ];
        args.extend(media::audio_arguments(
            &[json!({"id":"a","kind":"audio","ext":"wav"})],
            "a",
            ext,
        ));
        let result = std::process::Command::new(&ytdlp)
            .args(&args)
            .output()
            .unwrap();
        assert!(
            result.status.success(),
            "{ext}: {}",
            String::from_utf8_lossy(&result.stderr)
        );
        let output = base.join(format!("out-{ext}.{ext}"));
        assert!(output.is_file());
        let result = runtime
            .block_on(media::file_info(&output, &ffmpeg))
            .unwrap();
        assert_eq!(result["kind"], "audio", "{ext}");
        assert_eq!(result["title"], "Fixture Track", "{ext}");
        assert_eq!(result["metadata"]["artist"], "Fixture Artist", "{ext}");
        assert_eq!(result["metadata"]["album"], "Fixture Album", "{ext}");
        assert_eq!(result["metadata"]["year"], 2024, "{ext}");
        assert_eq!(result["hasArtwork"], ext != "wav", "embedded artwork {ext}");
        let preview = runtime
            .block_on(
                torikomi_desktop::preview::Previewer::new()
                    .unwrap()
                    .local_media(&output, "audio", &ffmpeg),
            )
            .unwrap();
        assert!(preview.starts_with("data:image/png;base64,"));
    }
    let raw = base.join("source.aac");
    let result = std::process::Command::new(&ffmpeg)
        .args(["-hide_banner", "-loglevel", "error", "-nostdin", "-i"])
        .arg(&input)
        .args(["-c:a", "aac"])
        .arg(&raw)
        .output()
        .unwrap();
    assert!(result.status.success());
    let mut aac_fixture = fixture.clone();
    aac_fixture["url"] = json!(url::Url::from_file_path(&raw).unwrap().as_str());
    aac_fixture["ext"] = json!("aac");
    aac_fixture["acodec"] = json!("aac");
    std::fs::write(&info, serde_json::to_vec(&aac_fixture).unwrap()).unwrap();
    let mut args = vec![
        "--ignore-config".into(),
        "--enable-file-urls".into(),
        "--no-simulate".into(),
        "--load-info-json".into(),
        info.to_str().unwrap().into(),
        "--ffmpeg-location".into(),
        ffmpeg.clone(),
        "-o".into(),
        base.join("out-aac.%(ext)s").to_str().unwrap().into(),
    ];
    args.extend(media::audio_arguments(
        &[json!({"id":"a","kind":"audio","ext":"aac"})],
        "a",
        "original",
    ));
    let result = std::process::Command::new(&ytdlp)
        .args(&args)
        .output()
        .unwrap();
    assert!(
        result.status.success(),
        "aac: {}",
        String::from_utf8_lossy(&result.stderr)
    );
    let aac = base.join("out-aac.aac");
    assert!(aac.is_file());
    assert_eq!(
        runtime.block_on(media::file_info(&aac, &ffmpeg)).unwrap()["kind"],
        "audio"
    );
    assert!(runtime
        .block_on(
            torikomi_desktop::preview::Previewer::new()
                .unwrap()
                .local_media(&aac, "audio", &ffmpeg)
        )
        .is_ok());
    let cached = runtime
        .block_on(media::playback_copy(
            &base.join("out-flac.flac"),
            &base.join("playback"),
            &ffmpeg,
            std::sync::Arc::new(torikomi_desktop::process::Job::default()),
        ))
        .unwrap();
    assert!(cached.is_file());
    assert_eq!(
        runtime
            .block_on(media::file_info(&cached, &ffmpeg))
            .unwrap()["kind"],
        "audio"
    );
    let canceled = std::sync::Arc::new(torikomi_desktop::process::Job::default());
    canceled.cancel();
    assert!(runtime
        .block_on(media::playback_copy(
            &base.join("out-flac.flac"),
            &base.join("playback"),
            &ffmpeg,
            canceled
        ))
        .is_err());
    for entry in std::fs::read_dir(base.join("playback")).unwrap() {
        std::fs::remove_file(entry.unwrap().path()).unwrap();
    }
    std::fs::remove_dir(base.join("playback")).unwrap();
    for entry in std::fs::read_dir(&base).unwrap() {
        std::fs::remove_file(entry.unwrap().path()).unwrap();
    }
    std::fs::remove_dir(&base).unwrap();
}

#[test]
#[ignore = "requires TORIKOMI_FFMPEG and TORIKOMI_YTDLP prepared engines"]
fn direct_audio_keeps_embedded_tags_without_promoting_uploader_to_artist() {
    let ffmpeg = std::env::var("TORIKOMI_FFMPEG").unwrap();
    let ytdlp = std::env::var("TORIKOMI_YTDLP").unwrap();
    let base =
        std::env::temp_dir().join(format!("torikomi-existing-tags-{}", uuid::Uuid::new_v4()));
    std::fs::create_dir(&base).unwrap();
    let runtime = tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()
        .unwrap();
    for tagged in [true, false] {
        let source = base.join(format!("source-{tagged}.mp3"));
        let mut command = std::process::Command::new(&ffmpeg);
        command.args([
            "-hide_banner",
            "-loglevel",
            "error",
            "-nostdin",
            "-f",
            "lavfi",
            "-i",
            "sine=duration=0.2",
            "-c:a",
            "libmp3lame",
        ]);
        if tagged {
            command.args([
                "-metadata",
                "title=Existing Track",
                "-metadata",
                "artist=Existing Artist",
                "-metadata",
                "album=Existing Album",
                "-metadata",
                "album_artist=Existing Album Artist",
                "-metadata",
                "genre=Existing Genre",
                "-metadata",
                "date=2022",
                "-metadata",
                "track=3",
            ]);
        }
        assert!(command.arg(&source).output().unwrap().status.success());
        let fixture = json!({"id":"fixture","extractor":"generic","extractor_key":"Generic","webpage_url":"https://example.org/direct.mp3","url":url::Url::from_file_path(&source).unwrap().as_str(),"ext":"mp3","format_id":"a","vcodec":"none","acodec":"mp3","title":"Direct file","uploader":"A hosting account","series":"A website series","categories":["Website category"],"upload_date":"20251016"});
        let info = base.join("info.json");
        std::fs::write(&info, serde_json::to_vec(&fixture).unwrap()).unwrap();
        let target = base.join(format!("output-{tagged}.mp3"));
        let mut args = vec![
            "--ignore-config".into(),
            "--enable-file-urls".into(),
            "--no-simulate".into(),
            "--load-info-json".into(),
            info.to_str().unwrap().into(),
            "--ffmpeg-location".into(),
            ffmpeg.clone(),
            "-o".into(),
            target.to_str().unwrap().into(),
        ];
        args.extend(media::audio_arguments(
            &[json!({"id":"a","kind":"audio","ext":"mp3"})],
            "a",
            "original",
        ));
        args.extend(media::direct_audio_arguments(&fixture));
        let result = std::process::Command::new(&ytdlp)
            .args(&args)
            .output()
            .unwrap();
        assert!(
            result.status.success(),
            "{}",
            String::from_utf8_lossy(&result.stderr)
        );
        let result = runtime
            .block_on(media::file_info(&target, &ffmpeg))
            .unwrap();
        if tagged {
            assert_eq!(result["title"], "Existing Track");
            assert_eq!(result["metadata"]["artist"], "Existing Artist");
            assert_eq!(result["metadata"]["album"], "Existing Album");
            assert_eq!(result["metadata"]["albumArtist"], "Existing Album Artist");
            assert_eq!(result["metadata"]["genre"], "Existing Genre");
            assert_eq!(result["metadata"]["year"], 2022);
            assert_eq!(result["metadata"]["trackNumber"], 3);
        } else {
            assert!(result["title"].is_null());
            assert!(result["metadata"]["artist"].is_null());
            assert!(result["metadata"]["album"].is_null());
            assert!(result["metadata"]["genre"].is_null());
            assert!(result["metadata"]["year"].is_null());
        }
        std::fs::remove_file(source).unwrap();
        std::fs::remove_file(target).unwrap();
    }
    std::fs::remove_file(base.join("info.json")).unwrap();
    std::fs::remove_dir(base).unwrap();
}
