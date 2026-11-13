#!/usr/bin/env python3
"""Build the retained offline handbook, or compare its bytes without writing."""

from __future__ import annotations

import argparse
import hashlib
import html
import json
import os
from pathlib import Path
import re
import sys
from urllib.parse import urlsplit

ROOT = Path(__file__).resolve().parents[1]
SOURCES = ROOT / "docs/handbooks/_sources/overview"
OUTPUT = ROOT / "docs/handbooks/html/overview.html"


def local(name: str) -> Path:
    path = (ROOT / name).resolve()
    if not path.is_relative_to(ROOT) or not path.is_file():
        raise ValueError(f"Missing or external source: {name}")
    return path


def load(name: str) -> str:
    return local(name).read_text(encoding="utf-8-sig").replace("\r\n", "\n")


def slug(text: str) -> str:
    return re.sub(r"[^a-z0-9]+", "-", text.lower()).strip("-")


def inline(text: str, source: Path, source_ids: dict[Path, str]) -> str:
    tokens: list[str] = []

    def keep(value: str) -> str:
        tokens.append(value)
        return f"\x00{len(tokens) - 1}\x00"

    text = re.sub(r"`([^`]+)`", lambda m: keep("<code>" + html.escape(m[1]) + "</code>"), text)

    def link(match: re.Match) -> str:
        label, target = match.groups()
        parts = urlsplit(target)
        if parts.scheme and parts.scheme not in {"https", "http"}:
            raise ValueError(f"Unsupported link scheme in {source.name}")
        if not parts.scheme and not target.startswith("#"):
            destination = (source.parent / parts.path).resolve()
            if not destination.is_relative_to(ROOT):
                raise ValueError(f"Link outside repository in {source.name}")
            if destination in source_ids:
                target = "#" + source_ids[destination]
                if parts.fragment:
                    target += "-" + slug(parts.fragment)
            else:
                target = Path(os.path.relpath(destination, OUTPUT.parent)).as_posix()
                if parts.fragment:
                    target += "#" + parts.fragment
        return keep('<a href="' + html.escape(target, quote=True) + '">' + html.escape(label) + "</a>")

    text = re.sub(r"\[([^\]]+)\]\(([^\s)]+)\)", link, text)
    text = html.escape(text)
    text = re.sub(r"\*\*([^*]+)\*\*", r"<strong>\1</strong>", text)
    text = re.sub(r"(?<!\*)\*([^*]+)\*(?!\*)", r"<em>\1</em>", text)
    for index, token in enumerate(tokens):
        text = text.replace(f"\x00{index}\x00", token)
    return text


def render_source(config: dict, source_ids: dict[Path, str]) -> tuple[str, dict[str, dict]]:
    source = local(config["path"])
    lines = source.read_text(encoding="utf-8-sig").splitlines()
    units: dict[str, dict] = {}
    current: list[str] = []
    current_id = ""
    title = ""
    index = 0

    def end_section() -> None:
        if current_id:
            units[current_id] = {"title": title, "html": "\n".join(current) + "\n</section>"}

    while index < len(lines):
        line = lines[index]
        if not line.strip():
            index += 1
            continue
        heading = re.fullmatch(r"(#{1,2}) (.+)", line)
        if heading:
            end_section()
            title = heading[2]
            current_id = config["id"] + "-" + slug(title)
            if current_id in units:
                raise ValueError(f"Duplicate source heading: {current_id}")
            level = len(heading[1]) + 1
            current = [f'<section class="source-section" id="{current_id}" data-source-unit="{current_id}">', f"<h{level}>{html.escape(title)}</h{level}>"]
            index += 1
            continue
        if not current_id:
            raise ValueError(f"Source must start with a heading: {source.name}")
        if line.startswith("```"):
            language = line[3:].strip()
            code: list[str] = []
            index += 1
            while index < len(lines) and lines[index] != "```":
                code.append(lines[index])
                index += 1
            if index == len(lines):
                raise ValueError(f"Unclosed code fence: {source.name}")
            current.append('<pre><code class="language-' + html.escape(language, quote=True) + '">' + html.escape("\n".join(code)) + "</code></pre>")
            index += 1
            continue
        if line.startswith("|"):
            rows: list[list[str]] = []
            while index < len(lines) and lines[index].startswith("|"):
                rows.append([cell.strip() for cell in lines[index].strip().strip("|").split("|")])
                index += 1
            if len(rows) < 2 or any(len(row) != len(rows[0]) for row in rows) or not all(re.fullmatch(r":?-{3,}:?", cell) for cell in rows[1]):
                raise ValueError(f"Unsupported table in {source.name}")
            cells = lambda row, tag: "<tr>" + "".join(f"<{tag}>" + inline(cell, source, source_ids) + f"</{tag}>" for cell in row) + "</tr>"
            current.append('<div class="table-wrap" tabindex="0" role="region" aria-label="' + html.escape(title, quote=True) + ' table"><table><thead>' + cells(rows[0], "th") + "</thead><tbody>" + "".join(cells(row, "td") for row in rows[2:]) + "</tbody></table></div>")
            continue
        item = re.match(r"^(?:- |\d+\. )(.+)", line)
        if item:
            ordered = not line.startswith("- ")
            pattern = r"^\d+\. (.+)" if ordered else r"^- (.+)"
            tag = "ol" if ordered else "ul"
            items: list[str] = []
            while index < len(lines) and (item := re.match(pattern, lines[index])):
                items.append("<li>" + inline(item[1], source, source_ids) + "</li>")
                index += 1
            current.append(f"<{tag}>" + "".join(items) + f"</{tag}>")
            continue
        if line.startswith(("#", ">", "<", "![", "    ", "\t")):
            raise ValueError(f"Unsupported Markdown construct in {source.name} at line {index + 1}")
        paragraph = [line]
        index += 1
        while index < len(lines) and lines[index].strip() and not re.match(r"^(#|```|\||- |\d+\. )", lines[index]):
            paragraph.append(lines[index])
            index += 1
        current.append("<p>" + inline(" ".join(paragraph), source, source_ids) + "</p>")
    end_section()
    css_class = "source-document technical" if config["technical"] else "source-document"
    return f'<div class="{css_class}" id="{config["id"]}">' + "\n".join(unit["html"] for unit in units.values()) + "</div>", units


def namespace(fragment: str, prefix: str) -> str:
    ids = re.findall(r'\bid="([^"]+)"', fragment)
    for value in ids:
        fragment = fragment.replace(f'id="{value}"', f'id="{prefix}{value}"')
        fragment = fragment.replace(f'href="#{value}"', f'href="#{prefix}{value}"')
    return fragment


def build() -> bytes:
    model = json.loads((SOURCES / "model.json").read_text(encoding="utf-8"))
    design = json.loads((SOURCES / "design.json").read_text(encoding="utf-8"))
    manifest = json.loads((ROOT / "docs/handbooks/handbooks.json").read_text(encoding="utf-8"))
    entry = manifest["handbooks"][0]
    if design["status"] != "resolved" or len(model["slides"]) > model["presentation"]["slide_ceiling"]:
        raise ValueError("Unresolved design or exceeded slide ceiling")
    source_ids = {local(source["path"]): source["id"] for source in model["sources"]}
    reading: list[str] = []
    units: dict[str, dict] = {}
    technical: set[str] = set()
    for source in model["sources"]:
        rendered, source_units = render_source(source, source_ids)
        reading.append(rendered)
        units.update(source_units)
        if source["technical"]:
            technical.update(source_units)
    mapped = [unit for slide in model["slides"] for unit in slide["units"]]
    if set(mapped) != set(units):
        raise ValueError("Source/slide coverage differs: " + repr(sorted(set(mapped) ^ set(units))))
    if len({slide["id"] for slide in model["slides"]}) != len(model["slides"]):
        raise ValueError("Duplicate slide id")
    unit_slide = {unit: slide["id"] for slide in model["slides"] for unit in slide["units"]}
    for unit, value in units.items():
        control = f'<button type="button" class="chapter-entry" data-dv-chapter="{unit_slide[unit]}">Present chapter</button>'
        reading = [part.replace(value["html"], value["html"].replace("</section>", control + "</section>")) for part in reading]
    contents = "<ul>" + "".join(f'<li class="{"technical-link" if unit in technical else ""}"><a href="#{unit}">{html.escape(value["title"])}</a></li>' for unit, value in units.items()) + "</ul>"
    slides = []
    for index, slide in enumerate(model["slides"]):
        points = "".join(f'<li data-dv-animate="process" data-dv-step="{step}">{html.escape(point)}</li>' for step, point in enumerate(slide["points"]))
        deep = namespace("\n".join(units[unit]["html"] for unit in slide["units"]), f'slide-{slide["id"]}-')
        slides.append(f'<section data-dv-slide="{slide["id"]}" data-theme="{slide["theme"]}" data-default-theme="{slide["theme"]}" aria-label="{index + 1}. {html.escape(slide["title"], quote=True)}" hidden><p class="slide-kicker">MileStone / {index + 1:02d}</p><h2>{html.escape(slide["title"])}</h2><p class="slide-lede">{html.escape(slide["lede"])}</p><ul class="slide-points">{points}</ul><div class="deep-source" data-dv-native tabindex="0" role="region" aria-label="Full source detail; scroll to read">{deep}</div><p class="slide-sources">Source detail is available in Deep dive; the full handbook remains in reading view.</p></section>')
    # Generated bytes stay portable across Git's Windows/Linux text line endings.
    hashes = {name: hashlib.sha256(load(name).encode("utf-8")).hexdigest() for name in entry["inputs"]}
    record = {"schema_version": 1, "source_units": list(units), "presentation": model["presentation"], "slides": [{key: slide[key] for key in ("id", "theme", "units")} for slide in model["slides"]], "input_hashes": hashes, "design": design}
    icons = {"PREV_ICON": '<path d="m14 5-7 7 7 7"/>', "NEXT_ICON": '<path d="m10 5 7 7-7 7"/>', "REPLAY_ICON": '<path d="M5 7a8 8 0 1 1-1 8M5 3v5h5"/>', "FULLSCREEN_ICON": '<path d="M9 3H3v6m12-6h6v6M3 15v6h6m12-6v6h-6"/>', "EXIT_ICON": '<path d="m6 6 12 12M18 6 6 18"/>'}
    values = {"TITLE": html.escape(model["title"]), "SUBTITLE": html.escape(model["subtitle"]), "READING": "\n".join(reading), "CONTENTS": contents, "SLIDES": "\n".join(slides), "PICKER": "".join(f'<option value="{i}">{i + 1}. {html.escape(slide["title"])}</option>' for i, slide in enumerate(model["slides"])), "RECORD": json.dumps(record, sort_keys=True).replace("<", "\\u003c"), "INPUT_HASH": hashlib.sha256(json.dumps(hashes, sort_keys=True).encode()).hexdigest(), "SHARED_CSS": (SOURCES / "dual-view.css").read_text(encoding="utf-8"), "PROJECT_CSS": (SOURCES / "project.css").read_text(encoding="utf-8"), "SHARED_RUNTIME": (SOURCES / "dual-view-runtime.js").read_text(encoding="utf-8"), "PROJECT_RUNTIME": (SOURCES / "project-runtime.js").read_text(encoding="utf-8")}
    values.update({key: '<svg aria-hidden="true" viewBox="0 0 24 24">' + path + "</svg>" for key, path in icons.items()})
    template = (SOURCES / "template.html.in").read_text(encoding="utf-8")
    for key, value in values.items():
        template = template.replace("{{" + key + "}}", value)
    if re.search(r"\{\{[A-Z_]+\}\}", template):
        raise ValueError("Unresolved template token")
    return template.replace("\r\n", "\n").encode("utf-8")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="Compare expected bytes without writing files")
    args = parser.parse_args()
    try:
        expected = build()
        if args.check:
            if not OUTPUT.is_file() or OUTPUT.read_bytes() != expected:
                print("Handbook is stale; run python scripts/build_handbooks.py", file=sys.stderr)
                return 1
            print("Handbook is current")
        else:
            OUTPUT.parent.mkdir(parents=True, exist_ok=True)
            OUTPUT.write_bytes(expected)
            print("Built docs/handbooks/html/overview.html")
        return 0
    except (OSError, ValueError, KeyError, TypeError) as error:
        print(f"Handbook build failed: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
