# Platform Folders Implementation Plan

> **For agentic workers:** Execute the authorized folder reorganization inline, then verify the resulting builds. Preserve all existing local edits; do not create commits or change branches.

**Goal:** Provide `android/`, `macos/`, `windows/`, and `ios/` at the repository root.

**Architecture:** Move the Android module from `app/` to `android/`, retaining the root Gradle wrapper and local signing configuration. Move the shared Electron application to `shared/desktop/`; give Windows and macOS their own package commands, packaging configuration and output folders. Keep the iOS project and shared sync fixture in place.

**Tech Stack:** Gradle/Kotlin, Node/Electron, Swift/XcodeGen, GitHub Actions.

**Spec:** User's request for separate platform folders; root `AGENTS.md`.

## Global Constraints

- Preserve source contents and uncommitted work when moving folders.
- Leave root SDK paths, signing files and keys in place; Android stays one directory below the root.
- Keep one copy of the shared desktop application and one dependency lockfile.
- Update commands, fixture lookups, artifact paths, documentation and release jobs together.
- Do not change application behavior or publish a release.

## Review Focus

- Verify source hashes immediately after the moves, before intentional edits.
- Rebuild Android under its new Gradle module name to catch generated-path dependencies.
- Run desktop sync tests to catch the fixture's new relative path.
- Launch the desktop UI through platform package commands to verify working-directory handling.
- Package Windows using its own configuration; check macOS configuration without claiming an Apple build.

## Tasks

- [x] Move `app/` and `desktop/` safely; verify source hashes.
- [x] Update `settings.gradle.kts`, the desktop fixture lookup and shared package scripts.
- [x] Add `windows/` and `macos/` package commands and packaging configuration.
- [x] Update `.github/workflows/release.yml`, `.gitignore`, `README.md` and `docs/platforms.md`.
- [x] Run Android tests/lint/build, desktop tests/UI smoke, Windows packaging and configuration checks.

## Execution Notes

The current workspace contains the user's existing edits and the prior four-platform implementation. Work in place so the requested reorganization includes that exact state.

The initial Android move encountered a Gradle-held generated JAR. Stop the Gradle
daemon, move source directories atomically, and preserve leftover generated cache
under ignored `build/previous-android-build/`. SHA-256 checks confirmed 98 source,
configuration and asset files were unchanged before the intentional path edits.

Verification: `:android:testDebugUnitTest :android:lintDebug :android:assembleDebug`
passed; both platform test commands passed all six shared tests. Windows setup,
UI smoke and installer packaging passed through the new platform commands.
Release YAML, macOS packaging paths and installer-only artifact filters were
checked. Apple builds remain unverified on this Windows host.
