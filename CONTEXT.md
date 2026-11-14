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
