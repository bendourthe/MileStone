# ADR-0007: Catalog Filter, Added Outline, and One Card Style

- **Status**: Accepted
- **Date**: 2026-11-10
- **Template**: MADR
- **Origin**: review of Add program after ADR-0006 shipped
- **Supersedes**: [ADR-0006](0006-catalog-lists-programs-not-syncing-yet.md)
- **Superseded by**: -

## Context and Problem Statement

ADR-0006 listed programs without automatic sync dimmed and untappable. Review found them too dark and asked for one card style for every program and an Add program focused on programs not added yet. Tapping Delta did nothing, which read as a broken button.

## Decision Drivers

- Add program is for adding: programs already added are noise by default.
- Every program looks the same; added ones are marked without dimming anything.
- A tap always answers, and manual entry stays rejected.

## Considered Options

1. **Filter with Not added (default), Added, and All programs; one card style; green outline on added programs; a tap on a program that cannot sync explains it (chosen).**
2. **Keep ADR-0006's dimmed cards.** Rejected.
3. **Let programs that cannot sync be added.** Rejected: the tile could never show a balance.

## Decision Outcome

Chosen: option 1.

- A filter button in the top bar offers Not added, Added, and All programs; Add program opens on Not added.
- Every card uses the same style. Added programs carry a green outline and the Added mark.
- Tapping a program that syncs opens its sign-in. Tapping one that cannot sync yet shows "<program> can't sign in from MileStone yet".

## Consequences

- A program becomes addable as soon as its catalog entry gains automatic sync; Delta is next, now that its home page loads after sign-in.
