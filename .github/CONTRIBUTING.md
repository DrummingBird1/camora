# Contributing to Camora

Thanks for helping! Issues and pull requests in English or Hebrew are both welcome.

## Reporting a bug

Open an issue with the **Bug report** template. Please include the phone model, Android version, the
camera or capture card, and what you expected versus what happened. Screenshots or a short screen
recording help a lot. Never post stream addresses with their `?k=` key or camera passwords.

## Suggesting a feature

Open an issue with the **Feature request** template and describe the problem you want solved.

## Pull requests

1. Fork the repo and branch from `main` (`feat/...` or `fix/...`).
2. Keep the change small and focused. Pure logic gets a unit test (see `app/src/test`).
3. Run `./gradlew testDebugUnitTest lintDebug assembleDebug`.
4. If you touched video, audio, networking or the app lifecycle, test on a phone or emulator
   (`docs/TESTING.md` explains how to do it without real cameras) and say what you tested.
5. User-visible text goes into `tools/i18n/catalog.js` for every language; run `node tools/i18n/build.js`.
6. Describe what changed, why, and what you could not test.

## Ground rules

- No secrets in commits: stream keys, camera URLs with passwords, keystores, `local.properties`.
- No code that sends media to, or controls, a device the user did not set up.
- Anything that exposes the app to the internet is opt-in, with a clear warning.
- Be kind. Harassment or personal attacks are not tolerated in issues, pull requests or discussions.
