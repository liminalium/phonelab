# PhoneLab

An Android app that turns a phone into a recording, measurement and automation
lab. This first build is the **system check**: on first launch it reads
everything the phone exposes, tests the hardware on request, and saves a report.
The report decides which modules from the build plan are worth building for
this phone.

## Install on your phone

Direct download (always the newest build):
https://github.com/liminalium/phonelab/releases/latest/download/phonelab.apk

For automatic updates, install [Obtainium](https://github.com/ImranR98/Obtainium),
tap **Add app**, and paste `https://github.com/liminalium/phonelab`.

On MIUI, allow installs from your browser or Obtainium when asked, and ignore
the security scan's warning about an unknown developer.

## What the system check does

A step-by-step guide with screenshots: [docs/system-check.md](docs/system-check.md).

- **Phone info** (every run, no permissions): device and OS, MIUI version,
  CPU clusters, GPU, memory and storage, display, battery (including design
  capacity and cycle count where readable), thermal zones, every sensor,
  every camera's capabilities, media codecs, audio devices and microphones,
  radios (Wi-Fi bands, BLE features, NFC, IR, UWB, GNSS chip), network,
  security (keystore, StrongBox, encryption), system features, related apps,
  and what the screen recorder still needs.
- **Hardware tests** (on request): real sensor sample rates, a microphone
  level check, each camera streaming alone and every pair together (Android
  10 has no API that says whether front and back can run at once), a 30 s
  GNSS listen (constellations, dual-frequency, raw measurements), a Wi-Fi
  scan, cell info and a BLE scan.
- **What this phone can do**: the plan's module catalog, marked ✓ / ◐ / ✗.

Each run is saved as JSON in app storage and copied to `Download/PhoneLab/`.
**Copy summary** puts a short text version on the clipboard for pasting into
a chat; **Share** sends the file.

No identifiers are saved: no IMEI, serial number, MAC addresses, network
names, cell IDs or coordinates.

## Build

GitHub Actions builds every push to `main` and publishes a release
(`v0.1.<run>`). A second job then runs the APK on Android 15 and Android 10 emulators,
walks through every screen with `scripts/screenshots.sh`, fails on a crash,
and uploads screenshots, the saved report and logcat as an artifact.
Locally: `./gradlew assembleRelease` with JDK 17.

The toolchain is pinned to a known-good set (AGP 8.13, Kotlin 2.2, Gradle
8.14, Compose BOM 2025.10). Builds are signed with a committed prototype key;
see `signing/README.md`.

## License

GPL-3.0. See `LICENSE`.
