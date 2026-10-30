#!/usr/bin/env python3
"""Prepare pinned sources and deterministic Android assets (no SDK needed)."""
import argparse
import hashlib
import json
import os
import shutil
from pathlib import Path
import subprocess
import zipfile

from prepare_upstream import main as prepare_upstream

ROOT = Path(__file__).resolve().parents[1]
RESOURCE_DIRS = ('data', 'images', 'shaders', 'sounds')
RESOURCE_FILES = ('credits.txt', 'keys.txt', 'copyright', 'license.txt', 'changelog')


def fetch_dependency(name, spec):
    destination = ROOT / 'build' / name
    if not destination.exists():
        subprocess.run(['git', 'init', str(destination)], check=True)
        subprocess.run(['git', '-C', str(destination), 'remote', 'add', 'origin',
                        spec['repository']], check=True)
    if not (destination / '.git').exists():
        raise RuntimeError(f'Refusing non-git dependency directory: {destination}')
    head = subprocess.run(['git', '-C', str(destination), 'rev-parse', 'HEAD'],
                          capture_output=True, text=True)
    if head.returncode:
        subprocess.run(['git', '-C', str(destination), 'fetch', '--depth', '1',
                        'origin', spec['revision']], check=True)
        subprocess.run(['git', '-C', str(destination), 'checkout', '--detach',
                        spec['revision']], check=True)
    elif head.stdout.strip() != spec['revision']:
        raise RuntimeError(f'{name} revision differs; use a new clean checkout.')
    if subprocess.check_output(['git', '-C', str(destination), 'status', '--porcelain']):
        raise RuntimeError(f'{name} contains local changes; refusing to use it.')


def package_resources(source, output):
    """Produce stable ZIP bytes and a SHA-256 manifest used by the installer."""
    source, output = Path(source), Path(output)
    files = []
    for name in RESOURCE_DIRS:
        directory = source / name
        if directory.is_symlink() or not directory.is_dir():
            raise ValueError(f'Missing resource directory: {directory}')
        for path in directory.rglob('*'):
            if path.is_symlink():
                raise ValueError(f'Resource symlinks are not allowed: {path}')
            if path.is_file():
                files.append(path)
    files.extend(source / name for name in RESOURCE_FILES)
    files.sort(key=lambda p: p.relative_to(source).as_posix())
    output.mkdir(parents=True, exist_ok=True)
    manifest = []
    total = 0
    temporary = output / 'resources.zip.tmp'
    try:
        with zipfile.ZipFile(temporary, 'w', compression=zipfile.ZIP_DEFLATED,
                             compresslevel=6, allowZip64=True) as archive:
            for path in files:
                if path.is_symlink() or not path.is_file():
                    raise ValueError(f'Resource must be a regular file: {path}')
                relative = path.relative_to(source).as_posix()
                if any(c in relative for c in '\t\r\n\\'):
                    raise ValueError(f'Unsupported resource name: {relative!r}')
                digest = hashlib.sha256()
                size = 0
                info = zipfile.ZipInfo(relative, date_time=(1980, 1, 1, 0, 0, 0))
                info.compress_type = zipfile.ZIP_DEFLATED
                info.external_attr = 0o100644 << 16
                with path.open('rb') as stream, archive.open(info, 'w', force_zip64=True) as target:
                    while chunk := stream.read(1024 * 1024):
                        digest.update(chunk)
                        size += len(chunk)
                        target.write(chunk)
                manifest.append(f'{digest.hexdigest()}\t{size}\t{relative}\n')
                total += size
        body = ''.join(manifest).encode('utf-8')
        version = hashlib.sha256(body).hexdigest()
        header = f'ESRES1\t{version}\t{total}\n'.encode('ascii')
        manifest_tmp = output / 'resources.manifest.tmp'
        manifest_tmp.write_bytes(header + body)
        os.replace(temporary, output / 'resources.zip')
        os.replace(manifest_tmp, output / 'resources.manifest')
    finally:
        temporary.unlink(missing_ok=True)
    print(f'Packaged {len(files)} resources, {total:,} bytes, version {version[:12]}')
    return version


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--assets-only', action='store_true',
                        help='Use prepared upstream; skip dependency downloads.')
    args = parser.parse_args()
    if not args.assets_only:
        prepare_upstream()
        specs = json.loads((ROOT / 'tools' / 'dependencies.json').read_text())
        for name, spec in specs.items():
            fetch_dependency(name, spec)
        native_version = json.loads((ROOT / 'build' / 'vcpkg' / 'ports' / 'sdl2' / 'vcpkg.json').read_text())['version']
        if native_version != specs['sdl2']['version']:
            raise RuntimeError('SDL Java/native version lock differs from the vcpkg baseline.')
        bootstrap = ROOT / 'build' / 'vcpkg' / ('bootstrap-vcpkg.bat' if os.name == 'nt' else 'bootstrap-vcpkg.sh')
        command = ['cmd', '/c', str(bootstrap)] if os.name == 'nt' else [str(bootstrap)]
        subprocess.run([*command, '-disableMetrics'], check=True)
    assets = ROOT / 'build' / 'android-assets'
    package_resources(ROOT / 'build' / 'upstream', assets)
    icon_directory = ROOT / 'build' / 'android-res' / 'mipmap-nodpi'
    icon_directory.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(ROOT / 'build' / 'upstream' / 'icons' / 'icon_512x512.png', icon_directory / 'ic_launcher.png')
    sdl_license = ROOT / 'build' / 'sdl2' / 'LICENSE.txt'
    if sdl_license.is_file():
        (assets / 'licenses').mkdir(exist_ok=True)
        shutil.copyfile(sdl_license, assets / 'licenses' / 'SDL2.txt')


if __name__ == '__main__':
    main()
