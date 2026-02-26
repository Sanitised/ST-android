# ST-android

SillyTavern runner for Android. Works on device with zero setup. Supports Android 8.0+ and arm64.

<img src="pics/ST-android-app-icon-original.svg" alt="App icon" width="120">

<img src="pics/ST-android-screenshot.png" alt="Screenshot" width="300">

This is a personal project and is not affiliated with or endorsed by SillyTavern.
It is intended primarily for basic on-device chatting; advanced workflows (for example, extensions) may not work.

## Privacy

- No telemetry of any kind.
- Unlike Termux, app works in Private Space/Secure Folder/Secondary profiles.
- Minimal network calls: opt-in Github release checks, npm installs for custom ST versions. All other traffic comes from SillyTavern itself.
- All chats, characters, settings stay local unless you decide to export them manually and share with others.
- Bundles SillyTavern source code without modifications.
- Bundles Node.js with minimal patches required to run on Android.
- Release APKs are built in the pipeline and published automatically through immutable releases.

## Importing data from SillyTavern on Termux/PC

The app accepts `.tar.gz`, `.tar`, and `.zip` archives. The format is detected automatically.

### Quick export (Termux or Linux)

Run this one-liner:

```bash
bash <(curl -sSf https://raw.githubusercontent.com/Sanitised/ST-android/master/tools/export_to_st_android.sh)
```
If your SillyTavern folder is not in a standard location, first do `cd ./my-sillytavern`.

### Manual export

The archive must have this structure:

```
st_backup/
├── config.yaml
└── data/
```

```bash
mkdir st_backup
cp /path/to/sillytavern/config.yaml st_backup/
cp -r /path/to/sillytavern/data st_backup/
tar -czf st_backup.tar.gz st_backup/
```

On Termux, copy the archive to Downloads so the app can reach it:

```bash
termux-setup-storage   # one-time permission grant
cp st_backup.tar.gz ~/storage/downloads/
```

Then stop the server in the app and use **Import Data**.

## Changelog

See [CHANGELOG.md](CHANGELOG.md).

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
