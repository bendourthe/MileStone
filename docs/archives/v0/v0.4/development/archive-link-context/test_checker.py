import contextlib
import importlib.util
import io
import json
from pathlib import Path
import runpy
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('local_link_context', Path(__file__).with_name('checker.py'))
checker = importlib.util.module_from_spec(spec)
spec.loader.exec_module(checker)
OFFICIAL = runpy.run_path(str(checker.OFFICIAL))


class ContextTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name).resolve()
        self.before = self.root / 'before.ndjson'
        self.after = self.root / 'after.ndjson'
        self.renames = self.root / 'renames.tsv'
        self.old = 'docs/releases/v0/v0.4'
        self.new = 'docs/archives/v0/v0.4'
        self.sources = [self.old + '/plans/search.md', self.old + '/known-gaps.md']
        self.sources += [self.old + '/development/report-' + str(i) + '.md' for i in range(12)]
        self.stationary = 'docs/releases/v0/v0.5/plans/next.md'
        self.left = sorted(self.sources + [self.stationary])
        self.right = sorted([self.project(p) for p in self.sources] + [self.stationary])
        self.outside = 'docs/releases/v0/implement-phase/contract.md'
        self.row(self.before, self.sources[0], self.outside)
        self.row(self.after, self.project(self.sources[0]), self.outside)
        self.renames.write_text(''.join(p + '\t' + self.project(p) + '\n' for p in self.sources))

    def project(self, value):
        return checker.projected(value, self.old, self.new)

    def row(self, file, source, target, append=False):
        with file.open('a' if append else 'w', encoding='utf-8') as stream:
            stream.write(json.dumps(dict(source=source, resolved_target=target, link='fixture')) + '\n')

    def mapping(self, left=None, right=None):
        left = self.left if left is None else left
        right = self.right if right is None else right
        contexts = [dict(root=str(self.root), head='a' * 40, files=left),
                    dict(root=str(self.root), head='a' * 40, files=right)]
        with patch.object(checker, 'load_context', side_effect=contexts), \
                patch.object(checker, 'git', return_value='a' * 40), \
                patch.object(checker, 'files', side_effect=lambda root, committed=False: left if committed else right):
            return checker.scoped_map(self.before, self.after, self.renames, OFFICIAL)

    def diff(self, mapping):
        file = self.root / 'verified.tsv'
        file.write_text(''.join(a + '\t' + b + '\n' for a, b in mapping.items()))
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            code = OFFICIAL['main'](['diff', '--before', str(self.before), '--after', str(self.after),
                                     '--rename-map', str(file)])
        return code, json.loads(output.getvalue())

    def test_preexisting_external_target_stays_in_original_coordinates(self):
        mapping = self.mapping()
        self.assertEqual(mapping[self.outside], self.outside)
        code, result = self.diff(mapping)
        self.assertEqual(code, 0)
        self.assertEqual(result['totals']['newly_broken'], 0)

    def test_real_new_break_is_not_hidden(self):
        self.row(self.after, self.project(self.sources[0]), 'docs/deliberately-missing.md', append=True)
        code, result = self.diff(self.mapping())
        self.assertEqual(code, 1)
        self.assertEqual(result['totals']['newly_broken'], 1)

    def test_preexisting_missing_target_inside_moved_tree_is_projected(self):
        self.row(self.before, self.sources[0], self.old + '/missing.md')
        self.row(self.after, self.project(self.sources[0]), self.new + '/missing.md')
        code, result = self.diff(self.mapping())
        self.assertEqual((code, result['totals']['newly_broken']), (0, 0))

    def test_unrelated_added_file_refuses_projection(self):
        with self.assertRaises(ValueError):
            self.mapping(right=sorted(self.right + ['unrelated.txt']))

    def test_conflicting_rename_map_refuses_projection(self):
        self.renames.write_text(self.sources[0] + '\t' + self.new + '/wrong.md\n')
        with self.assertRaises(ValueError):
            self.mapping()

    def test_untracked_baseline_source_refuses_projection(self):
        self.row(self.before, 'untracked.md', self.outside)
        with self.assertRaises(ValueError):
            self.mapping()

    def test_noncanonical_paths_refuse(self):
        for value in ['../escape', '/absolute', 'C:/absolute', 'tabs\tinjected', 'a/../b', 'a//b']:
            with self.subTest(value=value), self.assertRaises(ValueError):
                checker.safe_path(value)

    def test_baseline_tampering_refuses_context(self):
        data = dict(root=str(self.root), head='a' * 40, files=self.left,
                    baseline_sha256=checker.digest(self.before),
                    checker_sha256=checker.digest(checker.OFFICIAL),
                    adapter_sha256=checker.digest(checker.__file__))
        checker.context_path(self.before).write_text(json.dumps(data))
        self.before.write_text('tampered')
        with self.assertRaises(ValueError):
            checker.load_context(self.before)

    def test_missing_context_cannot_return_success(self):
        output, errors = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(output), contextlib.redirect_stderr(errors):
            code = checker.main(['diff', '--before', str(self.before), '--after', str(self.after),
                                 '--rename-map', str(self.renames)])
        self.assertEqual(code, 2)
        self.assertEqual(output.getvalue(), '')

    def test_official_baseline_failure_is_preserved(self):
        with patch.object(checker.runpy, 'run_path', return_value={'main': lambda argv: 7}):
            self.assertEqual(checker.main(['baseline', '--root', str(self.root), '--out', str(self.root/'failed.ndjson')]), 7)
        self.assertFalse(checker.context_path(self.root/'failed.ndjson').exists())


if __name__ == '__main__':
    unittest.main()
