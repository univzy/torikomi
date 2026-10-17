# Desktop engine auto-update

Status: approved by the user with "oke ubah" and implemented. Deployment of the
workflow and first public engine feed remain outside this local change. See the
[verification record](../../verification/2026-10-16-media-engine-updates.md).

The user's subsequent media requirements are captured separately in
[Audio metadata, History, favorites, and playback](2026-10-16-media-library-player-design.md).
Neither proposal cancels the other. Media work can proceed independently, and the
updater's idle/activation checks must account for its native media-processing jobs.

## Intent and scope

The user wants FFmpeg, gallery-dl, and yt-dlp to update automatically when a newer
version is available. Apply this to the current Windows/macOS desktop work:
Windows x86_64, macOS Intel, and macOS Apple Silicon. FFprobe updates together with
FFmpeg. Keep first-run installation offline and keep user-managed executable files
available. Users must not need Python, Homebrew, an administrator account, or a new
server account to receive engine updates.

Android's existing yt-dlp integration and iOS's direct-media implementation are
outside this desktop updater. This does not add application self-update.

## Existing code and upstream constraints

- `shared/desktop/src-tauri/src/setup.rs` installs a complete bundled tool set into
  a directory named after its manifest hash. It verifies the platform, architecture,
  executable names, and SHA256 hashes before returning paths.
- `app.rs` persists selected paths atomically and owns inspection/download jobs.
  The installed application resources must remain immutable.
- `storage.rs` keeps device-only executable paths outside portable JSON backups.
- `scripts/prepare_desktop_engines.py` already creates standalone gallery-dl on
  each desktop host and verifies the bundled executables.
- [yt-dlp](https://github.com/yt-dlp/yt-dlp#update) supplies standalone Windows and
  universal macOS releases and supports self-update. Its self-update alone cannot
  update the other engines or coordinate a rollback of the complete tool set.
- [gallery-dl](https://github.com/mikf/gallery-dl#installation) now directs active
  development to Codeberg. Its documented standalone downloads cover Windows and
  Linux, so a native standalone macOS executable still needs packaging by Torikomi.
- [FFmpeg](https://ffmpeg.org/download.html) publishes source releases and links to
  third-party executable providers. The project's existing Apple Silicon source
  build can supply matching FFmpeg/FFprobe without relying on a user's compiler.

## Approach

Use tested engine packages published by Torikomi's GitHub Actions, independently
of application installers. The app reads only the pinned `univzy/torikomi` engine
feed. A scheduled workflow checks upstream stable versions, packages changed tools
on native runners, runs compatibility tests, and publishes a complete update.

Directly calling `yt-dlp -U` is smaller but leaves FFmpeg and macOS gallery-dl
uncovered and modifies files in place. Running pip/Homebrew on users' computers
adds external runtimes and changes user-managed installations. Neither satisfies
the requested standalone desktop experience.

### User-visible behavior

1. Automatic engine updates are enabled by default for Torikomi-managed tools.
2. Check asynchronously after completed setup and on application startup when the
   previous attempt is at least 24 hours old. While the app stays open, check again
   every 24 hours. Offline failures do not block startup or downloads.
3. Download newer compatible packages in the background. Keep using the current
   tools while downloading. Activate a verified update when no inspection,
   download, setup, or other update activation is running.
4. Settings shows the installed versions of gallery-dl, yt-dlp, FFmpeg, and FFprobe,
   last check, current update state, an automatic-update toggle, and a Check now
   button. Show whether a selected executable is user-managed.
5. Keep the previous verified installation while validating a new candidate.
   Failed candidates leave the current selected versions unchanged.
6. Never replace a manually selected executable. Existing automatic setup lets
   users switch back to Torikomi-managed tools if they want automatic updates.
7. Updates mean the latest stable versions that have passed Torikomi's packaging
   and compatibility checks, rather than an immediate promise for every upstream
   commit. Both feed publication and the app's check interval affect availability.

### Publishing the engine feed

- Add a separate scheduled/manual engine workflow. Check upstream once per day;
  skip native builds when the supported stable version tuple has not changed.
- Obtain yt-dlp release binaries and upstream checksums. Obtain gallery-dl wheels
  from PyPI with their release hashes, preserving the locked build dependencies.
  Resolve FFmpeg stable source releases and verify their upstream release signature
  against a pinned project key before building on the native targets. Preserve
  static LAME/Opus, PNG/zlib, HTTPS, and existing minimum OS requirements.
- Build standalone gallery-dl for each platform. Use the existing packaging and
  signing helpers; keep source references and license notices alongside each set.
- Test executable versions, architecture, runtime dependencies, album extraction,
  and FFmpeg conversion/frame extraction before publishing any candidate.
- Publish versioned engine releases with an `engines-` prefix. Mark these GitHub
  releases as prereleases solely to keep them out of the application's normal
  latest-release channel; their engine contents must be stable upstream versions.
  The updater explicitly selects this engine release series. Publish only after
  every target artifact is uploaded and verified, using a draft release first.
- Package each executable as an individual gzip asset. The manifest lists exact
  compressed/uncompressed sizes and SHA256 hashes, actual engine versions,
  platform, architecture, minimum app version, bundle revision, source references,
  and the hash of its license-notice asset. Download only changed executables.
- Extend `release.yml` to validate updater contracts and feed metadata. Publishing
  scheduled engine updates must not trigger Android/iOS application releases.
- Preparing code and workflow files does not publish a release. Actual automatic
  delivery begins once the changes and first successful engine feed are deployed
  to the repository. No existing repository secrets are printed or rewritten.

### Native updater and persistence

- Add a focused Rust updater module, called by the existing trusted IPC dispatcher
  and by a native background scheduler. Reuse reqwest, SHA256, process execution,
  native version probes, and atomic state persistence already in the application.
- HTTPS only. Pin the repository and select asset URLs from that release's API
  metadata. Validate redirect destinations, names, platform, architecture, version,
  sizes, timeouts, and hashes before executing anything. UI arguments and imported
  backups cannot supply update repositories, executable URLs, or arbitrary paths.
- Download into a private staging directory under the app's engine directory.
  Bound compressed downloads and decompressed executable size; gzip carries one
  explicitly named file, so no archive-provided filesystem paths are extracted.
  Maximum executable size remains 512 MiB. License/metadata reads are bounded too.
- Build a complete candidate set by reusing unchanged managed tools. Verify every
  file and probe all four native executables before committing the selected paths.
  FFmpeg and FFprobe must have matching versions. Reject downgrades and packages
  requiring a newer app or unsupported OS/architecture.
- Install to a new immutable directory and atomically persist the selected set.
  Do not overwrite a running executable. Recheck selected paths before committing
  so a manual selection made during a background download is respected.
- Failed downloads, malformed manifests, failed probes, and failed persistence
  retain the current installation. Never remove the current or previous set.
  Clean abandoned staging files and obsolete unreferenced sets within the owned
  engine directory; a locked file is retained rather than forcing removal.
- Automatic activation waits for idle operation. An explicit Check now reports
  progress and a useful outcome; errors have a retry action. Permit only one
  updater run at a time, including checks requested through IPC.
- Store automatic-update preference, check timestamps, version metadata, selected
  managed set, and previous managed set locally with migration defaults. Preserve
  the portable JSON schema and exclude these device-only update records.
- If no feed exists yet, report that no update package is available and retain
  the bundled tools. Network failure is distinct from already up to date.

### Settings design

Extend the existing engine settings, preserving the user's themes and layout.
ENERGY 2 / RHYTHM 1 / MOTION 1 remain appropriate: a compact version list, one update
action, and a progress indicator only while work is pending. No new illustrations
or decorative assets are needed. Localize added strings across the current 24
languages. Preserve keyboard access, visible focus, RTL, light/dark themes,
reduced motion, and larger font settings.

## Acceptance checks

- An older managed installation discovers and activates a newer compatible set;
  all three engine versions and matching FFprobe become visible after restart.
- No-change checks transfer metadata only. A single changed engine downloads only
  that engine; an FFmpeg update downloads both FFmpeg and FFprobe.
- Partial transfers, wrong hashes, wrong platform, oversized gzip output, missing
  assets, mismatched FFprobe, invalid URLs, and failed probes never activate tools.
- Startup remains usable offline. Automatic checks obey the interval; manual
  checks bypass the interval. Turning the toggle off stops future automatic work.
- Downloads/inspection keep using their selected tool set. Activation waits until
  idle and never overwrites a user-managed tool or a concurrent manual selection.
- A failed candidate leaves the selected versions unchanged after restart.
- JSON import/export remains compatible and contains no executable paths or
  machine-specific updater state.
- Native Windows integration tests use an isolated profile and local fixtures;
  release CI runs equivalent engine checks on both macOS architectures.
- Browser tests cover update progress, no update, missing feed, offline/error,
  retry, toggle, and manual paths, including RTL and increased text size.

## Review boundary

This is the concrete proposed design. Implementation follows review of this spec;
the existing installer has not been rebuilt for this proposed feature.
