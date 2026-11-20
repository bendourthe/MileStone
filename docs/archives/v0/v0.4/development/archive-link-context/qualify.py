"""Real-checkout qualification of the archive link-context adapter.

Replays the native archive order on a fresh detached worktree:
baseline -> staged git mv of the v0.4 tree -> unstaged repairs -> R-only rename map
-> baseline -> diff. Then runs countercontrols. Writes every output to --out.
"""
from __future__ import annotations
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import sys

HERE = Path(__file__).resolve().parent
ADAPTER = HERE / 'checker.py'
OFFICIAL = Path.home() / '.agents/skills/docs-layout-refactor/scripts/link-baseline.py'
OLD, NEW = 'docs/releases/v0/v0.4', 'docs/archives/v0/v0.4'
REPAIRS = {  # repaired path after the move -> same path in the diagnostic replay
    p: p for p in ['docs/DEVLOG.md', 'docs/archives/v0/v0.3/known-gaps.md', NEW + '/known-gaps.md',
                   NEW + '/plans/v0.4.0-global-search.md', 'docs/todos.md', 'scripts/tests/test_validate.py']}
NO_WINDOW = getattr(subprocess, 'CREATE_NO_WINDOW', 0)


def run(cmd, log, name, cwd=None):
    proc = subprocess.run(cmd, capture_output=True, cwd=cwd, creationflags=NO_WINDOW, stdin=subprocess.DEVNULL)
    out, err = proc.stdout.decode('utf-8', 'replace'), proc.stderr.decode('utf-8', 'replace')
    log.append(dict(step=name, argv=[str(c) for c in cmd], exit=proc.returncode, stdout=out, stderr=err))
    return proc.returncode, out


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def totals(out):
    try:
        return json.loads(out)['totals']
    except (ValueError, KeyError, TypeError):
        return None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--worktree', type=Path, required=True)
    ap.add_argument('--replay', type=Path, required=True)
    ap.add_argument('--out', type=Path, required=True)
    a = ap.parse_args()
    w, out, log, checks = a.worktree.resolve(), a.out.resolve(), [], {}
    out.mkdir(parents=True, exist_ok=False)
    py = [sys.executable, '-I']
    adapter = lambda *args: py + [str(ADAPTER), *map(str, args)]
    official = lambda *args: py + [str(OFFICIAL), *map(str, args)]
    git = lambda *args: ['git', '-C', str(w), *args]

    run(git('rev-parse', 'HEAD'), log, 'head')
    run(git('status', '--porcelain'), log, 'status-clean-before')
    checks['clean_start'] = log[-1]['exit'] == 0 and log[-1]['stdout'] == ''
    before = out / 'before.ndjson'
    checks['baseline_before'] = run(adapter('baseline', '--root', w, '--out', before), log, 'baseline-before')[0] == 0

    run(git('mv', OLD, NEW), log, 'git-mv')
    for dest, src in REPAIRS.items():
        shutil.copyfile(a.replay / src, w / dest)  # working tree only, like the native repair writes
    log.append(dict(step='repairs', files={d: sha(w / d) for d in REPAIRS}))
    code, names = run(git('diff', '--cached', '--name-status', '-M', 'HEAD'), log, 'name-status')
    renames = out / 'rename-map.tsv'
    renames.write_text('\n'.join(l for l in names.splitlines() if l.startswith('R')) + '\n', encoding='utf-8')
    checks['index_is_pure_moves'] = code == 0 and all(l.startswith('R') for l in names.splitlines() if l)

    after = out / 'after.ndjson'
    checks['baseline_after'] = run(adapter('baseline', '--root', w, '--out', after), log, 'baseline-after')[0] == 0

    code, report = run(adapter('diff', '--before', before, '--after', after, '--rename-map', renames), log, 'adapter-diff')
    (out / 'adapter-diff.json').write_text(report, encoding='utf-8')
    t = totals(report)
    checks['adapter_zero_new'] = code == 0 and t is not None and t['newly_broken'] == 0
    unchanged = json.loads(report)['unchanged'] if t else []
    checks['preexisting_missing_target_preserved'] = any(
        r['resolved_target'] == 'docs/releases/v0/implement-phase/references/completion-contract.md'
        and r['source'] == NEW + '/plans/v0.4.0-global-search.md' for r in unchanged)

    code, report = run(official('diff', '--before', before, '--after', after, '--rename-map', renames), log, 'official-diff-countercontrol')
    (out / 'official-diff.json').write_text(report, encoding='utf-8')
    t = totals(report)
    checks['official_alone_reproduces_refusal'] = code == 1 and t is not None and t['newly_broken'] >= 1

    # Countercontrol: a genuinely new broken Markdown link in a moved file must fail.
    target = w / NEW / 'known-gaps.md'
    original = target.read_bytes()
    target.write_bytes(original + b'\n[qualification](./deliberately-missing-qualification.md)\n')
    broken = out / 'after-broken.ndjson'
    run(adapter('baseline', '--root', w, '--out', broken), log, 'baseline-after-broken')
    code, report = run(adapter('diff', '--before', before, '--after', broken, '--rename-map', renames), log, 'adapter-diff-new-break')
    t = totals(report)
    checks['new_break_fails'] = code == 1 and t is not None and t['newly_broken'] == 1
    target.write_bytes(original)

    # Countercontrol: missing context.
    missing = out / 'before-missing.ndjson'
    shutil.copyfile(before, missing)
    code, report = run(adapter('diff', '--before', missing, '--after', after, '--rename-map', renames), log, 'missing-context')
    checks['missing_context_fails'] = code == 2 and report == ''

    # Countercontrol: tampered baseline with its original context.
    tampered = out / 'before-tampered.ndjson'
    tampered.write_bytes(before.read_bytes().replace(b'implement-phase', b'implement-phasX'))
    shutil.copyfile(Path(str(before) + '.context.json'), Path(str(tampered) + '.context.json'))
    code, report = run(adapter('diff', '--before', tampered, '--after', after, '--rename-map', renames), log, 'tampered-baseline')
    checks['tampered_baseline_fails'] = code == 2 and report == ''

    # Countercontrol: stale index (an extra staged file after the after-baseline).
    extra = w / 'qualification-extra.txt'
    extra.write_text('x', encoding='utf-8')
    run(git('add', '--', 'qualification-extra.txt'), log, 'stage-extra')
    code, report = run(adapter('diff', '--before', before, '--after', after, '--rename-map', renames), log, 'stale-index')
    checks['stale_index_fails'] = code == 2 and report == ''
    run(git('rm', '-q', '--cached', '--', 'qualification-extra.txt'), log, 'unstage-extra')
    extra.unlink()

    # The original passing diff still passes after countercontrols were undone.
    code, report = run(adapter('diff', '--before', before, '--after', after, '--rename-map', renames), log, 'adapter-diff-repeat')
    t = totals(report)
    checks['repeat_zero_new'] = code == 0 and t is not None and t['newly_broken'] == 0

    hashes = {p.name: sha(p) for p in [ADAPTER, OFFICIAL, Path(__file__), before, after, renames]}
    result = dict(passed=all(checks.values()), checks=checks, hashes=hashes, log=log)
    (out / 'qualification.json').write_text(json.dumps(result, indent=1), encoding='utf-8')
    print(json.dumps(dict(passed=result['passed'], checks=checks), indent=1))
    return 0 if result['passed'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
