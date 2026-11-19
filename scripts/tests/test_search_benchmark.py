import json
from pathlib import Path
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from search_benchmark import validate_metrics, validate_phase_anchors, execute, TARGET, CONTROLLER
import validate

RUN_ID = "b8a0615e-c5b0-4c40-8cd9-07975a627c36"


def metrics():
    cases = list(zip(("all_entities", "title", "description_only", "contextual", "program_only", "empty"),
                     (2125, 1, 1000, 40, 85, 0)))
    return dict(run_id=RUN_ID, target_package="com.bendourthe.milestone.benchmark", debuggable=False,
                complete=True, preservation_passed=True, scenario_contract="query-to-visible-v1",
                programs=25, cards=100, displayed_benefits=2000, samples=30, warmups=3,
                frame_clock="Android Choreographer", injected_ui_delay_ms=0, p95_ms=100.0,
                minimum_ms=100.0, maximum_ms=100.0, limit_ms=200, viewport_width=1080, viewport_height=2340,
                observations=[dict(family=family, count=count, elapsed_ms=100.0) for family, count in cases * 5])


class SearchBenchmarkMetricsTest(unittest.TestCase):
    def test_phase_anchors_match_actual_elapsed_observations(self):
        value = metrics()
        value['phase_anchor_clock'] = 'android.os.SystemClock.elapsedRealtimeNanos'
        cases = [('all_entities', 2125), ('title', 1), ('contextual', 40)] + [(row['family'], row['count']) for row in value['observations']]
        value['phase_diagnostics_including_warmups'] = [dict(family=family, count=count,
            start_elapsed_ns=1_000_000_000 + index * 200_000_000,
            ready_elapsed_ns=1_020_000_000 + index * 200_000_000,
            draw_elapsed_ns=1_100_000_000 + index * 200_000_000,
            results_ready_ms=20.0, draw_after_results_ms=80.0) for index, (family, count) in enumerate(cases)]
        self.assertEqual(33, len(validate_phase_anchors(value)))
        self.assertIs(value, validate_metrics(value, RUN_ID))
        for field, replacement in [('start_elapsed_ns', True), ('start_elapsed_ns', -1), ('ready_elapsed_ns', 0),
                                   ('draw_elapsed_ns', 1), ('results_ready_ms', 21.0), ('draw_after_results_ms', 81.0),
                                   ('family', 'empty'), ('count', 0)]:
            with self.subTest(field=field):
                altered = json.loads(json.dumps(value))
                altered['phase_diagnostics_including_warmups'][0][field] = replacement
                with self.assertRaises(ValueError):
                    validate_phase_anchors(altered)
        altered = json.loads(json.dumps(value))
        altered['phase_diagnostics_including_warmups'][3]['start_elapsed_ns'] -= 1_000_000_000
        with self.assertRaises(ValueError):
            validate_phase_anchors(altered)
        altered = json.loads(json.dumps(value))
        altered['observations'][0]['elapsed_ms'] = 101.0
        with self.assertRaises(ValueError):
            validate_phase_anchors(altered)
        for index, replacement in [(0, None), (0, {'family': 'empty', 'count': 2125, 'elapsed_ms': 100.0}),
                                   (0, {'family': 'all_entities', 'count': 0, 'elapsed_ms': 100.0}),
                                   (1, {'family': 'title', 'count': True, 'elapsed_ms': 100.0})]:
            altered = json.loads(json.dumps(value))
            altered['observations'][index] = replacement
            with self.assertRaises(ValueError):
                validate_phase_anchors(altered)
        for offset in (2**63, 10**1000):
            altered = json.loads(json.dumps(value))
            for phase in altered['phase_diagnostics_including_warmups']:
                for field in ('start_elapsed_ns', 'ready_elapsed_ns', 'draw_elapsed_ns'):
                    phase[field] += offset
            with self.assertRaises(ValueError):
                validate_phase_anchors(altered)
        altered = json.loads(json.dumps(value))
        altered['phase_diagnostics_including_warmups'].pop()
        with self.assertRaises(ValueError):
            validate_phase_anchors(altered)
        value['phase_anchor_clock'] = 'another-clock'
        with self.assertRaises(ValueError):
            validate_phase_anchors(value)

    def test_complete_correlated_draw_evidence_is_accepted(self):
        self.assertEqual(100.0, validate_metrics(metrics(), RUN_ID)["p95_ms"])

    def test_wrong_identity_incomplete_preservation_or_contract_is_rejected(self):
        for key, value in (("run_id", "another-run"), ("target_package", "another.package"),
                           ("debuggable", True), ("complete", False), ("preservation_passed", False),
                           ("scenario_contract", "another-contract"), ("samples", 29), ("warmups", 4),
                           ("programs", 24), ("cards", 99), ("displayed_benefits", 1999),
                           ("frame_clock", "state-ready"), ("viewport_width", 720), ("limit_ms", 201)):
            with self.subTest(key=key):
                value_copy = metrics()
                value_copy[key] = value
                with self.assertRaises(ValueError):
                    validate_metrics(value_copy, RUN_ID)

    def test_p95_is_recomputed_and_nonfinite_or_partial_data_cannot_pass(self):
        candidates = []
        dishonest = metrics()
        for observation in dishonest["observations"][-2:]:
            observation["elapsed_ms"] = 250.0
        dishonest["maximum_ms"] = 250.0
        candidates.append(dishonest)
        for bad in (float("nan"), float("inf"), -1.0, True):
            value = metrics()
            value["observations"][0]["elapsed_ms"] = bad
            candidates.append(value)
        partial = metrics()
        partial["observations"].pop()
        candidates.append(partial)
        wrong_order = metrics()
        wrong_order["observations"].reverse()
        candidates.append(wrong_order)
        for value in candidates:
            with self.subTest(value=value):
                with self.assertRaises(ValueError):
                    validate_metrics(value, RUN_ID)

    def test_real_slow_results_and_unexpected_injected_delay_are_rejected(self):
        value = metrics()
        for row in value["observations"]:
            row["elapsed_ms"] = 300.0
        value.update(p95_ms=300.0, minimum_ms=300.0, maximum_ms=300.0)
        with self.assertRaisesRegex(ValueError, "200"):
            validate_metrics(value, RUN_ID)
        value = metrics()
        value["injected_ui_delay_ms"] = 250
        with self.assertRaises(ValueError):
            validate_metrics(value, RUN_ID)


class SearchBenchmarkReportTest(unittest.TestCase):
    def test_platform_requires_both_selected_tasks_and_rejects_benchmark_failure(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory).resolve()
            reports = root / "reports"
            for name in ("metadata", "junit", "coverage", "sarif"):
                (reports / name).mkdir(parents=True)
            run = validate.Run(root, reports, SimpleNamespace(profile="platform"))
            debug = root / "app/build/outputs/androidTest-results/connected/debug/TEST-debug.xml"
            optimized = root / "searchBenchmark/build/outputs/androidTest-results/connected/benchmark/TEST-optimized.xml"
            debug.parent.mkdir(parents=True)
            optimized.parent.mkdir(parents=True)
            debug.write_text('<testsuite tests="76" failures="0" errors="0" skipped="0"/>')
            with self.assertRaisesRegex(RuntimeError, "JUnit"):
                validate.collect_reports(run, succeeded=True)
            optimized.write_text('<testsuite tests="1" failures="1" errors="0" skipped="0"/>')
            with self.assertRaisesRegex(RuntimeError, "JUnit reports failures or errors"):
                validate.collect_reports(run, succeeded=True)
            optimized.write_text('<testsuite tests="1" failures="0" errors="0" skipped="0"/>')
            counts = validate.collect_reports(run, succeeded=True)
            self.assertEqual((77, 0, 0), (counts["tests"], counts["failures"], counts["errors"]))
            debug.unlink()
            with self.assertRaisesRegex(RuntimeError, "JUnit"):
                validate.collect_reports(run, succeeded=True)


class FakeRun:
    def __init__(self, root):
        self.root = root
        self.reports = root / "reports"
        self.args = SimpleNamespace(platform="windows")
        self.commands = []
        self.boundaries = 0
        self.run_id = None
        self.preexisting = False
        self.no_export = False
        self.malformed_export = False
        self.failed_task = False
        self.changed_apk = False
        self.failed_target_cleanup = False
        self.apks = [root / "app/build/outputs/apk/benchmark/app-benchmark.apk",
                     root / "searchBenchmark/build/outputs/apk/benchmark/searchBenchmark-benchmark.apk"]
        for index, path in enumerate(self.apks):
            path.parent.mkdir(parents=True)
            path.write_bytes(f"synthetic-apk-{index}".encode())

    def command(self, argv, timeout):
        self.commands.append(tuple(argv))
        if ":searchBenchmark:connectedBenchmarkAndroidTest" in argv:
            self.run_id = next(part.split("=", 1)[1] for part in argv if part.startswith("-Pandroid.testInstrumentationRunnerArguments.run_id="))
            if self.changed_apk:
                self.apks[0].write_bytes(b"changed")
            if self.failed_task:
                raise RuntimeError("Original Gradle failure")
        if "force-stop" in argv and argv[-1] == TARGET and self.failed_target_cleanup:
            raise RuntimeError("Target cleanup failure")
        return b""

    def raw(self, argv, timeout):
        if "test" in argv:
            return (0 if self.preexisting else 1), b""
        if self.no_export:
            return 1, b""
        if self.malformed_export:
            return 0, b"not-json"
        value = metrics()
        value["run_id"] = self.run_id
        return 0, json.dumps(value).encode()

    def boundary(self, run):
        self.boundaries += 1


class SearchBenchmarkExecutionTest(unittest.TestCase):
    def run_fixture(self, run):
        execute(run, validate.gradle, run.boundary)

    def assert_cleanup(self, run):
        self.assertTrue(any("force-stop" in command and command[-1] == TARGET for command in run.commands))
        self.assertTrue(any("force-stop" in command and command[-1] == CONTROLLER for command in run.commands))
        self.assertTrue(any("rm" in command and run.run_id in command[-1] for command in run.commands))
        self.assertEqual(3, run.boundaries)

    def test_success_binds_hashes_and_collects_only_current_uuid(self):
        with tempfile.TemporaryDirectory() as directory:
            run = FakeRun(Path(directory).resolve())
            self.run_fixture(run)
            result = json.loads((run.reports / "metadata/search-latency.json").read_text())
            self.assertEqual(run.run_id, result["run_id"])
            import hashlib
            connected = next(command for command in run.commands if ":searchBenchmark:connectedBenchmarkAndroidTest" in command)
            self.assertIn("-Pandroid.testInstrumentationRunnerArguments.expectedTargetSha256=" + hashlib.sha256(run.apks[0].read_bytes()).hexdigest(), connected)
            self.assertIn("-Pandroid.testInstrumentationRunnerArguments.expectedControllerSha256=" + hashlib.sha256(run.apks[1].read_bytes()).hexdigest(), connected)
            self.assert_cleanup(run)

    def test_preexisting_uuid_output_prevents_launch(self):
        with tempfile.TemporaryDirectory() as directory:
            run = FakeRun(Path(directory).resolve())
            run.preexisting = True
            with self.assertRaisesRegex(RuntimeError, "absent"):
                self.run_fixture(run)
            self.assertFalse(any(":searchBenchmark:connectedBenchmarkAndroidTest" in command for command in run.commands))

    def test_missing_malformed_or_changed_artifacts_cannot_pass(self):
        for flag in ("no_export", "malformed_export", "changed_apk"):
            with self.subTest(flag=flag), tempfile.TemporaryDirectory() as directory:
                run = FakeRun(Path(directory).resolve())
                setattr(run, flag, True)
                with self.assertRaises((RuntimeError, ValueError)):
                    self.run_fixture(run)
                self.assert_cleanup(run)

    def test_failed_task_remains_original_error_even_with_valid_or_malformed_export(self):
        for malformed in (False, True):
            with self.subTest(malformed=malformed), tempfile.TemporaryDirectory() as directory:
                run = FakeRun(Path(directory).resolve())
                run.failed_task = True
                run.malformed_export = malformed
                with self.assertRaisesRegex(RuntimeError, "Original Gradle failure"):
                    self.run_fixture(run)
                self.assert_cleanup(run)

    def test_cleanup_failure_does_not_skip_other_cleanup_or_hide_original_failure(self):
        for failed_task in (False, True):
            with self.subTest(failed_task=failed_task), tempfile.TemporaryDirectory() as directory:
                run = FakeRun(Path(directory).resolve())
                run.failed_target_cleanup = True
                run.failed_task = failed_task
                with self.assertRaisesRegex(RuntimeError, "Original Gradle failure" if failed_task else "cleanup"):
                    self.run_fixture(run)
                self.assert_cleanup(run)

    def test_metrics_retention_failure_cannot_be_reported_as_success(self):
        with tempfile.TemporaryDirectory() as directory:
            run = FakeRun(Path(directory).resolve())
            write_text = Path.write_text
            def checked_write(path, *args, **kwargs):
                if path.name == "search-latency.json":
                    raise OSError("Synthetic report disk error")
                return write_text(path, *args, **kwargs)
            with patch.object(Path, "write_text", checked_write):
                with self.assertRaisesRegex(RuntimeError, "metrics"):
                    self.run_fixture(run)
            self.assert_cleanup(run)


if __name__ == "__main__":
    unittest.main()
