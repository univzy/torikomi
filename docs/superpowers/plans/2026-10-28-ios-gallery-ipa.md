# iOS gallery and IPA build

Match Android's current-photo Download and separate Download all behavior using a
native SwiftUI pager. Resolve supported TikTok/Instagram photo posts in a visible
WebKit sheet, read structured page media, and validate a bounded list of HTTPS
photo URLs before passing it to the existing native downloader. Keep direct-media
downloads, JSON backups, local library persistence and no-server behavior.

Use the existing macOS GitHub Actions runner for Swift tests and Xcode archive.
Add an iOS-only dispatch choice to avoid rebuilding unrelated platforms. Without
configured iOS signing secrets, package the compiled device app as an explicitly
unsigned IPA for re-signing/sideloading. Work on a separate build branch; preserve
the shared working tree and do not publish a release or merge to main.

- [ ] Test URL normalization, bounded photo decoding, exact current/all selection,
  invalid URLs and missing photos in the portable Swift core.
- [ ] Add native album browsing, pager and serial batch download with cancellation
  and existing per-file atomic library saves.
- [ ] Run Swift tests and Xcode build on macOS CI, fetch the generated IPA, and
  inspect the archive contents and signing status.
