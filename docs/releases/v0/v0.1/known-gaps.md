# v0.1 Known Gaps

**Status**: Closed. v0.1.0 shipped; every open gap was carried to [v0.2 known gaps](../v0.2/known-gaps.md) with its ID unchanged.

**Open items**: 0

| Status | Count |
|---|---|
| Open | 0 |
| Carried to v0.2 | 19 |
| Fixed | 4 |

## Carried to v0.2

G01, G02, G03, G04, G05, G06, G08, G11, G12, G14, G15, G16, G17, G18, G19, G20, G21, G22, G23. Each keeps its description, action, and source in [v0.2 known gaps](../v0.2/known-gaps.md).

## Fixed

| ID | Gap | Fix |
|---|---|---|
| G07 | Marriott Bonvoy did not sync: pages after sign-in did not load in MileStone's browser. | The zero-viewport-height fix in `WebViewConfig` and the account-panel read (`MARRIOTT_CSS`, fixture `panel-balance.html`). Confirmed on a device: Marriott added and synced with no browser. |
| G09 | Automatic sign-in relied on page heuristics (form fields, "remember this device" prompts) not yet confirmed on each bank's live pages. | Confirmed on a device: the locked-phone keep-alive signed in to Amex and Chase again by itself after their sessions ended and read all three cards, including another automatic Amex sign-in. |
| G13 | Chase ended its session within about 20 minutes, so the keep-alive signed in to Chase on nearly every run while the phone was unattended. | [ADR-0005](../../../decisions/0005-sign-in-only-on-open-with-stop-reasons.md): the keep-alive no longer types passwords; Chase signs in only when MileStone is opened. |
| G10 | Chase reported "Timed out" when its session had ended, because its sign-in box sits in a same-origin frame. | `extract.js` searches same-origin frames; Chase now shows Reconnect (verified on a device). |
