GUIDELENS FIX PACK — v1.2 (blind/elderly usability overhaul)

Apply on top of a project that already has v1.1 applied (or merge everything at
once — this pack contains ALL files, updated).

REPLACE (overwrite existing files):
  README.md
  CHANGELOG.md
  .gitignore
  app/build.gradle
  app/proguard-rules.pro
  .github/workflows/build.yml
  app/src/main/java/com/guidelens/app/AlertPolicy.kt
  app/src/main/java/com/guidelens/app/AlertCenter.kt
  app/src/main/java/com/guidelens/app/Speaker.kt
  app/src/main/java/com/guidelens/app/CameraController.kt
  app/src/main/java/com/guidelens/app/MainActivity.kt
  app/src/main/java/com/guidelens/app/Prefs.kt
  app/src/test/java/com/guidelens/app/AlertPolicyTest.kt
  app/src/main/res/mipmap-*/ic_launcher*.png   (if you have not copied them yet)

NEW (copy into the repo):
  LICENSE  CHANGELOG.md  CONTRIBUTING.md  CODE_OF_CONDUCT.md  SECURITY.md
  .github/ISSUE_TEMPLATE/*  .github/PULL_REQUEST_TEMPLATE.md
  app/src/main/java/com/guidelens/app/Sonar.kt
  app/src/main/java/com/guidelens/app/FallDetector.kt
  app/src/main/java/com/guidelens/app/PocketWatcher.kt
  app/src/test/java/com/guidelens/app/DetectorMathTest.kt
  app/src/test/java/com/guidelens/app/GeoTest.kt

DELETE from the repo (if still present):
  summary_raw.md

NO MANUAL WIRING NEEDED — everything is wired in MainActivity/CameraController.
No AndroidManifest.xml changes are required (accelerometer/proximity need no
permissions; keep-screen-on uses a window flag).

THEN VERIFY:
  ./gradlew.bat testDebugUnitTest     (40 tests)
  ./gradlew.bat lint assembleDebug

NOT DONE (needs hands-on device work, listed in CHANGELOG):
  - Foreground service for screen-off detection
  - strings.xml extraction / Urdu UI text
  - TalkBack pass on the layouts
  - Enabling R8 on release

IMPORTANT NOTES:
  - Fall detection is EXPERIMENTAL — expect some false alarms (e.g. dropping
    the phone hard). It only arms a 15-second spoken countdown; nothing is
    sent unless the user doesn't touch the screen.
  - The sonar beeps use STREAM_NOTIFICATION; volume follows the notification
    volume on the device.
  - If the app feels too chatty still, set verbosity to "minimal" in settings.
