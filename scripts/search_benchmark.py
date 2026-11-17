"""Run the isolated optimized Search scenario and validate its artifact-bound evidence."""
from __future__ import annotations

import hashlib
import json
import math
from pathlib import Path
import uuid

TARGET = "com.bendourthe.milestone.benchmark"
CONTROLLER = "com.bendourthe.milestone.searchbenchmark"
SERIAL = "emulator-5554"
CASES = tuple(zip(("all_entities", "title", "description_only", "contextual", "program_only", "empty"),
                  (2125, 1, 1000, 40, 85, 0))) * 5


def _number(value):
    if type(value) not in (int, float) or not math.isfinite(value) or value < 0:
        raise ValueError("Benchmark timing must be a finite nonnegative number")
    return float(value)


def validate_metrics(value: dict, run_id: str) -> dict:
    expected = dict(run_id=run_id, target_package=TARGET, debuggable=False, complete=True,
                    preservation_passed=True, scenario_contract="query-to-visible-v1", programs=25, cards=100,
                    displayed_benefits=2000, samples=30, warmups=3, frame_clock="Android Choreographer",
                    injected_ui_delay_ms=0, limit_ms=200, viewport_width=1080, viewport_height=2340)
    if not isinstance(value, dict) or any(type(value.get(key)) is not type(item) or value[key] != item for key, item in expected.items()):
        raise ValueError("Benchmark identity, completion, preservation, workload or viewport mismatch")
    rows = value.get("observations")
    if not isinstance(rows, list) or len(rows) != 30:
        raise ValueError("Benchmark requires all 30 measured observations")
    elapsed = []
    for row, (family, count) in zip(rows, CASES):
        if not isinstance(row, dict) or row.get("family") != family or type(row.get("count")) is not int or row["count"] != count:
            raise ValueError("Benchmark query sequence or expected result count changed")
        elapsed.append(_number(row.get("elapsed_ms")))
    ordered = sorted(elapsed)
    p95 = ordered[math.ceil(len(ordered) * .95) - 1]
    for key, actual in (("p95_ms", p95), ("minimum_ms", ordered[0]), ("maximum_ms", ordered[-1])):
        if abs(_number(value.get(key)) - actual) > 0.000001:
            raise ValueError("Benchmark summary does not match its actual observations")
    if p95 > 200:
        raise ValueError(f"Query-to-visible p95 is {p95} ms; limit is 200 ms")
    return value


def _digest(path: Path) -> str:
    if path.is_symlink() or not path.is_file():
        raise RuntimeError("Benchmark APK is missing or is a link")
    return hashlib.sha256(path.read_bytes()).hexdigest()


class BenchmarkCleanupError(RuntimeError):
    """The workload ended without confirmed native cleanup."""


def execute(run, gradle_step, emulator_boundary):
    """Use the existing native Gradle/JUnit runner; no issuer data or phone access."""
    emulator_boundary(run)
    build = gradle_step(run.args.platform, ":app:assembleBenchmark", ":searchBenchmark:assembleBenchmark")
    run.command(build.argv, build.timeout)
    apks = {"target_sha256": run.root / "app/build/outputs/apk/benchmark/app-benchmark.apk",
            "controller_sha256": run.root / "searchBenchmark/build/outputs/apk/benchmark/searchBenchmark-benchmark.apk"}
    hashes = {key: _digest(path) for key, path in apks.items()}
    run_id = str(uuid.uuid4())
    remote = f"/sdcard/Download/MileStoneSearchProbe/search-{run_id}.json"
    code, _ = run.raw(["adb", "-s", SERIAL, "shell", "test", "-e", remote], 15)
    if code != 1:
        raise RuntimeError("Benchmark UUID output is not proven absent before launch")
    metadata = run.reports / "metadata"
    metadata.mkdir(parents=True, exist_ok=True)
    (metadata / "search-benchmark-artifacts.json").write_text(json.dumps(dict(run_id=run_id, build_type="benchmark", validation_invocation_id=getattr(run, "invocation_id", None), **hashes), indent=2) + "\n", encoding="utf-8")
    task = gradle_step(run.args.platform, ":searchBenchmark:connectedBenchmarkAndroidTest",
                       f"-Pandroid.testInstrumentationRunnerArguments.run_id={run_id}",
                       f"-Pandroid.testInstrumentationRunnerArguments.expectedTargetSha256={hashes['target_sha256']}",
                       f"-Pandroid.testInstrumentationRunnerArguments.expectedControllerSha256={hashes['controller_sha256']}")
    failure = None
    cleanup_errors = []
    export_error = None
    code, exported = 1, b""
    try:
        emulator_boundary(run)
        run.command(task.argv, task.timeout)
    except RuntimeError as error:
        failure = error
    finally:
        try:
            emulator_boundary(run)
        except (OSError, RuntimeError) as error:
            cleanup_errors.append(str(error))
        else:
            try:
                code, exported = run.raw(["adb", "-s", SERIAL, "exec-out", "cat", remote], 15)
            except (OSError, RuntimeError) as error:
                export_error = error
            cleanup = [("adb", "-s", SERIAL, "shell", "am", "force-stop", package) for package in (TARGET, CONTROLLER)]
            cleanup.append(("adb", "-s", SERIAL, "shell", "rm", "-f", remote))
            for command in cleanup:
                try:
                    run.command(command, 15)
                except (OSError, RuntimeError) as error:
                    cleanup_errors.append(str(error))
    value = None
    if code == 0 and exported:
        try:
            value = json.loads(exported)
            (metadata / "search-latency.json").write_text(json.dumps(value, indent=2) + "\n", encoding="utf-8")
        except (OSError, ValueError, UnicodeError) as error:
            export_error = error
    if failure is not None:
        if cleanup_errors:
            raise BenchmarkCleanupError(f"{failure}; cleanup errors: {'; '.join(cleanup_errors)}") from failure
        raise failure
    if cleanup_errors:
        raise BenchmarkCleanupError("Benchmark cleanup errors: " + "; ".join(cleanup_errors))
    if export_error is not None:
        raise RuntimeError("Fresh benchmark metrics collection failed") from export_error
    if value is None:
        raise RuntimeError("Fresh benchmark metrics are missing or malformed") from export_error
    if hashes != {key: _digest(path) for key, path in apks.items()}:
        raise RuntimeError("Benchmark APK changed during execution")
    validate_metrics(value, run_id)
