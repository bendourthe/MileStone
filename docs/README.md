# Documentation

This tree separates living documents from release documents.

Living documents describe the project as it is now. They stay in place across versions:

- `handbooks/` holds the technical overview, written from the code as it is: modules, runtime flow, privacy rules, and tests.
- `decisions/` holds the architecture decision records, indexed in `decisions/README.md`.
- `todos.md` is the progress tracker.
- `DEVLOG.md` is the release index. It gains one row when a version is released.

Active release documents live in `releases/v0/v0.4/`. Plans go in `plans/`. Comparisons go in `comparisons/`. When a minor closes, its whole active tree moves to `archives/` using the same version folders, including its plans, comparisons, known gaps, evidence, and histories. Unfinished gaps are carried into the next minor before the source minor closes.

v0.1.0, v0.2.0, v0.3.0 and v0.3.1 are released and listed in `DEVLOG.md`; the root `CHANGELOG.md` is the authoritative record. Their closed records are under [v0.1](archives/v0/v0.1/), [v0.2](archives/v0/v0.2/) and [v0.3](archives/v0/v0.3/), with a shared [archive index](archives/README.md). v0.2 is absent from the active release tree because it was archived, not because its implementation is missing.
