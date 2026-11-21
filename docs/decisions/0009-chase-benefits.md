# ADR-0009: Chase Benefits Identity and Coverage

- **Status**: Accepted; source admission GO with recorded assumptions (2026-11-20)
- **Date**: 2026-11-18, accepted 2026-11-20
- **Author**: Benjamin Dourthe
- **Template**: MADR
- **Origin**: v0.5.0 Chase benefits, T002
- **Related**: [ADR-0001](0001-login-bridge-sessions-fixtures-logging.md), [ADR-0005](0005-sign-in-only-on-open-with-stop-reasons.md), [ADR-0008](0008-amex-benefits-card-navigation.md)
- **Supersedes**: -
- **Superseded by**: -

## Context and Problem Statement

One Chase sign-in may expose personal cards, business cards, or both. The owner confirmed that a linked business sign-in exposes both, while the personal-only sign-in exposes personal cards. Other users need independent sign-ins. Chase Personal and Chase Business therefore identify presentation groups, rather than independent authentication stores or card ownership namespaces.

The integrated Amex model at develop commit 2a4153449bc11bb992fa6e947cecf38d5b042b60 uses CardKey(programId, lastDigits), CardList completeness, per-card generations and section-scoped benefit replacement. Equal endings cannot identify distinct Chase cards safely. Migration must preserve existing Amex records, sign-ins, benefit/reminder keys, manual welcome data, enrollment, acknowledgements, sorting, collapse and exact Search navigation.

The bounded Capture observations establish useful selector behavior. A personal-card A/B/A sequence changed selection between two anonymous radio-ID groups and returned to the first. A later same-map comparison of the owner-confirmed same personal card under personal-only and linked business sign-ins retained both personal control IDs, with the same selected control and one additional linked-profile control. Capture changed process between reads; a fresh owned debugger connection per read completed successfully. These observations establish control equality in the tested contexts, not undocumented issuer-key semantics or complete inventory coverage.

Private source-structure reports cover personal-only and linked access, listed rows, progress components and offers. They do not yet establish complete inventory traversal, classification, section completion, welcome semantics or conservative complete-read duration. Captured card names, endings, ordering, benefit titles and counts must never define production discovery or completeness.

## Decision Drivers

- Discover every accessible card dynamically, including unfamiliar products and account sizes absent from the captures.
- Support independent and linked connections without duplicating verified cards or combining equal-ending distinct cards.
- Preserve successful data when a source, connection, inventory or section is incomplete.
- Keep raw issuer account identifiers, full numbers and bank URLs out of persistent application identity and logs.
- Preserve the accepted Amex experience and local Search behavior through a tested migration.
- Complete the benefits read within the existing shared 120000 ms deadline, with explicit partial status when it cannot finish.

## Considered Options

| Option | Benefits | Costs and limitations |
|---|---|---|
| Separate Personal and Business stores keyed by endings | Small change to the existing program/card model; straightforward presentation mapping. | Linked access duplicates cards and potentially rewards; equal endings collide; a personal-only snapshot can be mistaken for complete issuer coverage. |
| Global card identity derived directly from issuer keys or selector IDs | Verified stable source identifiers could simplify automatic overlap matching. | Raw-key persistence violates the stated privacy contract; current observations do not establish durable semantics or complete identifier coverage. A selector control cannot be promoted to a universal account key from this evidence. |
| Opaque local cards with explicit connection coverage and verified source bindings | Separates identity, sign-in and presentation; permits verified overlap and preserves uncertain cards without destructive merging. | Requires migration, binding and coverage state, conflict handling and an explicit fallback when correspondence cannot be proved. This is the proposed option. |

## Proposed Decision

Use opaque local CardIds and separate opaque connection identifiers. A card can be reachable through multiple authenticated connections, and a connection can cover multiple cards and segments. Personal and Business tiles are projections of confirmed classification and reachability, not separate copies of one card or separate rewards balances. Keep unclassified discovered cards visible with explicit classification status.

Automatically discover the complete accessible inventory through the observed issuer flow. Deduplicate across connections only through a proven binding contract. Endings, names, product artwork, card order, profile labels and matching benefit text never suffice. Preserve uncertain records separately and request user-confirmed linking only for uncertain correspondence. That fallback does not replace automatic inventory discovery. The durable source-binding representation remains an admission gate; this proposal authorizes no raw issuer-key persistence or unreviewed identifier transformation.

Preserve existing Amex identities through explicit migration aliases. Existing benefit/reminder, preference, welcome and Search keys retain their associations. New equal-ending cards receive different local identities. Refresh the implementation against integrated v0.4 source before changing shared models; this planning worktree predates that integration.

## Read and Storage Contract

Every read carries issuer, connection, exact card binding, current main-frame origin, fresh page nonce and generation. Card or profile changes invalidate pending callbacks. Unknown origin, login or verification state, unsupported structure and ambiguous selection stop that read without replacing prior successful data. The Capture transport observations are diagnostic evidence; they are not a production navigation implementation.

Record inventory coverage separately for each proven connection and segment. A complete personal-only inventory cannot prune business coverage or another connection's bindings. An inaccessible or disconnected connection changes its reachability status, not ownership learned from another connection. Empty inventory requires verified empty-state criteria; absent or partially rendered controls remain unknown. A safety cap or deadline always yields incomplete coverage rather than a truncated complete inventory.

Replace only the sections whose completion criteria were independently satisfied for the exact card. An unsupported welcome tracker is unavailable, not a zero-valued or completed bonus. Preserve manual welcome input and distinguish its provenance from issuer tracking. Normalize credits, spend/welcome progress, completed items, enrollment and listed benefits from verified source semantics. Merchant offers have a separate v0.6 lifecycle and cause no enrollment action during v0.5 discovery.

Serialize conflicting persistence updates and reject obsolete generations. A newer partial result does not erase a successful older section; complete results affect only their confirmed card, sections and coverage. The Room migration must retain the existing Amex records and user preferences and export a schema with migration regression evidence.

## Feasibility and Admission

One 120000 ms run budget covers connection selection, inventory traversal, every required card/profile transition, loading, section discovery and bounded recovery. Start no independent per-card deadline that can extend the total read. Required feasibility evidence records navigation counts and representative authenticated load durations across personal-only, independent and linked access, then derives a conservative complete-read estimate and supported scale. No durations or supported card limit are established by the current radio observations.

On 2026-11-20 the owner directed that the existing personal, business, Benefits and card-selector captures are sufficient. A text-free structure report over all 16 captures ([capture structure](../releases/v0/v0.5/development/planning/chase-capture-structure-20261120.json)) supports admission with the assumptions below. Assumptions are verified by the implementation's own tests and live reads, not by further capture sessions.

| Evidence | Result | How the remaining assumption is verified |
|---|---|---|
| Card selector | Native radios in exactly one fieldset per Benefits page, one checked, unnamed, no card-number-shaped ids (3 linked, 2 personal-only). | Production selection locks on that single fieldset; any second radio group or rejected control stops the read as unsupported. Fixture and synthetic tests. |
| Cross-profile binding | Same selected control and both overlapping controls matched across personal-only and linked sign-ins. | Used as the in-run binding candidate; cross-connection merges require that equality, and uncertain matches stay separate until the owner links them. |
| Inventory | Selector control count is the inventory for one connection; account selector shows 4 (linked) and 2 (personal) options. | Zero/one/many, reorder, added/removed and over-cap synthetic cases; a cap or deadline reports incomplete coverage. |
| Classification | The selector carries no segment grouping. | Each connection is declared Personal or Business by the owner when connected. A card reachable through a Personal connection is Personal; a card reachable only through Business connections is Business; the owner can override per card. |
| Section completeness | Benefits pages show 6-8 sections, 29-55 rows, 3 commerce tiles, headings and at most one progress tracker; zero busy indicators in every capture. | A card section is complete when the selector shows that card, no busy indicator remains and the section structure is stable; otherwise the section is unavailable and prior data is kept. |
| Complete-read feasibility | Estimated, not measured: Amex reads under the same WebView and budget take 5.5-6.9 s per page. Chase needs 1 Benefits page plus 1 in-page switch per card. Counting a switch as 2 pages: about 48 s for 3 linked cards, about 83 s for personal and linked read separately, about 8 cards per read. | The Definition of Done's three complete live reads within 120000 ms; above the supported scale the read reports partial coverage and keeps existing data. |
| Preservation and generalization | Required cases enumerated in the generalization matrix. | Phase 2-4 tests: migration, equal-ending cards, unfamiliar products, zero/one/many cards, stale callbacks, partial sources, exact navigation. |

Admission is GO for Phase 2 under these assumptions. If live reads contradict one, the affected scope stops and is recorded as a known gap rather than guessed.

## Consequences

The proposed model supports linked and independent sign-ins without tying display tiles to storage ownership. It preserves all discovered cards until evidence supports a reconciliation, and it makes partial coverage visible instead of destructive. Shared identity can serve Benefits, Search, reminders and future offers consistently.

The costs are a broader migration and additional binding, classification, coverage and conflict states. Some uncertain duplicates require owner confirmation. Strict completeness can leave retained data visibly stale when the issuer changes structure or the shared deadline expires. These costs are accepted in the proposal because guessing identity or replacing incomplete data would violate the user's requirements.

## Evidence and Follow-up

- [Capture record](../releases/v0/v0.5/development/v0.5.0-chase-capture-record.md)
- [Source-structure contract](../releases/v0/v0.5/development/planning/chase-source-contract-20261115.json)
- [Within-session selection comparison](../releases/v0/v0.5/development/planning/chase-radio-selection-observation-20261118.json)
- [Cross-profile comparison](../releases/v0/v0.5/development/planning/chase-cross-profile-radio-comparison-20261118.json)
- [Account generalization matrix](../releases/v0/v0.5/development/planning/account-generalization-matrix-20261116.json)
- [Owning plan and unchecked T002](../releases/v0/v0.5/plans/v0.5.0-chase-benefits.md)

- [Capture structure and feasibility estimate](../releases/v0/v0.5/development/planning/chase-capture-structure-20261120.json)

Accepted 2026-11-20 as the source contract for v0.5 Phases 2-4, under the assumptions in Feasibility and Admission. It is not implemented architecture, a completed phase or publication authority. The decision index update belongs to the coordinator's final documentation integration; the accepted ADR-0008 remains unchanged. Resolve the admission evidence and review this proposal before dependent production changes. The v0.6 default-on offer work must add offers to every independently eligible card for both Amex and Chase; it must not infer eligibility or activation from this discovery evidence.

## Amendment 2026-11-20: One Chase Tile

After the live check on the owner's phone, the owner replaced the two display tiles with one. Chase shows one institution tile; its page lists Personal cards first and Business cards below them, each under a header that collapses its section, and the Chase sign-ins panel sits below the cards, collapsed. Segments stay a projection of classification and reachability, exactly as decided above; only the display grouping changes. The Show under menu moves a card between the two sections.

## Amendment 2026-11-21: Section Signals, Sign-ins Sheet, and Product Artwork

Owner acceptance on the phone showed that one Chase sign-in often lists both personal and business cards, so reachability alone put a business card under Personal. A card's section is now decided, first match wins, by:

1. The owner's Show under choice.
2. A product name that says Business ("Ink ...", "... Business ...").
3. A public product list (`CardProducts`, 70 Chase and Amex products researched from the issuers' pages on 2026-11-20, plus older Chase cards from review sites).
4. The issuer home page's own grouping, stored as the derived segment: the read records, per card ending, whether Chase lists the card under "Personal" or under a business name. Only the ending and a true/false flag cross the bridge; the heading text never leaves the page, and an ending is used only when it appears once in both lists. Last digits still never identify a card.
5. The reaching sign-ins, as decided above.

The sections apply to every program with a benefits reader, so Amex is grouped the same way; only Chase has sign-ins. The sign-ins panel below the cards is replaced by a top-bar button that opens a sheet listing each sign-in as Personal or Business with its status, plus Add personal and Add business; a sign-in's kind is chosen when it is added. The product list also carries bundled offline artwork (JPEG, at most 480 px wide); the classic Chase Sapphire shares the Sapphire Preferred image because no real image was found. Product names never define coverage: an unknown product only lacks artwork and falls back to the signals below it.
