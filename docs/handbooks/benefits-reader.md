# Amex Benefits Reader

The benefits read starts after a saved American Express balance, in the same program profile. It reads cards and benefit sections from the site's existing controls. It never signs in, composes an account-key URL, or fetches account data from another service.

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

Issuer sections remain the replacement boundary; display categories do not change stored rows. The card's successful-write generation increments inside the same Room transaction as section replacement. After that write, SyncManager records covered section generations in DataStore, bound to the exact Room generation and read time. If the outcome write is unavailable or cancelled, the UI labels list freshness unknown; equal timestamps cannot reuse an earlier generation. Tracker-only success preserves both old listed rows and their older source generation.

The presentation transform retains the original enrollment source when it merges a listed row into a tracker. Alert identity and acknowledgement use that source row and successful section generation, so a changed tracker period cannot revive acknowledged enrollment from an unchanged list. Separate local keys retain sort, collapse, welcome setup, and acknowledgement changes under concurrent writes. The version 3 to 4 migration adds the counter without deleting existing data.

## Diagnostics

Benefits diagnostics hold fixed page kinds, bounded counts, readiness reasons, wait times, card indexes, and the run total. They do not hold benefit text, amounts, card digits, account keys, page queries, or sign-in values. Card outcomes distinguish read, unread-list, and mismatch results; a read outcome alone does not prove that every benefit was loaded.

Card freshness alone does not prove that its list was refreshed: trackers can be new while a failed list keeps its older contents. The visible per-list freshness note is tracked separately from the extraction fix.

## Verification

Synthetic WebView fixtures exercise late lower sections, in-place address changes, card switching, view-all navigation, and failed-list preservation. JVM tests cover the protocol, budget, card-read transitions, and real Room section replacement. Private-capture tests run locally, and their contents are never committed or printed.

Instrumented tests run on the emulator only. Running them on the owner's phone uninstalls the app and clears sign-ins. Live phone verification uses `adb install -r`, keeps the app in the foreground, and records only per-card section counts, enrollment counts, diagnostics, and read times. The database copy used for counts is deleted immediately afterward.

## Limits

The site can change or load too slowly for the budget. The read preserves sections explicitly left unread, while a capped page can contain only the content loaded before its cap. Card freshness alone is not proof of completeness. Background continuation and automatic retry policy remain separate work. The Benefits display supports card-specific sorting, grouping and collapse, conservative duplicate presentation, local welcome-bonus setup, and yellow acknowledgeable alerts; these do not extend the extraction deadline. The architecture decision is [ADR-0008](../decisions/0008-amex-benefits-card-navigation.md).
