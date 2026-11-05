# Castor

Lightweight full-screen Android shell for gemini.google.com.
Package: `app.castor.shell` | minSdk 26 | targetSdk 34 | Java, 3 small AndroidX deps.

## Build
- **GitHub:** push this folder, Actions > "Build APK" > download `Castor-apk` (release APK, debug-signed).
- **Android Studio:** open the folder, let it sync, Build > Build APK(s).
- **CLI:** `gradle assembleRelease` (JDK 17) -> `app/build/outputs/apk/release/app-release.apk`

## Features
- Full screen, no app bar (toggle in panel). Slim semi-transparent handle at mid-right: tap or drag left.
- Pull to refresh (aware of Gemini's inner scrolling chat view).
- Separate accounts: each one is an isolated WebView profile (own cookies, storage, cache). Needs WebView 128+.
- UI cache: JS/CSS/fonts/images from Google static hosts are saved on first load and served from disk after (200 MB cap, LRU, refreshed every 14 days).
- Mic permission for Gemini voice input, file upload, desktop-site toggle, external links open in your browser.

- Home page: set any link as the page the app opens (panel > Home page); leave empty for Gemini. Links on that site stay inside the app.
- Downloads: go through Android's system download manager (notification, Downloads folder, login cookies kept). Switch to 'Ask which app' to hand links to 1DM/ADM/etc. Images and files made by the page (blob/data) are saved straight to Downloads.
- Extras: Share link, Open downloads, Open Gemini shortcut when a custom home is set.
