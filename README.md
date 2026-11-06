# MileStone

MileStone is a new repository. It holds the project icon and the files that track version, documentation, and progress. Application code is not in the tree yet, so there is no install step and no run command.

## Version

The current version is v0.1.0. It is recorded as the `0.1.0` heading in `CHANGELOG.md`. There is no package manifest and no git tag.

## Layout

| Path | Purpose |
|---|---|
| `MileStone-icon.jpg` | Project icon: stacked stones, a gold coin with a star, and a gold airplane on a dark navy field |
| `README.md` | This file |
| `CHANGELOG.md` | Version history in Keep a Changelog form |
| `CLAUDE.md` | Short project notes for coding sessions |
| `docs/DEVLOG.md` | Release index, one line per shipped release |
| `docs/README.md` | Map of the documentation tree |
| `docs/todos.md` | Living progress tracker |
| `docs/handbooks/` | Living technical overview |
| `docs/decisions/` | Architecture decision records, added when a decision is made |
| `docs/releases/v0/v0.1/` | Active docs for version 0.1 (`plans/`, `comparisons/`) |

## Version control

This directory is a git repository. `main` is the release branch. `develop` is the integration branch. The remote `origin` is `https://github.com/bendourthe/MileStone.git`.

Work branches use these prefixes and merge through `develop` into `main`: `feat/`, `fix/`, `refactor/`, `ci/`, `docs/`, `chore/`, `test/`.
