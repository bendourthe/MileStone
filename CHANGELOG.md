# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

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
