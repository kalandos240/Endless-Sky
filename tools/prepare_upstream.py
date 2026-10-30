#!/usr/bin/env python3
"""Fetch a pinned upstream checkout and apply the Android port patches."""
import pathlib
import os
import tempfile
import subprocess

ROOT = pathlib.Path(__file__).resolve().parents[1]
REVISION = (ROOT / 'upstream-revision.txt').read_text().strip()
DESTINATION = ROOT / 'build' / 'upstream'


def git(*args):
    return subprocess.run(['git', '-C', str(DESTINATION), *args], check=True)


def main():
    DESTINATION.parent.mkdir(parents=True, exist_ok=True)
    if not DESTINATION.exists():
        subprocess.run(['git', 'init', str(DESTINATION)], check=True)
        git('remote', 'add', 'origin', 'https://github.com/endless-sky/endless-sky.git')
    if not (DESTINATION / '.git').exists():
        raise SystemExit(f'Refusing to modify a non-git directory: {DESTINATION}')
    head = subprocess.run(['git', '-C', str(DESTINATION), 'rev-parse', 'HEAD'],
                          capture_output=True, text=True)
    if head.returncode:
        git('fetch', '--depth', '1', 'origin', REVISION)
        git('checkout', '--detach', REVISION)
    elif head.stdout.strip() != REVISION:
        raise SystemExit('Upstream revision differs; use a new clean checkout.')
    patches = sorted((ROOT / 'patches').glob('*.patch'))
    # Later patches can overlap earlier hunks, making individual reverse checks fail.
    # Build the final expected index without modifying the real index or working files.
    with tempfile.TemporaryDirectory(dir=DESTINATION.parent) as temporary:
        environment = dict(os.environ, GIT_INDEX_FILE=str(pathlib.Path(temporary) / 'index'))
        command = ['git', '-C', str(DESTINATION)]
        subprocess.run([*command, 'read-tree', 'HEAD'], env=environment, check=True)
        for patch in patches:
            subprocess.run([*command, 'apply', '--cached', str(patch)], env=environment, check=True)
        paths = subprocess.check_output([*command, 'diff', '--cached', '--name-only', '-z'],
                                        env=environment).decode().split('\0')
        paths = [path for path in paths if path]
        complete = subprocess.run([*command, 'diff', '--quiet', '--', *paths],
                                  env=environment).returncode == 0
        if complete:
            print(f'All {len(patches)} upstream patches already applied: {DESTINATION}')
            return
    for patch in patches:
        applied = subprocess.run(['git', '-C', str(DESTINATION), 'apply',
                                  '--reverse', '--check', str(patch)], capture_output=True)
        if applied.returncode == 0:
            print(f'Already applied: {patch.name}')
            continue
        git('apply', '--check', str(patch))
        git('apply', str(patch))
        print(f'Applied: {patch.name}')
    print(f'Prepared source: {DESTINATION}')


if __name__ == '__main__':
    main()
