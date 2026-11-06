# Repository overview

MileStone is a git repository at the project root. The checked-in product surface is one file, `MileStone-icon.jpg`. The icon shows stacked stones in slate and teal, a ridged gold center stone, a gold coin with a star on top, and a gold airplane with a curved trail, on a dark navy background.

The remote `origin` is `https://github.com/bendourthe/MileStone.git`. `main` is the release branch and `develop` is the integration branch. There is no git tag and no package manifest. The version v0.1.0 is the `0.1.0` heading in the root `CHANGELOG.md`.

Governance files describe how later work is recorded:

- `README.md` orients a reader.
- `CHANGELOG.md` is the version record.
- `docs/DEVLOG.md` is an index of shipped releases and is empty until the first release.
- `docs/releases/v0/v0.1/plans/` and `docs/releases/v0/v0.1/comparisons/` are the places for v0.1 plans and comparisons.
- `docs/decisions/` is reserved for architecture decision records.

No module boundaries, data stores, or runtime flows exist in this tree. This overview will be revised when application code is added.
