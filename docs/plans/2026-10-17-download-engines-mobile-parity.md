# Downloadable desktop engines and mobile parity

User scope: desktop installers contain the application, with engine download or
manual selection offered at first launch. Existing configured engines keep working.
Android and iOS receive the desktop media-library, favorite-folder and player flows.
The user explicitly selected native iOS direct-media downloads without a server.

Desktop setup reuses verified engine preparation and downloads separate gzip assets.
A small manifest embedded in the application pins each asset's compressed/expanded
size and SHA256. Installation is staged, validated, probed and committed atomically;
failed/cancelled transfers preserve existing selections and setup completion.
The existing updater continues to manage installed engines. The release workflow
publishes separate engine assets alongside app installers, rather than placing
executables inside installers.

Mobile parity covers thumbnails, filters, local photo/video/audio opening, audio
metadata where available, named favorite folders, JSON schema2 preservation and a
native now-playing queue with compact controls and highlighted single-line tracks.
Playback history stays removed. Existing theme/font/language/layout settings remain.
Android retains its platform runtime/FFmpeg and downloads verified pure-Python
gallery-dl dependencies for supported photos/albums on first use.
iOS uses AVFoundation/URLSession and direct HTTPS URLs; no desktop CLI installation.

Independent implementation areas: Android, iOS, native desktop setup, and root
packaging/UI/localization/CI integration. Verify Rust setup rollback and bounds,
desktop browser/native UI, locale generation, Android unit/lint/debug build, and
iOS portable core/native compilation through macOS CI where available.

Engine files must be available at their pinned release URLs before network setup
can succeed. Prepare and verify the assets locally; publishing them is a separate
external action requiring explicit authorization. Do not claim unpublished downloads
are live or native iOS execution was tested on Windows.
