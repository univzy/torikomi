use serde_json::{json, Value};
use sha2::{Digest, Sha256};
use std::{fs, path::PathBuf};
use torikomi_desktop::setup::{bundle_available, install_bundle};

struct Fixture {
    root: PathBuf,
    source: PathBuf,
    destination: PathBuf,
}

impl Fixture {
    fn new() -> Self {
        let root = std::env::temp_dir().join(format!("torikomi-setup-{}", uuid::Uuid::new_v4()));
        let source = root.join("bundle");
        fs::create_dir_all(&source).unwrap();
        let mut tools = serde_json::Map::new();
        for tool in ["yt-dlp", "gallery-dl", "ffmpeg", "ffprobe"] {
            let file = format!("{tool}{}", std::env::consts::EXE_SUFFIX);
            let bytes = format!("fixture executable for {tool}");
            fs::write(source.join(&file), bytes.as_bytes()).unwrap();
            tools.insert(tool.into(), json!({"file":file,"sha256":format!("{:x}",Sha256::digest(bytes.as_bytes())),"version":"1.0"}));
        }
        fs::write(source.join("manifest.json"), serde_json::to_vec(&json!({"schemaVersion":1,"platform":std::env::consts::OS,"arch":std::env::consts::ARCH,"tools":tools})).unwrap()).unwrap();
        Self {
            destination: root.join("installed"),
            root,
            source,
        }
    }

    fn edit(&self, edit: impl FnOnce(&mut Value)) {
        let path = self.source.join("manifest.json");
        let mut manifest = serde_json::from_slice(&fs::read(&path).unwrap()).unwrap();
        edit(&mut manifest);
        fs::write(path, serde_json::to_vec(&manifest).unwrap()).unwrap();
    }
}

impl Drop for Fixture {
    fn drop(&mut self) {
        let _ = fs::remove_dir_all(&self.root);
    }
}

#[test]
fn installs_and_reuses_verified_complete_bundle() {
    let fixture = Fixture::new();
    assert!(bundle_available(&fixture.source));
    let first = install_bundle(&fixture.source, &fixture.destination).unwrap();
    let second = install_bundle(&fixture.source, &fixture.destination).unwrap();
    assert_eq!(first.engine, second.engine);
    for path in [&first.engine, &first.gallery, &first.ffmpeg] {
        assert!(PathBuf::from(path).starts_with(fixture.destination.canonicalize().unwrap()));
        assert!(fs::metadata(path).unwrap().is_file());
    }
    assert_eq!(fs::read_dir(&fixture.destination).unwrap().count(), 1);
    let probe = PathBuf::from(&first.ffmpeg)
        .with_file_name(format!("ffprobe{}", std::env::consts::EXE_SUFFIX));
    assert!(probe.is_file());
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        assert_eq!(
            fs::metadata(&first.gallery).unwrap().permissions().mode() & 0o777,
            0o755
        );
    }
}

#[test]
fn damaged_new_bundle_does_not_change_previous_installation() {
    let fixture = Fixture::new();
    let previous = install_bundle(&fixture.source, &fixture.destination).unwrap();
    let previous_bytes = fs::read(&previous.gallery).unwrap();
    fixture.edit(|value| value["tools"]["gallery-dl"]["version"] = json!("2.0"));
    fs::write(
        fixture
            .source
            .join(format!("gallery-dl{}", std::env::consts::EXE_SUFFIX)),
        b"tampered",
    )
    .unwrap();
    assert!(install_bundle(&fixture.source, &fixture.destination).is_err());
    assert_eq!(fs::read(&previous.gallery).unwrap(), previous_bytes);
    assert_eq!(fs::read_dir(&fixture.destination).unwrap().count(), 1);
}

#[test]
fn rejects_traversal_wrong_host_missing_tools_and_invalid_hashes() {
    for edit in [
        |v: &mut Value| v["tools"]["gallery-dl"]["file"] = json!("../escaped"),
        |v: &mut Value| v["platform"] = json!("other-platform"),
        |v: &mut Value| v["arch"] = json!("other-arch"),
        |v: &mut Value| {
            v["tools"].as_object_mut().unwrap().remove("ffprobe");
        },
        |v: &mut Value| v["tools"]["ffmpeg"]["sha256"] = json!("z".repeat(64)),
    ] {
        let fixture = Fixture::new();
        fixture.edit(edit);
        assert!(!bundle_available(&fixture.source));
        assert!(install_bundle(&fixture.source, &fixture.destination).is_err());
        assert!(!fixture.destination.exists());
    }
}

#[test]
fn refuses_corrupted_existing_installation() {
    let fixture = Fixture::new();
    let installed = install_bundle(&fixture.source, &fixture.destination).unwrap();
    fs::write(&installed.engine, b"corrupted").unwrap();
    assert!(install_bundle(&fixture.source, &fixture.destination).is_err());
}

#[test]
fn rejects_oversized_manifest_before_creating_install_directory() {
    let fixture = Fixture::new();
    fs::write(fixture.source.join("manifest.json"), vec![b' '; 65537]).unwrap();
    assert!(!bundle_available(&fixture.source));
    assert!(install_bundle(&fixture.source, &fixture.destination).is_err());
    assert!(!fixture.destination.exists());
}

#[cfg(unix)]
#[test]
fn rejects_symbolic_link_executable() {
    use std::os::unix::fs::symlink;
    let fixture = Fixture::new();
    let gallery = fixture.source.join("gallery-dl");
    fs::rename(&gallery, fixture.root.join("outside")).unwrap();
    symlink(fixture.root.join("outside"), gallery).unwrap();
    assert!(install_bundle(&fixture.source, &fixture.destination).is_err());
}
