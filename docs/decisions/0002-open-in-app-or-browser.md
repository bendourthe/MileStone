# ADR-0002: Embedded Sign-In for Sync, Program App or Browser for Viewing

- **Status**: Accepted
- **Date**: 2026-11-07
- **Template**: MADR
- **Origin**: decision after the first Amex feasibility session
- **Supersedes**: -
- **Superseded by**: -

## Context and Problem Statement

In the first feasibility session, Amex treated MileStone's embedded WebView as a new device and asked the user to approve it in the Amex app. The proposed alternative was sign-in through the phone's default browser or the program's own app, because both already know the device.

Android isolates apps from one another. MileStone cannot read pages shown in the default browser, in a Chrome Custom Tab, or in another app, so none of those can supply a balance. Only a WebView that MileStone owns can be read, and that WebView is a separate browser with its own cookies.

## Decision Drivers

- Reading balances automatically is the app's main purpose.
- The user wants the program's own app or familiar browser available for looking at an account.
- No new hosts, package names, or credentials may be invented or stored.

## Considered Options

1. **Embedded WebView for sync plus an "Open in app or browser" action on every tile (chosen).** Pros: automatic reading stays; the user can jump to the program's app or default browser in one tap. Cons: each program asks for new-device approval once, at first sign-in in MileStone.
2. **Embedded WebView only, as planned.** Pros: smallest change. Cons: no quick way to see the account in the program's app.
3. **Default browser or program app only.** Pros: no new-device approvals. Cons: MileStone can read nothing, so every program becomes manual entry.

## Decision Outcome

Chosen: option 1. Sync and sign-in stay in the embedded WebView described in [ADR-0001](0001-login-bridge-sessions-fixtures-logging.md). Each tile's menu gains "Open in app or browser", which opens the program's balance page URL with `FLAG_ACTIVITY_REQUIRE_NON_BROWSER` on Android 11 and later, so the system hands it to an installed app that claims the link, and otherwise opens the default browser. MileStone keeps no package list and reads nothing back from either destination.

## Consequences

- The first MileStone sign-in for each program may require a one-time device approval; the per-program WebView profile then keeps that session, and feasibility Sessions 2 and 3 measure how long it lasts.
- Opening an account in the program's app or browser never updates the MileStone balance.
- On Android 10 the link opens through the system's normal link handling, which may show a chooser.

## Amendment (2026-11-11, menu item removed)

"Open in app or browser" was removed from the tile menu: it never updates a balance, and Refresh with the saved sign-in now covers reconnecting. The menu item, `OpenExternally.kt`, and its unit test are deleted. The embedded WebView stays the only sync path, so the rest of this decision stands.
