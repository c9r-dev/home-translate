# Home Translate

Brings back the old "hold home → Translate" Assistant feature, fully on-device (ML Kit OCR + ML Kit Translate).

- **Hold home**: the app registers as your Digital assistant app. Holding home shows the current screen with every block of text replaced by its translation. Tap the picture (or hold "Original") to compare, "Copy" copies the translation, "Live" switches to live mode.
- **Live translate (beta)**: a screen-capture overlay that keeps translations on top of any app. It hides while the screen moves and re-translates once it settles. Floating 文A bubble: tap to pause, long-press to stop, drag to move. Also available as a Quick Settings tile.

## Download

Grab the APK from the latest [release](../../releases/latest).

## Releasing

Bump the number in `VERSION` and push to `main`. The workflow builds a signed APK and publishes it as release `v<VERSION>`. Pushing a tag like `v0.4` works too. Other pushes and pull requests just build a test APK, attached to the workflow run under Artifacts.

Release APKs are signed with the key in the repo secrets `SIGNING_KEYSTORE_BASE64`, `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS` and `SIGNING_KEY_PASSWORD`. Keep using the same key, or Android will refuse to install updates over an existing copy.

## Build locally

Needs JDK 17+ and the Android SDK (platform 35). `gradle assembleRelease` (Gradle 8.14) builds `app/build/outputs/apk/release/app-release.apk`, signed with your debug key unless the `SIGNING_*` environment variables are set.

## Setup on the phone

1. Install the APK (allow installs from your browser/files app).
2. Open Home Translate → "Open assistant settings" → set **Digital assistant app** to Home Translate and turn on **Use screenshot**.
3. If holding home opens Circle to Search, switch Circle to Search off.
4. Pick your target language. Each language pair downloads ~30 MB once, then works offline.
