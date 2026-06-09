# yt-dlp Refactor Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace Torikomi's external-extension download system with an embedded yt-dlp engine (`youtubedl-android` + bundled FFmpeg), gated behind a required first-launch "download yt-dlp" step, with a universal single-URL Home flow.

**Architecture:** A new `YtDlpEngine` (singleton on the `Torikomi` Application) wraps `youtubedl-android` for init / update / `getInfo` / `execute`. Downloads run in a foreground `DownloadService` that writes to an app-private dir, then a `DownloadPublisher` moves the file to the user's destination (MediaStore/legacy default folder or SAF custom folder). The UI becomes: Splash → Onboarding (first run) → Setup gate (download yt-dlp) → Home (paste URL → fetch info → pick quality → download). All extension code, models, UI, catalog, and manifest entries are removed.

**Tech Stack:** Kotlin, Jetpack Compose (Material3), Coroutines/Flow, DataStore, OkHttp, Coil, `io.github.junkfood02.youtubedl-android` (`:library` + `:ffmpeg`), JUnit4.

**Spec:** `docs/superpowers/specs/2026-06-08-ytdlp-refactor-design.md`

**Conventions for this plan**
- Shell is Windows PowerShell. Use `.\gradlew.bat` (not `./gradlew`).
- Compile check: `.\gradlew.bat compileDebugKotlin`
- Unit tests: `.\gradlew.bat testDebugUnitTest` (single class: append `--tests "univzy.torikomi.ClassNameTest"`)
- Full debug build: `.\gradlew.bat assembleDebug`
- Commit after every task with the message shown in its final step.
- Pure-logic units use full TDD (failing test → implement → pass → commit). UI, native-engine, and service code can't be unit-tested without a device; those tasks verify with a compile/build check plus the on-device checklist in Phase 7.

---

## Phase 0 — Dependencies & build configuration

### Task 0.1: Add youtubedl-android + FFmpeg dependencies and native packaging

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `app/build.gradle.kts`

> Version note: this plan pins `youtubedlAndroid = "0.17.2"` (group `io.github.junkfood02.youtubedl-android`, packages remain `com.yausername.*`). Before starting, confirm the latest stable release on https://central.sonatype.com/artifact/io.github.junkfood02.youtubedl-android/library and bump the version number if a newer one exists. Keep the same `:library` + `:ffmpeg` artifact pair.

- [ ] **Step 1: Add version + library aliases to `gradle/libs.versions.toml`**

In the `[versions]` block add:
```toml
youtubedlAndroid = "0.17.2"
```

In the `[libraries]` block add:
```toml
youtubedl-android-library = { group = "io.github.junkfood02.youtubedl-android", name = "library", version.ref = "youtubedlAndroid" }
youtubedl-android-ffmpeg  = { group = "io.github.junkfood02.youtubedl-android", name = "ffmpeg",  version.ref = "youtubedlAndroid" }
```

- [ ] **Step 2: Add dependencies + ABI filters + native packaging to `app/build.gradle.kts`**

In `android { defaultConfig { ... } }`, add an `ndk` block right after `versionName`:
```kotlin
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
```

In `android { ... }` (top level, e.g. after the `buildFeatures` block), add:
```kotlin
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
```

In `dependencies { ... }`, add (next to the other `implementation(...)` lines):
```kotlin
    // yt-dlp engine (Python + yt-dlp bundled, FFmpeg bundled)
    implementation(libs.youtubedl.android.library)
    implementation(libs.youtubedl.android.ffmpeg)
```

- [ ] **Step 3: Add `extractNativeLibs` to the manifest `<application>`**

In `app/src/main/AndroidManifest.xml`, add `android:extractNativeLibs="true"` to the `<application` opening tag (alongside the existing attributes).

- [ ] **Step 4: Verify the dependency graph resolves and project still compiles**

Run: `.\gradlew.bat compileDebugKotlin`
Expected: BUILD SUCCESSFUL (dependencies download; existing code still compiles — nothing has been removed yet).

- [ ] **Step 5: Commit**

```bash
git add gradle/libs.versions.toml app/build.gradle.kts app/src/main/AndroidManifest.xml
git commit -m "build: add youtubedl-android + ffmpeg deps and native packaging"
```

---

## Phase 1 — Pure logic (TDD)

### Task 1.1: `DownloadPaths` — destination path construction

**Files:**
- Create: `app/src/main/java/univzy/torikomi/engine/DownloadPaths.kt`
- Modify: `app/src/main/java/univzy/torikomi/data/repository/SettingsRepository.kt` (remove duplicate map, re-export)
- Modify: `app/src/main/java/univzy/torikomi/ui/screens/SettingsScreen.kt` (fix import)
- Test: `app/src/test/java/univzy/torikomi/engine/DownloadPathsTest.kt`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/univzy/torikomi/engine/DownloadPathsTest.kt`:
```kotlin
package univzy.torikomi.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadPathsTest {
    @Test fun videoTypeMapsToVideoFolder() {
        assertEquals("VIDEO", DownloadPaths.mediaTypeFolder("video"))
    }

    @Test fun audioTypeMapsToMusicFolder() {
        assertEquals("MUSIC", DownloadPaths.mediaTypeFolder("audio"))
    }

    @Test fun imageTypeMapsToImagesFolder() {
        assertEquals("IMAGES", DownloadPaths.mediaTypeFolder("image"))
    }

    @Test fun unknownTypeFallsBackToVideo() {
        assertEquals("VIDEO", DownloadPaths.mediaTypeFolder("whatever"))
    }

    @Test fun relativeDirIsTorikomiPlusTypeFolder() {
        assertEquals("Torikomi/MUSIC", DownloadPaths.relativeDir("audio"))
    }

    @Test fun sanitizeRemovesIllegalFileNameChars() {
        assertEquals("a_b_c", DownloadPaths.sanitizeFileName("a/b:c"))
    }

    @Test fun sanitizeBlankFallsBackToDownload() {
        assertEquals("download", DownloadPaths.sanitizeFileName("   "))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.\gradlew.bat testDebugUnitTest --tests "univzy.torikomi.engine.DownloadPathsTest"`
Expected: FAIL (compilation error — `DownloadPaths` does not exist).

- [ ] **Step 3: Write the implementation**

Create `app/src/main/java/univzy/torikomi/engine/DownloadPaths.kt`:
```kotlin
package univzy.torikomi.engine

/** Single source of truth for download destination paths and file naming. Pure Kotlin (no Android). */
object DownloadPaths {
    /** Maps a history downloadType to the media-type subfolder name. */
    val MEDIA_TYPE_FOLDERS: Map<String, String> = mapOf(
        "music" to "MUSIC",
        "audio" to "MUSIC",
        "videos" to "VIDEO",
        "video" to "VIDEO",
        "pictures" to "IMAGES",
        "image" to "IMAGES",
    )

    fun mediaTypeFolder(type: String): String = MEDIA_TYPE_FOLDERS[type.lowercase()] ?: "VIDEO"

    /** Path relative to the public Downloads dir, e.g. "Torikomi/VIDEO". */
    fun relativeDir(type: String): String = "Torikomi/${mediaTypeFolder(type)}"

    fun sanitizeFileName(name: String): String =
        name.trim().replace(Regex("[\\\\/:*?\"<>|]+"), "_").ifBlank { "download" }
}
```

- [ ] **Step 4: Remove the duplicate map and re-export it from `SettingsRepository.kt`**

In `app/src/main/java/univzy/torikomi/data/repository/SettingsRepository.kt`, delete the existing top-level `MEDIA_TYPE_FOLDERS` declaration (the `val MEDIA_TYPE_FOLDERS = mapOf( ... )` block) and replace it with a re-export so existing imports keep working:
```kotlin
import univzy.torikomi.engine.DownloadPaths

val MEDIA_TYPE_FOLDERS: Map<String, String> = DownloadPaths.MEDIA_TYPE_FOLDERS
```
(Add the `import` with the other imports; place the `val` where the old map was.)

- [ ] **Step 5: Run test to verify it passes**

Run: `.\gradlew.bat testDebugUnitTest --tests "univzy.torikomi.engine.DownloadPathsTest"`
Expected: PASS (7 tests).

- [ ] **Step 6: Verify the app still compiles (SettingsScreen import unchanged still resolves)**

Run: `.\gradlew.bat compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/univzy/torikomi/engine/DownloadPaths.kt app/src/test/java/univzy/torikomi/engine/DownloadPathsTest.kt app/src/main/java/univzy/torikomi/data/repository/SettingsRepository.kt
git commit -m "feat: add DownloadPaths and consolidate MEDIA_TYPE_FOLDERS"
```

---

### Task 1.2: New domain models

**Files:**
- Create: `app/src/main/java/univzy/torikomi/data/model/MediaModels.kt`

> These are plain data classes. No unit test; verified by compile + downstream use.

- [ ] **Step 1: Create the models file**

Create `app/src/main/java/univzy/torikomi/data/model/MediaModels.kt`:
```kotlin
package univzy.torikomi.data.model

enum class MediaKind { VIDEO, AUDIO, MUXED }

/** A single yt-dlp format option, mapped from the library's VideoFormat. */
data class MediaFormat(
    val formatId: String,
    val kind: MediaKind,
    val ext: String,
    val resolution: String?,   // e.g. "1920x1080"; null for audio-only
    val heightPx: Int?,        // null/0 means no video height
    val abrKbps: Int?,         // audio bitrate, if known
    val filesizeApprox: Long?, // bytes, if known
    val note: String,          // human-readable label from yt-dlp
)

/** Result of fetching info for a URL (maps the library's VideoInfo). */
data class MediaInfo(
    val sourceUrl: String,
    val title: String,
    val uploader: String,
    val thumbnail: String,
    val durationSec: Int,
    val isPlaylist: Boolean,
    val detectedSite: String,            // for display + history.platform
    val formats: List<MediaFormat>,
)

/** A user-selectable quality option; carries the yt-dlp format selector. */
data class QualityChoice(
    val label: String,         // "Best (video + audio)", "1080p", "Audio (MP3)"
    val type: String,          // "video" | "audio"  (history downloadType)
    val formatSelector: String,
    val audioExtract: Boolean, // true -> -x --audio-format mp3
)

/** Everything DownloadService needs to perform one download. */
data class DownloadSpec(
    val sourceUrl: String,
    val title: String,
    val thumbnail: String,
    val detectedSite: String,
    val choice: QualityChoice,
)
```

- [ ] **Step 2: Verify it compiles**

Run: `.\gradlew.bat compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/univzy/torikomi/data/model/MediaModels.kt
git commit -m "feat: add yt-dlp domain models (MediaInfo, MediaFormat, QualityChoice, DownloadSpec)"
```

---

### Task 1.3: `QualityPresets` — derive selectable qualities from formats

**Files:**
- Create: `app/src/main/java/univzy/torikomi/engine/QualityPresets.kt`
- Test: `app/src/test/java/univzy/torikomi/engine/QualityPresetsTest.kt`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/univzy/torikomi/engine/QualityPresetsTest.kt`:
```kotlin
package univzy.torikomi.engine

import univzy.torikomi.data.model.MediaFormat
import univzy.torikomi.data.model.MediaKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QualityPresetsTest {

    private fun videoFmt(height: Int) = MediaFormat(
        formatId = "f$height", kind = MediaKind.VIDEO, ext = "mp4",
        resolution = "x$height", heightPx = height, abrKbps = null,
        filesizeApprox = null, note = "${height}p",
    )
    private fun audioFmt() = MediaFormat(
        formatId = "a", kind = MediaKind.AUDIO, ext = "m4a",
        resolution = null, heightPx = null, abrKbps = 128,
        filesizeApprox = null, note = "audio",
    )

    @Test fun alwaysStartsWithBestAndEndsWithAudioMp3() {
        val choices = QualityPresets.from(listOf(videoFmt(720), audioFmt()))
        assertEquals("Best (video + audio)", choices.first().label)
        assertEquals("Audio (MP3)", choices.last().label)
        assertTrue(choices.last().audioExtract)
        assertEquals("audio", choices.last().type)
    }

    @Test fun listsDistinctVideoHeightsDescending() {
        val choices = QualityPresets.from(
            listOf(videoFmt(1080), videoFmt(720), videoFmt(720), videoFmt(480), audioFmt())
        )
        val labels = choices.map { it.label }
        assertEquals(listOf("Best (video + audio)", "1080p", "720p", "480p", "Audio (MP3)"), labels)
    }

    @Test fun heightSelectorIsBounded() {
        val choices = QualityPresets.from(listOf(videoFmt(720), audioFmt()))
        val p720 = choices.first { it.label == "720p" }
        assertEquals("bestvideo[height<=720]+bestaudio/best[height<=720]/best", p720.formatSelector)
        assertEquals("video", p720.type)
    }

    @Test fun bestSelectorMergesVideoAndAudio() {
        val choices = QualityPresets.from(listOf(videoFmt(1080), audioFmt()))
        assertEquals("bestvideo*+bestaudio/best", choices.first().formatSelector)
    }

    @Test fun audioOnlySourceStillOffersBestAndAudio() {
        val choices = QualityPresets.from(listOf(audioFmt()))
        assertEquals(listOf("Best (video + audio)", "Audio (MP3)"), choices.map { it.label })
    }

    @Test fun cappedToFourVideoHeights() {
        val choices = QualityPresets.from(
            listOf(videoFmt(2160), videoFmt(1440), videoFmt(1080), videoFmt(720), videoFmt(480), videoFmt(360))
        )
        val videoLabels = choices.filter { it.type == "video" && it.label != "Best (video + audio)" }
        assertEquals(4, videoLabels.size)
        assertEquals(listOf("2160p", "1440p", "1080p", "720p"), videoLabels.map { it.label })
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.\gradlew.bat testDebugUnitTest --tests "univzy.torikomi.engine.QualityPresetsTest"`
Expected: FAIL (compilation error — `QualityPresets` does not exist).

- [ ] **Step 3: Write the implementation**

Create `app/src/main/java/univzy/torikomi/engine/QualityPresets.kt`:
```kotlin
package univzy.torikomi.engine

import univzy.torikomi.data.model.MediaFormat
import univzy.torikomi.data.model.QualityChoice

/** Derives a small curated list of quality choices from raw yt-dlp formats. Pure Kotlin. */
object QualityPresets {
    private const val MAX_VIDEO_HEIGHTS = 4

    fun from(formats: List<MediaFormat>): List<QualityChoice> {
        val out = mutableListOf<QualityChoice>()
        out += QualityChoice(
            label = "Best (video + audio)",
            type = "video",
            formatSelector = "bestvideo*+bestaudio/best",
            audioExtract = false,
        )
        val heights = formats.mapNotNull { it.heightPx }
            .filter { it > 0 }
            .distinct()
            .sortedDescending()
            .take(MAX_VIDEO_HEIGHTS)
        for (h in heights) {
            out += QualityChoice(
                label = "${h}p",
                type = "video",
                formatSelector = "bestvideo[height<=$h]+bestaudio/best[height<=$h]/best",
                audioExtract = false,
            )
        }
        out += QualityChoice(
            label = "Audio (MP3)",
            type = "audio",
            formatSelector = "bestaudio/best",
            audioExtract = true,
        )
        return out.distinctBy { it.label }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `.\gradlew.bat testDebugUnitTest --tests "univzy.torikomi.engine.QualityPresetsTest"`
Expected: PASS (6 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/univzy/torikomi/engine/QualityPresets.kt app/src/test/java/univzy/torikomi/engine/QualityPresetsTest.kt
git commit -m "feat: add QualityPresets derivation from yt-dlp formats"
```

---

### Task 1.4: Loosen `UrlDetectorService` to accept any URL

**Files:**
- Modify: `app/src/main/java/univzy/torikomi/util/UrlDetectorService.kt`
- Test: `app/src/test/java/univzy/torikomi/util/UrlDetectorServiceTest.kt`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/univzy/torikomi/util/UrlDetectorServiceTest.kt`:
```kotlin
package univzy.torikomi.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlDetectorServiceTest {

    @Test fun acceptsKnownPlatformUrl() {
        assertTrue(UrlDetectorService.isValidMediaUrl("https://youtube.com/watch?v=abc"))
    }

    @Test fun acceptsUnknownButValidHttpUrl() {
        // yt-dlp supports 1800+ sites; any http(s) URL is allowed now.
        assertTrue(UrlDetectorService.isValidMediaUrl("https://some-random-site.example/video/123"))
    }

    @Test fun rejectsNonUrlText() {
        assertFalse(UrlDetectorService.isValidMediaUrl("just some text"))
    }

    @Test fun rejectsNonHttpScheme() {
        assertFalse(UrlDetectorService.isValidMediaUrl("ftp://example.com/file"))
    }

    @Test fun extractsUrlFromSharedText() {
        val shared = "Check this out https://vimeo.com/12345 amazing"
        assertEquals("https://vimeo.com/12345", UrlDetectorService.extractUrl(shared))
    }

    @Test fun extractReturnsNullWhenNoUrl() {
        assertNull(UrlDetectorService.extractUrl("no link here"))
    }

    @Test fun detectPlatformStillWorksForDisplay() {
        assertEquals("YouTube", UrlDetectorService.detectPlatform("https://youtu.be/abc"))
        assertNull(UrlDetectorService.detectPlatform("https://unknown.example/x"))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.\gradlew.bat testDebugUnitTest --tests "univzy.torikomi.util.UrlDetectorServiceTest"`
Expected: FAIL — `acceptsUnknownButValidHttpUrl` fails (current `isValidMediaUrl` requires a known platform), and possibly `extractsUrlFromSharedText` for the unknown host.

- [ ] **Step 3: Update `isValidMediaUrl` and `extractUrl`**

In `app/src/main/java/univzy/torikomi/util/UrlDetectorService.kt`, replace the `isValidMediaUrl` and `extractUrl` functions with:
```kotlin
    /** Any well-formed http(s) URL is acceptable — yt-dlp decides if it's supported. */
    fun isValidMediaUrl(text: String): Boolean {
        val t = text.trim()
        if (!t.startsWith("http://", ignoreCase = true) && !t.startsWith("https://", ignoreCase = true)) return false
        // Require at least scheme + host with a dot.
        return Regex("^https?://[^\\s/]+\\.[^\\s/]+", RegexOption.IGNORE_CASE).containsMatchIn(t)
    }

    fun extractUrl(text: String): String? {
        if (text.isBlank()) return null
        val trimmed = text.trim()
        if (isValidMediaUrl(trimmed)) return trimmed
        val urlRegex = Regex("https?://\\S+", RegexOption.IGNORE_CASE)
        return urlRegex.findAll(text).map { it.value.trimEnd('.', ',', ')', ']') }
            .firstOrNull { isValidMediaUrl(it) }
    }
```
Leave `detectPlatform`, `getPlatformEmoji`, `shortenUrl`, and `platformPatterns` unchanged (still used for display icons/labels).

- [ ] **Step 4: Run test to verify it passes**

Run: `.\gradlew.bat testDebugUnitTest --tests "univzy.torikomi.util.UrlDetectorServiceTest"`
Expected: PASS (7 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/univzy/torikomi/util/UrlDetectorService.kt app/src/test/java/univzy/torikomi/util/UrlDetectorServiceTest.kt
git commit -m "feat: accept any http(s) URL in UrlDetectorService (yt-dlp supports any site)"
```

---

## Phase 2 — Engine

### Task 2.1: `YtDlpEngine` + wire into `Torikomi`, add SettingsRepository yt-dlp state

**Files:**
- Create: `app/src/main/java/univzy/torikomi/engine/YtDlpEngine.kt`
- Modify: `app/src/main/java/univzy/torikomi/data/repository/SettingsRepository.kt`
- Modify: `app/src/main/java/univzy/torikomi/Torikomi.kt`

> Engine wraps the native library; not unit-testable here. Verified by compile + Phase 7 device run.
> **Library API note:** the field/method names below follow `com.yausername.youtubedl_android` (VideoInfo/VideoFormat are Jackson POJOs). If the pinned library version exposes a slightly different name (e.g. `duration` nullability, `fileSize` vs `filesize`), adjust the mapping accordingly — the structure stays the same.

- [ ] **Step 1: Add yt-dlp state accessors to `SettingsRepository.kt`**

In `app/src/main/java/univzy/torikomi/data/repository/SettingsRepository.kt`, add these preference keys next to the existing ones:
```kotlin
private val YTDLP_READY_KEY = booleanPreferencesKey("ytdlp_ready")
private val YTDLP_VERSION_KEY = stringPreferencesKey("ytdlp_version")
```
And add these methods inside `class SettingsRepository`:
```kotlin
    suspend fun isYtDlpReady(): Boolean =
        context.settingsDataStore.data.first()[YTDLP_READY_KEY] == true

    suspend fun setYtDlpReady(ready: Boolean, version: String) {
        context.settingsDataStore.edit {
            it[YTDLP_READY_KEY] = ready
            if (version.isNotBlank()) it[YTDLP_VERSION_KEY] = version
        }
    }

    suspend fun getYtDlpVersion(): String =
        context.settingsDataStore.data.first()[YTDLP_VERSION_KEY] ?: ""
```

- [ ] **Step 2: Create `YtDlpEngine.kt`**

Create `app/src/main/java/univzy/torikomi/engine/YtDlpEngine.kt`:
```kotlin
package univzy.torikomi.engine

import android.app.Application
import android.util.Log
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import com.yausername.youtubedl_android.mapper.VideoInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import univzy.torikomi.data.model.DownloadSpec
import univzy.torikomi.data.model.MediaFormat
import univzy.torikomi.data.model.MediaInfo
import univzy.torikomi.data.model.MediaKind
import java.io.File

/** Wraps youtubedl-android. One instance held by the Application. */
class YtDlpEngine(private val app: Application) {

    companion object { private const val TAG = "YtDlpEngine" }

    @Volatile private var initialized = false
    private val initMutex = Mutex()

    /** Extracts the bundled Python + FFmpeg payloads. Idempotent. Call once on splash. */
    suspend fun init() = withContext(Dispatchers.IO) {
        initMutex.withLock {
            if (initialized) return@withContext
            YoutubeDL.getInstance().init(app)
            FFmpeg.getInstance().init(app)
            initialized = true
        }
    }

    /** Downloads/updates the yt-dlp binary from GitHub. The required first-launch step. */
    suspend fun updateYtDlp(): String = withContext(Dispatchers.IO) {
        init()
        YoutubeDL.getInstance().updateYoutubeDL(app, YoutubeDL.UpdateChannel.STABLE)
        version()
    }

    suspend fun version(): String = withContext(Dispatchers.IO) {
        runCatching { YoutubeDL.getInstance().version(app) }.getOrNull() ?: ""
    }

    /** Fetches metadata + formats for a URL. */
    suspend fun fetchInfo(url: String): MediaInfo = withContext(Dispatchers.IO) {
        init()
        val request = YoutubeDLRequest(url).apply {
            addOption("--no-playlist")
            addOption("--no-warnings")
        }
        val info: VideoInfo = YoutubeDL.getInstance().getInfo(request)
        info.toMediaInfo(url)
    }

    /**
     * Runs a download into [outputDir] (app-private). Returns the produced file.
     * @param onProgress (percent 0..100, etaSeconds, rawLine)
     */
    suspend fun download(
        spec: DownloadSpec,
        outputDir: File,
        processId: String,
        onProgress: (Float, Long, String) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        init()
        outputDir.mkdirs()
        val request = YoutubeDLRequest(spec.sourceUrl).apply {
            addOption("--no-playlist")
            addOption("--no-mtime")
            addOption("-o", "${outputDir.absolutePath}/%(title).80s.%(ext)s")
            if (spec.choice.audioExtract) {
                addOption("-x")
                addOption("--audio-format", "mp3")
            } else {
                addOption("-f", spec.choice.formatSelector)
                addOption("--merge-output-format", "mp4")
            }
        }
        YoutubeDL.getInstance().execute(request, processId) { progress, eta, line ->
            onProgress(progress, eta, line)
        }
        // yt-dlp wrote a single media file into outputDir; pick the largest non-partial file.
        outputDir.listFiles()
            ?.filter { it.isFile && !it.name.endsWith(".part") && !it.name.endsWith(".ytdl") }
            ?.maxByOrNull { it.length() }
            ?: error("Download produced no file")
    }

    private fun VideoInfo.toMediaInfo(sourceUrl: String): MediaInfo {
        val mappedFormats = (formats ?: emptyList()).map { f ->
            val h = f.height.takeIf { it > 0 }
            val hasVideo = f.vcodec != null && f.vcodec != "none"
            val hasAudio = f.acodec != null && f.acodec != "none"
            MediaFormat(
                formatId = f.formatId ?: "",
                kind = when {
                    hasVideo && hasAudio -> MediaKind.MUXED
                    hasVideo -> MediaKind.VIDEO
                    else -> MediaKind.AUDIO
                },
                ext = f.ext ?: "",
                resolution = if (f.width > 0 && f.height > 0) "${f.width}x${f.height}" else null,
                heightPx = h,
                abrKbps = f.abr.takeIf { it > 0 }?.toInt(),
                filesizeApprox = (f.fileSize.takeIf { it > 0 } ?: f.fileSizeApproximate.takeIf { it > 0 }),
                note = f.formatNote ?: f.format ?: (h?.let { "${it}p" } ?: "audio"),
            )
        }
        return MediaInfo(
            sourceUrl = sourceUrl,
            title = title ?: "Untitled",
            uploader = uploader ?: "",
            thumbnail = thumbnail ?: "",
            durationSec = duration,
            isPlaylist = false,
            detectedSite = (extractorKey ?: extractor ?: "").ifBlank { "Media" },
            formats = mappedFormats,
        )
    }
}
```
> If the compiler reports that a `VideoInfo`/`VideoFormat` member has a different name or nullability than used above (e.g. `duration` is `Int?`, or `fileSize`/`fileSizeApproximate` differ), adjust those references to match the pinned library — the mapping logic is unchanged.

- [ ] **Step 3: Wire the engine into `Torikomi.kt` (keep extension wiring for now)**

In `app/src/main/java/univzy/torikomi/Torikomi.kt`, add the import and a lazy property:
```kotlin
import univzy.torikomi.engine.YtDlpEngine
```
Inside `class Torikomi`, add (alongside the existing `bridge`/`extensionRepo` properties — do not remove those yet):
```kotlin
    val ytDlpEngine: YtDlpEngine by lazy { YtDlpEngine(this) }
```

- [ ] **Step 4: Verify compile**

Run: `.\gradlew.bat compileDebugKotlin`
Expected: BUILD SUCCESSFUL. If it fails on a `VideoInfo`/`VideoFormat` member name, fix per the note in Step 2, then re-run.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/univzy/torikomi/engine/YtDlpEngine.kt app/src/main/java/univzy/torikomi/data/repository/SettingsRepository.kt app/src/main/java/univzy/torikomi/Torikomi.kt
git commit -m "feat: add YtDlpEngine and yt-dlp readiness state"
```

---

## Phase 3 — Download execution

### Task 3.1: `DownloadController` + `ActiveDownload` model

**Files:**
- Create: `app/src/main/java/univzy/torikomi/engine/DownloadController.kt`

> Process-global StateFlow bridging the foreground service and the UI. Verified by compile + use.

- [ ] **Step 1: Create the controller**

Create `app/src/main/java/univzy/torikomi/engine/DownloadController.kt`:
```kotlin
package univzy.torikomi.engine

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class ActiveDownload(
    val id: String,
    val title: String,
    val itemLabel: String,
    val progress: Int,      // 0..100, or -1 for indeterminate
    val statusText: String,
)

/** Singleton observed by the UI and mutated by DownloadService. */
object DownloadController {
    private val _active = MutableStateFlow<List<ActiveDownload>>(emptyList())
    val active: StateFlow<List<ActiveDownload>> = _active.asStateFlow()

    fun upsert(item: ActiveDownload) = _active.update { list ->
        if (list.any { it.id == item.id }) list.map { if (it.id == item.id) item else it }
        else list + item
    }

    fun update(id: String, progress: Int, statusText: String) = _active.update { list ->
        list.map { if (it.id == id) it.copy(progress = progress, statusText = statusText) else it }
    }

    fun remove(id: String) = _active.update { list -> list.filterNot { it.id == id } }

    fun isEmpty(): Boolean = _active.value.isEmpty()
}
```

- [ ] **Step 2: Verify compile**

Run: `.\gradlew.bat compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/univzy/torikomi/engine/DownloadController.kt
git commit -m "feat: add DownloadController active-download state"
```

---

### Task 3.2: `DownloadPublisher` — move produced file to destination

**Files:**
- Create: `app/src/main/java/univzy/torikomi/engine/DownloadPublisher.kt`

> Touches Android `MediaStore`/SAF; not unit-tested (path logic already covered by DownloadPathsTest). Verified on device.

- [ ] **Step 1: Create the publisher**

Create `app/src/main/java/univzy/torikomi/engine/DownloadPublisher.kt`:
```kotlin
package univzy.torikomi.engine

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import java.io.File

data class PublishResult(val fileName: String, val folderUri: String)

/** Moves a downloaded file from the app-private dir to the user's destination. */
object DownloadPublisher {

    /**
     * @param customFolderUri SAF tree URI string, or blank/"" for the default Downloads/Torikomi location.
     * @param type history downloadType ("video" | "audio" | "image")
     */
    fun publish(context: Context, source: File, type: String, customFolderUri: String): PublishResult {
        val fileName = source.name
        return if (customFolderUri.isNotBlank()) {
            publishToSaf(context, source, type, customFolderUri)
            PublishResult(fileName = fileName, folderUri = customFolderUri)
        } else {
            publishToDefault(context, source, type)
            PublishResult(fileName = fileName, folderUri = "default")
        }
    }

    private fun publishToDefault(context: Context, source: File, type: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, source.name)
                put(
                    MediaStore.Downloads.RELATIVE_PATH,
                    "${Environment.DIRECTORY_DOWNLOADS}/${DownloadPaths.relativeDir(type)}",
                )
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("MediaStore insert failed")
            resolver.openOutputStream(uri)?.use { out -> source.inputStream().use { it.copyTo(out) } }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } else {
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                DownloadPaths.relativeDir(type),
            ).also { it.mkdirs() }
            val dest = File(dir, source.name)
            source.copyTo(dest, overwrite = true)
            MediaScannerConnection.scanFile(context, arrayOf(dest.absolutePath), null, null)
        }
    }

    private fun publishToSaf(context: Context, source: File, type: String, treeUriString: String) {
        val treeUri = Uri.parse(treeUriString)
        val resolver = context.contentResolver
        val parentDocId = DocumentsContract.getTreeDocumentId(treeUri)
        val parentDocUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, parentDocId)
        val mime = when {
            source.extension.lowercase().matches(Regex("mp3|m4a|wav|flac|opus|ogg")) -> "audio/*"
            source.extension.lowercase().matches(Regex("mp4|mkv|avi|mov|webm")) -> "video/*"
            source.extension.lowercase().matches(Regex("jpg|jpeg|png|gif|bmp|webp")) -> "image/*"
            else -> "*/*"
        }
        val prefixedName = "${DownloadPaths.mediaTypeFolder(type)}_${source.name}"
        val docUri = DocumentsContract.createDocument(resolver, parentDocUri, mime, prefixedName)
            ?: error("SAF createDocument failed")
        resolver.openOutputStream(docUri)?.use { out -> source.inputStream().use { it.copyTo(out) } }
    }
}
```

- [ ] **Step 2: Verify compile**

Run: `.\gradlew.bat compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/univzy/torikomi/engine/DownloadPublisher.kt
git commit -m "feat: add DownloadPublisher (MediaStore/legacy default + SAF custom)"
```

---

### Task 3.3: `DownloadService` (foreground) + manifest service & permissions

**Files:**
- Create: `app/src/main/java/univzy/torikomi/service/DownloadService.kt`
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/src/main/java/univzy/torikomi/Torikomi.kt` (add a download notification channel)

> Native + Android service; verified by compile + device run.

- [ ] **Step 1: Add a download notification channel in `Torikomi.kt`**

In `app/src/main/java/univzy/torikomi/Torikomi.kt`, add a constant in the `companion object`:
```kotlin
        const val DOWNLOAD_CHANNEL_ID = "torikomi_downloads"
```
And in `ensureNotificationChannels()`, after the update channel is created, add:
```kotlin
        val downloadChannel = NotificationChannel(
            DOWNLOAD_CHANNEL_ID,
            "Downloads",
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = "Shows active download progress" }
        manager.createNotificationChannel(downloadChannel)
```

- [ ] **Step 2: Create `DownloadService.kt`**

Create `app/src/main/java/univzy/torikomi/service/DownloadService.kt`:
```kotlin
package univzy.torikomi.service

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import univzy.torikomi.Torikomi
import univzy.torikomi.data.model.DownloadSpec
import univzy.torikomi.data.model.QualityChoice
import univzy.torikomi.data.repository.HistoryRepository
import univzy.torikomi.data.repository.SettingsRepository
import univzy.torikomi.engine.ActiveDownload
import univzy.torikomi.engine.DownloadController
import univzy.torikomi.engine.DownloadPublisher
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * Foreground service that runs yt-dlp downloads. One job per enqueue; survives backgrounding.
 * Start via [enqueue].
 */
class DownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = AtomicInteger(0)

    companion object {
        private const val NOTIF_ID = 4242
        private const val EX_URL = "url"
        private const val EX_TITLE = "title"
        private const val EX_THUMB = "thumb"
        private const val EX_SITE = "site"
        private const val EX_LABEL = "label"
        private const val EX_TYPE = "type"
        private const val EX_SELECTOR = "selector"
        private const val EX_AUDIO = "audioExtract"

        fun enqueue(context: Context, spec: DownloadSpec) {
            val intent = Intent(context, DownloadService::class.java).apply {
                putExtra(EX_URL, spec.sourceUrl)
                putExtra(EX_TITLE, spec.title)
                putExtra(EX_THUMB, spec.thumbnail)
                putExtra(EX_SITE, spec.detectedSite)
                putExtra(EX_LABEL, spec.choice.label)
                putExtra(EX_TYPE, spec.choice.type)
                putExtra(EX_SELECTOR, spec.choice.formatSelector)
                putExtra(EX_AUDIO, spec.choice.audioExtract)
            }
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification("Preparing download…"))
        if (intent != null) startJob(intent)
        return START_NOT_STICKY
    }

    private fun startJob(intent: Intent) {
        val spec = DownloadSpec(
            sourceUrl = intent.getStringExtra(EX_URL).orEmpty(),
            title = intent.getStringExtra(EX_TITLE).orEmpty(),
            thumbnail = intent.getStringExtra(EX_THUMB).orEmpty(),
            detectedSite = intent.getStringExtra(EX_SITE).orEmpty(),
            choice = QualityChoice(
                label = intent.getStringExtra(EX_LABEL).orEmpty(),
                type = intent.getStringExtra(EX_TYPE).orEmpty(),
                formatSelector = intent.getStringExtra(EX_SELECTOR).orEmpty(),
                audioExtract = intent.getBooleanExtra(EX_AUDIO, false),
            ),
        )
        if (spec.sourceUrl.isBlank()) { maybeStop(); return }

        val app = applicationContext as Torikomi
        val historyRepo = HistoryRepository(applicationContext)
        val settingsRepo = SettingsRepository(applicationContext)
        val id = "${System.currentTimeMillis()}-${running.incrementAndGet()}"
        val outDir = File(cacheDir, "ytdlp/$id")

        DownloadController.upsert(
            ActiveDownload(id, spec.title.ifBlank { "Download" }, spec.choice.label, -1, "Starting…")
        )

        scope.launch {
            try {
                val file = app.ytDlpEngine.download(spec, outDir, id) { percent, _, _ ->
                    val p = percent.toInt().coerceIn(0, 100)
                    DownloadController.update(id, p, "Downloading $p%")
                    updateNotification("${spec.title.ifBlank { "Downloading" }} — $p%")
                }
                DownloadController.update(id, 100, "Saving…")
                val folderUri = settingsRepo.getDownloadPathSettings().folderUri
                val published = DownloadPublisher.publish(applicationContext, file, spec.choice.type, folderUri)
                historyRepo.saveDownload(
                    platform = spec.detectedSite,
                    title = spec.title.ifBlank { "Untitled" },
                    url = spec.sourceUrl,
                    thumbnailUrl = spec.thumbnail,
                    downloadType = spec.choice.type,
                    fileCount = 1,
                    folderUri = published.folderUri,
                    fileName = published.fileName,
                    quality = spec.choice.label,
                )
            } catch (t: Throwable) {
                DownloadController.update(id, -1, "Failed: ${t.message ?: "error"}")
            } finally {
                runCatching { outDir.deleteRecursively() }
                DownloadController.remove(id)
                maybeStop()
            }
        }
    }

    private fun maybeStop() {
        if (DownloadController.isEmpty()) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, Torikomi.DOWNLOAD_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Torikomi")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

    private fun updateNotification(text: String) {
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        mgr.notify(NOTIF_ID, buildNotification(text))
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
```

- [ ] **Step 3: Declare the service and foreground permissions in the manifest**

In `app/src/main/AndroidManifest.xml`, add these permissions near the top with the other `<uses-permission>` entries:
```xml
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
```
And declare the service inside `<application>` (e.g. after the `<activity>`):
```xml
        <service
            android:name=".service.DownloadService"
            android:exported="false"
            android:foregroundServiceType="dataSync" />
```

- [ ] **Step 4: Verify compile**

Run: `.\gradlew.bat compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/univzy/torikomi/service/DownloadService.kt app/src/main/AndroidManifest.xml app/src/main/java/univzy/torikomi/Torikomi.kt
git commit -m "feat: add foreground DownloadService and download notification channel"
```

---

## Phase 4 — ViewModels

### Task 4.1: Rewrite `DownloadViewModel`

**Files:**
- Modify: `app/src/main/java/univzy/torikomi/ui/ViewModels.kt`

> Replaces the extension-scrape/DownloadManager VM with a fetch-info + enqueue VM. Verified by compile.

- [ ] **Step 1: Replace `DownloadUiState`, `ActiveDownload` usage, and the whole `DownloadViewModel` class**

In `app/src/main/java/univzy/torikomi/ui/ViewModels.kt`, delete the existing `data class ActiveDownload`, `data class DownloadUiState`, and the entire `class DownloadViewModel(...) { ... }` (everything from `data class ActiveDownload(` through the closing brace of `DownloadViewModel`, including all its private helpers: `humanizeErrorMessage`, `fetchMedia`, `fetchPlaylistItemMedia`, `startDownload`, `resolveDeferredDownloadItem`, `resolvePollingItem`, `resolvePostJsonItem`, `monitorDownload`, `copyFileToCustomFolder`, `platformUrlRules`, etc.). Replace it all with:

```kotlin
data class DownloadUiState(
    val url: String = "",
    val isFetching: Boolean = false,
    val info: MediaInfo? = null,
    val choices: List<QualityChoice> = emptyList(),
    val error: String? = null,
    val saveDirectoryLabel: String = "Torikomi",
)

class DownloadViewModel(
    private val engine: YtDlpEngine,
    private val settingsRepo: SettingsRepository,
    private val appContext: Context,
) : ViewModel() {

    private val _uiState = MutableStateFlow(DownloadUiState())
    val uiState: StateFlow<DownloadUiState> = _uiState.asStateFlow()

    val activeDownloads: StateFlow<List<ActiveDownload>> = DownloadController.active

    init { refreshSavePathLabel() }

    fun updateUrl(url: String) = _uiState.update { it.copy(url = url) }

    fun clearResult() = _uiState.update { it.copy(info = null, choices = emptyList(), error = null) }

    fun refreshSavePathLabel() {
        viewModelScope.launch {
            val path = settingsRepo.getDownloadPathSettings()
            val label = if (path.folderUri.isNotBlank()) "Custom: ${path.folderName}" else "Torikomi"
            _uiState.update { it.copy(saveDirectoryLabel = label) }
        }
    }

    private fun isNetworkAvailable(): Boolean {
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    fun fetchInfo() {
        val url = _uiState.value.url.trim()
        if (url.isEmpty()) {
            _uiState.update { it.copy(error = "Paste a link first") }
            return
        }
        if (!UrlDetectorService.isValidMediaUrl(url)) {
            _uiState.update { it.copy(error = "That doesn't look like a valid link") }
            return
        }
        if (!isNetworkAvailable()) {
            _uiState.update { it.copy(error = "No internet connection. Check your network and try again.") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isFetching = true, error = null, info = null, choices = emptyList()) }
            runCatching { engine.fetchInfo(url) }
                .onSuccess { info ->
                    _uiState.update {
                        it.copy(isFetching = false, info = info, choices = QualityPresets.from(info.formats))
                    }
                }
                .onFailure { e ->
                    _uiState.update { it.copy(isFetching = false, error = humanizeError(e.message)) }
                }
        }
    }

    fun startDownload(choice: QualityChoice) {
        val info = _uiState.value.info ?: return
        DownloadService.enqueue(
            appContext,
            DownloadSpec(
                sourceUrl = info.sourceUrl,
                title = info.title,
                thumbnail = info.thumbnail,
                detectedSite = info.detectedSite,
                choice = choice,
            ),
        )
        android.widget.Toast.makeText(appContext, "Download started", android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun humanizeError(raw: String?): String {
        val lower = raw?.lowercase().orEmpty()
        return when {
            lower.isBlank() -> "Failed to fetch media. Please try again."
            "unsupported url" in lower || "no video" in lower ->
                "This link isn't supported by yt-dlp."
            "unable to" in lower && "host" in lower ->
                "Could not reach the server. Try another network or a VPN."
            "timed out" in lower || "timeout" in lower ->
                "Request timed out. Check your connection and try again."
            "http error 403" in lower || "private" in lower ->
                "Access blocked by the source — the link may be private or rate-limited."
            "http error 404" in lower -> "Content not found — the link may have been removed."
            else -> raw!!.take(200)
        }
    }
}
```

- [ ] **Step 2: Fix imports at the top of `ViewModels.kt`**

Add these imports (and remove now-unused extension/DownloadManager imports — the compiler will flag unused ones; at minimum these must be present):
```kotlin
import univzy.torikomi.data.model.MediaInfo
import univzy.torikomi.data.model.QualityChoice
import univzy.torikomi.data.model.DownloadSpec
import univzy.torikomi.engine.YtDlpEngine
import univzy.torikomi.engine.QualityPresets
import univzy.torikomi.engine.ActiveDownload
import univzy.torikomi.engine.DownloadController
import univzy.torikomi.service.DownloadService
import univzy.torikomi.util.UrlDetectorService
```
Leave `ExtensionViewModel`, `SplashViewModel`, etc. for later tasks (they still reference the extension repo and will be updated in Task 4.3/4.4).

- [ ] **Step 3: Verify compile (expected to still fail on factory wiring — that's fixed in Task 4.4)**

Run: `.\gradlew.bat compileDebugKotlin`
Expected: FAIL — `AppViewModelFactory` still constructs `DownloadViewModel` with the old parameters, and `ExtensionViewModel`/`SplashViewModel` still reference the extension repo. These are resolved in Tasks 4.3–4.4. Do not commit yet; continue.

---

### Task 4.2: `SetupViewModel`

**Files:**
- Modify: `app/src/main/java/univzy/torikomi/ui/ViewModels.kt`

- [ ] **Step 1: Add the SetupViewModel (place it near SplashViewModel)**

In `app/src/main/java/univzy/torikomi/ui/ViewModels.kt`, add:
```kotlin
data class SetupUiState(
    val isDownloading: Boolean = false,
    val done: Boolean = false,
    val version: String = "",
    val error: String? = null,
)

class SetupViewModel(
    private val engine: YtDlpEngine,
    private val settingsRepo: SettingsRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SetupUiState())
    val uiState: StateFlow<SetupUiState> = _uiState.asStateFlow()

    fun downloadYtDlp() {
        if (_uiState.value.isDownloading) return
        viewModelScope.launch {
            _uiState.update { it.copy(isDownloading = true, error = null) }
            runCatching { engine.updateYtDlp() }
                .onSuccess { version ->
                    settingsRepo.setYtDlpReady(true, version)
                    _uiState.update { it.copy(isDownloading = false, done = true, version = version) }
                }
                .onFailure { e ->
                    _uiState.update {
                        it.copy(isDownloading = false, error = e.message ?: "Failed to download yt-dlp")
                    }
                }
        }
    }
}
```

- [ ] **Step 2: (No standalone compile yet — continue to Task 4.3.)**

---

### Task 4.3: Adapt `SplashViewModel` + add `SETUP` destination

**Files:**
- Modify: `app/src/main/java/univzy/torikomi/ui/ViewModels.kt`

- [ ] **Step 1: Replace `SplashDestination` enum and `SplashViewModel`**

In `app/src/main/java/univzy/torikomi/ui/ViewModels.kt`, replace:
```kotlin
enum class SplashDestination { LOADING, ONBOARDING, HOME }
```
with:
```kotlin
enum class SplashDestination { LOADING, ONBOARDING, SETUP, HOME }
```
Then replace the entire `class SplashViewModel(...) { ... }` with:
```kotlin
data class SplashUiState(
    val destination: SplashDestination = SplashDestination.LOADING,
    val loadingStep: String = "Starting up...",
)

class SplashViewModel(
    private val settingsRepo: SettingsRepository,
    private val engine: YtDlpEngine,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SplashUiState())
    val uiState: StateFlow<SplashUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            _uiState.update { it.copy(loadingStep = "Preparing engine...") }
            runCatching { engine.init() }
            val onboardingDone = runCatching { settingsRepo.isOnboardingDone() }.getOrDefault(false)
            val ytReady = runCatching { settingsRepo.isYtDlpReady() }.getOrDefault(false)
            _uiState.update {
                it.copy(
                    destination = when {
                        !onboardingDone -> SplashDestination.ONBOARDING
                        !ytReady -> SplashDestination.SETUP
                        else -> SplashDestination.HOME
                    },
                    loadingStep = "Ready!",
                )
            }
        }
    }

    /** Called by onboarding's final step; routes to the setup gate. */
    fun completeOnboarding(folderUri: String, folderName: String) {
        viewModelScope.launch {
            if (folderUri.isNotBlank()) settingsRepo.setDownloadFolder(folderUri, folderName)
            settingsRepo.markOnboardingDone()
            val ytReady = runCatching { settingsRepo.isYtDlpReady() }.getOrDefault(false)
            _uiState.update {
                it.copy(destination = if (ytReady) SplashDestination.HOME else SplashDestination.SETUP)
            }
        }
    }

    /** Called by the setup gate on success. */
    fun completeSetup() {
        _uiState.update { it.copy(destination = SplashDestination.HOME) }
    }
}
```
> The `delay(300)` and the old `extensionRepo.fetchCatalog()`/`getDeviceInstalledExtensions()` calls are intentionally removed.

- [ ] **Step 2: Remove `ExtensionViewModel`**

In the same file, delete the entire `data class ExtensionsUiState(...)` and `class ExtensionViewModel(...) { ... }`.

- [ ] **Step 2 continued: (compile after Task 4.4 wiring.)**

---

### Task 4.4: Update `AppViewModelFactory`

**Files:**
- Modify: `app/src/main/java/univzy/torikomi/ui/ViewModels.kt`

- [ ] **Step 1: Replace `AppViewModelFactory`**

Replace the entire `class AppViewModelFactory(...) { ... }` with:
```kotlin
class AppViewModelFactory(private val app: Application) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        val torikomi = app as? Torikomi ?: throw IllegalStateException("Torikomi not initialized")
        val engine = torikomi.ytDlpEngine
        return when {
            modelClass.isAssignableFrom(ThemeViewModel::class.java) -> ThemeViewModel(app) as T
            modelClass.isAssignableFrom(HistoryViewModel::class.java) -> HistoryViewModel(HistoryRepository(app)) as T
            modelClass.isAssignableFrom(DownloadViewModel::class.java) ->
                DownloadViewModel(
                    engine = engine,
                    settingsRepo = SettingsRepository(app),
                    appContext = app,
                ) as T
            modelClass.isAssignableFrom(SettingsViewModel::class.java) ->
                SettingsViewModel(SettingsRepository(app), app) as T
            modelClass.isAssignableFrom(StartupUpdateViewModel::class.java) ->
                StartupUpdateViewModel(SettingsRepository(app), app) as T
            modelClass.isAssignableFrom(SetupViewModel::class.java) ->
                SetupViewModel(engine, SettingsRepository(app)) as T
            modelClass.isAssignableFrom(SplashViewModel::class.java) ->
                SplashViewModel(SettingsRepository(app), engine) as T
            else -> throw IllegalArgumentException("Unknown ViewModel: ${modelClass.name}")
        }
    }
}
```

- [ ] **Step 2: Verify compile (ViewModels + screens that still import removed types will fail — fixed in Phase 5)**

Run: `.\gradlew.bat compileDebugKotlin`
Expected: FAIL — `HomeScreen`, `DownloadScreen`, `App.kt`, `ExtensionScreenTemplate`, etc. still reference `ExtensionViewModel`, old `DownloadViewModel` API, and `SplashViewModel(extensionRepo=...)`. These are fixed in Phase 5/6. `ViewModels.kt` itself should now be internally consistent.

> Do not commit a broken build. Phase 5 brings the build back to green; commit at the end of Task 5.5 (or after Phase 6 if you batch). If using subagent-driven execution, treat Tasks 4.1–5.5 as one "build-green" unit and commit once compiling.

---

## Phase 5 — UI screens

### Task 5.1: `SetupScreen` (the download-yt-dlp gate)

**Files:**
- Create: `app/src/main/java/univzy/torikomi/ui/screens/SetupScreen.kt`

- [ ] **Step 1: Create the screen**

Create `app/src/main/java/univzy/torikomi/ui/screens/SetupScreen.kt`:
```kotlin
package univzy.torikomi.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import univzy.torikomi.Torikomi
import univzy.torikomi.ui.AppViewModelFactory
import univzy.torikomi.ui.SetupViewModel

@Composable
fun SetupScreen(onSetupComplete: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val factory = AppViewModelFactory(context.applicationContext as Torikomi)
    val vm: SetupViewModel = viewModel(factory = factory)
    val state by vm.uiState.collectAsState()

    LaunchedEffect(state.done) { if (state.done) onSetupComplete() }

    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                Icons.Rounded.CloudDownload,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(96.dp),
            )
            Spacer(Modifier.height(24.dp))
            Text(
                "Download the yt-dlp engine",
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                "Torikomi needs the latest yt-dlp to fetch and download media. This is a one-time download (you can update it later in Settings).",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(32.dp))

            when {
                state.isDownloading -> {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(12.dp))
                    Text("Downloading yt-dlp…", style = MaterialTheme.typography.bodySmall)
                }
                else -> {
                    if (state.error != null) {
                        Icon(
                            Icons.Rounded.ErrorOutline, null,
                            tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            state.error ?: "",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(16.dp))
                    }
                    Button(
                        onClick = { vm.downloadYtDlp() },
                        modifier = Modifier.fillMaxWidth().height(54.dp),
                        shape = RoundedCornerShape(16.dp),
                    ) {
                        Text(if (state.error != null) "Retry" else "Download yt-dlp", fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}
```

---

### Task 5.2: Rewrite `HomeScreen` (universal downloader, 3 tabs)

**Files:**
- Modify: `app/src/main/java/univzy/torikomi/ui/screens/HomeScreen.kt` (full rewrite)

- [ ] **Step 1: Replace the entire file contents**

Replace ALL of `app/src/main/java/univzy/torikomi/ui/screens/HomeScreen.kt` with:
```kotlin
package univzy.torikomi.ui.screens

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import coil.compose.AsyncImage
import univzy.torikomi.Torikomi
import univzy.torikomi.data.model.QualityChoice
import univzy.torikomi.ui.AppViewModelFactory
import univzy.torikomi.ui.DownloadViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    navController: NavController,
    isDark: Boolean,
    onToggleTheme: () -> Unit,
) {
    var selectedTab by remember { mutableStateOf(0) }
    var showAbout by remember { mutableStateOf(false) }
    val tabTitles = listOf("Downloads", "History", "Settings")

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(tabTitles[selectedTab], fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = onToggleTheme) {
                        Icon(
                            if (isDark) Icons.Rounded.LightMode else Icons.Rounded.DarkMode,
                            contentDescription = "Toggle theme",
                        )
                    }
                    IconButton(onClick = { showAbout = true }) {
                        Icon(Icons.Rounded.Info, contentDescription = "About")
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                listOf(
                    Triple("Downloads", Icons.Rounded.Download, Icons.Rounded.DownloadDone),
                    Triple("History", Icons.Rounded.History, Icons.Rounded.History),
                    Triple("Settings", Icons.Rounded.Settings, Icons.Rounded.Settings),
                ).forEachIndexed { index, (label, icon, selectedIcon) ->
                    NavigationBarItem(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        icon = { Icon(if (selectedTab == index) selectedIcon else icon, label) },
                        label = { Text(label) },
                    )
                }
            }
        },
    ) { padding ->
        when (selectedTab) {
            0 -> DownloadsTab(
                modifier = Modifier.padding(padding),
                onOpenWhatsApp = { navController.navigate("whatsapp") },
            )
            1 -> HistoryScreen(modifier = Modifier.padding(padding))
            2 -> SettingsScreen(modifier = Modifier.padding(padding))
        }
    }

    if (showAbout) AboutDialog(onDismiss = { showAbout = false })
}

@Composable
private fun DownloadsTab(
    modifier: Modifier,
    onOpenWhatsApp: () -> Unit,
) {
    val context = LocalContext.current
    val factory = AppViewModelFactory(context.applicationContext as Torikomi)
    val vm: DownloadViewModel = viewModel(factory = factory)
    val state by vm.uiState.collectAsState()
    val active by vm.activeDownloads.collectAsState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        // Share hint
        Surface(
            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
            shape = MaterialTheme.shapes.small,
        ) {
            Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Share, null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(10.dp))
                Text(
                    "Paste any video/audio link, or share it from another app",
                    style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.secondary),
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        OutlinedTextField(
            value = state.url,
            onValueChange = vm::updateUrl,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Link") },
            placeholder = { Text("https://…") },
            singleLine = true,
            trailingIcon = {
                if (state.url.isNotBlank()) {
                    IconButton(onClick = { vm.updateUrl(""); vm.clearResult() }) {
                        Icon(Icons.Rounded.Clear, "Clear")
                    }
                }
            },
        )

        Spacer(Modifier.height(12.dp))

        Button(
            onClick = { vm.fetchInfo() },
            enabled = !state.isFetching,
            modifier = Modifier.fillMaxWidth().height(50.dp),
        ) {
            if (state.isFetching) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                Spacer(Modifier.width(10.dp))
                Text("Fetching…")
            } else {
                Icon(Icons.Rounded.Search, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Fetch")
            }
        }

        state.error?.let { err ->
            Spacer(Modifier.height(12.dp))
            Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.small) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.ErrorOutline, null, tint = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.width(8.dp))
                    Text(err, color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        state.info?.let { info ->
            Spacer(Modifier.height(16.dp))
            Card {
                Column(Modifier.padding(14.dp)) {
                    if (info.thumbnail.isNotBlank()) {
                        AsyncImage(
                            model = info.thumbnail,
                            contentDescription = info.title,
                            modifier = Modifier.fillMaxWidth().height(180.dp),
                        )
                        Spacer(Modifier.height(10.dp))
                    }
                    Text(info.title, fontWeight = FontWeight.Bold, maxLines = 2)
                    if (info.uploader.isNotBlank()) {
                        Text(info.uploader, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text("${info.detectedSite} • ${formatDuration(info.durationSec)}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Divider(Modifier.padding(vertical = 12.dp))
                    Text("Choose quality", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Spacer(Modifier.height(8.dp))
                    state.choices.forEach { choice ->
                        QualityRow(choice = choice, onClick = { vm.startDownload(choice) })
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }
        }

        if (active.isNotEmpty()) {
            Spacer(Modifier.height(20.dp))
            Text("Active downloads", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Spacer(Modifier.height(8.dp))
            active.forEach { d ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(d.title, fontWeight = FontWeight.Medium, maxLines = 1)
                        Text("${d.itemLabel} • ${d.statusText}", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(8.dp))
                        if (d.progress in 0..100) {
                            LinearProgressIndicator(progress = { d.progress / 100f }, modifier = Modifier.fillMaxWidth())
                        } else {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        }

        Spacer(Modifier.height(20.dp))
        // WhatsApp Status shortcut (kept bonus feature)
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            shape = MaterialTheme.shapes.medium,
            onClick = onOpenWhatsApp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Chat, null, tint = Color(0xFF25D366))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("WhatsApp Status", fontWeight = FontWeight.Medium)
                    Text("Save statuses from your device", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(Icons.Rounded.ChevronRight, null)
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun QualityRow(choice: QualityChoice, onClick: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        shape = MaterialTheme.shapes.small,
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (choice.type == "audio") Icons.Rounded.MusicNote else Icons.Rounded.Movie,
                null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(12.dp))
            Text(choice.label, Modifier.weight(1f), fontWeight = FontWeight.Medium)
            Icon(Icons.Rounded.Download, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        }
    }
}

private fun formatDuration(seconds: Int): String {
    if (seconds <= 0) return "—"
    val m = seconds / 60
    val s = seconds % 60
    return "%d:%02d".format(m, s)
}

@Composable
private fun AboutDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    Dialog(onDismissRequest = onDismiss) {
        Card(shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Download, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text("Torikomi Downloader", fontWeight = FontWeight.Bold)
                    Spacer(Modifier.weight(1f))
                    val appVersionLabel = remember(context) {
                        runCatching {
                            "v${context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"}"
                        }.getOrDefault("v?")
                    }
                    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.large) {
                        Text(appVersionLabel, Modifier.padding(horizontal = 8.dp, vertical = 2.dp), fontSize = 11.sp)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "A universal media downloader powered by yt-dlp.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(16.dp))
                Button(onClick = onDismiss, Modifier.fillMaxWidth()) { Text("Close") }
            }
        }
    }
}
```

---

### Task 5.3: Trim `OnboardingScreen` (remove install-permission step + copy)

**Files:**
- Modify: `app/src/main/java/univzy/torikomi/ui/screens/OnboardingScreen.kt`

- [ ] **Step 1: Remove `INSTALL_PERMISSION` from the steps list**

In the `steps` `buildList { ... }` block, delete the line `add(OnboardingStep.INSTALL_PERMISSION)`.

- [ ] **Step 2: Remove install-permission state + lifecycle observer**

Delete the `installPermissionGranted` `remember { ... }` block and the `DisposableEffect(lifecycleOwner) { ... }` block that updates it. (The `canRequestPackageInstalls` checks.)

- [ ] **Step 3: Remove the `INSTALL_PERMISSION` branch from the `AnimatedContent` `when (step)`**

Delete the `OnboardingStep.INSTALL_PERMISSION -> InstallPermissionStep(...)` branch.

- [ ] **Step 4: Remove the `INSTALL_PERMISSION` branch from the button `when (current)`**

Delete the `OnboardingStep.INSTALL_PERMISSION -> ...` branch in the `Triple(...)` `when`.

- [ ] **Step 5: Remove now-dead code**

Delete the `OnboardingStep.INSTALL_PERMISSION` enum entry, the `InstallPermissionStep(...)` composable, and any now-unused imports (`Settings`, `canRequestPackageInstalls` usages). Update the enum to:
```kotlin
private enum class OnboardingStep {
    WELCOME, NOTIFICATIONS, STORAGE, DONE
}
```

- [ ] **Step 6: Update the welcome copy**

In `WelcomeStep()`, change the subtitle text from `"A multi-platform downloader powered by\nplugin-based extensions."` to:
```kotlin
            text = "A universal media downloader\npowered by yt-dlp.",
```

---

### Task 5.4: Settings — yt-dlp section + WhatsApp entry

**Files:**
- Modify: `app/src/main/java/univzy/torikomi/ui/ViewModels.kt` (extend `SettingsViewModel`)
- Modify: `app/src/main/java/univzy/torikomi/ui/screens/SettingsScreen.kt`

- [ ] **Step 1: Add yt-dlp state to `SettingsViewModel`**

In `app/src/main/java/univzy/torikomi/ui/ViewModels.kt`, extend `SettingsUiState`:
```kotlin
data class SettingsUiState(
    val currentVersion: String = "-",
    val downloadFolderUri: String = "",
    val downloadFolderName: String = "Select Folder",
    val isCheckingUpdate: Boolean = false,
    val latestRelease: AppReleaseInfo? = null,
    val hasUpdate: Boolean = false,
    val updateError: String? = null,
    val ytDlpVersion: String = "",
    val isUpdatingYtDlp: Boolean = false,
)
```
Change `SettingsViewModel`'s constructor to take the engine, and add load/update logic. Replace the class header and `init`:
```kotlin
class SettingsViewModel(
    private val repo: SettingsRepository,
    private val engine: YtDlpEngine,
    private val context: Context,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        _uiState.update { it.copy(currentVersion = appVersionName()) }
        loadDownloadSettings()
        viewModelScope.launch {
            _uiState.update { it.copy(ytDlpVersion = repo.getYtDlpVersion()) }
        }
    }

    fun updateYtDlp() {
        if (_uiState.value.isUpdatingYtDlp) return
        viewModelScope.launch {
            _uiState.update { it.copy(isUpdatingYtDlp = true) }
            runCatching { engine.updateYtDlp() }
                .onSuccess { v ->
                    repo.setYtDlpReady(true, v)
                    _uiState.update { it.copy(isUpdatingYtDlp = false, ytDlpVersion = v) }
                }
                .onFailure { _uiState.update { it.copy(isUpdatingYtDlp = false) } }
        }
    }
```
(Keep the rest of `SettingsViewModel` — `loadDownloadSettings`, `setDownloadFolder`, `checkLatestRelease`, `appVersionName` — unchanged.)

- [ ] **Step 2: Update the factory wiring for `SettingsViewModel`**

In `AppViewModelFactory.create`, change the `SettingsViewModel` branch to:
```kotlin
            modelClass.isAssignableFrom(SettingsViewModel::class.java) ->
                SettingsViewModel(SettingsRepository(app), engine, app) as T
```

- [ ] **Step 3: Add the yt-dlp + WhatsApp rows to `SettingsScreen`**

In `app/src/main/java/univzy/torikomi/ui/screens/SettingsScreen.kt`, add a parameter so Settings can open the WhatsApp screen:
```kotlin
@Composable
fun SettingsScreen(modifier: Modifier = Modifier, onOpenWhatsApp: () -> Unit = {}) {
```
Then, inside the `Column`, after the "App Settings" `Text` header (before "Check for Updates"), add an engine section. Place this block right after the `Spacer(Modifier.height(16.dp))` that precedes "App Settings", and BEFORE the Check-for-Updates `SettingsItem`:
```kotlin
        SettingsItem(
            icon = Icons.Rounded.Terminal,
            title = "yt-dlp Engine",
            subtitle = if (state.ytDlpVersion.isNotBlank()) "Version: ${state.ytDlpVersion}" else "Tap to update",
            trailingContent = {
                if (state.isUpdatingYtDlp) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
                } else {
                    Icon(Icons.Rounded.Refresh, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                }
            },
            onClick = { vm.updateYtDlp() },
        )
        Divider(modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)

        SettingsItem(
            icon = Icons.Rounded.Chat,
            title = "WhatsApp Status",
            subtitle = "Save statuses from your device",
            onClick = onOpenWhatsApp,
        )
        Divider(modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
```
Add the imports for the new icons at the top of the file:
```kotlin
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Chat
```

- [ ] **Step 4: Pass the WhatsApp callback from HomeScreen**

In `HomeScreen.kt`, change the Settings tab branch to forward navigation:
```kotlin
            2 -> SettingsScreen(
                modifier = Modifier.padding(padding),
                onOpenWhatsApp = { navController.navigate("whatsapp") },
            )
```

---

### Task 5.5: `App.kt` navigation + share handling

**Files:**
- Modify: `app/src/main/java/univzy/torikomi/ui/App.kt`

- [ ] **Step 1: Replace the navigation logic**

In `app/src/main/java/univzy/torikomi/ui/App.kt`:

(a) Remove the import `import univzy.torikomi.ui.screens.DownloadScreen` and add `import univzy.torikomi.ui.screens.SetupScreen`. Remove the unused `import java.net.URLEncoder` / `URLDecoder` and `NavType`/`navArgument` imports if no longer referenced after this step.

(b) Replace the `LaunchedEffect(splashState.destination) { ... }` navigation block with:
```kotlin
        LaunchedEffect(splashState.destination) {
            when (splashState.destination) {
                SplashDestination.HOME -> navController.navigate("home") { popUpTo("splash") { inclusive = true } }
                SplashDestination.ONBOARDING -> navController.navigate("onboarding") { popUpTo("splash") { inclusive = true } }
                SplashDestination.SETUP -> navController.navigate("setup") { popUpTo("splash") { inclusive = true } }
                SplashDestination.LOADING -> Unit
            }
        }
```

(c) Replace the shared-URL `LaunchedEffect(initialSharedUrl, splashState.destination) { ... }` block with one that prefills the Home URL field (no platform). Replace it with:
```kotlin
        val context2 = LocalContext.current
        LaunchedEffect(initialSharedUrl, splashState.destination) {
            if (splashState.destination == SplashDestination.HOME && !initialSharedUrl.isNullOrBlank()) {
                val url = UrlDetectorService.extractUrl(initialSharedUrl)
                if (url != null) {
                    val factory = AppViewModelFactory(context2.applicationContext as Torikomi)
                    val dlVm = ViewModelProvider(
                        context2 as androidx.lifecycle.ViewModelStoreOwner, factory
                    )[DownloadViewModel::class.java]
                    dlVm.updateUrl(url)
                    dlVm.fetchInfo()
                }
            }
        }
```
> Add imports: `import androidx.lifecycle.ViewModelProvider`, `import univzy.torikomi.ui.AppViewModelFactory`, `import univzy.torikomi.ui.DownloadViewModel`. Keep `import univzy.torikomi.util.UrlDetectorService`.
> Note: the shared `DownloadViewModel` instance must be the same one the HomeScreen `DownloadsTab` uses. Since both resolve via `AppViewModelFactory` against the Activity `ViewModelStoreOwner`, they share. If in practice the DownloadsTab uses a NavBackStackEntry-scoped VM and the prefill doesn't appear, fall back to a nav argument: add a `home?initialUrl={initialUrl}` route and read it in `DownloadsTab`. (Verify during Phase 7; the Activity-scoped approach is preferred.)

(d) In the `NavHost`, replace the `composable("onboarding") { ... }`, remove the `composable("download/...") { ... }` block entirely, and add a `setup` route. The NavHost becomes:
```kotlin
        NavHost(navController = navController, startDestination = "splash") {
            composable("splash") { SplashScreen(loadingStep = splashState.loadingStep) }
            composable("onboarding") {
                OnboardingScreen(onComplete = { uri, name -> splashVm.completeOnboarding(uri, name) })
            }
            composable("setup") {
                SetupScreen(onSetupComplete = { splashVm.completeSetup() })
            }
            composable("home") {
                HomeScreen(navController = navController, isDark = isDark, onToggleTheme = { themeVm.toggleTheme(isDark) })
            }
            composable("whatsapp") {
                WhatsAppStatusScreen(onBack = { navController.popBackStack() })
            }
        }
```
> Also add a `LaunchedEffect(splashState.destination)` navigation case is already handled in (b); ensure `setup` and `onboarding` routes navigate to `home` when `completeOnboarding`/`completeSetup` flip the destination — the existing `LaunchedEffect(splashState.destination)` handles HOME, so completing setup (which sets destination=HOME) auto-navigates.

- [ ] **Step 2: Bring the build back to green**

Run: `.\gradlew.bat compileDebugKotlin`
Expected: FAIL only on references to not-yet-deleted extension files (e.g. `DownloadScreen.kt`, `ExtensionScreenTemplate.kt`, `PlatformCard.kt`, extension models) if they're still importing removed VM/types. Those files are deleted in Phase 6. If the only remaining errors are inside files slated for deletion in Phase 6, proceed to Phase 6 before committing. If there are errors in files we keep, fix them now.

> Recommended: proceed directly into Phase 6 (deletions) and run the green build + commit once at the end of Task 6.3. The build is intentionally red between Task 4.1 and Task 6.3.

---

## Phase 6 — Removal & cleanup

### Task 6.1: Delete extension source files

**Files (delete):**
- `app/src/main/java/univzy/torikomi/service/ExtensionBridgeHandler.kt`
- `app/src/main/java/univzy/torikomi/data/repository/ExtensionRepository.kt`
- `app/src/main/java/univzy/torikomi/ui/template/ExtensionScreenTemplate.kt`
- `app/src/main/java/univzy/torikomi/ui/template/BlockRenderer.kt`
- `app/src/main/java/univzy/torikomi/ui/template/ScreenBlock.kt`
- `app/src/main/java/univzy/torikomi/ui/components/PlatformCard.kt`
- `app/src/main/java/univzy/torikomi/ui/screens/DownloadScreen.kt`

- [ ] **Step 1: Delete the files**

Run (PowerShell):
```powershell
Remove-Item -Force `
  app/src/main/java/univzy/torikomi/service/ExtensionBridgeHandler.kt, `
  app/src/main/java/univzy/torikomi/data/repository/ExtensionRepository.kt, `
  app/src/main/java/univzy/torikomi/ui/template/ExtensionScreenTemplate.kt, `
  app/src/main/java/univzy/torikomi/ui/template/BlockRenderer.kt, `
  app/src/main/java/univzy/torikomi/ui/template/ScreenBlock.kt, `
  app/src/main/java/univzy/torikomi/ui/components/PlatformCard.kt, `
  app/src/main/java/univzy/torikomi/ui/screens/DownloadScreen.kt
```

- [ ] **Step 2: Remove the extension wiring from `Torikomi.kt`**

In `app/src/main/java/univzy/torikomi/Torikomi.kt`, delete the imports and properties for the extension system:
```kotlin
import univzy.torikomi.data.repository.ExtensionRepository
import univzy.torikomi.service.ExtensionBridgeHandler
```
and the lines:
```kotlin
    val bridge: ExtensionBridgeHandler by lazy { ExtensionBridgeHandler(this) }
    val extensionRepo: ExtensionRepository by lazy { ExtensionRepository(this, bridge) }
```

---

### Task 6.2: Remove extension models + extension JSON mappers

**Files:**
- Modify: `app/src/main/java/univzy/torikomi/data/model/Models.kt`
- Modify: `app/src/main/java/univzy/torikomi/data/model/JsonMappers.kt`

- [ ] **Step 1: Trim `Models.kt`**

In `app/src/main/java/univzy/torikomi/data/model/Models.kt`, delete these declarations entirely:
- `data class DownloadItem(...)`
- `data class ScrapeResult(...)`
- `enum class ExtensionStatus`
- `data class ExtensionSource(...)`
- `data class ExtensionInfo(...)` (including its computed properties)
- `data class InstalledExtensionInfo(...)`
- `data class ExtensionMeta(...)` and the now-unused `import androidx.compose.ui.graphics.Color`

Keep `DownloadHistory` (and its `platformIcon`/`downloadTypeLabel`). The file should end up containing only `DownloadHistory`.

- [ ] **Step 2: Trim `JsonMappers.kt`**

In `app/src/main/java/univzy/torikomi/data/model/JsonMappers.kt`, delete:
- `fun scrapeResultFromJson(...)`
- `private fun jsonObjectToMap(...)`
- `fun downloadItemFromJson(...)`
- `private fun JSONObject.optIntOrNull(...)`
- `fun extensionInfoFromJson(...)`
- `fun extensionSourceFromJson(...)`

Keep only `DownloadHistory.toJson()` and `downloadHistoryFromJson(...)`. Remove the now-unused `import org.json.JSONArray` if nothing else uses it (keep it only if a remaining function references `JSONArray`; `downloadHistoryFromJson`/`toJson` do not, so remove it).

---

### Task 6.3: Manifest cleanup + migration cleanup + green build

**Files:**
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/src/main/java/univzy/torikomi/Torikomi.kt`

- [ ] **Step 1: Remove extension-only manifest entries**

In `app/src/main/AndroidManifest.xml`, delete:
- `<uses-permission android:name="android.permission.REQUEST_INSTALL_PACKAGES" />`
- the `<uses-permission android:name="android.permission.QUERY_ALL_PACKAGES" .../>` block
- the `<intent><action android:name="com.torikomi.extension.action.DISCOVERY" /></intent>` entry inside `<queries>`
- the `<provider android:name="androidx.core.content.FileProvider" .../>` block (APK-install FileProvider) and its `<meta-data>` child

Keep `INTERNET`, `ACCESS_NETWORK_STATE`, `POST_NOTIFICATIONS`, the storage permissions, `FOREGROUND_SERVICE*`, the launcher + share intent-filters, and the `DownloadService` declaration. The `<queries>` block may keep the `VIEW`/`MAIN` intents (used for opening links); if `<queries>` becomes empty, remove it.

- [ ] **Step 2: Clear stale extension prefs on first launch of the new version**

In `app/src/main/java/univzy/torikomi/Torikomi.kt`, add a one-time cleanup in `onCreate()` after `ensureNotificationChannels()`:
```kotlin
        clearLegacyExtensionPrefs()
```
And add the method:
```kotlin
    private fun clearLegacyExtensionPrefs() {
        // The extension system stored installed-extension ids in this DataStore; it's now unused.
        runCatching {
            val prefsDir = java.io.File(filesDir.parentFile, "datastore")
            java.io.File(prefsDir, "torikomi_ext_prefs.preferences_pb").takeIf { it.exists() }?.delete()
        }
    }
```

- [ ] **Step 3: Compile**

Run: `.\gradlew.bat compileDebugKotlin`
Expected: BUILD SUCCESSFUL. Fix any remaining references to deleted symbols (search the codebase for `ExtensionInfo`, `ScrapeResult`, `DownloadItem`, `bridge`, `extensionRepo`, `ExtensionViewModel` — there should be none left).

- [ ] **Step 4: Run all unit tests**

Run: `.\gradlew.bat testDebugUnitTest`
Expected: PASS (DownloadPathsTest, QualityPresetsTest, UrlDetectorServiceTest — 20 tests total).

- [ ] **Step 5: Commit the whole refactor cutover**

```bash
git add -A
git commit -m "refactor: replace extension system with embedded yt-dlp engine

Remove ExtensionBridgeHandler/ExtensionRepository, the screen-template
system, PlatformCard, the old DownloadScreen, extension models/mappers,
and extension manifest entries. Add yt-dlp-based fetch/download flow,
required setup gate, 3-tab Home, and migration cleanup."
```

---

## Phase 7 — Verification

### Task 7.1: Full build + on-device smoke test

**Files:** none (verification only)

- [ ] **Step 1: Assemble the debug APK**

Run: `.\gradlew.bat assembleDebug`
Expected: BUILD SUCCESSFUL; APK at `app/build/outputs/apk/debug/app-debug.apk`.

- [ ] **Step 2: Install on a device/emulator (arm) and run the smoke checklist**

Install: `.\gradlew.bat installDebug` (device connected via adb, arm64-v8a or armeabi-v7a).

Verify each:
- [ ] First launch (fresh install): Splash → Onboarding (no install-permission step) → **Setup gate** appears → tap "Download yt-dlp" → progress → lands on Home.
- [ ] Upgrade path: install the OLD extension build first, then install this build over it → app still shows the **Setup gate** (because `ytDlpReady` defaults false) before Home.
- [ ] Home: paste a YouTube URL → Fetch → info card shows title/thumbnail/duration → quality presets listed.
- [ ] Download "Best (video+audio)" → foreground notification shows progress → file appears in `Downloads/Torikomi/VIDEO/` → History records it.
- [ ] Download "Audio (MP3)" → file appears in `Downloads/Torikomi/MUSIC/` as `.mp3` (confirms FFmpeg extraction works).
- [ ] Set a custom SAF folder in Settings → download → file lands in the chosen folder.
- [ ] Share a link from a browser → app opens with the URL prefilled and auto-fetching.
- [ ] Settings → "yt-dlp Engine" shows a version; tapping it re-runs the update without error.
- [ ] Settings → "WhatsApp Status" (and the Home shortcut card) open the WhatsApp screen.
- [ ] History delete removes both the entry and the file.

- [ ] **Step 3: Update spec status**

In `docs/superpowers/specs/2026-06-08-ytdlp-refactor-design.md`, change `**Status:** Design — pending user review` to `**Status:** Implemented`.

- [ ] **Step 4: Commit**

```bash
git add docs/superpowers/specs/2026-06-08-ytdlp-refactor-design.md
git commit -m "docs: mark yt-dlp refactor spec as implemented"
```

---

## Self-review notes (coverage map)

- Spec §5.1 engine → Task 2.1. §5.2 models → Tasks 1.2, 6.2. §5.3 download/storage → Tasks 3.1–3.3. §5.4 nav/screens → Tasks 5.1–5.5. §5.5 settings → Task 5.4. §5.6 repos/migration → Tasks 2.1, 6.1, 6.3. §5.7 URL detector → Task 1.4. §5.8 build/manifest → Tasks 0.1, 3.3, 6.3.
- Spec §6 removed/kept → Phase 6 (delete list matches exactly) + kept files untouched or adapted as noted.
- Spec §7 testing → Tasks 1.1, 1.3, 1.4 (unit) + Task 7.1 (on-device).
- Spec §9 open questions: playlists handled via `--no-playlist` (single item) in Task 2.1; update channel fixed to STABLE (Task 2.1/4.2); WhatsApp entry points on Downloads tab (Task 5.2) + Settings (Task 5.4).
