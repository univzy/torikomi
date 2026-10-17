# Audio metadata, History, favorite folders, and in-app playback

Status: approved by the user with "oke ubah" and implemented. See the
[verification record](../../verification/2026-10-16-media-engine-updates.md).
This extends the desktop work alongside the separate
[engine auto-update design](2026-10-16-engine-auto-update-design.md).

## User intent

Downloaded audio should have its actual song title, artist, album, and artwork
where those values are available. Torikomi should show a download History with
thumbnails for video, photos, and audio, filter by media type, open downloaded
media inside the application, and organize favorite audio into named collections
such as the user's example, MusicKu. Audio playback should use familiar Spotify
patterns while retaining Torikomi's selectable themes and typography.

Implementation scope is the current Windows/macOS desktop applications. Shared
JSON readers/writers on Android and iOS must also preserve the new portable data.
This is not a promise of new mobile playback screens in the desktop change.
The media feature can be implemented and tested independently of auto-update;
the updater must later respect active download and media-processing operations.

## Existing behavior and affected boundaries

- `src-tauri/src/core.rs::download_arguments` selects exact formats and optional
  audio conversion, but does not request embedded metadata or artwork.
- `src-tauri/src/app.rs` currently retains the source title/formats after inspection
  and registers only title, URL, kind, and local filename after downloading.
- `src-tauri/src/preview.rs` already supplies bounded offline photo/video previews.
  Audio artwork needs to join this path without treating an attached cover image
  as a video download.
- `ui/app.js::renderEntries` displays downloaded entries and opens files through
  the system application. Its observer, thumbnail cache, search, and pagination
  should be reused for History rather than duplicated.
- The current portable document is schema 1. Rust, Android, and Swift readers
  serialize a fixed set of fields; merely adding favorite fields in desktop JSON
  would let another platform silently discard them on export.

## Screen and interaction design

Use ui-ux-pro-max and the existing direction: ENERGY 2 / RHYTHM 1 / MOTION 1.
Artwork and the current track form the focal point. Repeated History rows help
comparison, and the accent identifies active playback, selection, and favorites.
Retain working light/dark/gradient themes, reduced motion, RTL, and font choices.

### Navigation and History

Sidebar destinations: Download, History, Favorites, Settings. History replaces the
visible Library label while reusing its underlying page and media records.

History contains search and filters All, Video, Photos, Audio. Sort by newest
download first and show real dates, thumbnails, title, artist when available,
media type, and duration where meaningful. Search matches title, artist, album,
and source URL. Preserve paging rather than rendering the entire library.

Each downloaded row has Open/Play, Open in system app, and the existing removal
action. Audio also has Favorite and Add to folder. A video opens the in-app video
viewer; a photo opens the image viewer; an audio opens the audio player. A saved
link without a local download remains in All with an explicit saved-link state.
Entries whose local file is missing remain visible and offer Download again.
Removing a library record must warn if it also removes favorite references;
removing records never deletes the actual downloaded file.

### Favorites as folders

Favorites opens a folder view with a built-in All favorite audio collection and
user-created folders. MusicKu is an example supplied by the user, not a seeded
folder or fabricated playlist. Let users create, rename, and remove folders.
Names are trimmed, nonempty, bounded to 80 characters, and cannot duplicate a
visible folder name. Folders are logical collections, not filesystem directories.

Hearting an audio immediately adds it to All favorite audio. Add to folder opens
a folder chooser with Create folder available there, so users can add it to MusicKu
without leaving the track. A track may belong to multiple folders. Adding a track
to a folder also favorites it. Unfavoriting removes it from favorite collections,
but leaves its History record and media file. Removing a track from one user folder
does not remove it from other folders or All favorite audio. Removing a folder
keeps its audio favorited and leaves every file untouched.

Opening a folder shows its name, actual track count, artwork from its own tracks,
Play action, and a track list with title, artist, duration, play, and folder actions.
No fake tracks, listening statistics, or external music service are introduced.

### Audio player

Use a single audio playback session shared by every page. A persistent bottom bar
contains the current artwork, title, artist, favorite action, previous/play/pause/
next, seek position and duration, shuffle/repeat, and volume. Every shown control
must work. A larger Now playing view emphasizes the cover and track information.

Play from a favorite folder queues that folder in its visible order. Play from
History queues audio in the current filtered/sorted result. Capture that queue so
changing a search or page does not unexpectedly stop or reorder current playback.
Only one audio track plays at a time; starting a video pauses audio. Viewing a photo
does not interrupt audio. Starting playback requires a user gesture; application
startup never starts sound automatically.

At narrow widths, retain artwork/title and the primary play control in the mini
player; expose the complete controls in Now playing. Reserve content space so the
bottom bar never covers focused controls, the end of a list, or dialogs. All icons
need accessible names and states, range controls need labels, and keyboard use
must not intercept typing in search fields. Closing a viewer returns focus to its
trigger. Audio continues while navigating between Download, History, and Favorites.

### Video and photo viewers

Provide an in-app video viewer with playback controls, seeking, volume, and full
screen. The photo viewer displays the original supported image with fit/zoom and
an explicit close action. Preserve the existing system-app action as an alternative.
Do not feed the entire media file through JSON/base64 IPC.

Prefer the system WebView's native media elements first. Browser codec support
varies across WebView2 and WKWebView, so do not claim every downloaded container
is playable. An unsupported audio format may use a cached MP3 playback copy made
with the already bundled FFmpeg/LAME, with progress and cancel. Keep the original
file unchanged. An unsupported video/image displays a useful format error and
the system-app action instead of silently opening an external app or hanging.

## Metadata and artwork

Retain normalized source metadata during inspection and store it when the actual
download succeeds. Prefer the source track title over the video title when present.
Use real artist/album-artist, album, track number, genre, release year, and duration
where supplied. Missing artist/album remains unknown; do not guess from a hyphenated
title or present the uploader as a verified musician. The source title remains a
valid fallback title. Keep uploader information distinct from artist metadata.

Use yt-dlp's existing metadata and thumbnail postprocessors rather than building a
new tag writer. Apply audio metadata by default, normalize cover images to a
supported format, and verify the output tags/artwork using FFprobe or the relevant
container reader in integration tests. Official standalone yt-dlp includes mutagen;
manual engines may lack it and must report artwork limitations without discarding
a successful audio download.

Cover embedding must respect the selected output container. MP3, M4A, FLAC, and
Opus support the corresponding yt-dlp artwork path. WAV/raw AAC must not be passed
to a thumbnail postprocessor that rejects them. Preserve their selected format,
write supported tags, and retain artwork locally for Torikomi instead. A missing or
inaccessible source thumbnail must not destroy the downloaded audio. Report a
partial metadata/artwork failure honestly and retain the usable original output.

Old downloads are not rewritten automatically. Read their existing tags and cover
when displayed/played; an unknown value remains unknown. Artwork priority is an
embedded cover, then the locally retained source cover, then an unavailable state.
Use the same lazy, bounded thumbnail service as video/photos. A file whose only
image stream is attached cover art is audio, not a video History entry.

References: [yt-dlp postprocessing](https://github.com/yt-dlp/yt-dlp#post-processing-options)
and [thumbnail format handling](https://github.com/yt-dlp/yt-dlp/blob/master/yt_dlp/postprocessor/embedthumbnail.py).

## Media access and storage

Expose playback only for a nondeleted library entry with an existing mapped local
media file. A request carries an entry ID, not an arbitrary filesystem path. Resolve
and validate the mapping in Rust for every request. Cache files are private app data
and cannot be selected through an untrusted URL or imported backup.

Use Tauri's native URI-protocol handling with GET/HEAD and bounded byte-range reads,
correct media types, content length, and invalid-range responses. This supports
seeking in large files without reading the whole file into memory. Limit concurrent
reads and metadata/FFmpeg work. Allow only the media protocol in CSP media/image
sources; retain the trusted-window check and the existing navigation restrictions.
Do not allow a broad home-directory filesystem scope or a public localhost server.

The protocol must reject deleted/unknown entries, path traversal, nonmedia paths,
malformed ranges, and unsupported methods. Native WebView2 and WKWebView tests
must confirm playback and seeking under the actual CSP before shipping.

Keep the existing device-only mapping of entry IDs to downloaded files. Cache
artwork and any compatible playback copies by entry ID and file modification
identity; invalidate them when a file changes or a record is removed. Preserve
bounded storage and remove only app-owned unreferenced cache files. A canceled
conversion must stop FFmpeg and remove its incomplete cache output.

## Portable document migration

Introduce schema 2 instead of silently adding fields that schema-1 readers drop.
Accept schema-1 backups and migrate their entries/preferences without creating
favorites or fabricated metadata. Export schema 2 after migration. Existing older
applications reject this newer schema instead of claiming a complete import.

Schema 2 adds optional normalized metadata to entries, user folder records, and
membership records. Folder records contain an ID, name, created/updated timestamps,
device ID, and deletion timestamp. Memberships are identified by the pair of folder
ID and entry ID and have independent revision/deletion timestamps. Reserve a stable
system folder ID for All favorite audio. This avoids losing unrelated membership
changes when JSON backups from two devices are merged.

Use the existing greatest `(updatedAt, deviceId)` rule independently for entries,
folders, and each membership pair. Retain tombstones. Deleted entries/folders never
appear through stale memberships. Enforce valid references and bounded strings,
5,000 entries, 100 user folders, 20,000 membership records including tombstones, and
the existing total 2 MiB portable document limit before committing a change.
Favorites contain audio entries only in this requested version.

Update the Rust, Android, and Swift document readers/writers and shared fixtures
together so current platform exports preserve folders and metadata. Portable JSON
includes the folder structure, memberships, and textual metadata, but excludes media
files, cover image bytes, cache paths, engine paths, and credentials. Imported
entries without local media retain their folders but cannot play until downloaded
on that device. Download again from an existing record must preserve its entry ID
and favorite memberships when the source and media kind match.

## Implementation boundaries

- Extend Rust document/storage types and shared format fixtures first; test schema
  migration, merge conflicts, tombstones, references, and atomic failure behavior.
- Extend the existing download/inspection and preview services for metadata and
  audio artwork. Keep exact format selection and original audio choices working.
- Add a focused local media protocol service and reuse the existing FFmpeg process
  lifecycle for bounded metadata reading or compatible audio copies.
- Extend the current History rendering rather than introducing another library.
  Add the Favorites page/folder controls and one shared player state in the UI.
- Extend the existing generated locale catalog for every new control and state.
- Extend desktop/native tests and `release.yml` with metadata, cover, URI ranges,
  playback, favorites, and schema interoperability checks. Do not publish or replace
  the user's installed application as a side effect of implementing the feature.

## Acceptance checks

- Download an audio fixture containing source title, artist, album, and cover.
  Confirm real embedded tags/artwork in MP3, M4A, FLAC, and Opus outputs. WAV/raw AAC
  retains its requested format and still has visible local artwork in Torikomi.
- Missing artwork or missing mutagen leaves a usable download with an accurate
  outcome message. A failed media transfer is never registered as a completed
  download. No invented artist/title splitting is applied.
- Audio with attached art stays in Audio; all filters and search return the correct
  History rows. Existing local photo/video previews continue to work offline.
- Create MusicKu, favorite two real fixture tracks, add/remove folder membership,
  rename/reopen the folder, and restart. No media file is copied, moved, or deleted.
- Export/import between current Rust/Android/Swift schemas preserves metadata,
  folders, membership deletions, and existing schema-1 data; no local paths leak.
- Play audio from History and MusicKu, seek, pause, skip, shuffle/repeat, and change
  volume. Navigate away and back without stopping playback. Start a video and check
  that no second audio session keeps playing underneath.
- Large-file seeking uses bounded range reads. Malformed ranges, unknown entry IDs,
  missing files, and cache-conversion cancellation cannot expose unrelated files.
- Test real native audio/video/photo viewers on Windows and both Mac architectures,
  including an audio playback-copy fixture for an unsupported native codec.
- Verify empty/loading/error/retry states, RTL, all theme modes, reduced motion,
  keyboard controls, and larger text. Persistent playback controls must not cover
  a row action or focused element at supported window sizes.

## Review boundary

This is a proposed design, not an implemented player or metadata feature. Review
this media design together with the separate engine auto-update proposal before
implementation. No new installer has been produced for either pending proposal.
