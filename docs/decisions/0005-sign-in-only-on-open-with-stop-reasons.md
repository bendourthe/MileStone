# ADR-0005: Password Sign-In Only When the App Opens, with Stop Reasons

- **Status**: Accepted
- **Date**: 2026-11-09
- **Template**: MADR
- **Origin**: decisions after Capital One showed Reconnect and then reconnected by itself when tapped
- **Supersedes**: in [ADR-0004](0004-keep-alive-and-automatic-sign-in.md), background automatic sign-in and the rule that every stopped sign-in blocks automatic sign-in
- **Superseded by**: -

## Context and Problem Statement

Under ADR-0004 the 15-minute keep-alive both reused sessions and typed the saved password when a session had ended. Chase ends its session within about 20 minutes, so the keep-alive signed in to Chase on nearly every run while the user was away (G13). Any stopped automatic sign-in also blocked further attempts until the user acted, whatever the cause, so a program whose texted code arrived late showed Reconnect even though its password was fine; tapping Reconnect then succeeded.

The requirement is that MileStone connect by itself only when the app is opened, that a refused password be reported and never retried, and that the app report which programs could not reconnect and why.

## Decision Drivers

- A refused password must never be retried automatically, so the account cannot be locked.
- No password is typed while the user is away from the app.
- Sessions that are still valid stay valid, so most opens need no sign-in at all.

## Considered Options

1. **Keep-alive reuses sessions only; sign-in runs when the app is opened afresh; stops carry a reason (chosen).** Pros: no background sign-ins; Chase-style short sessions still stay alive while the phone is idle; only a refused password, or the same unknown page twice, needs the user. Cons: a session that ends while the app is closed waits until the next open.
2. **No background activity at all.** Cons: every open after a session ends signs in again, often with a texted code.
3. **Close the app after 15 minutes so the next open restarts it.** Cons: Android apps are not meant to end themselves; returning after 15 minutes away gives the same effect without killing the process.

## Decision Outcome

Chosen: option 1.

- The keep-alive never types a password: it skips programs that need a sign-in, and its "needs you" notification fires only for a program without a usable saved sign-in.
- Each time MileStone comes on screen it refreshes every program and signs in where a saved sign-in allows, ignoring the failure backoff while still skipping programs tried in the last 5 minutes. Opening it afresh, meaning the first start of the process or a return after at least 15 minutes away, also ends with one message listing each program still not connected and the reason.
- Each stopped sign-in records why: the sign-in form came back after the password was sent (`Password refused`), a texted code could not be entered (`Texted code needed`), or the sign-in ended on an unknown page or found no form (`Sign-in unfinished`). A refused password blocks automatic sign-in at once and asks the user for the current password; an unknown page blocks it when it happens twice in a row; a missing code or form never sent a wrong password, so the next open tries again.
- Blocks recorded under ADR-0004, which carry no reason, are ignored once, so those programs try again under these rules.
- There is no Reconnect button. A tile says what its sync is doing ("Syncing...", "Reconnecting...", "Signing in...", "Waiting for the texted code..."); only a program MileStone cannot sign in by itself shows a "Sign-in needed" chip, which opens the sign-in when tapped. A new program's tile appears as soon as its sign-in starts.

## Consequences

**Positive**

- No password leaves the vault while the user is away from MileStone.
- A late texted code no longer leaves a program waiting for the user.

**Negative**

- A program whose session ends while the app is closed shows its old balance until the next open.
- A site that shows its sign-in form again for a reason other than a wrong password is reported as `Password refused` and waits for the user.

## Clarification (2026-11-10)

Capital One reported `Password refused` on app opens while its password was correct: the site sent no response to the submit (no request answered in 16 s, against three answers within 3 s when it succeeded) and showed no error. A submitted form that stays on screen with no visible error is therefore not yet a refusal: its button is pressed once more, and the sign-in counts as refused only if the form is still there after that press or an error shows. The password is not typed again, so a form the site emptied submits nothing. The first fill also waits up to 10 s for the page to finish loading its own scripts.

## Clarification (2026-11-10, retries)

Timeouts and similar stops now sign in again by themselves. A refresh started by opening MileStone, the refresh button, or a tile's Refresh now signs in once more, one program at a time, each program whose sign-in timed out, stopped unfinished, or hit a page or network error. A refused password and a missing texted code are still never retried: the first could lock the account, the second sends another text. An unfinished sign-in now pauses automatic sign-in on its third consecutive stop instead of its second, so the in-run retry alone never pauses a program. After the saved sign-in is submitted, a still-loading page that asks for nothing gets up to three more page timeouts, inside the sync's three-minute budget. Opening MileStone refreshes a connected balance only when it is older than the user's chosen interval (daily, weekly by default, or monthly); programs that failed or are signed out still try on every open.

## Clarification (2026-11-11, schedule and refusal)

Every program refreshed on open with the weekly setting, and Amex and Capital One stopped as `Password refused` although the saved sign-in worked from the tile menu. Three rules replace the earlier ones:

- The refresh schedule is a wall-clock time: every day at midnight, every Sunday at midnight (default), or the last day of the month at midnight. A phone alarm at that time refreshes every balance older than it, tries every failed program once, and may sign in with the saved sign-in in the background. Opening MileStone refreshes a program only when it was neither updated nor tried since that scheduled time; a program that failed or needs a sign-in waits for the next scheduled time or the user's Refresh (retries on open read as a refresh of everything). A timed-out or unfinished sign-in still gets its one retry inside the same refresh.
- A sign-in counts as refused only when the site shows a visible error with the form. A form that comes back with no error is an unfinished sign-in, which the retry rules above may try again. This supersedes the negative consequence that any returning form reads as `Password refused`.
- Refresh from a tile menu or the top refresh button is the user asking again, so it clears a paused sign-in and tries once with the saved sign-in. Refresh shows in the menu of every program that can sync, including one paused by a stop or showing Sign in needed, and signs in out of sight; only a program with no saved sign-in asks the user to save one.

## Clarification (2026-11-11, Refresh only)

Most programs showed Sign in required after the background session ended, and a Sign in item next to Refresh made no sense for a program already added. An added program's menu now offers Refresh and Sign out only; Refresh is the one way to sign in again, using the saved sign-in out of sight. A program with a saved sign-in no longer shows Sign in needed when its session ends, because the next scheduled refresh or the user's Refresh signs it in again; the chip stays only for a program with no saved sign-in.

Every refresh first reads the program's session without typing anything, beside the other reads; only a read that lands on a sign-in page, or fails in a way a sign-in may fix, queues for the one-at-a-time sign-in. Observed case: an open took almost 5 minutes because United, Hyatt, and Marriott, shown as signed out, waited behind Delta's 2-minute sign-in although their sessions still read in 4 seconds each. The keep-alive never reaches the sign-in step.

## Clarification (2026-11-11, which errors count, and menu order)

Capital One stopped as `Password refused` twice, with the same shape: username and password typed, the form still up about 16 s later, one checkbox, six buttons, no code box, and no HTTP error after the submit. A wrong password at a bank normally returns an error status, so the site most likely never judged the password. Two markers that are not a refusal could cause this: a notice already on the page before the sign-in was typed, or the 1 px screen-reader announcer that Angular pages keep. Neither is shown to the user as an error. Two rules refine "a visible error":

- A marker counts only when it is on screen. A screen-reader-only region of 1 px or less does not count. A password box marked invalid still counts; another box marked invalid with no message does not (see the later clarification on Capital One's username).
- A marker counts only when it appeared, or changed its message, after MileStone typed the sign-in. The page compares the message with what the same marker showed before. The text is never sent or logged. A refusal written into a notice that was already on screen still counts (fixture `refused-in-standing-notice.html`). A standing notice with an announcer and a first press the site ignores leads to a second press and a balance read (fixture `standing-notice-signin.html`).

Each stopped sign-in now also logs counts of alerts and assertive live regions, split into shown and screen-reader only, and of invalid fields. It never logs their text.

An added program's menu reads Refresh, Sign out, Remove. Sign out deletes the saved sign-in, so the next Refresh asks for it to be saved again. Open in app or browser was also removed because it never updated a balance ([ADR-0002](0002-open-in-app-or-browser.md) amendment). The Capital One fix was confirmed on a device: Refresh from the tile menu reconnected Capital One.

## Clarification (2026-11-11, quiet keep-alive misses)

The diagnostics file showed the 15-minute keep-alive putting `Timed out` on Delta, United, and Hyatt tiles. Delta's session ended within about 30 minutes of its sign-in, and every later keep-alive landed on its signed-out home page (11 text boxes, 38 buttons, no sign-in form), which the page script cannot tell apart from an account page whose balance has not appeared yet. United and Hyatt timed out on a slow page and read again 15 minutes later without a sign-in; later United's balance element still held loading text and was recorded as `Unparseable`.

Decision: on a program that is connected, a keep-alive `Timed out` or `Unparseable` result is written to the diagnostics file only. The tile, its error, and its failure count stay as they were, and no result is reported. A sign-in page (`Sign in needed`) and every other failure still show. A refresh the user starts, the scheduled refresh, and the refresh on opening still show every result. Delta therefore keeps its last miles with no error and reads again at the scheduled refresh or on Refresh, like Chase ([G13](../releases/v0/v0.1/known-gaps.md)). Unit tests: `aKeepAliveTimeoutOrLoadingBalanceLeavesAWorkingTileUnchanged`, `aKeepAliveThatLandsSignedOutStillShowsOnTheTile`, `anOwnerRefreshThatTimesOutStillShowsOnTheTile`.

## Clarification (2026-11-11, faster sign-in and the opening summary)

A user-started Refresh on Delta took about 98 s: a 30 s session read on Delta's signed-out home page, a sign-in run that waited about 28 s more on the same page before opening the sign-in page, then the sign-in and the read. Two rules now apply to every program:

- A hidden sign-in loads the program's sign-in page first when it differs from the balance page (Amex, Capital One, Delta, Marriott, Hilton). If that page shows no form before anything is typed, the balance page opens once, so a session that is still alive is read there.
- MileStone remembers, in memory only, each program whose last session read ended signed out or did not reach the balance. A refresh that may sign in skips the session read for that program and signs in at once; the sign-in reads the balance anyway. A successful read or sign-in clears the mark, and an app restart forgets it.

Opening MileStone showed "Couldn't reconnect: Air Canada Aeroplan (Timed out)" although the refresh on opening refreshed nothing: the summary listed every tile with an error, including one left by an earlier background run. The summary now names only programs refreshed on that opening, and nothing is shown when none were.

The quiet keep-alive rule first covered only connected programs, so Air Canada's `Timed out` was renewed by a later keep-alive and stayed on its tile. A keep-alive `Timed out` or `Unparseable` is now quiet for every program: it never writes an error or a failure count. A first version also cleared a tile's earlier timeout on such a miss; the Tier 3 review showed that this hides a real timeout from a scheduled sign-in, so it was removed. An error already on a tile stays until the user's Refresh or a successful read (unit test `aKeepAliveMissNeverChangesAnErrorAlreadyOnTheTile`).

Also from that review: only a sign-in page or a balance page that timed out marks a session as ended; a network or renderer failure does not, so a user's Refresh never types the password over a live session after a network blip (`aNetworkFailureDoesNotMarkTheSessionEnded`). A refresh or its retry never starts beside a sign-in the user started (`aRefreshNeverStartsBesideASignInTheOwnerStarted`). A "needs you" notification intent is honored only with its own action and never lifts a paused sign-in, because `MainActivity` is exported as the launcher and any app can send it extras. A texted code is matched to a program only when the program's keyword is a whole word ("purchase" no longer matches "chase").

At a global refresh, Capital One stopped as `Password refused` again. Its error-marker counts were `0,0,0,0,0,1`: no alert, no live message, and one non-password box marked invalid, most likely the username. A box marked invalid with no message is the site's own form check before anything is sent, so only a password box marked invalid now counts by itself; the form back without a counted marker is unfinished, and the script presses again (instrumented fixture `invalid-username-no-message.html`). Why Capital One's check rejects the typed username is still unknown. In the same refresh, United's balance element held words while the page loaded and was read as `Could not read balance`; a balance text without a digit is now treated as still loading (instrumented fixture `loading-text-then-balance.html`).

Unit tests: `aHiddenSignInStartsAtTheSignInPageOnlyWhenItCanFillAndThePagesDiffer`, `aSignInPageThatShowsNoFormOpensTheBalancePageOnceInsteadOfReturningToIt`, `aSignInPageStartedDirectlyIsFilledAsUsual`, `aSessionThatLastReadSignedOutSignsInWithoutAnotherRead`, `aSessionReadThatWorksIsTriedBeforeASignIn`, `openSummaryNeverAnnouncesAnOlderErrorOnAProgramThatWasNotRefreshed`.

## Risks

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| A bank flags a sign-in on every app open | Low | Medium | The keep-alive keeps most sessions alive, so an open usually needs no sign-in. |
| A missing code repeats on every open | Medium | Low | Each attempt uses the accepted password once; the tile and the open message name the cause. |
