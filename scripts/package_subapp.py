#!/usr/bin/env python3
"""Build a deterministic runtime ZIP. Never package development/user records."""
import argparse
import hashlib
import json
import pathlib
import subprocess
import zipfile


def package(source, output, preview=False):
    source = source.resolve()
    manifest = json.loads((source / 'manifest.json').read_text())
    update = manifest['app2Update']
    managed, seeds = update['managedFiles'], update.get('seedFiles', [])
    paths = managed + seeds
    if len(paths) != len(set(paths)) or 'index.html' not in managed or 'manifest.json' not in managed:
        raise ValueError('Invalid file ownership list')
    for name in paths:
        path = pathlib.PurePosixPath(name)
        if not name or name.startswith('/') or '\\' in name or any(part in ('', '.', '..') or part.startswith('.') for part in name.split('/')):
            raise ValueError(f'Unsafe path: {name}')
        if not (source / path).resolve().is_relative_to(source):
            raise ValueError(f'Path escapes source: {name}')
    revision = subprocess.check_output(['git', '-C', str(source), 'rev-parse', 'HEAD'], text=True).strip()
    dirty = bool(subprocess.check_output(['git', '-C', str(source), 'status', '--porcelain'], text=True).strip())
    if preview:
        manifest['name'] = manifest['short_name'] = manifest.get('name', 'App') + ' Preview'
    manifest['buildRevision'] = revision
    manifest['buildDirty'] = dirty
    hashes = {}
    output.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(output, 'w', compression=zipfile.ZIP_DEFLATED) as archive:
        for name in sorted(paths):
            if name == 'manifest.json':
                content = (json.dumps(manifest, ensure_ascii=False, indent=2) + '\n').encode()
            elif name in seeds:
                # Seed from declared empty defaults, never export a developer's personal records.
                value = update.get('seedDefaults', {}).get(name)
                if value is None:
                    raise ValueError(f'Missing empty seed default: {name}')
                content = (json.dumps(value, ensure_ascii=False) + '\n').encode()
            else:
                content = (source / name).read_bytes()
            entry = zipfile.ZipInfo(name, date_time=(2020, 1, 1, 0, 0, 0))
            entry.compress_type = zipfile.ZIP_DEFLATED
            entry.external_attr = 0o100644 << 16
            archive.writestr(entry, content)
            hashes[name] = hashlib.sha256(content).hexdigest()
    with zipfile.ZipFile(output) as archive:
        assert archive.testzip() is None
        assert set(archive.namelist()) == set(paths)
    report = {'revision': revision, 'dirty': dirty, 'files': len(paths), 'zip_sha256': hashlib.sha256(output.read_bytes()).hexdigest(), 'hashes': hashes}
    output.with_suffix('.checks.json').write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps({key: report[key] for key in ('revision', 'dirty', 'files', 'zip_sha256')}))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('source', type=pathlib.Path)
    parser.add_argument('output', type=pathlib.Path)
    parser.add_argument('--preview', action='store_true')
    args = parser.parse_args()
    package(args.source, args.output, args.preview)
