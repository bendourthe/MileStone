# MileStone: Progress Dashboard

**Branch:** `develop` (v0.1.0 shipped and closed)

**Version:** v0.1.0 (recorded in `CHANGELOG.md`)

---

## Scores

| Metric | Current | Target | Delta |
|--------|---------|--------|-------|
| Governance files present | yes | yes | 0 |
| Commits on the feature branch | 75 | one per phase plus fixes | met |
| Integration branch `develop` | present | present | 0 |
| v0.1.0 plan phases complete | 10 of 10 | 10 of 10 | 0 (shipped, tag v0.1.0) |
| Application code | debug APK on a phone | sideloadable APK | release build pending |

## Phase 0: Governance baseline

- [x] Add README, changelog, development log index, docs map, handbook overview, and decisions folder
- [x] Add `docs/releases/v0/v0.1/plans/` and `docs/releases/v0/v0.1/comparisons/`
- [x] Confirm and create the first commit on `main`
- [x] Confirm and create `develop` from that commit
- [x] Record the branch model in `CLAUDE.md` after `develop` exists

## Phase 1: Product

- [x] State what MileStone does, in one sentence, before any application scaffold
- [x] Move the app icon to `branding/MileStone-icon.jpg`
- [x] Write, review, and approve the v0.1.0 plan: `docs/archives/v0/v0.1/plans/v0.1.0-milestone-android-app.md`

## Phase 2: v0.1.0 implementation

- [x] Phase 1: Toolchain, scaffold, and installable APK
- [x] Phase 2: Site feasibility test (three sessions over 24 hours; restarted after a test uninstall cleared the Session 1 sign-ins)
- [x] Phase 3: Domain and data layer
- [x] Phase 4: Manual-entry app
- [x] Phase 5: Login bridge and session profiles
- [x] Phase 6: Sync engine and foreground refresh
- [x] Phase 7: Extractors for credit cards
- [x] Phase 8: Extractors for airlines
- [x] Phase 9: Extractors for hotels
- [x] Phase 10: Architecture refactor, known gaps, CI/CD, and publication

## Plan deltas to record in phase session histories

- [ ] Phase 2: `X-Requested-With` not cleared; `WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST` is a restricted API in the current `androidx.webkit` and fails lint `RestrictedApi`. Confirm on a device that the header is absent.
- [x] Phase 2: Site check gained "Paste link" for emailed sign-in links (Hyatt).
- [x] Phase 2: no `connectedDebugAndroidTest` run until Session 3 ends, because the run uninstalls the app and clears WebView sign-ins.
- [x] Phase 3: Alaska is named "Alaska Atmos Rewards" (the site's current name), not "Alaska Mileage Plan".
- [x] Phase 5: ADR-0002 adds "Open in app or browser" on each tile (hybrid sign-in).
- [x] Phase 5: `LoginBridge` flushes the program profile's cookie manager (not the default profile's) on every page finish, on extraction, and on destroy, so per-program sign-ins reach disk before the process dies. If a restart test still loses a session, re-stamping session cookies with an expiry is the next step and needs an ADR-0001 amendment.
- [x] Phase 5: `WebViewConfig` accepts third-party cookies on each program's own profile cookie manager, so device-recognition cookies set by anti-fraud services persist and "remember this device" can stick. Covered by the on-device `WebViewConfigTest` (Robolectric's shadow does not record the setting).
- [x] Phase 6: `WebViewHost.withAttachedWebView` takes no `profileName`; `LoginBridge` assigns the profile itself.
- [x] Phase 6: the headless WebView is screen-sized, invisible, behind the app content, and swallows touches, because Chase renders dashboard tiles only inside a real viewport (a 1x1 WebView never showed the balance).
- [x] Phase 6: `extract.js` searches same-origin frames and polls them every second, so a session whose sign-in box sits in a frame reports `LoginRequired` instead of `timeout`.
- [x] Phase 6 ([ADR-0004](decisions/0004-keep-alive-and-automatic-sign-in.md)): background keep-alive every 15 min (idle-time alarm, short `dataSync` foreground task, private virtual display), saved sign-ins sealed with a StrongBox or Keystore AES-256-GCM key, one automatic sign-in per expiry, texted codes read in memory, "remember this device" ticked automatically, and a "needs you" notification. Unit tests cover the vault, the code reader, the sign-in session decisions, and the reconnect eligibility and budget.
- [x] Phase 6: `extract.js` waits 1.5 s for a sign-in form to settle and 0.5 s between filling and submitting; a form that stays on the same page counts as refused only after 15 s (the first 4 s limit stopped a working Amex sign-in).
- [x] Phase 6: Add program and Reconnect sign in out of sight (`SyncManager.connect`, the same hidden WebView as a refresh); a hidden sign-in adds the program's tile whatever the outcome (balance, Reconnect, or error), and a page that needs the user (`NeedsAttention`) opens the site once. The capture build always shows the site. Aeroplan's dashboard sign-in button goes to `akamai-gw.dbaas.aircanada.com`, now allowed.
- [x] Phase 6 ([ADR-0005](decisions/0005-sign-in-only-on-open-with-stop-reasons.md)): the keep-alive only reuses sessions and never types a password; opening MileStone afresh (first start, or back after 15 min or more) refreshes and signs in, ignoring backoff, then lists what did not reconnect and why. A stopped sign-in records `Password refused` (blocks at once), `Texted code needed`, or `Sign-in unfinished` (blocks on the second in a row). Aeroplan's hidden sign-in cannot reach its form (G14).
- [x] Phase 4: logos come from supplied image files, not from Commons or press-kit URLs; source URLs unknown. Bilt and British Airways keep monograms. Logo badges are rounded squares; Amex and Atmos carry their own background and fill the badge edge to edge.
- [x] Phase 4: `MainActivity` is locked to portrait. Android 16 ignores this on screens 600 dp wide or more, so unfolded foldables still rotate.
- [x] Phase 4: a third sort, "By type", groups tiles under "Points and miles" and "Cash back" headings (`BalanceUnit.typeLabel`), alphabetical within each group. Sort labels live on `SortMode.label` so the dashboard menu and Settings list the same options. The list scrolls to the top when the sort changes so the first heading is visible.
- [x] Phase 4: the dashboard sort mode (DataStore) and custom order (Room `sortOrder`) survive a force-stop and relaunch.
- [x] Phase 4: Add program shows every syncable program as a thumbnail card, two per row, grouped by category with popular programs first; tapping a card goes straight to sign-in.
- [x] Phase 4: the Dashboard tab is now "Rewards"; it sorts by category by default under the Add program headings (Credit cards, Airlines, Hotels), with By type, Alphabetical, and Custom still in the sort menu. A new program's tile appears as soon as its hidden sign-in starts and reads "Signing in...".
- [x] Phase 1: launcher icon fitted to 64% of the adaptive-icon canvas on a white background (`ic_launcher_background` #FFFFFF), with a monochrome layer from its non-white pixels.
- [x] Phase 7: the capture build keeps `AuthWebViewModal` open after a failed read so the dashboard can be captured before the 20 s extraction timeout closes it; the capture build is labeled "MileStone Capture" (`app/src/capture/res/values/strings.xml`); "Capture page" shows a "Page captured" or "Capture failed" toast.
- [x] Phase 7: Amex and Chase use XPath selectors (Chase reads the `mds-list-item` `label` attribute, so `extract.js` also observes attribute mutations); `ExtractorFixtureTest` compares through Jsoup `W3CDom` plus `javax.xml.xpath` to mirror `document.evaluate`. Per [ADR-0003](decisions/0003-sync-only-catalog-and-reconnect.md): Capital One Rewards is shown as cash in dollars (`BalanceUnit.CASH_CENTS`, `BalanceParser.parseCents`); Delta is read only from Delta's site, not through Amex; manual entry is removed and the catalog lists only syncable programs; expired sessions use the one-tap Reconnect chip with Android autofill.
- [x] Phase 7 (pulled forward from Phase 10): the manual data layer is removed. Room schema version 2 auto-migrates from 1: it drops `lastManualEditEpochMs`, clears manual balances and their snapshots, and renames `MANUAL_ONLY` to `UNSUPPORTED`. `applySyncResult` loses `fetchStartedEpochMs` and `interactive`, which only guarded against manual-edit races. `MigrationTest` covers it under Robolectric, with the schemas added to debug assets.
- [x] Phase 7: Chase's code page shares the dashboard URL, so the 20 s timeout closed the sheet at the texted-code step. `extract.js` now reports `awaitingInput` (whether a visible text, tel, number, email, or password field exists; never values), searches open shadow roots, and counts `mds-text-input`, `mds-password-input`, and `autocomplete="one-time-code"` as entry fields (the deep password scan is throttled to every 500 ms). Interactive sessions never close on a timeout: they re-arm on login, dashboard, and unknown pages (after the one dashboard redirect) and end only on a balance, a sign-in failure, or Close.
- [x] Phase 8: Alaska reads `.loyalty-info-row .available-points .display-md` and Aeroplan reads the signed-in account menu's points label; each matched exactly one parseable number in a capture, which also holds a second, different points figure the selector must skip. Synthetic fixtures carry 1- and 4-digit balances next to decoys. The private runs skip the value comparison because no `.expected` values are filled in.
- [x] Phase 9: "Paste sign-in link" in `AuthWebViewModal` built ahead of the hotel extractors.

## Next minor (v0.2) candidates

- [ ] Benefits tab: the next plan, in `docs/releases/v0/v0.2/plans/`; Amex first, one tile per card, cards added and removed on refresh, a per-card page of benefits and their progress; card name and last digits stored on the device only.
