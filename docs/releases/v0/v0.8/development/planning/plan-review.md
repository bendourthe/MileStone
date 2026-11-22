# Plan Review

Date: 2026-11-21 (America/Los_Angeles)

Plan: [v0.8.0 adoption-cardpointers](../../plans/v0.8.0-adoption-cardpointers.md)

## Coverage

Seven independent read-only lenses ran: coherence, feasibility, product, design, scope guardian, security and adversarial. No conditional lens was skipped: the plan defines data models, handles private wallet data and accepts user-controlled input. All six phases and the mandatory final lifecycle were in scope. Reviews assessed the written draft, not an implementation. Security's empty finding array is not execution approval.

## Findings

| Severity | Lens | Location | Finding | Confidence | Disposition |
|---|---|---|---|---|---|
| P1 | Product and coherence | Overview / Phases 1-2 | The proposed 12-product pilot did not serve the owner's broader card-library need. | 100 | Owner rejected the two-issuer pilot. Revised to a provider-independent institution/program/card library, broad US/Canada source portfolios, maintainable growth and same-binary unseen-provider use. Two raw findings deduplicated. Product, coherence, design and scope rechecks returned no findings on the broader draft. |
| P2 | Product | Goals First / Phase 6 | Merchant-name journeys lacked an agreed context-question policy. | 75 | Owner selected asking only for missing context. Applied to UI prompt and representative grocery/restaurant/online retail/travel journeys across providers. |
| P2 | Coherence | Goals First / Phase 1 | Staleness qualification lacked testable rules. | 100 | Corrected: product verification age 30 days, offer confirmation age 24 hours, explicit validity/expiry/missing-state rules, conditional preservation and boundary tests. These are conservative heuristics rather than issuer guarantees. |
| P2 | Design | Phases 3-4 | Offer thresholds failed to distinguish a single transaction from cumulative campaign progress. | 75 | Corrected: explicit threshold scope, verified prior progress, conditional unknowns and completed/redeemed checks with independent cases. |
| P2 | Adversarial | Scope / Phase 3 | BigDecimal alone did not bound oversized or exponent-driven numeric inputs. | 75 | Corrected: pre-parse text/digit/scale/grammar/range bounds and reject-with-preservation behavior; focused hostile-input cases required. |
| P2 | Security, broader-scope recheck | Phase 2 | Reviewed public source URLs could redirect to unreviewed/local destinations. | 90 | Corrected: HTTPS, destination/redirect validation, public-address checks, no credential/header forwarding and malicious redirect/loop cases. |
| P2 | Feasibility, broader-scope recheck | Phase 7.5 | Final CI prompt retained old task 6.9 after renumber. | 100 | Corrected to 7.9; phase count, strict task IDs and prerequisites rechecked. |

Across initial and broader-scope reviews, eight raw findings became seven after path/section/title deduplication. Cross-lens agreement retained the initial coverage issue at P1. The confidence gate was applied after deduplication/promotion; all seven met anchor 75. No P0 or P3 findings, and no suppressed findings. All seven lenses re-read the broader draft; scope guardian, product, design, coherence and adversarial returned no new findings. Security and feasibility's two narrow findings are corrected above. Feasibility explicitly checked phase commits, non-final push/remote-CI prohibition, final pipeline comparison and green integration before release. The new recommendation target is 200 ms p95 on a distinct 100-card/2,000-offer workload; no old Search gate was changed.

Verdict: **READY**, with final owner confirmation recorded on 2026-11-21. Both product decisions are applied and five engineering findings are corrected. The owner selected "Approve the revised plan and publish its documents". No implementation validation or execution approval is claimed. Reviewers did not edit the plan.

## Minor Analysis

The installed plan-queue-assessment replan_minor.py reran against docs/releases/v0/v0.8/plans with claims-deps-only after expansion. One plan was analyzed, 28 strict tasks are open, no unreadable plan exists, one wave is sufficient, and no merge/split/redundancy/renumber proposal was emitted. The output used tasks-derived paths rather than a native schema-2 record, so its write/read list is only a lower bound; the plan's broader inline claims remain authoritative inputs to future native admission. No other v0.8 plan exists. No change proposed to other plans.

## Interview

The design-interview seeded frontier contained coverage and merchant-context UX. Both questions were sent together after autonomous review because neither depended on the other's answer. The owner rejected Amex/Chase-only coverage and required a growing library across providers, banks, institutions, programs and cards that supports a future new card. The owner selected asking only for missing purchase context. Both decisions are applied; no design branch is parked. The owner then approved the revised seven-phase breakdown and publication of planning documents only. Existing Search scope and all-independently-eligible-card enrollment policy remain decided constraints.

## Verification

The model map was checked against current official catalogs, validated and diffed with no tier reclassification. All seven phase rubrics were executed separately and recorded in phase-routing-20261121.json. The expanded draft passes repository fast validation, Unicode sanitizer with zero repairs, plan_status.py --check, seven-phase/28-sequential-task/seven-checklist/final-duty checks, relative-link checks and git diff --check. Final confirmation changes only status/decision records; repeat the targeted document checks before publication. This task has no runnable application change; app behavior and live issuer actions were not exercised.
