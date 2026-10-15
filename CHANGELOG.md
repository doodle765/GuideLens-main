# Changelog

## [1.2.0] - 2026-10-11  — blind/elderly usability overhaul

### Added
- Sonar earcon channel (new Sonar.kt): beeps that rise in rate/pitch as the
  nearest obstacle approaches, stereo-panned to its side; bypasses TTS so it
  can never lag; soft 10-second heartbeat so the user knows the app is alive.
- Detection auto-starts on launch and auto-restarts after interruptions.
- "Person approaching" / "moving away" tracking and one-shot "Path clear" /
  "Clear on your left" announcements (pure, unit-tested AlertPolicy tracking).
- Periodic sign reading in detection mode: every 4 s the OCR pass announces
  NEW text ("Sign says: ...") with 30 s dedupe.
- Spoken onboarding tutorial after the safety disclaimer (holding the phone,
  overhang limitation, one-earbud guidance, voice commands).
- Pocket pause (proximity sensor) — mutes alerts + sonar while covered.
- Frame-luminance watchdog: tells the user when the lens is covered or it is
  too dark, and says "I can see again" on recovery.
- Double-tap anywhere repeats the last spoken message.
- EXPERIMENTAL fall detection (new FallDetector.kt): impact + stillness
  heuristic -> 15 s spoken countdown -> SOS unless cancelled by touch.
- TTS language follows the system locale (Urdu device -> Urdu voice) with
  automatic English fallback.

### Fixed
- "It doesn't stop talking": Speech queue is now capped at 2 pending items,
  de-duplicates, and priority alerts cut off non-priority speech.
- Side objects no longer get a running commentary (NOTICE tier is ahead-only);
  receding objects are silent for 8 s; static close objects repeat at most
  every 15 s ("still ...").
- OCR "reads only once": "Read this" and periodic sign reading now use the
  camera frame cache instead of PreviewView.getBitmap() (which returns null
  on many Xiaomi/MIUI devices).
- AlertCenter/CameraController pipeline reworked: tracked per-frame alerts
  via onFrame() instead of untracked per-detection alert() calls.

### Changed
- AlertPolicy API: evaluate()/tick() return Say events; includes tracking
  state and urgency(); 22 new/updated unit tests (40 total in the suite).

### Not done (needs device work)
- Foreground service for screen-off detection (CameraController is bound to
  the Activity; a service needs a device-tested refactor).

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
- ~~`SafetyDisclaimer` — one-time in-app disclaimer~~ REMOVED: MainActivity
  already ships a richer first-launch disclaimer (`showDisclaimer()`, gated by
  `prefs.agreed`). The extra file was dead code and has been dropped from the
  pack — do NOT wire it in.
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
