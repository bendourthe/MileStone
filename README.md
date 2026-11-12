# MileStone

MileStone is a native Android app (Kotlin, Jetpack Compose, Material 3) that shows credit card, airline, and hotel loyalty balances on one dashboard. It is installed on the user's own phone as a signed APK; there is no Play Store listing and no server.

## What's in v0.2.0

- A Benefits tab for American Express: one tile per card on the account, each with its next open credit and when it was last read. Each card opens a page with its spend progress, credits, completed benefits, and, for Platinum-type cards, the full benefits list with enrollment status. Cards are kept in step after every Amex refresh except the 15-minute keep-alive, and a card is identified on the phone by its last digits only.
- Benefit reminders: one private notification when a card credit's period ends soon with money left; the lock screen shows no card details. A Settings switch turns them off.

From v0.1.0:

- One dashboard for 14 catalog programs (cards, airlines, hotels). Programs whose sign-in is tested sync; the rest are listed and say they cannot sign in from MileStone yet.
- Each balance is read from the program's own site in a hidden WebView after a one-time sign-in. A saved sign-in, sealed by the Android Keystore and kept only on the phone, signs in again when a session ends; a refused password is never retried by itself.
- Refreshes run at midnight on a chosen schedule (daily, Sunday, or month end), a 15-minute background keep-alive reuses sessions without typing passwords, a tile's Refresh refreshes one program, and pulling the dashboard down asks before refreshing everything.
- Sorting by category, type, name, or a custom drag order; an animated opening screen.

## Prerequisites

- Android Studio Rabbit 2 (2026.2.2 Patch 1) or newer
- JDK 17 or newer
- Android SDK Platform 36, with `ANDROID_HOME` pointing at the SDK (default `%LOCALAPPDATA%\Android\Sdk`)

## Build

```powershell
git clone https://github.com/bendourthe/MileStone.git
cd MileStone
.\gradlew.bat assembleDebug
```

The debug APK is written to `app\build\outputs\apk\debug\app-debug.apk`. Run the unit tests and lint with `.\gradlew.bat testDebugUnitTest lintDebug`.

## Install on a phone

Enable Developer options and USB debugging on the phone, connect it, accept the RSA prompt, then run:

```powershell
adb install -r app\build\outputs\apk\release\app-release.apk
```

## Release signing

`.\gradlew.bat assembleRelease` needs a signing key and fails with "Release signing is not configured: see README Release signing" until one is set up. Create a keystore outside the repository; `keytool` prompts for both passwords so they never reach PowerShell history:

```powershell
New-Item -ItemType Directory -Force $env:USERPROFILE\keys
keytool -genkeypair -v -keystore $env:USERPROFILE\keys\milestone-release.jks -alias milestone -keyalg RSA -keysize 4096 -validity 10000
```

Then create `keystore.properties` at the repository root (it is gitignored):

```properties
storeFile=C:/Users/<you>/keys/milestone-release.jks
storePassword=<store password>
keyAlias=milestone
keyPassword=<key password>
```

The build also accepts `MILESTONE_KEYSTORE_PATH`, `MILESTONE_KEYSTORE_PASSWORD`, `MILESTONE_KEY_ALIAS`, and `MILESTONE_KEY_PASSWORD` environment variables when `keystore.properties` is absent; the file is preferred over persistent environment variables. Keep an offline backup of the keystore: an APK signed with a different key cannot update an installed one. CI never signs builds.

## Page capture runbook

Selector work for a program needs a copy of its real balance page. Captures hold account data, so they stay on the developer's PC in the gitignored `app/src/test/resources/fixtures-private/` folder and never enter Git. Committed fixtures are synthetic.

1. Build and install the capture build, which installs beside the normal app as `com.bendourthe.milestone.capture`:

   ```powershell
   .\gradlew.bat assembleCapture
   adb install -r app\build\outputs\apk\capture\app-capture.apk
   ```

2. On the phone, open the capture build, sign in to the program once, wait for the balance page, and tap "Capture page" in the sign-in screen's top bar.
3. List the saved captures, then copy one to the PC. `cmd /c` keeps the HTML bytes unchanged, which PowerShell redirection does not:

   ```powershell
   adb shell run-as com.bendourthe.milestone.capture ls files/captures
   New-Item -ItemType Directory -Force app\src\test\resources\fixtures-private
   cmd /c "adb exec-out run-as com.bendourthe.milestone.capture cat files/captures/REPLACE.html > app\src\test\resources\fixtures-private\REPLACE.html"
   ```

   Replace `REPLACE.html` with a file name from the list.
4. Remove the capture build, which also deletes its sessions and captures. The capture build is debuggable, so anyone with USB access could read its sessions while it is installed:

   ```powershell
   adb uninstall com.bendourthe.milestone.capture
   ```

   Expected output: `Success`.

## Version

The current version is v0.2.0. It is recorded as the `0.2.0` heading in `CHANGELOG.md` and as `versionName = "0.2.0"` (`versionCode = 2`) in `app/build.gradle.kts`. Releases are tagged `vX.Y.Z` on `main`.

## Layout

| Path | Purpose |
|---|---|
| `branding/MileStone-icon.jpg` | Source artwork for the app logo and launcher icon: a stilt cabin under palm trees, a slate mountain, a navy airplane over a gold path, and gold stars on a dark navy field |
| `branding/split_mark.py` | Splits the in-app mark into the layers the opening animation builds |
| `app/` | The Android app module: Kotlin sources, resources, unit tests, and on-device tests |
| `.github/workflows/ci.yml` | Pull-request CI: unit tests, lint, debug build, schema drift and private-fixture guards, secret scan |
| `README.md` | This file |
| `CHANGELOG.md` | Version history in Keep a Changelog form |
| `CLAUDE.md` | Short project notes for coding sessions |
| `docs/DEVLOG.md` | Release index, one line per shipped release |
| `docs/README.md` | Map of the documentation tree |
| `docs/todos.md` | Living progress tracker |
| `docs/handbooks/` | Living technical overview |
| `docs/decisions/` | Architecture decision records, added when a decision is made |
| `docs/releases/v0/v0.2/` | Active docs for version 0.2 (`plans/`, `comparisons/`, `known-gaps.md`) |
| `docs/releases/v0/v0.1/` | Closed v0.1 records kept active: release evidence, session histories, closed known gaps |
| `docs/archives/v0/v0.1/` | Archived v0.1 plan and comparisons |

## Version control

This directory is a git repository. `main` is the release branch. `develop` is the integration branch. The remote `origin` is `https://github.com/bendourthe/MileStone.git`.

Work branches use these prefixes and merge through `develop` into `main`: `feat/`, `fix/`, `refactor/`, `ci/`, `docs/`, `chore/`, `test/`.
