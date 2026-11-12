# ADR-0004: Background Keep-Alive, Saved Sign-Ins, and Texted Codes

- **Status**: Accepted
- **Date**: 2026-11-08
- **Template**: MADR
- **Origin**: decisions after Amex, Capital One, and Chase kept ending their sessions
- **Supersedes**: the "Store credentials and sign in automatically" rejection and the "background refresh out of scope" note in [ADR-0001](0001-login-bridge-sessions-fixtures-logging.md)
- **Superseded by**: [ADR-0005](0005-sign-in-only-on-open-with-stop-reasons.md), in part: background sign-in and blocking on every stop

## Context and Problem Statement

Sessions persist correctly on the phone, but the banks end them after a period without use: about 40 to 50 minutes for American Express and about an hour for Capital One, even with "Remember me" checked. Chase ends its session too, and its sign-in box sits inside a same-origin frame. MileStone refreshed only while it was on screen, so every visit after an hour started with Reconnect, and each reconnect asked for a password and a texted code or an app approval.

Reconnecting by hand makes the app pointless next to each bank's own app with biometrics, so the goal is the strongest automatic path that keeps credentials encrypted and on the phone.

## Decision Drivers

- Credentials, codes, cookies, and page content still never leave the phone or reach the repository, logs, or backups.
- An automatic sign-in must never retry a refused or interrupted attempt, so a bank's lockout defenses are not triggered.
- MileStone must not approve sign-ins inside another app, because that would also approve an attacker's attempt.

## Considered Options

### Keeping sessions alive

1. **Idle-time alarm every 15 minutes starting a short foreground task that refreshes on a private virtual display (chosen).** Pros: fires during Doze; the page renders as on screen; the user sees a quiet notification while it runs. Cons: needs an exemption from battery optimization; costs some battery; a bank's absolute session cap still applies.
2. **WorkManager periodic work.** Cons: deferred for hours in Doze, longer than the banks' idle limits.

### Signing in again

1. **Saved sign-in in an on-phone vault, submitted once per expiry (chosen).** Each program's username and password are sealed with AES-256-GCM using an Android Keystore key, in StrongBox when available, bound to the program ID as associated data, stored under `noBackupFilesDir`, and never logged. The password is read from the vault only when the program's own sign-in form appears, and only sent to a page on the program's allowed hosts. A second sign-in form, an unfilled form, a missing code, or a timeout after submission blocks automatic sign-in until the user signs in or saves the sign-in again. The key does not require an unlocked screen, so background sign-in works while the phone is locked; the vault file sits in credential-encrypted storage, unreadable before the first unlock after a restart.
2. **Require a biometric for every use of the key.** Cons: background sign-in would stop whenever the screen is off, which is the case it exists for.
3. **Keep rejecting stored credentials (ADR-0001).** Cons: the user reconnects every hour by hand.

### Second factor

1. **Read texted codes in memory and enter them (chosen).** The `RECEIVE_SMS` broadcast hands each text to an in-memory inbox that keeps a code for 5 minutes at most, hands it out once, and only to a program whose name the text contains, sent after MileStone's own submission. After its own sign-in the page script also ticks "remember this device" boxes, accepts a "save this device" prompt, and picks a texted code over app approval when an option names the masked phone number or a code.
2. **Approve the bank app's notification through an accessibility service.** Cons: grants control of the whole screen and approves whichever sign-in arrives, including a criminal's. Rejected.
3. **Read texts through the default Messages app's notifications.** Cons: needs notification access to every app's notifications, broader than the one broadcast needed.

## Decision Outcome

Chosen: the alarm and foreground-task keep-alive on a private virtual display, the Keystore-sealed vault with one automatic attempt per expiry, and in-memory texted codes. A program that still needs the user gets one "needs you" notification that opens its sign-in with the saved sign-in already filled. Signing out of or removing a program deletes its saved sign-in.

## Consequences

**Positive**

- Sessions are used every 15 minutes, so idle limits rarely end them, and an ended session usually reconnects with no action from the user.
- A copied vault file is useless off the phone, and swapping one program's file for another's fails to decrypt.

**Negative**

- Anyone who can unlock the phone, or code running as MileStone, can use the saved sign-ins. The phone's lock screen is the boundary.
- Banks may still demand app approval or a passkey; those sign-ins need the user.
- Background refresh stops if the user restricts MileStone's battery use.

## Risks

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| A bank flags repeated automated sign-ins | Medium | High | One attempt per expiry, then blocked until the user acts; keep-alive makes expiries rare. |
| A text from another sender names the program and carries a code | Low | Medium | Codes only count after MileStone's own submission, for 5 minutes, once, and only typed into that program's own pages. |
| The page heuristics click the wrong control | Medium | Medium | The "text me" choice requires a masked phone number or the word code, runs only after MileStone's own sign-in, and never on links. |
