# Roadmap

Where Camora stands and what is still open. This is a status page, not a promise: dates are not committed. Details of what shipped are in [CHANGELOG.md](CHANGELOG.md).

## Current state

- Version 1.0.0 is released on GitHub (`full` and `foss` builds). Google Play is "coming soon" (see README); the F-Droid build and store metadata are prepared.
- Android app (Kotlin, Jetpack Compose), minSdk 26. Apache 2.0.
- Covered: RTSP/ONVIF cameras and USB HDMI capture cards, motion alerts, recording (MP4/MKV/TS, phone, SD card or USB drive), streaming to browsers, DLNA TVs, Chromecast and HLS players, 8 languages.
- Unit tests cover the pure logic. Video, audio, networking and lifecycle changes are checked by hand on a phone (see `docs/TESTING.md`).

## Open questions

- **Google Play:** whether and when to publish the first Play listing (store metadata is in `fastlane/`).
- **Hardware coverage:** which phones, cameras and capture cards have been verified. Reports from the community feed this (see the camera report template in `.github/ISSUE_TEMPLATE`).
- **Casting and DLNA:** how widely it has been tried across TV and Chromecast models.
- **Remote access (UPnP):** stays opt-in with a clear warning; real-router behaviour has not been broadly tested.
- **Media3:** pinned at 1.8.0 on purpose because newer versions broke lip-sync; needs a recheck before any bump (`docs/TESTING.md`).

## Not planned yet

No feature roadmap exists. Ideas and requests go through the Feature request issue template; this file will list accepted items once there are any.
