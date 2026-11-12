"""Strip identifiers from a captured page before it is written on the developer PC.

Reads HTML on stdin and writes sanitized HTML to the path given as the only argument, so the raw
capture never touches the PC's disk. Keeps tags, class names, and attribute names (what selector work
needs); drops script and style contents, form values, query values and fragments (parameter names stay), grouped digit
runs of 6 or more digits, and tokens of 8 or more characters containing 4 or more digits.

Usage (PowerShell, from the repository root):
    cmd /c "adb exec-out run-as com.bendourthe.milestone.capture cat files/captures/NAME.html | python -I app\\src\\capture\\sanitize_capture.py app\\src\\test\\resources\\fixtures-private\\NAME.html"
"""

from __future__ import annotations

import html
import re
import sys
from html.parser import HTMLParser
from pathlib import Path

DIGIT_GROUP = re.compile(r"\d+(?:[ .\- ]+\d+)*")
TOKEN = re.compile(r"[^\s\"'<>]{8,}")
ACCOUNT_KEY = re.compile(r"(?<![A-Za-z0-9])[A-Z0-9]{10,}(?![A-Za-z0-9])")
URL_ATTRS = {
    "href",
    "src",
    "action",
    "formaction",
    "srcset",
    "poster",
    "data-href",
    "data-src",
}
DROPPED_ATTRS = {"value", "nonce", "integrity", "content"}
OPAQUE_TAGS = {"script", "style", "noscript", "template"}
VOID_TAGS = {
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


def strip_identifiers(text: str) -> str:
    """Remove grouped digit runs totaling 6+ digits, then identifier-like tokens."""

    def group(match: re.Match[str]) -> str:
        digits = sum(ch.isdigit() for ch in match.group(0))
        return "#" if digits >= 6 else match.group(0)

    def token(match: re.Match[str]) -> str:
        value = match.group(0)
        return "#" if sum(ch.isdigit() for ch in value) >= 4 else value

    def key(match: re.Match[str]) -> str:
        return "#" if any(ch.isdigit() for ch in match.group(0)) else match.group(0)

    # Issuer account keys can carry as few as 3 digits (Amex: 15 uppercase letters and digits).
    return ACCOUNT_KEY.sub(key, TOKEN.sub(token, DIGIT_GROUP.sub(group, text)))


def _strip_one_url(value: str) -> str:
    base, _, rest = value.partition("?")
    base = base.split("#", 1)[0]
    query = rest.split("#", 1)[0]
    names = [part.split("=", 1)[0] for part in query.split("&") if part]
    kept = strip_identifiers(base)
    return kept + (
        "?" + "&".join(f"{strip_identifiers(n)}=" for n in names) if names else ""
    )


def strip_url(value: str) -> str:
    """Keep the scheme, host, path, and query parameter names of each address; drop every value."""
    parts = (
        [p.strip() for p in value.split(",")]
        if "," in value and " " in value
        else [value]
    )
    return ", ".join(_strip_one_url(p) for p in parts)


class Sanitizer(HTMLParser):
    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self.out: list[str] = []
        self.opaque = 0

    def _attrs(self, attrs: list[tuple[str, str | None]]) -> str:
        rendered = []
        for name, value in attrs:
            if value is None:
                rendered.append(f" {name}")
                continue
            if name in DROPPED_ATTRS or name.startswith("on"):
                rendered.append(f' {name}=""')
                continue
            if name == "class":
                clean = value
            elif name in URL_ATTRS:
                clean = strip_url(value)
            else:
                clean = strip_identifiers(value)
            rendered.append(f' {name}="{html.escape(clean, quote=True)}"')
        return "".join(rendered)

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        self.out.append(f"<{tag}{self._attrs(attrs)}>")
        if tag in OPAQUE_TAGS and tag not in VOID_TAGS:
            self.opaque += 1

    def handle_startendtag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        self.out.append(f"<{tag}{self._attrs(attrs)}/>")

    def handle_endtag(self, tag: str) -> None:
        if tag in OPAQUE_TAGS and self.opaque:
            self.opaque -= 1
        self.out.append(f"</{tag}>")

    def handle_data(self, data: str) -> None:
        if not self.opaque:
            self.out.append(html.escape(strip_identifiers(data), quote=False))

    def handle_comment(self, data: str) -> None:
        # Only the capture build's own address line survives; every other comment is dropped.
        text = data.strip()
        if text.startswith("url: "):
            self.out.append(f"<!-- url: {strip_url(text[5:]).replace('--', '- -')} -->")

    def handle_decl(self, decl: str) -> None:
        self.out.append(f"<!{decl}>")


def sanitize(raw: str) -> str:
    parser = Sanitizer()
    parser.feed(raw)
    parser.close()
    return "".join(parser.out)


def main(argv: list[str]) -> int:
    if len(argv) != 2:
        print("usage: sanitize_capture.py OUTPUT.html < capture.html", file=sys.stderr)
        return 2
    raw = sys.stdin.buffer.read().decode("utf-8", errors="replace")
    if not raw.strip():
        print("no capture received on stdin; nothing written", file=sys.stderr)
        return 1
    out = Path(argv[1])
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(sanitize(raw), encoding="utf-8")
    print(f"wrote {out.name} ({out.stat().st_size} bytes)")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
