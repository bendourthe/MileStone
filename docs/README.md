# Documentation

This tree separates living documents from release documents.

Living documents describe the project as it is now. They stay in place across versions:

- `handbooks/` holds the technical overview. The overview is written from the repository as it exists: an icon and governance files, with no application code.
- `decisions/` holds architecture decision records. No decision has been recorded yet.
- `todos.md` is the progress tracker.
- `DEVLOG.md` is the release index. It gains one row when a version is released. It has no rows yet.

Release documents for the current version live in `releases/v0/v0.1/`. Plans go in `plans/`. Comparisons go in `comparisons/`. Closed release snapshots, when a version is later closed, go under `archives/` using the same version folders. That archive folder is not created until something is closed.

v0.1.0 is recorded in the root `CHANGELOG.md`. It is not a shipped release, so `DEVLOG.md` does not list it.
