from pathlib import Path
from types import SimpleNamespace
import sys
import io
import json
from contextlib import redirect_stdout
from unittest.mock import patch
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import validate

class JUnitTaskStatusTest(unittest.TestCase):
    def test_successful_task_cannot_override_junit_failures_or_errors(self):
        with tempfile.TemporaryDirectory(prefix="milestone-junit-gate-") as directory:
            root = Path(directory).resolve()
            reports = root / "reports"
            for name in ("metadata", "sarif", "junit", "coverage"):
                (reports / name).mkdir(parents=True, exist_ok=True)
            for profile, path in (("full", "app/build/test-results/testDebugUnitTest"),
                                  ("platform", "app/build/outputs/androidTest-results/connected/debug")):
                run = validate.Run(root, reports, SimpleNamespace(profile=profile, base_ref="origin/develop", gitleaks_executable=None))
                result = root / path / "TEST-crashed-run.xml"
                result.parent.mkdir(parents=True)
                for failures, errors in ((1, 0), (0, 1)):
                    with self.subTest(profile=profile, failures=failures, errors=errors):
                        result.write_text(f'<testsuite tests="1" failures="{failures}" errors="{errors}" skipped="0"/>')
                        with self.assertRaisesRegex(RuntimeError, "JUnit reports failures or errors"):
                            validate.collect_reports(run, succeeded=True)
                        self.assertTrue(list((reports / "junit").glob(f"{profile}-*.xml")))
                        partial = validate.collect_reports(run, succeeded=False)
                        self.assertEqual((failures, errors), (partial["failures"], partial["errors"]))

    def test_profile_fails_when_successful_command_has_a_crashed_instrumentation_report(self):
        with tempfile.TemporaryDirectory(prefix="milestone-profile-junit-") as directory:
            root = Path(directory).resolve()
            result = root / "app/build/outputs/androidTest-results/connected/debug/TEST-crashed.xml"
            result.parent.mkdir(parents=True)
            result.write_text('<testsuite tests="1" failures="1" errors="0"><testcase name="testRunFailed"><failure>Process crashed</failure></testcase></testsuite>')
            reports = root / "reports"
            selected = [validate.Group("android-emulator-runtime", (validate.Step(("synthetic-successful-task",)),))]
            with patch.object(validate, "groups", return_value=selected), patch.object(validate, "fingerprint", return_value="synthetic-source"), \
                 patch.object(validate.Run, "raw", return_value=(0, b"synthetic version")), patch.object(validate.Run, "command", return_value=b""), \
                 redirect_stdout(io.StringIO()):
                code = validate.main(["--profile", "platform", "--reports-dir", str(reports), "--json"], root=root)
            receipt = json.loads((reports / "results" / f"platform-{validate.host_class()}.json").read_text())
            self.assertEqual(1, code)
            self.assertEqual("FAIL", receipt["status"])
            self.assertIn("JUnit reports failures or errors", receipt["groups"][0]["error"])
            self.assertTrue(list((reports / "junit").glob("platform-*.xml")))

    def test_platform_collects_only_the_debug_variant_selected_by_its_task(self):
        with tempfile.TemporaryDirectory(prefix="milestone-variant-reports-") as directory:
            root = Path(directory).resolve()
            reports = root / "reports"
            for name in ("metadata", "sarif", "junit", "coverage"):
                (reports / name).mkdir(parents=True, exist_ok=True)
            run = validate.Run(root, reports, SimpleNamespace(profile="platform", base_ref="origin/develop", gitleaks_executable=None))
            for variant, failures in (("debug", 0), ("benchmark", 1)):
                result = root / f"app/build/outputs/androidTest-results/connected/{variant}/TEST-result.xml"
                result.parent.mkdir(parents=True)
                result.write_text(f'<testsuite tests="1" failures="{failures}" errors="0" skipped="0"/>')
            counts = validate.collect_reports(run, succeeded=True)
            self.assertEqual((1, 0, 0), (counts["tests"], counts["failures"], counts["errors"]))
