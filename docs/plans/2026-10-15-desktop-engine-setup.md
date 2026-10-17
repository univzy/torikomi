# Desktop engine setup and photo albums

Windows/macOS installers carry verified, self-contained gallery-dl, yt-dlp,
FFmpeg and FFprobe binaries. First launch introduces Torikomi, then offers
automatic local setup or manual executable selection. Setup completion and
executable paths remain device-local and never enter portable JSON backups.

Video retains every explicit source format through yt-dlp. A separate photo/album
mode uses gallery-dl, displays individual images, and downloads the explicitly
selected original images. Delegated collections require an individual album URL
so selection indices cannot apply to the wrong child extractor.

Changes reach packaging and licenses, CI for native Windows/Intel Mac/Apple Silicon
Mac installers, Rust setup validation/storage/IPC/library records, desktop UI,
all existing locale catalogs, documentation and regression checks. Existing media,
library entries and settings must survive setup failure and legacy state migration.

Verification: malformed manifests/hashes/path escape, exact album selection and
multi-file output, first-run/manual/automatic UI behavior, existing format/theme/
backup checks, native Windows engine versions and downloads, installer build.
Apple builds run in CI; this Windows workspace cannot execute macOS binaries.
