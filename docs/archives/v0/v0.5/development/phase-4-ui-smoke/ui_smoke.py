"""Emulator-only Phase 4 smoke: seeded synthetic Chase cards in the installed debug app, driven through the real UI.

Refuses any device that is not emulator-5554 with ro.kernel.qemu=1. Seeds synthetic rows only. Taps by the accessibility
tree from `uiautomator dump`, never by fixed coordinates.
"""
from __future__ import annotations
import json
import re
import sqlite3
import subprocess
import sys
import time
from pathlib import Path

ADB = "C:/Users/BEDOURTHE/AppData/Local/Android/Sdk/platform-tools/adb.exe"
SERIAL = "emulator-5554"
PKG = "com.bendourthe.milestone"
OUT = Path(sys.argv[2]).resolve()
APK = Path(sys.argv[1]).resolve()
NO_WINDOW = getattr(subprocess, "CREATE_NO_WINDOW", 0)


def adb(*args, binary=False, check=True):
    proc = subprocess.run([ADB, "-s", SERIAL, *args], capture_output=True, timeout=180, creationflags=NO_WINDOW, stdin=subprocess.DEVNULL)
    if check and proc.returncode:
        raise SystemExit(f"adb {args[0]} failed")
    return proc.stdout if binary else proc.stdout.decode("utf-8", "replace").strip()


def dump() -> str:
    adb("shell", "uiautomator", "dump", "/sdcard/ui.xml", check=False)
    return adb("exec-out", "cat", "/sdcard/ui.xml", binary=True).decode("utf-8", "replace")


def tap(xml: str, predicate) -> bool:
    for node in re.finditer(r"<node [^>]*>", xml):
        attrs = dict(re.findall(r'(\S+?)="([^"]*)"', node.group(0)))
        if predicate(attrs):
            x1, y1, x2, y2 = map(int, re.findall(r"\d+", attrs["bounds"]))
            adb("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))
            return True
    return False


def wait_for(predicate, seconds=20):
    deadline = time.time() + seconds
    xml = ""
    while time.time() < deadline:
        xml = dump()
        if predicate(xml):
            return xml
        time.sleep(1)
    return xml


def shot(name):
    (OUT / name).write_bytes(adb("exec-out", "screencap", "-p", binary=True))


def main() -> int:
    OUT.mkdir(parents=True, exist_ok=True)
    if adb("shell", "getprop", "ro.kernel.qemu") != "1":
        raise SystemExit("not an emulator; refusing")
    adb("uninstall", PKG, check=False)
    adb("install", str(APK))
    adb("shell", "am", "start", "-W", "-n", f"{PKG}/.ui.MainActivity")
    db_local = OUT / "seed.db"
    for _ in range(30):
        time.sleep(2)
        data = adb("exec-out", "run-as", PKG, "cat", "databases/milestone.db", binary=True, check=False)
        if data.startswith(b"SQLite format 3"):
            break
    adb("shell", "am", "force-stop", PKG)
    time.sleep(1)
    for suffix in ("", "-wal", "-shm"):
        data = adb("exec-out", "run-as", PKG, "cat", f"databases/milestone.db{suffix}", binary=True, check=False)
        path = Path(str(db_local) + suffix)
        path.unlink(missing_ok=True)
        if data and not data.startswith(b"cat:"):
            path.write_bytes(data)
    con = sqlite3.connect(db_local)
    con.execute("PRAGMA wal_checkpoint(TRUNCATE)")
    con.execute("PRAGMA journal_mode=DELETE")
    version = con.execute("PRAGMA user_version").fetchone()[0]
    con.execute("INSERT OR REPLACE INTO loyalty_accounts (programId, consecutiveFailures, sortOrder, status, addedEpochMs) VALUES ('chase', 0, 0, 'CONNECTED', 1)")
    con.execute("INSERT INTO issuer_connections (connectionId, programId, segment, sortOrder, addedEpochMs, inventoryReadEpochMs, inventoryComplete) VALUES ('chase', 'chase', 'PERSONAL', 0, 1, NULL, 0), ('chase_2', 'chase', 'BUSINESS', 1, 2, NULL, 0)")
    now = int(time.time() * 1000)
    rows = [("c_0000000000000001", "1111", "Synthetic Reserve", 0, "PERSONAL"), ("c_0000000000000002", "3333", "Synthetic Ink", 1, "BUSINESS"),
            ("c_0000000000000003", "5555", "Synthetic Unsorted", 2, None)]
    for card_id, digits, name, order, segment in rows:
        con.execute("INSERT INTO cards (programId, cardId, lastDigits, name, sortOrder, cardListReadEpochMs, benefitsReadEpochMs, benefitsReadGeneration, segment, segmentOverride) VALUES ('chase', ?, ?, ?, ?, ?, ?, 1, ?, NULL)",
                    (card_id, digits, name, order, now, now, segment))
    con.commit()
    con.close()
    adb("push", str(db_local), "/data/local/tmp/milestone-seed.db")
    adb("shell", f"run-as {PKG} sh -c 'cp /data/local/tmp/milestone-seed.db databases/milestone.db && rm -f databases/milestone.db-wal databases/milestone.db-shm'")
    adb("shell", "rm", "-f", "/data/local/tmp/milestone-seed.db")

    adb("shell", "am", "start", "-W", "-n", f"{PKG}/.ui.MainActivity")
    xml = wait_for(lambda x: 'text="Benefits"' in x, 30)
    tapped_tab = tap(xml, lambda a: a.get("text") == "Benefits" and a.get("clickable") == "true") or tap(xml, lambda a: a.get("text") == "Benefits")
    xml = wait_for(lambda x: "Chase Personal" in x, 20)
    shot("benefits-institutions.png")
    institutions = {"personal": "Chase Personal" in xml, "business": "Chase Business" in xml}
    tap(xml, lambda a: a.get("content-desc", "").startswith("Chase Business"))
    xml = wait_for(lambda x: "Synthetic Ink" in x, 20)
    shot("chase-business.png")
    business = {
        "ink_with_ending": "Synthetic Ink, card ending 3333" in xml,
        "reserve_absent": "Synthetic Reserve" not in xml,
        "connections_section": "Chase sign-ins" in xml,
        "first_sign_in_listed": "Sign-in 1 (balance)" in xml,
        "second_sign_in_listed": "Sign-in 2" in xml,
        "add_button": "Add a business sign-in" in xml,
    }
    crash = adb("logcat", "-d", "-s", "AndroidRuntime:E", check=False)
    result = {"device": SERIAL, "db_version": version, "tapped_benefits_tab": tapped_tab, "institutions": institutions, "business_page": business,
              "fatal_exceptions": sum(1 for line in crash.splitlines() if "FATAL EXCEPTION" in line)}
    result["passed"] = version == 5 and tapped_tab and all(institutions.values()) and all(business.values()) and result["fatal_exceptions"] == 0
    (OUT / "ui-smoke.json").write_text(json.dumps(result, indent=1), encoding="utf-8")
    print(json.dumps(result))
    return 0 if result["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
