# Contributing to GuideLens

Thanks for helping. GuideLens serves visually impaired and elderly users —
changes here can affect real-world safety, so the bar is a bit higher than a
typical app.

## Ground rules

- **Never weaken a safety behavior** (alert tiers, throttles, disclaimers)
  without an issue discussion first.
- Every behavior change to alerts, distance estimation or navigation needs a
  unit test. Pure logic lives in `AlertPolicy` / `Detector` companion /
  `Geo` — keep it Android-free and tested.
- Match the existing style: small classes, one job per file, KDoc comments on
  public APIs, hardcoded user-facing strings for now (until strings are
  extracted — see CHANGELOG "remaining work").

## Development setup

1. Android Studio (Ladybug+) with SDK 34.
2. Clone, open, let Gradle sync. Models are **committed** in
   `app/src/main/assets/` — builds work offline.
3. Run tests: `./gradlew testDebugUnitTest`
4. Build: `./gradlew assembleDebug`

## Pull requests

- Keep PRs small and focused; CI must pass (unit tests + lint + debug build).
- Add a line to `CHANGELOG.md` under `[Unreleased]`.
- If your change affects spoken output, read it out loud — cadence and
  brevity matter to users listening at walking pace.

## Reporting bugs / requesting features

Use the issue templates. For anything safety-related, use the Security
policy (`SECURITY.md`) instead of a public issue.
