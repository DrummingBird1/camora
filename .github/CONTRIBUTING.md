# Contributing to Camora

Thanks for helping! Issues and pull requests in English or Hebrew are both welcome.

## Reporting a bug

Open an issue with the **Bug report** template. Please include the phone model, Android version, the
camera or capture card, and what you expected versus what happened. Screenshots or a short screen
recording help a lot. Never post stream addresses with their `?k=` key or camera passwords.

## Reporting a camera or capture card

Whether it works or not, the **Camera or capture card report** template helps others know what to buy
and helps fix what does not work.

## Suggesting a feature or asking a question

Open an issue with the **Feature request** template and describe the problem you want solved. For
questions and setup help, use [Discussions](https://github.com/DrummingBird1/camora/discussions).

## Translations

All app text lives in `tools/i18n/catalog.js`, one row per text with a column per language. Fix a
translation there, or add a language column, and run `node tools/i18n/build.js`. Good first issues for
translators are labelled `translation`.

## Builds

The app has two flavors: `full` (with Chromecast) and `foss` (no Google code, for F-Droid). Code shared
by both goes in `app/src/main`; only the Chromecast code lives in `app/src/full`, with a stub in
`app/src/foss`.

## Pull requests

1. Fork the repo and branch from `main` (`feat/...` or `fix/...`).
2. Keep the change small and focused. Pure logic gets a unit test (see `app/src/test`).
3. Run `./gradlew testFullDebugUnitTest testFossDebugUnitTest lintFullDebug lintFossDebug assembleDebug`.
4. If you touched video, audio, networking or the app lifecycle, test on a phone or emulator
   (`docs/TESTING.md` explains how to do it without real cameras) and say what you tested.
5. User-visible text goes into `tools/i18n/catalog.js` for every language; run `node tools/i18n/build.js`.
6. Describe what changed, why, and what you could not test.

## Ground rules

- No secrets in commits: stream keys, camera URLs with passwords, keystores, `local.properties`.
- No code that sends media to, or controls, a device the user did not set up.
- Anything that exposes the app to the internet is opt-in, with a clear warning.
- Be kind: see the [code of conduct](CODE_OF_CONDUCT.md).
