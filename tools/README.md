# Developer tools

Plain Node scripts, no dependencies (Node 18+), plus one PowerShell script. Recipes that use them are in `../docs/TESTING.md`.

| Tool | What it does |
|---|---|
| `dev/rtsp-test-server.js` | Fake RTSP camera: serves an H.264 file in a loop over RTP/TCP to any number of clients, optional 440 Hz G.711 tone. Header explains how to make the test clip. |
| `dev/seed-cameras.js` | Writes the app's camera list (DataStore file) from a JSON file, to start a debug build with cameras already added. `dev/example-cameras.json` is a starting point. |
| `dev/stream-viewer.js` | Pulls a live stream for N seconds, prints bytes per second and the first chunk timings, optionally saves the stream for ffprobe. |
| `i18n/build.js` | Writes `strings.xml` for all 8 languages from `i18n/catalog.js`, the single source of every user-visible string. |
| `brand/build-site.js` | Builds the website in `site/` from `assets/brand/brand.json`, the screenshots and `docs/PRIVACY.md`. |
| `brand/render.js` | Renders the logo, app icon, banner and store graphics in `assets/` from the SVG sources in `assets/brand/`. |

Keep tools dependency-free and print a usage line when called without arguments.
