use crate::{process, setup, storage};
use flate2::bufread::GzDecoder;
use serde::{Deserialize, Serialize};
use serde_json::Value;
use sha2::{Digest, Sha256};
use std::{
    cmp::Ordering,
    collections::{BTreeMap, BTreeSet},
    fs::{self, File, OpenOptions},
    io::{BufReader, Read, Write},
    path::{Path, PathBuf},
    sync::{Arc, Mutex},
    time::Duration,
};

const TOOLS: [&str; 4] = ["yt-dlp", "gallery-dl", "ffmpeg", "ffprobe"];
const LIMIT: u64 = 512 * 1024 * 1024;
const METADATA_LIMIT: u64 = 2 * 1024 * 1024;
const DAY: u64 = 24 * 60 * 60 * 1000;
const REPOSITORY: &str = "univzy/torikomi";

#[derive(Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SetupProgress {
    pub tool: String,
    pub phase: String,
    pub received: u64,
    pub total: u64,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SelectedPaths {
    pub engine: String,
    pub gallery: String,
    pub ffmpeg: String,
}

impl SelectedPaths {
    fn path(&self, name: &str) -> PathBuf {
        match name {
            "yt-dlp" => PathBuf::from(&self.engine),
            "gallery-dl" => PathBuf::from(&self.gallery),
            "ffmpeg" => PathBuf::from(&self.ffmpeg),
            _ => Path::new(&self.ffmpeg)
                .with_file_name(format!("ffprobe{}", std::env::consts::EXE_SUFFIX)),
        }
    }
}

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ToolAsset {
    pub file: String,
    pub asset: String,
    pub version: String,
    pub sha256: String,
    pub size: u64,
    pub compressed_size: u64,
    pub compressed_sha256: String,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct NoticeAsset {
    pub asset: String,
    pub sha256: String,
    pub size: u64,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct FeedManifest {
    pub schema_version: u32,
    pub platform: String,
    pub arch: String,
    pub min_app_version: String,
    pub min_os_version: String,
    pub revision: String,
    pub tools: BTreeMap<String, ToolAsset>,
    pub sources: BTreeMap<String, String>,
    pub notices: NoticeAsset,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Candidate {
    pub paths: SelectedPaths,
    pub previous: SelectedPaths,
    pub revision: String,
    pub versions: BTreeMap<String, String>,
    hashes: BTreeMap<String, String>,
    manifest: FeedManifest,
}

#[derive(Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ToolStatus {
    pub version: Option<String>,
    pub managed: bool,
}

#[derive(Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct UpdateStatus {
    pub automatic: bool,
    pub last_checked: Option<u64>,
    pub state: String,
    pub error: Option<String>,
    pub tools: BTreeMap<String, ToolStatus>,
    pub revision: Option<String>,
}

#[derive(Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
struct LocalUpdates {
    automatic: bool,
    last_checked: Option<u64>,
    versions: BTreeMap<String, String>,
    selected: Option<SelectedPaths>,
    previous: Option<SelectedPaths>,
    candidate: Option<Candidate>,
    state: String,
    error: Option<String>,
    revision: Option<String>,
}
impl Default for LocalUpdates {
    fn default() -> Self {
        Self {
            automatic: true,
            last_checked: None,
            versions: BTreeMap::new(),
            selected: None,
            previous: None,
            candidate: None,
            state: "idle".into(),
            error: None,
            revision: None,
        }
    }
}

pub struct EngineUpdater {
    root: PathBuf,
    state: Mutex<LocalUpdates>,
    running: tokio::sync::Mutex<()>,
    client: reqwest::Client,
    #[cfg(test)]
    pub(crate) fixtures: Option<BTreeMap<String, Vec<u8>>>,
    #[cfg(test)]
    pub(crate) bootstrap_fixture: Option<Value>,
}

fn asset_name(name: &str) -> bool {
    !name.is_empty()
        && name.len() <= 160
        && !name.starts_with('.')
        && !name.contains("..")
        && name
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || b"._-".contains(&b))
}
fn hash(value: &str) -> bool {
    value.len() == 64 && value.bytes().all(|b| b.is_ascii_hexdigit())
}
fn version(value: &str) -> Result<Vec<u64>, String> {
    if value.is_empty() || value.len() > 64 {
        return Err("Invalid stable engine version".into());
    }
    value
        .split('.')
        .map(|part| {
            if part.is_empty() || !part.bytes().all(|b| b.is_ascii_digit()) {
                return Err("Only stable numeric engine versions are accepted".into());
            }
            part.parse()
                .map_err(|_| "Invalid stable engine version".into())
        })
        .collect()
}
pub fn version_compare(left: &str, right: &str) -> Result<Ordering, String> {
    let mut a = version(left)?;
    let mut b = version(right)?;
    let length = a.len().max(b.len());
    a.resize(length, 0);
    b.resize(length, 0);
    Ok(a.cmp(&b))
}
pub fn validate_manifest(value: &Value) -> Result<FeedManifest, String> {
    let manifest: FeedManifest =
        serde_json::from_value(value.clone()).map_err(|e| e.to_string())?;
    if manifest.schema_version != 1
        || manifest.platform != std::env::consts::OS
        || manifest.arch != std::env::consts::ARCH
        || !matches!(manifest.platform.as_str(), "windows" | "macos")
        || !matches!(manifest.arch.as_str(), "x86_64" | "aarch64")
        || version_compare(&manifest.min_app_version, env!("CARGO_PKG_VERSION"))?.is_gt()
        || version_compare(&manifest.min_os_version, &host_version()?)?.is_gt()
        || !manifest.revision.starts_with("engines-")
        || !asset_name(&manifest.revision)
    {
        return Err("Engine package is incompatible with this application".into());
    }
    if manifest.tools.len() != 4 {
        return Err("Engine package must contain four tools".into());
    }
    for name in TOOLS {
        let tool = manifest
            .tools
            .get(name)
            .ok_or("Engine missing from update package")?;
        version(&tool.version)?;
        if tool.file != format!("{name}{}", std::env::consts::EXE_SUFFIX)
            || !asset_name(&tool.asset)
            || !tool.asset.ends_with(".gz")
            || !hash(&tool.sha256)
            || !hash(&tool.compressed_sha256)
            || tool.size == 0
            || tool.size > LIMIT
            || tool.compressed_size == 0
            || tool.compressed_size > LIMIT
        {
            return Err(format!("Invalid update asset: {name}"));
        }
    }
    if manifest.tools["ffmpeg"].version != manifest.tools["ffprobe"].version {
        return Err("FFmpeg and FFprobe must have matching versions".into());
    }
    if !asset_name(&manifest.notices.asset)
        || !hash(&manifest.notices.sha256)
        || manifest.notices.size == 0
        || manifest.notices.size > METADATA_LIMIT
        || manifest.sources.is_empty()
        || manifest.sources.len() > 32
        || manifest.sources.values().any(|s| {
            url::Url::parse(s)
                .map(|u| {
                    u.scheme() != "https"
                        || u.host_str().is_none()
                        || !u.username().is_empty()
                        || u.password().is_some()
                })
                .unwrap_or(true)
        })
    {
        return Err("Invalid engine source or license information".into());
    }
    Ok(manifest)
}

fn host_version() -> Result<String, String> {
    #[cfg(windows)]
    {
        let value = windows_version::OsVersion::current();
        Ok(format!("{}.{}.{}", value.major, value.minor, value.build))
    }
    #[cfg(target_os = "macos")]
    {
        let output = std::process::Command::new("/usr/bin/sw_vers")
            .arg("-productVersion")
            .output()
            .map_err(|e| e.to_string())?;
        if !output.status.success() || output.stdout.len() > 128 {
            return Err("Cannot determine macOS version".into());
        }
        let value = String::from_utf8(output.stdout)
            .map_err(|e| e.to_string())?
            .trim()
            .to_string();
        version(&value)?;
        Ok(value)
    }
    #[cfg(not(any(windows, target_os = "macos")))]
    {
        Err("Unsupported engine update platform".into())
    }
}

fn trusted_redirect(url: &url::Url) -> bool {
    url.scheme() == "https"
        && url.port_or_known_default() == Some(443)
        && url.username().is_empty()
        && url.password().is_none()
        && matches!(
            url.host_str(),
            Some(
                "api.github.com"
                    | "github.com"
                    | "release-assets.githubusercontent.com"
                    | "objects.githubusercontent.com"
            )
        )
}

pub fn validate_asset_url(raw: &str, tag: &str, name: &str) -> Result<(), String> {
    let url = url::Url::parse(raw).map_err(|e| e.to_string())?;
    if !asset_name(tag)
        || !asset_name(name)
        || !trusted_redirect(&url)
        || url.host_str() != Some("github.com")
        || url.path() != format!("/{REPOSITORY}/releases/download/{tag}/{name}")
        || url.query().is_some()
        || url.fragment().is_some()
    {
        return Err("Engine asset URL is outside the pinned repository".into());
    }
    Ok(())
}

impl EngineUpdater {
    pub fn new(root: PathBuf) -> Result<Self, String> {
        if !root.is_absolute() {
            return Err("Updater data directory must be absolute".into());
        }
        fs::create_dir_all(root.join("engines")).map_err(|e| e.to_string())?;
        setup::directory(&root)?;
        setup::directory(&root.join("engines"))?;
        let root = root.canonicalize().map_err(|e| e.to_string())?;
        cleanup_staging(&root.join("engines"));
        let path = root.join("engine-updates.json");
        let mut state: LocalUpdates = if path.exists() {
            match storage::read_bounded(&path, METADATA_LIMIT as usize)
                .and_then(|bytes| serde_json::from_slice(&bytes).map_err(|e| e.to_string()))
            {
                Ok(value) => value,
                Err(error) => LocalUpdates {
                    automatic: false,
                    state: "error".into(),
                    error: Some(format!(
                        "Engine update preferences could not be read: {error}"
                    )),
                    ..LocalUpdates::default()
                },
            }
        } else {
            LocalUpdates::default()
        };
        // A process can exit during a transfer; only fully verified persisted candidates resume.
        if matches!(state.state.as_str(), "checking" | "downloading") {
            state.state = "idle".into();
            state.last_checked = None;
        }
        let client = reqwest::Client::builder()
            .user_agent("Torikomi-engine-updater/1")
            .connect_timeout(Duration::from_secs(20))
            .timeout(Duration::from_secs(600))
            .redirect(reqwest::redirect::Policy::custom(|attempt| {
                if attempt.previous().len() < 5 && trusted_redirect(attempt.url()) {
                    attempt.follow()
                } else {
                    attempt.error("Untrusted engine redirect")
                }
            }))
            .build()
            .map_err(|e| e.to_string())?;
        Ok(Self {
            root,
            state: Mutex::new(state),
            running: tokio::sync::Mutex::new(()),
            client,
            #[cfg(test)]
            fixtures: None,
            #[cfg(test)]
            bootstrap_fixture: None,
        })
    }
    fn save(&self, state: &LocalUpdates) -> Result<(), String> {
        storage::atomic_write(
            &self.root.join("engine-updates.json"),
            &serde_json::to_vec(state).map_err(|e| e.to_string())?,
        )
    }
    fn mutate(&self, change: impl FnOnce(&mut LocalUpdates)) -> Result<(), String> {
        let mut state = self.state.lock().map_err(|e| e.to_string())?;
        let mut next = state.clone();
        change(&mut next);
        self.save(&next)?;
        *state = next;
        Ok(())
    }
    fn managed(&self, path: &Path, name: &str) -> bool {
        let owned = self.root.join("engines");
        let Ok(relative) = path.strip_prefix(&owned) else {
            return false;
        };
        let parts: Vec<_> = relative.components().collect();
        if parts.len() != 2
            || !hash(&parts[0].as_os_str().to_string_lossy())
            || parts[1].as_os_str() != format!("{name}{}", std::env::consts::EXE_SUFFIX).as_str()
        {
            return false;
        }
        let Some(parent) = path.parent() else {
            return false;
        };
        setup::directory(parent).is_ok() && setup::regular_file(path, LIMIT).is_ok()
    }
    pub fn status(&self, selected: &SelectedPaths) -> UpdateStatus {
        let state = self.state.lock().unwrap_or_else(|p| p.into_inner());
        UpdateStatus {
            automatic: state.automatic,
            last_checked: state.last_checked,
            state: state.state.clone(),
            error: state.error.clone(),
            revision: state.revision.clone(),
            tools: TOOLS
                .into_iter()
                .map(|name| {
                    (
                        name.into(),
                        ToolStatus {
                            version: if state.selected.as_ref() == Some(selected) {
                                state.versions.get(name).cloned()
                            } else {
                                None
                            },
                            managed: self.managed(&selected.path(name), name),
                        },
                    )
                })
                .collect(),
        }
    }
    pub fn set_automatic(&self, enabled: bool) -> Result<(), String> {
        self.mutate(|state| state.automatic = enabled)
    }
    pub async fn refresh_versions(&self, selected: SelectedPaths) -> Result<(), String> {
        let mut versions = BTreeMap::new();
        for name in TOOLS {
            let path = selected.path(name);
            if path.is_file() {
                let managed = self.managed(&path, name);
                match self.probe(&path, name, managed).await {
                    Ok(version) => {
                        versions.insert(name.into(), version);
                    }
                    Err(error) if managed => return Err(error),
                    Err(_) => {}
                }
            }
        }
        self.mutate(|state| {
            state.selected = Some(selected);
            state.versions = versions;
        })
    }
    pub fn activated(&self, candidate: &Candidate) -> Result<(), String> {
        self.mutate(|state| {
            state.previous = Some(candidate.previous.clone());
            state.selected = Some(candidate.paths.clone());
            state.versions = candidate.versions.clone();
            state.revision = Some(candidate.revision.clone());
            state.candidate = None;
            state.state = "updated".into();
            state.error = None;
        })
    }
    pub fn discard_candidate(&self) -> Result<(), String> {
        self.mutate(|state| {
            state.candidate = None;
            state.state = "idle".into();
            state.last_checked = None;
        })
    }
    pub async fn check(
        &self,
        selected: SelectedPaths,
        manual: bool,
    ) -> Result<Option<Candidate>, String> {
        let _guard = self
            .running
            .try_lock()
            .map_err(|_| "An engine update check is already running".to_string())?;
        let snapshot = self.state.lock().map_err(|e| e.to_string())?.clone();
        if !manual && !snapshot.automatic {
            return Ok(None);
        }
        if let Some(candidate) = snapshot.candidate {
            if candidate.previous == selected {
                if let Err(error) = self.verify_candidate(&candidate).await {
                    self.mutate(|state| {
                        state.candidate = None;
                        state.state = "error".into();
                        state.error = Some(error.clone());
                        state.last_checked = None;
                    })?;
                    return Err(error);
                }
                return Ok(Some(candidate));
            }
            self.discard_candidate()?;
        }
        let now = storage::now();
        if !manual
            && snapshot.selected.as_ref() == Some(&selected)
            && snapshot
                .last_checked
                .is_some_and(|stamp| stamp <= now && now - stamp < DAY)
        {
            return Ok(None);
        }
        self.mutate(|state| {
            state.state = "checking".into();
            state.error = None;
            state.last_checked = Some(storage::now());
        })?;
        let result = self.check_inner(selected, manual).await;
        if let Err(error) = &result {
            let _ = self.mutate(|state| {
                state.state = "error".into();
                state.error = Some(error.clone());
            });
        }
        result
    }
    async fn metadata(&self, url: &str) -> Result<Vec<u8>, String> {
        #[cfg(test)]
        if let Some(fixtures) = &self.fixtures {
            let bytes = fixtures
                .get(url)
                .ok_or("Missing offline metadata fixture")?;
            if bytes.len() as u64 > METADATA_LIMIT {
                return Err("Engine feed exceeds size limit".into());
            }
            return Ok(bytes.clone());
        }
        let mut response = self
            .client
            .get(url)
            .send()
            .await
            .map_err(|e| format!("Engine feed could not be reached: {e}"))?
            .error_for_status()
            .map_err(|e| e.to_string())?;
        if response
            .content_length()
            .is_some_and(|n| n > METADATA_LIMIT)
        {
            return Err("Engine feed exceeds size limit".into());
        }
        let mut bytes = Vec::new();
        while let Some(chunk) = response.chunk().await.map_err(|e| e.to_string())? {
            if bytes.len() as u64 + chunk.len() as u64 > METADATA_LIMIT {
                return Err("Engine feed exceeds size limit".into());
            }
            bytes.extend_from_slice(&chunk);
        }
        Ok(bytes)
    }
    async fn probe(&self, path: &Path, name: &str, stable: bool) -> Result<String, String> {
        self.probe_with_job(path, name, stable, Arc::new(process::Job::default()))
            .await
    }
    async fn probe_with_job(
        &self,
        path: &Path,
        name: &str,
        stable: bool,
        job: Arc<process::Job>,
    ) -> Result<String, String> {
        let flag = if matches!(name, "ffmpeg" | "ffprobe") {
            "-version"
        } else {
            "--version"
        };
        let result = tokio::time::timeout(
            Duration::from_secs(45),
            process::execute(
                path.to_str().ok_or("Invalid engine path")?,
                &[flag.into()],
                job.clone(),
                64 * 1024,
                |_| {},
            ),
        )
        .await;
        let output = match result {
            Ok(value) => value?,
            Err(_) => {
                job.cancel();
                return Err(format!("{name} version probe timed out"));
            }
        };
        let line = output
            .lines()
            .next()
            .ok_or("Engine returned no version")?
            .trim();
        let value = if matches!(name, "ffmpeg" | "ffprobe") {
            line.strip_prefix(&format!("{name} version "))
                .and_then(|s| s.split_whitespace().next())
                .ok_or("Invalid FFmpeg version output")?
        } else {
            line
        };
        if value.len() > 128 || value.chars().any(char::is_control) {
            return Err("Invalid engine version output".into());
        }
        let value = if stable {
            // Stable vendor FFmpeg builds may append build options after the numeric release.
            let value = value.split('-').next().unwrap_or(value);
            version(value)?;
            value
        } else {
            value
        };
        Ok(value.into())
    }
    async fn check_inner(
        &self,
        selected: SelectedPaths,
        manual: bool,
    ) -> Result<Option<Candidate>, String> {
        let mut current = BTreeMap::new();
        let mut current_hashes = BTreeMap::new();
        let mut managed = BTreeMap::new();
        for name in TOOLS {
            let owned = self.managed(&selected.path(name), name);
            managed.insert(name, owned);
            if owned {
                current_hashes.insert(name.into(), file_hash(&selected.path(name))?);
            }
            if selected.path(name).is_file() {
                match self.probe(&selected.path(name), name, owned).await {
                    Ok(version) => {
                        current.insert(name.into(), version);
                    }
                    Err(error) if owned => return Err(error),
                    Err(_) => {}
                }
            }
        }
        self.mutate(|state| {
            state.selected = Some(selected.clone());
            state.versions = current.clone();
        })?;
        if !managed.values().any(|v| *v) {
            self.mutate(|s| s.state = "manual".into())?;
            return Ok(None);
        }
        let releases: Value = serde_json::from_slice(
            &self
                .metadata(&format!(
                    "https://api.github.com/repos/{REPOSITORY}/releases?per_page=100"
                ))
                .await?,
        )
        .map_err(|e| e.to_string())?;
        let releases = releases.as_array().ok_or("Invalid engine release list")?;
        let release = releases.iter().find(|r| {
            r["draft"] == false
                && r["prerelease"] == true
                && r["tag_name"]
                    .as_str()
                    .is_some_and(|s| s.starts_with("engines-"))
        });
        let Some(release) = release else {
            self.mutate(|s| s.state = "no_feed".into())?;
            return Ok(None);
        };
        let tag = release["tag_name"]
            .as_str()
            .ok_or("Missing engine release tag")?;
        let assets = release["assets"]
            .as_array()
            .ok_or("Missing engine release assets")?;
        if assets.len() > 64 {
            return Err("Too many engine release assets".into());
        }
        let asset_url = |name: &str| -> Result<String, String> {
            let matches: Vec<_> = assets.iter().filter(|a| a["name"] == name).collect();
            if matches.len() != 1 {
                return Err(format!("Missing or duplicate engine asset: {name}"));
            }
            let raw = matches[0]["browser_download_url"]
                .as_str()
                .ok_or("Missing engine download URL")?;
            validate_asset_url(raw, tag, name)?;
            Ok(raw.into())
        };
        let manifest_name = format!(
            "{}-{}-manifest.json",
            std::env::consts::OS,
            std::env::consts::ARCH
        );
        let manifest = validate_manifest(
            &serde_json::from_slice(&self.metadata(&asset_url(&manifest_name)?).await?)
                .map_err(|e| e.to_string())?,
        )?;
        if manifest.revision != tag {
            return Err("Engine manifest revision does not match release".into());
        }
        let mut changed = BTreeSet::new();
        for name in TOOLS {
            if managed[name] {
                match version_compare(
                    &manifest.tools[name].version,
                    current
                        .get(name)
                        .ok_or("Missing installed engine version")?,
                )? {
                    Ordering::Less => {
                        self.mutate(|s| s.state = "up_to_date".into())?;
                        return Ok(None);
                    }
                    Ordering::Greater => {
                        changed.insert(name);
                    }
                    Ordering::Equal
                        if current_hashes.get(name) != Some(&manifest.tools[name].sha256) =>
                    {
                        changed.insert(name);
                    }
                    _ => {}
                }
            }
        }
        if changed.is_empty() {
            self.mutate(|s| s.state = "up_to_date".into())?;
            return Ok(None);
        }
        if changed.contains("ffmpeg") || changed.contains("ffprobe") {
            if !managed["ffmpeg"] || !managed["ffprobe"] {
                return Err(
                    "Reinstall the managed FFmpeg and FFprobe pair before updating it".into(),
                );
            }
            changed.extend(["ffmpeg", "ffprobe"]);
        }
        if !manual && !self.state.lock().map_err(|e| e.to_string())?.automatic {
            self.mutate(|state| state.state = "idle".into())?;
            return Ok(None);
        }
        self.mutate(|s| s.state = "downloading".into())?;
        let staging = setup::new_update_staging_directory(&self.root.join("engines"))?;
        let result = async {
            let notices = self.metadata(&asset_url(&manifest.notices.asset)?).await?;
            if notices.len() as u64 != manifest.notices.size
                || format!("{:x}", Sha256::digest(&notices)) != manifest.notices.sha256
            {
                return Err("Engine license information failed verification".into());
            }
            fs::write(staging.join("notices.json"), notices).map_err(|e| e.to_string())?;
            let mut paths = selected.clone();
            let mut versions = current.clone();
            let mut hashes = current_hashes.clone();
            let mut changed_names = Vec::new();
            for name in TOOLS {
                if !changed.contains(name) {
                    continue;
                }
                let tool = &manifest.tools[name];
                let output = staging.join(&tool.file);
                changed_names.push(name);
                if current.get(name) == Some(&tool.version)
                    && current_hashes.get(name) == Some(&tool.sha256)
                {
                    let mut copy = OpenOptions::new()
                        .write(true)
                        .create_new(true)
                        .open(&output)
                        .map_err(|e| e.to_string())?;
                    setup::verify_file(&selected.path(name), &tool.sha256, Some(&mut copy))?;
                    copy.sync_all().map_err(|e| e.to_string())?;
                } else {
                    let compressed = staging.join(&tool.asset);
                    self.download(
                        &asset_url(&tool.asset)?,
                        &compressed,
                        tool.compressed_size,
                        &tool.compressed_sha256,
                    )
                    .await?;
                    let compressed_copy = compressed.clone();
                    let output_copy = output.clone();
                    let tool_copy = tool.clone();
                    tokio::task::spawn_blocking(move || {
                        expand_verified(&compressed_copy, &output_copy, &tool_copy)
                    })
                    .await
                    .map_err(|e| e.to_string())??;
                    fs::remove_file(compressed).map_err(|e| e.to_string())?;
                }
                #[cfg(unix)]
                {
                    use std::os::unix::fs::PermissionsExt;
                    fs::set_permissions(&output, fs::Permissions::from_mode(0o755))
                        .map_err(|e| e.to_string())?;
                }
                if self.probe(&output, name, true).await? != tool.version {
                    return Err(format!("Unexpected {name} version after download"));
                }
                versions.insert(name.into(), tool.version.clone());
                hashes.insert(name.into(), tool.sha256.clone());
            }
            if managed["ffmpeg"] && versions.get("ffmpeg") != versions.get("ffprobe") {
                return Err("Installed FFmpeg and FFprobe versions differ".into());
            }
            fs::write(
                staging.join("update-manifest.json"),
                serde_json::to_vec(&manifest).map_err(|e| e.to_string())?,
            )
            .map_err(|e| e.to_string())?;
            let identity = format!(
                "{:x}",
                Sha256::digest(
                    serde_json::to_vec(&(&manifest, &selected, uuid::Uuid::new_v4()))
                        .map_err(|e| e.to_string())?
                )
            );
            let installed = self.root.join("engines").join(identity);
            if !installed.exists() {
                fs::rename(&staging, &installed).map_err(|e| e.to_string())?;
            }
            for name in ["yt-dlp", "gallery-dl", "ffmpeg"] {
                if changed_names.contains(&name) {
                    let value = installed
                        .join(&manifest.tools[name].file)
                        .to_str()
                        .ok_or("Invalid engine path")?
                        .to_string();
                    match name {
                        "yt-dlp" => paths.engine = value,
                        "gallery-dl" => paths.gallery = value,
                        _ => paths.ffmpeg = value,
                    }
                }
            }
            let candidate = Candidate {
                paths,
                previous: selected.clone(),
                revision: tag.into(),
                versions,
                hashes,
                manifest,
            };
            self.verify_candidate(&candidate).await?;
            if !manual && !self.state.lock().map_err(|e| e.to_string())?.automatic {
                self.mutate(|s| s.state = "idle".into())?;
                return Ok(None);
            }
            self.mutate(|s| {
                s.candidate = Some(candidate.clone());
                s.state = "ready".into();
            })?;
            Ok(Some(candidate))
        }
        .await;
        if staging.exists() {
            let _ = fs::remove_dir_all(staging);
        }
        result
    }
    async fn verify_candidate(&self, candidate: &Candidate) -> Result<(), String> {
        validate_manifest(&serde_json::to_value(&candidate.manifest).map_err(|e| e.to_string())?)?;
        for name in TOOLS {
            let path = candidate.paths.path(name);
            if path != candidate.previous.path(name) && !self.managed(&path, name) {
                return Err("Candidate is outside managed engine directory".into());
            }
            if let Some(expected) = candidate.hashes.get(name) {
                setup::verify_file(&path, expected, None)?;
            } else if self.managed(&path, name) {
                return Err("Managed candidate checksum is missing".into());
            } else {
                continue;
            }
            if self.probe(&path, name, true).await?
                != *candidate
                    .versions
                    .get(name)
                    .ok_or("Candidate version missing")?
            {
                return Err(format!("Candidate {name} version verification failed"));
            }
        }
        Ok(())
    }
    async fn download(
        &self,
        url: &str,
        path: &Path,
        size: u64,
        expected: &str,
    ) -> Result<(), String> {
        self.download_with_progress(url, path, size, expected, &|_| {})
            .await
    }

    async fn download_with_progress(
        &self,
        url: &str,
        path: &Path,
        size: u64,
        expected: &str,
        progress: &(impl Fn(u64) + Send + Sync),
    ) -> Result<(), String> {
        #[cfg(test)]
        if let Some(fixtures) = &self.fixtures {
            let bytes = fixtures
                .get(url)
                .ok_or("Missing offline executable fixture")?;
            if bytes.len() as u64 != size || format!("{:x}", Sha256::digest(bytes)) != expected {
                return Err("Engine download hash or size mismatch".into());
            }
            fs::write(path, bytes).map_err(|e| e.to_string())?;
            progress(size);
            return Ok(());
        }
        let response = self
            .client
            .get(url)
            .send()
            .await
            .map_err(|e| format!("Engine download failed; check your connection and retry, or select engines manually: {e}"))?;
        if response.status() == reqwest::StatusCode::NOT_FOUND {
            return Err("Engine files for this release have not been published yet. Retry later or select installed engines manually.".into());
        }
        let mut response = response.error_for_status().map_err(|e| {
            format!("Engine download failed; retry or select installed engines manually: {e}")
        })?;
        if response.content_length().is_some_and(|value| value != size) {
            return Err("Engine download size mismatch".into());
        }
        let mut output = OpenOptions::new()
            .write(true)
            .create_new(true)
            .open(path)
            .map_err(|e| e.to_string())?;
        let mut count = 0;
        let mut digest = Sha256::new();
        while let Some(chunk) = response.chunk().await.map_err(|e| e.to_string())? {
            count += chunk.len() as u64;
            if count > size {
                return Err("Engine download exceeds size limit".into());
            }
            digest.update(&chunk);
            output.write_all(&chunk).map_err(|e| e.to_string())?;
            progress(count);
        }
        if count != size || format!("{:x}", digest.finalize()) != expected {
            return Err("Engine download hash or size mismatch".into());
        }
        output.sync_all().map_err(|e| e.to_string())
    }

    pub async fn install_initial(
        &self,
        job: Arc<process::Job>,
        progress: impl Fn(SetupProgress) + Send + Sync,
    ) -> Result<setup::InstalledEngines, String> {
        let _guard = self
            .running
            .try_lock()
            .map_err(|_| "An engine update is running; try again shortly")?;
        #[cfg(test)]
        let target = match &self.bootstrap_fixture {
            Some(value) => serde_json::from_value(value.clone()).map_err(|e| e.to_string())?,
            None => setup::download_target()?,
        };
        #[cfg(not(test))]
        let target = setup::download_target()?;
        validate_download_target(&target)?;
        check_cancelled(&job)?;
        let owned = self.root.join("engines");
        let identity = format!(
            "{:x}",
            Sha256::digest(serde_json::to_vec(&target).map_err(|e| e.to_string())?)
        );
        let installed = owned.join(identity);
        if installed.exists() {
            setup::directory(&installed)?;
            self.verify_initial(&installed, &target, job).await?;
            return initial_paths(&installed, &target);
        }
        let staging = setup::new_staging_directory(&owned)?;
        let result = async {
            fs::write(staging.join("notices.txt"), &target.notices).map_err(|e| e.to_string())?;
            for name in TOOLS {
                check_cancelled(&job)?;
                let tool = &target.tools[name];
                let output = staging.join(&tool.file);
                let download = if tool.format == "gzip" { staging.join(format!("{name}.gz")) } else {output.clone()};
                let report = |received| progress(SetupProgress {tool: name.into(),phase:"downloading".into(),received,total:tool.download_size});
                report(0);
                tokio::select! {
                    biased;
                    _ = job.wake.notified() => return Err("Operation cancelled".into()),
                    result = self.download_with_progress(&tool.url, &download, tool.download_size, &tool.download_sha256, &report) => result?,
                }
                check_cancelled(&job)?;
                progress(SetupProgress {tool:name.into(),phase:"verifying".into(),received:tool.download_size,total:tool.download_size});
                if tool.format == "gzip" {
                    let asset = ToolAsset {file:tool.file.clone(),asset:format!("{name}.gz"),version:tool.version.clone(),sha256:tool.sha256.clone(),size:tool.size,compressed_size:tool.download_size,compressed_sha256:tool.download_sha256.clone()};
                    let input = download.clone();
                    let destination = output.clone();
                    let cancellation = job.clone();
                    tokio::task::spawn_blocking(move || expand_verified_with_cancel(&input, &destination, &asset, Some(&cancellation))).await.map_err(|e|e.to_string())??;
                    fs::remove_file(download).map_err(|e|e.to_string())?;
                }
                setup::verify_file(&output, &tool.sha256, None)?;
                #[cfg(unix)]
                {
                    use std::os::unix::fs::PermissionsExt;
                    fs::set_permissions(&output, fs::Permissions::from_mode(0o755)).map_err(|e|e.to_string())?;
                }
            }
            self.verify_initial(&staging, &target, job.clone()).await?;
            check_cancelled(&job)?;
            if let Err(error) = fs::rename(&staging, &installed) {
                if !installed.exists() {return Err(error.to_string());}
                setup::directory(&installed)?;
                self.verify_initial(&installed, &target, job.clone()).await?;
            }
            initial_paths(&installed, &target)
        }.await;
        if staging.exists() {
            let _ = fs::remove_dir_all(staging);
        }
        result
    }

    async fn verify_initial(
        &self,
        directory: &Path,
        target: &setup::DownloadTarget,
        job: Arc<process::Job>,
    ) -> Result<(), String> {
        for name in TOOLS {
            check_cancelled(&job)?;
            let tool = &target.tools[name];
            let path = directory.join(&tool.file);
            setup::verify_file(&path, &tool.sha256, None)?;
            if fs::metadata(&path).map_err(|e| e.to_string())?.len() != tool.size
                || self.probe_with_job(&path, name, true, job.clone()).await? != tool.version
            {
                return Err(format!(
                    "Downloaded {name} version or size verification failed"
                ));
            }
        }
        Ok(())
    }
}

fn check_cancelled(job: &process::Job) -> Result<(), String> {
    if job.cancelled.load(std::sync::atomic::Ordering::SeqCst) {
        Err("Operation cancelled".into())
    } else {
        Ok(())
    }
}

fn initial_paths(
    directory: &Path,
    target: &setup::DownloadTarget,
) -> Result<setup::InstalledEngines, String> {
    let path = |name: &str| {
        directory
            .join(&target.tools[name].file)
            .to_str()
            .map(str::to_owned)
            .ok_or_else(|| "Invalid engine path".to_string())
    };
    Ok(setup::InstalledEngines {
        engine: path("yt-dlp")?,
        gallery: path("gallery-dl")?,
        ffmpeg: path("ffmpeg")?,
    })
}

pub(crate) fn validate_download_target(target: &setup::DownloadTarget) -> Result<(), String> {
    if target.platform != std::env::consts::OS
        || target.arch != std::env::consts::ARCH
        || target.tools.len() != TOOLS.len()
        || target.notices.is_empty()
        || target.notices.len() > METADATA_LIMIT as usize
    {
        return Err("Invalid built-in engine platform or license information".into());
    }
    for name in TOOLS {
        let tool = target
            .tools
            .get(name)
            .ok_or("A built-in engine download is missing")?;
        version(&tool.version)?;
        let url = url::Url::parse(&tool.url).map_err(|e| e.to_string())?;
        if tool.file != format!("{name}{}", std::env::consts::EXE_SUFFIX)
            || !matches!(tool.format.as_str(), "raw" | "gzip")
            || !hash(&tool.sha256)
            || !hash(&tool.download_sha256)
            || tool.size == 0
            || tool.size > LIMIT
            || tool.download_size == 0
            || tool.download_size > LIMIT
            || !trusted_redirect(&url)
            || url.host_str() != Some("github.com")
            || !url.path().contains("/releases/download/")
            || url.query().is_some()
            || url.fragment().is_some()
            || (tool.format == "raw"
                && (tool.sha256 != tool.download_sha256 || tool.size != tool.download_size))
        {
            return Err(format!("Invalid built-in engine download: {name}"));
        }
    }
    if target.tools["ffmpeg"].version != target.tools["ffprobe"].version {
        return Err("FFmpeg and FFprobe versions must match".into());
    }
    Ok(())
}

fn file_hash(path: &Path) -> Result<String, String> {
    setup::regular_file(path, LIMIT)?;
    let mut stream = File::open(path).map_err(|e| e.to_string())?.take(LIMIT + 1);
    let mut digest = Sha256::new();
    let mut buffer = [0; 64 * 1024];
    let mut size = 0;
    loop {
        let count = stream.read(&mut buffer).map_err(|e| e.to_string())?;
        if count == 0 {
            break;
        }
        size += count as u64;
        if size > LIMIT {
            return Err("Engine exceeds size limit".into());
        }
        digest.update(&buffer[..count]);
    }
    Ok(format!("{:x}", digest.finalize()))
}

fn cleanup_staging(owned: &Path) {
    let Ok(entries) = fs::read_dir(owned) else {
        return;
    };
    for entry in entries.flatten() {
        let name = entry.file_name();
        let name = name.to_string_lossy();
        let Some(suffix) = name.strip_prefix(".update-") else {
            continue;
        };
        let pieces: Vec<_> = suffix.split('-').collect();
        if pieces.len() != 3
            || pieces
                .iter()
                .any(|s| s.is_empty() || !s.bytes().all(|b| b.is_ascii_digit()))
        {
            continue;
        }
        let path = entry.path();
        if setup::directory(&path).is_err()
            || path
                .canonicalize()
                .ok()
                .is_none_or(|p| p.parent() != Some(owned))
        {
            continue;
        }
        if entry
            .metadata()
            .and_then(|m| m.modified())
            .ok()
            .and_then(|t| t.elapsed().ok())
            .is_some_and(|age| age > Duration::from_secs(2 * 24 * 60 * 60))
        {
            let _ = fs::remove_dir_all(path);
        }
    }
    // shortcut: retain immutable older sets to protect manual selections; reclaim only with a cross-process selection lock.
}

fn expand_verified(source: &Path, destination: &Path, tool: &ToolAsset) -> Result<(), String> {
    expand_verified_with_cancel(source, destination, tool, None)
}

fn expand_verified_with_cancel(
    source: &Path,
    destination: &Path,
    tool: &ToolAsset,
    job: Option<&process::Job>,
) -> Result<(), String> {
    let input = File::open(source).map_err(|e| e.to_string())?;
    let mut decoder = GzDecoder::new(BufReader::new(input));
    let mut output = OpenOptions::new()
        .write(true)
        .create_new(true)
        .open(destination)
        .map_err(|e| e.to_string())?;
    let mut count = 0_u64;
    let mut buffer = [0_u8; 64 * 1024];
    loop {
        if let Some(job) = job {
            check_cancelled(job)?;
        }
        let read = decoder.read(&mut buffer).map_err(|e| e.to_string())?;
        if read == 0 {
            break;
        }
        count += read as u64;
        if count > tool.size {
            return Err("Expanded engine size mismatch".into());
        }
        output
            .write_all(&buffer[..read])
            .map_err(|e| e.to_string())?;
    }
    if count != tool.size {
        return Err("Expanded engine size mismatch".into());
    }
    if decoder
        .into_inner()
        .read(&mut [0_u8; 1])
        .map_err(|e| e.to_string())?
        != 0
    {
        return Err("Engine gzip must contain one complete stream without trailing data".into());
    }
    output.sync_all().map_err(|e| e.to_string())?;
    drop(output);
    setup::verify_file(destination, &tool.sha256, None)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn http_download_requires_the_complete_pinned_body_and_reports_unpublished_assets() {
        use std::net::TcpListener;
        let root =
            std::env::temp_dir().join(format!("torikomi-http-download-{}", uuid::Uuid::new_v4()));
        let mut updater = EngineUpdater::new(root.clone()).unwrap();
        updater.client = reqwest::Client::builder()
            .no_proxy()
            .timeout(Duration::from_secs(3))
            .build()
            .unwrap();
        let expected = b"verified fixture body";
        for (name, body, status, succeeds) in [
            ("complete", expected.as_slice(), "200 OK", true),
            ("partial", &expected[..5], "200 OK", false),
            (
                "bad-hash",
                b"corrupt fixture body!".as_slice(),
                "200 OK",
                false,
            ),
            ("unpublished", b"".as_slice(), "404 Not Found", false),
        ] {
            let listener = TcpListener::bind("127.0.0.1:0").unwrap();
            let url = format!("http://{}/fixture", listener.local_addr().unwrap());
            let body = body.to_vec();
            let server = std::thread::spawn(move || {
                let (mut stream, _) = listener.accept().unwrap();
                stream
                    .set_read_timeout(Some(Duration::from_secs(3)))
                    .unwrap();
                let _ = stream.read(&mut [0_u8; 4096]);
                write!(
                    stream,
                    "HTTP/1.1 {status}\r\nContent-Length: {}\r\nConnection: close\r\n\r\n",
                    expected.len()
                )
                .unwrap();
                stream.write_all(&body).unwrap();
            });
            let received = Mutex::new(0);
            let result = updater
                .download_with_progress(
                    &url,
                    &root.join(name),
                    expected.len() as u64,
                    &format!("{:x}", Sha256::digest(expected)),
                    &|count| {
                        *received.lock().unwrap() = count;
                    },
                )
                .await;
            server.join().unwrap();
            assert_eq!(result.is_ok(), succeeds, "{name}: {result:?}");
            if succeeds {
                assert_eq!(fs::read(root.join(name)).unwrap(), expected);
                assert_eq!(*received.lock().unwrap(), expected.len() as u64);
            } else if name == "unpublished" {
                assert!(result
                    .unwrap_err()
                    .contains("select installed engines manually"));
            }
        }
        fs::remove_dir_all(root).unwrap();
    }
    #[test]
    fn urls_pin_the_repository_and_disallow_credentials_paths_and_queries() {
        assert!(validate_asset_url(
            "https://github.com/univzy/torikomi/releases/download/engines-1/engine.gz",
            "engines-1",
            "engine.gz"
        )
        .is_ok());
        for url in [
            "http://github.com/univzy/torikomi/releases/download/engines-1/engine.gz",
            "https://github.com/other/torikomi/releases/download/engines-1/engine.gz",
            "https://github.com/univzy/torikomi/releases/download/engines-1/engine.gz?url=x",
            "https://user@github.com/univzy/torikomi/releases/download/engines-1/engine.gz",
        ] {
            assert!(validate_asset_url(url, "engines-1", "engine.gz").is_err());
        }
    }
    #[test]
    fn gzip_expansion_is_bounded_and_bad_hash_cannot_succeed() {
        use flate2::{write::GzEncoder, Compression};
        let root = std::env::temp_dir().join(format!("torikomi-gzip-{}", uuid::Uuid::new_v4()));
        fs::create_dir(&root).unwrap();
        let mut encoder = GzEncoder::new(Vec::new(), Compression::default());
        encoder.write_all(&[1; 1024]).unwrap();
        fs::write(root.join("input"), encoder.finish().unwrap()).unwrap();
        let mut tool = ToolAsset {
            file: "engine".into(),
            asset: "engine.gz".into(),
            version: "1.0".into(),
            sha256: "0".repeat(64),
            size: 10,
            compressed_size: 1,
            compressed_sha256: "0".repeat(64),
        };
        assert!(expand_verified(&root.join("input"), &root.join("out"), &tool).is_err());
        tool.size = 1024;
        assert!(expand_verified(&root.join("input"), &root.join("bad-hash"), &tool).is_err());
        fs::remove_dir_all(root).unwrap();
    }

    fn native_fixture(root: &Path, value: &str) -> Vec<u8> {
        let source = root.join(format!("fixture-{value}.rs"));
        fs::write(&source,format!(r#"fn main() {{ let name=std::env::current_exe().unwrap().file_stem().unwrap().to_string_lossy().into_owned(); if name=="ffmpeg" || name=="ffprobe" {{println!("{{}} version {value} Copyright fixture",name);}} else {{println!("{value}");}} }}"#)).unwrap();
        let executable = root.join(format!("fixture-{value}{}", std::env::consts::EXE_SUFFIX));
        let status = std::process::Command::new("rustc")
            .arg(&source)
            .args(["--crate-name", "engine_fixture", "-o"])
            .arg(&executable)
            .status()
            .unwrap();
        assert!(status.success());
        fs::read(executable).unwrap()
    }

    #[tokio::test]
    async fn offline_update_stages_verifies_recovers_and_never_changes_selected_paths() {
        use flate2::{write::GzEncoder, Compression};
        use serde_json::json;
        let root =
            std::env::temp_dir().join(format!("torikomi-update-offline-{}", uuid::Uuid::new_v4()));
        fs::create_dir(&root).unwrap();
        let old = native_fixture(&root, "1.0.0");
        let new = native_fixture(&root, "2.0.0");
        let mut updater = EngineUpdater::new(root.clone()).unwrap();
        let installed = updater.root.join("engines").join("a".repeat(64));
        fs::create_dir(&installed).unwrap();
        for name in TOOLS {
            let path = installed.join(format!("{name}{}", std::env::consts::EXE_SUFFIX));
            fs::write(&path, &old).unwrap();
            #[cfg(unix)]
            {
                use std::os::unix::fs::PermissionsExt;
                fs::set_permissions(path, fs::Permissions::from_mode(0o755)).unwrap();
            }
        }
        let selected = SelectedPaths {
            engine: installed
                .join(format!("yt-dlp{}", std::env::consts::EXE_SUFFIX))
                .to_str()
                .unwrap()
                .into(),
            gallery: installed
                .join(format!("gallery-dl{}", std::env::consts::EXE_SUFFIX))
                .to_str()
                .unwrap()
                .into(),
            ffmpeg: installed
                .join(format!("ffmpeg{}", std::env::consts::EXE_SUFFIX))
                .to_str()
                .unwrap()
                .into(),
        };
        let tag = "engines-offline-test";
        let mut fixtures = BTreeMap::new();
        let mut assets = Vec::new();
        let mut tools = serde_json::Map::new();
        for name in TOOLS {
            let mut gzip = GzEncoder::new(Vec::new(), Compression::fast());
            gzip.write_all(&new).unwrap();
            let compressed = gzip.finish().unwrap();
            let asset = format!("{name}.gz");
            let url = format!("https://github.com/{REPOSITORY}/releases/download/{tag}/{asset}");
            assets.push(json!({"name":asset,"browser_download_url":url}));
            fixtures.insert(url, compressed.clone());
            tools.insert(name.into(),json!({"file":format!("{name}{}",std::env::consts::EXE_SUFFIX),"asset":asset,"version":"2.0.0","sha256":format!("{:x}",Sha256::digest(&new)),"size":new.len(),"compressedSize":compressed.len(),"compressedSha256":format!("{:x}",Sha256::digest(&compressed))}));
        }
        let notices = b"source and license fixture";
        let notice_url =
            format!("https://github.com/{REPOSITORY}/releases/download/{tag}/notices.json");
        assets.push(json!({"name":"notices.json","browser_download_url":notice_url}));
        fixtures.insert(notice_url, notices.to_vec());
        let manifest_name = format!(
            "{}-{}-manifest.json",
            std::env::consts::OS,
            std::env::consts::ARCH
        );
        let manifest_url =
            format!("https://github.com/{REPOSITORY}/releases/download/{tag}/{manifest_name}");
        assets.push(json!({"name":manifest_name,"browser_download_url":manifest_url}));
        let manifest = json!({"schemaVersion":1,"platform":std::env::consts::OS,"arch":std::env::consts::ARCH,"minAppVersion":"2.0.0","minOsVersion":"10.0","revision":tag,"tools":tools,"sources":{"fixture":"https://example.com/source"},"notices":{"asset":"notices.json","size":notices.len(),"sha256":format!("{:x}",Sha256::digest(notices))}});
        fixtures.insert(manifest_url, serde_json::to_vec(&manifest).unwrap());
        fixtures.insert(
            format!("https://api.github.com/repos/{REPOSITORY}/releases?per_page=100"),
            serde_json::to_vec(
                &json!([{"draft":false,"prerelease":true,"tag_name":tag,"assets":assets}]),
            )
            .unwrap(),
        );
        updater.fixtures = Some(fixtures.clone());
        let candidate = updater
            .check(selected.clone(), true)
            .await
            .unwrap()
            .unwrap();
        assert_ne!(candidate.paths, selected);
        assert_eq!(candidate.previous, selected);
        assert_eq!(fs::read(&selected.engine).unwrap(), old);
        assert_eq!(updater.status(&selected).state, "ready");
        let recovered = EngineUpdater::new(root.clone())
            .unwrap()
            .check(selected.clone(), false)
            .await
            .unwrap()
            .unwrap();
        assert_eq!(recovered.paths, candidate.paths);
        updater.activated(&candidate).unwrap();
        assert_eq!(updater.status(&candidate.paths).state, "updated");
        assert!(installed.is_dir());
        let mut metadata_only = fixtures.clone();
        metadata_only.retain(|key, _| !key.ends_with(".gz"));
        updater.fixtures = Some(metadata_only);
        assert!(updater
            .check(candidate.paths.clone(), true)
            .await
            .unwrap()
            .is_none());
        assert_eq!(updater.status(&candidate.paths).state, "up_to_date");

        let manual = root.join(format!("gallery-dl{}", std::env::consts::EXE_SUFFIX));
        fs::write(&manual, &old).unwrap();
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            fs::set_permissions(&manual, fs::Permissions::from_mode(0o755)).unwrap();
        }
        let mixed = SelectedPaths {
            gallery: manual.to_str().unwrap().into(),
            ..selected.clone()
        };
        let mut mixed_fixtures = fixtures.clone();
        mixed_fixtures.remove(&format!(
            "https://github.com/{REPOSITORY}/releases/download/{tag}/gallery-dl.gz"
        ));
        updater.fixtures = Some(mixed_fixtures);
        let mixed_candidate = updater.check(mixed.clone(), true).await.unwrap().unwrap();
        assert_eq!(mixed_candidate.paths.gallery, mixed.gallery);
        assert_eq!(fs::read(&mixed.gallery).unwrap(), old);
        updater.discard_candidate().unwrap();

        let mut one_changed = fixtures.clone();
        one_changed.retain(|url, _| !url.ends_with(".gz") || url.ends_with("/gallery-dl.gz"));
        let manifest_key = one_changed
            .keys()
            .find(|url| url.ends_with("-manifest.json"))
            .unwrap()
            .clone();
        let mut one_manifest: Value = serde_json::from_slice(&one_changed[&manifest_key]).unwrap();
        for name in ["yt-dlp", "ffmpeg", "ffprobe"] {
            one_manifest["tools"][name]["version"] = json!("1.0.0");
            one_manifest["tools"][name]["sha256"] = json!(format!("{:x}", Sha256::digest(&old)));
            one_manifest["tools"][name]["size"] = json!(old.len());
        }
        one_changed.insert(
            manifest_key.clone(),
            serde_json::to_vec(&one_manifest).unwrap(),
        );
        updater.fixtures = Some(one_changed.clone());
        let only_gallery = updater
            .check(selected.clone(), true)
            .await
            .unwrap()
            .unwrap();
        assert_eq!(only_gallery.paths.engine, selected.engine);
        assert_eq!(only_gallery.paths.ffmpeg, selected.ffmpeg);
        assert_ne!(only_gallery.paths.gallery, selected.gallery);
        updater.discard_candidate().unwrap();

        let nightly_bytes = native_fixture(&root, "N-123-test");
        let manual_directory = root.join("manual-tools");
        fs::create_dir(&manual_directory).unwrap();
        let nightly_path = manual_directory.join(format!("ffmpeg{}", std::env::consts::EXE_SUFFIX));
        fs::write(&nightly_path, &nightly_bytes).unwrap();
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            fs::set_permissions(&nightly_path, fs::Permissions::from_mode(0o755)).unwrap();
        }
        let nightly = SelectedPaths {
            ffmpeg: nightly_path.to_str().unwrap().into(),
            ..selected.clone()
        };
        assert!(!nightly.path("ffprobe").exists());
        updater.refresh_versions(nightly.clone()).await.unwrap();
        assert_eq!(
            updater.status(&nightly).tools["ffmpeg"].version.as_deref(),
            Some("N-123-test")
        );
        assert!(updater.status(&nightly).tools["ffprobe"].version.is_none());
        let nightly_candidate = updater.check(nightly.clone(), true).await.unwrap().unwrap();
        assert_eq!(nightly_candidate.paths.ffmpeg, nightly.ffmpeg);
        assert_eq!(nightly_candidate.paths.engine, nightly.engine);
        assert_ne!(nightly_candidate.paths.gallery, nightly.gallery);
        updater.discard_candidate().unwrap();
        fs::write(&nightly_path, b"unavailable manual executable").unwrap();
        updater.refresh_versions(nightly.clone()).await.unwrap();
        assert!(updater.status(&nightly).tools["ffmpeg"].version.is_none());
        assert!(updater
            .check(nightly.clone(), true)
            .await
            .unwrap()
            .is_some());
        updater.discard_candidate().unwrap();
        fs::remove_file(&nightly_path).unwrap();
        assert!(updater.check(nightly, true).await.unwrap().is_some());
        updater.discard_candidate().unwrap();

        let mut repaired = old.clone();
        repaired.extend_from_slice(b"same-version repaired executable fixture");
        let mut repair_manifest = one_manifest.clone();
        for name in TOOLS {
            repair_manifest["tools"][name]["version"] = json!("1.0.0");
            repair_manifest["tools"][name]["sha256"] = json!(format!("{:x}", Sha256::digest(&old)));
            repair_manifest["tools"][name]["size"] = json!(old.len());
        }
        let mut compressed = GzEncoder::new(Vec::new(), Compression::fast());
        compressed.write_all(&repaired).unwrap();
        let compressed = compressed.finish().unwrap();
        repair_manifest["tools"]["gallery-dl"]["sha256"] =
            json!(format!("{:x}", Sha256::digest(&repaired)));
        repair_manifest["tools"]["gallery-dl"]["size"] = json!(repaired.len());
        repair_manifest["tools"]["gallery-dl"]["compressedSha256"] =
            json!(format!("{:x}", Sha256::digest(&compressed)));
        repair_manifest["tools"]["gallery-dl"]["compressedSize"] = json!(compressed.len());
        let mut repair_fixtures = one_changed;
        repair_fixtures.insert(
            manifest_key.clone(),
            serde_json::to_vec(&repair_manifest).unwrap(),
        );
        repair_fixtures.insert(
            format!("https://github.com/{REPOSITORY}/releases/download/{tag}/gallery-dl.gz"),
            compressed.clone(),
        );
        updater.fixtures = Some(repair_fixtures);
        let repair = updater
            .check(selected.clone(), true)
            .await
            .unwrap()
            .unwrap();
        assert_eq!(repair.versions["gallery-dl"], "1.0.0");
        assert_ne!(repair.paths.gallery, selected.gallery);
        assert_eq!(fs::read(&repair.paths.gallery).unwrap(), repaired);
        assert_eq!(repair.paths.engine, selected.engine);
        assert_eq!(repair.paths.ffmpeg, selected.ffmpeg);
        updater.discard_candidate().unwrap();
        repair_manifest["tools"]["gallery-dl"]["sha256"] =
            json!(format!("{:x}", Sha256::digest(&old)));
        repair_manifest["tools"]["gallery-dl"]["size"] = json!(old.len());
        repair_manifest["tools"]["ffprobe"]["sha256"] =
            json!(format!("{:x}", Sha256::digest(&repaired)));
        repair_manifest["tools"]["ffprobe"]["size"] = json!(repaired.len());
        repair_manifest["tools"]["ffprobe"]["compressedSha256"] =
            json!(format!("{:x}", Sha256::digest(&compressed)));
        repair_manifest["tools"]["ffprobe"]["compressedSize"] = json!(compressed.len());
        let mut pair_repair = fixtures.clone();
        pair_repair.retain(|url, _| !url.ends_with(".gz"));
        pair_repair.insert(
            manifest_key.clone(),
            serde_json::to_vec(&repair_manifest).unwrap(),
        );
        pair_repair.insert(
            format!("https://github.com/{REPOSITORY}/releases/download/{tag}/ffprobe.gz"),
            compressed,
        );
        updater.fixtures = Some(pair_repair);
        let pair = updater
            .check(selected.clone(), true)
            .await
            .unwrap()
            .unwrap();
        assert_ne!(pair.paths.ffmpeg, selected.ffmpeg);
        assert_eq!(fs::read(&pair.paths.ffmpeg).unwrap(), old);
        assert_eq!(fs::read(pair.paths.path("ffprobe")).unwrap(), repaired);
        updater.discard_candidate().unwrap();
        for name in TOOLS {
            one_manifest["tools"][name]["version"] = json!("0.9.0");
        }
        let mut downgrade = fixtures.clone();
        downgrade.retain(|url, _| !url.ends_with(".gz"));
        downgrade.insert(manifest_key, serde_json::to_vec(&one_manifest).unwrap());
        updater.fixtures = Some(downgrade);
        assert!(updater
            .check(selected.clone(), true)
            .await
            .unwrap()
            .is_none());
        assert_eq!(updater.status(&selected).state, "up_to_date");

        let releases_url =
            format!("https://api.github.com/repos/{REPOSITORY}/releases?per_page=100");
        updater.fixtures = Some(BTreeMap::from([(releases_url, b"[]".to_vec())]));
        updater
            .mutate(|state| state.last_checked = Some(storage::now() + DAY * 10))
            .unwrap();
        assert!(updater
            .check(selected.clone(), false)
            .await
            .unwrap()
            .is_none());
        assert_eq!(updater.status(&selected).state, "no_feed");
        // A partial transfer cannot activate or overwrite the previous installation.
        updater.discard_candidate().unwrap();
        let key = format!("https://github.com/{REPOSITORY}/releases/download/{tag}/yt-dlp.gz");
        fixtures.insert(key, b"partial".to_vec());
        updater.fixtures = Some(fixtures);
        let candidate_parent = Path::new(&candidate.paths.engine).parent().unwrap();
        fs::remove_dir_all(candidate_parent).unwrap();
        assert!(updater.check(selected.clone(), true).await.is_err());
        assert_eq!(fs::read(&selected.engine).unwrap(), old);
        assert_eq!(updater.status(&selected).state, "error");
        assert!(!fs::read_dir(updater.root.join("engines"))
            .unwrap()
            .any(|entry| entry
                .unwrap()
                .file_name()
                .to_string_lossy()
                .starts_with(".update-")));
        fs::remove_dir_all(root).unwrap();
    }

    #[test]
    fn damaged_optional_updater_state_does_not_prevent_application_startup() {
        let root =
            std::env::temp_dir().join(format!("torikomi-updater-corrupt-{}", uuid::Uuid::new_v4()));
        fs::create_dir(&root).unwrap();
        fs::write(root.join("engine-updates.json"), b"{damaged").unwrap();
        let updater = EngineUpdater::new(root.clone()).unwrap();
        let selected = SelectedPaths {
            engine: String::new(),
            gallery: String::new(),
            ffmpeg: String::new(),
        };
        assert_eq!(updater.status(&selected).state, "error");
        assert!(!updater.status(&selected).automatic);
        fs::remove_dir_all(root).unwrap();
    }
}
