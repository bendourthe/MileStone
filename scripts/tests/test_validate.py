"""Contract checks use temporary outputs and never run Gradle or a device suite."""
from contextlib import redirect_stdout
import io
import hashlib
import json
from pathlib import Path
from types import SimpleNamespace
import tempfile
import unittest
from unittest.mock import patch
import sys
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import validate


class ProfilesTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="milestone-validation-test-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name).resolve()
        self.reports = self.root / "evidence"
        self.args = SimpleNamespace(profile="fast", base_ref="origin/develop", gitleaks_executable=None)

    def run_context(self):
        for name in ("metadata", "sarif", "junit", "coverage"):
            (self.reports / name).mkdir(parents=True, exist_ok=True)
        return validate.Run(self.root, self.reports, self.args)

    def main(self, profile, *extra):
        output = io.StringIO()
        with redirect_stdout(output):
            code = validate.main(["--profile", profile, "--reports-dir", str(self.reports), "--json", *extra], root=self.root)
        return code, json.loads(output.getvalue())

    def test_every_profile_lists_without_running_or_writing(self):
        with patch.object(validate.Run, "raw", side_effect=AssertionError("listing must not execute")):
            for name in validate.PROFILES:
                code, listing = self.main(name, "--list")
                self.assertEqual(0, code)
                self.assertEqual(name, listing["profile"])
        self.assertFalse(self.reports.exists())

    def test_local_full_contains_one_complete_gradle_graph_and_ci_excludes_capture(self):
        local = validate.groups("full", "windows")
        android = next(group for group in local if group.name == "android")
        tasks = android.steps[0].argv
        for task in ("testDebugUnitTest", "lintDebug", "assembleDebug", "assembleCapture", "compileDebugAndroidTestKotlin", "createDebugUnitTestCoverageReport"):
            self.assertEqual(1, tasks.count(task))
        self.assertNotIn("connectedDebugAndroidTest", tasks)
        remote = next(group for group in validate.groups("full", "ci-linux") if group.name == "android")
        self.assertNotIn("assembleCapture", remote.steps[0].argv)
        self.assertTrue(any("ADR-0001" in note for note in validate.limitations("full", "ci-linux")))

    def test_fast_has_no_suite_or_install_and_release_does_not_revalidate(self):
        fast = " ".join(part for group in validate.groups("fast", "linux") for step in group.steps for part in step.argv)
        self.assertNotIn("testDebugUnitTest", fast)
        self.assertNotIn("unittest", fast)
        self.assertNotIn("install", fast)
        release = " ".join(part for group in validate.groups("release", "linux") for step in group.steps for part in step.argv)
        self.assertIn("@source-package", release)
        self.assertNotIn("assembleRelease", release)
        self.assertNotIn("testDebugUnitTest", release)
        self.assertNotIn("lintDebug", release)
        self.assertNotIn("publish", release)

    def test_failure_stops_its_group_but_later_groups_run_and_reports_survive(self):
        calls = []

        def command(run, argv, *args, **kwargs):
            calls.append(argv)
            if "py_compile" in argv:
                raise RuntimeError("Synthetic syntax failure")
            return b"ok"

        with patch.object(validate, "fingerprint", return_value="scope"), patch.dict(validate.ACTIONS, {"@private": lambda run: None}), patch.object(validate.Run, "command", command):
            code, summary = self.main("fast")
        self.assertEqual(1, code)
        self.assertEqual("FAIL", summary["status"])
        self.assertFalse(any("node" in argv for argv in calls))
        self.assertTrue(any("scripts/build_handbooks.py" in argv for argv in calls))
        self.assertTrue((self.reports / "summary.md").is_file())
        self.assertTrue((self.reports / "metadata/environment.json").is_file())

    def test_private_boundary_blocks_all_later_commands(self):
        def fail(run):
            raise RuntimeError("Private inputs are tracked")

        with patch.object(validate, "fingerprint", return_value="scope"), patch.dict(validate.ACTIONS, {"@private": fail}), patch.object(validate.Run, "command") as command:
            code, summary = self.main("fast")
        self.assertEqual(1, code)
        command.assert_not_called()
        self.assertEqual(["FAIL", "BLOCKED", "BLOCKED"], [g["status"] for g in summary["profiles"][0]["groups"]])

    def test_report_aggregates_only_and_rejects_a_stale_receipt(self):
        directory = self.reports / "results"
        directory.mkdir(parents=True)
        receipt = {"profile": "fast", "status": "PASS", "source_sha256": "old", "groups": []}
        (directory / "fast.json").write_text(json.dumps(receipt))
        with patch.object(validate, "fingerprint", return_value="current"), patch.object(validate.Run, "command") as command:
            code, summary = self.main("report")
        command.assert_not_called()
        self.assertEqual(1, code)
        self.assertTrue(any("stale" in note for note in summary["limitations"]))

    def test_report_without_any_receipt_cannot_claim_success(self):
        with patch.object(validate, "fingerprint", return_value="scope"):
            code, summary = self.main("report")
        self.assertEqual(1, code)
        self.assertEqual("FAIL", summary["status"])

    def test_emulator_guard_rejects_a_physical_or_unverified_target(self):
        run = self.run_context()
        with patch.object(run, "command", return_value=b"0") as command:
            with self.assertRaisesRegex(RuntimeError, "not a verified Android emulator"):
                validate.emulator_boundary(run)
        self.assertEqual(("adb", "-s", "emulator-5554", "shell", "getprop", "ro.kernel.qemu"), command.call_args.args[0])
        self.args.profile = "platform"
        platform_run = self.run_context()
        self.assertEqual("emulator-5554", platform_run.env["ANDROID_SERIAL"])

    def test_redaction_covers_environment_secrets_and_user_paths(self):
        secret = "unit-" + "credential"
        message = f"{secret} C:/Users/Somebody/secret.txt /home/somebody/a.txt"
        result = validate.redact(message, self.root, {"SERVICE_TOKEN": secret})
        self.assertNotIn(secret, result)
        self.assertNotIn("Somebody", result)
        self.assertNotIn("somebody", result)

    def test_shared_windows_spawn_hides_consoles_and_preserves_compatible_flags(self):
        with patch.object(validate.os, "name", "nt"), patch.object(validate.subprocess, "CREATE_NO_WINDOW", 0x08000000, create=True), patch.object(validate.subprocess, "Popen") as popen:
            validate.spawn(["tool"], creationflags=0x200)
            self.assertEqual(0x08000200, popen.call_args.kwargs["creationflags"])
            with self.assertRaises(ValueError):
                validate.spawn(["tool"], creationflags=0x10)

    def test_real_owned_child_timeout_returns_failure_without_delay_workaround(self):
        run = self.run_context()
        code, output = run.raw([sys.executable, "-c", "import time; time.sleep(30)"], timeout=0.05)
        self.assertEqual(124, code)
        self.assertIn(b"timed out", output)

    def test_security_missing_tools_is_failure_not_a_fake_scan(self):
        run = self.run_context()
        with patch.object(validate.shutil, "which", return_value=None):
            with self.assertRaisesRegex(RuntimeError, "Security not covered"):
                validate.security(run)
        self.assertFalse((self.reports / "metadata/security.json").exists())

    def test_docker_scan_mounts_only_config_and_sarif_and_detects_its_control(self):
        run = self.run_context()
        calls = []

        def command(argv, timeout=60, data=None, allowed=(0,)):
            calls.append((argv, data, allowed))
            if allowed == (1,):
                return b"leaks found: 1"
            if "--report-format" in argv:
                (self.reports / "sarif/gitleaks.sarif").write_text('{"runs": [{"results": []}]}')
            return b"cached image"

        with patch.object(validate.shutil, "which", side_effect=lambda name: "docker" if name == "docker" else None), patch.object(run, "command", command), patch.object(validate, "candidate_input", return_value=b"Synthetic public source"):
            validate.security(run)
        scan = next(argv for argv, _, _ in calls if "--report-format" in argv)
        mounts = [scan[i + 1] for i, value in enumerate(scan) if value == "--mount"]
        self.assertEqual([f"type=bind,source={self.root / '.gitleaks.toml'},target=/config/gitleaks.toml,readonly", f"type=bind,source={self.reports / 'sarif'},target=/report"], mounts)
        self.assertIn("--pull=never", scan)
        self.assertIn("--network=none", scan)
        self.assertNotIn("/repo", scan)
        self.assertTrue(json.loads((self.reports / "metadata/security.json").read_text())["positive_control_detected"])

    def test_undetected_positive_control_stops_the_security_group(self):
        run = self.run_context()
        with patch.object(validate.shutil, "which", side_effect=lambda name: "docker" if name == "docker" else None), patch.object(run, "command", return_value=b"") as command:
            with self.assertRaisesRegex(RuntimeError, "positive control"):
                validate.security(run)
        self.assertFalse(any("--report-format" in call.args[0] for call in command.call_args_list))

    def test_public_input_fingerprint_ignores_release_receipts_but_not_code(self):
        run = self.run_context()
        source = self.root / "app/src/main/Source.kt"
        source.parent.mkdir(parents=True)
        source.write_text("Synthetic source one")
        evidence = self.root / "docs/releases/v0/v0.4/development/verification.json"
        evidence.parent.mkdir(parents=True)
        evidence.write_text("Initial receipt")
        listing = b"app/src/main/Source.kt\0docs/releases/v0/v0.4/development/verification.json\0"
        with patch.object(run, "git", return_value=listing):
            before = validate.fingerprint(run)
            evidence.write_text("Final receipt")
            self.assertEqual(before, validate.fingerprint(run))
            source.write_text("Synthetic source two")
            self.assertNotEqual(before, validate.fingerprint(run))

    def test_failed_task_does_not_reuse_old_test_xml_but_success_names_cache_scope(self):
        run = self.run_context()
        self.args.profile = "full"
        result = self.root / "app/build/test-results/testDebugUnitTest/TEST-sample.xml"
        result.parent.mkdir(parents=True)
        result.write_text('<testsuite tests="3" failures="0" errors="0" skipped="0"/>')
        import os
        os.utime(result, (run.started - 100, run.started - 100))
        partial = validate.collect_reports(run, succeeded=False)
        self.assertEqual(0, partial["tests"])
        reused = validate.collect_reports(run, succeeded=True)
        self.assertEqual(3, reused["tests"])
        self.assertIn("Gradle-validated up-to-date", reused["evidence_origin"])

    def test_source_input_never_reads_private_untracked_files_or_report_outputs(self):
        run = self.run_context()
        public = self.root / "public.txt"
        public.write_text("Synthetic public source")
        private = self.root / "app/src/test/resources/fixtures-private/private.txt"
        private.parent.mkdir(parents=True)
        private.write_text("Synthetic data that must not be opened")
        sensitive = [self.root / name for name in (".env.local", ".ENV", "snapshot.db-wal", "snapshot.db-shm", "private.key")]
        for path in sensitive:
            path.write_text("Synthetic secret-shaped input that must not be opened")
        (self.reports / "summary.json").write_text("Generated report excluded from source")

        def git(*args):
            return b"public.txt\0app/src/test/resources/fixtures-private/private.txt\0evidence/summary.json\0.env.local\0.ENV\0snapshot.db-wal\0snapshot.db-shm\0private.key\0" if args[0] == "ls-files" else b""

        original = Path.read_bytes

        def read(path):
            self.assertNotEqual(private, path)
            self.assertNotIn(path, sensitive)
            return original(path)

        with patch.object(run, "git", git), patch.object(Path, "read_bytes", read):
            content = validate.candidate_input(run)
        self.assertIn(b"Synthetic public source", content)
        self.assertNotIn(b"Generated report", content)

    def test_source_package_uses_clean_immutable_canonical_archive(self):
        run = self.run_context()
        buffer = io.BytesIO()
        with zipfile.ZipFile(buffer, "w") as archive:
            archive.writestr("app/build.gradle.kts", 'versionName = "0.4.0"')
            archive.writestr("README.md", "Synthetic public source")
        content = buffer.getvalue()
        calls = []

        def git(*args):
            calls.append(args)
            if args[0] == "status": return b""
            if args[0] == "rev-parse": return b"a" * 40
            if args[0] == "ls-tree": return b"app/build.gradle.kts\0README.md\0"
            if args[0] == "show": return b'versionName = "0.4.0"'
            if args[:6] == ("-c", "core.autocrlf=false", "-c", "core.eol=lf", "archive", "--format=zip"): return content
            raise AssertionError(args)

        with patch.object(run, "git", git):
            validate.source_package(run)
        metadata = json.loads((self.reports / "release/artifacts.json").read_text())
        self.assertEqual("a" * 40, metadata["commit"])
        self.assertTrue(metadata["canonical_archive_verified"])
        self.assertFalse(metadata["private_inputs_included"])
        self.assertEqual(2, sum(args[:6] == ("-c", "core.autocrlf=false", "-c", "core.eol=lf", "archive", "--format=zip") for args in calls))
        self.assertEqual(content, (self.reports / metadata["path"]).read_bytes())

    def test_real_source_package_preserves_committed_lf_with_autocrlf_enabled(self):
        import os
        self.run_context()
        repository = self.root / "repository"
        repository.mkdir()
        run = validate.Run(repository, self.reports, self.args)
        run.env.update(GIT_CONFIG_GLOBAL=os.devnull, GIT_CONFIG_NOSYSTEM="1",
                       GIT_AUTHOR_NAME="Synthetic Test", GIT_AUTHOR_EMAIL="test@example.invalid",
                       GIT_COMMITTER_NAME="Synthetic Test", GIT_COMMITTER_EMAIL="test@example.invalid")
        run.git("init", "--quiet")
        run.git("config", "core.autocrlf", "true")
        run.git("config", "commit.gpgsign", "false")
        build = repository / "app/build.gradle.kts"
        build.parent.mkdir()
        build.write_bytes(b'versionName = "0.4.0"\n')
        readme = repository / "README.md"
        readme.write_bytes(b"Synthetic public source\nSecond line\n")
        run.git("add", "app/build.gradle.kts", "README.md")
        run.git("commit", "--quiet", "-m", "Synthetic source archive regression")
        blob = run.git("show", "HEAD:README.md")
        self.assertNotIn(b"\r", blob)
        with zipfile.ZipFile(io.BytesIO(run.git("archive", "--format=zip", "HEAD"))) as archive:
            self.assertNotEqual(blob, archive.read("README.md"))
        validate.source_package(run)
        metadata = json.loads((self.reports / "release/artifacts.json").read_text())
        with zipfile.ZipFile(self.reports / metadata["path"]) as archive:
            self.assertEqual(blob, archive.read("README.md"))
            self.assertEqual(run.git("show", "HEAD:app/build.gradle.kts"), archive.read("app/build.gradle.kts"))
        self.assertEqual(b"true", run.git("config", "core.autocrlf").strip())

    def test_source_package_refuses_a_dirty_tree_before_reading_or_archiving(self):
        run = self.run_context()
        with patch.object(run, "git", return_value=b" M app/source.kt") as git:
            with self.assertRaisesRegex(RuntimeError, "clean committed candidate"):
                validate.source_package(run)
        self.assertEqual(1, git.call_count)

    def test_tracked_private_paths_are_rejected_by_shared_policy_without_reading_contents(self):
        run = self.run_context()
        for name in ("keys/private.key", "keys/client.p12", "id_rsa", "snapshot.db-wal", "snapshot.sqlite-shm", "nested/.ENV.production"):
            with self.subTest(name=name), patch.object(run, "git", return_value=name.encode() + b"\0") as git, patch.object(Path, "read_bytes", side_effect=AssertionError("Private contents must not be read")):
                with self.assertRaisesRegex(RuntimeError, "contents were not read"):
                    validate.private_boundary(run)
                self.assertEqual(("ls-files", "--cached", "-z"), git.call_args.args)
        with patch.object(run, "git", return_value=b".env.example\0README.md\0"):
            validate.private_boundary(run)

    def test_source_package_preflights_committed_names_before_show_or_archive(self):
        run = self.run_context()
        calls = []

        def git(*args):
            calls.append(args)
            if args[0] == "status": return b""
            if args[0] == "rev-parse": return b"a" * 40
            if args[0] == "ls-tree": return b"README.md\0keys/private.key\0"
            raise AssertionError("Private committed contents were requested")

        with patch.object(run, "git", git), patch.object(Path, "read_bytes", side_effect=AssertionError("No private byte reads")):
            with self.assertRaisesRegex(RuntimeError, "contents were not read"):
                validate.source_package(run)
        self.assertEqual(["status", "rev-parse", "ls-tree"], [call[0] for call in calls])
        self.assertFalse((self.reports / "release").exists())

    def test_report_rechecks_security_payload_without_rerunning_scan_or_invalidating_android(self):
        run = self.run_context()
        evidence = self.root / "docs/releases/v0/v0.4/development/evidence.md"
        evidence.parent.mkdir(parents=True)
        evidence.write_text("Synthetic release evidence before scan")

        def git(_run, *args):
            return evidence.relative_to(self.root).as_posix().encode() + b"\0" if args[0] == "ls-files" else b""

        with patch.object(validate.Run, "git", git):
            original = hashlib.sha256(validate.candidate_input(run, history=True)).hexdigest()
        directory = self.reports / "results"
        directory.mkdir()
        receipt = {"profile": "full", "status": "PASS", "source_sha256": "unchanged-code", "groups": [{"name": "android", "status": "PASS", "seconds": 1}, {"name": "security", "status": "PASS", "seconds": 1}], "security": {"status": "PASS", "base_ref": "origin/develop", "input_sha256": original, "findings": 0}}
        retained = directory / "full.json"
        retained.write_text(json.dumps(receipt))
        with patch.object(validate, "fingerprint", return_value="unchanged-code"), patch.object(validate.Run, "git", git), patch.object(validate.Run, "command", side_effect=AssertionError("Report cannot run validators")):
            code, _ = self.main("report")
            self.assertEqual(0, code)
            evidence.write_text("Synthetic release evidence changed after scan")
            code, summary = self.main("report")
        self.assertEqual(1, code)
        current = summary["profiles"][0]
        self.assertEqual("STALE", current["security"]["status"])
        self.assertEqual(["PASS", "STALE"], [group["status"] for group in current["groups"]])
        self.assertEqual(receipt, json.loads(retained.read_text()))
        self.assertIn("Security evidence is stale", (self.reports / "summary.md").read_text())

    def test_blocked_full_clears_owned_exports_and_security_but_preserves_platform_evidence(self):
        self.run_context()
        owned = ["junit/full-000.xml", "coverage/report.xml", "lint-results-debug.xml", "sarif/gitleaks.sarif", "sarif/index.json", "metadata/security.json"]
        for name in owned:
            (self.reports / name).write_text("Old passing exported evidence")
        preserved = self.reports / "junit/platform-000.xml"
        preserved.write_text("Other profile evidence")
        directory = self.reports / "results"
        directory.mkdir()
        platform_receipt = directory / "platform-windows.json"
        platform_receipt.write_text('{"profile":"platform","status":"PASS"}')
        source = self.root / "app/build/test-results/testDebugUnitTest/TEST-old.xml"
        source.parent.mkdir(parents=True)
        source.write_text('<testsuite tests="3"/>')
        import os
        os.utime(source, (1, 1))
        with patch.object(validate, "fingerprint", return_value="scope"), patch.dict(validate.ACTIONS, {"@private": lambda run: (_ for _ in ()).throw(RuntimeError("Synthetic boundary failure"))}), patch.object(validate.Run, "raw", return_value=(0, b"Synthetic tool")), patch.object(validate.Run, "command", side_effect=AssertionError("Blocked full cannot start commands")):
            code, summary = self.main("full")
        self.assertEqual(1, code)
        self.assertEqual(0, summary["profiles"][0]["counts"]["tests"])
        for name in owned:
            if name == "sarif/index.json":
                self.assertEqual([], json.loads((self.reports / name).read_text()))
            else:
                self.assertFalse((self.reports / name).exists(), name)
        self.assertEqual("Other profile evidence", preserved.read_text())
        self.assertEqual('{"profile":"platform","status":"PASS"}', platform_receipt.read_text())

    def test_collection_replaces_exported_xml_on_repeated_failed_invocations(self):
        self.args.profile = "full"
        run = self.run_context()
        old = self.reports / "junit/full-000.xml"
        old.write_text('<testsuite tests="999"/>')
        self.assertEqual(0, validate.collect_reports(run, succeeded=False)["tests"])
        self.assertFalse(old.exists())

    def test_fatal_profile_error_replaces_its_retained_pass_receipt(self):
        directory = self.reports / "results"
        directory.mkdir(parents=True)
        retained = directory / ("full-" + validate.host_class() + ".json")
        retained.write_text('{"profile":"full","status":"PASS"}')
        with patch.object(validate, "fingerprint", side_effect=RuntimeError("Synthetic unreadable inputs")):
            code, _ = self.main("full")
        self.assertEqual(1, code)
        self.assertEqual("FAIL", json.loads(retained.read_text())["status"])


if __name__ == "__main__":
    unittest.main()
