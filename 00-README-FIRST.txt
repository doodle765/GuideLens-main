GUIDELENS FIX PACK — how to apply
=================================
This pack contains every file from the improvement review. Paths inside the
pack mirror the repository, so each file maps 1:1 to where it belongs.

REPLACE (overwrite the existing file):
  README.md
  .gitignore
  app/build.gradle
  app/proguard-rules.pro
  .github/workflows/build.yml
  app/src/main/java/com/guidelens/app/AlertCenter.kt
  app/src/main/java/com/guidelens/app/Detector.kt
  app/src/main/res/mipmap-mdpi/ic_launcher.png
  app/src/main/res/mipmap-mdpi/ic_launcher_round.png
  app/src/main/res/mipmap-hdpi/ic_launcher.png
  app/src/main/res/mipmap-hdpi/ic_launcher_round.png
  app/src/main/res/mipmap-xhdpi/ic_launcher.png
  app/src/main/res/mipmap-xhdpi/ic_launcher_round.png
  app/src/main/res/mipmap-xxhdpi/ic_launcher.png
  app/src/main/res/mipmap-xxhdpi/ic_launcher_round.png
  app/src/main/res/mipmap-xxxhdpi/ic_launcher.png
  app/src/main/res/mipmap-xxxhdpi/ic_launcher_round.png

NEW (copy into the repo):
  LICENSE
  CHANGELOG.md
  CONTRIBUTING.md
  CODE_OF_CONDUCT.md
  SECURITY.md
  .github/ISSUE_TEMPLATE/bug_report.md
  .github/ISSUE_TEMPLATE/feature_request.md
  .github/ISSUE_TEMPLATE/config.yml
  .github/PULL_REQUEST_TEMPLATE.md
  app/src/main/java/com/guidelens/app/AlertPolicy.kt
  app/src/main/java/com/guidelens/app/SafetyDisclaimer.kt
  app/src/test/java/com/guidelens/app/AlertPolicyTest.kt
  app/src/test/java/com/guidelens/app/DetectorMathTest.kt
  app/src/test/java/com/guidelens/app/GeoTest.kt

DELETE from the repo (leftover artifact, intentionally not shipped here):
  summary_raw.md

ONE-LINE WIRING (manual):
  In MainActivity.onCreate, before setContentView(...), add:
      SafetyDisclaimer.showOnce(this)

THEN VERIFY:
  ./gradlew testDebugUnitTest     # 20+ unit tests must pass
  ./gradlew lint assembleDebug

NOT DONE IN THIS PACK (needs hands-on work — listed in CHANGELOG):
  - Extracting hardcoded strings to strings.xml (+ Urdu locale)
  - Splitting MainActivity.kt / activity_main.xml by screen
  - Full TalkBack pass (contentDescriptions, focus order)
  - Enabling R8/minification on release (keep-rules shipped in proguard-rules.pro)

Notes:
- app/build.gradle bumps versionCode 1 -> 2, versionName 1.0 -> 1.1.0.
- Behavior changes vs. the old code are limited to two deliberate fixes:
  (1) negative distances can no longer trigger a "Stop!" alert,
  (2) .tflite assets are kept uncompressed in the APK (required by the
      openFd() + memory mapping in Detector.kt — previously a latent crash
      depending on aapt compression).
- AlertCenter behavior is otherwise identical; all decisions now come from
  the pure, unit-tested AlertPolicy.
