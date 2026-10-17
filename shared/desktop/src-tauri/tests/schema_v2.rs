use serde_json::{json, Value};
use torikomi_desktop::{
    core::{document, merge, Document},
    storage::LocalState,
};

fn legacy() -> Value {
    serde_json::from_str(include_str!("../../../../sync/fixtures/v1.json")).unwrap()
}

fn modern() -> Value {
    let mut value = legacy();
    value["schemaVersion"] = json!(2);
    value["entries"][0]["kind"] = json!("audio");
    value["entries"][0]["metadata"] = json!({"artist":"Artist","album":"Album","albumArtist":"Artist","trackNumber":2,"year":2026,"genre":"Pop","duration":123.5});
    value["folders"] = json!([{"id":"music","name":"MusicKu","createdAt":10,"updatedAt":10,"deviceId":"a","deletedAt":0}]);
    value["memberships"] = json!([
        {"folderId":"favorites","entryId":"fixture-link","updatedAt":10,"deviceId":"a","deletedAt":0},
        {"folderId":"music","entryId":"fixture-link","updatedAt":10,"deviceId":"a","deletedAt":0}
    ]);
    value
}

#[test]
fn schema_one_migrates_without_fabricating_metadata_or_favorites() {
    let migrated = document(legacy()).unwrap();
    assert_eq!(migrated["schemaVersion"], 2);
    assert_eq!(migrated["folders"], json!([]));
    assert_eq!(migrated["memberships"], json!([]));
    assert_eq!(migrated["entries"], legacy()["entries"]);
}

#[test]
fn metadata_and_independent_membership_tombstones_survive_merge() {
    let original = modern();
    let mut remote = original.clone();
    remote["memberships"][1]["updatedAt"] = json!(20);
    remote["memberships"][1]["deletedAt"] = json!(20);
    remote["memberships"][0]["updatedAt"] = json!(5);
    let result = merge(original.clone(), remote.clone()).unwrap();
    assert_eq!(result["entries"][0]["metadata"]["artist"], "Artist");
    assert_eq!(result["memberships"][0]["deletedAt"], 0);
    assert_eq!(result["memberships"][1]["deletedAt"], 20);
    assert_eq!(result, merge(remote, original).unwrap());
    assert_eq!(result, document(result.clone()).unwrap());
}

#[test]
fn schema_two_rejects_bad_metadata_references_names_and_duplicate_pairs() {
    for (key, bad) in [
        ("trackNumber", json!(-1)),
        ("year", json!(10000)),
        ("duration", json!(-1)),
        ("artist", json!("x".repeat(1001))),
    ] {
        let mut value = modern();
        value["entries"][0]["metadata"][key] = bad;
        assert!(document(value).is_err(), "metadata {key}");
    }
    for (field, bad) in [("folderId", "missing"), ("entryId", "missing")] {
        let mut value = modern();
        value["memberships"][0][field] = json!(bad);
        assert!(document(value).is_err());
    }
    let mut value = modern();
    value["entries"][0]["kind"] = json!("video");
    assert!(document(value).is_err());
    let mut value = modern();
    value["folders"][0]["name"] = json!(" ");
    assert!(document(value).is_err());
    let mut value = modern();
    value["memberships"][1] = value["memberships"][0].clone();
    assert!(document(value).is_err());
}

#[test]
fn metadata_strips_private_artwork_and_paths() {
    let mut value = modern();
    value["entries"][0]["metadata"]["coverUrl"] = json!("https://private.example/art.jpg");
    value["entries"][0]["metadata"]["coverBytes"] = json!("secret");
    assert_eq!(
        document(value).unwrap()["entries"][0]["metadata"],
        modern()["entries"][0]["metadata"]
    );
}

#[test]
fn schema_two_collections_count_tombstones_and_next_time_includes_memberships() {
    let mut value = modern();
    value["memberships"][0]["updatedAt"] = json!(9_007_199_254_740_991_u64);
    assert!(Document::from_value(value).unwrap().next_time(0).is_err());
    let mut value = modern();
    value["folders"] = json!((0..101).map(|i| json!({"id":format!("f{i}"),"name":format!("Folder {i}"),"createdAt":0,"updatedAt":0,"deviceId":"a","deletedAt":1})).collect::<Vec<_>>());
    assert!(document(value).is_err());
}

#[test]
fn storage_folder_actions_preserve_other_collections_and_media_records() {
    let mut state = LocalState::fresh(&std::env::temp_dir());
    state.document = Document::from_value(modern()).unwrap();
    let second = state.create_folder(" Other ").unwrap();
    state.folder_add(&second.id, "fixture-link").unwrap();
    state.folder_remove("music", "fixture-link").unwrap();
    assert!(state
        .document
        .memberships
        .iter()
        .any(|m| m.folder_id == second.id && m.deleted_at == 0));
    state.rename_folder(&second.id, "Renamed").unwrap();
    state.remove_folder(&second.id).unwrap();
    assert!(state
        .document
        .memberships
        .iter()
        .any(|m| m.folder_id == "favorites" && m.deleted_at == 0));
    state.favorite("fixture-link", false).unwrap();
    assert!(state.document.memberships.iter().all(|m| m.deleted_at > 0));
    assert_eq!(state.document.entries[0].deleted_at, 0);
    let before = serde_json::to_value(&state.document).unwrap();
    assert!(state.create_folder("  ").is_err());
    assert!(state.rename_folder("favorites", "Oops").is_err());
    assert!(state.favorite("fixture-removed", true).is_err());
    assert_eq!(serde_json::to_value(&state.document).unwrap(), before);
}

#[test]
fn deletion_tombstones_references_and_stale_backups_cannot_restore_them() {
    let original = modern();
    let mut state = LocalState::fresh(&std::env::temp_dir());
    state.document = Document::from_value(original.clone()).unwrap();
    let file = std::env::temp_dir().join(format!("torikomi-kept-{}.mp3", uuid::Uuid::new_v4()));
    std::fs::write(&file, b"original media").unwrap();
    state
        .files
        .insert("fixture-link".into(), file.to_string_lossy().into_owned());
    state.remove_entry("fixture-link").unwrap();
    let merged = state
        .document
        .merge(&Document::from_value(original).unwrap())
        .unwrap();
    assert!(
        merged
            .entries
            .iter()
            .find(|e| e.id == "fixture-link")
            .unwrap()
            .deleted_at
            > 0
    );
    assert!(merged.memberships.iter().all(|m| m.deleted_at > 0));
    assert_eq!(std::fs::read(&file).unwrap(), b"original media");
    assert_eq!(state.files.len(), 1);
    std::fs::remove_file(file).unwrap();
}

#[test]
fn collection_changes_that_exceed_document_bytes_leave_original_unchanged() {
    let mut state = LocalState::fresh(&std::env::temp_dir());
    let template = state.add_link("https://e.co/", "", "audio").unwrap();
    state.document.entries = (0..2000)
        .map(|i| {
            let mut entry = template.clone();
            entry.id = format!("e{i}");
            entry
        })
        .collect();
    let mut remaining =
        torikomi_desktop::core::MAX_BYTES - 5 - serde_json::to_vec(&state.document).unwrap().len();
    for entry in &mut state.document.entries {
        let count = remaining.min(1000);
        entry.title = "x".repeat(count);
        remaining -= count;
    }
    assert_eq!(remaining, 0);
    let before = serde_json::to_value(&state.document).unwrap();
    assert!(Document::from_value(before.clone()).is_ok());
    assert!(state.create_folder("MusicKu").is_err());
    assert!(state.favorite("e0", true).is_err());
    assert_eq!(serde_json::to_value(&state.document).unwrap(), before);
}

#[test]
fn collection_limits_count_membership_tombstones() {
    let mut value = modern();
    value["entries"] = json!((0..201).map(|i| json!({"id":format!("e{i}"),"url":"https://e.co/","title":"","kind":"audio","createdAt":0,"updatedAt":0,"deviceId":"a","deletedAt":0})).collect::<Vec<_>>());
    value["folders"] = json!((0..100).map(|i| json!({"id":format!("f{i}"),"name":format!("F{i}"),"createdAt":0,"updatedAt":0,"deviceId":"a","deletedAt":0})).collect::<Vec<_>>());
    value["memberships"] = json!((0..20000).map(|i| json!({"folderId":format!("f{}",i % 100),"entryId":format!("e{}",i / 100),"updatedAt":0,"deviceId":"a","deletedAt":1})).collect::<Vec<_>>());
    assert!(document(value.clone()).is_ok());
    value["memberships"]
        .as_array_mut()
        .unwrap()
        .push(json!({"folderId":"f0","entryId":"e200","updatedAt":0,"deviceId":"a","deletedAt":1}));
    assert_eq!(document(value).unwrap_err(), "Unsupported backup document");
}

#[test]
fn folder_names_normalize_and_failed_mutations_do_not_commit() {
    let mut state = LocalState::fresh(&std::env::temp_dir());
    let folder = state.create_folder(" MusicKu ").unwrap();
    assert_eq!(folder.name, "MusicKu");
    let before = serde_json::to_value(&state.document).unwrap();
    assert!(state.create_folder("MusicKu").is_err());
    assert!(state.rename_folder(&folder.id, &"x".repeat(81)).is_err());
    assert!(state.folder_add(&folder.id, "missing").is_err());
    assert_eq!(serde_json::to_value(&state.document).unwrap(), before);
}

#[test]
fn shared_v2_fixture_roundtrips_without_portable_media_paths() {
    let value: Value =
        serde_json::from_str(include_str!("../../../../sync/fixtures/v2.json")).unwrap();
    assert_eq!(document(value.clone()).unwrap(), value);
}
