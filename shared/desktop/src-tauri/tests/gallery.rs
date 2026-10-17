use serde_json::json;
use std::fs;
use torikomi_desktop::gallery::{
    download_args, inspect_args, parse_inspection, parse_output_files, photo_ranges,
};

#[test]
fn inspection_preserves_album_positions_and_distinguishes_videos() {
    let raw = r#"[
      [2,{"title":"Summer album"}],
      [3,"https://cdn.example/one.JPG",{"extension":"jpg","filename":"one","width":1200,"height":800}],
      [3,"https://cdn.example/clip.mp4",{"extension":"mp4"}],
      [3,"https://cdn.example/one.JPG",{"extension":"jpg","filename":"duplicate"}],
      [3,"https://cdn.example/three.avif",{"extension":"avif","title":"Third photo"}]
    ]"#;
    let entries = parse_inspection(raw).unwrap();
    assert_eq!(entries.len(), 4);
    assert_eq!(entries[0]["imageIndex"], 1);
    assert_eq!(entries[0]["title"], "Summer album");
    assert_eq!(entries[0]["width"], 1200);
    assert_eq!(entries[1]["kind"], "video");
    assert_eq!(entries[2]["imageIndex"], 3);
    assert_ne!(entries[0]["id"], entries[2]["id"]);
    assert_eq!(entries[3]["title"], "Third photo");
    assert_eq!(entries[3]["ext"], "avif");
}

#[test]
fn tiktok_album_background_audio_is_not_a_video_or_a_selectable_photo() {
    for (url, ext) in [
        ("https://cdn.example/music.mp4", "mp4"),
        ("ytdl:https://www.tiktok.com/@user/photo/123", "mp3"),
    ] {
        let items = parse_inspection(
            &json!([
                [2,{"post_type":"image"}],
                [3,"https://cdn.example/one.jpeg",{"extension":"jpeg","type":"image"}],
                [3,url,{"extension":ext,"type":"audio"}],
                [3,"https://cdn.example/two.jpeg",{"extension":"jpeg","type":"image"}]
            ])
            .to_string(),
        )
        .unwrap();
        assert_eq!(items[1]["kind"], "audio");
        assert!(!items.iter().any(|item| item["kind"] == "video"));
        assert_eq!(photo_ranges(&items, &[1, 3]).unwrap(), vec![1, 2]);
        assert!(photo_ranges(&items, &[2]).is_err());
    }
}

#[test]
fn gallery_failures_are_not_silently_reported_as_empty_albums() {
    let denied = "[tiktok][error] Failed to extract post (HttpError: '403 Forbidden')";
    assert!(
        torikomi_desktop::gallery::parse_inspection_output("[]", denied)
            .unwrap_err()
            .contains("403 Forbidden")
    );
    let valid = r#"[[3,"https://cdn.example/one.jpg",{"extension":"jpg"}]]"#;
    assert!(torikomi_desktop::gallery::parse_inspection_output(valid, denied).is_err());
    assert!(torikomi_desktop::gallery::parse_inspection_output(
        valid,
        "[test][warning] Optional audio missing"
    )
    .is_ok());
}

#[test]
fn delegated_dash_video_preserves_photos_and_absolute_carousel_indices() {
    let items = parse_inspection(
        r#"[
        [3,"https://cdn.example/one.jpg",{"extension":"jpg"}],
        [3,"ytdl:https://cdn.example/dash-manifest",{"extension":"mp4"}],
        [3,"https://cdn.example/two.jpg",{"extension":"jpg"}]
    ]"#,
    )
    .unwrap();
    assert_eq!(items.len(), 3);
    assert_eq!(items[1]["kind"], "video");
    assert_eq!(items[1]["url"], "https://cdn.example/dash-manifest");
    assert_eq!(items[2]["imageIndex"], 3);
    assert_eq!(photo_ranges(&items, &[1, 3]).unwrap(), vec![1, 2]);
    assert!(photo_ranges(&items, &[2]).is_err());
}

#[test]
fn delegated_video_cannot_impersonate_a_photo_or_bypass_url_validation() {
    let items = parse_inspection(
        r#"[
        [3,"https://cdn.example/one.jpg",{"extension":"jpg"}],
        [3,"ytdl:https://cdn.example/misleading.jpg",{"extension":"jpg"}],
        [3,"https://cdn.example/two.jpg",{"extension":"jpg"}]
    ]"#,
    )
    .unwrap();
    assert_eq!(items[1]["kind"], "video");
    assert_eq!(photo_ranges(&items, &[1, 3]).unwrap(), vec![1, 3]);
    assert!(photo_ranges(&items, &[2]).is_err());
    for url in [
        "ytdl:file:///private.jpg",
        "ytdl:https://user:secret@cdn.example/v.mp4",
        "ytdl:javascript:alert(1)",
        "other:https://cdn.example/v.mp4",
    ] {
        let raw = json!([[3,"https://cdn.example/one.jpg",{"extension":"jpg"}], [3,url,{"extension":"mp4"}]]);
        assert!(
            parse_inspection(&raw.to_string()).is_err(),
            "accepted {url}"
        );
    }
}

#[test]
fn inspection_rejects_unsafe_urls_partial_errors_and_delegated_pages() {
    for raw in [
        r#"[[3,"file:///etc/passwd",{"extension":"jpg"}]]"#,
        r#"[[3,"https://user:secret@cdn.example/p.jpg",{"extension":"jpg"}]]"#,
        r#"[[6,"https://example.com/album",{}]]"#,
        r#"[[3,"https://example.com/p.jpg",{"extension":"jpg"}],[-1,{"error":"AuthRequired","message":"Log in"}]]"#,
        r#"[[3,"https://example.com/p.jpg",{"extension":"../jpg"}]]"#,
        r#"[[3,"https://example.com/p.jpg",[]]]"#,
        "[]",
        "{}",
        "not json",
    ] {
        assert!(parse_inspection(raw).is_err(), "accepted {raw}");
    }
}

#[test]
fn inspection_limits_output_and_retains_indices_across_unknown_media() {
    let raw = json!([[3,"https://example.com/a.bin",{"extension":"bin"}],
                    [3,"https://example.com/b.webp",{}]]);
    let entries = parse_inspection(&raw.to_string()).unwrap();
    assert_eq!(entries[0]["kind"], "unknown");
    assert_eq!(entries[1]["imageIndex"], 2);
    assert_eq!(entries[1]["ext"], "webp");
    let too_many = vec![json!([3,"https://example.com/a.jpg",{"extension":"jpg"}]); 5001];
    assert!(parse_inspection(&json!(too_many).to_string()).is_err());
    assert!(parse_inspection(&" ".repeat(32 * 1024 * 1024 + 1)).is_err());
}

#[test]
fn commands_use_literal_url_and_selected_album_range() {
    let source = "https://example.com/album?title=$(calc)&x=one";
    let args = inspect_args(source).unwrap();
    assert!(args.iter().any(|a| a == "--config-ignore"));
    assert!(args.iter().any(|a| a == "--dump-json"));
    assert_eq!(&args[args.len() - 2..], &["--", source]);
    let args = download_args(source, &[3, 1, 3], &std::env::temp_dir()).unwrap();
    let range = args.iter().position(|a| a == "--range").unwrap();
    assert_eq!(args[range + 1], "1,3");
    assert!(args.windows(2).any(|a| a == ["--Print", "after:{_path!j}"]));
    assert!(!args.iter().any(|a| a == "--print" || a == "--exec"));
    assert_eq!(&args[args.len() - 2..], &["--", source]);
    for selection in [&[][..], &[0][..], &[5001][..]] {
        assert!(download_args(source, selection, &std::env::temp_dir()).is_err());
    }
    for source in ["file:///a.jpg", "https://u:p@example.com/", "--exec=calc"] {
        assert!(inspect_args(source).is_err());
        assert!(download_args(source, &[1], &std::env::temp_dir()).is_err());
    }
}

#[test]
fn mixed_album_selection_maps_absolute_indices_to_photo_only_ranks() {
    let items = parse_inspection(
        r#"[
        [3,"https://example.com/one.jpg",{"extension":"jpg"}],
        [3,"https://example.com/video.mp4",{"extension":"mp4"}],
        [3,"https://example.com/one.jpg",{"extension":"jpg"}],
        [3,"https://example.com/third.avif",{"extension":"avif"}]
    ]"#,
    )
    .unwrap();
    assert_eq!(photo_ranges(&items, &[1, 3]).unwrap(), vec![1, 2]);
    assert_eq!(photo_ranges(&items, &[4, 1, 4]).unwrap(), vec![1, 3]);
    for selection in [&[][..], &[0][..], &[2][..], &[5][..]] {
        assert!(photo_ranges(&items, selection).is_err());
    }
    assert!(photo_ranges(&[json!({"kind":"image","imageIndex":1,"ext":"exe"})], &[1]).is_err());
    assert!(photo_ranges(
        &[
            json!({"kind":"image","imageIndex":1,"ext":"jpg"}),
            json!({"kind":"image","imageIndex":1,"ext":"png"})
        ],
        &[1]
    )
    .is_err());
}

#[test]
fn output_requires_existing_images_inside_destination_and_returns_every_file() {
    let root = std::env::temp_dir().join(format!("torikomi-gallery-{}", uuid::Uuid::new_v4()));
    fs::create_dir_all(root.join("nested")).unwrap();
    let one = root.join("one photo.jpg");
    let two = root.join("nested/two.webp");
    let other = root.with_extension("jpg");
    fs::write(&one, b"photo").unwrap();
    fs::write(&two, b"photo").unwrap();
    fs::write(&other, b"outside").unwrap();
    let output = format!("{}\n{}\n{}\n", json!(one), json!(two), json!(one));
    assert_eq!(
        parse_output_files(&output, &root).unwrap(),
        vec![one.canonicalize().unwrap(), two.canonicalize().unwrap()]
    );
    for output in [
        json!(other).to_string(),
        json!(root.join("missing.jpg")).to_string(),
        json!(root).to_string(),
        "\"../outside.jpg\"".into(),
        "not a path".into(),
        String::new(),
    ] {
        assert!(
            parse_output_files(&output, &root).is_err(),
            "accepted {output}"
        );
    }
    let executable = root.join("payload.exe");
    fs::write(&executable, b"payload").unwrap();
    assert!(parse_output_files(&json!(executable).to_string(), &root).is_err());
    fs::remove_file(other).unwrap();
    fs::remove_dir_all(root).unwrap();
}
