# Handbooks

Living technical documentation for MileStone.

| Topic | File | Status |
|---|---|---|
| Repository overview | [overview.md](overview.md) | Maintained source |
| Amex benefits reader | [benefits-reader.md](benefits-reader.md) | Maintained technical companion |
| Offline handbook | [Reading and Presentation Mode](html/overview.html) | Generated from both sources |

The offline handbook includes the complete reading sources and an eight-slide presentation. Presentation Mode is available from the title and top menu. Reading and presentation themes and detail controls are independent; presentation starts at slide 1 unless a chapter or slide link is selected. No captured account page or account data is included.

Run from the repository root in PowerShell:

```powershell
python scripts/build_handbooks.py
python scripts/build_handbooks.py --check
```

Expected results: `Built docs/handbooks/html/overview.html`, then `Handbook is current`. The builder uses only the Python standard library. `--check` is read-only and fails when the generated file differs from its retained sources. Build checks establish reproducibility; content and browser reviews are separate release requirements.

On a developer computer with the Playwright Python package and its Chromium runtime installed, run the browser review from the repository root in PowerShell:

```powershell
python scripts/check_handbook_render.py --out "$env:TEMP\milestone-handbook-render"
```

Expected result: a JSON summary with `status` equal to `pass`, plus measurements and screenshots in the output folder. It exercises reading, presentation, keyboard navigation, contrast, enlarged text, offline behavior, and print/no-JavaScript reading. This command records observations; it does not create or renew the verification receipt automatically.

`handbooks.json` maps the output, sources, code dependencies, builder, and verification record. `_sources/overview/` retains the design settings, slide model, template, styles, and presentation runtime. Edit those inputs or the Markdown sources, then rebuild; do not edit the generated HTML directly. The verification record is completed only after actual content, build, and rendered reviews of the final bytes.
