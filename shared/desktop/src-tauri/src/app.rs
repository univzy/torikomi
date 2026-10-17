use crate::{
    core::{self, Document, Preferences, Settings, MAX_BYTES},
    gallery, media,
    preview::Previewer,
    process::{execute, execute_captured, execute_output, Job},
    setup,
    storage::{self, LocalState},
    updater::{Candidate, EngineUpdater, SelectedPaths},
};
use serde_json::{json, Value};
use std::{
    collections::BTreeMap,
    fs,
    path::{Path, PathBuf},
    sync::{Arc, Mutex, MutexGuard},
};
use tauri::{
    utils::{
        config::{Color, WindowEffectsConfig},
        WindowEffect,
    },
    Emitter, Manager, WebviewWindow,
};
use tauri_plugin_notification::NotificationExt;

#[derive(Clone)]
struct Inspection {
    url: String,
    title: String,
    formats: Vec<Value>,
    metadata: Option<core::MediaMetadata>,
    direct_audio_arguments: Vec<String>,
}

#[derive(Clone)]
struct MediaLease {
    entry_id: String,
    source: PathBuf,
    path: PathBuf,
    size: u64,
    modified: std::time::SystemTime,
}

fn lease_path(local: &LocalState, lease: &MediaLease, root: &Path) -> Result<PathBuf, String> {
    let (source, _) = local.preview_file(&lease.entry_id)?;
    let metadata = source.metadata().map_err(|e| e.to_string())?;
    if source.canonicalize().map_err(|e| e.to_string())? != lease.source
        || metadata.len() != lease.size
        || metadata.modified().map_err(|e| e.to_string())? != lease.modified
        || lease.path.canonicalize().map_err(|e| e.to_string())? != lease.path
        || (lease.path != lease.source
            && !lease.path.starts_with(
                root.canonicalize()
                    .map_err(|e| e.to_string())?
                    .join("playback-cache"),
            ))
    {
        return Err("This media session is no longer available".into());
    }
    Ok(lease.path.clone())
}

struct Backend {
    local: Mutex<LocalState>,
    inspected: Mutex<Option<Inspection>>,
    gallery_inspected: Mutex<Option<(String, Vec<Value>)>>,
    previews: Previewer,
    media_leases: Mutex<BTreeMap<String, MediaLease>>,
    updates: EngineUpdater,
    update_candidate: Mutex<Option<(Candidate, bool)>>,
    pending: Mutex<Option<Document>>,
    job: Mutex<Option<Arc<Job>>>,
    path: PathBuf,
}

fn lock<T>(value: &Mutex<T>) -> Result<MutexGuard<'_, T>, String> {
    value
        .lock()
        .map_err(|_| "App state is unavailable; restart Torikomi".into())
}

fn glass_available() -> bool {
    #[cfg(windows)]
    {
        windows_version::OsVersion::current().build >= 22621
    }
    #[cfg(target_os = "macos")]
    {
        true
    }
    #[cfg(not(any(windows, target_os = "macos")))]
    {
        false
    }
}

impl Backend {
    fn view(&self, window: &WebviewWindow) -> Result<Value, String> {
        let local = lock(&self.local)?;
        let prefs = &local.document.settings.value;
        let dark = prefs.theme_mode == "dark"
            || (prefs.theme_mode == "system" && window.theme().ok() == Some(tauri::Theme::Dark));
        Ok(
            json!({"document":local.document,"deviceId":local.device_id,"folder":local.folder,"engine":local.engine,"ffmpeg":local.ffmpeg,"gallery":local.gallery,"setupComplete":local.setup_complete,"bundleAvailable":setup::download_available(),"downloadAvailable":setup::download_available(),"files":local.files,"isDark":dark,"glassAvailable":glass_available(),"engineUpdates":self.updates.status(&Self::selected(&local))}),
        )
    }

    fn update(&self, window: &WebviewWindow) -> Result<Value, String> {
        self.activate_update()?;
        let view = self.view(window)?;
        let _ = window.emit("state", &view);
        Ok(view)
    }

    fn commit<T>(
        &self,
        change: impl FnOnce(&mut LocalState) -> Result<T, String>,
    ) -> Result<T, String> {
        let mut current = lock(&self.local)?;
        let mut next = current.clone();
        let result = change(&mut next)?;
        next.persist(&self.path)?;
        *current = next;
        Ok(result)
    }

    fn start_job(&self) -> Result<Arc<Job>, String> {
        let mut active = lock(&self.job)?;
        if active.is_some() {
            return Err("Wait for the current operation or cancel it first".into());
        }
        let job = Arc::new(Job::default());
        *active = Some(job.clone());
        Ok(job)
    }

    fn theme(&self, window: &WebviewWindow) -> Result<(), String> {
        let prefs = lock(&self.local)?.document.settings.value.clone();
        let mode = match prefs.theme_mode.as_str() {
            "light" => Some(tauri::Theme::Light),
            "dark" => Some(tauri::Theme::Dark),
            _ => None,
        };
        window
            .set_theme(mode)
            .map_err(|e| format!("Could not apply the window theme: {e}"))?;
        let dark = mode.unwrap_or_else(|| window.theme().unwrap_or(tauri::Theme::Dark))
            == tauri::Theme::Dark;
        let glass = prefs.glass && glass_available();
        let themes: Vec<Value> = serde_json::from_str(include_str!("../../../catalog/themes.json"))
            .map_err(|e| e.to_string())?;
        let color = themes
            .iter()
            .find(|t| t["id"] == prefs.theme_color)
            .and_then(|t| t[if dark { "dark" } else { "light" }]["background"].as_str())
            .unwrap_or("#16171f");
        let background = if glass {
            Color(0, 0, 0, 0)
        } else {
            color.parse().map_err(|_| "Invalid theme background")?
        };
        window
            .set_background_color(Some(background))
            .map_err(|e| format!("Could not apply the window background: {e}"))?;
        #[cfg(any(windows, target_os = "macos"))]
        {
            #[cfg(windows)]
            let effect = WindowEffect::Acrylic;
            #[cfg(target_os = "macos")]
            let effect = WindowEffect::Sidebar;
            let effects = glass.then(|| WindowEffectsConfig {
                effects: vec![effect],
                ..Default::default()
            });
            window
                .set_effects(effects)
                .map_err(|e| format!("Could not apply the glass effect: {e}"))?;
        }
        Ok(())
    }

    fn selected(local: &LocalState) -> SelectedPaths {
        SelectedPaths {
            engine: local.engine.clone(),
            gallery: local.gallery.clone(),
            ffmpeg: local.ffmpeg.clone(),
        }
    }

    fn activate_update(&self) -> Result<(), String> {
        let active = lock(&self.job)?;
        if active.is_some() {
            return Ok(());
        }
        let mut pending = lock(&self.update_candidate)?;
        if let Some((candidate, manual)) = pending.as_ref() {
            let selected = Self::selected(&*lock(&self.local)?);
            if selected != candidate.previous
                || (!manual && !self.updates.status(&selected).automatic)
            {
                self.updates.discard_candidate()?;
                *pending = None;
                return Ok(());
            }
            let applied = self.commit(|local| {
                if Self::selected(local) != candidate.previous {
                    return Ok(false);
                }
                local.engine = candidate.paths.engine.clone();
                local.gallery = candidate.paths.gallery.clone();
                local.ffmpeg = candidate.paths.ffmpeg.clone();
                Ok(true)
            })?;
            if !applied {
                self.updates.discard_candidate()?;
                *pending = None;
                return Ok(());
            }
            self.updates.activated(candidate)?;
            *pending = None;
        }
        Ok(())
    }
}

async fn check_engine_updates(
    backend: &Backend,
    window: &WebviewWindow,
    manual: bool,
) -> Result<Value, String> {
    let selected = Backend::selected(&*lock(&backend.local)?);
    let before = json!(backend.updates.status(&selected));
    let result = backend.updates.check(selected.clone(), manual).await;
    if let Ok(Some(candidate)) = &result {
        *lock(&backend.update_candidate)? = Some((candidate.clone(), manual));
        backend.activate_update()?;
    }
    let changed = selected != Backend::selected(&*lock(&backend.local)?)
        || before != json!(backend.updates.status(&selected));
    let view = if manual || changed {
        backend.update(window)?
    } else {
        backend.view(window)?
    };
    result.map(|_| view)
}

async fn open_media(id: &str, compatible: bool, backend: &Backend) -> Result<Value, String> {
    let (source, kind, ffmpeg, title) = {
        let local = lock(&backend.local)?;
        let (path, kind) = local.preview_file(id)?;
        (
            path,
            kind,
            local.ffmpeg.clone(),
            local
                .document
                .entries
                .iter()
                .find(|e| e.id == id)
                .map(|e| e.title.clone())
                .ok_or("Missing media entry")?,
        )
    };
    let source = source.canonicalize().map_err(|e| e.to_string())?;
    if !ffmpeg.is_empty() {
        executable(&ffmpeg)?;
    }
    let root = backend.path.parent().ok_or("Invalid app data directory")?;
    let path = if compatible {
        if kind != "audio" {
            return Err("Playback copies are only available for audio".into());
        }
        let job = backend.start_job()?;
        let result =
            media::playback_copy(&source, &root.join("playback-cache"), &ffmpeg, job).await;
        *lock(&backend.job)? = None;
        result?
    } else {
        source.clone()
    }
    .canonicalize()
    .map_err(|e| e.to_string())?;
    let mut info = media::file_info(&path, &ffmpeg).await?;
    if info["kind"] != kind {
        return Err("The local file type no longer matches this History entry".into());
    }
    let actual_title = if kind == "audio" {
        info["title"]
            .as_str()
            .filter(|s| !s.is_empty())
            .map(str::to_owned)
            .unwrap_or(title)
    } else {
        title
    };
    if !compatible && kind == "audio" {
        let metadata: Option<core::MediaMetadata> =
            serde_json::from_value(info["metadata"].clone()).map_err(|e| e.to_string())?;
        let changed = {
            let local = lock(&backend.local)?;
            let entry = local
                .document
                .entries
                .iter()
                .find(|e| e.id == id)
                .ok_or("Missing media entry")?;
            entry.title != actual_title
                || json!(entry.metadata)
                    != json!(merge_metadata(entry.metadata.clone(), metadata.clone()))
        };
        if changed {
            backend.commit(|local| {
                let time = local.document.next_time(storage::now())?;
                let entry = local
                    .document
                    .entries
                    .iter_mut()
                    .find(|e| e.id == id && e.deleted_at == 0)
                    .ok_or("Missing media entry")?;
                entry.title = actual_title.clone();
                entry.metadata = merge_metadata(entry.metadata.clone(), metadata);
                entry.updated_at = time;
                entry.device_id = local.device_id.clone();
                Ok(())
            })?;
        }
    }
    let mut leases = lock(&backend.media_leases)?;
    leases.retain(|_, lease| {
        lock(&backend.local)
            .map(|local| lease_path(&local, lease, root).is_ok())
            .unwrap_or(false)
    });
    let token = leases
        .iter()
        .find(|(_, lease)| lease.entry_id == id && lease.path == path && lease.source == source)
        .map(|(key, _)| key.clone())
        .unwrap_or_else(|| uuid::Uuid::new_v4().to_string());
    if leases.len() >= 64 && !leases.contains_key(&token) {
        return Err("Close another media session before opening more files".into());
    }
    let identity = source.metadata().map_err(|e| e.to_string())?;
    leases.insert(
        token.clone(),
        MediaLease {
            entry_id: id.into(),
            source,
            path,
            size: identity.len(),
            modified: identity.modified().map_err(|e| e.to_string())?,
        },
    );
    info["id"] = json!(id);
    info["title"] = json!(actual_title);
    info["url"] = json!(format!(
        "{}/{token}",
        if cfg!(windows) {
            "http://media.localhost"
        } else {
            "media://localhost"
        }
    ));
    Ok(info)
}

fn merge_metadata(
    base: Option<core::MediaMetadata>,
    extra: Option<core::MediaMetadata>,
) -> Option<core::MediaMetadata> {
    match (base, extra) {
        (None, value) | (value, None) => value,
        (Some(mut base), Some(extra)) => {
            base.artist = extra.artist.or(base.artist);
            base.album = extra.album.or(base.album);
            base.album_artist = extra.album_artist.or(base.album_artist);
            base.track_number = extra.track_number.or(base.track_number);
            base.genre = extra.genre.or(base.genre);
            base.year = extra.year.or(base.year);
            base.duration = extra.duration.or(base.duration);
            Some(base)
        }
    }
}

fn argument(args: &[Value], index: usize) -> Result<&str, String> {
    args.get(index)
        .and_then(Value::as_str)
        .ok_or_else(|| format!("Missing argument {}", index + 1))
}

fn executable(path: &str) -> Result<String, String> {
    if path.is_empty() {
        return Ok(if cfg!(windows) {
            "yt-dlp.exe"
        } else {
            "yt-dlp"
        }
        .into());
    }
    let p = Path::new(path);
    if !p.is_absolute() || !p.is_file() {
        return Err("Select a valid installed executable in Settings".into());
    }
    #[cfg(windows)]
    if !p
        .extension()
        .map(|e| e.eq_ignore_ascii_case("exe"))
        .unwrap_or(false)
    {
        return Err("Choose an .exe binary; command scripts are not supported".into());
    }
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        if p.metadata()
            .map_err(|e| e.to_string())?
            .permissions()
            .mode()
            & 0o111
            == 0
        {
            return Err("Choose an executable binary".into());
        }
    }
    Ok(path.into())
}

async fn inspect(url: &str, backend: &Backend, window: &WebviewWindow) -> Result<Value, String> {
    let url = core::media_url(url)?;
    let engine = executable(&lock(&backend.local)?.engine)?;
    let job = backend.start_job()?;
    *lock(&backend.inspected)? = None;
    let args = [
        "--ignore-config",
        "--no-playlist",
        "--skip-download",
        "--dump-single-json",
        "--no-warnings",
        "--no-colors",
        "--",
        &url,
    ]
    .map(String::from);
    let result = tokio::time::timeout(
        std::time::Duration::from_secs(180),
        execute(&engine, &args, job.clone(), 32 * 1024 * 1024, |_| {}),
    )
    .await;
    *lock(&backend.job)? = None;
    let output = match result {
        Ok(value) => value?,
        Err(_) => {
            job.cancel();
            return Err("Format inspection timed out; try again".into());
        }
    };
    let raw: Value =
        serde_json::from_str(&output).map_err(|e| format!("Invalid format response: {e}"))?;
    let formats = core::inspect_formats(&raw)?;
    let (title, metadata) = media::source_metadata(&raw);
    *lock(&backend.inspected)? = Some(Inspection {
        url: url.clone(),
        title: title.clone(),
        formats: formats.clone(),
        metadata: metadata.clone(),
        direct_audio_arguments: media::direct_audio_arguments(&raw),
    });
    let _ = window.emit("inspect-progress", json!({"text":""}));
    Ok(json!({"url":url,"title":title,"formats":formats,"metadata":metadata}))
}

async fn download(
    args: &[Value],
    backend: &Backend,
    app: &tauri::AppHandle,
    window: &WebviewWindow,
) -> Result<Value, String> {
    let url = core::media_url(argument(args, 0)?)?;
    let format = argument(args, 1)?;
    let audio = args
        .get(2)
        .and_then(Value::as_str)
        .filter(|s| !s.is_empty());
    let output = args.get(3).and_then(Value::as_str).unwrap_or("original");
    let inspected = lock(&backend.inspected)?
        .clone()
        .filter(|i| i.url == url)
        .ok_or("Inspect this URL before downloading")?;
    let local = lock(&backend.local)?.clone();
    let engine = executable(&local.engine)?;
    let mut command_args = core::download_arguments(
        &url,
        &inspected.formats,
        format,
        audio,
        output,
        &local.folder,
        &local.ffmpeg,
    )?;
    let audio_only = inspected
        .formats
        .iter()
        .any(|f| f["id"] == format && f["kind"] == "audio");
    let mut extra = media::audio_arguments(&inspected.formats, format, output);
    if audio_only {
        extra.extend(inspected.direct_audio_arguments.clone());
    }
    command_args.splice(command_args.len() - 2..command_args.len() - 2, extra);
    fs::create_dir_all(&local.folder).map_err(|e| e.to_string())?;
    let folder = fs::canonicalize(&local.folder).map_err(|e| e.to_string())?;
    let job = backend.start_job()?;
    let progress_window = window.clone();
    let result = execute_captured(
        &engine,
        &command_args,
        job.clone(),
        32 * 1024 * 1024,
        move |text| {
            let _ = progress_window.emit("progress", json!({"text":text}));
        },
    )
    .await;
    *lock(&backend.job)? = None;
    let mut warning = None;
    let file = match result {
        Ok(output_text) => output_text
            .lines()
            .rev()
            .map(str::trim)
            .filter(|s| Path::new(s).is_absolute())
            .find_map(|s| fs::canonicalize(s).ok())
            .ok_or("Download completed but no output file was found")?,
        Err(error) if audio_only && !job.cancelled.load(std::sync::atomic::Ordering::SeqCst) => {
            let recovered = media::recover_audio_path(&error.output, output, &folder)
                .map_err(|_| error.message.clone())?;
            let info = media::file_info(&recovered, &local.ffmpeg).await?;
            if info["kind"] != "audio" {
                return Err(error.message);
            }
            warning = Some(error.message.chars().take(300).collect::<String>());
            recovered
        }
        Err(error) => return Err(error.message),
    };
    if !file.starts_with(&folder) || !file.is_file() {
        return Err("Download output is outside the selected folder".into());
    }
    let image = inspected
        .formats
        .iter()
        .any(|f| f["id"] == format && f["kind"] == "image");
    let actual = if audio_only {
        media::file_info(&file, &local.ffmpeg).await.ok()
    } else {
        None
    };
    let metadata = merge_metadata(
        inspected.metadata.clone(),
        actual
            .as_ref()
            .and_then(|info| serde_json::from_value(info["metadata"].clone()).ok())
            .flatten(),
    );
    let kind = if audio_only {
        "audio"
    } else if image {
        "image"
    } else {
        "video"
    };
    backend.commit(|state| {
        let existing=args.get(4).and_then(Value::as_str).map(|id|state.document.entries.iter().find(|e|e.id==id&&e.deleted_at==0&&e.url==url&&e.kind==kind).map(|e|e.id.clone()).ok_or("The original History entry changed or was removed; the downloaded file remains in your download folder")).transpose()?;
        let id=if let Some(id)=existing{id}else{state.add_link(&url,&inspected.title,kind)?.id};
        let time=state.document.next_time(storage::now())?;
        let entry=state.document.entries.iter_mut().find(|e|e.id==id).ok_or("Missing downloaded entry")?;
        entry.title=actual.as_ref().and_then(|v|v["title"].as_str()).filter(|s|!s.is_empty()).unwrap_or(&inspected.title).to_owned();entry.metadata=merge_metadata(entry.metadata.clone(),metadata);entry.updated_at=time;entry.device_id=state.device_id.clone();
        state
            .files
            .insert(id, file.to_string_lossy().into_owned());
        Ok(())
    })?;
    if lock(&backend.local)?.document.settings.value.notifications {
        let notification = app.notification();
        if notification.request_permission().is_ok() {
            let _ = notification
                .builder()
                .title("Torikomi")
                .body(file.file_name().unwrap_or_default().to_string_lossy())
                .show();
        }
    }
    let mut view = backend.update(window)?;
    if let Some(warning) = warning {
        view["downloadWarning"] = json!(warning);
    }
    Ok(view)
}

async fn gallery_inspect(url: &str, backend: &Backend) -> Result<Value, String> {
    let url = core::media_url(url)?;
    let path = lock(&backend.local)?.gallery.clone();
    if path.is_empty() {
        return Err("Set up gallery-dl in Settings first".into());
    }
    let engine = executable(&path)?;
    let args = gallery::inspect_args(&url)?;
    let job = backend.start_job()?;
    *lock(&backend.gallery_inspected)? = None;
    let result = tokio::time::timeout(
        std::time::Duration::from_secs(180),
        execute_output(
            &engine,
            &args,
            job.clone(),
            gallery::MAX_OUTPUT_BYTES,
            |_| {},
        ),
    )
    .await;
    *lock(&backend.job)? = None;
    let raw = match result {
        Ok(output) => output.map_err(|error| error.message)?,
        Err(_) => {
            job.cancel();
            return Err("Album inspection timed out; try a smaller album".into());
        }
    };
    let items = gallery::parse_inspection_output(&raw.stdout, &raw.stderr)?;
    *lock(&backend.gallery_inspected)? = Some((url.clone(), items.clone()));
    Ok(json!({"url":url,"items":items}))
}

async fn gallery_download(
    args: &[Value],
    backend: &Backend,
    window: &WebviewWindow,
) -> Result<Value, String> {
    let url = core::media_url(argument(args, 0)?)?;
    let selected: Vec<u32> =
        serde_json::from_value(args.get(1).cloned().ok_or("Select photos first")?)
            .map_err(|_| "Invalid photo selection")?;
    let (_, items) = lock(&backend.gallery_inspected)?
        .clone()
        .filter(|(source, _)| source == &url)
        .ok_or("Inspect this album before downloading")?;
    if selected.is_empty()
        || selected.len() > items.len()
        || selected.iter().any(|index| {
            !items
                .iter()
                .any(|item| item["imageIndex"] == *index && item["kind"] == "image")
        })
    {
        return Err("Choose only photos from the inspected album".into());
    }
    let local = lock(&backend.local)?.clone();
    if local.gallery.is_empty() {
        return Err("Set up gallery-dl in Settings first".into());
    }
    let engine = executable(&local.gallery)?;
    fs::create_dir_all(&local.folder).map_err(|e| e.to_string())?;
    let folder = fs::canonicalize(&local.folder).map_err(|e| e.to_string())?;
    let ranges = gallery::photo_ranges(&items, &selected)?;
    local.check_photo_capacity(&url, ranges.len())?;
    let command_args = gallery::download_args(&url, &ranges, &folder)?;
    let job = backend.start_job()?;
    let progress_window = window.clone();
    let result = execute_captured(
        &engine,
        &command_args,
        job,
        gallery::MAX_OUTPUT_BYTES,
        move |text| {
            let _ = progress_window.emit("progress", json!({"text":text}));
        },
    )
    .await;
    *lock(&backend.job)? = None;
    let (output, failure) = match result {
        Ok(output) => (output, None),
        Err(error) => {
            // A killed process can end mid-line; completed paths always end with a newline.
            let complete = error
                .output
                .rfind('\n')
                .map(|end| error.output[..=end].to_owned())
                .unwrap_or_default();
            (complete, Some(error.message))
        }
    };
    let files = if output.trim().is_empty() && failure.is_some() {
        vec![]
    } else {
        gallery::parse_output_files(&output, &folder)?
    };
    backend.commit(|state| {
        for file in &files {
            let title = file.file_name().unwrap_or_default().to_string_lossy();
            let entry = state.add_link(&url, &title, "image")?;
            state
                .files
                .insert(entry.id, file.to_string_lossy().into_owned());
        }
        Ok(())
    })?;
    let view = backend.update(window)?;
    if let Some(error) = failure {
        Err(format!(
            "{} photo(s) saved before the operation stopped. {error}",
            files.len()
        ))
    } else {
        Ok(view)
    }
}

async fn probe(path: &str, kind: &str, job: Arc<Job>) -> Result<(), String> {
    if path.is_empty() {
        return Err(format!("Choose {kind} first"));
    }
    let program = executable(path)?;
    let args = vec![if kind == "ffmpeg" || kind == "ffprobe" {
        "-version"
    } else {
        "--version"
    }
    .to_string()];
    let result = tokio::time::timeout(
        std::time::Duration::from_secs(20),
        execute(&program, &args, job.clone(), 64 * 1024, |_| {}),
    )
    .await;
    let output = match result {
        Ok(output) => output?,
        Err(_) => {
            job.cancel();
            return Err(format!("{kind} did not respond; choose another executable"));
        }
    };
    if !tool_version(&output, kind) {
        return Err(format!(
            "The selected file is not a working {kind} executable"
        ));
    }
    Ok(())
}

fn tool_version(output: &str, kind: &str) -> bool {
    let first = output.lines().next().unwrap_or("").trim();
    if kind == "ffmpeg" || kind == "ffprobe" {
        return first.starts_with(&format!("{kind} version "));
    }
    let parts: Vec<_> = first.split('.').take(3).collect();
    parts.len() == 3
        && parts
            .iter()
            .all(|part| !part.is_empty() && part.bytes().all(|b| b.is_ascii_digit()))
        && if kind == "yt-dlp" {
            parts[0].len() == 4
        } else {
            parts[0].len() < 4
        }
}

#[cfg(test)]
mod tests {
    struct SetupFixture {
        backend: super::Backend,
        root: std::path::PathBuf,
    }

    impl SetupFixture {
        fn new() -> Self {
            use sha2::{Digest, Sha256};
            let root =
                std::env::temp_dir().join(format!("torikomi-setup-state-{}", uuid::Uuid::new_v4()));
            let bundle = root.join("bundle");
            std::fs::create_dir_all(&bundle).unwrap();
            let source = root.join("tool.rs");
            std::fs::write(
                &source,
                r#"
                fn main() {
                    let exe = std::env::current_exe().unwrap();
                    let name = exe.file_stem().unwrap().to_str().unwrap();
                    if exe.with_file_name("fail-probe").exists() && name == "ffmpeg" {
                        println!("invalid executable");
                    } else {
                        println!("{}", match name {
                            "yt-dlp" => "2026.08.19",
                            "gallery-dl" => "1.32.16",
                            "ffmpeg" => "ffmpeg version 9.0.2",
                            _ => "ffprobe version 9.0.2",
                        });
                    }
                }
            "#,
            )
            .unwrap();
            let executable = root.join(format!("tool{}", std::env::consts::EXE_SUFFIX));
            let compilation = std::process::Command::new("rustc")
                .arg(&source)
                .arg("-o")
                .arg(&executable)
                .output()
                .unwrap();
            assert!(
                compilation.status.success(),
                "{}",
                String::from_utf8_lossy(&compilation.stderr)
            );
            let digest = format!("{:x}", Sha256::digest(std::fs::read(&executable).unwrap()));
            let mut tools = serde_json::Map::new();
            let mut downloads = serde_json::Map::new();
            let mut fixtures = std::collections::BTreeMap::new();
            for name in ["yt-dlp", "gallery-dl", "ffmpeg", "ffprobe"] {
                let file = format!("{name}{}", std::env::consts::EXE_SUFFIX);
                std::fs::copy(&executable, bundle.join(&file)).unwrap();
                tools.insert(
                    name.into(),
                    serde_json::json!({"file":file,"sha256":digest,"version":"1.0"}),
                );
                let url = format!("https://github.com/yt-dlp/yt-dlp/releases/download/test/{name}");
                let bytes = std::fs::read(&executable).unwrap();
                let mut compressed =
                    flate2::write::GzEncoder::new(Vec::new(), flate2::Compression::fast());
                std::io::Write::write_all(&mut compressed, &bytes).unwrap();
                let compressed = compressed.finish().unwrap();
                downloads.insert(name.into(), serde_json::json!({"file":file,"sha256":digest,"version": match name {"yt-dlp"=>"2026.08.19","gallery-dl"=>"1.32.16",_=>"9.0.2"},"url":url,"format":"gzip","size":bytes.len(),"downloadSize":compressed.len(),"downloadSha256":format!("{:x}",Sha256::digest(&compressed))}));
                fixtures.insert(url, compressed);
            }
            std::fs::write(bundle.join("manifest.json"), serde_json::to_vec(&serde_json::json!({
                "schemaVersion":1,"platform":std::env::consts::OS,"arch":std::env::consts::ARCH,"tools":tools
            })).unwrap()).unwrap();
            let path = root.join("library.json");
            let local = crate::storage::LocalState::load(&path, None, &root).unwrap();
            let mut updates = crate::updater::EngineUpdater::new(root.clone()).unwrap();
            updates.fixtures = Some(fixtures);
            updates.bootstrap_fixture = Some(
                serde_json::json!({"platform":std::env::consts::OS,"arch":std::env::consts::ARCH,"tools":downloads,"notices":"Fixture license and sources"}),
            );
            let backend = super::Backend {
                local: std::sync::Mutex::new(local),
                inspected: std::sync::Mutex::new(None),
                gallery_inspected: std::sync::Mutex::new(None),
                previews: crate::preview::Previewer::new().unwrap(),
                media_leases: std::sync::Mutex::new(std::collections::BTreeMap::new()),
                updates,
                update_candidate: std::sync::Mutex::new(None),
                pending: std::sync::Mutex::new(None),
                job: std::sync::Mutex::new(None),
                path,
            };
            Self { backend, root }
        }

        fn reload(&self) -> crate::storage::LocalState {
            crate::storage::LocalState::load(&self.backend.path, None, &self.root).unwrap()
        }
    }

    impl Drop for SetupFixture {
        fn drop(&mut self) {
            let _ = std::fs::remove_dir_all(&self.root);
        }
    }

    #[tokio::test]
    async fn automatic_setup_is_complete_after_reload_without_finish_click() {
        let fixture = SetupFixture::new();
        std::fs::remove_dir_all(fixture.root.join("bundle")).unwrap();
        assert!(!fixture.reload().setup_complete);
        super::automatic_setup(&fixture.backend, |_| {})
            .await
            .unwrap();
        let reloaded = fixture.reload();
        assert!(
            reloaded.setup_complete,
            "Verified setup must survive closing before Finish"
        );
        for path in [&reloaded.engine, &reloaded.gallery, &reloaded.ffmpeg] {
            assert!(std::path::Path::new(path).is_file());
        }
        assert!(fixture.backend.job.lock().unwrap().is_none());
    }

    #[tokio::test]
    async fn failed_setup_preserves_completion_and_previously_selected_paths() {
        for previously_complete in [false, true] {
            let mut fixture = SetupFixture::new();
            fixture
                .backend
                .commit(|state| {
                    state.engine = fixture
                        .root
                        .join("custom-yt-dlp")
                        .to_string_lossy()
                        .into_owned();
                    state.gallery = fixture
                        .root
                        .join("custom-gallery-dl")
                        .to_string_lossy()
                        .into_owned();
                    state.ffmpeg = fixture
                        .root
                        .join("custom-ffmpeg")
                        .to_string_lossy()
                        .into_owned();
                    state.setup_complete = previously_complete;
                    Ok(())
                })
                .unwrap();
            let before = std::fs::read(&fixture.backend.path).unwrap();
            let target = fixture.backend.updates.bootstrap_fixture.as_mut().unwrap();
            target["tools"]["ffmpeg"]["version"] = serde_json::json!("8.0.0");
            target["tools"]["ffprobe"]["version"] = serde_json::json!("8.0.0");
            let error = super::automatic_setup(&fixture.backend, |_| {})
                .await
                .unwrap_err();
            assert!(error.contains("ffmpeg"), "{error}");
            assert_eq!(std::fs::read(&fixture.backend.path).unwrap(), before);
            assert_eq!(fixture.reload().setup_complete, previously_complete);
            assert_eq!(
                fixture.backend.local.lock().unwrap().engine,
                fixture.root.join("custom-yt-dlp").to_string_lossy()
            );
            assert!(fixture.backend.job.lock().unwrap().is_none());
        }
    }

    #[tokio::test]
    async fn partial_or_cancelled_initial_download_preserves_selected_paths_and_cleans_staging() {
        for (cancel, previously_complete) in
            [(false, false), (true, false), (false, true), (true, true)]
        {
            let mut fixture = SetupFixture::new();
            fixture
                .backend
                .commit(|state| {
                    state.engine = fixture
                        .root
                        .join("custom yt-dlp")
                        .to_string_lossy()
                        .into_owned();
                    state.gallery = fixture
                        .root
                        .join("custom gallery-dl")
                        .to_string_lossy()
                        .into_owned();
                    state.ffmpeg = fixture
                        .root
                        .join("custom ffmpeg")
                        .to_string_lossy()
                        .into_owned();
                    state.setup_complete = previously_complete;
                    Ok(())
                })
                .unwrap();
            let before = std::fs::read(&fixture.backend.path).unwrap();
            if !cancel {
                let fixtures = fixture.backend.updates.fixtures.as_mut().unwrap();
                let bytes = fixtures
                    .get_mut("https://github.com/yt-dlp/yt-dlp/releases/download/test/gallery-dl")
                    .unwrap();
                bytes.truncate(bytes.len() / 2);
            }
            let phases = std::sync::Mutex::new(Vec::new());
            let error = super::automatic_setup(&fixture.backend, |progress| {
                phases.lock().unwrap().push(progress.phase.clone());
                if cancel && progress.tool == "gallery-dl" && progress.received > 0 {
                    fixture
                        .backend
                        .job
                        .lock()
                        .unwrap()
                        .as_ref()
                        .unwrap()
                        .cancel();
                }
            })
            .await
            .unwrap_err();
            assert!(
                if cancel {
                    error.contains("cancelled")
                } else {
                    error.contains("hash or size")
                },
                "{error}"
            );
            assert_eq!(std::fs::read(&fixture.backend.path).unwrap(), before);
            assert_eq!(fixture.reload().setup_complete, previously_complete);
            assert_eq!(
                fixture.backend.local.lock().unwrap().gallery,
                fixture.root.join("custom gallery-dl").to_string_lossy()
            );
            assert!(fixture.backend.job.lock().unwrap().is_none());
            assert!(!phases
                .lock()
                .unwrap()
                .iter()
                .any(|phase| phase == "complete"));
            assert_eq!(
                std::fs::read_dir(fixture.root.join("engines"))
                    .unwrap()
                    .count(),
                0
            );
        }
    }

    #[test]
    fn media_lease_requires_the_active_entry_and_unchanged_source_mapping() {
        let base = std::env::temp_dir().join(format!("torikomi-lease-{}", uuid::Uuid::new_v4()));
        std::fs::create_dir(&base).unwrap();
        let source = base.join("audio.mp3");
        std::fs::write(&source, b"local media fixture").unwrap();
        let source = source.canonicalize().unwrap();
        let mut local = crate::storage::LocalState::fresh(&base);
        let entry = local
            .add_link("https://example.org/audio", "Audio", "audio")
            .unwrap();
        local
            .files
            .insert(entry.id.clone(), source.to_string_lossy().into_owned());
        let metadata = source.metadata().unwrap();
        let lease = super::MediaLease {
            entry_id: entry.id.clone(),
            source: source.clone(),
            path: source.clone(),
            size: metadata.len(),
            modified: metadata.modified().unwrap(),
        };
        assert_eq!(super::lease_path(&local, &lease, &base).unwrap(), source);
        std::fs::write(&source, b"changed local fixture with a different size").unwrap();
        assert!(super::lease_path(&local, &lease, &base).is_err());
        local.files.remove(&entry.id);
        assert!(super::lease_path(&local, &lease, &base).is_err());
        local
            .files
            .insert(entry.id.clone(), source.to_string_lossy().into_owned());
        local.remove_entry(&entry.id).unwrap();
        assert!(super::lease_path(&local, &lease, &base).is_err());
        std::fs::remove_file(source).unwrap();
        std::fs::remove_dir(base).unwrap();
    }
    #[test]
    fn manual_selection_rejects_the_wrong_engine_version_output() {
        assert!(super::tool_version("2026.08.19\n", "yt-dlp"));
        assert!(super::tool_version("1.32.16\n", "gallery-dl"));
        assert!(super::tool_version(
            "ffmpeg version 9.0.2 Copyright",
            "ffmpeg"
        ));
        assert!(super::tool_version("ffprobe version 9.0.2", "ffprobe"));
        assert!(!super::tool_version("2026.08.19\n", "gallery-dl"));
        assert!(!super::tool_version("1.32.16\n", "yt-dlp"));
        assert!(!super::tool_version("not ffmpeg", "ffmpeg"));
        assert!(!super::tool_version("", "gallery-dl"));
    }
    #[test]
    fn local_origin_rejects_ports_and_embedded_credentials() {
        for value in [
            "http://tauri.localhost:9000/",
            "http://user:secret@tauri.localhost/",
            "https://tauri.localhost/",
            "https://example.org/",
        ] {
            assert!(!super::local_origin(&url::Url::parse(value).unwrap()));
        }
        for value in ["http://tauri.localhost/", "tauri://localhost/"] {
            assert!(super::local_origin(&url::Url::parse(value).unwrap()));
        }
    }
}

async fn automatic_setup(
    backend: &Backend,
    progress: impl Fn(crate::updater::SetupProgress) + Send + Sync,
) -> Result<(), String> {
    let job = backend.start_job()?;
    let result = async {
        let installed = backend
            .updates
            .install_initial(job.clone(), &progress)
            .await?;
        backend.commit(|state| {
            if job.cancelled.load(std::sync::atomic::Ordering::SeqCst) {
                return Err("Operation cancelled".into());
            }
            state.engine = installed.engine;
            state.gallery = installed.gallery;
            state.ffmpeg = installed.ffmpeg;
            state.setup_complete = true;
            Ok(())
        })?;
        progress(crate::updater::SetupProgress {
            tool: String::new(),
            phase: "complete".into(),
            received: 0,
            total: 0,
        });
        Ok(())
    }
    .await;
    *lock(&backend.job)? = None;
    result
}

#[tauri::command]
async fn dispatch(
    command: String,
    args: Vec<Value>,
    app: tauri::AppHandle,
    window: WebviewWindow,
    state: tauri::State<'_, Backend>,
) -> Result<Value, String> {
    let origin = window.url().map_err(|e| e.to_string())?;
    if window.label() != "main" || !local_origin(&origin) {
        return Err("Untrusted window".into());
    }
    let backend = state.inner();
    match command.as_str() {
        "load" => backend.view(&window),
        "engine-updates-status" => Ok(json!(backend
            .updates
            .status(&Backend::selected(&*lock(&backend.local)?)))),
        "engine-updates-toggle" => {
            let enabled = args
                .first()
                .and_then(Value::as_bool)
                .ok_or("Choose whether automatic updates are enabled")?;
            backend.updates.set_automatic(enabled)?;
            backend.update(&window)
        }
        "engine-updates-check" => check_engine_updates(backend, &window, true).await,
        "media-open" | "media-compatible" => {
            let info =
                open_media(argument(&args, 0)?, command == "media-compatible", backend).await?;
            backend.update(&window)?;
            Ok(info)
        }
        "media-release" => {
            let url = url::Url::parse(argument(&args, 0)?).map_err(|e| e.to_string())?;
            let token = url.path().trim_start_matches('/');
            lock(&backend.media_leases)?.remove(token);
            Ok(Value::Null)
        }
        "favorite" => {
            let id = argument(&args, 0)?;
            let enabled = args
                .get(1)
                .and_then(Value::as_bool)
                .ok_or("Choose favorite state")?;
            backend.commit(|local| local.favorite(id, enabled))?;
            backend.update(&window)
        }
        "folder-create" => {
            backend.commit(|local| local.create_folder(argument(&args, 0)?))?;
            backend.update(&window)
        }
        "folder-rename" => {
            backend
                .commit(|local| local.rename_folder(argument(&args, 0)?, argument(&args, 1)?))?;
            backend.update(&window)
        }
        "folder-remove" => {
            backend.commit(|local| local.remove_folder(argument(&args, 0)?))?;
            backend.update(&window)
        }
        "folder-add" => {
            backend.commit(|local| local.folder_add(argument(&args, 0)?, argument(&args, 1)?))?;
            backend.update(&window)
        }
        "folder-track-remove" => {
            backend
                .commit(|local| local.folder_remove(argument(&args, 0)?, argument(&args, 1)?))?;
            backend.update(&window)
        }
        "setup-auto" => {
            automatic_setup(backend, |progress| {
                let _ = window.emit("setup-progress", progress);
            })
            .await?;
            backend.update(&window)
        }
        "setup-complete" => {
            let local = lock(&backend.local)?.clone();
            let job = backend.start_job()?;
            let result = async {
                for (path, kind) in [
                    (&local.engine, "yt-dlp"),
                    (&local.gallery, "gallery-dl"),
                    (&local.ffmpeg, "ffmpeg"),
                ] {
                    probe(path, kind, job.clone()).await?;
                }
                backend.commit(|local| {
                    local.setup_complete = true;
                    Ok(())
                })?;
                backend.update(&window)
            }
            .await;
            *lock(&backend.job)? = None;
            result
        }
        "settings" => {
            let prefs = Preferences::parse(args.first().cloned().ok_or("Missing preferences")?)?;
            backend.commit(|local| {
                local.document.settings = Settings {
                    value: prefs,
                    updated_at: local.document.next_time(storage::now())?,
                    device_id: local.device_id.clone(),
                };
                Ok(())
            })?;
            backend.theme(&window)?;
            backend.update(&window)
        }
        "save-link" => {
            let url = core::media_url(argument(&args, 0)?)?;
            let title = args
                .get(1)
                .and_then(Value::as_str)
                .filter(|t| !t.trim().is_empty())
                .map(String::from)
                .unwrap_or_else(|| {
                    url::Url::parse(&url)
                        .ok()
                        .and_then(|u| u.host_str().map(String::from))
                        .unwrap_or_else(|| "Saved link".into())
                });
            let entry = backend.commit(|local| local.add_link(&url, &title, "link"))?;
            backend.update(&window)?;
            Ok(json!(entry))
        }
        "remove-link" => {
            let id = argument(&args, 0)?;
            backend.commit(|local| local.remove_entry(id))?;
            backend.update(&window)
        }
        "choose" => {
            let kind = argument(&args, 0)?;
            if !["folder", "engine", "gallery", "ffmpeg"].contains(&kind) {
                return Err("Invalid file selection".into());
            }
            let dialog = rfd::AsyncFileDialog::new().set_title(if kind == "folder" {
                "Download folder"
            } else {
                "Choose installed executable"
            });
            let choice = if kind == "folder" {
                dialog.pick_folder().await
            } else {
                dialog.pick_file().await
            };
            if let Some(choice) = choice {
                let selected = choice.path().to_string_lossy().into_owned();
                if kind != "folder" {
                    executable(&selected)?;
                    let job = backend.start_job()?;
                    let result = probe(
                        &selected,
                        if kind == "engine" {
                            "yt-dlp"
                        } else if kind == "gallery" {
                            "gallery-dl"
                        } else {
                            "ffmpeg"
                        },
                        job,
                    )
                    .await;
                    *lock(&backend.job)? = None;
                    result?;
                }
                backend.commit(|local| {
                    match kind {
                        "folder" => local.folder = selected,
                        "engine" => local.engine = selected,
                        "gallery" => local.gallery = selected,
                        _ => local.ffmpeg = selected,
                    };
                    Ok(())
                })?;
            }
            backend.update(&window)
        }
        "open-folder" => {
            let folder = lock(&backend.local)?.folder.clone();
            fs::create_dir_all(&folder).map_err(|e| e.to_string())?;
            opener::open(Path::new(&folder)).map_err(|e| e.to_string())?;
            Ok(Value::Null)
        }
        "open-file" => {
            let id = argument(&args, 0)?;
            let local = lock(&backend.local)?;
            if !local
                .document
                .entries
                .iter()
                .any(|e| e.id == id && e.deleted_at == 0)
            {
                return Err("Library entry no longer exists".into());
            }
            let file = local
                .files
                .get(id)
                .ok_or("This file is on another device. Download it here first.")?;
            if !Path::new(file).is_file() {
                return Err("The downloaded file has been moved or deleted".into());
            }
            opener::open(Path::new(file)).map_err(|e| e.to_string())?;
            Ok(Value::Null)
        }
        "inspect" => inspect(argument(&args, 0)?, backend, &window).await,
        "gallery-inspect" => gallery_inspect(argument(&args, 0)?, backend).await,
        "gallery-preview" => {
            let source = core::media_url(argument(&args, 0)?)?;
            let index = args
                .get(1)
                .and_then(Value::as_u64)
                .ok_or("Choose an inspected photo")?;
            let url = {
                let inspected = lock(&backend.gallery_inspected)?;
                let (_, items) = inspected
                    .as_ref()
                    .filter(|(url, _)| url == &source)
                    .ok_or("Inspect this album before previewing photos")?;
                items
                    .iter()
                    .find(|item| item["imageIndex"] == index && item["kind"] == "image")
                    .and_then(|item| item["url"].as_str())
                    .ok_or("Choose a photo from the inspected album")?
                    .to_owned()
            };
            Ok(json!(backend.previews.fetch(&url, &source).await?))
        }
        "library-preview" => {
            let (path, kind, ffmpeg) = {
                let local = lock(&backend.local)?;
                let (path, kind) = local.preview_file(argument(&args, 0)?)?;
                (path, kind, local.ffmpeg.clone())
            };
            if kind == "video" && !ffmpeg.is_empty() {
                executable(&ffmpeg)?;
            }
            Ok(json!(
                backend.previews.local_media(&path, &kind, &ffmpeg).await?
            ))
        }
        "gallery-download" => gallery_download(&args, backend, &window).await,
        "download" => download(&args, backend, &app, &window).await,
        "cancel" => {
            if let Some(job) = lock(&backend.job)?.as_ref() {
                job.cancel();
            }
            Ok(Value::Null)
        }
        "backup-export" => {
            let doc = lock(&backend.local)?.document.clone();
            let bytes = serde_json::to_vec(&doc).map_err(|e| e.to_string())?;
            if bytes.len() > MAX_BYTES {
                return Err("Backup exceeds 2 MiB".into());
            }
            let choice = rfd::AsyncFileDialog::new()
                .set_title("Export Torikomi backup")
                .add_filter("Torikomi JSON", &["json"])
                .set_file_name("torikomi-backup.json")
                .save_file()
                .await;
            if let Some(choice) = choice {
                storage::atomic_write(choice.path(), &bytes)?;
                Ok(json!({"cancelled":false}))
            } else {
                Ok(json!({"cancelled":true}))
            }
        }
        "backup-import-preview" => {
            *lock(&backend.pending)? = None;
            let choice = rfd::AsyncFileDialog::new()
                .set_title("Import Torikomi backup")
                .add_filter("Torikomi JSON", &["json"])
                .pick_file()
                .await;
            if let Some(choice) = choice {
                let doc = Document::parse(&storage::read_bounded(choice.path(), MAX_BYTES)?)?;
                let count = doc.entries.iter().filter(|e| e.deleted_at == 0).count();
                let deleted = doc.entries.len() - count;
                *lock(&backend.pending)? = Some(doc);
                Ok(json!({"cancelled":false,"count":count,"deletedCount":deleted}))
            } else {
                Ok(json!({"cancelled":true,"count":0}))
            }
        }
        "backup-import-apply" => {
            let imported = lock(&backend.pending)?
                .clone()
                .ok_or("Choose and preview a backup first")?;
            backend.commit(|local| {
                local.document = Document::from_value(storage::restore(
                    json!(local.document),
                    json!(imported),
                    &local.device_id,
                    storage::now(),
                )?)?;
                Ok(())
            })?;
            *lock(&backend.pending)? = None;
            backend.theme(&window)?;
            backend.update(&window)
        }
        "backup-import-cancel" => {
            *lock(&backend.pending)? = None;
            Ok(Value::Null)
        }
        _ => Err("Unknown app command".into()),
    }
}

fn local_origin(url: &url::Url) -> bool {
    url.port().is_none()
        && url.username().is_empty()
        && url.password().is_none()
        && ((url.scheme() == "tauri" && url.host_str() == Some("localhost"))
            || (url.scheme() == "http" && url.host_str() == Some("tauri.localhost")))
}

pub fn run() {
    tauri::Builder::default()
        .plugin(tauri_plugin_notification::init())
        .invoke_handler(tauri::generate_handler![dispatch])
        .register_asynchronous_uri_scheme_protocol("media",|context,request,responder|{
            let allowed=context.webview_label()=="main"&&matches!(request.uri().host(),Some("localhost"|"media.localhost"));
            let app=context.app_handle().clone();
            let token=request.uri().path().trim_start_matches('/').to_owned();
            let method=request.method().as_str().to_owned();
            let range=request.headers().get("range").map(|h|h.to_str().unwrap_or("").to_owned());
            tauri::async_runtime::spawn(async move {
                let result:Result<tauri::http::Response<Vec<u8>>,String>=async {
                    if !allowed{return Err("Untrusted media request".into());}
                    let path={let backend=app.state::<Backend>();let leases=lock(&backend.media_leases)?;let lease=leases.get(&token).ok_or("Media session unavailable")?;let local=lock(&backend.local)?;lease_path(&local,lease,backend.path.parent().ok_or("Invalid app data directory")?)?};
                    tokio::task::spawn_blocking(move||media::response(&path,&method,range.as_deref())).await.map_err(|e|e.to_string())?
                }.await;
                let mut response=result.unwrap_or_else(|_|tauri::http::Response::builder().status(404).header("Content-Type","text/plain").body(b"Media unavailable".to_vec()).unwrap());
                response.headers_mut().insert("Access-Control-Allow-Origin",tauri::http::HeaderValue::from_static(if cfg!(windows){"http://tauri.localhost"}else{"tauri://localhost"}));
                responder.respond(response);
            });
        })
        .setup(|app| {
            let default_data = app.path().app_data_dir()?;
            #[cfg(debug_assertions)]
            let data_dir = std::env::var_os("TORIKOMI_DATA_DIR").map(PathBuf::from).unwrap_or_else(|| default_data.clone());
            #[cfg(not(debug_assertions))]
            let data_dir = default_data.clone();
            let path = data_dir.join("library.json");
            let downloads = app.path().download_dir().or_else(|_| app.path().home_dir())?;
            let legacy = if data_dir == default_data { storage::legacy_data_path() } else { None };
            let local = LocalState::load(&path, legacy.as_deref(), &downloads).map_err(std::io::Error::other)?;
            let previews = Previewer::new().map_err(std::io::Error::other)?;
            let updates=EngineUpdater::new(data_dir.clone()).map_err(std::io::Error::other)?;
            app.manage(Backend { local: Mutex::new(local), inspected: Mutex::new(None), gallery_inspected: Mutex::new(None), previews, media_leases:Mutex::new(BTreeMap::new()),updates,update_candidate:Mutex::new(None), pending: Mutex::new(None), job: Mutex::new(None), path });
            let window = tauri::WebviewWindowBuilder::from_config(app, &app.config().app.windows[0])?
                .on_navigation(local_origin)
                .on_new_window(|_, _| tauri::webview::NewWindowResponse::Deny)
                .build()?;
            app.state::<Backend>().theme(&window).map_err(std::io::Error::other)?;
            let handle=app.handle().clone();
            tauri::async_runtime::spawn(async move {
                let mut previous_paths=None;
                loop {
                    let backend=handle.state::<Backend>();
                    let selection=lock(&backend.local).map(|local|(local.setup_complete,Backend::selected(&local)));
                    if let Ok((true,selected))=selection {
                        if previous_paths.as_ref()!=Some(&selected) {
                            let _=backend.updates.refresh_versions(selected.clone()).await;let _=backend.update(&window);
                        }
                        previous_paths=Some(selected.clone());
                        let pending=lock(&backend.update_candidate).map(|value|value.is_some()).unwrap_or(false);
                        if pending {
                            let _=backend.activate_update();
                            if lock(&backend.local).map(|local|Backend::selected(&local)!=selected).unwrap_or(false) {let _=backend.update(&window);}
                        }else{let _=check_engine_updates(&backend,&window,false).await;}
                    }
                    tokio::time::sleep(std::time::Duration::from_secs(60)).await;
                }
            });
            Ok(())
        })
        .on_window_event(|window, event| {
            if let Some(backend) = window.try_state::<Backend>() {
                match event {
                    tauri::WindowEvent::CloseRequested { .. } => { if let Ok(active) = backend.job.lock() { if let Some(job) = active.as_ref() { job.cancel(); } } }
                    tauri::WindowEvent::ThemeChanged(_) => { if let Some(webview) = window.app_handle().get_webview_window("main") { let _ = backend.theme(&webview); let _ = backend.update(&webview); } }
                    _ => {}
                }
            }
        })
        .run(tauri::generate_context!())
        .unwrap_or_else(|error| {
            rfd::MessageDialog::new().set_title("Torikomi").set_level(rfd::MessageLevel::Error).set_description(format!("Torikomi could not start. Your existing library has been preserved.\n\n{error}")).show();
            std::process::exit(1);
        });
}
