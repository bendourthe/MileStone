import hashlib
import json
import os
from pathlib import Path
import sys
import tempfile
import threading
import time
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import search_benchmark as benchmark
import search_diagnostics as diagnostic
import validate

OWNER = "a81ba9b4-b1c1-4509-b726-76e4c3acee11"
RUN = "b8a0615e-c5b0-4c40-8cd9-07975a627c36"
SOURCE = "a" * 64


class OriginFixture:
    def __init__(self, root):
        self.root, self.reports = root, root / "reports"
        for name in ("metadata", "results", "junit"):
            (self.reports / name).mkdir(parents=True)
        self.profile = dict(profile="platform", platform="windows", status="FAIL", source_sha256=SOURCE,
                            invocation_id=OWNER, groups=[dict(name="optimized-search-performance", status="FAIL")])
        self.artifact = dict(run_id=RUN, validation_invocation_id=OWNER, build_type="benchmark")
        for key, path in diagnostic.APKS.items():
            path = root / path
            path.parent.mkdir(parents=True)
            path.write_bytes(key.encode())
            self.artifact[key] = hashlib.sha256(path.read_bytes()).hexdigest()
        self.metrics = dict(run_id=RUN, target_package=benchmark.TARGET, debuggable=False, complete=True,
            preservation_passed=True, scenario_contract="query-to-visible-v1", programs=25, cards=100,
            displayed_benefits=2000, samples=30, warmups=3, frame_clock="Android Choreographer",
            injected_ui_delay_ms=0, limit_ms=200, viewport_width=1080, viewport_height=2340,
            p95_ms=300.0, minimum_ms=300.0, maximum_ms=300.0,
            observations=[dict(family=f, count=c, elapsed_ms=300.0) for f, c in benchmark.CASES])
        (self.reports / "junit/platform-001.xml").write_text('<testsuite tests="1" failures="1"/>')
        self.save()

    def save(self):
        for path, value in (("results/platform-windows.json", self.profile),
                            ("metadata/search-benchmark-artifacts.json", self.artifact),
                            ("metadata/search-latency.json", self.metrics)):
            (self.reports / path).write_text(json.dumps(value), encoding="utf-8")


class FailureGateTest(unittest.TestCase):
    def test_benchmark_producer_retains_parent_invocation(self):
        from scripts.tests.test_search_benchmark import FakeRun

        with tempfile.TemporaryDirectory() as folder:
            run = FakeRun(Path(folder).resolve())
            run.invocation_id = OWNER
            benchmark.execute(run, validate.gradle, run.boundary)
            artifact = json.loads((run.reports / "metadata/search-benchmark-artifacts.json").read_text())
            self.assertEqual(OWNER, artifact["validation_invocation_id"])
            self.assertEqual(run.run_id, artifact["run_id"])

    def test_benchmark_cleanup_failure_has_a_distinct_outcome(self):
        from scripts.tests.test_search_benchmark import FakeRun

        for failed_task in (False, True):
            with self.subTest(failed_task=failed_task), tempfile.TemporaryDirectory() as folder:
                run = FakeRun(Path(folder).resolve())
                run.failed_target_cleanup = True
                run.failed_task = failed_task
                with self.assertRaises(benchmark.BenchmarkCleanupError):
                    benchmark.execute(run, validate.gradle, run.boundary)

    def test_only_current_invocation_latency_failure_is_eligible(self):
        with tempfile.TemporaryDirectory() as folder:
            fixture = OriginFixture(Path(folder).resolve())
            before = diagnostic.protected_files(fixture.root, fixture.reports)
            origin = diagnostic.load_failure(fixture.root, fixture.reports, "windows", SOURCE)
            self.assertEqual(OWNER, origin["invocation_id"])
            self.assertEqual(RUN, origin["artifact"]["run_id"])
            self.assertEqual(before, diagnostic.protected_files(fixture.root, fixture.reports))

    def test_stale_same_source_pair_and_legacy_missing_owner_are_rejected(self):
        for owner in (RUN, None):
            with self.subTest(owner=owner), tempfile.TemporaryDirectory() as folder:
                fixture = OriginFixture(Path(folder).resolve())
                fixture.artifact["validation_invocation_id"] = owner
                fixture.save()
                with self.assertRaises(diagnostic.NotEligible):
                    diagnostic.load_failure(fixture.root, fixture.reports, "windows", SOURCE)

    def test_passing_other_failed_group_and_invalid_metrics_skip_without_adb(self):
        for kind in ("passing", "other_group", "preservation", "partial", "source", "uuid", "apk", "overflow"):
            with self.subTest(kind=kind), tempfile.TemporaryDirectory() as folder:
                fixture = OriginFixture(Path(folder).resolve())
                if kind == "passing":
                    for row in fixture.metrics["observations"]:
                        row["elapsed_ms"] = 100.0
                    fixture.metrics.update(p95_ms=100.0, minimum_ms=100.0, maximum_ms=100.0)
                if kind == "other_group": fixture.profile["groups"][0]["name"] = "other"
                if kind == "preservation": fixture.metrics["preservation_passed"] = False
                if kind == "partial": fixture.metrics["observations"].pop()
                if kind == "source": fixture.profile["source_sha256"] = "b" * 64
                if kind == "uuid": fixture.metrics["run_id"] = OWNER
                if kind == "apk": (fixture.root / diagnostic.APKS["target_sha256"]).write_bytes(b"changed")
                if kind == "overflow": fixture.metrics["observations"][0]["elapsed_ms"] = 10 ** 400
                fixture.save()
                with patch.object(diagnostic, "collect") as collect, patch.object(validate, "fingerprint", return_value=SOURCE):
                    with patch("builtins.print"):
                        self.assertEqual(0, diagnostic.main(["--platform", "windows"], root=fixture.root))
                    collect.assert_not_called()

    def test_missing_exported_original_junit_rejects_collection(self):
        with tempfile.TemporaryDirectory() as folder:
            fixture = OriginFixture(Path(folder).resolve())
            (fixture.reports / "junit/platform-001.xml").unlink()
            with self.assertRaises(diagnostic.NotEligible):
                diagnostic.load_failure(fixture.root, fixture.reports, "windows", SOURCE)

    def test_bounded_but_unparseable_numeric_or_recursive_json_skips(self):
        for raw in ('{"number":' + '1' * 5000 + '}', '[' * 2000 + '0' + ']' * 2000):
            with tempfile.TemporaryDirectory() as folder:
                fixture = OriginFixture(Path(folder).resolve())
                (fixture.reports / "metadata/search-latency.json").write_text(raw)
                with patch.object(diagnostic, "collect") as collect, patch.object(validate, "fingerprint", return_value=SOURCE), patch("builtins.print"):
                    self.assertEqual(0, diagnostic.main(["--platform", "windows"], root=fixture.root))
                    collect.assert_not_called()


class ValidatorBindingTest(unittest.TestCase):
    def test_exited_windows_wrapper_drain_is_bounded_and_uncertainty_is_reported(self):
        for interrupted, drained in ((True, False), (False, False), (True, True), (False, True)):
            with self.subTest(interrupted=interrupted, drained=drained), tempfile.TemporaryDirectory() as folder:
                run = validate.Run(Path(folder).resolve(), Path(folder) / "reports", SimpleNamespace(profile="platform"))
                original = KeyboardInterrupt() if interrupted else validate.subprocess.TimeoutExpired("synthetic-child", .1)
                child = Mock(pid=123)
                child.poll.return_value = 0
                child.communicate.side_effect = [original, (b"", None) if drained else validate.subprocess.TimeoutExpired("synthetic-drain", 5)]
                with patch.object(validate, "spawn", return_value=child) as spawn, patch.object(validate.os, "name", "nt"):
                    if interrupted:
                        with self.assertRaises(KeyboardInterrupt) as raised:
                            run.raw(["synthetic-child"], timeout=.1)
                        self.assertIs(original, raised.exception)
                        self.assertIn("Owned process cleanup could not be confirmed.", original.__notes__)
                    else:
                        code, output = run.raw(["synthetic-child"], timeout=.1)
                        self.assertEqual(124, code)
                        self.assertIn(b"cleanup could not be confirmed", output)
                spawn.assert_called_once()
                self.assertEqual(5, child.communicate.call_args.kwargs["timeout"])

    @unittest.skipUnless(os.name == "posix", "Requires real POSIX process groups")
    def test_exited_group_leader_does_not_leave_live_stdout_descendant(self):
        with tempfile.TemporaryDirectory() as folder:
            run = validate.Run(Path(folder).resolve(), Path(folder) / "reports", SimpleNamespace(profile="platform"))
            child = "import subprocess,sys; subprocess.Popen([sys.executable,'-c','import time; time.sleep(5)'])"
            started = time.monotonic()
            code, _ = run.raw([sys.executable, "-c", child], timeout=.2)
            self.assertEqual(124, code)
            self.assertLess(time.monotonic() - started, 4)

    def test_interrupt_reaps_owned_tree_before_propagating(self):
        with tempfile.TemporaryDirectory() as folder:
            run = validate.Run(Path(folder).resolve(), Path(folder) / "reports", SimpleNamespace(profile="platform"))
            child = Mock(pid=123)
            child.poll.return_value = None
            child.communicate.side_effect = [KeyboardInterrupt(), (b"", None)]
            with patch.object(validate, "spawn", return_value=child), \
                 patch.object(validate.os, "name", "posix"), \
                 patch.object(validate.os, "killpg", create=True) as kill, \
                 patch.object(validate.signal, "SIGKILL", 9, create=True):
                with self.assertRaises(KeyboardInterrupt):
                    run.raw(["synthetic-child"])
            kill.assert_called_once_with(123, 9)
            self.assertEqual(2, child.communicate.call_count)

    def test_report_collection_error_retains_failed_optimized_group_and_owner(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder).resolve()
            group = validate.Group("optimized-search-performance", (validate.Step(("@diagnostic-test",)),))
            with patch.object(validate, "groups", return_value=[group]), \
                 patch.object(validate, "host_class", return_value="windows"), \
                 patch.object(validate, "clear_exports"), patch.object(validate, "fingerprint", return_value=SOURCE), \
                 patch.object(validate.Run, "raw", return_value=(0, b"synthetic tool")), \
                 patch.object(validate, "collect_reports", side_effect=RuntimeError("JUnit reports failures")), \
                 patch.dict(validate.ACTIONS, {"@diagnostic-test": lambda run: (_ for _ in ()).throw(RuntimeError("Latency failure"))}), \
                 patch("builtins.print"):
                self.assertEqual(1, validate.main(["--profile", "platform", "--platform", "windows", "--quiet"], root=root))
            result = json.loads((root / "reports/results/platform-windows.json").read_text())
            self.assertEqual("FAIL", result["status"])
            self.assertTrue(diagnostic.canonical_uuid(result["invocation_id"]))
            self.assertEqual("profile", result["groups"][0]["name"])
            self.assertEqual("optimized-search-performance", result["groups"][1]["name"])
            self.assertEqual("FAIL", result["groups"][1]["status"])


class CollectionTest(unittest.TestCase):
    def test_real_capture_path_only_cleans_its_owned_file_on_the_same_boot(self):
        for mode in ("ok", "perfetto_failure", "pull_failure", "cleanup_failure", "preexisting", "replacement_boot", "stale_marker"):
            with self.subTest(mode=mode), tempfile.TemporaryDirectory() as folder:
                fixture = OriginFixture(Path(folder).resolve())
                out = fixture.reports / "synthetic-capture"
                (out / "metadata").mkdir(parents=True)
                (out / "metadata/search-benchmark-artifacts.json").write_text(json.dumps(fixture.artifact))
                commands = []
                def raw(argv, timeout):
                    if "pidof" in argv: return 0, b"123"
                    if "test" in argv: return (0 if mode == "preexisting" else 1), b""
                    raise AssertionError("Unexpected synthetic raw call")
                def fake_adb(_run, *args, **_kwargs):
                    commands.append(args)
                    if args[0] == "logcat":
                        return ("0.900000" if mode == "stale_marker" else "1.100000").encode() + b" 123 I timing phase=measured samples=30\n"
                    if "pidof" in args: return b"124"
                    if "ps" in args: return b"PID TID COMM\n123 123 [main]\n"
                    if "perfetto" in args:
                        if mode == "perfetto_failure": raise RuntimeError("Injected capture failure")
                        return b""
                    if args[0] == "pull":
                        if mode == "pull_failure": raise RuntimeError("Injected pull failure")
                        Path(args[-1]).write_bytes(b"synthetic trace, not a real trace")
                        return b""
                    if "rm" in args:
                        self.assertTrue(args[-1].startswith("/data/misc/perfetto-traces/milestone-search-"))
                        self.assertTrue(args[-1].endswith(".pftrace"))
                        if mode == "cleanup_failure": raise RuntimeError("Injected cleanup failure")
                        return b""
                    raise AssertionError("Unexpected synthetic diagnostic call")
                class Stop:
                    calls = 0
                    def wait(self, _seconds):
                        self.calls += 1
                        return self.calls > 1
                result = {}
                boots = [OWNER, RUN] if mode == "replacement_boot" else [OWNER, OWNER]
                run = SimpleNamespace(root=fixture.root, raw=raw)
                with patch.object(diagnostic, "adb", side_effect=fake_adb), patch.object(diagnostic, "boot", side_effect=boots):
                    diagnostic.capture(run, out, {"artifact": fixture.artifact}, Stop(), result, 1_000_000_000, OWNER, OWNER)
                removals = [x for x in commands if "rm" in x]
                if mode in ("preexisting", "replacement_boot", "stale_marker"):
                    self.assertEqual([], removals)
                else:
                    self.assertEqual(1, len(removals))
                if mode in ("cleanup_failure", "replacement_boot"):
                    self.assertFalse(result["owned_trace_removed"])
                if mode in ("perfetto_failure", "pull_failure", "preexisting"):
                    self.assertEqual("FAILED", result["capture"])
                if mode == "ok":
                    self.assertEqual("COMPLETE", result["capture"])
                    self.assertTrue(result["owned_trace_removed"])
                if mode == "stale_marker":
                    self.assertEqual("NO_CURRENT_MARKER", result["capture"])
                    self.assertFalse(any("perfetto" in x for x in commands))

    def test_capture_is_separate_nonqualifying_and_rejects_failed_cleanup_or_uuid(self):
        for mode in ("ok", "cleanup_failure", "wrong_uuid", "capture_failure", "missing_completion",
                     "native_cleanup_failure", "slow_worker", "worker_timeout"):
            with self.subTest(mode=mode), tempfile.TemporaryDirectory() as folder:
                fixture = OriginFixture(Path(folder).resolve())
                origin = diagnostic.load_failure(fixture.root, fixture.reports, "windows", SOURCE)
                before = diagnostic.protected_files(fixture.root, fixture.reports)
                ready = threading.Event()
                current_id = []
                def fake_execute(run, _gradle, boundary):
                    identity = "a7b0615e-c5b0-4c40-8cd9-07975a627c36"
                    current_id.append(identity)
                    artifact = dict(fixture.artifact, run_id=identity, validation_invocation_id=run.invocation_id)
                    value = dict(fixture.metrics, run_id=identity)
                    (run.reports / "metadata/search-benchmark-artifacts.json").write_text(json.dumps(artifact))
                    (run.reports / "metadata/search-latency.json").write_text(json.dumps(value))
                    ready.set()
                    if mode == "native_cleanup_failure":
                        raise benchmark.BenchmarkCleanupError("Synthetic native cleanup failure")
                def fake_capture(_run, _out, _origin, _stop, result, *_args):
                    self.assertTrue(ready.wait(2))
                    result.update(capture="FAILED" if mode == "capture_failure" else "COMPLETE",
                                  run_id=RUN if mode == "wrong_uuid" else current_id[0],
                                  controller_pid=124, marker_epoch_ns=1234567890000000000,
                                  owned_trace_removed=mode != "cleanup_failure")
                def fake_adb(_run, *args, **_kwargs):
                    if args[-1] == "/sys/devices/system/cpu/online": return b"0-3"
                    if "animator_duration_scale" in args: return b"1"
                    if args[-1] == "+%s%N": return b"1234567890000000000"
                    if args[0] == "logcat":
                        return b"" if mode == "missing_completion" else (
                            "1234567891.000000 124 I result run_id=" + current_id[0] + " p95_ms=300\n").encode()
                    raise AssertionError("Unexpected synthetic device call")
                clock, joins = [0.0], []
                real_thread = threading.Thread

                class DelayedWorker:
                    def __init__(self, *, target, args, daemon):
                        self.target, self.args, self.alive = target, args, False
                        self.daemon = daemon

                    def start(self):
                        self.alive = True

                    def is_alive(self):
                        return self.alive

                    def join(self, timeout=None):
                        self_budget = 70 if mode == "slow_worker" else 1000
                        joins.append(timeout)
                        waited = self_budget if timeout is None else min(timeout, self_budget)
                        clock[0] += waited
                        if waited >= self_budget:
                            self.target(*self.args)
                            self.alive = False

                with patch.object(diagnostic, "boot", return_value=OWNER), \
                     patch.object(diagnostic, "adb", side_effect=fake_adb), \
                     patch.object(validate.Run, "raw", return_value=(1, b"")), \
                     patch.object(validate, "fingerprint", return_value=SOURCE), \
                     patch.object(diagnostic, "capture", side_effect=fake_capture), \
                     patch.object(diagnostic, "host_metadata", return_value={"logical_processors": 4}), \
                     patch.object(diagnostic.threading, "Thread", DelayedWorker if mode in ("slow_worker", "worker_timeout") else real_thread), \
                     patch.object(diagnostic.time, "monotonic", side_effect=lambda: clock[0]), \
                     patch.object(benchmark, "execute", side_effect=fake_execute):
                    result = diagnostic.collect(fixture.root, fixture.reports, "windows", origin)
                self.assertFalse(result["qualification"])
                self.assertTrue(result["original_evidence_preserved"])
                self.assertEqual(before, diagnostic.protected_files(fixture.root, fixture.reports))
                expected = "CAPTURED_PENDING_OFFLINE_VALIDATION" if mode in ("ok", "slow_worker") else "DIAGNOSTIC_FAILED"
                self.assertEqual(expected, result["status"])
                if mode == "worker_timeout":
                    self.assertFalse(result["worker_stopped"])
                    self.assertFalse(result["trace"]["owned_trace_removed"])
                    self.assertTrue(all(value is not None for value in joins))
                    self.assertLessEqual(sum(joins), 240)

    def test_marker_rejects_stale_and_unrelated_log_lines(self):
        logs = b"123.456000 123 I other\n123.457000 123 I timing phase=measured samples=30\n"
        self.assertIsNone(diagnostic.marker(logs, 123_458_000_000))
        self.assertEqual(123_457_000_000, diagnostic.marker(logs, 123_456_000_000))


if __name__ == "__main__":
    unittest.main()
