# ADR-0008: Amex Benefits Read, Card Navigation, and Benefit Pages

- **Status**: Accepted
- **Date**: 2026-11-12
- **Template**: MADR
- **Origin**: v0.2.0 plan, task 2.2 (T006), after the owner capture session
- **Supersedes**: -
- **Superseded by**: -

## Context and Problem Statement

The Benefits tab needs every card on the Amex account and each card's benefits. The v0.2.0 plan assumed one benefits page per program and asked how the read moves between cards. The capture session (7 sanitized pages from 4 cards, 2026-11-12) showed:

- Each page has a card switcher (`simple_switcher_combobox`). When opened, its list (`simple_switcher_listbox`) holds an "All accounts" entry and one option per card, each with a name (`simple_switcher_display_name`) and 5 last digits (`simple_switcher_display_number_val`). The closed switcher shows the current card the same way. Options carry no link; internally Amex keys each card by a 15-character account key, which also appears in option test ids.
- Every card-specific page address carries `account_key` in its query.
- Cards come in two shapes. Platinum and Business Platinum have a benefits activity page (`/card-benefits/activity`) with every tracker, grouped under "Spend Progress", "Credit Earned", and "Completed Benefits" headings, plus a "Browse benefits" tab holding the full benefits list with "Enrolled" or "Enrollment Required" ribbons. Their Rewards page shows only 2 of about 15 trackers. Hilton Surpass and Gold Delta SkyMiles have no activity page; their Rewards page (`/rewards`) shows all of their trackers under "Track Your Progress" and only one featured benefit, not a list.
- Trackers share one component on both pages: `container-<name>` (role group), `heading-<name>`, a `progress` element with `max`, `tracker-spent-amount` followed by a word and an optional period label ("Oct - Dec", "November"), a `tracker-remaining-section` ("$NN to go"), and `icon-check-*` on a completed item. A "Congratulations!" container is a yearly summary, not a benefit.
- The owner recalls a page load after picking a card, but is not certain.

## Decision Drivers

- The app never reads, stores, logs, or sends an account key or a full card number (plan privacy rules).
- Reading must cover every card on the account and every benefit Amex shows for it.
- One read must keep working whether a switch loads a new document or changes the page in place.

## Considered Options

1. **Press the switcher option whose last digits match the next card, then read the page it lands on; follow the page's own benefits-activity link when the card has one (chosen).**
2. **Build each card's address from the account key read from the page (plan fallback B).** Rejected: the key would pass through the page script and the app, and option 1 works without it.
3. **Read only the card the page shows.** Rejected: the account has 4 cards and the plan's Goal needs all of them.

## Decision Outcome

Chosen: option 1.

- The benefits read opens `https://global.americanexpress.com/rewards` in the program's own profile. The page script opens the switcher, reports the card list (name and last digits only), and closes it.
- To reach another card, the app sends the target last digits; the script presses the matching option. If the page reloads, the next document's script receives the same target and continues; if it changes in place, the script watches the switcher's selected digits until they match. A switch that does not land on the target card within its budget fails that card only.
- On a card's page, when a link to `/card-benefits/activity` exists, the script presses it and reads the activity page; otherwise it reads the Rewards page's trackers. The script presses existing links and options only; it never reads or composes an address with a key.
- The app accepts card and benefit messages only from `global.americanexpress.com` with path `/rewards` or `/card-benefits/activity`.
- The script reads named elements only: tracker containers, the list entries in the Browse benefits tab (title, short description, ribbon, and the benefit's slug from its `/card-benefits/detail/<slug>/` link path), and the switcher's name and digits fields. Before posting it strips grouped digit runs of 6+ digits, tokens of 8+ characters with 4+ digits, and runs of 10+ uppercase letters and digits that contain a digit.

## Consequences

- The plan's single "benefits page" becomes two accepted paths; recorded as a plan delta in the Phase 2 history.
- The owner asked to keep every benefit, so the read also stores the full benefits list with its enrollment status where Amex shows one (Platinum and Business Platinum). Hilton Surpass and Gold Delta SkyMiles show trackers only; a capture of their "View all benefits" page, if Amex offers one, is a v0.2 known gap.
- Pressing a switcher option changes which card the site shows in that profile; the balance read is unaffected because it reads the account-level points balance.
- The capture build's "keep the window open after a read" and address stamp were used as an uncommitted build for this session; a permanent option is a v0.2 known gap.

## Clarification 2026-11-12: page-ready rules, switch invariant, and run deadline

Origin: v0.3.0 plan, task 2.1 (T006), from the [v0.3.0 capture record](../releases/v0/v0.3/development/v0.3.0-capture-record.md).

**What the live diagnostics showed.** Amex moves from Rewards to the activity page, and from one card to another, by changing the address inside the same document. The page script restarts on each address change while the previous content stays on screen for several seconds. The v0.2 rule ("trackers steady for 2 s") therefore read the Rewards page's 2 trackers under the activity address (`kind=activity trackers=2 activity=true`) and lost the fact that a switch had happened (`switched=false` on switched cards). The decision's assumption that the script watches an in-place switch to completion within one run does not hold.

**Page kinds by path.** The script classifies the page by its path prefix, from three spec values: `activityPath` (`/card-benefits/activity`), `viewAllPath` (`/card-benefits/view-all`), and `rewardsPath` (`/rewards`), checked in that order. Any other accepted path posts `benefitsUnreadable` with `why=page`. The app derives the same kind from the page address and rejects a message whose `page` or diagnostics `kind` differs, and any Rewards message that carries listed benefits.

**Ready rules.** A page counts as steady when its tracker count, listed-link count, and page height are unchanged for 2 s.

| Kind | Required marker | Bottom reached | Settle | Cap |
|---|---|---|---|---|
| rewards | a view-all or activity link (every captured Rewards page has one; it sits in the scroll-loaded lower section) | yes | 2 s steady; 6 s when it has no trackers | 15 s |
| activity | no activity link (only the Rewards content has one) and at least one tracker or listed link | yes | 2 s steady | 20 s |
| viewAll (Phase 3) | at least one listed link, and the listed count differs from the page it was opened from | yes | 2 s steady | 20 s |

A ready Rewards page follows its activity link when it has one and the app allows a second page; otherwise it posts its own trackers. At its cap a Rewards page posts what it has with `reason=cap`. At its cap an activity page posts with `reason=cap` only when its marker holds; while it still shows the Rewards content it posts `benefitsUnreadable` (`reason=timeout`), so Rewards content is never stored as the activity page.

**Handoff across the restart.** Before pressing a link or a switcher option, the script notes, in its own document-scoped memory (not page storage), what it pressed, the target card, and a fingerprint of the trackers on screen (titles and amounts, never posted). The next run on the same document adopts a note younger than 30 s. A full document load drops the note, which is safe because a new document holds no old content.

**Switch invariant.** After a switch, whether watched in one run or adopted from a note, the page is ready only when the selected card's digits match the target and the tracker fingerprint is non-empty and different from the one before the switch. An emptied tracker area is not ready. After the 12 s switch cap: with the markers present and the tracker area steady and empty, the page posts an empty read with `reason=cap`; with the old fingerprint still on screen it posts `benefitsUnreadable` with `reason=switchMismatch`, so the old card's trackers are never stored under the new card. Two cards whose trackers match in title and amount therefore fail that card rather than risk a wrong read.

**Run deadline.** The source keeps one deadline for the whole read (`BENEFITS_TIMEOUT_MS` minus a 2 s margin) and sends each page `min(20 s, time left minus 2 s)`; the script also applies the cap of the page's kind. The measured worst case (card list plus 4 cards, 2 pages each; 7 to 16 s per card, at most 12.4 s per page) is about 60 s, so it fits. When time runs short the read cuts in this order, logging each cut:

1. When a card's Rewards page starts, its second page is allowed only while the time left covers 20 s for that page plus 15 s for this card's first page and for each card still waiting after it; otherwise that card's Rewards page posts its own trackers, and every later card also reads its first page only.
2. A new card starts only while at least 10 s remain; otherwise the read ends with the remaining cards unread, so their stored benefits stay.

**Rejected alternative (B).** One fixed longer settle for every page was rejected: the activity page's marker (no activity link) and the Rewards page's marker (a view-all or activity link) were present in every capture, and a longer settle alone would still read the Rewards content under the activity address, because that content stays steady for longer than any reasonable settle.
