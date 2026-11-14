# MileStone: Progress Dashboard

**Branch:** `develop` is integration; the active candidate is `feat/v0.3.1-benefits-redesign` in its linked worktree.

**Version:** v0.3.0 is published and back-merged; v0.3.1 Benefits redesign remains a local candidate awaiting final delivery.

## Expanded Completion Goal

Owner scope update, 2026-11-13: complete the existing v0.1/v0.2/v0.3 integration, release, canonical archive, and authorized cleanup obligations AND deliver the complete Benefits redesign. The six interface requirements below are required work, superseding their earlier deferral. The overall goal remains unfinished until the redesign is implemented, installed without erasing sign-ins, and verified on the phone; extraction correctness or v0.3.0 PLAN COMPLETE alone cannot close it. Original count-confirmation, privacy, test-device, and publication requirements remain in force. The native goal API cannot edit an unfinished objective; this tracker and the owner's scope instruction retain the expanded completion criteria without falsely marking the old goal complete.

Implementation plan: [v0.3.1 Benefits redesign](releases/v0/v0.3/plans/v0.3.1-benefits-redesign.md). This is the proposed follow-up slot in the active minor; its release authority must cover the new version before publication. The owner confirmed the extraction minimums; redesign delivery remains separately required.

---

## Global Search Scope Expansion

Owner authorization, 2026-11-13: add implementation and delivery of v0.4.0 global search to every existing completion obligation. The finalized plan is docs/releases/v0/v0.4/plans/v0.4.0-global-search.md, merged through PR 9 at 532ce1f317d9649f81298a7f616be1ba8378866f and verified as an ancestor of origin/develop. This new instruction authorizes reversible local implementation after the integrated v0.3.1 Benefits presentation dependency is verified. It supersedes the plan's historical planning-only authority boundary for local implementation; separately required remote, release and destructive authority stays pending.

- [ ] Verify integrated v0.3.1 Benefits presentation before search implementation.
- [ ] Implement the finalized four-phase search plan: top Search icon, added programs and stored cards/displayed benefits only, results grouped by program, contextual matching with an item-token match, exact navigation, preserved Back/scroll state and on-device operation without changing issuer data or sign-ins.
- [ ] Complete search verification, owner phone delivery, separately authorized publication/release and the plan's native completion requirements.
- [ ] Finish all original v0.1/v0.2/v0.3 release, archive and authorized cleanup duties as well as search delivery before declaring the expanded goal complete.

The supported goal API can create a goal or change its status, but cannot edit an active objective. The existing goal stays active; this durable tracker records the expansion without falsely completing or replacing it.

## Scores

| Metric | Current | Target | Delta |
|--------|---------|--------|-------|
| Governance files present | yes | yes | 0 |
| Unpublished commits ahead of origin/develop | 4 plus owner refinements in progress | publish only after final gates | local only |
| Integration branch `develop` | present | present | 0 |
| v0.1.0 plan phases complete | 10 of 10 | 10 of 10 | 0 (shipped, tag v0.1.0) |
| v0.2.0 plan phases complete | 6 of 6 | 6 of 6 | 0 (shipped, tag v0.2.0) |
| v0.3.0 plan phases complete | 4 of 4 | 4 of 4 | release published; minor archive/cleanup remain |
| v0.3.1 redesign phases complete | 3 of 4 | 4 of 4 | 1 (386 JVM cases and 41 device cases pass; owner accepted; integration/release gates open) |
| Required Benefits redesign outcomes verified | Owner accepted baseline images and alerts; refinements verified synthetically | All six on phone | Institution/polish candidate installed with -r; tested/installed APK hashes match; owner accepted all final checks |
| v0.4.0 search plan phases complete | 0 of 4 | 4 of 4 | 4 (planned; owner confirmed, implementation not started) |
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

## Phase 3: v0.2.0 implementation (Benefits tab for Amex cards)

- [x] Phase 1: Cards, benefits, and reminder storage (Room schema 3)
- [x] Phase 2: Capture session, ADR-0008, and the benefits read mode in the page script
- [x] Phase 3: Benefits read after an Amex refresh
- [x] Phase 4: Benefits tab and card pages
- [x] Phase 5: Benefit reminders
- [x] Phase 6: Architecture refactor, known gaps, CI/CD, and publication

## Phase 4: v0.3.0 implementation (Amex benefits extraction fix)

Plan: `docs/releases/v0/v0.3/plans/v0.3.0-amex-benefits-extraction-fix.md`.

- [x] Phase 1: Diagnostics and missing captures (owner capture session)
- [x] Phase 2: Page-ready timing and switch rule (T007 follow-up is tracked in Phase 4)
- [x] Phase 3: View-all list and section-scoped storage (Should)
- [x] Phase 4: Architecture refactor, known gaps, CI/CD, and publication
- [x] Phase 4 T007 follow-up: cap correction and 3 foreground live refreshes pass, with all card/category minimums in 75.97, 72.92, and 71.61 s.
- [x] Phase 4 local gate: 356 JVM cases with no failures, 35/35 emulator tests, 8/8 private benefits cases, lint/build/coverage, and 280 handbook browser checks plus native handbook check. G44 and G45 are Fixed; G46 remains a historical warning.
- [ ] Remaining gates: v0.3.0 release, redesigned phone acceptance and version-authorized publication/release, v0.3 archive, and cleanup. The owner confirmed the site minimums; extraction PR #6 is green and merged.
- [x] v0.1/v0.2 audit: both tags/releases and green implementation PRs verified; v0.2 already fully archived.
- [x] v0.1 local archive reconciliation: native whole-tree move and link repair from chore/close-v0.1 combined into the feature candidate; publication to develop completed through PR #6 (merge cbd82d1).

## Required Benefits Redesign

- [ ] Alerts: yellow warning badges with active counts; distinct alerts at the top of the opened card; individual acknowledgement; applicable alerts can return after a successful relevant refresh.
- [ ] Card images: matching offline artwork for all four known Amex products on tiles and card pages, with an unknown-card fallback.
- [ ] Organization: program, then card; Welcome bonus, Credits, Spend trackers, Other; preserve progress, enrollment, descriptions, periods, and completed styling.
- [ ] Sorting: alphabetical within categories, with a completed-items-last option and persisted user choice.
- [ ] Collapsing: independently expandable counted categories, Other initially collapsed, and persisted card-specific choices.
- [ ] Redundancy removal: display a listed benefit and its matching tracker once on the same card, retaining useful enrollment/description data without deleting stored issuer rows or merging distinct periods.
- [ ] Related Benefits requirements: benefit/alert counts, Rewards-to-Benefits program navigation (G36), per-list freshness (G43), a generic empty message, and local manual welcome-bonus setup.
- [ ] Delivery: exercise every interaction on a runnable synthetic screen, install with -r, obtain phone verification, and finish authorized integration/release/archive duties before closing the expanded goal.

## Search Planning

- [x] Review and finalize the [v0.4.0 global search plan](releases/v0/v0.4/plans/v0.4.0-global-search.md) (G37): owner confirmed the top search icon, added-programs-only corpus, contextual queries, and four-phase breakdown on 2026-11-13; all seven review lenses ran and their findings are resolved.
- [ ] Implement the four-phase global search plan after its verified v0.3.1 presentation dependency is integrated; planning confirmation does not authorize implementation.

## Separate Refresh Work

- [ ] Per-scope refresh (Rewards tile, program, card, global with a per-tab choice) on one coordinated queue remains a separate scope. Preserve the existing background-continuation and automatic-retry gaps; the Benefits display redesign does not silently claim them fixed.

## Next minor (v0.2) candidates

- [x] Benefits tab: planned in `docs/releases/v0/v0.2/plans/v0.2.0-benefits-tab-amex.md`; Amex first, one tile per card, cards added and removed on refresh, a per-card page of benefits and their progress; card name and last digits stored on the device only.

## Current Delivery Gate

All six Benefits redesign requirements are implemented locally. Final gate: 375 JVM cases (0 failures/errors, 10 skips), all 8 Benefits fixture cases without skips, 38/38 emulator cases, lint with 0 errors, debug/capture builds and coverage report passed. The full native handbook gate and 280 browser checks pass. Commits be40bcf, 93a08fb and 9ef6c40 retain the three implementation phases; final evidence/documentation remains in the linked worktree.

The owner reconnected/unlocked the phone after an initial unavailable-device attempt. adb install -r succeeded, and the installed APK hash matches the tested build. The owner confirmed the baseline images, alerts, categories, welcome entry and acknowledgements; requested refinements still need a new installed build. The owner confirmed all Amex-site minimums. Extraction publication and v0.1 reconciliation integrated through green PR #6 on 2026-11-13; releases, redesign publication, v0.3 archive and final cleanup remain open; v0.2 is already released and fully archived. No push/merge/tag/release or branch/worktree deletion occurred in this continuation.

## Owner UI Refinement Request

The owner confirmed the installed images, alerts, welcome entry, categories, acknowledgements and completed-last control, and confirmed the Amex-site minimums. Required refinements are clear category Show/Hide controls, top-right filter placement, explicit sort choices, grouped amount input, automatic date hyphens and a calendar picker. These are captured in v0.3.1 T023-T027; affected final gates are reopened. Numeric site confirmation is no longer a blocker. Earlier 375/38 and handbook results remain historical checkpoint evidence, not proof of the new controls.


## Cleanup Status

The native approved cleanup executor removed the merged local and remote chore/close-v0.2 branches and retained 16 protected or active items. The original v0.3.0 checker now has only cleanup.branches unmet; cleanup.merged is met. Retain recently active extraction/release branches and the active redesign worktree. The primary checkout is clean at d43d344, including the independent Search plan. Phone delivery of the current institution APK and separate redesign publication/release authority remain open.


The owner reconnected the phone; the current institution/polish APK is installed with its tested hash verified. Final phone acceptance remains pending.


Final phone acceptance is confirmed: the owner replied Yes, all checks pass for institution/card Back, retained four-card content, category outlines and welcome-bonus formatting. Local delivery gates are satisfied; current develop integration, separately authorized publication/release, archive and cleanup remain open.
