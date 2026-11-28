# OnePlus OTA

A native Android app (Kotlin + Jetpack Compose) that finds, downloads and unpacks full OTA firmware for OnePlus phones, straight from OnePlus update servers.

Maintained by [WajahatNaeem056](https://github.com/WajahatNaeem056).

## Download

Get the latest APK from the [Releases](../../releases) page (current version: **1.0.0**).

Every push to `main` also builds an APK on GitHub Actions: open the **Actions** tab, pick the latest run and download its artifact.

Requirements: a OnePlus device, Android 10 (API 29) or newer, internet access.

## What it does

1. Finds the latest full OTA for your phone, or any other OnePlus model, and shows version, changelog, file name and MD5.
2. Downloads it in the background with pause/resume. It never restarts from zero after an error.
3. Extracts single partitions (`boot`, `init_boot`, `vendor_boot` ...) from an OTA zip without downloading the whole file.

Useful when you want the OTA zip for a local install, or just a `boot.img` for patching, without pulling the full package every time.

## Features

- **Latest full OTA lookup** with a fresh link on every check, so links are never stale.
- **Multiple sources**, tried in order: OnePlus servers directly, then Oxygen Updater.
- **Region guard**: a package is shown only if its own metadata matches your device and region. If it carries no readable model, it is marked unverified, not guessed.
- **Resumable background download** as a foreground service, with a `.part` file kept on errors, MD5 verification, and Save to Downloads for the system updater's local-install picker.
- **Partition extraction** from a link (only the needed parts are fetched using HTTP Range) or from a local OTA `.zip`, with search and multi-select. Output goes to `Downloads/OnePlusOTA/<OTA name>/`.
- **ARB check**: reads the anti-rollback index from the OTA's `xbl_config`.
- **OnePlus-only**: the app works only on OnePlus devices.
- **Settings**: theme, and auto check for updates on app open.

### Tabs

| Tab | Purpose |
|---|---|
| Update | Detects your device and region, checks for the latest full OTA. Changelog, download link (copy), Download / Pause / Resume / Cancel. |
| Other | Flat per-region device list with search, to fetch OTA for other OnePlus devices. |
| Extract | Partition extraction from a link or a local zip. |
| Settings | Theme and auto check. |

## How it works

- **Lookup:** requests use the same encrypted v2 protocol as the official updater (AES-256-CTR body, key wrapped with the server's RSA public key).
- **Download:** resume uses HTTP Range with `If-Range` and `Accept-Encoding: identity`, so a changed file is detected instead of corrupting the download.
- **Extraction:** reads the zip's central directory and the `payload.bin` manifest with Range requests, then fetches only the selected partitions. Runs as a background job with saved progress.

## Project structure

```
app/src/main/java/com/wajahatnaeem/oneplusota/
  data/       OTA sources, request crypto, link resolver, region guard, device catalog
  download/   resumable downloader and foreground service
  extract/    remote zip and payload parsing, partition extraction, ARB check
  ui/         Compose screens and view models
devices/      device catalog (oneplus.json)
tools/        catalog validator (validate_catalog.py)
.github/      CI workflow that builds the debug APK
```

## Build

CI builds automatically (see Download). Local build needs JDK 17 and Gradle 8.9:

```
gradle assembleDebug
```

To publish a release, bump `versionName` / `versionCode` in `app/build.gradle.kts`, then push a tag:

```
git tag v1.0.0
git push origin v1.0.0
```

The workflow builds the APK and attaches it to a GitHub Release. If the repo secrets `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS` and `KEY_PASSWORD` are set, the release APK is signed with your key; otherwise a debug APK is built.

## Device list

`devices/oneplus.json` is the device and region catalog. A copy is bundled at `app/src/main/assets/oneplus_devices.json`, and both files must be identical. CI checks them:

```
python3 tools/validate_catalog.py
```

## Rules for changes

- Keep the `.part` file on download errors.
- Keep `Accept-Encoding: identity` and `If-Range` on resume.
- Keep the source order: OnePlus direct, then Oxygen Updater.
- Keep the OnePlus-only gate.
- All in-app text stays in English.
