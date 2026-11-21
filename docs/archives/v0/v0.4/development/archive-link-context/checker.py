"""Supply verified single-directory move coordinates to the official link checker."""
from __future__ import annotations
import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import posixpath
import runpy
import subprocess
import sys
import tempfile

OFFICIAL = Path.home() / '.agents/skills/docs-layout-refactor/scripts/link-baseline.py'
MAX_BYTES = 16 * 1024 * 1024


def digest(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def read_json(path):
    raw = Path(path).read_bytes()
    if len(raw) > MAX_BYTES:
        raise ValueError('context too large')
    return json.loads(raw)


def safe_path(value):
    if (not isinstance(value, str) or not value or len(value) > 4096
            or any(c in value for c in '\\:\t\r\n\0')
            or PurePosixPath(value).is_absolute()
            or '..' in PurePosixPath(value).parts
            or str(PurePosixPath(value)) != value):
        raise ValueError('noncanonical repository path')
    return value


def git(root, *args):
    result = subprocess.run(['git', '-C', str(root), *args], capture_output=True,
                            timeout=15, creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0))
    if result.returncode:
        raise ValueError('git observation unavailable')
    return result.stdout.decode('utf-8')


def files(root, committed=False):
    args = ('ls-tree', '-r', '--name-only', '-z', 'HEAD') if committed else ('ls-files', '-z')
    return sorted({safe_path(p) for p in git(root, *args).split('\0') if p})


def context_path(baseline):
    return Path(str(baseline) + '.context.json')


def context(root, baseline):
    return dict(root=str(root.resolve()), head=git(root, 'rev-parse', 'HEAD').strip(),
                files=files(root), baseline_sha256=digest(baseline),
                checker_sha256=digest(OFFICIAL), adapter_sha256=digest(__file__))


def load_context(baseline):
    data = read_json(context_path(baseline))
    if (not isinstance(data, dict) or set(data) != {'root', 'head', 'files',
            'baseline_sha256', 'checker_sha256', 'adapter_sha256'}
            or data['baseline_sha256'] != digest(baseline)
            or data['checker_sha256'] != digest(OFFICIAL)
            or data['adapter_sha256'] != digest(__file__)
            or not isinstance(data['files'], list)
            or data['files'] != sorted({safe_path(p) for p in data['files']})):
        raise ValueError('stale or malformed context')
    return data


def projected(path, old, new):
    return new + path[len(old):] if path == old or path.startswith(old + '/') else path


def scoped_map(before, after, mapping, official):
    left, right = load_context(before), load_context(after)
    root = Path(left['root']).resolve()
    if (left['root'] != right['root'] or str(root) != left['root']
            or left['head'] != right['head']
            or left['head'] != git(root, 'rev-parse', 'HEAD').strip()
            or left['files'] != files(root, committed=True)
            or right['files'] != files(root)):
        raise ValueError('repository observations changed')
    removed = set(left['files']) - set(right['files'])
    added = set(right['files']) - set(left['files'])
    if len(removed) < 2 or len(added) < 2:
        raise ValueError('a complete directory move is required')
    old, new = safe_path(posixpath.commonpath(sorted(removed))), safe_path(posixpath.commonpath(sorted(added)))
    if old in removed or new in added or old == new:
        raise ValueError('ambiguous directory move')
    if {projected(p, old, new) for p in left['files']} != set(right['files']):
        raise ValueError('move coordinates disagree with tracked files')
    result = official['_read_rename_map'](Path(mapping))
    for source, target in result.items():
        safe_path(source); safe_path(target)
        if source not in left['files'] or target != projected(source, old, new):
            raise ValueError('rename map disagrees with observed move')
    raw = Path(before).read_bytes()
    if len(raw) > MAX_BYTES:
        raise ValueError('baseline too large')
    for line in raw.decode('utf-8').splitlines():
        row = json.loads(line)
        source = safe_path(row['source'])
        if source not in left['files']:
            raise ValueError('baseline source is not tracked')
        for key in ('source', 'resolved_target'):
            value = safe_path(row[key])
            result[value] = projected(value, old, new)
    return result


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='command', required=True)
    baseline = commands.add_parser('baseline')
    baseline.add_argument('--root', type=Path, default=Path('.'))
    baseline.add_argument('--out', type=Path, required=True)
    diff = commands.add_parser('diff')
    diff.add_argument('--before', type=Path, required=True)
    diff.add_argument('--after', type=Path, required=True)
    diff.add_argument('--rename-map', type=Path, required=True)
    args = parser.parse_args(argv)
    try:
        official = runpy.run_path(str(OFFICIAL))
        if args.command == 'baseline':
            sidecar = context_path(args.out)
            if sidecar.exists():
                raise ValueError('context already exists')
            code = official['main'](['baseline', '--root', str(args.root), '--out', str(args.out)])
            if code != 0:
                return code
            with sidecar.open('x', encoding='utf-8') as output:
                json.dump(context(args.root, args.out), output)
            return 0
        mapping = scoped_map(args.before, args.after, args.rename_map, official)
        with tempfile.NamedTemporaryFile(mode='w', encoding='utf-8', suffix='.tsv',
                prefix='verified-link-map-', dir=args.before.parent, delete=False) as output:
            for source, target in sorted(mapping.items()):
                output.write(source + '\t' + target + '\n')
            name = output.name
        try:
            return official['main'](['diff', '--before', str(args.before), '--after', str(args.after),
                                     '--rename-map', name])
        finally:
            Path(name).unlink(missing_ok=True)
    except (OSError, ValueError, KeyError, TypeError, subprocess.SubprocessError):
        print('Archive link context is unavailable or inconsistent.', file=sys.stderr)
        return 2


if __name__ == '__main__':
    raise SystemExit(main())
