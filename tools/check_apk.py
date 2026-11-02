#!/usr/bin/env python3
"""Check that a development APK contains the complete arm64 native runtime."""
import argparse
import struct
import zipfile
import tempfile
import shutil
import subprocess
from pathlib import Path

REQUIRED_LIBRARIES = {'libmain.so', 'libSDL2.so', 'libopenal.so', 'libc++_shared.so'}


def check_apk(path, readelf=None, expected_ad_unit=None):
    with zipfile.ZipFile(path) as archive:
        names = archive.namelist()
        libraries = [name for name in names if name.startswith('lib/') and name.endswith('.so')]
        found = set()
        for name in libraries:
            parts = name.split('/')
            if len(parts) != 3 or parts[1] != 'arm64-v8a':
                raise ValueError(f'Unexpected APK ABI: {name}')
            with archive.open(name) as stream:
                header = stream.read(64)
            if len(header) != 64 or header[:6] != b'\x7fELF\x02\x01':
                raise ValueError(f'Expected a 64-bit little-endian ELF library: {name}')
            if struct.unpack_from('<H', header, 18)[0] != 183:
                raise ValueError(f'Library is not AArch64: {name}')
            found.add(parts[2])
        if not REQUIRED_LIBRARIES <= found:
            raise ValueError(f'Missing native runtime libraries: {REQUIRED_LIBRARIES - found}')
        for name in ('assets/resources.zip', 'assets/resources.manifest'):
            if archive.getinfo(name).file_size == 0:
                raise ValueError(f'Empty resource asset: {name}')
        if archive.getinfo('assets/resources.zip').compress_type != zipfile.ZIP_STORED:
            raise ValueError('Game resource ZIP must not be compressed again inside the APK')
        if not any(name.startswith('assets/licenses/vcpkg/') for name in names):
            raise ValueError('Native dependency license notices are missing')
        if 'assets/licenses/SDL2.txt' not in names:
            raise ValueError('SDL license notice is missing')
        if expected_ad_unit:
            if 'assets/licenses/Yandex-Mobile-Ads.txt' not in names:
                raise ValueError('Yandex license notice is missing')
            dex_names = [name for name in names if name.startswith('classes') and name.endswith('.dex')]
            unit_found = sdk_found = False
            for name in dex_names:
                data = archive.read(name)
                unit_found |= expected_ad_unit.encode('ascii') in data
                sdk_found |= b'Lcom/yandex/mobile/ads/' in data
            if not unit_found:
                raise ValueError('Expected publisher ad unit is missing from APK DEX')
            if not sdk_found:
                raise ValueError('Yandex SDK is missing from APK DEX')
        if readelf:
            prefix = 'Java_io_github_kalandos240_endlesssky_GameActivity_'
            required = {'SDL_main', *(prefix + name for name in
                         ('nativeSetAction', 'nativeResetTouch', 'nativeIsInFlight', 'nativeBack', 'nativeAdBreakToken'))}
            with tempfile.TemporaryDirectory() as temporary:
                main = Path(temporary) / 'libmain.so'
                with archive.open('lib/arm64-v8a/libmain.so') as source, main.open('wb') as output:
                    shutil.copyfileobj(source, output)
                table = subprocess.check_output([readelf, '--dyn-syms', '--wide', str(main)], text=True)
                exports = {line.split()[-1].split('@')[0] for line in table.splitlines()
                           if line.strip() and ' UND ' not in line}
                if not required <= exports:
                    raise ValueError(f'Missing SDL/JNI exports: {required - exports}')
            print('SDL entry point and all five Android JNI exports verified')
    print(f'APK validated: {len(libraries)} arm64 libraries, resource pack, dependency notices')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('apk')
    parser.add_argument('--readelf', help='NDK llvm-readelf for exported SDL/JNI symbol checks')
    parser.add_argument('--expected-ad-unit', help='Require this live unit, SDK classes and Yandex notice')
    arguments = parser.parse_args()
    check_apk(arguments.apk, arguments.readelf, arguments.expected_ad_unit)
