# Benefits Reader

The Amex sections below describe the American Express read; the Chase sections describe the Chase read ([ADR-0009](../decisions/0009-chase-benefits.md)). The Amex benefits read starts after a saved American Express balance, in the same program profile. It reads cards and benefit sections from the site's existing controls. It never signs in, composes an account-key URL, or fetches account data from another service.

## Page Kinds

| Page | Role | Successful result |
|---|---|---|
| Rewards | Lists the cards and starts a card read | Follows the card's activity link, or posts trackers before following its view-all link |
| Activity | Holds the full tracker set and list for cards that offer it | Stores every covered section for that card |
| View-all | Holds the full list for cards without an activity page | Joins the list to the Rewards trackers |

Navigation presses the page's own link or card-switcher option. The app accepts benefits messages only on the configured Amex host and page paths, with the expected message nonce and page kind.

## Readiness

The script scrolls to load lower sections. A shape is steady when tracker count, listed-link count, and page height stay unchanged for two seconds. A page with no trackers gets a longer empty settle.

- Rewards requires an activity or view-all link, a reached bottom, and settled content. At its 15-second readiness cap, an available activity link is still followed. A missing lower section may wait up to the supplied page timeout, capped at 20 seconds.
- Activity requires its own content: the Rewards activity link must be absent, and at least one tracker or listed link must exist. Rewards content under an activity address is never accepted as an activity read.
- View-all requires listed content whose count differs from the page it was opened from, then bottom and settle checks. A capped empty list does not erase the previous list.

Before pressing a link, the script leaves a document-local handoff note. An in-place address change adopts that note; a full document load drops it. After pressing an activity link, the script waits for navigation or a bounded unreadable result instead of posting partial Rewards trackers.

## Card Switching

The selected card must match the target, and its tracker fingerprint must be nonempty and different from the previous card's. The fingerprint stays inside the script and is never posted. An emptied tracker area is not proof that the new card has loaded.

The switch cap is 12 seconds. A settled empty tracker area with the right markers may become an empty read after that cap. Old trackers still on screen produce a mismatch, preserving the stored card rather than assigning another card's trackers to it.

## Run Budget

One deadline covers the whole 120-second benefits read, with margins for script and app cleanup. Each page gets at most 20 seconds and cannot extend the shared deadline.

At a card's first page, the scheduler reserves 20 seconds for its possible second page and 20 seconds for every remaining first page, including the current one. If that time is unavailable, it cuts second pages for this and all later cards. If fewer than 10 seconds remain for another card to start, it leaves that card unread. These cuts preserve stored sections that were not reached.

The 20-second first-page reserve includes a late Rewards lower section. Reserving only 15 seconds could spend time on an earlier activity page and leave the last card unread.

## Storing a Read

`BenefitsCardReads` records which sections a card's pages covered. Rewards alone covers tracker sections. A successful view-all adds the listed section. An activity page covers every section. A failed or mismatched view-all keeps the new trackers and the previous stored list.

`SyncManager` sends those section sets to `RoomCardRepository`. The repository deletes and replaces only those sections in a transaction under the program lock. A failed card keeps its previous benefits. Cards disappear only when a complete card list no longer contains them.

## Display and Freshness

Completed credit tiles render full progress with the earned/target ratio inline beside the bar. Their display-only target uses a positive stored target first, then a positive stored earned amount, then a single leading dollar amount in the completed credit title. A title with multiple dollar amounts or no usable leading amount supplies no inferred target. The display does not rewrite issuer amounts or stored rows. Unfinished credits retain actual progress and their inline ratio; noncredit trackers retain their existing amount labels. Enrolled appears beside every enrolled item's title, before the completed check when present. Enrollment required remains a body status.

Issuer sections remain the replacement boundary; display categories do not change stored rows. The card's successful-write generation increments inside the same Room transaction as section replacement. After that write, SyncManager records covered section generations in DataStore, bound to the exact Room generation and read time. If the outcome write is unavailable or cancelled, the UI labels list freshness unknown; equal timestamps cannot reuse an earlier generation. Tracker-only success preserves both old listed rows and their older source generation.

The presentation transform retains the original enrollment source when it merges a listed row into a tracker. Alert identity and acknowledgement use that source row and successful section generation, so a changed tracker period cannot revive acknowledged enrollment from an unchanged list. Separate local keys retain sort, collapse, welcome setup, and acknowledgement changes under concurrent writes. The version 3 to 4 migration adds the counter without deleting existing data.

## Chase Path

The Chase read starts after a saved Chase balance and visits each Chase sign-in in its own WebView profile, within the shared 120-second deadline. A sign-in starts only with 20 seconds left, and a card only with 8 seconds left. The page script is re-entrant on every page of the path:

- Dashboard: press the first card entry, skipping forms, dialogs, and payment or transfer controls.
- Card page: press More, then Benefits. A missing More control ends the sign-in as unreadable with no card list.
- Benefits: open the card selector, post the listed cards (name, ending, and which one is selected) once the list has stayed unchanged for 2 seconds, then read the selected card's benefit groups, rows (title, tag, value), and welcome tracker.

For the next card, the app sends the target option and the script presses it in the selector, at most once every 1.5 seconds, and waits for the page to change before reading. A handoff note survives single-page route changes. The script stops on any code, phone, or number field or a radio outside the selector (an identity check) and never chooses a delivery option. A page that keeps reloading stops the sign-in: the limit is 8 page loads plus 2 per listed card, so a site that reloads per card still finishes. After the card list, that stop is a budget stop, and unread cards keep their stored benefits; later sign-ins still start while the shared deadline allows. A card page with more than 30 groups, 150 rows, or 20 trackers is reported unreadable (overflow) and keeps its stored benefits instead of being stored partially.

`ChaseBenefitsReader` turns rows into listed benefits and the tracker into a spend tracker, taking amounts, periods, and status only from stated text. A tracker's earned amount comes from "$X of $Y" wording or from a progress bar whose maximum is the stated target; "Earn $300 after you spend $3,000" is a reward and a target, not progress. Half-year wording such as "every six months" is read before monthly wording. Oversized or malformed payloads are rejected.

## Chase Sign-ins and Coverage

The first Chase sign-in uses the program's own profile and owns the Rewards balance. Up to three more sign-ins (`chase_2` and up) each get an isolated profile and never write a balance; a phone that cannot isolate profiles refuses them. Each sign-in is declared Personal or Business.

Cards are bound by an HMAC-SHA256 of their selector option under a per-install key in `noBackupFilesDir`; the stored card gets an opaque `c_` id. Each sign-in's card list updates only its own coverage rows. Only a complete list removes coverage, and a card is deleted only when no sign-in reaches it. Partial, empty, stale, duplicate, or unknown lists change nothing, and so does a complete list that shares no card with the sign-in's existing coverage: that looks like the selector's ids changing, and applying it would delete every card with its benefits, preferences, and reminder history. Removing Chase signs out every additional sign-in before its records are deleted. A card's segment is Personal when a Personal sign-in reaches it, otherwise Business; the user's choice from the card page's Show under menu wins and survives refreshes. A card already read through an earlier sign-in is not read again in the same refresh.

## Diagnostics

Benefits diagnostics hold fixed page kinds, bounded counts, readiness reasons, wait times, card indexes, and the run total. They do not hold benefit text, amounts, card digits, account keys, page queries, or sign-in values. Card outcomes distinguish read, unread-list, and mismatch results; a read outcome alone does not prove that every benefit was loaded.

Card freshness alone does not prove that its list was refreshed: trackers can be new while a failed list keeps its older contents. The visible per-list freshness note is tracked separately from the extraction fix.

## Verification

Synthetic WebView fixtures exercise late lower sections, in-place address changes, card switching, view-all navigation, and failed-list preservation. The Chase fixture covers the card, More, Benefits path, a second sign-in, a missing More control, a payment control, an identity check, a sign-in without a session, a ten-card account that reloads the page per card, a selector that lists its cards in two batches, and a card with 151 rows. JVM tests cover the protocol, budget, card-read transitions, and real Room section replacement. Private-capture tests run locally, and their contents are never committed or printed.

Instrumented tests run on the emulator only. Running them on the owner's phone uninstalls the app and clears sign-ins. Live phone verification uses `adb install -r`, keeps the app in the foreground, and records only per-card section counts, enrollment counts, diagnostics, and read times. The database copy used for counts is deleted immediately afterward.

## Limits

The site can change or load too slowly for the budget. The read preserves sections explicitly left unread, while a capped page can contain only the content loaded before its cap. Card freshness alone is not proof of completeness. Background continuation and automatic retry policy remain separate work. The Benefits display supports card-specific sorting, grouping and collapse, conservative duplicate presentation, local welcome-bonus setup, and yellow acknowledgeable alerts; these do not extend the extraction deadline. The Chase path, its timing, and the stability of selector options are proven on fixtures and private captures, not yet on the live site. The architecture decisions are [ADR-0008](../decisions/0008-amex-benefits-card-navigation.md) and [ADR-0009](../decisions/0009-chase-benefits.md).
