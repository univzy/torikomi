use crate::core::{
    Document, Entry, Folder, Membership, Preferences, Settings, FAVORITES, MAX_BYTES,
};
use serde::{Deserialize, Serialize};
use serde_json::Value;
use std::{
    collections::BTreeMap,
    fs,
    io::{Read, Write},
    path::{Path, PathBuf},
    time::{SystemTime, UNIX_EPOCH},
};
use uuid::Uuid;

#[derive(Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct LocalState {
    pub device_id: String,
    pub document: Document,
    pub folder: String,
    pub engine: String,
    pub ffmpeg: String,
    #[serde(default)]
    pub gallery: String,
    #[serde(default)]
    pub setup_complete: bool,
    pub files: BTreeMap<String, String>,
}

pub fn now() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis() as u64
}

pub fn restore(local: Value, imported: Value, device: &str, now: u64) -> Result<Value, String> {
    let local = Document::from_value(local)?;
    let imported = Document::from_value(imported)?;
    let mut merged = local.merge(&imported)?;
    merged.settings = Settings {
        value: imported.settings.value,
        device_id: device.into(),
        updated_at: merged.next_time(now)?,
    };
    serde_json::to_value(Document::from_value(
        serde_json::to_value(merged).map_err(|e| e.to_string())?,
    )?)
    .map_err(|e| e.to_string())
}

pub fn read_bounded(path: &Path, max: usize) -> Result<Vec<u8>, String> {
    let file = fs::File::open(path).map_err(|e| e.to_string())?;
    if file.metadata().map_err(|e| e.to_string())?.len() > max as u64 {
        return Err("File exceeds size limit".into());
    }
    let mut bytes = Vec::new();
    file.take(max as u64 + 1)
        .read_to_end(&mut bytes)
        .map_err(|e| e.to_string())?;
    if bytes.len() > max {
        return Err("File exceeds size limit".into());
    }
    Ok(bytes)
}

pub fn atomic_write(path: &Path, bytes: &[u8]) -> Result<(), String> {
    let parent = path.parent().ok_or("Invalid file location")?;
    fs::create_dir_all(parent).map_err(|e| e.to_string())?;
    let temp = parent.join(format!(".torikomi-{}.tmp", Uuid::new_v4()));
    let result = (|| {
        let mut options = fs::OpenOptions::new();
        options.write(true).create_new(true);
        #[cfg(unix)]
        {
            use std::os::unix::fs::OpenOptionsExt;
            options.mode(0o600);
        }
        let mut file = options.open(&temp).map_err(|e| e.to_string())?;
        file.write_all(bytes)
            .and_then(|_| file.sync_all())
            .map_err(|e| e.to_string())?;
        drop(file);
        fs::rename(&temp, path).map_err(|e| e.to_string())
    })();
    if result.is_err() {
        let _ = fs::remove_file(temp);
    }
    result
}

impl LocalState {
    pub fn preview_file(&self, id: &str) -> Result<(PathBuf, String), String> {
        let entry = self
            .document
            .entries
            .iter()
            .find(|entry| {
                entry.id == id
                    && entry.deleted_at == 0
                    && matches!(entry.kind.as_str(), "image" | "video" | "audio")
            })
            .ok_or("This library media entry is unavailable")?;
        let path = self
            .files
            .get(id)
            .map(PathBuf::from)
            .filter(|path| path.is_absolute() && path.is_file())
            .ok_or("Download this media on this device to see its preview")?;
        Ok((path, entry.kind.clone()))
    }

    pub fn check_photo_capacity(&self, url: &str, count: usize) -> Result<(), String> {
        if self.document.entries.len().saturating_add(count) > crate::core::MAX_ENTRIES {
            return Err("This album would exceed the 5000-entry library limit".into());
        }
        let mut candidate = self.clone();
        // Reserve the maximum display-title length before downloading any album files.
        if count > 0 {
            let entry = candidate.add_link(url, &"x".repeat(1000), "image")?;
            for _ in 1..count {
                let mut additional = entry.clone();
                additional.id = Uuid::new_v4().to_string();
                candidate.document.entries.push(additional);
            }
        }
        Document::from_value(serde_json::to_value(&candidate.document).map_err(|e| e.to_string())?)
            .map(|_| ())
            .map_err(|_| {
                "Not enough library capacity for this selection; select fewer photos".into()
            })
    }

    pub fn fresh(downloads: &Path) -> Self {
        let device_id = Uuid::new_v4().to_string();
        Self {
            document: Document {
                schema_version: 2,
                settings: Settings {
                    updated_at: 0,
                    device_id: device_id.clone(),
                    value: Preferences::default(),
                },
                entries: vec![],
                folders: vec![],
                memberships: vec![],
            },
            device_id,
            folder: downloads.join("Torikomi").to_string_lossy().into(),
            engine: String::new(),
            ffmpeg: String::new(),
            gallery: String::new(),
            setup_complete: false,
            files: BTreeMap::new(),
        }
    }

    pub fn load(path: &Path, legacy: Option<&Path>, downloads: &Path) -> Result<Self, String> {
        let source = if path.exists() {
            Some(path)
        } else {
            legacy.filter(|p| p.exists())
        };
        let state = if let Some(source) = source {
            let raw: Value = serde_json::from_slice(&read_bounded(source, 8 * MAX_BYTES)?)
                .map_err(|e| {
                    format!("Local data could not be read; the original file is preserved: {e}")
                })?;
            let mut state: Self = serde_json::from_value(raw.clone()).map_err(|e| e.to_string())?;
            state.document = Document::from_value(raw["document"].clone())?;
            if state.device_id.is_empty()
                || state.device_id.len() > 100
                || !Path::new(&state.folder).is_absolute()
                || [&state.engine, &state.ffmpeg, &state.gallery]
                    .iter()
                    .any(|s| !s.is_empty() && !Path::new(s).is_absolute())
                || state.files.len() > 5000
                || state.files.values().any(|s| !Path::new(s).is_absolute())
            {
                return Err("Invalid local data; the original file is preserved".into());
            }
            state
        } else {
            Self::fresh(downloads)
        };
        if !path.exists() {
            state.persist(path)?;
        }
        Ok(state)
    }

    pub fn persist(&self, path: &Path) -> Result<(), String> {
        Document::from_value(serde_json::to_value(&self.document).map_err(|e| e.to_string())?)?;
        atomic_write(path, &serde_json::to_vec(self).map_err(|e| e.to_string())?)
    }

    pub fn add_link(&mut self, url: &str, title: &str, kind: &str) -> Result<Entry, String> {
        if self.document.entries.len() >= crate::core::MAX_ENTRIES {
            return Err("Library has reached the 5000-entry limit".into());
        }
        let url = crate::core::media_url(url)?;
        if !["link", "video", "audio", "image"].contains(&kind) {
            return Err("Invalid media kind".into());
        }
        let time = self.document.next_time(now())?;
        let entry = Entry {
            id: Uuid::new_v4().to_string(),
            url,
            title: title.chars().take(1000).collect(),
            kind: kind.into(),
            created_at: time,
            updated_at: time,
            device_id: self.device_id.clone(),
            deleted_at: 0,
            metadata: None,
        };
        let mut next = self.document.clone();
        next.entries.push(entry.clone());
        self.set_document(next)?;
        Ok(entry)
    }

    fn set_document(&mut self, document: Document) -> Result<(), String> {
        self.document =
            Document::from_value(serde_json::to_value(document).map_err(|e| e.to_string())?)?;
        Ok(())
    }

    pub fn create_folder(&mut self, name: &str) -> Result<Folder, String> {
        let time = self.document.next_time(now())?;
        let folder = Folder {
            id: Uuid::new_v4().to_string(),
            name: name.trim().into(),
            created_at: time,
            updated_at: time,
            device_id: self.device_id.clone(),
            deleted_at: 0,
        };
        let mut next = self.document.clone();
        next.folders.push(folder.clone());
        self.set_document(next)?;
        Ok(folder)
    }

    pub fn rename_folder(&mut self, id: &str, name: &str) -> Result<(), String> {
        let mut next = self.document.clone();
        let time = next.next_time(now())?;
        let folder = next
            .folders
            .iter_mut()
            .find(|f| f.id == id && f.deleted_at == 0)
            .ok_or("Folder unavailable")?;
        folder.name = name.trim().into();
        folder.updated_at = time;
        folder.device_id = self.device_id.clone();
        self.set_document(next)
    }

    pub fn remove_folder(&mut self, id: &str) -> Result<(), String> {
        let mut next = self.document.clone();
        let time = next.next_time(now())?;
        let folder = next
            .folders
            .iter_mut()
            .find(|f| f.id == id && f.deleted_at == 0)
            .ok_or("Folder unavailable")?;
        folder.deleted_at = time;
        folder.updated_at = time;
        folder.device_id = self.device_id.clone();
        for member in next
            .memberships
            .iter_mut()
            .filter(|m| m.folder_id == id && m.deleted_at == 0)
        {
            member.updated_at = time;
            member.deleted_at = time;
            member.device_id = self.device_id.clone();
        }
        self.set_document(next)
    }

    fn membership(&self, next: &mut Document, folder: &str, id: &str, enabled: bool, time: u64) {
        if let Some(member) = next
            .memberships
            .iter_mut()
            .find(|m| m.folder_id == folder && m.entry_id == id)
        {
            member.updated_at = time;
            member.device_id = self.device_id.clone();
            member.deleted_at = if enabled { 0 } else { time };
        } else {
            next.memberships.push(Membership {
                folder_id: folder.into(),
                entry_id: id.into(),
                updated_at: time,
                device_id: self.device_id.clone(),
                deleted_at: if enabled { 0 } else { time },
            });
        }
    }

    pub fn favorite(&mut self, id: &str, enabled: bool) -> Result<(), String> {
        if !self
            .document
            .entries
            .iter()
            .any(|e| e.id == id && e.kind == "audio" && e.deleted_at == 0)
        {
            return Err("Audio entry unavailable".into());
        }
        let mut next = self.document.clone();
        let time = next.next_time(now())?;
        if !enabled {
            for member in next
                .memberships
                .iter_mut()
                .filter(|m| m.entry_id == id && m.deleted_at == 0)
            {
                member.updated_at = time;
                member.deleted_at = time;
                member.device_id = self.device_id.clone();
            }
        }
        self.membership(&mut next, FAVORITES, id, enabled, time);
        self.set_document(next)
    }

    pub fn folder_add(&mut self, folder: &str, id: &str) -> Result<(), String> {
        if folder == FAVORITES {
            return self.favorite(id, true);
        }
        if !self
            .document
            .folders
            .iter()
            .any(|f| f.id == folder && f.deleted_at == 0)
            || !self
                .document
                .entries
                .iter()
                .any(|e| e.id == id && e.kind == "audio" && e.deleted_at == 0)
        {
            return Err("Folder or audio entry unavailable".into());
        }
        let mut next = self.document.clone();
        let time = next.next_time(now())?;
        self.membership(&mut next, FAVORITES, id, true, time);
        self.membership(&mut next, folder, id, true, time);
        self.set_document(next)
    }

    pub fn folder_remove(&mut self, folder: &str, id: &str) -> Result<(), String> {
        if folder == FAVORITES {
            return self.favorite(id, false);
        }
        if !self
            .document
            .folders
            .iter()
            .any(|f| f.id == folder && f.deleted_at == 0)
            || !self
                .document
                .entries
                .iter()
                .any(|e| e.id == id && e.kind == "audio" && e.deleted_at == 0)
        {
            return Err("Folder or audio entry unavailable".into());
        }
        let mut next = self.document.clone();
        let time = next.next_time(now())?;
        self.membership(&mut next, folder, id, false, time);
        self.set_document(next)
    }

    pub fn remove_entry(&mut self, id: &str) -> Result<(), String> {
        let mut next = self.document.clone();
        let time = next.next_time(now())?;
        let entry = next
            .entries
            .iter_mut()
            .find(|e| e.id == id && e.deleted_at == 0)
            .ok_or("Entry unavailable")?;
        entry.updated_at = time;
        entry.deleted_at = time;
        entry.device_id = self.device_id.clone();
        for member in next
            .memberships
            .iter_mut()
            .filter(|m| m.entry_id == id && m.deleted_at == 0)
        {
            member.updated_at = time;
            member.deleted_at = time;
            member.device_id = self.device_id.clone();
        }
        self.set_document(next)
    }
}

pub fn legacy_data_path() -> Option<PathBuf> {
    #[cfg(target_os = "windows")]
    {
        std::env::var_os("APPDATA").map(|p| PathBuf::from(p).join("torikomi-desktop/library.json"))
    }
    #[cfg(target_os = "macos")]
    {
        std::env::var_os("HOME").map(|p| {
            PathBuf::from(p).join("Library/Application Support/torikomi-desktop/library.json")
        })
    }
    #[cfg(not(any(target_os = "windows", target_os = "macos")))]
    {
        None
    }
}
