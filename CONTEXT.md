# Domain Glossary

## Global Search

**Definition**: An on-device keyword search across added programs and their stored cards and displayed benefits, opened through the top search icon.

**Avoid**: Search tab, web search.

**Relationships**: Separate from Add program catalog discovery; contains typed program, card, and displayed-benefit results. Contextual queries may match parent names, with at least one token matching the result itself. Unadded programs are excluded, as confirmed by the owner on 2026-11-13.

## Displayed Benefit

**Definition**: One benefit shown by the canonical Benefits presentation, which may combine matching tracker and listed issuer rows while retaining their useful content.

**Avoid**: Raw issuer row, title identity.

**Relationships**: Belongs to one card and period; can have multiple source identities; a global search result opens this item without deleting source records.

## Temporary Reveal

**Definition**: A one-visit expansion and scroll that makes the selected search result visible without changing saved category or sort preferences.

**Avoid**: Saved expansion, preference reset.

**Relationships**: Applies to a displayed benefit on its card page and is consumed after the target is revealed.

## Card Product

**Definition**: A public issuer product template with regional identity, artwork references and independently reviewed earning rules.

**Avoid**: Owned card, bank connection, personalized offer.

**Relationships**: One product can describe multiple owned instances; library coverage does not grant issuer connectivity or establish a person's eligibility.

## Owned Card Instance

**Definition**: One specific card represented in the local wallet, identified by an existing connected CardKey or a distinct manual-card identifier.

**Avoid**: Product name identity, card-ending identity, sign-in profile.

**Relationships**: Refers to a product when known; manual instances survive connected-inventory pruning and do not imply a bank sign-in.

## Purchase Recommendation

**Definition**: A read-only local comparison of estimated incremental purchase value across selected owned card instances using explicit purchase context and assumptions.

**Avoid**: Global Search result, guaranteed savings, offer enrollment.

**Relationships**: A separate action reached from Search; consumes earning rules, user valuations and exact-card offer state without changing issuer state.

## Confirmed Offer Value

**Definition**: Estimated value contributed by an issuer-confirmed added offer whose known card, date, merchant, channel, threshold and remaining-use conditions qualify for the proposed purchase.

**Avoid**: Redeemed savings, discovered offer value, automatic eligibility.

**Relationships**: Distinct from conditional offer estimates and actual posted statement credits; unknown or stale qualifying state cannot establish confirmed value.

## Institution

**Definition**: A bank or other provider represented by a stable identity and regional product/program relationships, with separate catalog, balance, benefit and offer capabilities.

**Avoid**: Connected account, supported-everywhere provider, issuer enum.

**Relationships**: Can have products and programs before an authenticated connector exists; adding a catalog record grants no network permission.

## Reward Program

**Definition**: A bank, airline, hotel or other reward scheme with identified earning units and independently described rules.

**Avoid**: Card product, cardholder account, universal point value.

**Relationships**: Many card products can earn the same program's units, and a program can exist without an associated card or connected account.
