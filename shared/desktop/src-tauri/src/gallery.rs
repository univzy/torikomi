use crate::core::{media_url, MAX_ENTRIES};
use serde_json::{json, Value};
use std::{
    collections::{HashMap, HashSet},
    path::{Path, PathBuf},
};

pub const MAX_OUTPUT_BYTES: usize = 32 * 1024 * 1024;
const IMAGE_EXTENSIONS: &[&str] = &[
    "jpg", "jpeg", "png", "webp", "gif", "avif", "heic", "heif", "jxl", "bmp", "tif", "tiff",
];

pub fn parse_inspection(raw: &str) -> Result<Vec<Value>, String> {
    if raw.len() > MAX_OUTPUT_BYTES {
        return Err("Gallery inspection exceeds the safety limit".into());
    }
    let data: Value =
        serde_json::from_str(raw).map_err(|e| format!("Invalid gallery inspection: {e}"))?;
    let messages = data.as_array().ok_or("Invalid gallery inspection")?;
    let mut entries = Vec::new();
    let mut directory = json!({});
    for message in messages {
        let message = message.as_array().ok_or("Invalid gallery message")?;
        match message.first().and_then(Value::as_i64) {
            Some(2) => {
                directory = message
                    .get(1)
                    .filter(|m| m.is_object())
                    .cloned()
                    .ok_or("Invalid album metadata")?;
            }
            Some(3) => {
                if entries.len() == MAX_ENTRIES {
                    return Err("Album exceeds 5000 files; open a smaller album or post".into());
                }
                let raw_url = message
                    .get(1)
                    .and_then(Value::as_str)
                    .ok_or("Invalid photo URL")?;
                // gallery-dl delegates DASH videos to yt-dlp while retaining their carousel position.
                let delegated_video = raw_url.strip_prefix("ytdl:");
                let url = media_url(delegated_video.unwrap_or(raw_url))?;
                let metadata = message
                    .get(2)
                    .filter(|m| m.is_object())
                    .ok_or("Invalid photo metadata")?;
                let parsed = url::Url::parse(&url).map_err(|e| e.to_string())?;
                let inferred = parsed.path().rsplit('.').next().unwrap_or("");
                let ext = metadata["extension"]
                    .as_str()
                    .unwrap_or(inferred)
                    .to_ascii_lowercase();
                if ext.is_empty()
                    || ext.len() > 16
                    || !ext.bytes().all(|b| b.is_ascii_alphanumeric())
                {
                    return Err("Invalid gallery media extension".into());
                }
                let index = entries.len() + 1;
                let kind = if metadata["type"] == "audio"
                    || [
                        "mp3", "m4a", "aac", "ogg", "opus", "wav", "flac", "weba", "wma",
                    ]
                    .contains(&ext.as_str())
                {
                    "audio"
                } else if delegated_video.is_some() {
                    "video"
                } else if IMAGE_EXTENSIONS.contains(&ext.as_str()) {
                    "image"
                } else if ["mp4", "webm", "mkv", "mov", "m4v", "avi"].contains(&ext.as_str()) {
                    "video"
                } else {
                    "unknown"
                };
                let title = metadata["title"]
                    .as_str()
                    .or_else(|| directory["title"].as_str())
                    .or_else(|| metadata["filename"].as_str())
                    .unwrap_or("Photo")
                    .chars()
                    .filter(|c| !c.is_control())
                    .take(1000)
                    .collect::<String>();
                let mut entry = json!({"id":format!("gallery-{index}"), "imageIndex":index, "url":url,
                    "title":title, "ext":ext, "kind":kind});
                for key in ["width", "height"] {
                    if let Some(size) = metadata[key].as_u64().filter(|n| *n > 0 && *n <= 1_000_000)
                    {
                        entry[key] = json!(size);
                    }
                }
                entries.push(entry);
            }
            // --range is local to an extractor; a queued page cannot share these indices safely.
            Some(6) => {
                return Err(
                    "Open an individual album or post URL instead of a delegated collection".into(),
                )
            }
            Some(-1) => {
                return Err(
                    "Gallery extraction failed; check the URL and whether the site requires login"
                        .into(),
                )
            }
            _ => return Err("Unsupported gallery message".into()),
        }
    }
    if !entries.iter().any(|e| e["kind"] == "image") {
        return Err("No supported photos found in this album".into());
    }
    Ok(entries)
}

pub fn parse_inspection_output(raw: &str, diagnostic: &str) -> Result<Vec<Value>, String> {
    let result = parse_inspection(raw);
    let diagnostic = diagnostic.trim();
    if !diagnostic.is_empty()
        && (result.is_err()
            || diagnostic
                .lines()
                .any(|line| line.contains("][error]") || line.starts_with("ERROR:")))
    {
        return Err(format!(
            "Photo inspection failed: {}",
            diagnostic.chars().take(2000).collect::<String>()
        ));
    }
    result
}

fn arguments() -> Vec<String> {
    [
        "--config-ignore",
        "--no-input",
        "--no-colors",
        "--quiet",
        "--http-timeout",
        "30",
        "--retries",
        "3",
        "-o",
        "output.mode=null",
    ]
    .into_iter()
    .map(String::from)
    .collect()
}

pub fn inspect_args(source: &str) -> Result<Vec<String>, String> {
    let source = media_url(source)?;
    let mut args = arguments();
    args.extend(["--dump-json".into(), "--".into(), source]);
    Ok(args)
}

pub fn photo_ranges(items: &[Value], selected_indices: &[u32]) -> Result<Vec<u32>, String> {
    if items.len() > MAX_ENTRIES
        || selected_indices.is_empty()
        || selected_indices.len() > MAX_ENTRIES
    {
        return Err("Select at least one inspected photo".into());
    }
    let mut photos = HashMap::new();
    let mut rank = 0;
    for (index, item) in items.iter().enumerate() {
        let index = index as u32 + 1;
        if item["imageIndex"].as_u64() != Some(index as u64) {
            return Err("Inspect this album again before choosing photos".into());
        }
        let ext = item["ext"]
            .as_str()
            .ok_or("Invalid inspected photo extension")?;
        let image = IMAGE_EXTENSIONS.contains(&ext);
        if item["kind"] == "image" && !image {
            return Err("Invalid inspected photo type".into());
        }
        if image {
            rank += 1;
            // The CLI filter counts extensions; delegated videos can never be selected as photos.
            if item["kind"] == "image" {
                photos.insert(index, rank);
            }
        }
    }
    let mut selected = selected_indices
        .iter()
        .map(|index| {
            photos
                .get(index)
                .copied()
                .ok_or_else(|| "Select only photos from the inspected album".to_owned())
        })
        .collect::<Result<Vec<u32>, String>>()?;
    selected.sort_unstable();
    selected.dedup();
    Ok(selected)
}

pub fn download_args(
    source: &str,
    selected_indices: &[u32],
    output_dir: &Path,
) -> Result<Vec<String>, String> {
    let source = media_url(source)?;
    if selected_indices.is_empty()
        || selected_indices.len() > MAX_ENTRIES
        || selected_indices
            .iter()
            .any(|n| *n == 0 || *n as usize > MAX_ENTRIES)
    {
        return Err("Select at least one inspected photo (indices 1–5000)".into());
    }
    let output_dir = output_dir
        .canonicalize()
        .map_err(|e| format!("Could not access download folder: {e}"))?;
    if !output_dir.is_dir() {
        return Err("Choose an existing download folder".into());
    }
    let mut indices = selected_indices.to_vec();
    indices.sort_unstable();
    indices.dedup();
    let range = indices
        .iter()
        .map(u32::to_string)
        .collect::<Vec<_>>()
        .join(",");
    let mut args = arguments();
    args.extend([
        "--range".into(),
        range,
        "--directory".into(),
        output_dir.to_str().ok_or("Invalid download folder")?.into(),
        "--filename".into(),
        format!(
            "{}-{{filename|id|num!s:.100}}.{{extension}}",
            uuid::Uuid::new_v4()
        ),
        "--restrict-filenames".into(),
        "windows".into(),
        "-o".into(),
        "extractor.skip=enumerate".into(),
        "-o".into(),
        "extractor.children=false".into(),
        // --filter runs before --range; callers convert album indices with photo_ranges first.
        "--filter".into(),
        format!(
            "(extension or '').lower() in ({})",
            IMAGE_EXTENSIONS
                .iter()
                .map(|e| format!("'{e}'"))
                .collect::<Vec<_>>()
                .join(",")
        ),
        // Lowercase --print disables downloads; --Print preserves them.
        "--Print".into(),
        "after:{_path!j}".into(),
        "--".into(),
        source,
    ]);
    Ok(args)
}

pub fn parse_output_files(output: &str, root: &Path) -> Result<Vec<PathBuf>, String> {
    if output.len() > MAX_OUTPUT_BYTES {
        return Err("Gallery output exceeds the safety limit".into());
    }
    let root = root.canonicalize().map_err(|e| e.to_string())?;
    let mut files = Vec::new();
    let mut seen = HashSet::new();
    for line in output.lines().filter(|line| !line.trim().is_empty()) {
        if files.len() == MAX_ENTRIES {
            return Err("Too many downloaded gallery files".into());
        }
        let raw: String = serde_json::from_str(line).map_err(|_| "Invalid gallery output path")?;
        let path = Path::new(&raw);
        if !path.is_absolute()
            || path
                .components()
                .any(|c| c == std::path::Component::ParentDir)
        {
            return Err("Invalid gallery output path".into());
        }
        let path = path
            .canonicalize()
            .map_err(|e| format!("Could not find downloaded photo: {e}"))?;
        let ext = path
            .extension()
            .and_then(|e| e.to_str())
            .unwrap_or("")
            .to_ascii_lowercase();
        if !path.starts_with(&root) || !path.is_file() || !IMAGE_EXTENSIONS.contains(&ext.as_str())
        {
            return Err(
                "Downloaded photo is outside the destination or has an unsupported extension"
                    .into(),
            );
        }
        if seen.insert(path.clone()) {
            files.push(path);
        }
    }
    if files.is_empty() {
        return Err("gallery-dl did not report any completed photo downloads".into());
    }
    Ok(files)
}
