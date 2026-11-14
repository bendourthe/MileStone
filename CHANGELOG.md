# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [0.3.0] - 2026-11-13

### Fixed

- A newly visible password refusal is classified at the existing sign-in page deadline instead of being lost as an unfinished sign-in.
- Benefits pages wait for their own content, and a card switch waits for the new card's trackers, within one 2-minute benefits-read deadline. Rewards follows an available activity link at its readiness cap and waits within the page budget for a late lower section.
- Hilton Surpass and Gold Delta SkyMiles read their full benefits list from the View all benefits page. A refresh that reaches only part of a card's pages keeps what the other pages stored.

### Added

- Matching offline card images for Platinum, Business Platinum, Hilton Surpass, and Delta Gold, with an unknown-card fallback.
- Benefits organized by program and card, with Welcome bonus, Credits, Spend trackers, and Other categories, counted independently collapsible headers, and saved per-card choices.
- Yellow alert counts and top-of-card alerts with individual saved acknowledgement; a successful relevant section refresh can return an applicable alert.
- Optional local welcome-bonus spend, target, deadline, and reward setup with bounded field-specific validation.
- Offline product handbook with reading and presentation views, retained build sources, and a CI freshness check.
- Count-only page, card, and total-time diagnostics for benefits reads.
- The capture build keeps its window open after a read and shows a shortened page path. The capture runbook uses sanitization and structure-only inspection, and Python caches are ignored.

### Changed

- Benefits sort alphabetically by default, offer saved completed-last ordering, and retain completed styling within the appropriate category.
- Rewards programs with benefits open their Benefits cards when tapped.
- Matching listed benefits enrich a single tracker display with description and enrollment while raw issuer rows and ambiguous matches remain intact.
- Listed-benefits freshness uses saved section outcomes bound to an atomic Room read generation; the additive schema migration preserves existing account, benefit, enrollment, and reminder records.

## [0.2.0] - 2026-11-12

Adds a Benefits tab for American Express cards and reminders before a card credit expires. Everything stays on the phone.

### Added

- Benefits tab between Rewards and Settings: one tile per American Express card with its last digits, next open credit, completed count, and freshness, plus a "Not updated since" note when a card's last read failed or is more than 8 days old.
- Card pages grouped like the Amex site: spend progress, credits, completed, other, and, for Platinum-type cards, the full benefits list with "Enrolled" or "Enrollment required".
- A separate benefits read after each saved Amex balance (never the 15-minute keep-alive). It visits every card by pressing the site's card switcher, never signs in, keeps a card whose page failed, and removes a card only when a complete card list no longer shows it (ADR-0008).
- Benefit reminders: one private notification per credit and period when money is left and the period ends within 7, 14, or 30 days by its length; never from benefits older than 8 days; a Settings switch, on by default, and a banner when notifications are not allowed.
- Room schema 3 with `cards`, `benefits`, and `benefit_reminders` tables, auto-migrated from 2 without changing accounts or balances.
- Capture tools for the developer PC: `sanitize_capture.py` strips identifiers while copying a page, and `inspect_capture.py` prints structure with text masked.

### Security

- A card is stored by its last digits only. The page script strips grouped digit runs, identifier-like tokens, and account-key-shaped runs before posting, never reads input values, cookies, storage, or link query strings, and its benefit messages are accepted only from the two Amex pages.
- This release changes no opt-in capability: Benefit reminders are on by default and need the system notification permission.

### Notes

- Known gaps G25 to G33 were found in this release; G24 is fixed. See `docs/releases/v0/v0.2/known-gaps.md`.

## [0.1.0] - 2026-11-11

First release: a native Android app that shows loyalty-program balances on one dashboard, with no server and nothing leaving the phone.

### Added

- Catalog of 14 programs (credit cards, airlines, hotels) with fixture-verified balance selectors for every program that syncs; programs not tested yet are listed but cannot be added.
- Login bridge: each program's own site in a WebView with exact-host rules, a per-program profile and cookie store, and a page script that only reads the balance and fills fixed sign-in fields.
- Automatic sign-in with a saved sign-in sealed by the Android Keystore (StrongBox when available), texted-code entry, and "remember this device" handling; a refused password pauses automatic sign-in until the user taps Refresh.
- Refresh rules: a midnight schedule (daily, Sunday by default, or month end), a 15-minute background keep-alive that never types a password and never changes a tile on a timeout, per-tile Refresh, and a pull-down refresh that asks first.
- Dashboard with category, type, alphabetical, and custom drag order; Add program with a filter and added-program outlines; Settings; an animated opening screen and a dark teal theme.
- Room database with migrations, encrypted session metadata, a private diagnostics file that names programs and hosts only, and no cloud backup or device transfer.
- Unit, Robolectric, and instrumented tests; GitHub Actions CI with one aggregate required check, a schema drift check, a private-fixture guard, and a secret scan.
- Docs: the v0.1.0 plan, decision records ADR-0001 to ADR-0007, the handbook, known gaps, and release evidence.

### Notes

- This release changes no opt-in capability, installer flag, or host surface beyond the app's own runtime permissions (texts, notifications), which the app requests in context.
- Known gaps carried to v0.2 are listed in `docs/releases/v0/v0.1/known-gaps.md`.
