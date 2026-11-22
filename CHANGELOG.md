# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [0.5.1] - 2026-11-21

### Changed

- Cards the product catalog recognizes show the catalog's product name, shortened to the product ("Platinum", "Sapphire Preferred", "Ink Business Preferred"); other cards show the issuer's name without the issuer's own words or a trailing "Card", in capitalized words.
- A card Chase lists as "CHASE SAPPHIRE" is matched to Sapphire Preferred; the separate classic Sapphire entry is removed, leaving 69 catalog products.
- Pages change with a sideways fade: the page being left fades out in the swipe's direction before the next fades in, so two pages never show together.

### Added

- Below a tab's first page, swiping right goes back one page and follows the finger; swiping left reopens the page last left.
- During a Chase refresh, MileStone opens each credit's own page, reads how much of the credit is used, and goes back, at most 8 credits per card. The card's log line counts the credit pages planned and the used amounts read.

### Fixed

- A finished Amex tracker that Amex retitles "Congratulations!" (such as the Hilton Honors Surpass free night) is kept and named from the card's benefit list or its own wording, instead of being dropped as the yearly summary.
- An Amex Rewards page that keeps growing now still opens the card's view-all list at its readiness cap when trackers are on screen, so the list refreshes and the "Listed benefits did not refresh" alert clears.
- Chase spend trackers read their progress from the gauge's accessible text and take their title from the tile heading; a row's two-part value reads "$500 - Up to $250 per transaction", and listed credits appear under Credits.
- The Rewards tab returns to the Rewards page from a card page, and the Benefits tab opens its program page.
- Card artwork is clipped in proportion to its size, so no gray image corner or light edge shows on the card page.

## [0.5.0] - 2026-11-21

### Added

- Chase card benefits: after a saved Chase balance, MileStone reads each card's benefits, credits, and spend trackers from Chase's own Benefits page through every Chase sign-in, within the shared 120-second budget, and never signs in, presses a payment control, or answers an identity check.
- Up to four isolated Chase sign-ins, added as Personal or Business from a sign-ins sheet on the Chase page; extra sign-ins never write a balance, and signing one out removes only the cards no other sign-in reaches.
- Cards keep an opaque local id bound across sign-ins, so the same card seen by two sign-ins is stored once and two cards with the same ending stay distinct in tiles, Search, and reminders.
- Personal and Business sections, each collapsible, on every benefits program's page. A card's section follows the owner's Show under choice, a product name that says Business, a public list of 70 Chase and Amex products, the Chase home page's own grouping, and finally the sign-ins that reach it.
- Bundled offline artwork for the listed products; any other card shows a drawn card in the issuer's color with its ending.
- Rewards category and type headings collapse and expand.
- Swiping an alert to the right acknowledges it.

### Changed

- Benefits shows one Chase tile, with compact card tiles (artwork, name with an alert badge, ending and benefit count, last update) without the next-credit line.
- Program tiles show their alert count as a small corner badge, and a card's alerts appear as slim rows below its artwork with Acknowledge on the right.

### Fixed

- A benefits read cancelled because MileStone left the screen starts again when MileStone returns, even when no balance is due; its log line now says cancelled instead of budget.
- The Chase path waits for a menu that opens in place, presses Chase's components through their own buttons, matches labels kept in attributes, allows 25 seconds for the Benefits page, and restarts once from the dashboard, with count-only diagnostics when a step fails.

## [0.4.0] - 2026-11-19

### Added

- On-device search from a top-right icon finds added programs and their stored cards and displayed benefits, including contextual queries such as Delta lounge.
- Rewards Search shows program images and stored points. Benefits results use distinctly themed program headings with logos, compact clickable card headers with product images, independent chevrons, and single-line items in canonical section order.
- Program-only queries such as Amex include all stored card benefits; mixed contextual queries still require an item token.
- Exact result navigation highlights the destination temporarily; Back restores the query, selected tab, scroll and disclosure state. Search requests no issuer refresh or sign-in, and a new process starts with a blank query.

### Changed

- Completed credit tiles retain a full progress bar and target amount. Credit amounts sit beside progress, and Enrolled appears beside the title before the completion check.
- The pull-request gate includes the repository-native full validation profile and API 36 emulator execution before the required CI result.
- Local validation exports source-bound test, coverage and security reports, verifies the engineering handbook, and preserves committed text bytes in source archives.

### Fixed

- Search list resets wait for the new query's results instead of scrolling earlier results while matching is pending.
- The animated background uses one bounded, reusable image in its own layer, preserving its verified appearance while reducing drawing work.
- Successful password submissions receive the existing full sign-in page timeout window.
- Completed credits keep their actual earned amount when no target is known, while retaining the full progress bar.
- The opening coupon ribbon appears complete before the airplane arrives, then forms the final overlap as the plane settles.

## [0.3.1] - 2026-11-13

### Added

- Benefits starts with institution tiles, then cards, with offline images for Platinum, Business Platinum, Hilton Surpass, and Delta Gold and an unknown-card fallback.
- Welcome bonus, Credits, Spend trackers, and Other categories have outlined counted headers and independently saved Show/Hide controls.
- Four per-card sorts: Name A-Z, Name Z-A, Deadline soonest, and Largest amount remaining, with Completed last in a separate filter menu.
- Yellow alert counts and top-of-card alerts support individual saved acknowledgement and return after a relevant successful read.
- Optional local welcome-bonus setup supports grouped dollar and Reward values, whole-dollar amounts without automatic decimals, formatted dates, a calendar picker, and bounded validation.

### Changed

- Rewards and reminder entry preserve the institution/card navigation hierarchy and Back behavior.
- Matching trackers and listed entries render once with retained descriptions and enrollment; raw issuer rows and ambiguous matches remain intact.
- Listed-benefits freshness uses saved section outcomes bound to an atomic Room read generation; the additive schema migration preserves accounts, benefits, enrollment, and reminders.
- The offline handbook describes the redesigned navigation, controls, and local persistence.

## [0.3.0] - 2026-11-13

### Fixed

- A newly visible password refusal is classified at the existing sign-in page deadline instead of being lost as an unfinished sign-in.
- Benefits pages wait for their own content, and a card switch waits for the new card's trackers, within one 2-minute benefits-read deadline. Rewards follows an available activity link at its readiness cap and waits within the page budget for a late lower section.
- Hilton Surpass and Gold Delta SkyMiles read their full benefits list from the View all benefits page. A refresh that reaches only part of a card's pages keeps what the other pages stored.

### Added

- Offline product handbook with reading and presentation views, retained build sources, and a CI freshness check.
- Count-only page, card, and total-time diagnostics for benefits reads.
- The capture build keeps its window open after a read and shows a shortened page path. The capture runbook uses sanitization and structure-only inspection, and Python caches are ignored.

## [0.2.0] - 2026-11-12

Adds a Benefits tab for American Express cards and reminders before a card credit expires. Everything stays on the phone.

### Added

- Benefits tab between Rewards and Settings: one tile per American Express card with its last digits, next open credit, completed count, and freshness, plus a "Not updated since" note when a card's last read failed or is more than 8 days old.
- Card pages grouped like the Amex site: spend progress, credits, completed, other, and, for Platinum-type cards, the full benefits list with "Enrolled" or "Enrollment required".
- A separate benefits read after each saved Amex balance (never the 15-minute keep-alive). It visits every card by pressing the site's card switcher, never signs in, keeps a card whose page failed, and removes a card only when a complete card list no longer shows it (ADR-0008).
- Benefit reminders: one private notification per credit and period when money is left and the period ends within 7, 14, or 30 days by its length; never from benefits older than 8 days; a Settings switch, on by default, and a banner when notifications are not allowed.
- Room schema 3 with `cards`, `benefits`, and `benefit_reminders` tables, auto-migrated from 2 without changing accounts or balances.
- Capture tools for the developer PC: `sanitize_capture.py` strips identifiers while copying a page, and `inspect_capture.py` prints structure with text masked.

### Security

- A card is stored by its last digits only. The page script strips grouped digit runs, identifier-like tokens, and account-key-shaped runs before posting, never reads input values, cookies, storage, or link query strings, and its benefit messages are accepted only from the two Amex pages.
- This release changes no opt-in capability: Benefit reminders are on by default and need the system notification permission.

### Notes

- Known gaps G25 to G33 were found in this release; G24 is fixed. See `docs/releases/v0/v0.2/known-gaps.md`.

## [0.1.0] - 2026-11-11

First release: a native Android app that shows loyalty-program balances on one dashboard, with no server and nothing leaving the phone.

### Added

- Catalog of 14 programs (credit cards, airlines, hotels) with fixture-verified balance selectors for every program that syncs; programs not tested yet are listed but cannot be added.
- Login bridge: each program's own site in a WebView with exact-host rules, a per-program profile and cookie store, and a page script that only reads the balance and fills fixed sign-in fields.
- Automatic sign-in with a saved sign-in sealed by the Android Keystore (StrongBox when available), texted-code entry, and "remember this device" handling; a refused password pauses automatic sign-in until the user taps Refresh.
- Refresh rules: a midnight schedule (daily, Sunday by default, or month end), a 15-minute background keep-alive that never types a password and never changes a tile on a timeout, per-tile Refresh, and a pull-down refresh that asks first.
- Dashboard with category, type, alphabetical, and custom drag order; Add program with a filter and added-program outlines; Settings; an animated opening screen and a dark teal theme.
- Room database with migrations, encrypted session metadata, a private diagnostics file that names programs and hosts only, and no cloud backup or device transfer.
- Unit, Robolectric, and instrumented tests; GitHub Actions CI with one aggregate required check, a schema drift check, a private-fixture guard, and a secret scan.
- Docs: the v0.1.0 plan, decision records ADR-0001 to ADR-0007, the handbook, known gaps, and release evidence.

### Notes

- This release changes no opt-in capability, installer flag, or host surface beyond the app's own runtime permissions (texts, notifications), which the app requests in context.
- Known gaps carried to v0.2 are listed in `docs/releases/v0/v0.1/known-gaps.md`.
