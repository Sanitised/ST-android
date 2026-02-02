# ST-android

Lightweight Android app that launches SillyTavern server on-device.

Functionality is minimal: start/stop, view logs, edit config, and export/import user data.

This is a personal project and is not affiliated with or endorsed by SillyTavern.
It is intended primarily for basic on-device chatting; advanced workflows (for example, some extensions) may not work.

Requires Android 8.0+ (API 26) and arm64 devices.

## Privacy

- The app makes no network requests beyond what SillyTavern itself does.
- No telemetry of any kind.
- Unlike Termux, app works in Private Space/Secure Folder/Secondary profiles.
- All chats, characters, settings stay local unless you decide to export them manually and share with others.
- Bundles SillyTavern source code without modifications.
- Bundles Node.js with only the minimal patches required to run on Android.
- The codebase is intentionally small and easy to review manually or with a coding agent.

APKs are built in the pipeline and published automatically.

## Changelog

See `CHANGELOG.md`.

## Build (Docker)

Prereqs: Docker installed (plus Git for cloning the repo). Tested only on Linux.

```bash
git clone <repo>
cd ST-android
git submodule update --init --recursive
./ci/scripts/build_apk_docker.sh
```

Output:
- `app/build/outputs/apk/debug/app-debug.apk`
