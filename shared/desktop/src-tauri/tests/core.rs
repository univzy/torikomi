use serde_json::{json, Value};
use torikomi_desktop::core::*;

fn fixture() -> Value {
    serde_json::from_str(include_str!("../../../../sync/fixtures/v1.json")).unwrap()
}

#[test]
fn direct_media_preserves_original_when_codecs_or_format_list_are_missing() {
    let format =
        json!({"format_id":"mp4", "ext":"mp4", "vcodec":null, "acodec":null, "filesize":4096});
    let formats = inspect_formats(&json!({"formats":[format.clone()]})).unwrap();
    assert_eq!(formats.len(), 1);
    assert_eq!(formats[0]["id"], "mp4");
    assert_eq!(formats[0]["kind"], "source");
    assert_eq!(inspect_formats(&format).unwrap()[0]["id"], "mp4");
    assert_eq!(
        inspect_formats(&json!({"format_id":"m4a", "ext":"m4a"})).unwrap()[0]["kind"],
        "audio"
    );
    assert!(inspect_formats(
        &json!({"formats":[{"format_id":"sb0", "ext":"mhtml", "vcodec":"none", "acodec":"none"}]})
    )
    .is_err());
    assert_eq!(
        inspect_formats(&json!({"format_id":"png", "ext":"png"})).unwrap()[0]["kind"],
        "image"
    );
}

#[test]
fn portable_backup_preserves_fixture_and_strips_private_fields() {
    let mut value = fixture();
    value["files"] = json!({"fixture-link":"C:/secret/media.mp4"});
    value["settings"]["value"]["password"] = json!("secret");
    value["entries"][0]["localPath"] = json!("/secret/media.mp4");
    assert_eq!(document(value).unwrap(), document(fixture()).unwrap());
}

#[test]
fn merge_keeps_tombstones_and_uses_device_id_for_ties() {
    let a = fixture();
    let mut b = a.clone();
    b["entries"][0]["deviceId"] = json!("z-device");
    b["entries"][0]["title"] = json!("new title");
    b["entries"][1]["updatedAt"] = json!(0);
    b["entries"][1]["deletedAt"] = json!(0);
    let merged = merge(a.clone(), b.clone()).unwrap();
    assert_eq!(merged["entries"][0]["title"], "new title");
    assert_ne!(merged["entries"][1]["deletedAt"], 0);
    assert_eq!(merged, merge(b, a).unwrap());
}

#[test]
fn invalid_backup_is_rejected_without_mutating_input() {
    let original = fixture();
    for url in [
        "file:///etc/passwd",
        "https://user:secret@example.org/v",
        "javascript:alert(1)",
    ] {
        let mut bad = original.clone();
        bad["entries"][0]["url"] = json!(url);
        assert!(document(bad).is_err());
    }
    let mut duplicate = original.clone();
    duplicate["entries"][1] = duplicate["entries"][0].clone();
    assert!(document(duplicate).is_err());
    let mut invalid = original.clone();
    invalid["settings"]["value"]["themeColor"] = json!("invalid-theme");
    assert!(document(invalid).is_err());
    assert_eq!(original, fixture());
}

#[test]
fn backup_missing_required_preferences_is_rejected() {
    for missing in ["notifications", "themeMode"] {
        let mut invalid = fixture();
        invalid["settings"]["value"]
            .as_object_mut()
            .unwrap()
            .remove(missing);
        assert!(
            document(invalid).is_err(),
            "missing {missing} must be rejected"
        );
    }
}

#[test]
fn inspector_keeps_every_playable_format_including_same_resolution() {
    let raw = json!({"formats":[
        {"format_id":"137","ext":"mp4","height":1080,"vcodec":"avc1","acodec":"none"},
        {"format_id":"248","ext":"webm","height":1080,"vcodec":"vp9","acodec":"none"},
        {"format_id":"18","ext":"mp4","height":360,"vcodec":"avc1","acodec":"aac"},
        {"format_id":"140","ext":"m4a","vcodec":"none","acodec":"aac","abr":128},
        {"format_id":"storyboard","ext":"mhtml","vcodec":"none","acodec":"none"}
    ]});
    let formats = inspect_formats(&raw).unwrap();
    assert_eq!(formats.len(), 4);
    assert_eq!(formats[0]["id"], "137");
    assert_eq!(formats[1]["id"], "248");
    assert_eq!(formats[2]["kind"], "muxed");
    assert_eq!(formats[3]["kind"], "audio");
    assert_eq!(
        format_selector(&formats, "137", Some("140")).unwrap(),
        "137+140"
    );
    assert_eq!(format_selector(&formats, "137", None).unwrap(), "137");
    assert!(format_selector(&formats, "best", None).is_err());
    assert!(format_selector(&formats, "137+140", None).is_err());
    assert!(format_selector(&formats, "--exec=evil", None).is_err());
    assert!(format_selector(&formats, "18", Some("140")).is_err());
}

#[test]
fn downloader_uses_exact_selection_and_literal_arguments_with_optional_audio_conversion() {
    let formats = vec![
        json!({"id":"140","kind":"audio"}),
        json!({"id":"137","kind":"video"}),
    ];
    let args = download_arguments(
        "https://example.org/?x=$(evil)&y=1",
        &formats,
        "140",
        None,
        "mp3",
        "C:/My Downloads",
        "C:/Tools/ffmpeg.exe",
    )
    .unwrap();
    assert!(args.windows(2).any(|a| a == ["-f", "140"]));
    assert!(args.windows(2).any(|a| a == ["--audio-format", "mp3"]));
    assert!(args
        .windows(2)
        .any(|a| a == ["--", "https://example.org/?x=$(evil)&y=1"]));
    assert!(!args.iter().any(|a| a.contains("best")));
    assert!(download_arguments(
        "https://example.org/",
        &formats,
        "137",
        None,
        "mp3",
        "/tmp",
        ""
    )
    .is_err());
    assert!(download_arguments(
        "https://example.org/",
        &formats,
        "140",
        None,
        "--exec=evil",
        "/tmp",
        ""
    )
    .is_err());
}

#[test]
fn restoring_preferences_is_a_new_local_revision_even_when_backup_is_older() {
    let local = fixture();
    let mut imported = fixture();
    imported["settings"]["updatedAt"] = json!(1);
    imported["settings"]["value"]["themeMode"] = json!("light");
    let restored =
        torikomi_desktop::storage::restore(local.clone(), imported, "local-device", 2).unwrap();
    assert_eq!(restored["settings"]["value"]["themeMode"], "light");
    assert_eq!(restored["settings"]["deviceId"], "local-device");
    assert!(
        restored["settings"]["updatedAt"].as_u64().unwrap()
            > local["entries"][1]["updatedAt"].as_u64().unwrap()
    );
    assert_eq!(local, fixture());
}

#[test]
fn backup_limits_and_bad_revisions_are_rejected() {
    use torikomi_desktop::core::{Document, MAX_BYTES};
    assert!(Document::parse(&vec![b' '; MAX_BYTES + 1]).is_err());
    let mut bad = fixture();
    bad["entries"] = json!(vec![bad["entries"][0].clone(); 5001]);
    assert!(document(bad).is_err());
    let mut bad = fixture();
    bad["entries"][0]["updatedAt"] = json!(9_007_199_254_740_992_u64);
    assert!(document(bad).is_err());
}

#[test]
fn photo_capacity_is_checked_before_download_without_changing_library() {
    use torikomi_desktop::storage::LocalState;
    let mut state = LocalState::fresh(&std::env::temp_dir());
    assert!(state
        .check_photo_capacity("https://example.org/album", 2)
        .is_ok());
    let entry = state
        .add_link("https://example.org/album", "Photo", "image")
        .unwrap();
    state.document.entries = vec![entry; 4999];
    assert!(state
        .check_photo_capacity("https://example.org/album", 2)
        .is_err());
    assert_eq!(state.document.entries.len(), 4999);
    let state = LocalState::fresh(&std::env::temp_dir());
    assert!(
        state
            .check_photo_capacity("https://example.org/album", 2000)
            .is_err(),
        "Reserve title bytes against the portable document limit before downloading"
    );
    assert!(state.document.entries.is_empty());
}

#[test]
fn legacy_migration_preserves_old_file_and_bad_restore_never_overwrites_current() {
    use torikomi_desktop::storage::LocalState;
    let base = std::env::temp_dir().join(format!("torikomi-test-{}", uuid::Uuid::new_v4()));
    std::fs::create_dir_all(&base).unwrap();
    let legacy = base.join("legacy.json");
    let current = base.join("new/library.json");
    let mut state = LocalState::fresh(&base);
    state.document = torikomi_desktop::core::Document::from_value(fixture()).unwrap();
    let mut legacy_state = serde_json::to_value(&state).unwrap();
    legacy_state.as_object_mut().unwrap().remove("gallery");
    legacy_state
        .as_object_mut()
        .unwrap()
        .remove("setupComplete");
    std::fs::write(&legacy, serde_json::to_vec(&legacy_state).unwrap()).unwrap();
    let old = std::fs::read(&legacy).unwrap();
    let migrated = LocalState::load(&current, Some(&legacy), &base).unwrap();
    assert_eq!(migrated.document.entries.len(), 2);
    assert_eq!(migrated.gallery, "");
    assert!(!migrated.setup_complete);
    let mut configured = migrated.clone();
    configured.gallery = base.join("gallery-dl").to_string_lossy().into_owned();
    configured.setup_complete = true;
    assert_eq!(
        serde_json::to_value(&configured.document).unwrap(),
        document(fixture()).unwrap(),
        "Engine setup is device-local and excluded from portable backup"
    );
    assert_eq!(std::fs::read(&legacy).unwrap(), old);
    let before = std::fs::read(&current).unwrap();
    let mut invalid = migrated.clone();
    invalid.document.entries[0].url = "file:///private".into();
    assert!(invalid.persist(&current).is_err());
    assert_eq!(std::fs::read(&current).unwrap(), before);
    std::fs::remove_dir_all(base).unwrap();
}
