# Release guide

## 1. Create the upload key (once)

The key never goes into git and never into a chat. Keep two copies (password manager + offline backup):
if it is lost, Play App Signing can reset the upload key, but only after a support request.

```bash
keytool -genkeypair -v -keystore ipcamhub-upload.jks -alias upload -keyalg RSA -keysize 4096 -validity 10000
```

Put the file next to the project (or anywhere) and create `keystore.properties` in the project root
(both are git-ignored):

```properties
storeFile=ipcamhub-upload.jks
storePassword=...
keyAlias=upload
keyPassword=...
```

`storeFile` is relative to the project root. Without this file, release builds are signed with the
debug key (fine for sideloading, rejected by Play).

## 2. Version

Raise `versionCode` (every upload) and `versionName` in `app/build.gradle.kts`. `versionCode` follows the version: major × 10000 + minor × 100 + patch (1.0.0 = 10000, 1.2.3 = 10203).

## 3. Build

```bash
./gradlew testDebugUnitTest lintDebug bundleRelease
```

The bundle is `app/build/outputs/bundle/release/app-release.aab`. Upload it to Play Console and let
Google manage the app signing key (Play App Signing).

Only `arm64-v8a` and `x86_64` native code is shipped (see `abiFilters`), so old 32-bit phones cannot
install the app. Add `armeabi-v7a` there if they matter.

## 4. Before the first upload

Fill in the Play Console forms (listing, permissions, foreground service, data safety) and publish
`docs/PRIVACY.md` at a public address. Then test the release build on a real phone.
