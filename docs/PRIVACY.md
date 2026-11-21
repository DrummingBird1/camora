# Camora privacy policy

Last updated: 2026-11-12

Contact: Solvaris2@gmail.com

## Summary

Camora shows, records and streams video from your own IP cameras and USB capture cards. It has no
account, no advertising and no analytics. Video, sound and settings stay on your phone and on the
devices you choose to stream to.

## What the app accesses and why

| Access | Why |
| --- | --- |
| Camera permission | Android only lets apps open a USB video device (a capture card) when they hold it. The phone's own cameras are not used. |
| Microphone permission | A capture card's sound arrives as a USB audio input, read through the microphone API. The phone's own microphone is not recorded on purpose. |
| Local network | To connect to your IP cameras, find TVs and Chromecast devices, and serve the stream to devices on your network. |
| Notifications | To show that the app is recording, streaming or watching for motion, and to report detected motion. |
| Storage (app folder) | Recordings are saved in the app's own folder on the storage you choose (the phone, an SD card, or a folder you pick on a USB drive). "Save to gallery" copies a recording to your Movies folder, and a snapshot goes to Pictures/Camora, only when you ask. |

## Data stored on the phone

- Camera names and addresses. Camera passwords are encrypted with a key kept in the Android Keystore.
- Recordings and a list of motion events.
- App settings.

Uninstalling the app deletes all of this, except recordings you saved to the gallery.

## Data that leaves the phone

- **Streaming:** when you turn on the streaming server, devices on your network that have the link (which contains a secret key) can watch the cameras you share.
- **Remote access (off by default):** if you turn it on, the app asks your router to open a port so the stream can be reached from the internet, protected only by the secret key.
- **Chromecast:** casting uses Google Play services. Google's privacy policy applies to that part: https://policies.google.com/privacy. The F-Droid build (foss) has no Chromecast support and no Google code at all.
- **Feedback (only when you ask):** "Send feedback" in Settings opens your email app with a report attached: app and Android version, phone model, the last crash and the app's own recent log. Camera addresses, passwords and stream keys are removed from it, and you can read it before you send. Nothing is sent unless you press Send.
- Nothing else is sent to the developer.

## Children

The app is not directed at children.

## Changes

Changes to this policy will be published at the same address with a new date.
