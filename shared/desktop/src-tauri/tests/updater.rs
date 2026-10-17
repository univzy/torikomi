use serde_json::{json, Value};
use torikomi_desktop::updater::{validate_manifest, version_compare, EngineUpdater, SelectedPaths};

fn manifest() -> Value {
    let mut tools = serde_json::Map::new();
    for name in ["yt-dlp", "gallery-dl", "ffmpeg", "ffprobe"] {
        tools.insert(
            name.into(),
            json!({"file":format!("{name}{}", std::env::consts::EXE_SUFFIX),
            "asset":format!("windows-x86_64-{name}.gz"), "version":"9.0.2", "sha256":"a".repeat(64),
            "size":100, "compressedSize":70,"compressedSha256":"b".repeat(64)}),
        );
    }
    json!({"schemaVersion":1,"platform":std::env::consts::OS,"arch":std::env::consts::ARCH,
        "minAppVersion":"2.0.0","minOsVersion":"10.0","revision":"engines-20261016-abcdef", "tools":tools,
        "sources":{"ffmpeg":"https://ffmpeg.org/releases/ffmpeg-9.0.2.tar.xz"},
        "notices":{"asset":"windows-x86_64-notices.json","sha256":"c".repeat(64),"size":100}})
}

#[test]
fn feed_rejects_wrong_platform_paths_versions_and_resource_limits() {
    assert!(validate_manifest(&manifest()).is_ok());
    for edit in [
        |v: &mut Value| v["platform"] = json!("other"),
        |v: &mut Value| v["tools"]["ffmpeg"]["file"] = json!("../ffmpeg"),
        |v: &mut Value| v["tools"]["ffmpeg"]["size"] = json!(536870913_u64),
        |v: &mut Value| v["tools"]["ffmpeg"]["sha256"] = json!("z".repeat(64)),
        |v: &mut Value| v["tools"]["ffprobe"]["version"] = json!("8.0.0"),
        |v: &mut Value| v["minAppVersion"] = json!("99.0.0"),
        |v: &mut Value| v["minOsVersion"] = json!("99.0.0"),
        |v: &mut Value| v["tools"]["ffmpeg"]["version"] = json!("9.1-rc1"),
        |v: &mut Value| v["notices"]["asset"] = json!("../../notice"),
    ] {
        let mut value = manifest();
        edit(&mut value);
        assert!(validate_manifest(&value).is_err(), "{value}");
    }
}

#[test]
fn stable_version_order_handles_calendar_and_semantic_versions() {
    assert!(version_compare("2026.10.2", "2026.08.19").unwrap().is_gt());
    assert!(version_compare("9.0.2", "6.1.1").unwrap().is_gt());
    assert!(version_compare("1.32.16", "1.32.16").unwrap().is_eq());
    assert!(version_compare("9.0.2-git", "9.0.2").is_err());
}

#[test]
fn local_preferences_default_enabled_and_never_treat_manual_paths_as_managed() {
    let root = std::env::temp_dir().join(format!("torikomi-update-test-{}", uuid::Uuid::new_v4()));
    let updater = EngineUpdater::new(root.clone()).unwrap();
    let selected = SelectedPaths {
        engine: "C:/custom/yt-dlp.exe".into(),
        gallery: String::new(),
        ffmpeg: String::new(),
    };
    assert!(updater.status(&selected).automatic);
    assert!(updater
        .status(&selected)
        .tools
        .values()
        .all(|tool| !tool.managed));
    updater.set_automatic(false).unwrap();
    assert!(
        !EngineUpdater::new(root.clone())
            .unwrap()
            .status(&selected)
            .automatic
    );
    std::fs::remove_dir_all(root).unwrap();
}
