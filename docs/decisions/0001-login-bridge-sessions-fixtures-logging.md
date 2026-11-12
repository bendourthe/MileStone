# ADR-0001: Login Bridge, Session Profiles, Fixtures, and Logging

- **Status**: Accepted
- **Date**: 2026-11-07
- **Template**: MADR
- **Origin**: decisions during the v0.1.0 plan review
- **Supersedes**: -
- **Superseded by**: [ADR-0004](0004-keep-alive-and-automatic-sign-in.md), for stored credentials and background refresh only

## Context and Problem Statement

MileStone reads loyalty balances from 14 program websites that offer no public API. The user signs in to each site inside the app, and the app reads one number from the rendered dashboard page. That needs four things the platform does not hand over: a way for script running in a third-party page to send a value back to Kotlin, somewhere to keep each program's signed-in session, sample pages to build and test the extractors against, and a rule for what the app may write to logs while it handles authenticated pages.

Each of these choices touches credentials or account data for real financial and travel accounts. A wrong choice can leak a session cookie to another site, let a malicious page or iframe forge a balance, put account pages into a public repository, or write private data into logs that other apps or a bug report can read.

The app is sideloaded on one phone (Android API 37, current WebView), has no server, and stores everything on the device. The WebView feature set therefore varies only with the WebView version, and the app must degrade safely when a feature is missing rather than fall back to something weaker.

The plan review settled these questions. This record captures the result so later work does not reopen them by accident.

## Decision Drivers

- No credential, cookie, or page content may leave the phone or reach the repository.
- A message claiming to be a balance must come from the program's own top-level page, never from an iframe, an ad, or another site.
- Signing out of one program must not sign the user out of the others.
- A device without a required WebView feature must get manual entry, not a weaker mechanism.
- Diagnosing a broken extractor must be possible without logging private data.

## Considered Options

### Bridge transport

1. **`WebViewCompat.addWebMessageListener` with exact origin rules (chosen).** The listener is registered only for `https://<host>` entries built from the program's `allowedHosts`. The callback also checks `isMainFrame`, re-checks the source origin with `HostPolicy`, and matches a per-page-load nonce. Pros: the platform filters origins before the page can post; iframes are identifiable; nothing is injected into the page's JavaScript global scope. Cons: needs the `WEB_MESSAGE_LISTENER` feature; origin rules must be maintained per program.
2. **`addJavascriptInterface`.** Pros: available on every WebView. Cons: the interface is exposed to every frame and every origin the WebView loads, including ads and redirects, so any loaded script could call it; origin checks must be done by hand after the fact.
3. **Polling `evaluateJavascript` from Kotlin.** Pros: no page-to-Kotlin channel at all. Cons: the result cannot be tied to a frame or origin, it races with navigation, and it needs a polling loop that costs battery.

### Session persistence

1. **WebView cookie jar only, one `ProfileStore` profile per program named `program_<programId>` (chosen).** Tink-encrypted DataStore keeps metadata only (connected and last-verified times). Pros: no cookie copy exists outside WebView's own store; deleting one profile signs out exactly one program; nothing secret sits in app storage. Cons: needs the `MULTI_PROFILE` feature; without it every program shares the default profile.
2. **Export cookies into an encrypted app store.** Pros: full control over expiry and per-program separation on any WebView. Cons: creates a second copy of every session token, so a bug in the app's own storage could leak them; cookie flags such as `HttpOnly` and partitioning are hard to replay correctly.
3. **Store credentials and sign in automatically.** Cons: the app would hold passwords, and automated sign-ins trigger fraud and lockout defenses. Rejected outright.

When `MULTI_PROFILE` is missing, the shared default profile is used, and Sign out clears all cookies and marks every connected program NEEDS_RECONNECT after a confirmation dialog.

### Fixtures

1. **Real captures only in gitignored `app/src/test/resources/fixtures-private/`; committed fixtures are synthetic (chosen).** Pros: no account page can reach the repository even by mistake; synthetic fixtures carry only the structure the selector needs. Cons: the private set lives only on the developer's PC and needs its own offline backup; CI tests only the synthetic set.
2. **Commit redacted real captures.** Cons: redaction of names, member numbers, and balances spread through markup and inline JSON is error-prone, and one miss publishes account data. A redaction script was therefore not built.

### Capture build

A `capture` build type (debuggable, `.capture` application ID suffix) adds a "Capture page" action that saves `outerHTML` to app-internal storage. Debuggable builds enable WebView remote debugging and `run-as`, so anyone with USB access could read that build's sessions. After each capture session the developer clears the capture build's data and uninstalls it. CI never builds or publishes this build type.

### Logging

1. **Log hosts and failure codes only (chosen).** Never full URLs, query strings, cookies, page text, or raw balance strings. Release builds strip `Log.d` and `Log.v`.
2. **Verbose logging in debug builds only.** Cons: debug builds are exactly what runs on the developer's phone during development and capture sessions, so debug logs would still contain private data.

## Decision Outcome

Chosen: the message-listener bridge with exact origins, main-frame and nonce checks, failing closed to `ManualOnly` when `WEB_MESSAGE_LISTENER` or `DOCUMENT_START_SCRIPT` is unsupported and never falling back to `addJavascriptInterface`; per-program WebView profiles holding the only copy of each session, with Tink protecting metadata only; private real captures kept out of Git with synthetic committed fixtures; a capture build type with the uninstall rule above; and host-only logging.

Not decided here: which programs sync automatically (the feasibility record decides per program), the selector for each program (Phases 7 to 9), and background refresh scheduling (out of scope for v0.1).

## Consequences

**Positive**

- A forged balance from an iframe, an ad, or a redirect to another site is dropped before it reaches the sync coordinator.
- Signing out of or removing one program deletes only that program's profile.
- The repository cannot contain account data, because real captures never enter it.
- Logs are safe to share in a bug report.

**Negative**

- Devices or WebView versions lacking `WEB_MESSAGE_LISTENER` or `DOCUMENT_START_SCRIPT` lose automatic sync entirely and fall back to manual entry.
- Without `MULTI_PROFILE`, Sign out of one program signs out of all of them.
- The private fixture set exists only on the developer's PC, so extractor tests against real pages cannot run in CI.
- Diagnosing a selector failure from logs is harder, because the page text that failed to parse is never logged; diagnosis needs a fresh capture session instead.
- Each program's origin list must be kept current; a site moving its dashboard to a new host makes sync fail closed until the catalog is updated.

## Risks

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| A program serves its balance from a host outside `allowedHosts` | Medium | Medium | Sync fails closed with `ManualOnly` behavior; the feasibility record and catalog are updated in a patch release. |
| The capture build stays installed after a session | Medium | High | The capture runbook in the README ends with clearing data and uninstalling; the build has a distinct app name and ID. |
| A WebView update changes profile semantics | Low | Medium | `WebViewConfig.supportsProfiles` is checked at runtime, and the shared-profile path is tested. |
