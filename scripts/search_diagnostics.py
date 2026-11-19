"""Failure-only, emulator-only Search evidence. Never supplies qualification."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import signal
import threading
import time
from types import SimpleNamespace
import uuid

import search_benchmark as benchmark
import validate as validator

APKS = {"target_sha256": "app/build/outputs/apk/benchmark/app-benchmark.apk",
        "controller_sha256": "searchBenchmark/build/outputs/apk/benchmark/searchBenchmark-benchmark.apk"}
CONFIG = b'''buffers { size_kb: 16384 fill_policy: DISCARD }
duration_ms: 35000
data_sources { config { name: "linux.ftrace" ftrace_config {
ftrace_events: "sched/sched_switch"
ftrace_events: "sched/sched_waking"
ftrace_events: "sched/sched_wakeup"
ftrace_events: "task/task_newtask"
ftrace_events: "task/task_rename"
} } }
data_sources { config { name: "android.surfaceflinger.frametimeline" } }
'''


class NotEligible(ValueError):
    pass


def canonical_uuid(value):
    try:
        return isinstance(value, str) and str(uuid.UUID(value)) == value
    except ValueError:
        return False


def contained(root, path):
    if not path.resolve().is_relative_to(root):
        raise NotEligible("Evidence path leaves the repository")
    for current in (path, *path.parents):
        if current.is_symlink():
            raise NotEligible("Evidence path contains a link")
        if current == root:
            break
    return path


def read_json(root, path):
    contained(root, path)
    if not path.is_file() or path.stat().st_size > 1_048_576:
        raise NotEligible("Required bounded evidence is unavailable")
    return json.loads(path.read_text(encoding="utf-8"))


def protected_files(root, reports):
    result = {}
    total = 0
    for name in ("results", "metadata", "junit"):
        folder = contained(root, reports / name)
        for path in sorted(folder.glob("*")):
            contained(root, path)
            if not path.is_file():
                continue
            total += path.stat().st_size
            if total > 33_554_432 or len(result) >= 1000:
                raise NotEligible("Original evidence exceeds its bounded inventory")
            result[str(path.relative_to(root))] = hashlib.sha256(path.read_bytes()).hexdigest()
    return result


def apk_hashes(root):
    result = {}
    for key, name in APKS.items():
        path = contained(root, root / name)
        if not path.is_file() or path.stat().st_size > 67_108_864:
            raise NotEligible("Original APK is unavailable")
        result[key] = benchmark._digest(path)
    return result


def load_failure(root, reports, platform, current):
    """Pure file gate: no device calls for missing, stale or unrelated failures."""
    try:
        profile = read_json(root, reports / f"results/platform-{platform}.json")
        artifact = read_json(root, reports / "metadata/search-benchmark-artifacts.json")
        metrics = read_json(root, reports / "metadata/search-latency.json")
        if not all(isinstance(value, dict) for value in (profile, artifact, metrics)) or not isinstance(profile.get("groups"), list) or any(not isinstance(row, dict) for row in profile["groups"]):
            raise NotEligible("Original evidence schema did not qualify")
        if (profile.get("profile") != "platform" or profile.get("platform") != platform or
                profile.get("status") != "FAIL" or profile.get("source_sha256") != current or
                not any(row.get("name") == "optimized-search-performance" and row.get("status") == "FAIL"
                        for row in profile.get("groups", []))):
            raise NotEligible("Current optimized failure is not established")
        owner = profile.get("invocation_id")
        if (not canonical_uuid(owner) or artifact.get("validation_invocation_id") != owner or
                not canonical_uuid(artifact.get("run_id")) or artifact.get("build_type") != "benchmark"):
            raise NotEligible("Original invocation binding is unavailable")
        try:
            benchmark.validate_metrics(metrics, artifact["run_id"])
        except ValueError as error:
            if not str(error).startswith("Query-to-visible p95 is "):
                raise NotEligible("Original metrics do not prove a latency-only failure") from error
        else:
            raise NotEligible("Original timing gate passed")
        hashes = apk_hashes(root)
        if any(artifact.get(key) != value for key, value in hashes.items()):
            raise NotEligible("Current APK pair differs from original evidence")
        files = protected_files(root, reports)
        if not any(Path(name).name.startswith("platform-") and name.endswith(".xml") for name in files):
            raise NotEligible("Original exported platform JUnit is missing")
        return dict(invocation_id=owner, artifact=artifact, metrics=metrics,
                    source_sha256=current, original_files=files)
    except NotEligible:
        raise
    except Exception as error:
        raise NotEligible("Original evidence is missing or malformed") from error


def adb(run, *args, timeout=15, data=None):
    code, output = run.raw(["adb", "-s", benchmark.SERIAL, *args], timeout, data)
    if code:
        raise RuntimeError("Assigned emulator diagnostic command failed")
    return output


def boot(run):
    if adb(run, "shell", "getprop", "ro.kernel.qemu").strip() != b"1":
        raise RuntimeError("Assigned target is not an emulator")
    value = adb(run, "shell", "cat", "/proc/sys/kernel/random/boot_id").decode().strip()
    if not canonical_uuid(value):
        raise RuntimeError("Emulator boot identity is unavailable")
    return value


def marker(logs, after, needle="timing phase=measured samples=30"):
    for line in logs.decode().splitlines():
        found = re.match(r"^\s*([0-9]+)\.([0-9]+)\s", line)
        if found and needle in line:
            stamp = int(found[1]) * 1_000_000_000 + int((found[2] + "000000000")[:9])
            if stamp >= after:
                return stamp
    return None


def host_metadata():
    result = dict(logical_processors=os.cpu_count())
    if hasattr(os, "sched_getaffinity"):
        result["allowed_logical_processors"] = sorted(os.sched_getaffinity(0))
    path = Path("/proc/cpuinfo")
    if path.is_file():
        pairs = set()
        for block in path.read_text().split("\n\n"):
            fields = dict(line.split(":", 1) for line in block.splitlines() if ":" in line)
            fields = {key.strip(): value.strip() for key, value in fields.items()}
            if all(fields.get(key, "").isdigit() for key in ("physical id", "core id")):
                pairs.add((int(fields["physical id"]), int(fields["core id"])))
        result["reported_physical_cores"] = len(pairs) or None
    return result


def capture(run, out, origin, stop, result, cutoff, expected_boot, invocation):
    remote = "/data/misc/perfetto-traces/milestone-search-" + uuid.uuid4().hex + ".pftrace"
    created = False
    try:
        deadline = time.monotonic() + 300
        while not stop.wait(.2):
            if time.monotonic() > deadline:
                raise RuntimeError("Measured marker deadline expired")
            path = out / "metadata/search-benchmark-artifacts.json"
            if not path.is_file():
                continue
            try:
                artifact = read_json(run.root, path)
            except (OSError, ValueError):
                continue
            if not canonical_uuid(artifact.get("run_id")) or artifact.get("validation_invocation_id") != invocation or any(
                    artifact.get(key) != origin["artifact"][key] for key in APKS):
                raise RuntimeError("Diagnostic artifact binding changed")
            code, value = run.raw(["adb", "-s", benchmark.SERIAL, "shell", "pidof", benchmark.TARGET], 10)
            pid = value.strip().decode()
            if code or not re.fullmatch(r"[1-9][0-9]*", pid):
                continue
            stamp = marker(adb(run, "logcat", "-d", "-v", "epoch", "--pid", pid,
                               "-s", "MileStoneSearchFixture:I", "*:S"), cutoff)
            if stamp is None:
                continue
            if boot(run) != expected_boot:
                raise RuntimeError("Emulator boot changed")
            controller = adb(run, "shell", "pidof", benchmark.CONTROLLER).strip().decode()
            if not re.fullmatch(r"[1-9][0-9]*", controller):
                raise RuntimeError("Current controller PID is unavailable")
            result.update(target_pid=int(pid), controller_pid=int(controller), run_id=artifact["run_id"],
                          marker_epoch_ns=stamp, emulator_boot_id=expected_boot)
            result["threads"] = adb(run, "shell", "ps", "-T", "-p", pid, "-o", "PID,TID,COMM").decode().splitlines()
            code, _ = run.raw(["adb", "-s", benchmark.SERIAL, "shell", "test", "-e", remote], 10)
            if code != 1:
                raise RuntimeError("Owned trace path is not proven absent")
            created = True
            adb(run, "shell", "perfetto", "--txt", "-c", "-", "-o", remote, data=CONFIG, timeout=45)
            local = out / "scheduler.pftrace"
            adb(run, "pull", remote, str(local), timeout=25)
            if not 0 < local.stat().st_size < 67_108_864:
                raise RuntimeError("Trace size did not qualify")
            result.update(capture="COMPLETE", trace_sha256=hashlib.sha256(local.read_bytes()).hexdigest(),
                          trace_bytes=local.stat().st_size)
            return
        result["capture"] = "NO_CURRENT_MARKER"
    except Exception as error:
        result.update(capture="FAILED", error_type=type(error).__name__)
    finally:
        if created:
            try:
                if boot(run) != expected_boot:
                    raise RuntimeError("Do not clean up on a different emulator boot")
                adb(run, "shell", "rm", "-f", remote)
                result["owned_trace_removed"] = True
            except Exception:
                result["owned_trace_removed"] = False


def collect(root, reports, platform, origin):
    out = reports / ("search-diagnostic-" + uuid.uuid4().hex)
    contained(root, out).mkdir(exist_ok=False)
    for name in ("logs", "metadata"):
        (out / name).mkdir()
    args = SimpleNamespace(profile="platform", platform=platform, base_ref="origin/develop")
    run, observer = validator.Run(root, out, args), validator.Run(root, out / "observer", args)
    stop, trace = threading.Event(), {}
    result = dict(status="DIAGNOSTIC_FAILED", qualification=False, original_invocation_id=origin["invocation_id"],
                  source_sha256=origin["source_sha256"], host=host_metadata(), trace=trace,
                  limits=["Traced timing is diagnostic only and can inherit compilation/cache state.",
                          "Collection after the marker does not prove coverage of all thirty query intervals.",
                          "Trace overlap, completion coverage and loss checks require offline decoding."])
    worker = None
    worker_deadline = None

    def finish_worker():
        nonlocal worker_deadline
        stop.set()
        if worker is not None and worker.is_alive():
            # Remaining commands total at most 210 seconds; allow bounded cleanup and local I/O too.
            if worker_deadline is None:
                worker_deadline = time.monotonic() + 240
            worker.join(timeout=max(0, worker_deadline - time.monotonic()))

    try:
        expected_boot = boot(observer)
        code, value = observer.raw(["adb", "-s", benchmark.SERIAL, "shell", "pidof", benchmark.TARGET], 10)
        if code != 1 or value.strip():
            raise RuntimeError("Prior target process remains active")
        result["guest_cpu_online"] = adb(observer, "shell", "cat", "/sys/devices/system/cpu/online").decode().strip()
        if not re.fullmatch(r"[0-9,-]{1,128}", result["guest_cpu_online"]):
            raise RuntimeError("Guest CPU metadata did not qualify")
        scale = adb(observer, "shell", "settings", "get", "global", "animator_duration_scale").decode().strip()
        if not re.fullmatch(r"null|[0-9]+(?:\.[0-9]+)?", scale):
            raise RuntimeError("Animation metadata did not qualify")
        result["animator_duration_scale"] = scale
        cutoff = int(adb(observer, "shell", "date", "+%s%N").strip())
        worker = threading.Thread(target=capture, args=(observer, out, origin, stop, trace, cutoff,
                                                       expected_boot, run.invocation_id), daemon=True)
        worker.start()
        def boundary(current):
            if boot(current) != expected_boot:
                raise RuntimeError("Assigned emulator boot changed")
        try:
            benchmark.execute(run, validator.gradle, boundary)
        except benchmark.BenchmarkCleanupError:
            raise
        except Exception as error:
            result["workload_error_type"] = type(error).__name__
        finally:
            finish_worker()
            if worker.is_alive():
                raise RuntimeError("Owned trace worker has not stopped")
        artifact = read_json(root, out / "metadata/search-benchmark-artifacts.json")
        value = read_json(root, out / "metadata/search-latency.json")
        if (not canonical_uuid(artifact.get("run_id")) or artifact.get("validation_invocation_id") != run.invocation_id or
                trace.get("run_id") != artifact["run_id"] or any(artifact[key] != origin["artifact"][key] for key in APKS)):
            raise RuntimeError("Diagnostic APK pair differs from original failure")
        try:
            benchmark.validate_metrics(value, artifact["run_id"])
        except ValueError as error:
            if not str(error).startswith("Query-to-visible p95 is "):
                raise
        result["diagnostic_p95_ms"] = value["p95_ms"]
        if trace.get("capture") != "COMPLETE" or trace.get("owned_trace_removed") is not True:
            raise RuntimeError("Trace capture or cleanup did not qualify")
        if boot(observer) != expected_boot:
            raise RuntimeError("Emulator boot changed before completion evidence")
        logs = adb(observer, "logcat", "-d", "-v", "epoch", "--pid", str(trace["controller_pid"]),
                   "-s", "MileStoneSearchProbe:I", "*:S")
        completion = marker(logs, trace["marker_epoch_ns"],
                            "result run_id=" + artifact["run_id"] + " p95_ms=")
        if completion is None:
            raise RuntimeError("Current UUID completion timestamp is unavailable")
        trace["current_uuid_completion_epoch_ns"] = completion
        result["status"] = "CAPTURED_PENDING_OFFLINE_VALIDATION"
    except (Exception, KeyboardInterrupt) as error:
        result["error_type"] = type(error).__name__
    finally:
        finish_worker()
        result["worker_stopped"] = worker is None or not worker.is_alive()
        if not result["worker_stopped"]:
            result["status"] = "DIAGNOSTIC_FAILED"
            result["trace"] = dict(trace, owned_trace_removed=False)
        try:
            result["original_evidence_preserved"] = protected_files(root, reports) == origin["original_files"]
            result["source_unchanged"] = validator.fingerprint(run) == origin["source_sha256"]
        except Exception as error:
            result.update(original_evidence_preserved=False, source_unchanged=False,
                          preservation_error_type=type(error).__name__)
        if not result["original_evidence_preserved"] or not result["source_unchanged"]:
            result["status"] = "DIAGNOSTIC_FAILED"
        (out / "receipt.json").write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    return result


def main(argv=None, *, root=validator.ROOT):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--platform", required=True, choices=("windows", "ci-linux"))
    parser.add_argument("--reports-dir", type=Path, default=Path("reports"))
    args = parser.parse_args(argv)
    reports = args.reports_dir if args.reports_dir.is_absolute() else root / args.reports_dir
    probe = validator.Run(root, reports, SimpleNamespace(profile="platform"))
    try:
        origin = load_failure(root, contained(root, reports), args.platform, validator.fingerprint(probe))
    except NotEligible as error:
        print(json.dumps(dict(status="SKIPPED", reason=str(error), device_calls=0, qualification=False)))
        return 0
    result = collect(root, reports, args.platform, origin)
    print(json.dumps(dict(status=result["status"], qualification=False)))
    return 0 if result["status"] == "CAPTURED_PENDING_OFFLINE_VALIDATION" else 1


if __name__ == "__main__":
    def cancel(_signal, _frame):
        raise KeyboardInterrupt()
    signal.signal(signal.SIGTERM, cancel)
    raise SystemExit(main())
