#!/usr/bin/env python3
"""Fetch a pinned upstream checkout and apply the Android port patches."""
import pathlib
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
    for patch in sorted((ROOT / 'patches').glob('*.patch')):
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
