# GuideLens Android

[![Build & test](https://github.com/doodle765/GuideLens/actions/workflows/build.yml/badge.svg)](https://github.com/doodle765/GuideLens/actions/workflows/build.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/platform-Android%207.0%2B-green)](https://android.com)

Native Android version of the **GuideLens** assistive app (visually impaired & elderly users),
mirroring the GuideLens PWA: same screens, same voice-first flow, same distance-tiered alerts.
**Everything works fully offline except reverse-geocoding ("Where am I?") and walking-route**
**download, which need internet** — and both fail gracefully with a _spoken_ message.

## Screenshots

<!-- TODO: replace with real captures. A short screen-recording of obstacle
     detection + spoken alerts is worth more than any text here. -->
| Obstacle alerts | Sign reading | Navigation |
|---|---|---|
| _screenshot_ | _screenshot_ | _screenshot_ |

## What works offline (on-device)

| Feature | Implementation |
|---|---|
| Obstacle alerts | SSD MobileNetV2 FPNLite via TensorFlow Lite (**bundled** in `app/src/main/assets/`, ~7 MB total) |
| Pole / wall / fence zones | Optional DeepLabV3 Cityscapes segmentation (bundled) |
| Distance tiers | <=7 m notice (max 2x), <=5 m "coming close", <=3 m "Stop!" + triple vibration |
| Spoken output | Android TextToSpeech (offline voices) |
| Voice commands | Android SpeechRecognizer with `EXTRA_PREFER_OFFLINE` (see note below) |
| Sign reading | ML Kit Text Recognition **bundled** model — no network |
| Vibration / SOS | Local vibrator + `smsto:` intent with Google-Maps location link |
| Settings, themes, simple mode | 100% local |

## What needs internet

- **Where am I?** — reverse geocoding. Offline, the app **says out loud**:
  _"No internet access. I can't tell you where you are right now."_
- **Navigate somewhere** — walking routes come from OSRM (routing.openstreetmap.de).
  Offline, the app says: _"Navigation needs an internet connection to download the
  walking route. Everything else works offline."_
  (True offline routing requires bundling city-sized map data — out of scope.)

> **Deployment note:** the public OSRM/Nominatim services have usage policies and
> rate limits. If GuideLens gains real users, self-host an OSRM foot-profile
> instance and point `Geo.kt` at it.

## Voice input note

Offline speech recognition depends on the phone's speech service (e.g. Google app with the
English offline pack downloaded). If offline recognition isn't available, the app says it
didn't catch that — the big buttons always work regardless.

## Device requirements

- Android 7.0 (API 24) or newer, 3 GB RAM recommended (segmentation is heavy on low-end devices)
- Working offline TTS (e.g. Google TTS with the English voice pack)
- Camera, microphone and location permissions

## Privacy

Camera frames, microphone audio and your location are processed **on-device only** —
nothing is uploaded or stored. The only network requests are geocoding (Nominatim) and
walking routes (OSRM), which necessarily include the queried coordinates/address.

## Build

### Option A — GitHub Actions (zero setup)

Push to `main` (or open a PR). The workflow runs **unit tests + lint**, then builds a
debug APK and uploads it as an installable artifact. Pushing a tag like `v1.1.0` also
builds a release APK.

### Option B — Android Studio

1. **Open** this folder in Android Studio (Ladybug or newer).
2. Let Gradle sync. The ML models are **committed** in `app/src/main/assets/` — the
   `downloadModels` task only fetches them if the committed file is missing or invalid.
3. **Build > Build Bundle(s)/APK(s) > Build APK(s)**, then install on the phone.
4. Grant **Camera**, **Microphone** and **Location** permissions when asked.

### Option C — command line

```
./gradlew testDebugUnitTest   # unit tests (JVM, no device)
./gradlew lint                # static analysis
./gradlew assembleDebug       # app/build/outputs/apk/debug/app-debug.apk
```

## Testing

Alert thresholds, throttles, monocular distance math, polyline decoding and maneuver
phrasing are covered by JVM unit tests in `app/src/test/` — see `AlertPolicyTest`,
`DetectorMathTest` and `GeoTest`. Run them with `./gradlew testDebugUnitTest`.
Behavior changes to alerts or navigation should add or update tests there.

## Project layout

```
app/src/main/java/com/guidelens/app/
  MainActivity.kt     screens, wiring, navigation guidance, SOS, settings
  CameraController.kt CameraX preview + detection loop (fully offline)
  Detector.kt         COCO-SSD TFLite wrapper + monocular distance estimate
  Segmenter.kt        optional DeepLab cityscapes zones (poles/walls/fences)
  OverlayView.kt      bounding boxes + danger-zone canvas
  AlertCenter.kt      Android shell: executes AlertPolicy decisions (TTS + buzz)
  AlertPolicy.kt      pure, unit-tested tier/throttle decisions (no Android deps)
  Speaker.kt          TextToSpeech wrapper
  VoiceController.kt  hold-to-talk offline speech recognition
  Geo.kt              geocoding, OSRM routing, polyline decode, "Where am I"
  OcrReader.kt        ML Kit offline OCR
  Prefs.kt            settings storage
  SafetyDisclaimer.kt one-time in-app safety disclaimer (first launch)
```

## Honest limitations (safety-critical — read this)

- **Distance is estimated from a single camera** (monocular). Treat it as approximate
  bands (7 m / 5 m / 3 m), never exact meters.
- The segmentation model is optional; if the download fails or class indices don't match
  your model variant, named-object detection still works. Adjust `OBSTACLE_IDS` in
  `Segmenter.kt` for a different model.
- The segmentation class indices assume Cityscapes trainIds (wall=3, fence=4, pole=5,
  traffic light=6, traffic sign=7).
- The "Prefer step-free routes" setting is stored but not applied (OSRM foot profile has
  no step-free flag).
- **GuideLens is a complement to your cane or guide dog, never a replacement.**
  This is a working prototype, not a certified safety device. The same warning is shown
  in-app on first launch.

## Contributing

Bug reports and pull requests are welcome — see [CONTRIBUTING.md](CONTRIBUTING.md).
Please read the safety limitations above before changing anything alert-related.

## License

[MIT](LICENSE) — use it, learn from it, build on it.
