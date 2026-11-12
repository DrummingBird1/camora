<div align="center">

<img src="assets/brand/banner.png" width="100%" alt="Camora: Your cameras and capture card, recorded and on every screen.">

  <a href="LICENSE"><img alt="License" src="https://img.shields.io/badge/license-Apache%202.0-B8A4FF?style=flat-square"></a>
  <img alt="Android 8.0+" src="https://img.shields.io/badge/Android-8.0%2B-3DDC84?style=flat-square&logo=android&logoColor=white">
  <img alt="Kotlin" src="https://img.shields.io/badge/Kotlin-Jetpack%20Compose-7F52FF?style=flat-square&logo=kotlin&logoColor=white">
  <a href="https://github.com/DrummingBird1/camora/actions"><img alt="Build" src="https://github.com/DrummingBird1/camora/actions/workflows/android.yml/badge.svg"></a>

<b>English</b> · <a href="docs/readme/README.he.md">עברית</a> · <a href="docs/readme/README.ar.md">العربية</a> · <a href="docs/readme/README.ru.md">Русский</a> · <a href="docs/readme/README.es.md">Español</a> · <a href="docs/readme/README.fr.md">Français</a> · <a href="docs/readme/README.de.md">Deutsch</a> · <a href="docs/readme/README.pt.md">Português</a>

</div>

Camora turns an Android phone into a small video hub for your own cameras: watch IP cameras and HDMI capture cards, record them, get motion alerts, and put them on any screen in the house. No account, no ads, no tracking.

<p align="center">
  <img src="assets/store/screenshots/en-1-cameras.png" width="23%" alt="">
  <img src="assets/store/screenshots/en-2-stream.png" width="23%" alt="">
  <img src="assets/store/screenshots/en-3-recordings.png" width="23%" alt="">
  <img src="assets/store/screenshots/en-4-settings.png" width="23%" alt="">
</p>

## Features

| | | |
|---|---|---|
| 📷 | **IP cameras** | RTSP over TCP or UDP, ONVIF discovery, automatic reconnect. Passwords are encrypted on the phone. |
| 🎮 | **Capture cards** | USB HDMI capture cards (UVC) with sound, mode selection and a live frame-rate readout. |
| ⏺️ | **Recording** | MP4 with sound, record on motion, cleanup by age or storage limit, save to the gallery. |
| 🔔 | **Motion alerts** | Three sensitivities, notifications, keeps watching in the background and with the screen off. |
| 📺 | **On every screen** | Low-delay browser player, live TS for DLNA TVs, Chromecast, HLS for VLC and other players. |
| ✨ | **Quality** | Up to 4K, quality presets, H.264 or H.265 per camera. |
| 🔒 | **Private** | Everything stays on your network. Optional HTTPS, and remote access only if you turn it on. |
| 🌍 | **8 languages** | English, Hebrew, Arabic, Russian, Spanish, French, German and Portuguese. |

## 📥 Get Camora

Coming soon to Google Play. Until then you can build it from source (below).

**Website:** https://drummingbird1.github.io/camora/

## 📺 Watching on other screens

| Where | How |
|---|---|
| Computer or phone browser | Lowest delay (about half a second) |
| Samsung and other DLNA TVs | Live TS, a few seconds of delay from the TV |
| Chromecast / Google TV | HLS, a few seconds of delay from the receiver |
| VLC, OBS and other players | HLS or live TS address |

## 🔧 Requirements

Android 8.0 or newer. A capture card needs USB OTG (an adapter or a USB-C card). For long recordings use a USB-C hub with charging: a capture card and continuous encoding use a lot of battery.

## 🛠️ Build from source

You need JDK 17+ and the Android SDK 36.

```bash
git clone https://github.com/DrummingBird1/camora.git
cd camora
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## 📚 Documentation

- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md): How video flows from a source to recordings and viewers
- [docs/TESTING.md](docs/TESTING.md): Testing without hardware: fake RTSP cameras and measuring tools
- [docs/RELEASE.md](docs/RELEASE.md): Signing and release builds
- [.github/CONTRIBUTING.md](.github/CONTRIBUTING.md): How to contribute

## 🔒 Privacy

Camora has no account, no ads and no analytics, and sends nothing to its developer unless you choose Send feedback. [Read the privacy policy](https://drummingbird1.github.io/camora/privacy.html)

## 💜 Support the project

Camora is free and open source. If it is useful to you, you can support its development:

<p align="center">
  <a href="https://github.com/sponsors/DrummingBird1"><img alt="GitHub Sponsors" src="https://img.shields.io/badge/GitHub%20Sponsors-EA4AAA?style=for-the-badge&logo=githubsponsors&logoColor=white"></a>
  <a href="https://www.patreon.com/c/Solvaris"><img alt="Patreon" src="https://img.shields.io/badge/Patreon-F96854?style=for-the-badge&logo=patreon&logoColor=white"></a>
  <a href="https://ko-fi.com/solvaris1"><img alt="Ko-fi" src="https://img.shields.io/badge/Ko--fi-FF5E5B?style=for-the-badge&logo=kofi&logoColor=white"></a>
  <a href="https://buymeacoffee.com/solvaris"><img alt="Buy Me a Coffee" src="https://img.shields.io/badge/Buy%20Me%20a%20Coffee-FFDD00?style=for-the-badge&logo=buymeacoffee&logoColor=black"></a>
</p>

## 🤝 Contributing

Bug reports, ideas and pull requests are welcome. Please read CONTRIBUTING.md first.

## 📄 License

Apache License 2.0. Third-party components and their licenses are listed in the app (Settings, Tools, Open-source licenses).
