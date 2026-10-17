use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::{
    collections::BTreeMap,
    fs::{self, File, OpenOptions},
    io::{Read, Write},
    path::{Path, PathBuf},
    time::{SystemTime, UNIX_EPOCH},
};

const TOOLS: [&str; 4] = ["yt-dlp", "gallery-dl", "ffmpeg", "ffprobe"];
const MANIFEST_LIMIT: u64 = 64 * 1024;
const EXECUTABLE_LIMIT: u64 = 512 * 1024 * 1024;

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct Manifest {
    schema_version: u32,
    platform: String,
    arch: String,
    tools: BTreeMap<String, Tool>,
}

#[derive(Deserialize)]
struct Tool {
    file: String,
    sha256: String,
    version: String,
}

#[derive(Debug)]
pub struct InstalledEngines {
    pub engine: String,
    pub gallery: String,
    pub ffmpeg: String,
}

#[derive(Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub(crate) struct DownloadTool {
    pub file: String,
    pub version: String,
    pub url: String,
    pub format: String,
    pub sha256: String,
    pub size: u64,
    pub download_sha256: String,
    pub download_size: u64,
}

#[derive(Clone, Deserialize, Serialize)]
#[serde(deny_unknown_fields)]
pub(crate) struct DownloadTarget {
    pub platform: String,
    pub arch: String,
    pub tools: BTreeMap<String, DownloadTool>,
    pub notices: String,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct Downloads {
    schema_version: u32,
    targets: Vec<DownloadTarget>,
}

pub fn download_available() -> bool {
    static AVAILABLE: std::sync::OnceLock<bool> = std::sync::OnceLock::new();
    *AVAILABLE.get_or_init(|| {
        download_target()
            .and_then(|target| crate::updater::validate_download_target(&target))
            .is_ok()
    })
}

pub(crate) fn download_target() -> Result<DownloadTarget, String> {
    let manifest: Downloads = serde_json::from_str(include_str!("../bootstrap-engines.json"))
        .map_err(|e| format!("Invalid built-in engine download information: {e}"))?;
    if manifest.schema_version != 1 {
        return Err("Unsupported built-in engine download information".into());
    }
    manifest.targets.into_iter().find(|target| {
        target.platform == std::env::consts::OS && target.arch == std::env::consts::ARCH
    }).ok_or_else(|| "Automatic engine download is unavailable for this platform; select installed engines manually".into())
}

pub fn bundle_available(resource_root: &Path) -> bool {
    manifest(resource_root).is_ok()
}

pub fn install_bundle(
    resource_root: &Path,
    destination: &Path,
) -> Result<InstalledEngines, String> {
    let (manifest, digest) = manifest(resource_root)?;
    if !destination.is_absolute() {
        return Err("The engine installation directory must be absolute".into());
    }
    if destination.exists() {
        directory(destination)?;
    }
    fs::create_dir_all(destination).map_err(|error| error.to_string())?;
    directory(destination)?;
    let destination = destination
        .canonicalize()
        .map_err(|error| error.to_string())?;
    let installed = destination.join(digest);
    if installed.exists() {
        verify_installation(&installed, &manifest)?;
        return installed_paths(&installed, &manifest);
    }

    let staging = new_staging_directory(&destination)?;
    let result = (|| {
        for tool in manifest.tools.values() {
            let source = resource_root.join(&tool.file);
            let output = staging.join(&tool.file);
            let mut destination = OpenOptions::new()
                .write(true)
                .create_new(true)
                .open(&output)
                .map_err(|error| error.to_string())?;
            verify_file(&source, &tool.sha256, Some(&mut destination))?;
            destination.sync_all().map_err(|error| error.to_string())?;
            #[cfg(unix)]
            {
                use std::os::unix::fs::PermissionsExt;
                fs::set_permissions(&output, fs::Permissions::from_mode(0o755))
                    .map_err(|error| error.to_string())?;
            }
        }
        if let Err(error) = fs::rename(&staging, &installed) {
            if !installed.exists() {
                return Err(error.to_string());
            }
            // Another setup may have installed this same verified bundle concurrently.
            verify_installation(&installed, &manifest)?;
        }
        installed_paths(&installed, &manifest)
    })();
    if staging.exists() {
        let _ = fs::remove_dir_all(&staging);
    }
    result
}

fn manifest(root: &Path) -> Result<(Manifest, String), String> {
    directory(root)?;
    let path = root.join("manifest.json");
    regular_file(&path, MANIFEST_LIMIT)?;
    let mut bytes = Vec::new();
    File::open(path)
        .map_err(|error| error.to_string())?
        .take(MANIFEST_LIMIT + 1)
        .read_to_end(&mut bytes)
        .map_err(|error| error.to_string())?;
    if bytes.len() as u64 > MANIFEST_LIMIT {
        return Err("The engine manifest exceeds the size limit".into());
    }
    let manifest: Manifest = serde_json::from_slice(&bytes).map_err(|error| error.to_string())?;
    if manifest.schema_version != 1
        || manifest.platform != std::env::consts::OS
        || manifest.arch != std::env::consts::ARCH
        || !matches!(manifest.platform.as_str(), "windows" | "macos")
        || !matches!(manifest.arch.as_str(), "x86_64" | "aarch64")
    {
        return Err("This engine bundle does not match the application platform".into());
    }
    if manifest.tools.len() != TOOLS.len() {
        return Err("The engine bundle must contain all four executables".into());
    }
    for name in TOOLS {
        let tool = manifest
            .tools
            .get(name)
            .ok_or("An engine is missing from the bundle")?;
        if tool.file != format!("{name}{}", std::env::consts::EXE_SUFFIX)
            || tool.sha256.len() != 64
            || !tool.sha256.bytes().all(|byte| byte.is_ascii_hexdigit())
            || tool.version.is_empty()
            || tool.version.len() > 128
            || tool.version.chars().any(char::is_control)
        {
            return Err(format!("Invalid engine manifest entry: {name}"));
        }
        regular_file(&root.join(&tool.file), EXECUTABLE_LIMIT)?;
    }
    Ok((manifest, format!("{:x}", Sha256::digest(&bytes))))
}

pub(crate) fn directory(path: &Path) -> Result<(), String> {
    let metadata = fs::symlink_metadata(path).map_err(|error| error.to_string())?;
    if linked(&metadata) || !metadata.is_dir() {
        return Err("The engine directory must be a regular directory".into());
    }
    Ok(())
}

pub(crate) fn regular_file(path: &Path, limit: u64) -> Result<(), String> {
    let metadata = fs::symlink_metadata(path).map_err(|error| error.to_string())?;
    if linked(&metadata) || !metadata.is_file() || metadata.len() == 0 || metadata.len() > limit {
        return Err(format!(
            "Invalid or oversized engine file: {}",
            path.display()
        ));
    }
    Ok(())
}

fn linked(metadata: &fs::Metadata) -> bool {
    #[cfg(windows)]
    {
        use std::os::windows::fs::MetadataExt;
        metadata.file_attributes() & 0x400 != 0
    }
    #[cfg(not(windows))]
    {
        metadata.file_type().is_symlink()
    }
}

pub(crate) fn verify_file(
    path: &Path,
    expected_hash: &str,
    mut copy: Option<&mut File>,
) -> Result<(), String> {
    regular_file(path, EXECUTABLE_LIMIT)?;
    let mut source = File::open(path).map_err(|error| error.to_string())?;
    let mut digest = Sha256::new();
    let mut buffer = [0_u8; 64 * 1024];
    let mut total = 0_u64;
    loop {
        let count = source
            .read(&mut buffer)
            .map_err(|error| error.to_string())?;
        if count == 0 {
            break;
        }
        total += count as u64;
        if total > EXECUTABLE_LIMIT {
            return Err("The engine executable exceeds the size limit".into());
        }
        digest.update(&buffer[..count]);
        if let Some(output) = copy.as_deref_mut() {
            output
                .write_all(&buffer[..count])
                .map_err(|error| error.to_string())?;
        }
    }
    if total == 0 || !format!("{:x}", digest.finalize()).eq_ignore_ascii_case(expected_hash) {
        return Err(format!(
            "Engine SHA256 verification failed: {}",
            path.display()
        ));
    }
    Ok(())
}

fn verify_installation(path: &Path, manifest: &Manifest) -> Result<(), String> {
    directory(path)?;
    for tool in manifest.tools.values() {
        verify_file(&path.join(&tool.file), &tool.sha256, None)?;
    }
    Ok(())
}

fn installed_paths(path: &Path, manifest: &Manifest) -> Result<InstalledEngines, String> {
    let tool_path = |name: &str| {
        path.join(&manifest.tools[name].file)
            .to_str()
            .map(str::to_owned)
            .ok_or_else(|| "The engine path is not valid Unicode".to_string())
    };
    Ok(InstalledEngines {
        engine: tool_path("yt-dlp")?,
        gallery: tool_path("gallery-dl")?,
        ffmpeg: tool_path("ffmpeg")?,
    })
}

pub(crate) fn new_staging_directory(destination: &Path) -> Result<PathBuf, String> {
    staging_directory(destination, "setup")
}

pub(crate) fn new_update_staging_directory(destination: &Path) -> Result<PathBuf, String> {
    staging_directory(destination, "update")
}

fn staging_directory(destination: &Path, kind: &str) -> Result<PathBuf, String> {
    let stamp = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map_err(|error| error.to_string())?
        .as_nanos();
    for attempt in 0..10 {
        let path = destination.join(format!(".{kind}-{}-{stamp}-{attempt}", std::process::id()));
        match fs::create_dir(&path) {
            Ok(()) => return Ok(path),
            Err(error) if error.kind() == std::io::ErrorKind::AlreadyExists => continue,
            Err(error) => return Err(error.to_string()),
        }
    }
    Err("Could not create a temporary engine directory".into())
}
