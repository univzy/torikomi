use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use std::collections::{BTreeMap, HashSet};

pub const MAX_BYTES: usize = 2 * 1024 * 1024;
pub const MAX_ENTRIES: usize = 5000;
pub const MAX_FOLDERS: usize = 100;
pub const MAX_MEMBERSHIPS: usize = 20_000;
pub const FAVORITES: &str = "favorites";
pub const MAX_TIME: u64 = 9_007_199_254_740_991;

#[derive(Clone, Debug, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Preferences {
    pub theme_mode: String,
    pub theme_color: String,
    pub font_scale: f64,
    pub font_family: String,
    pub layout: String,
    pub language: String,
    pub notifications: bool,
    pub glass: bool,
}

impl Default for Preferences {
    fn default() -> Self {
        Self {
            theme_mode: "system".into(),
            theme_color: "violet".into(),
            font_scale: 1.0,
            font_family: "system".into(),
            layout: "comfortable".into(),
            language: "system".into(),
            notifications: true,
            glass: false,
        }
    }
}

impl Preferences {
    pub fn parse(value: Value) -> Result<Self, String> {
        if !value.is_object() {
            return Err("Invalid preferences".into());
        }
        let prefs: Self = serde_json::from_value(value).map_err(|e| e.to_string())?;
        let themes: Vec<Value> = serde_json::from_str(include_str!("../../../catalog/themes.json"))
            .map_err(|e| e.to_string())?;
        if !["system", "light", "dark"].contains(&prefs.theme_mode.as_str())
            || !themes.iter().any(|t| t["id"] == prefs.theme_color)
            || !["system", "sans", "serif", "mono"].contains(&prefs.font_family.as_str())
            || !["comfortable", "compact"].contains(&prefs.layout.as_str())
            || !prefs.font_scale.is_finite()
            || !(1.0..=1.5).contains(&prefs.font_scale)
            || prefs.language.encode_utf16().count() > 35
        {
            return Err("Invalid preferences".into());
        }
        Ok(prefs)
    }
}

#[derive(Clone, Debug, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Settings {
    pub updated_at: u64,
    pub device_id: String,
    pub value: Preferences,
}

#[derive(Clone, Debug, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct MediaMetadata {
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub artist: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub album: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub album_artist: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub track_number: Option<u32>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub genre: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub year: Option<u32>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub duration: Option<f64>,
}

#[derive(Clone, Debug, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Entry {
    pub id: String,
    pub url: String,
    pub title: String,
    pub kind: String,
    pub created_at: u64,
    pub updated_at: u64,
    pub device_id: String,
    pub deleted_at: u64,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub metadata: Option<MediaMetadata>,
}

#[derive(Clone, Debug, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Folder {
    pub id: String,
    pub name: String,
    pub created_at: u64,
    pub updated_at: u64,
    pub device_id: String,
    pub deleted_at: u64,
}

#[derive(Clone, Debug, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Membership {
    pub folder_id: String,
    pub entry_id: String,
    pub updated_at: u64,
    pub device_id: String,
    pub deleted_at: u64,
}

#[derive(Clone, Debug, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Document {
    pub schema_version: u32,
    pub settings: Settings,
    pub entries: Vec<Entry>,
    #[serde(default)]
    pub folders: Vec<Folder>,
    #[serde(default)]
    pub memberships: Vec<Membership>,
}

pub fn media_url(raw: &str) -> Result<String, String> {
    if raw.len() > 8192 || raw.chars().any(|c| c.is_control()) {
        return Err("Invalid URL".into());
    }
    let url = url::Url::parse(raw.trim()).map_err(|_| "Invalid URL")?;
    if !["http", "https"].contains(&url.scheme())
        || !url.username().is_empty()
        || url.password().is_some()
        || url.host_str().is_none()
    {
        return Err("Use an HTTP(S) URL without embedded credentials".into());
    }
    Ok(url.into())
}

fn stamp(time: u64, device: &str) -> Result<(), String> {
    if time > MAX_TIME || device.is_empty() || device.encode_utf16().count() > 100 {
        return Err("Invalid revision".into());
    }
    Ok(())
}

impl Document {
    pub fn parse(bytes: &[u8]) -> Result<Self, String> {
        if bytes.len() > MAX_BYTES {
            return Err("Backup exceeds 2 MiB".into());
        }
        Self::from_value(serde_json::from_slice(bytes).map_err(|e| e.to_string())?)
    }

    pub fn from_value(raw: Value) -> Result<Self, String> {
        let version = raw.get("schemaVersion").and_then(Value::as_u64);
        if !matches!(version, Some(1 | 2))
            || (version == Some(2)
                && (!raw.get("folders").is_some_and(Value::is_array)
                    || !raw.get("memberships").is_some_and(Value::is_array)))
        {
            return Err("Unsupported backup document".into());
        }
        let prefs = Preferences::parse(
            raw.get("settings")
                .and_then(|s| s.get("value"))
                .cloned()
                .ok_or("Missing settings")?,
        )?;
        let mut doc: Self = serde_json::from_value(raw).map_err(|e| e.to_string())?;
        if doc.entries.len() > MAX_ENTRIES
            || doc.folders.len() > MAX_FOLDERS
            || doc.memberships.len() > MAX_MEMBERSHIPS
        {
            return Err("Unsupported backup document".into());
        }
        doc.schema_version = 2;
        stamp(doc.settings.updated_at, &doc.settings.device_id)?;
        doc.settings.value = prefs;
        let mut ids = HashSet::new();
        for entry in &mut doc.entries {
            stamp(entry.updated_at, &entry.device_id)?;
            if entry.id.is_empty()
                || entry.id.encode_utf16().count() > 160
                || !ids.insert(entry.id.clone())
                || entry.title.encode_utf16().count() > 1000
                || !["video", "audio", "image", "link"].contains(&entry.kind.as_str())
                || entry.created_at > MAX_TIME
                || entry.deleted_at > MAX_TIME
            {
                return Err("Invalid library entry".into());
            }
            entry.url = media_url(&entry.url)?;
            if let Some(meta) = &entry.metadata {
                if [&meta.artist, &meta.album, &meta.album_artist, &meta.genre]
                    .iter()
                    .any(|value| {
                        value.as_ref().is_some_and(|v| {
                            v.encode_utf16().count() > 1000 || v.chars().any(char::is_control)
                        })
                    })
                    || meta.track_number == Some(0)
                    || meta.year.is_some_and(|v| !(1..=9999).contains(&v))
                    || meta
                        .duration
                        .is_some_and(|v| !v.is_finite() || !(0.0..=MAX_TIME as f64).contains(&v))
                {
                    return Err("Invalid media metadata".into());
                }
            }
        }
        let mut folder_ids = HashSet::new();
        let mut names = HashSet::new();
        for folder in &mut doc.folders {
            stamp(folder.updated_at, &folder.device_id)?;
            folder.name = folder.name.trim().to_string();
            if folder.id.is_empty()
                || folder.id == FAVORITES
                || folder.id.encode_utf16().count() > 160
                || !folder_ids.insert(folder.id.clone())
                || folder.name.is_empty()
                || folder.name.encode_utf16().count() > 80
                || folder.name.chars().any(char::is_control)
                || folder.created_at > MAX_TIME
                || folder.deleted_at > MAX_TIME
                || (folder.deleted_at == 0 && !names.insert(folder.name.clone()))
            {
                return Err("Invalid favorite folder".into());
            }
        }
        let mut pairs = HashSet::new();
        let audio_ids: HashSet<&str> = doc
            .entries
            .iter()
            .filter(|e| e.kind == "audio")
            .map(|e| e.id.as_str())
            .collect();
        for member in &doc.memberships {
            stamp(member.updated_at, &member.device_id)?;
            if member.deleted_at > MAX_TIME
                || !pairs.insert((&member.folder_id, &member.entry_id))
                || (member.folder_id != FAVORITES && !folder_ids.contains(&member.folder_id))
                || !audio_ids.contains(member.entry_id.as_str())
            {
                return Err("Invalid favorite membership".into());
            }
        }
        if serde_json::to_vec(&doc).map_err(|e| e.to_string())?.len() > MAX_BYTES {
            return Err("Backup exceeds 2 MiB".into());
        }
        Ok(doc)
    }

    pub fn next_time(&self, now: u64) -> Result<u64, String> {
        let last = self
            .entries
            .iter()
            .map(|e| e.updated_at)
            .chain(self.folders.iter().map(|f| f.updated_at))
            .chain(self.memberships.iter().map(|m| m.updated_at))
            .chain([self.settings.updated_at])
            .max()
            .unwrap_or(0);
        let time = now.max(last.saturating_add(1));
        if time > MAX_TIME {
            return Err("Revision timestamp exceeds supported range".into());
        }
        Ok(time)
    }

    pub fn merge(&self, other: &Self) -> Result<Self, String> {
        Self::from_value(serde_json::to_value(self).map_err(|e| e.to_string())?)?;
        Self::from_value(serde_json::to_value(other).map_err(|e| e.to_string())?)?;
        let mut entries: BTreeMap<String, Entry> = self
            .entries
            .iter()
            .map(|e| (e.id.clone(), e.clone()))
            .collect();
        for e in &other.entries {
            if entries
                .get(&e.id)
                .map(|old| newer(e.updated_at, &e.device_id, old.updated_at, &old.device_id))
                .unwrap_or(true)
            {
                entries.insert(e.id.clone(), e.clone());
            }
        }
        let settings = if newer(
            self.settings.updated_at,
            &self.settings.device_id,
            other.settings.updated_at,
            &other.settings.device_id,
        ) {
            self.settings.clone()
        } else {
            other.settings.clone()
        };
        let mut folders: BTreeMap<String, Folder> = self
            .folders
            .iter()
            .map(|f| (f.id.clone(), f.clone()))
            .collect();
        for folder in &other.folders {
            if folders
                .get(&folder.id)
                .map(|old| {
                    newer(
                        folder.updated_at,
                        &folder.device_id,
                        old.updated_at,
                        &old.device_id,
                    )
                })
                .unwrap_or(true)
            {
                folders.insert(folder.id.clone(), folder.clone());
            }
        }
        let mut memberships: BTreeMap<(String, String), Membership> = self
            .memberships
            .iter()
            .map(|m| ((m.folder_id.clone(), m.entry_id.clone()), m.clone()))
            .collect();
        for member in &other.memberships {
            let key = (member.folder_id.clone(), member.entry_id.clone());
            if memberships
                .get(&key)
                .map(|old| {
                    newer(
                        member.updated_at,
                        &member.device_id,
                        old.updated_at,
                        &old.device_id,
                    )
                })
                .unwrap_or(true)
            {
                memberships.insert(key, member.clone());
            }
        }
        Self::from_value(
            json!({"schemaVersion":2,"settings":settings,"entries":entries.into_values().collect::<Vec<_>>(),"folders":folders.into_values().collect::<Vec<_>>(),"memberships":memberships.into_values().collect::<Vec<_>>()}),
        )
    }
}

fn newer(at: u64, ad: &str, bt: u64, bd: &str) -> bool {
    at > bt || (at == bt && ad.encode_utf16().cmp(bd.encode_utf16()).is_ge())
}

pub fn document(raw: Value) -> Result<Value, String> {
    serde_json::to_value(Document::from_value(raw)?).map_err(|e| e.to_string())
}

pub fn merge(a: Value, b: Value) -> Result<Value, String> {
    serde_json::to_value(Document::from_value(a)?.merge(&Document::from_value(b)?)?)
        .map_err(|e| e.to_string())
}

fn codec<'a>(format: &'a Value, key: &str) -> &'a str {
    format.get(key).and_then(Value::as_str).unwrap_or("none")
}

pub fn inspect_formats(raw: &Value) -> Result<Vec<Value>, String> {
    let list: Vec<&Value> = raw
        .get("formats")
        .and_then(Value::as_array)
        .filter(|items| !items.is_empty())
        .map(|items| items.iter().collect())
        .unwrap_or_else(|| vec![raw]);
    let mut formats = Vec::new();
    let mut seen = HashSet::new();
    for f in list {
        let Some(id) = f.get("format_id").and_then(Value::as_str) else {
            continue;
        };
        let video = codec(f, "vcodec") != "none";
        let audio = codec(f, "acodec") != "none";
        let ext = f["ext"].as_str().unwrap_or("unknown");
        let image = ["jpg", "jpeg", "png", "webp", "gif", "avif", "heic"].contains(&ext);
        // Direct media often has an exact format ID but no codec metadata.
        let original = f["vcodec"].is_null()
            && f["acodec"].is_null()
            && [
                "mp4", "mkv", "webm", "mov", "m4v", "avi", "flv", "mpeg", "mpg", "ts", "m4a",
                "mp3", "aac", "opus", "ogg", "flac", "wav",
            ]
            .contains(&ext);
        if (!video && !audio && !image && !original)
            || !safe_format_id(id)
            || !seen.insert(id)
            || f["has_drm"] == true
        {
            continue;
        }
        let kind = if image {
            "image"
        } else if original && ["m4a", "mp3", "aac", "opus", "ogg", "flac", "wav"].contains(&ext) {
            "audio"
        } else if original {
            "source"
        } else if video && audio {
            "muxed"
        } else if video {
            "video"
        } else {
            "audio"
        };
        let resolution = f["height"]
            .as_u64()
            .map(|h| format!("{h}p"))
            .unwrap_or_else(|| {
                if audio && !video {
                    "Audio".into()
                } else {
                    "Video".into()
                }
            });
        let label = format!(
            "{} · {} · {} · {}{}",
            resolution,
            ext,
            id,
            codec(f, if video { "vcodec" } else { "acodec" }),
            if video && !audio {
                " · video only"
            } else {
                ""
            }
        );
        let mut item = json!({"id":id,"label":label,"kind":kind,"ext":ext,"vcodec":codec(f,"vcodec"),"acodec":codec(f,"acodec")});
        for key in ["height", "width", "fps", "abr"] {
            if f[key].is_number() {
                item[key] = f[key].clone();
            }
        }
        if let Some(size) = f
            .get("filesize")
            .filter(|n| n.is_number())
            .or_else(|| f.get("filesize_approx").filter(|n| n.is_number()))
        {
            item["sizeBytes"] = size.clone();
        }
        formats.push(item);
    }
    if formats.is_empty() {
        return Err("No playable formats found".into());
    }
    Ok(formats)
}

fn safe_format_id(id: &str) -> bool {
    !id.is_empty()
        && id.len() <= 160
        && id
            .bytes()
            .all(|c| c.is_ascii_alphanumeric() || b"_-.:".contains(&c))
        && !id.starts_with('-')
}

pub fn format_selector(formats: &[Value], id: &str, audio: Option<&str>) -> Result<String, String> {
    if !safe_format_id(id) {
        return Err("Invalid format selection".into());
    }
    let selected = formats
        .iter()
        .find(|f| f["id"] == id)
        .ok_or("Inspect this URL and select an available format first")?;
    match audio.filter(|s| !s.is_empty()) {
        Some(a) => {
            if !safe_format_id(a)
                || selected["kind"] != "video"
                || !formats.iter().any(|f| f["id"] == a && f["kind"] == "audio")
            {
                return Err("Choose a separate audio format only for video-only formats".into());
            }
            Ok(format!("{id}+{a}"))
        }
        None => Ok(id.into()),
    }
}

pub fn download_arguments(
    url: &str,
    formats: &[Value],
    id: &str,
    audio: Option<&str>,
    output: &str,
    folder: &str,
    ffmpeg: &str,
) -> Result<Vec<String>, String> {
    let url = media_url(url)?;
    let selector = format_selector(formats, id, audio)?;
    if !["original", "mp3", "m4a", "opus", "flac", "wav"].contains(&output)
        || (output != "original"
            && !formats
                .iter()
                .any(|f| f["id"] == id && f["kind"] == "audio"))
    {
        return Err(
            "Audio conversion requires an audio format and a supported output codec".into(),
        );
    }
    let mut args: Vec<String> = [
        "--ignore-config",
        "--no-playlist",
        "--no-colors",
        "--newline",
        "--restrict-filenames",
        "--progress-template",
        "download:progress:%(progress._percent_str)s",
        "--print",
        "after_move:filepath",
        "--progress",
        "-P",
        folder,
        "-o",
        "%(title).150B-%(id)s.%(ext)s",
        "-f",
        &selector,
    ]
    .into_iter()
    .map(String::from)
    .collect();
    if output != "original" {
        args.extend(["-x".into(), "--audio-format".into(), output.into()]);
    }
    if !ffmpeg.is_empty() {
        args.extend(["--ffmpeg-location".into(), ffmpeg.into()]);
    }
    args.extend(["--".into(), url]);
    Ok(args)
}
