# ADR-0006: Catalog Lists Programs Not Syncing Yet

- **Status**: Superseded
- **Date**: 2026-11-10
- **Template**: MADR
- **Origin**: report that Delta, Marriott, United, and the other programs were missing from Add program
- **Supersedes**: the "catalog and its search show only programs with automatic sync" rule in [ADR-0003](0003-sync-only-catalog-and-reconnect.md)
- **Superseded by**: [ADR-0007](0007-catalog-filter-and-added-outline.md)

## Context and Problem Statement

ADR-0003 hid every program without automatic sync from Add program. Users expected to see the full program list and read the missing programs as removed. Manual entry stays rejected, so a program that cannot sync still has nothing useful to add.

## Decision Drivers

- The user sees which programs MileStone covers, including the ones not working yet.
- No manual entry, and no tile that can never show a balance.

## Considered Options

1. **List them dimmed, marked "Not syncing yet", and not tappable (chosen).**
2. **Keep them hidden until each one syncs.** Rejected: the user reads hidden programs as dropped.
3. **List them and allow adding them.** Rejected: the tile would stay empty, since manual entry is gone.

## Decision Outcome

Chosen: option 1.

- Add program and its search list every catalog program. Programs that sync come first in each category; the rest follow, dimmed, with "Not syncing yet" and no tap action.
- A program moves to the tappable group as soon as its catalog entry gains automatic sync.

## Consequences

- Search can match a program that cannot be added; its dimmed card says why.
- ADR-0003's other decisions (no manual entry, Reconnect, cash balances, Delta read only from Delta) are unchanged.
