"""Print a captured page's structure without its text or numbers.

Prints tag counts, class-name counts, attribute-name counts, and, for elements matching an optional
class-name filter, their nesting path. Text is printed only for headings and short labels that contain
no digit and look like a section heading or a month-range period label, so no amount, name, or number
leaves the file.

Usage (from the repository root):
    python -I app\\src\\capture\\inspect_capture.py app\\src\\test\\resources\\fixtures-private\\NAME.html [CLASS_SUBSTRING]
"""

from __future__ import annotations

import re
import sys
from collections import Counter
from html.parser import HTMLParser
from pathlib import Path

MONTH = r"(?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)[a-z]*"
LABEL = re.compile(
    rf"^(?:{MONTH}(?:\s*[-–]\s*{MONTH})?|[A-Za-z][A-Za-z &/'-]{{2,40}})$"
)
HEADINGS = {"h1", "h2", "h3", "h4", "h5", "h6"}
VOID = {
    "area",
    "base",
    "br",
    "col",
    "embed",
    "hr",
    "img",
    "input",
    "link",
    "meta",
    "param",
    "source",
    "track",
    "wbr",
}


class Inspector(HTMLParser):
    def __init__(self, needle: str | None) -> None:
        super().__init__(convert_charrefs=True)
        self.needle = needle
        self.tags: Counter[str] = Counter()
        self.classes: Counter[str] = Counter()
        self.attrs: Counter[str] = Counter()
        self.labels: Counter[str] = Counter()
        self.testids: Counter[str] = Counter()
        self.paths: Counter[str] = Counter()
        self.stack: list[str] = []

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        self.tags[tag] += 1
        names = []
        for name, value in attrs:
            self.attrs[name] += 1
            if (
                name == "data-testid"
                and value
                and not any(ch.isdigit() for ch in value)
            ):
                self.testids[value] += 1
            if name == "class" and value:
                names = value.split()
                self.classes.update(names)
        label = tag + ("." + ".".join(names[:3]) if names else "")
        if tag not in VOID:
            self.stack.append(label)
        if self.needle and any(self.needle in n for n in names):
            self.paths[
                " > ".join(
                    self.stack[-6:] if tag not in VOID else [*self.stack[-5:], label]
                )
            ] += 1

    def handle_endtag(self, tag: str) -> None:
        for i in range(len(self.stack) - 1, -1, -1):
            if self.stack[i].split(".", 1)[0] == tag:
                del self.stack[i:]
                break

    def handle_data(self, data: str) -> None:
        text = " ".join(data.split())
        if not text or any(ch.isdigit() for ch in text):
            return
        in_heading = any(s.split(".", 1)[0] in HEADINGS for s in self.stack)
        if (
            in_heading or re.fullmatch(MONTH + r"(?:\s*[-–]\s*" + MONTH + ")?", text)
        ) and LABEL.match(text):
            self.labels[text] += 1


def main(argv: list[str]) -> int:
    if len(argv) not in (2, 3):
        print(__doc__, file=sys.stderr)
        return 2
    page = Path(argv[1])
    inspector = Inspector(argv[2] if len(argv) == 3 else None)
    inspector.feed(page.read_text(encoding="utf-8", errors="replace"))
    print(f"file: {page.name}, {page.stat().st_size} bytes")
    for title, counter, limit in (
        ("tags", inspector.tags, 40),
        ("attribute names", inspector.attrs, 40),
        ("class names", inspector.classes, 120),
        ("data-testid values (digit-free only)", inspector.testids, 150),
        ("headings and period labels", inspector.labels, 80),
        ("paths to matching elements", inspector.paths, 60),
    ):
        if counter:
            print(f"\n## {title}")
            for name, count in counter.most_common(limit):
                print(f"{count:5}  {name}")
    return 0


def mask(text: str) -> str:
    """Letters become 'a' and digits 'N', except digit-free month-range or short heading labels."""
    if not any(ch.isdigit() for ch in text) and re.fullmatch(
        MONTH + r"(?:\s*[-–]\s*" + MONTH + ")?", text
    ):
        return text
    return re.sub(r"\d", "N", re.sub(r"[A-Za-z]", "a", text))


class TreeDump(HTMLParser):
    """Prints the subtree of the n-th element whose data-testid starts with a prefix, text masked."""

    KEPT = (
        "data-testid",
        "role",
        "aria-selected",
        "aria-expanded",
        "aria-valuenow",
        "aria-valuemax",
        "value",
        "max",
    )

    def __init__(self, prefix: str, nth: int, depth: int) -> None:
        super().__init__(convert_charrefs=True)
        self.prefix, self.nth, self.max_depth = prefix, nth, depth
        self.seen = 0
        self.level = -1
        self.lines: list[str] = []

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        values = dict(attrs)
        testid = values.get("data-testid") or ""
        if self.level < 0 and testid.startswith(self.prefix):
            self.seen += 1
            if self.seen == self.nth:
                self.level = 0
        if self.level < 0:
            return
        if self.level <= self.max_depth:
            kept = " ".join(
                f'{k}="{re.sub(r"[0-9]", "N", values[k] or "")}"'
                for k in self.KEPT
                if k in values
            )
            self.lines.append("  " * self.level + f"<{tag} {kept}".rstrip() + ">")
        if tag not in VOID:
            self.level += 1

    def handle_endtag(self, tag: str) -> None:
        if self.level > 0 and tag not in VOID:
            self.level -= 1
            if self.level == 0:
                self.level = -2  # finished; never match again

    def handle_data(self, data: str) -> None:
        text = " ".join(data.split())
        if self.level > 0 and text and self.level <= self.max_depth + 1:
            self.lines.append("  " * self.level + "'" + mask(text)[:80] + "'")


def tree(argv: list[str]) -> int:
    """Usage: inspect_capture.py --tree FILE TESTID_PREFIX [NTH] [DEPTH]"""
    page, prefix = Path(argv[2]), argv[3]
    dump = TreeDump(
        prefix,
        int(argv[4]) if len(argv) > 4 else 1,
        int(argv[5]) if len(argv) > 5 else 12,
    )
    dump.feed(page.read_text(encoding="utf-8", errors="replace"))
    print(f"# {prefix} #{dump.nth} of {dump.seen}+ in {page.name}")
    print("\n".join(dump.lines[:200]))
    return 0


if __name__ == "__main__":
    sys.exit(
        tree(sys.argv)
        if len(sys.argv) > 1 and sys.argv[1] == "--tree"
        else main(sys.argv)
    )
