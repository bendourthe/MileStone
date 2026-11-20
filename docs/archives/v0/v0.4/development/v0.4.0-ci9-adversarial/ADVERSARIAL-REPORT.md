# Adversarial Verification

**Verdict:** CONDITIONAL PASS for the executed synthetic JVM matcher routes. No confirmed defect was observed in those routes. This report does not close whole-product, Android, release, or lifecycle obligations.

**Scope:** Actual current `app/src/main/java/com/bendourthe/milestone/domain/GlobalSearch.kt`, compiled directly with cached Kotlin 2.4.10 and Java 17. The immutable original `GlobalSearch` object from Git revision `d3df074` is renamed `ReferenceGlobalSearch` in a new isolated file. Both use the current SearchEntry, SearchTarget, SearchResults, and related declarations. The complete declarations before `object GlobalSearch` are asserted equal to the original, ignoring platform newline decoding. Normalization and contextSources code are unchanged. This isolates matcher/prepared-index behavior; it is not independent revalidation of the shared declarations themselves.

## Execution

The current source compiled and exited zero. The deterministic test generated 64 shuffled synthetic corpora, each with 4 programs, multiple cards, all available categories, duplicate comparator keys, different display orders including integer extremes, and duplicate program rows with distinct metadata. It ran 45 queries per corpus, totaling 2,880 query cases. Full SearchResults and derived groups, counts, initial tab, card groups, and sections were compared against the immutable reference through both public-list and prepared routes. Additional explicit contract and empty/orphan cases produced 5,838 result comparisons and 73,481 assertions. All passed. The 64 copied-membership cases modified the original mutable input list after prepare and observed retained prepared membership.

Only counters and failure case labels are printed. Synthetic corpus text, card digits, benefit text, and amounts are not printed. Synthetic fixture declarations remain in the local harness so cases can be reproduced. No real corpus, private capture, credential, phone, issuer, or network was accessed. No Gradle invocation or Android build was run.

## Attack Surface

| Entry point | Actual input sources | Execution |
|---|---|---|
| Public `match(query, entries)` | Query String; ordered List of immutable SearchEntry values; target/program/card identities and display fields | All 2,880 corpus queries plus explicit edge cases compared to original |
| `prepare(entries)` then prepared `match(query, prepared)` | Copied input list membership; same SearchEntry values; cached program metadata and sorted indexed rows; query String | Same query matrix, derived structures, and 64 post-prepare list membership mutations |
| `boundQuery` and shared normalization/context construction | UTF-16 query and title/description/alias display fields | Symbols, whitespace, NUL/BOM, composed/decomposed accents, Greek sigma, dotted I, CJK, supplementary letters, split-surrogate query boundary, 256/257/long repeated queries |
| Shared SearchResults projections | Matched group order, card identity, category, displayOrder, entry metadata and context | Result data-class equality plus explicit tab/count/card/section equality |

HTTP paths, cookies, headers, request bodies, shell execution, and decoded network blobs are not applicable to these dependency-free Kotlin function entry points. This statement describes this inspected domain surface, not the whole application.

## Observed Refutations

These refute concrete optimization-regression hypotheses only for the finite executed matrix. They do not reject unknown vulnerabilities or unexecuted routes.

| Counter-hypothesis | Actual sources | Public route | Prepared route | Observed evidence |
|---|---|---|---|---|
| Snapshot presorting preserves stable ties, first matched original-input group name, last duplicate program metadata, and output hierarchy | Ordered source membership; programName; kind; title; card identity/name; displayOrder; category; duplicate program display metadata | Equal to original across 64 shuffled corpora | Equal to original across the same corpora | Complete structured and derived result comparisons; mutation control below |
| Explicit program-only expansion and mixed parent/item token matching retain original semantics | Program title/identifier; entry own text; parent program/card display text | Full query matrix and explicit parent-only/parent+own/program expansion assertions pass | Same matrix and assertions pass | No parent-only benefit match; parent+own returns benefit; explicit program identity expands descendants |
| Description/alias context retains source priority and bounded excerpts | Title, description, alias fields; bounded normalized query tokens | Full context equality and explicit 156-character window including ellipses pass | Same context/window checks pass | Long description wins over a matching alias, with both excerpt ellipses |
| Query bounds, digit exclusion, symbols, Unicode, and injection-like strings preserve original behavior | Query String; title/alias/description; card suffix scrubbed at SearchEntry construction | Full equality and explicit exclusion/bound checks pass | Same equality/checks pass | Bound applies before normalization; card-only digits produce no hit; no evaluation sink is introduced |
| Prepared rows preserve list membership after caller mutation | Original mutable List passed into prepare | Reference uses the original saved rows | 64 mutated-list membership checks pass | Original list is cleared/replaced after prepare; prepared result still equals reference original membership |

## Verifier Control

The new isolated `MutantGlobalSearch.kt` changes only the group's original-input name selection: it keeps the first sorted hit instead of the minimum original row index. The mutant compiles successfully, then the identical harness exits one at `s0-q3-prepared-structured`. The public route for that case has already passed before the prepared route fails. The original current-source execution exits zero. This confirms sensitivity to a concrete prepared-index regression. It is a deliberate test fault, not a production finding. The mutant source and failure log are preserved.

## Feature Inventory

`feature-inventory.json` maps every one of the prior CI6 independent review's 26 features to its prior evidence, actual owner/harness input paths, current raw hashes, canonical LF hashes, exact comparisons to `d3df074`, and the CI6 evidence publication commit `969264d`. Existing attack evidence remains historical evidence for exact unchanged listed inputs. The CI6 report included an uncommitted working diff, so the publication commit is recorded explicitly as an anchor and is not represented as a reconstruction of the entire CI6 execution tree.

The current matcher and shared worker publication change can affect results reaching otherwise unchanged UI owners. Those UI owners' unchanged hashes preserve their prior owner-specific attack evidence; they do not establish current integrated behavior. Changed ViewModel publication, repository integration, navigation races, loading/updating, viewport/privacy behavior, and visible-frame latency require the coordinator's current JVM/native evidence. The tracker, browser/PDF, CI, release, phone and original lifecycle rows remain in the inventory with their original open limits.

## Limits

No Android, Compose, Room, coroutine publication/interleaving, process replacement, persistence, native renderer, latency, browser, PDF, phone, publication, release, or lifecycle execution was performed by this verifier. No native current-full profile, optimized timing, or owner phone acceptance is inferred from this domain result. Randomized deterministic finite corpus coverage is not an exhaustive proof. Input list membership is copied; concurrent mutation while prepare is copying was not exercised. Shared SearchEntry and SearchResults declarations are unchanged and intentionally shared, so an old defect common to both implementations may remain undetected by differential equality. Explicit contract assertions supplement that comparison but do not remove the limit.

## Reproduction

On the Windows machine, in PowerShell at the release worktree root, the original execution was `python reports/release-v040/ci9-adversarial/run_adversarial.py`. The script intentionally refuses to overwrite existing artifacts. For a repeat, preserve this directory and use a separate new report directory with corresponding path substitutions. The cached compiler and Java dependencies are documented in that script. The original execution's per-input hashes, logs, and counters are retained in `execution-receipt.json`.

## Closure

**Completed:** Isolated domain differential attacks, explicit edge contracts, verifier mutation control, and all 26 feature mappings. Only new artifacts beneath the owned report directory were authored; the required external edit-guard metadata store was used with a unique session and no adoption/reset.

**Verified:** Current compiler exit zero, current execution exit zero, 5,838 result comparisons, 73,481 assertions, and isolated mutant execution exit one at a structured prepared-result comparison.

**Open:** Current integrated publication/Android/latency and broader product obligations are outside this execution. Keep their coordinator gates and prior limitations; no finding is rejected by assuming an unseen layer is safe.

**Next:** The coordinator combines this bounded result with current full/native and rendered evidence and retains all release/remote/phone/lifecycle gates.

**Verdict:** CONDITIONAL PASS for the executed synthetic JVM matcher routes only.
