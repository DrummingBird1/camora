# Release guide

## Two flavors

| Flavor | Contains | Goes to |
|---|---|---|
| `full` | Everything, including Chromecast (Google Play services Cast library) | Google Play, GitHub Releases (`camora-<version>.apk`) |
| `foss` | Everything except Chromecast; no Google or other non-free code | F-Droid, IzzyOnDroid, GitHub Releases (`camora-<version>-foss.apk`) |

Both have the same application ID and the same features otherwise. DLNA TVs, browsers and HLS players
work in both.

## 1. Create the upload key (once)

The key never goes into git and never into a chat. Keep two copies (password manager + offline backup):
if it is lost, Play App Signing can reset the upload key, but only after a support request.

```bash
keytool -genkeypair -v -keystore camora-upload.jks -alias upload -keyalg RSA -keysize 4096 -validity 10000
```

Put the file next to the project (or anywhere) and create `keystore.properties` in the project root
(both are git-ignored):

```properties
storeFile=camora-upload.jks
storePassword=...
keyAlias=upload
keyPassword=...
```

`storeFile` is relative to the project root. Without this file, release builds are signed with the
debug key (fine for sideloading, rejected by Play).

## 2. Version

Raise `versionCode` (every upload) and `versionName` in `app/build.gradle.kts`. `versionCode` follows the
version: major × 10000 + minor × 100 + patch (1.0.0 = 10000, 1.2.3 = 10203). Add the version's section
to `CHANGELOG.md` (`## 1.2.3 ...`): the release notes are taken from it.

## 3. Release on GitHub (automatic)

`.github/workflows/release.yml` runs when a tag `v<versionName>` is pushed. It tests, builds both
flavors signed with the upload key plus the Play bundle, and publishes a GitHub Release with
`camora-<version>.apk`, `camora-<version>-foss.apk`, `camora-<version>.aab` and `SHA256SUMS.txt`.

It needs four repository secrets (Settings, Secrets and variables, Actions). With the GitHub CLI:

```powershell
gh secret set KEYSTORE_BASE64 --body ([Convert]::ToBase64String([IO.File]::ReadAllBytes("camora-upload.jks")))
gh secret set KEYSTORE_PASSWORD
gh secret set KEY_ALIAS --body upload
gh secret set KEY_PASSWORD
```

(`gh secret set NAME` without `--body` asks for the value, so passwords never land in the shell history.)
Then:

```bash
git tag v1.0.0
git push origin v1.0.0
```

Apps installed from GitHub (signed with the upload key) and from Google Play (re-signed by Google with
the app signing key) have different signatures: a phone cannot update from one source to the other
without uninstalling first. Say so in the release notes if it matters.

## 4. Build by hand

```bash
./gradlew testFullDebugUnitTest testFossDebugUnitTest lintFullRelease bundleFullRelease assembleFossRelease
```

The bundle is `app/build/outputs/bundle/fullRelease/app-full-release.aab`. Upload it to Play Console and let
Google manage the app signing key (Play App Signing).

Only `arm64-v8a` and `x86_64` native code is shipped (see `abiFilters`), so old 32-bit phones cannot
install the app. Add `armeabi-v7a` there if they matter.

## 5. Store listings

`fastlane/metadata/android/` holds the title, descriptions, changelogs, icon, feature graphic and
screenshots in English and Hebrew. F-Droid and IzzyOnDroid read them from the repository; use the same
texts in Play Console. Regenerate with `node tools/brand/fastlane.js` after changing the screenshots or
the changelog.

## 6. Before the first upload

Fill in the Play Console forms (listing, permissions, foreground service, data safety) and publish
`docs/PRIVACY.md` at a public address. Then test the release build on a real phone.
