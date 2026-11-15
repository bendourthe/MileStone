#!/usr/bin/env python3
"""Run repository-owned validation profiles; --list --json previews every command.

Requires Python 3.11+, Git, Node, JDK 17 and the Android SDK. Full additionally
uses requirements-validation.txt/Chromium and installed Gitleaks or cached Docker.
The platform profile is destructive only to the assigned emulator's test app.
"""
from __future__ import annotations

import argparse
from dataclasses import dataclass
import hashlib
import io
import json
import os
from pathlib import Path
import platform
import re
import shutil
import signal
import subprocess
import sys
import time
import tomllib
from datetime import datetime, timezone
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[1]
PROFILES = ("fast", "full", "platform", "report", "release")
EMULATOR = "emulator-5554"
GITLEAKS_IMAGE = "zricethezav/gitleaks@sha256:2bcceac45179b3a91bff11a824d0fb952585b429e54fc928728b1d4d5c3e5176"
PRIVATE = ("app/src/test/resources/fixtures-private/", "docs/private/")
# Evidence/history do not alter validator inputs. Living handbook, code, tests,
# build settings, exported schemas and CI/helper definitions always do.
INPUT_SCOPE = ("app/src", "app/schemas", "app/build.gradle.kts", "app/proguard-rules.pro",
               "build.gradle.kts", "settings.gradle.kts", "gradle", "gradle.properties",
               "gradlew", "gradlew.bat", ".gitignore", ".gitleaks.toml", ".github/workflows",
               "scripts", "docs/handbooks")


@dataclass(frozen=True)
class Step:
    argv: tuple[str, ...]
    timeout: int = 60


@dataclass(frozen=True)
class Group:
    name: str
    steps: tuple[Step, ...]
    blocking: bool = False


def host_class() -> str:
    return {"Windows": "windows", "Darwin": "macos", "Linux": "linux"}.get(platform.system(), "unsupported")


def gradle(host: str, *tasks: str) -> Step:
    launcher = ("cmd.exe", "/d", "/c", "gradlew.bat") if host == "windows" else ("./gradlew",)
    return Step(launcher + ("-q", "--no-daemon", "--max-workers=2") + tasks, 2700)


def groups(profile: str, host: str) -> list[Group]:
    cheap = [
        Group("private-input-boundary", (Step(("@private",)),), True),
        Group("syntax", (Step(("python", "-m", "py_compile", "scripts/validate.py", "scripts/build_handbooks.py", "scripts/check_handbook_render.py")),
                         Step(("node", "--check", "app/src/main/assets/extract.js")))),
        Group("handbook-freshness", (Step(("python", "scripts/build_handbooks.py", "--check")),)),
    ]
    if profile == "fast":
        return cheap
    if profile == "full":
        tasks = ["testDebugUnitTest", "lintDebug", "assembleDebug", "compileDebugAndroidTestKotlin", "createDebugUnitTestCoverageReport"]
        if host != "ci-linux":
            tasks.insert(3, "assembleCapture")
        return cheap + [
            Group("profile-tests", (Step(("python", "-m", "unittest", "discover", "-s", "scripts/tests", "-p", "test_*.py", "-q")),)),
            Group("android", (gradle(host, *tasks), Step(("@schema",)))),
            Group("handbook-render", (Step(("python", "scripts/check_handbook_render.py", "--out", "<reports>/handbook-render"), 240),)),
            Group("security", (Step(("@security",), 600),)),
        ]
    if profile == "platform":
        return [Group("android-emulator-runtime", (Step(("@emulator",)), gradle(host, "connectedDebugAndroidTest")), True)]
    if profile == "release":
        return [Group("source-package", (Step(("@private",)), Step(("@source-package",), 300)), True)]
    return []


def limitations(profile: str, host: str) -> list[str]:
    notes = []
    if profile == "full":
        notes.append("Device runtime belongs to the separate platform profile; compilation is not runtime proof.")
    if host == "ci-linux":
        notes.append("Capture is local-only under ADR-0001; ci-linux never selects assembleCapture.")
    if profile == "release":
        notes.append("Source-only archive from a clean committed candidate; no APK signing, validation rerun, tag, or publication.")
    return notes


def redact(text: str, root: Path, environment: dict[str, str]) -> str:
    values = {value for key, value in environment.items() if value and re.search(r"TOKEN|SECRET|KEY|PASSWORD", key, re.I)}
    for value in sorted(values, key=len, reverse=True):
        text = text.replace(value, "<redacted>")
    for path in (str(root), str(Path.home())):
        text = text.replace(path, "<repo>" if path == str(root) else "<home>")
        text = text.replace(path.replace("\\", "/"), "<repo>" if path == str(root) else "<home>")
    text = re.sub(r"(?i)(?:[A-Z]:[/\\]Users[/\\]|/home/|/Users/)[^/\\\s]+", "<home>", text)
    text = re.sub(r"\b(?:gh[pousr]_[A-Za-z0-9]{20,}|sk-[A-Za-z0-9_-]{20,})\b", "<redacted>", text)
    return text


def spawn(argv: list[str], **kwargs) -> subprocess.Popen:
    """All owned children, including batch shims and timeout cleanup, use this site."""
    flags = kwargs.pop("creationflags", 0)
    if flags & (0x10 | 0x8):  # CREATE_NEW_CONSOLE or DETACHED_PROCESS
        raise ValueError("Console-spawning creation flags are not allowed")
    if os.name == "nt":
        kwargs["creationflags"] = flags | subprocess.CREATE_NO_WINDOW
    else:
        kwargs["start_new_session"] = True
    return subprocess.Popen(argv, **kwargs)


class Run:
    def __init__(self, root: Path, reports: Path, args):
        self.root, self.reports, self.args = root, reports, args
        self.env = dict(os.environ, LC_ALL="C.UTF-8", LANG="C.UTF-8")
        if args.profile == "platform":
            self.env["ANDROID_SERIAL"] = EMULATOR
        self.started = time.time()
        self.logs = 0
        self.tools = {"python": platform.python_version()}

    def raw(self, argv: list[str], timeout=60, data: bytes | None = None) -> tuple[int, bytes]:
        process = spawn(argv, cwd=self.root, env=self.env, stdin=subprocess.PIPE if data is not None else subprocess.DEVNULL,
                        stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        try:
            output, _ = process.communicate(data, timeout=timeout)
            return process.returncode, output
        except subprocess.TimeoutExpired:
            if os.name == "nt":
                killer = spawn(["taskkill", "/PID", str(process.pid), "/T", "/F"], stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
                killer.communicate(timeout=15)
            else:
                os.killpg(process.pid, signal.SIGKILL)
            process.communicate()
            return 124, b"Command timed out; the owned process tree was stopped."

    def command(self, argv: tuple[str, ...] | list[str], timeout=60, data=None, allowed=(0,)) -> bytes:
        resolved = [sys.executable if part == "python" else part.replace("<reports>", str(self.reports)) for part in argv]
        code, output = self.raw(resolved, timeout, data)
        self.logs += 1
        log = self.reports / "logs" / f"command-{self.logs:02}.txt"
        log.parent.mkdir(parents=True, exist_ok=True)
        log.write_text(redact(output.decode(errors="replace"), self.root, self.env)[-60000:], encoding="utf-8")
        if code not in allowed:
            raise RuntimeError(f"Command failed with exit {code}; see logs/{log.name}")
        return output

    def git(self, *args: str) -> bytes:
        code, output = self.raw(["git", *args])
        if code:
            raise RuntimeError("Git input could not be read")
        return output


def excluded(path: str, run: Run) -> bool:
    normalized = path.replace("\\", "/")
    if normalized.startswith(PRIVATE + (".validation-venv/", ".venv/", "reports/")):
        return True
    if (run.root / path).is_relative_to(run.reports):
        return True
    name = Path(path).name.lower()
    return ((name.startswith(".env") and name != ".env.example") or
            name in ("local.properties", "keystore.properties", "id_rsa", "id_ed25519") or
            Path(name).suffix in (".jks", ".keystore", ".key", ".p12", ".pfx", ".db", ".sqlite", ".wal", ".shm") or
            name.endswith((".db-wal", ".db-shm", ".sqlite-wal", ".sqlite-shm")))


def candidate_input(run: Run, *, history=False, base_ref=None) -> bytes:
    exclusions = [":(exclude)app/src/test/resources/fixtures-private/**", ":(exclude)docs/private/**",
                  ":(exclude)reports/**", ":(exclude).validation-venv/**", ":(exclude).venv/**",
                  ":(exclude,icase)**/.env*", ":(exclude,icase).env*",
                  ":(exclude,icase)**/*.keystore", ":(exclude,icase)**/*.jks", ":(exclude,icase)**/*.key",
                  ":(exclude,icase)**/*.p12", ":(exclude,icase)**/*.pfx", ":(exclude,icase)**/*.db*",
                  ":(exclude,icase)**/*.sqlite*", ":(exclude,icase)**/id_rsa", ":(exclude,icase)**/id_ed25519",
                  ":(exclude,icase)local.properties", ":(exclude,icase)keystore.properties"]
    base = (base_ref or run.args.base_ref) if history else "HEAD"
    run.git("rev-parse", "--verify", "--end-of-options", base)
    content = run.git("diff", "--no-ext-diff", base, "--", ".", *exclusions)
    if history:
        content += run.git("log", "--format=", "-p", base + "..HEAD", "--", ".", *exclusions)
    for name in sorted(run.git("ls-files", "--others", "--exclude-standard", "-z").decode().split("\0")):
        path = run.root / name
        if not name or excluded(name, run) or path.is_symlink() or not path.is_file():
            continue
        if not path.resolve().is_relative_to(run.root):
            continue
        data = path.read_bytes()
        if b"\0" not in data[:8192]:
            content += b"\nFile: " + name.encode() + b"\n" + data
    return content


def fingerprint(run: Run) -> str:
    digest = hashlib.sha256()
    names = run.git("ls-files", "--cached", "--others", "--exclude-standard", "-z", "--", *INPUT_SCOPE).decode().split("\0")
    for name in sorted(set(names)):
        if not name or not any(name == scope or name.startswith(scope + "/") for scope in INPUT_SCOPE) or excluded(name, run):
            continue
        path = run.root / name
        digest.update(name.encode() + b"\0")
        if path.is_file() and not path.is_symlink() and path.resolve().is_relative_to(run.root):
            digest.update(path.read_bytes())
        else:
            digest.update(b"<missing-or-link>")
    return digest.hexdigest()


def private_boundary(run: Run):
    names = run.git("ls-files", "--cached", "-z").decode().split("\0")
    if any(name and excluded(name, run) for name in names):
        raise RuntimeError("Private or machine-local inputs are tracked; contents were not read")


def schema_boundary(run: Run):
    if run.git("status", "--porcelain", "--", "app/schemas").strip():
        raise RuntimeError("Room schema exports changed during the build; review and commit the exports")


def emulator_boundary(run: Run):
    result = run.command(("adb", "-s", EMULATOR, "shell", "getprop", "ro.kernel.qemu"), 15)
    if result.strip() != b"1":
        raise RuntimeError("The assigned serial is not a verified Android emulator; no device tests were started")


def apply_public_digest_allowlist(run: Run, payload: bytes) -> tuple[bytes, int]:
    # Gitleaks stdin fragments can cut a full public digest into a secret-like
    # prefix. Apply only the existing exact match exception before fragmentation.
    marker = b'CredentialVault.kt": "'
    if marker not in payload:
        return payload, 0
    try:
        config = run.root / ".gitleaks.toml"
        if config.is_symlink() or not config.resolve().is_relative_to(run.root):
            return payload, 0
        policy = tomllib.loads(config.read_text(encoding="utf-8"))
        source = run.git("show", "HEAD:app/src/main/java/com/bendourthe/milestone/data/credentials/CredentialVault.kt")
        digest = hashlib.sha256(source.replace(b"\r\n", b"\n")).hexdigest()
        expected = '^CredentialVault\\.kt": "' + digest + '"$'
        allowed = any(rule.get("id") == "generic-api-key" and
                      any(group.get("regexTarget") == "match" and expected in group.get("regexes", [])
                          for group in rule.get("allowlists", []))
                      for rule in policy.get("rules", []))
        if not allowed:
            return payload, 0
    except (OSError, RuntimeError, ValueError, TypeError, AttributeError):
        return payload, 0
    token = rb'(?<![A-Za-z0-9_.-])' + re.escape(marker + digest.encode() + b'"')
    return re.subn(token, b'CredentialVault.kt": ""', payload)


def security(run: Run):
    executable = run.args.gitleaks_executable or shutil.which("gitleaks")
    if executable:
        if not Path(executable).is_file():
            raise RuntimeError("The explicit Gitleaks executable is unavailable")
        prefix = [executable]
        config, report = ".gitleaks.toml", str(run.reports / "sarif" / "gitleaks.sarif")
        run.tools["gitleaks"] = run.command(prefix + ["version"], 15).decode().strip()
    elif shutil.which("docker"):
        run.command(("docker", "image", "inspect", GITLEAKS_IMAGE, "--format", "{{.Id}}"), 15)
        prefix = ["docker", "run", "--rm", "-i", "--pull=never", "--network=none",
                  "--mount", f"type=bind,source={run.root / '.gitleaks.toml'},target=/config/gitleaks.toml,readonly",
                  "--mount", f"type=bind,source={run.reports / 'sarif'},target=/report", GITLEAKS_IMAGE]
        config, report = "/config/gitleaks.toml", "/report/gitleaks.sarif"
        run.tools["gitleaks"] = GITLEAKS_IMAGE
    else:
        raise RuntimeError("Security not covered: install Gitleaks, pass --gitleaks-executable, or preload the documented Docker image")
    common = prefix + ["stdin", "--config", config, "--redact", "--no-banner", "--exit-code", "1"]
    control = b'api_key = "' + hashlib.sha256(b"synthetic-validation-positive-control").hexdigest().encode() + b'"\n'
    output = run.command(common, 60, control, allowed=(1,))
    if b"leaks found" not in output.lower():
        raise RuntimeError("The synthetic secret positive control was not detected")
    payload = candidate_input(run, history=True)
    scanned, exemptions = apply_public_digest_allowlist(run, payload)
    run.command(common + ["--report-format", "sarif", "--report-path", report], 600, scanned)
    sarif = run.reports / "sarif" / "gitleaks.sarif"
    document = json.loads(sarif.read_text())
    findings = sum(len(item.get("results", [])) for item in document.get("runs", []))
    if findings:
        raise RuntimeError("Security findings remain in the redacted SARIF report")
    (run.reports / "metadata" / "security.json").write_text(json.dumps({"status": "PASS", "base_ref": run.args.base_ref, "positive_control_detected": True, "input_sha256": hashlib.sha256(payload).hexdigest(), "input_bytes": len(payload), "scanned_input_sha256": hashlib.sha256(scanned).hexdigest(), "scanned_bytes": len(scanned), "public_metadata_exemptions": exemptions, "findings": 0}, indent=2) + "\n", encoding="utf-8")


def source_package(run: Run):
    if run.git("status", "--porcelain", "--untracked-files=all", "-z").strip():
        raise RuntimeError("Source packaging requires a clean committed candidate")
    head = run.git("rev-parse", "HEAD").decode().strip()
    names = run.git("ls-tree", "-r", "--name-only", "-z", head).decode().split("\0")
    if any(name and excluded(name, run) for name in names):
        raise RuntimeError("Committed source contains private or machine-local paths; contents were not read")
    source = run.git("show", head + ":app/build.gradle.kts").decode()
    version = re.search(r'versionName\s*=\s*"([^"]+)"', source).group(1)
    if not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+(?:[-+][0-9A-Za-z.-]+)?", version):
        raise RuntimeError("The committed source version is not a safe release version")
    archive_args = ("-c", "core.autocrlf=false", "-c", "core.eol=lf", "archive", "--format=zip", head)
    archive_bytes = run.git(*archive_args)
    with zipfile.ZipFile(io.BytesIO(archive_bytes)) as archive:
        names = archive.namelist()
        if not names or any(name.startswith(("/", ".git/")) or ".." in name.split("/") or excluded(name, run) for name in names):
            raise RuntimeError("Source archive contains private, generated, or unsafe paths")
        if archive.testzip() is not None or "app/build.gradle.kts" not in names:
            raise RuntimeError("Source archive integrity check failed")
    # Compare a second canonical archive of the same immutable commit, not a
    # worktree zip with timestamps or machine-local files.
    if archive_bytes != run.git(*archive_args) or head != run.git("rev-parse", "HEAD").decode().strip():
        raise RuntimeError("Source archive or candidate changed during packaging")
    directory = run.reports / "release"
    directory.mkdir(exist_ok=True)
    filename = "MileStone-v" + version + "-source.zip"
    (directory / filename).write_bytes(archive_bytes)
    metadata = {"path": "release/" + filename, "commit": head, "version": version,
                "sha256": hashlib.sha256(archive_bytes).hexdigest(), "canonical_archive_verified": True,
                "files": len(names), "private_inputs_included": False, "publication": "not performed"}
    (directory / "artifacts.json").write_text(json.dumps(metadata, indent=2) + "\n", encoding="utf-8")


ACTIONS = {"@private": private_boundary, "@schema": schema_boundary, "@emulator": emulator_boundary, "@security": security, "@source-package": source_package}


def clear_exports(run: Run, *, security_outputs=False):
    """Remove only this profile's exported files inside the chosen reports tree."""
    paths = list((run.reports / "junit").glob(f"{run.args.profile}-*.xml"))
    if run.args.profile == "full":
        # Coverage and lint belong to the full Gradle graph, never platform.
        paths += list((run.reports / "coverage").glob("*.xml"))
        paths.append(run.reports / "lint-results-debug.xml")
        if security_outputs:
            paths += [run.reports / "sarif/gitleaks.sarif", run.reports / "sarif/index.json", run.reports / "metadata/security.json"]
    for path in paths:
        if path.is_symlink() or not path.resolve().is_relative_to(run.reports):
            raise RuntimeError("An exported report path escapes the selected reports directory")
        if path.is_file():
            path.unlink()


def collect_reports(run: Run, succeeded: bool) -> dict:
    clear_exports(run)
    counts = dict(tests=0, failures=0, errors=0, skipped=0)
    evidence_origin = "selected task outputs, including Gradle-validated up-to-date outputs" if succeeded else "partial reports written during this invocation; no old XML counted"
    source = "app/build/outputs/androidTest-results/connected" if run.args.profile == "platform" else "app/build/test-results/testDebugUnitTest"
    if run.args.profile in ("full", "platform"):
        for index, path in enumerate(sorted((run.root / source).rglob("*.xml"))):
            if not succeeded and path.stat().st_mtime < run.started:
                continue
            document = ET.fromstring(path.read_text(encoding="utf-8"))
            suites = [document] if document.tag == "testsuite" else document.findall("testsuite")
            for suite in suites:
                for key in counts:
                    counts[key] += int(suite.get(key, "0"))
            destination = run.reports / "junit" / f"{run.args.profile}-{index:03}.xml"
            destination.write_text(redact(ET.tostring(document, encoding="unicode"), run.root, run.env), encoding="utf-8")
        for path in sorted((run.root / "app/build/reports").rglob("*.xml")) if run.args.profile == "full" else ():
            if "coverage" in str(path).lower() or "jacoco" in str(path).lower():
                if succeeded or path.stat().st_mtime >= run.started:
                    (run.reports / "coverage" / path.name).write_text(redact(path.read_text(), run.root, run.env), encoding="utf-8")
                    counter = ET.parse(path).getroot().find("counter[@type='LINE']")
                    if counter is not None:
                        covered, missed = int(counter.get("covered", "0")), int(counter.get("missed", "0"))
                        counts["coverage"] = {"covered": covered, "total": covered + missed, "percent": round(100 * covered / (covered + missed), 2) if covered + missed else None}
            elif path.name == "lint-results-debug.xml" and (succeeded or path.stat().st_mtime >= run.started):
                (run.reports / path.name).write_text(redact(path.read_text(), run.root, run.env), encoding="utf-8")
    if run.args.profile in ("full", "platform") and succeeded and counts["tests"] == 0:
        raise RuntimeError("The successful test task produced no JUnit evidence")
    index = [{"tool": "gitleaks", "path": "sarif/" + p.name} for p in sorted((run.reports / "sarif").glob("*.sarif"))]
    (run.reports / "sarif" / "index.json").write_text(json.dumps(index, indent=2) + "\n", encoding="utf-8")
    counts["evidence_origin"] = evidence_origin if run.args.profile in ("full", "platform") else "No Android test runner selected"
    return counts


def write_summary(run: Run, receipts: list[dict], environment: dict) -> dict:
    status = "PASS" if receipts and all(item["status"] == "PASS" for item in receipts) else "FAIL"
    summary = {"status": status, "profiles": receipts, "limitations": [note for item in receipts for note in item.get("limitations", [])]}
    (run.reports / "summary.json").write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
    rows = ["# Validation", "", f"Status: {status}", "", "| Profile / group | Status | Seconds |", "|---|---|---|"]
    for receipt in receipts:
        for group in receipt.get("groups", []):
            rows.append(f"| {receipt['profile']} / {group['name']} | {group['status']} | {group['seconds']:.2f} |")
            if group.get("error"):
                rows.append(f"\n{group['name']}: {group['error']}\n")
    for receipt in receipts:
        if "counts" in receipt:
            rows.append(f"\n{receipt['profile']} test reports: " + ", ".join(f"{k}={v}" for k, v in receipt["counts"].items()))
        if receipt.get("evidence_origin"):
            rows.append("\nEvidence scope: " + receipt["evidence_origin"] + ".")
        if receipt.get("coverage"):
            metric = receipt["coverage"]
            rows.append(f"\nCoverage lines: {metric['covered']}/{metric['total']} ({metric['percent']}%).")
        if receipt.get("security"):
            if receipt["security"].get("status") == "STALE":
                rows.append("\nSecurity evidence is stale for the current scanner input; a new scan is required.")
            else:
                rows.append(f"\nSecurity findings: {receipt['security']['findings']}; synthetic positive control detected.")
    rows += ["", "Details: summary.json, junit/, coverage/, sarif/index.json, metadata/environment.json.", "", "Tool versions: " + json.dumps(environment.get("tools", {}), sort_keys=True)]
    rows += ["", *summary["limitations"]]
    (run.reports / "summary.md").write_text("\n".join(rows) + "\n", encoding="utf-8")
    environment.update(status=status, finished_at=datetime.now(timezone.utc).isoformat())
    (run.reports / "metadata" / "environment.json").write_text(json.dumps(environment, indent=2) + "\n", encoding="utf-8")
    return summary


def main(argv=None, *, root=ROOT) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--profile", required=True, choices=PROFILES)
    parser.add_argument("--platform", choices=("windows", "linux", "macos", "ci-linux"), default=host_class())
    parser.add_argument("--reports-dir", type=Path, default=Path("reports"))
    parser.add_argument("--quiet", action="store_true")
    parser.add_argument("--json", action="store_true")
    parser.add_argument("--list", action="store_true")
    parser.add_argument("--base-ref", default="origin/develop")
    parser.add_argument("--gitleaks-executable")
    args = parser.parse_args(argv)
    selected = groups(args.profile, args.platform)
    if args.list:
        listing = {"profile": args.profile, "platform": args.platform, "groups": [{"name": group.name, "blocking": group.blocking, "commands": [{"argv": list(step.argv), "timeout_seconds": step.timeout} for step in group.steps]} for group in selected], "limitations": limitations(args.profile, args.platform)}
        print(json.dumps(listing, indent=2) if args.json else "\n".join(f"{group.name}: " + "; ".join(" ".join(step.argv) for step in group.steps) for group in selected) or "report: aggregate existing receipts only")
        return 0
    reports = args.reports_dir if args.reports_dir.is_absolute() else root / args.reports_dir
    reports = reports.resolve()
    for name in ("results", "logs", "junit", "coverage", "sarif", "metadata"):
        (reports / name).mkdir(parents=True, exist_ok=True)
    run = Run(root, reports, args)
    environment = dict(profile=args.profile, host=args.platform, os=platform.system(), os_release=platform.release(), shell=Path(os.environ.get("SHELL", "Windows process" if os.name == "nt" else "POSIX process")).name, python=platform.python_version(), tools=run.tools, started_at=datetime.now(timezone.utc).isoformat())
    receipts = []
    current = None
    try:
        if args.profile != "report":
            clear_exports(run, security_outputs=True)
        current = fingerprint(run)
        if args.profile == "report":
            for path in sorted((reports / "results").glob("*.json")):
                receipt = json.loads(path.read_text())
                stale_archive = receipt.get("profile") == "release" and receipt.get("package_head") != run.git("rev-parse", "HEAD").decode().strip()
                if receipt.get("source_sha256") != current or stale_archive:
                    receipt = dict(receipt, status="FAIL", limitations=receipt.get("limitations", []) + ["A retained profile receipt is stale for the current tree."])
                scan = receipt.get("security")
                if receipt.get("profile") == "full" and any(group.get("name") == "security" and group.get("status") == "PASS" for group in receipt.get("groups", [])):
                    payload_hash = hashlib.sha256(candidate_input(run, history=True, base_ref=scan.get("base_ref") if scan else None)).hexdigest()
                    if not scan or scan.get("input_sha256") != payload_hash:
                        receipt = dict(receipt, status="FAIL", security=dict(scan or {}, status="STALE"), limitations=receipt.get("limitations", []) + ["Retained security evidence is stale for the current scanner input; code-test evidence has its separate source fingerprint."])
                        receipt["groups"] = [dict(group, status="STALE") if group.get("name") == "security" else group for group in receipt.get("groups", [])]
                receipts.append(receipt)
        else:
            version_commands = {"git": ["git", "--version"]}
            if args.profile != "release":
                version_commands["node"] = ["node", "--version"]
            if args.profile in ("full", "platform"):
                version_commands["java"] = ["java", "-version"]
            if args.profile == "platform":
                version_commands["adb"] = ["adb", "version"]
            for tool, command in version_commands.items():
                try:
                    code, output = run.raw(command, 10)
                    clean = redact(output.decode(errors="replace"), root, run.env)
                    version = re.search(r'(?:openjdk|java) version "([^"]+)"', clean) if tool == "java" else None
                    run.tools[tool] = version.group(1) if version else clean.splitlines()[0][:160] if code == 0 and clean else "unavailable"
                except OSError:
                    run.tools[tool] = "unavailable"
            results, blocked = [], False
            for group in selected:
                started = time.monotonic()
                result = {"name": group.name, "status": "BLOCKED" if blocked else "PASS", "seconds": 0.0}
                if not blocked:
                    try:
                        if sys.version_info < (3, 11):
                            raise RuntimeError("Python 3.11 or newer is required")
                        if args.platform.removeprefix("ci-") != host_class():
                            raise RuntimeError("Selected host class does not match this host; use --list for another host")
                        for step in group.steps:
                            if step.argv[0].startswith("@"):
                                ACTIONS[step.argv[0]](run)
                            else:
                                run.command(step.argv, step.timeout)
                    except Exception as error:
                        result.update(status="FAIL", error=redact(f"{type(error).__name__}: {error}", root, run.env))
                        blocked = group.blocking
                result["seconds"] = round(time.monotonic() - started, 3)
                results.append(result)
                if not args.quiet and not args.json:
                    print(f"{group.name}: {result['status']}")
            passed = all(group["status"] == "PASS" for group in results)
            test_group_passed = any(item["status"] == "PASS" and item["name"] in ("android", "android-emulator-runtime") for item in results)
            counts = collect_reports(run, test_group_passed)
            coverage = counts.pop("coverage", None)
            evidence_origin = counts.pop("evidence_origin")
            scan = reports / "metadata/security.json"
            security_result = json.loads(scan.read_text()) if any(item["name"] == "security" and item["status"] == "PASS" for item in results) and scan.is_file() else None
            package_metadata = reports / "release/artifacts.json"
            package_head = json.loads(package_metadata.read_text())["commit"] if args.profile == "release" and passed else None
            receipt = dict(profile=args.profile, platform=args.platform, status="PASS" if passed else "FAIL", source_sha256=current, package_head=package_head, groups=results, counts=counts, evidence_origin=evidence_origin, coverage=coverage, security=security_result, limitations=limitations(args.profile, args.platform))
            (reports / "results" / f"{args.profile}-{args.platform}.json").write_text(json.dumps(receipt, indent=2) + "\n", encoding="utf-8")
            receipts = [receipt]
    except Exception as error:
        receipts = [dict(profile=args.profile, platform=args.platform, source_sha256=current, status="FAIL", groups=[dict(name="profile", status="FAIL", seconds=0.0, error=redact(f"{type(error).__name__}: {error}", root, run.env))])]
        if args.profile != "report":
            (reports / "results" / f"{args.profile}-{args.platform}.json").write_text(json.dumps(receipts[0], indent=2) + "\n", encoding="utf-8")
    summary = write_summary(run, receipts, environment)
    print(json.dumps(summary, indent=2) if args.json else f"{args.profile}: {summary['status']}; details in the selected reports directory")
    return 0 if summary["status"] == "PASS" else 1


if __name__ == "__main__":
    raise SystemExit(main())
