# Changelog

## [1.1.0] - 2026-10-11

### Added
- `LICENSE` (MIT).
- Unit tests (`./gradlew testDebugUnitTest`):
  - `AlertPolicyTest` — every distance tier, sensitivity multiplier, verbosity
    gating and throttle window.
  - `DetectorMathTest` — monocular distance estimation, side classification,
    spoken distance phrasing.
  - `GeoTest` — haversine, polyline decoding, route length, maneuver phrasing.
- New pure-Kotlin `AlertPolicy` class (all alert decision logic, zero Android
  imports) — `AlertCenter` is now a thin Android shell over it.
- `SafetyDisclaimer` — one-time spoken/dialog disclaimer on first launch
  ("complement to your cane or guide dog, never a replacement").
- GitHub community files: `CONTRIBUTING.md`, `CODE_OF_CONDUCT.md`,
  `SECURITY.md`, issue templates, PR template.
- CI now runs unit tests + lint before building, uploads lint reports, and
  builds a release APK on version tags (`v*`).
- Real launcher icons (high-contrast lens mark; previously near-blank
  placeholders).
- `CHANGELOG.md`.

### Fixed
- `.tflite` assets are no longer compressed into the APK
  (`androidResources { noCompress "tflite" })` — `assets.openFd()` + memory
  mapping requires uncompressed assets.
- Negative distances can no longer trigger a "Stop!" alert (`AlertPolicy`
  treats `dist < 0` as out of range).
- README stale claims: models are bundled/committed (no build-time download
  required); fixed stats and added testing, privacy, device-requirement and
  self-hosting sections.
- `.gitignore` replaced with a complete Android ruleset.

### Removed
- (You must delete `summary_raw.md` from the repo root — a leftover build
  artifact. It is intentionally NOT included in this pack.)

### Known remaining work (not in this pack — needs hands-on repo/device work)
- Extract hardcoded strings to `res/values/strings.xml` (+ Urdu locale).
- Split `MainActivity.kt` / `activity_main.xml` by screen.
- Full TalkBack pass (`contentDescription`s, focus order).
- Enable R8/minification once keep-rules are validated on a release build.
