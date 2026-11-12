# ADR-0003: Sync-Only Catalog, Reconnect with Autofill, and Cash Balances

- **Status**: Accepted
- **Date**: 2026-11-07
- **Template**: MADR
- **Origin**: decisions after the Amex, Chase, and Capital One capture session
- **Supersedes**: -
- **Superseded by**: [ADR-0006](0006-catalog-lists-programs-not-syncing-yet.md) and then [ADR-0007](0007-catalog-filter-and-added-outline.md), in part: hiding programs without automatic sync

## Context and Problem Statement

The plan offered manual balance entry for programs MileStone cannot read and as a fallback on every tile. That was rejected: the app exists so a user signs in to a program once and MileStone then refreshes the balance by itself on every refresh and every app open, with nothing typed by hand.

Feasibility Session 2 showed that Amex, Chase, Capital One, Hyatt, and Hilton end their web sessions within about an hour, so "sign in once" cannot mean one sign-in forever for those programs. Two more questions came up in the same session: Capital One's rewards balance is a dollar amount, not points, and the Amex overview page also shows a co-brand Delta SkyMiles balance.

## Decision Drivers

- Automatic balances are the product; a hand-typed number is not.
- No passwords, one-time codes, or cookies may be stored (ADR-0001).
- A balance must be read from the program's own pages on its own hosts.

## Considered Options

1. **Sync-only catalog, one-tap Reconnect filled by Android's password manager, Capital One shown in dollars, Delta read only from Delta (chosen).**
2. **Keep manual entry as a fallback.** Rejected as contrary to the product's purpose.
3. **Store credentials and sign in silently.** Rejected: it breaks ADR-0001's no-credential rule and invites lockouts and fraud flags.
4. **Read Delta SkyMiles from the Amex overview.** Rejected: it works only for Delta co-brand cardholders and makes Delta depend on Amex's page layout.

## Decision Outcome

Chosen: option 1.

- The catalog and its search show only programs with automatic sync. Programs without it stay in the catalog data with their feasibility reason, hidden, so a later extractor phase can enable them. All manual-entry screens, actions, and labels are removed.
- When a session expires, the tile shows a Reconnect chip. One tap opens the program's sign-in page in MileStone's WebView, where Android's autofill service (for example Google Password Manager) offers the saved password. MileStone never sees or keeps it.
- Programs gain a balance unit. Capital One Rewards uses cash in cents, parsed from the `$12.34` form and shown as dollars; every other program uses points.
- Delta SkyMiles is attempted only on Delta's own site, in the airline extractor phase.

## Consequences

- Programs with short sessions need one Reconnect tap, plus password-manager confirmation, on most app opens; the Session Lifetime summary lists them.
- The manual data layer (`setManualBalance`, the `lastManualEditEpochMs` column, `BalanceOrigin.MANUAL`, `AccountStatus.MANUAL_ONLY`) is now unused and is removed with a schema migration in the refactor phase.
- A user without a password manager types the password at each Reconnect.
