# Security Policy

## Reporting a vulnerability

Do **not** open a public issue for security problems. Instead, contact the
maintainer directly (see the README contact section) with:

- a description of the issue and its impact,
- steps to reproduce,
- the app version (`versionName`) and Android version.

## Scope

- In scope: the GuideLens Android app, its CI workflow, and this repository.
- Out of scope: upstream services it calls (Nominatim, routing.openstreetmap.de).

## Privacy posture (for reference)

Camera frames, microphone audio and location are processed **on-device only**
and are never uploaded. The only network requests are geocoding (Nominatim)
and walking routes (OSRM), which transmit the queried coordinates/address.
