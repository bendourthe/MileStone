# Known Gaps - v0.5

**Project**: MileStone
**Status**: in-progress
**Last updated**: 2026-11-20
**Open items**: 36

## v0.5.0

### Summary

| Category | Open | Resolved |
|---|---|---|
| Not implemented (NI) | 0 | 0 |
| Deferred (DF) | 36 | 0 |
| Bugs / regressions (BG) | 0 | 0 |
| Warnings (WN) | 0 | 0 |
| Missing tests / coverage gaps (MT) | 0 | 0 |
| Quality-gate gaps (QG) | 0 | 0 |

### Open Items

#### DF-1 - Legacy G01

- **Legacy ID**: G01
- **Source phase**: Inherited v0.3 ledger condition; original source is preserved in the exact legacy row below.
- **Plan reference**: docs/archives/v0/v0.3/known-gaps.md (G01); inherited v0.4 legacy snapshot.
- **Reason**: Existing condition carried into v0.4, outside the delivered Global Search change; preserve its original condition and source.
- **Suggested next step**: Follow the original Action or condition in the preserved legacy row.

| G01 | Citi ThankYou Points shows Unsupported: sign-in has not been tested yet. | An account to run a feasibility session against. | Feasibility record; carried from [v0.3](../../../archives/v0/v0.3/known-gaps.md) by v0.3.1 on 2026-11-13. |

- **Migrated from**: v0.4.0#DF-1 on 2026-11-20 (reason: user-deferred)
- **Migration evidence**: left open by v0.4.0, the last plan of v0.4

#### DF-2 - Legacy G02

- **Legacy ID**: G02
- **Source phase**: Inherited v0.3 ledger condition; original source is preserved in the exact legacy row below.
- **Plan reference**: docs/archives/v0/v0.3/known-gaps.md (G02); inherited v0.4 legacy snapshot.
- **Reason**: Existing condition carried into v0.4, outside the delivered Global Search change; preserve its original condition and source.
- **Suggested next step**: Follow the original Action or condition in the preserved legacy row.

| G02 | Bilt Rewards shows Unsupported: sign-in has not been tested yet. | An account to run a feasibility session against. | Feasibility record; carried from [v0.3](../../../archives/v0/v0.3/known-gaps.md) by v0.3.1 on 2026-11-13. |

- **Migrated from**: v0.4.0#DF-2 on 2026-11-20 (reason: user-deferred)
- **Migration evidence**: left open by v0.4.0, the last plan of v0.4

#### DF-3 - Legacy G03

- **Legacy ID**: G03
- **Source phase**: Inherited v0.3 ledger condition; original source is preserved in the exact legacy row below.
- **Plan reference**: docs/archives/v0/v0.3/known-gaps.md (G03); inherited v0.4 legacy snapshot.
- **Reason**: Existing condition carried into v0.4, outside the delivered Global Search change; preserve its original condition and source.
- **Suggested next step**: Follow the original Action or condition in the preserved legacy row.

| G03 | American Airlines AAdvantage shows Unsupported: sign-in has not been tested yet. | An account to run a feasibility session against. | Feasibility record; carried from [v0.3](../../../archives/v0/v0.3/known-gaps.md) by v0.3.1 on 2026-11-13. |

- **Migrated from**: v0.4.0#DF-3 on 2026-11-20 (reason: user-deferred)
- **Migration evidence**: left open by v0.4.0, the last plan of v0.4

#### DF-4 - Legacy G04

- **Legacy ID**: G04
- **Source phase**: Inherited v0.3 ledger condition; original source is preserved in the exact legacy row below.
- **Plan reference**: docs/archives/v0/v0.3/known-gaps.md (G04); inherited v0.4 legacy snapshot.
- **Reason**: Existing condition carried into v0.4, outside the delivered Global Search change; preserve its original condition and source.
- **Suggested next step**: Follow the original Action or condition in the preserved legacy row.

| G04 | British Airways Club shows Unsupported: sign-in has not been tested yet. | An account to run a feasibility session against. | Feasibility record; carried from [v0.3](../../../archives/v0/v0.3/known-gaps.md) by v0.3.1 on 2026-11-13. |

- **Migrated from**: v0.4.0#DF-4 on 2026-11-20 (reason: user-deferred)
- **Migration evidence**: left open by v0.4.0, the last plan of v0.4

#### DF-5 - Legacy G05

- **Legacy ID**: G05
- **Source phase**: Inherited v0.3 ledger condition; original source is preserved in the exact legacy row below.
- **Plan reference**: docs/archives/v0/v0.3/known-gaps.md (G05); inherited v0.4 legacy snapshot.
- **Reason**: Existing condition carried into v0.4, outside the delivered Global Search change; preserve its original condition and source.
- **Suggested next step**: Follow the original Action or condition in the preserved legacy row.

| G05 | Delta SkyMiles needed a sync approach other than manual entry, which was rejected as a decision. Its page went blank after sign-in. Root cause found for United (G06): MileStone's WebViews laid pages out with a zero viewport height, so every `vh` length was 0. Fixed in `WebViewConfig`. Signing in over Site check then showed that Delta asks for a personal or business account and afterward shows its home page with the miles in the header, and the catalog now reads them (`DELTA_XPATH`, synthetic fixture `delta.html`). The first Add program attempt timed out because the hidden browser stayed on the signed-out home page, which shows no form; a headless session with a saved sign-in now opens the program's sign-in page once when its balance page times out signed out. The hidden sign-in then reached the personal or business choice and stopped there (a page with 5 buttons and no fields); after Personal was chosen in the sign-in window, Delta read its miles on every refresh. The hidden sign-in now presses Personal when a page offers Personal and Business. A later remove and re-add of Delta still ended in the sign-in window after 2 to 3 minutes of hidden sign-in, and the stop reason was lost because the phone's log had rotated; since then MileStone keeps its own diagnostics file (hosts, page shapes, stop reasons only). Its first record: the hidden sign-in submitted, landed on a page with 5 buttons and no fields, and waited 2 minutes before stopping as unfinished; the in-run retry then read the miles in 3 s, so the sign-in had worked. Since then, a page after the password that asks for nothing opens the balance page once (unit test in `AutoSignInSessionTest`). Delta's session ends within about 30 minutes, so every keep-alive after that lands signed out; since the quiet keep-alive clarification of [ADR-0005](../../../decisions/0005-sign-in-only-on-open-with-stop-reasons.md), those misses leave the tile unchanged. A manual Refresh took about 98 s; a hidden sign-in now starts at Delta's sign-in page and skips the session read once a read has found the session ended (same ADR, faster sign-in clarification). | One Add program sign-in that finishes without anyone using the phone. | Feasibility record; carried from [v0.3](../../../archives/v0/v0.3/known-gaps.md) by v0.3.1 on 2026-11-13. |

- **Migrated from**: v0.4.0#DF-5 on 2026-11-20 (reason: user-deferred)
- **Migration evidence**: left open by v0.4.0, the last plan of v0.4

#### DF-6 - Legacy G06

- **Legacy ID**: G06
- **Source phase**: Inherited v0.3 ledger condition; original source is preserved in the exact legacy row below.
- **Plan reference**: docs/archives/v0/v0.3/known-gaps.md (G06); inherited v0.4 legacy snapshot.
- **Reason**: Existing condition carried into v0.4, outside the delivered Global Search change; preserve its original condition and source.
- **Suggested next step**: Follow the original Action or condition in the preserved legacy row.

| G06 | United MileagePlus does not sync yet. The empty sign-in had one cause: `100vh` measured 0 px in MileStone's WebViews (emulator), collapsing United's sign-in panel. Fixed; the panel now renders, and the catalog opens `/en/us/myunited`, which shows sign-in by itself (the old address was a 404 page). Signing in over Site check (MileagePlus number, password, texted code) showed the number under the MILES heading, and the catalog now reads it (`UNITED_CSS`, synthetic fixture `united.html`). A later remove and re-add still opened the sign-in window. A texted-code box named only by its label (no code-like name or autocomplete) was not recognized; the page script now reads field labels (instrumented fixture `code-by-label.html`), unconfirmed on the live site. The diagnostics file shows the hidden sign-in filling the username, then the password, then stopping on a page with 6 text boxes, no box marked as a code, a "remember this device" checkbox, and text-message wording; United most likely splits the code into one box per digit. The page script now types a code into 4 to 8 single-character boxes (instrumented fixture `code-by-digits.html`), and a stop logs each text box's type, autocomplete token, length limit, and input mode. | One Add program sign-in and one balance read on a device, or the diagnostics file of the next attempt. | Feasibility record; carried from [v0.3](../../../archives/v0/v0.3/known-gaps.md) by v0.3.1 on 2026-11-13. |

- **Migrated from**: v0.4.0#DF-6 on 2026-11-20 (reason: user-deferred)
- **Migration evidence**: left open by v0.4.0, the last plan of v0.4

#### DF-7 - Legacy G17

- **Legacy ID**: G17
- **Source phase**: Inherited v0.3 ledger condition; original source is preserved in the exact legacy row below.
- **Plan reference**: docs/archives/v0/v0.3/known-gaps.md (G17); inherited v0.4 legacy snapshot.
- **Reason**: Existing condition carried into v0.4, outside the delivered Global Search change; preserve its original condition and source.
- **Suggested next step**: Follow the original Action or condition in the preserved legacy row.

| G17 | Amex and Capital One stopped as `Password refused` although the saved sign-in worked from the tile menu, and their menus offered no Refresh. Any password form seen after submit counted as refused. Now only a visible site error counts; a form back with no error is unfinished and may be retried, Refresh shows on every syncing tile, and Refresh clears a paused sign-in ([ADR-0005](../../../decisions/0005-sign-in-only-on-open-with-stop-reasons.md), clarification). Why their hidden sign-in leaves the form on screen is not yet known. Capital One stopped as refused again with the same shape (username and password typed, form up about 16 s later, one checkbox, six buttons, no code box, no HTTP error after submit). Since then only an on-screen marker that appeared or changed after typing counts as a refusal, and each stop logs error-marker counts ([ADR-0005](../../../decisions/0005-sign-in-only-on-open-with-stop-reasons.md), error-marker clarification; fixtures `standing-notice-signin.html` and `refused-in-standing-notice.html`). Refresh on the Capital One tile was confirmed on a device to reconnect it with that build. A later global refresh stopped Capital One as refused again, with no message and one non-password box marked invalid; such a box no longer counts as a refusal (ADR-0005, Capital One username clarification), but why Capital One rejects the typed username is unknown. | One Amex stop or success with this build; one Capital One global refresh with the build that ignores a username marked invalid. | ADR-0005; carried from [v0.3](../../../archives/v0/v0.3/known-gaps.md) by v0.3.1 on 2026-11-13. |

- **Migrated from**: v0.4.0#DF-7 on 2026-11-20 (reason: user-deferred)
- **Migration evidence**: left open by v0.4.0, the last plan of v0.4

#### DF-8 - Legacy G16

- **Legacy ID**: G16
- **Source phase**: Inherited v0.3 ledger condition; original source is preserved in the exact legacy row below.
- **Plan reference**: docs/archives/v0/v0.3/known-gaps.md (G16); inherited v0.4 legacy snapshot.
- **Reason**: Existing condition carried into v0.4, outside the delivered Global Search change; preserve its original condition and source.
- **Suggested next step**: Follow the original Action or condition in the preserved legacy row.

| G16 | World of Hyatt's automatic sign-in is judged refused after filling only the password. One attempt logged no HTTP error after submit, so Hyatt rejected nothing: the page found no username box, and a password box was still showing afterward. On the emulator Hyatt serves a bot-protection challenge page (two scripts and an iframe, matching Kasada), so its form can be inspected only on a physical device. Since then, a stop like this opens the site instead of asking for the password again. Later attempts met a page with one password box and two buttons; the fill pressed the form's first button, which can be a typeless show-password toggle, so the submit now prefers a Sign in style label and skips reveal toggles (fixture `identity-first.html` proves it). The real flow was then found: Hyatt's email step emails a sign-in link that cannot open in MileStone, and its "Sign in with password" choice, which is not a button or a link with an address, leads to email, password, and last name; signing in by hand that way succeeded. The route search now covers any visible element labeled exactly as the route (fixture `password-route.html` proves it). After a manual sign-in, a refresh on opening reads the balance, but every background keep-alive refresh is sent to `world.hyatt.com`, which is outside Hyatt's allowed hosts, then times out on a near-empty `www.hyatt.com` page. | Confirm the next automatic Hyatt sign-in on a device; find why only the background refresh is sent to `world.hyatt.com` before deciding whether that host belongs in the feasibility record. | Phase 9; carried from [v0.3](../../../archives/v0/v0.3/known-gaps.md) by v0.3.1 on 2026-11-13. |

- **Migrated from**: v0.4.0#DF-8 on 2026-11-20 (reason: user-deferred)
- **Migration evidence**: left open by v0.4.0, the last plan of v0.4

#### DF-9 - Legacy G08

- **Legacy ID**: G08
- **Source phase**: Inherited v0.3 ledger condition; original source is preserved in the exact legacy row below.
- **Plan reference**: docs/archives/v0/v0.3/known-gaps.md (G08); inherited v0.4 legacy snapshot.
- **Reason**: Existing condition carried into v0.4, outside the delivered Global Search change; preserve its original condition and source.
- **Suggested next step**: Follow the original Action or condition in the preserved legacy row.

| G08 | The portrait lock does not apply on unfolded foldable screens: Android 16 ignores orientation locks on screens 600 dp wide or more. | None; platform behavior. | Phase 4; carried from [v0.3](../../../archives/v0/v0.3/known-gaps.md) by v0.3.1 on 2026-11-13. |

- **Migrated from**: v0.4.0#DF-9 on 2026-11-20 (reason: user-deferred)
- **Migration evidence**: left open by v0.4.0, the last plan of v0.4

#### DF-10 - Legacy G11

- **Legacy ID**: G11
- **Source phase**: Inherited v0.3 ledger condition; original source is preserved in the exact legacy row below.
- **Plan reference**: docs/archives/v0/v0.3/known-gaps.md (G11); inherited v0.4 legacy snapshot.
- **Reason**: Existing condition carried into v0.4, outside the delivered Global Search change; preserve its original condition and source.
- **Suggested next step**: Follow the original Action or condition in the preserved legacy row.

| G11 | Texted-code entry and the "text me" choice have unit coverage but have not run on a live page: no bank asked for a code during the automatic sign-ins tried so far. | A bank that sends a verification text during an automatic sign-in. | ADR-0004; carried from [v0.3](../../../archives/v0/v0.3/known-gaps.md) by v0.3.1 on 2026-11-13. |

- **Migrated from**: v0.4.0#DF-10 on 2026-11-20 (reason: user-deferred)
- **Migration evidence**: left open by v0.4.0, the last plan of v0.4

#### DF-11 - Legacy G12

- **Legacy ID**: G12
- **Source phase**: Inherited v0.3 ledger condition; original source is preserved in the exact legacy row below.
- **Plan reference**: docs/archives/v0/v0.3/known-gaps.md (G12); inherited v0.4 legacy snapshot.
- **Reason**: Existing condition carried into v0.4, outside the delivered Global Search change; preserve its original condition and source.
- **Suggested next step**: Follow the original Action or condition in the preserved legacy row.

| G12 | Capital One's first automatic sign-in after a save stopped twice on its verification site and then worked; the controls left on the page were not captured because the phone's log buffer rotated. | The sign-in shape log on the next stop. | ADR-0004; carried from [v0.3](../../../archives/v0/v0.3/known-gaps.md) by v0.3.1 on 2026-11-13. |

- **Migrated from**: v0.4.0#DF-11 on 2026-11-20 (reason: user-deferred)
- **Migration evidence**: left open by v0.4.0, the last plan of v0.4

#### DF-12 - Legacy G14

- **Legacy ID**: G14
- **Source phase**: Inherited v0.3 ledger condition; original source is preserved in the exact legacy row below.
- **Plan reference**: docs/archives/v0/v0.3/known-gaps.md (G14); inherited v0.4 legacy snapshot.
- **Reason**: Existing condition carried into v0.4, outside the delivered Global Search change; preserve its original condition and source.
- **Suggested next step**: Follow the original Action or condition in the preserved legacy row.

| G14 | Air Canada Aeroplan cannot sign in by itself: its sign-in form opens only from a button on the Air Canada page, so the hidden sign-in lands on a page it does not recognize. The user signs in once on the site, and again whenever the session ends (it lasted under 26 hours in one test). The hidden sign-in now presses a visible "Sign in" control once on a page with no form; Aeroplan then reaches a third page with only 7 elements and no form, likely an interstitial. A later attempt submitted, went through 11 pages, and stopped on an Air Canada page that is not the account page: no password box, no choice buttons, no send-code button, three text boxes, one checkbox, and 21 buttons. The shape alone cannot tell a verification step from a signed-out page with a booking form. One hidden sign-in submitted on `www.aircanada.com` and read the balance 25 s later without help, the first automatic Aeroplan sign-in on record. | A capture of the signed-out Air Canada page and of the page after "Sign in", to see what the near-empty page waits for. | Phase 8; carried from [v0.3](../../../archives/v0/v0.3/known-gaps.md) by v0.3.1 on 2026-11-13. |

- **Migrated from**: v0.4.0#DF-12 on 2026-11-20 (reason: user-deferred)
- **Migration evidence**: left open by v0.4.0, the last plan of v0.4

#### DF-13 - Legacy G18

- **Legacy ID**: G18
- **Source phase**: Inherited v0.3 ledger condition; original source is preserved in the exact legacy row below.
- **Plan reference**: docs/archives/v0/v0.3/known-gaps.md (G18); inherited v0.4 legacy snapshot.
- **Reason**: Existing condition carried into v0.4, outside the delivered Global Search change; preserve its original condition and source.
- **Suggested next step**: Follow the original Action or condition in the preserved legacy row.

| G18 | Delta SkyMiles, Marriott Bonvoy, and United MileagePlus have no private capture, so `ExtractorFixtureTest` skips them by name (3 skips). Their selectors are checked by the synthetic fixtures `delta.html`, `marriott.html`, and `united.html`, and by live reads on a device. Live reads were chosen as the evidence over another capture session. | A capture of each balance page from the capture build, if a selector ever needs rework. | Decision; carried from [v0.3](../../../archives/v0/v0.3/known-gaps.md) by v0.3.1 on 2026-11-13. |

- **Migrated from**: v0.4.0#DF-13 on 2026-11-20 (reason: user-deferred)
- **Migration evidence**: left open by v0.4.0, the last plan of v0.4

#### DF-14 - Legacy G19

- **Legacy ID**: G19
- **Source phase**: Inherited v0.3 ledger condition; original source is preserved in the exact legacy row below.
- **Plan reference**: docs/archives/v0/v0.3/known-gaps.md (G19); inherited v0.4 legacy snapshot.
- **Reason**: Existing condition carried into v0.4, outside the delivered Global Search change; preserve its original condition and source.
- **Suggested next step**: Follow the original Action or condition in the preserved legacy row.

| G19 | The WebViews may still send an `X-Requested-With` header naming the app: `WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST` is a restricted API in the current `androidx.webkit` and fails lint `RestrictedApi`, so it is not cleared. Whether the header is absent on a device was never confirmed. | Check one program's request headers on a device, or clear the header once the API is public. | Phase 2; carried from [v0.3](../../../archives/v0/v0.3/known-gaps.md) by v0.3.1 on 2026-11-13. |

- **Migrated from**: v0.4.0#DF-14 on 2026-11-20 (reason: user-deferred)
- **Migration evidence**: left open by v0.4.0, the last plan of v0.4

#### DF-15 - Legacy G20

- **Legacy ID**: G20
- **Source phase**: Inherited v0.3 ledger condition; original source is preserved in the exact legacy row below.
- **Plan reference**: docs/archives/v0/v0.3/known-gaps.md (G20); inherited v0.4 legacy snapshot.
- **Reason**: Existing condition carried into v0.4, outside the delivered Global Search change; preserve its original condition and source.
- **Suggested next step**: Follow the original Action or condition in the preserved legacy row.

| G20 | The Phase 2 Site check tool (`SiteCheckActivity`, debug source set only, absent from release and capture builds) is kept, although plan 10.1 expected it gone: it is the tool for the open site evidence of G05, G06, G14, and G16. | Remove it when those gaps close. | Phase 10; carried from [v0.3](../../../archives/v0/v0.3/known-gaps.md) by v0.3.1 on 2026-11-13. |

- **Migrated from**: v0.4.0#DF-15 on 2026-11-20 (reason: user-deferred)
- **Migration evidence**: left open by v0.4.0, the last plan of v0.4

#### DF-16 - Legacy G21

- **Legacy ID**: G21
- **Source phase**: Inherited v0.3 ledger condition; original source is preserved in the exact legacy row below.
- **Plan reference**: docs/archives/v0/v0.3/known-gaps.md (G21); inherited v0.4 legacy snapshot.
- **Reason**: Existing condition carried into v0.4, outside the delivered Global Search change; preserve its original condition and source.
- **Suggested next step**: Follow the original Action or condition in the preserved legacy row.

| G21 | A real balance drop of more than 90% (spending nearly all points) is never accepted by a background or manual refresh: `checkPlausible` keeps the old balance as `Implausible`, and only an attended sign-in applies it. | Accept a repeated identical low read, or show the new value with a confirm action. | Tier 3 review; carried from [v0.3](../../../archives/v0/v0.3/known-gaps.md) by v0.3.1 on 2026-11-13. |

- **Migrated from**: v0.4.0#DF-16 on 2026-11-20 (reason: user-deferred)
- **Migration evidence**: left open by v0.4.0, the last plan of v0.4

#### DF-17 - Legacy G22

- **Legacy ID**: G22
- **Source phase**: Inherited v0.3 ledger condition; original source is preserved in the exact legacy row below.
- **Plan reference**: docs/archives/v0/v0.3/known-gaps.md (G22); inherited v0.4 legacy snapshot.
- **Reason**: Existing condition carried into v0.4, outside the delivered Global Search change; preserve its original condition and source.
- **Suggested next step**: Follow the original Action or condition in the preserved legacy row.

| G22 | Sign-in edge cases from the Tier 3 hostile review, not reproduced: a refusal the site shows only in a plain element or on the username box is not recognized, so the password may be pressed again and stops as unfinished (blocked on the third); a refusal whose diagnostics callback never returns can end as a retryable timeout without blocking; a standing alert on the page after a sign-in can count as refused; a transient Keystore error deletes the saved sign-in; the opening summary can list a program whose refresh was cancelled. | Watch the diagnostics file for these shapes; fix each with a fixture when one appears on a live site. | Tier 3 review; carried from [v0.3](../../../archives/v0/v0.3/known-gaps.md) by v0.3.1 on 2026-11-13. |

- **Migrated from**: v0.4.0#DF-17 on 2026-11-20 (reason: user-deferred)
- **Migration evidence**: left open by v0.4.0, the last plan of v0.4

#### DF-18 - Legacy G23

- **Legacy ID**: G23
- **Source phase**: Inherited v0.3 ledger condition; original source is preserved in the exact legacy row below.
- **Plan reference**: docs/archives/v0/v0.3/known-gaps.md (G23); inherited v0.4 legacy snapshot.
- **Reason**: Existing condition carried into v0.4, outside the delivered Global Search change; preserve its original condition and source.
- **Suggested next step**: Follow the original Action or condition in the preserved legacy row.

| G23 | Accessibility items needing a device: at 200% font the tile name may shrink to one or two letters and the header title may overlap the centered mark; the monogram text scales with font size inside a fixed badge; turning animations off mid-session is only picked up on the next start; sign-in messages raised outside the dashboard show only on return to it. | Check on a device at the largest font size and fix what shows. | Tier 3 review; carried from [v0.3](../../../archives/v0/v0.3/known-gaps.md) by v0.3.1 on 2026-11-13. |

- **Migrated from**: v0.4.0#DF-18 on 2026-11-20 (reason: user-deferred)
- **Migration evidence**: left open by v0.4.0, the last plan of v0.4

#### DF-19 - Legacy G15

- **Legacy ID**: G15
- **Source phase**: Inherited v0.3 ledger condition; original source is preserved in the exact legacy row below.
- **Plan reference**: docs/archives/v0/v0.3/known-gaps.md (G15); inherited v0.4 legacy snapshot.
- **Reason**: Existing condition carried into v0.4, outside the delivered Global Search change; preserve its original condition and source.
- **Suggested next step**: Follow the original Action or condition in the preserved legacy row.

| G15 | CI cannot run the instrumented suite (`connectedDebugAndroidTest` needs a device) or the private fixture tests (their inputs are gitignored), so both run only on a developer machine. | Run both locally before each release and quote them in the last-phase evidence. | Phase 10; carried from [v0.3](../../../archives/v0/v0.3/known-gaps.md) by v0.3.1 on 2026-11-13. |

- **Migrated from**: v0.4.0#DF-19 on 2026-11-20 (reason: user-deferred)
- **Migration evidence**: left open by v0.4.0, the last plan of v0.4

#### DF-20 - Legacy G25

- **Legacy ID**: G25
- **Source phase**: Inherited v0.3 ledger condition; original source is preserved in the exact legacy row below.
- **Plan reference**: docs/archives/v0/v0.3/known-gaps.md (G25); inherited v0.4 legacy snapshot.
- **Reason**: Existing condition carried into v0.4, outside the delivered Global Search change; preserve its original condition and source.
- **Suggested next step**: Follow the original Action or condition in the preserved legacy row.

| G25 | Task T005 (the capture session) is done, but the completion checker can never mark it met: its plan line ends in `(gitignored)`, which the checker reads as the task's file path. Kept by the owner's decision (2026-11-12) rather than editing the frozen plan. | Future plans name a tracked file as the path of an owner step. | v0.2.0 Phase 2 (QG); carried from [v0.3](../../../archives/v0/v0.3/known-gaps.md) by v0.3.1 on 2026-11-13. |

- **Migrated from**: v0.4.0#DF-20 on 2026-11-20 (reason: user-deferred)
- **Migration evidence**: left open by v0.4.0, the last plan of v0.4

#### DF-21 - Legacy G26

- **Legacy ID**: G26
- **Source phase**: Inherited v0.3 ledger condition; original source is preserved in the exact legacy row below.
- **Plan reference**: docs/archives/v0/v0.3/known-gaps.md (G26); inherited v0.4 legacy snapshot.
- **Reason**: Existing condition carried into v0.4, outside the delivered Global Search change; preserve its original condition and source.
- **Suggested next step**: Follow the original Action or condition in the preserved legacy row.

| G26 | Task T025 (CI/CD reconciliation) names `.github/workflows/ci.yml` as its path; the comparison found no change needed, so no commit touches that file and the checker cannot mark T025 met. | Future plans name the evidence file as the path of a comparison that may change nothing. | v0.2.0 Phase 6 (QG); carried from [v0.3](../../../archives/v0/v0.3/known-gaps.md) by v0.3.1 on 2026-11-13. |

- **Migrated from**: v0.4.0#DF-21 on 2026-11-20 (reason: user-deferred)
- **Migration evidence**: left open by v0.4.0, the last plan of v0.4

#### DF-22 - Legacy G31

- **Legacy ID**: G31
- **Source phase**: Inherited v0.3 ledger condition; original source is preserved in the exact legacy row below.
- **Plan reference**: docs/archives/v0/v0.3/known-gaps.md (G31); inherited v0.4 legacy snapshot.
- **Reason**: Existing condition carried into v0.4, outside the delivered Global Search change; preserve its original condition and source.
- **Suggested next step**: Follow the original Action or condition in the preserved legacy row.

| G31 | A benefit reminder opens its card page by starting the app fresh (the intent clears the task), because `MainActivity` does not pass new intents on and was outside v0.2.0's write paths. | Handle `onNewIntent` in `MainActivity` and route to the card page without a restart. | v0.2.0 Phase 5 (NI); carried from [v0.3](../../../archives/v0/v0.3/known-gaps.md) by v0.3.1 on 2026-11-13. |

- **Migrated from**: v0.4.0#DF-22 on 2026-11-20 (reason: user-deferred)
- **Migration evidence**: left open by v0.4.0, the last plan of v0.4

#### DF-23 - Legacy G33

- **Legacy ID**: G33
- **Source phase**: Inherited v0.3 ledger condition; original source is preserved in the exact legacy row below.
- **Plan reference**: docs/archives/v0/v0.3/known-gaps.md (G33); inherited v0.4 legacy snapshot.
- **Reason**: Existing condition carried into v0.4, outside the delivered Global Search change; preserve its original condition and source.
- **Suggested next step**: Follow the original Action or condition in the preserved legacy row.

| G33 | Period labels near their edges: a single-month label read in the next month (for example "Dec" read on Jan 2) resolves to the next December, and yearly labels ("Annual") are understood but the page script reports only month labels. Tiles skip ended periods and reminders need a period end within the lead time, so no wrong reminder results. | Adjust once a live capture shows such a label. | v0.2.0 Phase 6 deep pass (WN); carried from [v0.3](../../../archives/v0/v0.3/known-gaps.md) by v0.3.1 on 2026-11-13. |

- **Migrated from**: v0.4.0#DF-23 on 2026-11-20 (reason: user-deferred)
- **Migration evidence**: left open by v0.4.0, the last plan of v0.4

#### DF-24 - Legacy G34

- **Legacy ID**: G34
- **Source phase**: Inherited v0.3 ledger condition; original source is preserved in the exact legacy row below.
- **Plan reference**: docs/archives/v0/v0.3/known-gaps.md (G34); inherited v0.4 legacy snapshot.
- **Reason**: Existing condition carried into v0.4, outside the delivered Global Search change; preserve its original condition and source.
- **Suggested next step**: Follow the original Action or condition in the preserved legacy row.

| G34 | During an all-programs refresh on 2026-11-12, Marriott Bonvoy and Hilton Honors stopped as `Timed out`; one or two manual refreshes then read them. A timed-out read is not retried by itself, and the owner expects it to be. | Read the diagnostics lines of the next such stop, then retry a timed-out read once within the same refresh if the cause is transient. | v0.3.0 Phase 1 owner session (BG); carried from [v0.3](../../../archives/v0/v0.3/known-gaps.md) by v0.3.1 on 2026-11-13. |

- **Migrated from**: v0.4.0#DF-24 on 2026-11-20 (reason: user-deferred)
- **Migration evidence**: left open by v0.4.0, the last plan of v0.4

#### DF-25 - Legacy G35

- **Legacy ID**: G35
- **Source phase**: Inherited v0.3 ledger condition; original source is preserved in the exact legacy row below.
- **Plan reference**: docs/archives/v0/v0.3/known-gaps.md (G35); inherited v0.4 legacy snapshot.
- **Reason**: Existing condition carried into v0.4, outside the delivered Global Search change; preserve its original condition and source.
- **Suggested next step**: Follow the original Action or condition in the preserved legacy row.

| G35 | A texted code that MileStone reads (Air Canada, 2026-11-12) stays in the phone's Messages. Android lets only the default SMS app delete texts, and MileStone reads them through the incoming-text broadcast, so it cannot delete them. | Decide between leaving the texts, offering a notification that opens Messages, or another code source; deletion itself needs MileStone to be the default SMS app. | v0.3.0 Phase 1 owner session (NI); carried from [v0.3](../../../archives/v0/v0.3/known-gaps.md) by v0.3.1 on 2026-11-13. |

- **Migrated from**: v0.4.0#DF-25 on 2026-11-20 (reason: user-deferred)
- **Migration evidence**: left open by v0.4.0, the last plan of v0.4

#### DF-26 - Legacy G38

- **Legacy ID**: G38
- **Source phase**: Inherited v0.3 ledger condition; original source is preserved in the exact legacy row below.
- **Plan reference**: docs/archives/v0/v0.3/known-gaps.md (G38); inherited v0.4 legacy snapshot.
- **Reason**: Existing condition carried into v0.4, outside the delivered Global Search change; preserve its original condition and source.
- **Suggested next step**: Follow the original Action or condition in the preserved legacy row.

| G38 | The capture build no longer saves a balance after a read: to keep the window open it holds back the read events, because `SignInViewModel` saves and closes in one step and is outside v0.3.0's write paths. The capture build is uninstalled after each session, so no stored data depends on it. | Split the save from the close in `SignInViewModel` in a plan that owns `ui/auth/`. | v0.3.0 Phase 1 T001 (NI); carried from [v0.3](../../../archives/v0/v0.3/known-gaps.md) by v0.3.1 on 2026-11-13. |

- **Migrated from**: v0.4.0#DF-26 on 2026-11-20 (reason: user-deferred)
- **Migration evidence**: left open by v0.4.0, the last plan of v0.4

#### DF-27 - Legacy G41

- **Legacy ID**: G41
- **Source phase**: Inherited v0.3 ledger condition; original source is preserved in the exact legacy row below.
- **Plan reference**: docs/archives/v0/v0.3/known-gaps.md (G41); inherited v0.4 legacy snapshot.
- **Reason**: Existing condition carried into v0.4, outside the delivered Global Search change; preserve its original condition and source.
- **Suggested next step**: Follow the original Action or condition in the preserved legacy row.

| G41 | Leaving MileStone during a refresh stops it: on 2026-11-12 a refresh whose balance was already read lost its benefits read after 16.8 s (`read=0 ... stop=budget`), and another stopped right after the automatic sign-in; the tile then showed the old update time with no message. | Keep a started refresh running when the app goes to the background (a foreground service or WorkManager work that owns the hidden WebView), or show that it stopped and offer to resume. | v0.3.0 Phase 4 owner check (BG); carried from [v0.3](../../../archives/v0/v0.3/known-gaps.md) by v0.3.1 on 2026-11-13. |

- **Migrated from**: v0.4.0#DF-27 on 2026-11-20 (reason: user-deferred)
- **Migration evidence**: left open by v0.4.0, the last plan of v0.4

#### DF-28 - Legacy G46

- **Legacy ID**: G46
- **Source phase**: Inherited v0.3 ledger condition; original source is preserved in the exact legacy row below.
- **Plan reference**: docs/archives/v0/v0.3/known-gaps.md (G46); inherited v0.4 legacy snapshot.
- **Reason**: Existing condition carried into v0.4, outside the delivered Global Search change; preserve its original condition and source.
- **Suggested next step**: Follow the original Action or condition in the preserved legacy row.

| G46 | An earlier full emulator run failed in BridgeFixtureTest teardown: ActivityScenario.close could not reach DESTROYED and last saw PAUSED. The exact case passed alone; no root cause is confirmed. After cold-booting only the unresponsive assigned emulator, the current full suite passes 35/35 without skips or suppression. | Owner: Android test infrastructure maintainer. Retain this historical stability warning; if it recurs, capture lifecycle callbacks and UI-thread state in the full-order run before changing timeouts or suppressing errors. The current local gate is green, but recovery alone does not prove a root-cause fix. | v0.3.0 Phase 4 T025, final-validation.json (WN); carried from [v0.3](../../../archives/v0/v0.3/known-gaps.md) by v0.3.1 on 2026-11-13. |

- **Migrated from**: v0.4.0#DF-28 on 2026-11-20 (reason: user-deferred)
- **Migration evidence**: left open by v0.4.0, the last plan of v0.4

#### DF-29 - Chase live navigation and timing unproven

- **Source phase**: v0.5.0 Phase 3 (T007-T009)
- **Plan reference**: docs/releases/v0/v0.5/plans/v0.5.0-chase-benefits.md, Definition of Done (live Chase reads within the 120000 ms budget)
- **Reason**: The Chase reader passes the synthetic fixture on the emulator and counts all 8 private captured pages (252 rows), but the live path (dashboard card entry, More, Benefits, card selector sheet) and its timing have not run against the real site. The traversal-timing review was superseded without a live run.
- **Suggested next step**: Owner live acceptance on the phone: three Chase refreshes, each read logged within 120000 ms with count-only lines; on a stop, compare the logged page kind and reason with the captured structure before changing selectors.

#### DF-30 - Extra sign-in success depends on the Rewards balance

- **Source phase**: v0.5.0 Phase 4 (T010)
- **Plan reference**: docs/releases/v0/v0.5/development/history/2026-11-20-v0.5.0-phase-4-personal-business.md, Plan delta
- **Reason**: An extra Chase sign-in is recorded as signed in when the site shows the Chase Rewards balance. A business-only login might not show it, and the sign-in would then stay "not signed in".
- **Suggested next step**: Owner adds a second (business) sign-in during live acceptance; if it stays "not signed in", record success on reaching the signed-in dashboard instead.

#### DF-31 - Card-selector option ids are masked in captures

- **Source phase**: v0.5.0 Phase 3 (T008)
- **Plan reference**: docs/releases/v0/v0.5/development/planning/chase-capture-structure-20261120.json
- **Reason**: The sanitizer masks the selector sheet's radio ids with '#', so the private-capture test checks id presence only. The reader binds cards through an HMAC of the option id, which assumes the live id is stable across visits.
- **Suggested next step**: During live acceptance, refresh twice and confirm the same cards keep their benefits (no duplicate cards); if cards duplicate, bind on the card name and ending instead.
- **Mitigation in v0.5.0**: a complete list that shares no card with a sign-in's coverage now changes nothing (`InventoryOutcome.UNMATCHED`), so changing ids cannot delete every card. The cost is that an account whose cards are all genuinely replaced at once keeps the old cards until a later list shares one.

#### DF-32 - Welcome offers are not classified from Chase text

- **Source phase**: v0.5.0 Phase 5 (T019 Goal-vs-codebase review)
- **Plan reference**: docs/releases/v0/v0.5/plans/v0.5.0-chase-benefits.md, Goal ("welcome information") and T008
- **Reason**: Chase rows map to listed benefits and the progress bar to a spend tracker; nothing marks a row or tracker as the welcome offer. The plan allows manual welcome setup where the source does not expose it, and that path is unchanged.
- **Suggested next step**: During live acceptance, note whether Chase labels the welcome offer; if it does, classify that wording as the welcome tracker.

#### DF-33 - No way to link two cards the app keeps apart

- **Source phase**: v0.5.0 Phase 5 (T019)
- **Plan reference**: ADR-0009 identity rules; T004
- **Reason**: When a binding is ambiguous, the app keeps cards separate rather than merging them, and there is no user action to link them.
- **Suggested next step**: Add a "Same card as" action only if the owner sees a duplicate during live acceptance.

#### DF-34 - Any phone or number field on the path ends the read as signed out

- **Source phase**: v0.5.0 Phase 5 (T018 adversarial pass)
- **Plan reference**: Phase 3 security review (identity-check stop)
- **Reason**: The script stops on any visible tel or number input, or a radio outside the selector, to avoid answering an identity check. A quick-pay amount field or a survey on the dashboard would make every Chase read stop as signed out. No data is lost.
- **Suggested next step**: If live reads stop as signed out while the site is signed in, narrow the check to code fields and radios inside dialogs or forms.

#### DF-35 - The first sign-in returns as Personal after it is signed out

- **Source phase**: v0.5.0 Phase 5 (T018)
- **Plan reference**: T005, T010
- **Reason**: Signing out the first Chase sign-in while extra sign-ins remain deletes its connection row; it is recreated as Personal on the next sync, so a Business declaration must be set again.
- **Suggested next step**: Keep the row and its declaration and clear only its coverage.

#### DF-36 - Chase timing beyond the fixtures

- **Source phase**: v0.5.0 Phase 5 (T018)
- **Plan reference**: Owner Generalization Requirement (21+ cards, lazy inventories)
- **Reason**: A selector with more than 20 cards is posted as incomplete (no removals) and not tested; a ten-card account that reloads per card read in 25.4 s on the emulator, but larger accounts may reach the 120000 ms budget, leaving later cards unread until the next refresh.
- **Suggested next step**: Add a 21-card fixture case; read the live per-card time from count-only logs during acceptance.

### Resolved

| ID | Title | Resolved in | Notes |
|---|---|---|---|
