"""Emulator-only upgrade smoke: v0.4 debug app with a seeded version 4 database, upgraded in place to v0.5.

Refuses any device that is not emulator-5554 with ro.kernel.qemu=1. Seeds synthetic rows only.
"""
from __future__ import annotations
import json
import sqlite3
import subprocess
import sys
import time
from pathlib import Path

ADB = "C:/Users/BEDOURTHE/AppData/Local/Android/Sdk/platform-tools/adb.exe"
SERIAL = "emulator-5554"
PKG = "com.bendourthe.milestone"
HERE = Path(__file__).resolve().parent
SCHEMA_4 = "C:/Users/BEDOURTHE/Documents/Benjamin/MileStone/app/schemas/com.bendourthe.milestone.data.db.MileStoneDatabase/4.json"
NO_WINDOW = getattr(subprocess, "CREATE_NO_WINDOW", 0)
LOG: list[dict] = []


def adb(*args: str, binary: bool = False, check: bool = True):
    proc = subprocess.run([ADB, "-s", SERIAL, *args], capture_output=True, timeout=180, creationflags=NO_WINDOW, stdin=subprocess.DEVNULL)
    out = proc.stdout if binary else proc.stdout.decode("utf-8", "replace").strip()
    LOG.append({"argv": list(args), "exit": proc.returncode, "stdout": None if binary else out[:300]})
    if check and proc.returncode:
        raise SystemExit(f"adb {' '.join(args)} failed: {proc.stderr.decode('utf-8', 'replace')[:300]}")
    return out


def launch_and_stop(seconds: int = 8) -> None:
    adb("shell", "am", "start", "-W", "-n", f"{PKG}/.ui.MainActivity")
    time.sleep(seconds)
    adb("shell", "am", "force-stop", PKG)
    time.sleep(1)


def pull_db(target: Path) -> None:
    for suffix in ("", "-wal", "-shm"):
        data = adb("exec-out", "run-as", PKG, "cat", f"databases/milestone.db{suffix}", binary=True, check=False)
        path = Path(str(target) + suffix)
        if data:
            path.write_bytes(data)
        elif path.exists():
            path.unlink()


def main() -> int:
    if adb("shell", "getprop", "ro.kernel.qemu") != "1":
        raise SystemExit("not an emulator; refusing")
    adb("uninstall", PKG, check=False)
    adb("install", str(HERE / "v040-debug.apk"))
    launch_and_stop()

    # The app opens its database lazily, so build the exact version 4 database from Room's exported schema.
    v4 = HERE / "v4.db"
    for suffix in ("", "-wal", "-shm"):
        Path(str(v4) + suffix).unlink(missing_ok=True)
    schema = json.loads(Path(SCHEMA_4).read_text(encoding="utf-8"))["database"]
    con = sqlite3.connect(v4)
    for entity in schema["entities"]:
        con.execute(entity["createSql"].replace("${TABLE_NAME}", entity["tableName"]))
        for index in entity.get("indices", []):
            con.execute(index["createSql"].replace("${TABLE_NAME}", entity["tableName"]))
    for query in schema["setupQueries"]:
        con.execute(query)
    con.execute(f"PRAGMA user_version = {schema['version']}")
    con.commit()
    user_version_before = con.execute("PRAGMA user_version").fetchone()[0]
    con.execute("PRAGMA journal_mode=DELETE")
    con.execute("INSERT OR REPLACE INTO loyalty_accounts (programId, consecutiveFailures, sortOrder, status, addedEpochMs) VALUES ('amex', 0, 0, 'CONNECTED', 1)")
    con.execute("INSERT INTO cards (programId, lastDigits, name, sortOrder, cardListReadEpochMs, benefitsReadEpochMs, benefitsReadGeneration) VALUES ('amex', '1234', 'Synthetic Platinum', 0, 10, 11, 2)")
    con.execute("INSERT INTO cards (programId, lastDigits, name, sortOrder, cardListReadEpochMs, benefitsReadEpochMs, benefitsReadGeneration) VALUES ('amex', '54321', 'Synthetic Gold', 1, 10, NULL, 0)")
    con.execute("INSERT INTO benefits (stableKey, programId, lastDigits, section, position, title, earnedCents, remainingCents, targetCents, periodLabel, periodEndEpochDay, periodLength, completed, description, enrollment) VALUES ('synthetic-k1', 'amex', '1234', 'CREDIT_EARNED', 0, 'Synthetic credit', 500, 1000, 1500, 'Monthly', 30000, 'MONTHLY', 0, NULL, NULL)")
    con.execute("INSERT INTO benefits (stableKey, programId, lastDigits, section, position, title, completed) VALUES ('synthetic-k2', 'amex', '54321', 'LISTED', 0, 'Synthetic listed', 0)")
    con.execute("INSERT INTO benefit_reminders (benefitKey, periodEndEpochDay, programId, lastDigits, sentEpochMs) VALUES ('synthetic-k1', 30000, 'amex', '1234', 12)")
    con.commit()
    con.close()
    adb("push", str(v4), "/data/local/tmp/milestone-seed.db")
    adb("shell", f"run-as {PKG} sh -c 'mkdir -p databases && cp /data/local/tmp/milestone-seed.db databases/milestone.db && rm -f databases/milestone.db-wal databases/milestone.db-shm'")
    # The seeded database is what the v0.4 app would open: confirm v0.4 opens it without error before upgrading.
    launch_and_stop()
    seeded = HERE / "v4-opened.db"
    pull_db(seeded)
    check = sqlite3.connect(seeded)
    opened_by_v4 = check.execute("SELECT COUNT(*) FROM cards").fetchone()[0] == 2 and check.execute("PRAGMA user_version").fetchone()[0] == 4
    check.close()
    adb("shell", "rm", "-f", "/data/local/tmp/milestone-seed.db")

    adb("install", "-r", str(HERE / "v050-debug.apk"))
    # The app opens its database during the start-of-session refresh; wait for the migrated version, up to 60 s.
    adb("shell", "am", "start", "-W", "-n", f"{PKG}/.ui.MainActivity")
    deadline = time.time() + 60
    probe = HERE / "probe.db"
    while time.time() < deadline:
        time.sleep(3)
        pull_db(probe)
        try:
            pc = sqlite3.connect(probe)
            version = pc.execute("PRAGMA user_version").fetchone()[0]
            pc.close()
        except sqlite3.DatabaseError:
            version = None
        if version == 5:
            break
    adb("shell", "am", "force-stop", PKG)
    time.sleep(1)
    running_crash = adb("shell", "dumpsys", "activity", "processes", PKG, check=False)
    v5 = HERE / "v5.db"
    pull_db(v5)
    con = sqlite3.connect(v5)
    con.execute("PRAGMA wal_checkpoint(TRUNCATE)")
    result = {
        "device": SERIAL,
        "qemu": True,
        "user_version_before_seed": user_version_before,
        "user_version_after_upgrade": con.execute("PRAGMA user_version").fetchone()[0],
        "cards": con.execute("SELECT programId, cardId, lastDigits, name, sortOrder, benefitsReadGeneration, segment FROM cards ORDER BY sortOrder").fetchall(),
        "benefits": con.execute("SELECT stableKey, cardId, section, earnedCents FROM benefits ORDER BY stableKey").fetchall(),
        "reminders": con.execute("SELECT benefitKey, cardId FROM benefit_reminders").fetchall(),
        "connections": con.execute("SELECT COUNT(*) FROM issuer_connections").fetchone()[0],
        "coverage": con.execute("SELECT COUNT(*) FROM card_coverage").fetchone()[0],
        "foreign_key_violations": con.execute("PRAGMA foreign_key_check").fetchall(),
        "integrity": con.execute("PRAGMA integrity_check").fetchone()[0],
    }
    con.close()
    crash_log = adb("logcat", "-d", "-s", "AndroidRuntime:E", check=False)
    result["androidruntime_errors"] = sum(1 for line in crash_log.splitlines() if "FATAL EXCEPTION" in line)
    expected_cards = [("amex", "1234", "1234", "Synthetic Platinum", 0, 2, None), ("amex", "54321", "54321", "Synthetic Gold", 1, 0, None)]
    result["checks"] = {
        "v4_app_kept_seeded_database": opened_by_v4,
        "upgraded_to_5": result["user_version_after_upgrade"] == 5,
        "cards_preserved_same_keys": [tuple(r) for r in result["cards"]] == expected_cards,
        "benefits_preserved": [tuple(r) for r in result["benefits"]] == [("synthetic-k1", "1234", "CREDIT_EARNED", 500), ("synthetic-k2", "54321", "LISTED", None)],
        "reminders_preserved": [tuple(r) for r in result["reminders"]] == [("synthetic-k1", "1234")],
        "new_tables_empty": result["connections"] == 0 and result["coverage"] == 0,
        "no_fk_violations": result["foreign_key_violations"] == [],
        "integrity_ok": result["integrity"] == "ok",
        "no_crash": result["androidruntime_errors"] == 0,
    }
    result["passed"] = all(result["checks"].values())
    (HERE / "upgrade-smoke.json").write_text(json.dumps({**result, "adb_log": LOG}, indent=1, default=str), encoding="utf-8")
    print(json.dumps({"passed": result["passed"], "checks": result["checks"], "user_version": [user_version_before, result["user_version_after_upgrade"]]}))
    return 0 if result["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
