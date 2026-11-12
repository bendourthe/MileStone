# Repository Overview

MileStone is a native Android app (`com.bendourthe.milestone`) that shows the points, miles, and rewards cash of the user's loyalty programs on one dashboard. It reads each balance from the program's own website inside an embedded browser (Android WebView) after the user signs in once, and keeps those sign-ins alive in the background. Nothing is entered by hand, and no server is involved: every balance, sign-in, and saved password stays on the phone.

The remote `origin` is `https://github.com/bendourthe/MileStone.git`. `main` is the release branch and `develop` is the integration branch. The version is the latest heading in the root `CHANGELOG.md`.

## Toolchain

- Kotlin, Jetpack Compose (Material 3), Gradle with a version catalog (`gradle/libs.versions.toml`).
- `compileSdk` 37, `targetSdk` 36, `minSdk` 29.
- Build types: `debug`, `release`, and `capture`. `capture` is a debug copy with the suffix `.capture` and the label "MileStone Capture"; it adds a "Capture page" action that saves a balance page for selector work. CI never builds it ([ADR-0001](../decisions/0001-login-bridge-sessions-fixtures-logging.md)).
- The `debug` build adds a "Site check" launcher entry (`app/src/debug`) for testing whether a program's site works in the embedded browser.

Local gate, run from the repository root in PowerShell:

```powershell
.\gradlew.bat -q testDebugUnitTest lintDebug assembleDebug assembleCapture compileDebugAndroidTestKotlin
```

CI (`.github/workflows/ci.yml`) runs `./gradlew testDebugUnitTest lintDebug assembleDebug compileDebugAndroidTestKotlin` on Ubuntu with Java 17.

## Module Layout

All code is in the single `app` module under `com.bendourthe.milestone`.

| Package | Responsibility |
|---|---|
| `domain` | Models (`Program`, `ExtractionSpec`, `SyncResult`, `FailureReason`, `BalanceUnit`), repository interfaces, catalog search, and the dashboard use case. No Android types. |
| `data.catalog` | `StaticCatalogRepository`: the 14 programs, their hosts, sign-in and balance URLs, and fixture-verified XPath or CSS balance selectors. Hosts come only from the feasibility record. |
| `data.db` | Room database (`loyalty_accounts`, `balance_snapshots`), schema exports in `app/schemas/`. |
| `data.session` | `TinkSessionStore`: encrypted per-program session metadata (connected and verified times), never cookies. |
| `data.credentials` | `CredentialVault`: saved sign-ins sealed with AES-256-GCM under an Android Keystore key (StrongBox when available), one file per program in `noBackupFilesDir/signins` ([ADR-0004](../decisions/0004-keep-alive-and-automatic-sign-in.md)). |
| `data.settings` | DataStore preferences (sort mode). |
| `sync` | `SyncManager`, the single writer of balances and statuses, with per-program throttle, backoff, and refresh reasons (`ON_START`, `MANUAL`, `KEEP_ALIVE`); `ForegroundSyncObserver` refreshes when the app opens. |
| `sync.webview` | The login bridge: `WebViewConfig` (hardened settings and a full-height layout, without which Chromium lays pages out with a zero viewport height and every `vh` length is 0), `HostPolicy` (exact-host allow list), `SessionProfiles` (one WebView profile and cookie store per program), `BridgeSession` (the page-state machine), `LoginBridge` (WebView glue), `HeadlessWebViewBalanceSource` (refresh without UI), and `AutoSignInSupport`. |
| `sync.extract` | `BridgeProtocol` (messages to and from the page script), `PageStateClassifier`, and `BalanceParser`. |
| `sync.mfa` | `OneTimeCodeInbox` and `SmsCodeReceiver`: texted sign-in codes held in memory for five minutes, matched by sender keywords, handed out once. |
| `sync.background` | `KeepAliveScheduler`, `KeepAliveReceiver`, and `KeepAliveService` (a refresh every 15 minutes as a short `dataSync` foreground task), `ScheduledRefreshScheduler` (an alarm at the next scheduled midnight, allowed while the phone idles), `VirtualDisplayWebViewHost` (an off-screen WebView), and the notification channels. |
| `ui` | `MainActivity`, the navigation host, the dashboard, the program catalog, settings, and the sign-in sheet (`AuthWebViewModal`, `AutoSignInDialog`). `ui.theme` holds the single dark teal color scheme and `AuroraBackground`, a slow animated gradient that stays still when system animations are off. |

`AppContainer` wires these together by hand; there is no dependency-injection framework.

## Runtime Flow

1. The user adds a program from the catalog and signs in inside `AuthWebViewModal`. The page script `app/src/main/assets/extract.js` runs only on the program's exact hosts, reads the balance element named by the catalog selector, and posts it back; `SyncManager` stores it.
2. `SyncManager` refreshes programs in a hidden WebView that reuses the program's own cookie store. The user's schedule in Settings (every day, every Sunday by default, or the last day of the month, each at midnight) sets an alarm that refreshes every balance older than that time and may sign in. Opening the app refreshes a program only when it was neither updated nor tried since that scheduled time; a failed program waits for the next scheduled time or the user's Refresh. Every 15 minutes in the background, the keep-alive reads connected programs to keep their sessions alive. Reads share two fetch slots and sign-ins run one at a time; a sign-in the user just started skips that queue.
3. When a session has ended and the user saved an automatic sign-in, the hidden browser opens the program's sign-in page directly (and skips the session read when the last one already found the session ended), and the page script fills the saved username and password, ticks "remember this device", prefers a texted code, and enters a code that arrives by text. A site that asks for the username alone first gets the username, then at most one more fill on its password page. The script submits with the form's Sign in or Continue button and never presses a show-password toggle. Passwords are typed when the user opens MileStone or at the scheduled refresh, never by the 15-minute keep-alive ([ADR-0005](../decisions/0005-sign-in-only-on-open-with-stop-reasons.md)). A sign-in counts as refused only when the site shows an error with its form; a form back without an error is unfinished and may be tried again. A refused password is never retried: automatic sign-in pauses for that program until the user taps Sign in or Refresh on its tile, which tries the saved sign-in once more and opens the site for the user if it stops again, or until a sign-in succeeds. A background refresh that needs the user posts a "needs you" notification.
4. Signing out or removing a program cancels its running refresh or sign-in, erases its WebView profile's cookies and storage at once, and deletes its saved sign-in; a profile still held by a live WebView is deleted at the next app start.
5. Add program opens on programs not added yet, with a filter for Added and All programs; added programs carry a green outline. Programs whose sites do not work in the embedded browser cannot be added, and tapping one says so. Pulling the dashboard down asks "Refresh all programs?" before refreshing everything, since a full refresh can take minutes; the top refresh button refreshes at once. An added program's menu offers Refresh, Sign out, and Remove; "Open in app or browser" was removed ([ADR-0002](../decisions/0002-open-in-app-or-browser.md), [ADR-0003](../decisions/0003-sync-only-catalog-and-reconnect.md), [ADR-0007](../decisions/0007-catalog-filter-and-added-outline.md)).

## Privacy Rules

- Logs name programs and hosts only, never full URLs, page text, balances, cookies, passwords, or codes. `DiagnosticLog` also appends these lines to `no_backup/diagnostics.log` (capped at 512 KB), so a stop can be diagnosed after the phone's log buffer rotates; on a debug build, read it with `adb shell run-as com.bendourthe.milestone cat no_backup/diagnostics.log`.
- Real captured pages live only in the gitignored `app/src/test/resources/fixtures-private/`; committed fixtures in `app/src/test/resources/fixtures/` are hand-written with fake balances.
- Cloud backup and device transfer exclude every app data domain (`res/xml/backup_rules.xml`, `res/xml/data_extraction_rules.xml`), and the vault sits in `noBackupFilesDir`, which Android never backs up.

## Tests

- Unit tests (`app/src/test`, Robolectric where Android types are needed) cover the catalog, Room and its migration, the vault, the code inbox, the bridge state machine, `SyncManager`, and `ExtractorFixtureTest`, which runs every selector against its synthetic fixture and, locally, its private capture.
- Instrumented tests (`app/src/androidTest`) cover WebView configuration and headless sync against local fixtures. Running them uninstalls the app, which clears every sign-in and saved password.
