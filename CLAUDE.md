# MileStone

MileStone is a native Android app (Kotlin, Jetpack Compose, Gradle) that reads the user's loyalty-program balances from each program's own website in an embedded WebView and keeps the sign-ins alive on the phone. Architecture, runtime flow, and privacy rules: `docs/handbooks/overview.md`.

## Commands

Run from the repository root in PowerShell:

- Local gate: `.\gradlew.bat -q testDebugUnitTest lintDebug assembleDebug assembleCapture compileDebugAndroidTestKotlin`
- Unit-test coverage: `.\gradlew.bat createDebugUnitTestCoverageReport` (report in `app\build\reports\coverage\test\debug\`)
- On-device tests: `.\gradlew.bat connectedDebugAndroidTest` (uninstalls the app, clearing every sign-in and saved password)

## Privacy

Logs name programs and hosts only. Real captured pages go only in the gitignored `app/src/test/resources/fixtures-private/`. Never read, print, or commit balances, credentials, codes, or cookies.

## Version

v0.1.0, recorded as the `0.1.0` heading in `CHANGELOG.md`.

## Branch model

develop+main. `main` is the release branch. `develop` is the integration branch. Branches named `feat/`, `fix/`, `refactor/`, `ci/`, `docs/`, `chore/`, and `test/` merge through `develop`.

## Docs

Living docs stay in `docs/handbooks/`, `docs/decisions/`, `docs/README.md`, and `docs/todos.md`. Release work for v0.2 goes in `docs/releases/v0/v0.2/`; v0.1 is closed and its plan is archived in `docs/archives/v0/v0.1/`.
