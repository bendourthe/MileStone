# Documentation

This tree separates living documents from release documents.

Living documents describe the project as it is now. They stay in place across versions:

- `handbooks/` holds the technical overview, written from the code as it is: modules, runtime flow, privacy rules, and tests.
- `decisions/` holds the architecture decision records, indexed in `decisions/README.md`.
- `todos.md` is the progress tracker.
- `DEVLOG.md` is the release index. It gains one row when a version is released.

Release documents for the current version live in `releases/v0/v0.3/`. Plans go in `plans/`. Comparisons go in `comparisons/`. When a version closes, its plans and comparisons move under `archives/` using the same version folders, while its known gaps, evidence, and histories stay in `releases/`: v0.1 and v0.2 are closed this way.

v0.1.0 shipped and is listed in `DEVLOG.md`; the root `CHANGELOG.md` is the authoritative record.
