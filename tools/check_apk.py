#!/usr/bin/env python3
"""Check that a development APK contains the complete arm64 native runtime."""
import argparse
import struct
import zipfile

REQUIRED_LIBRARIES = {'libmain.so', 'libSDL2.so', 'libopenal.so', 'libc++_shared.so'}


def check_apk(path):
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
    print(f'APK validated: {len(libraries)} arm64 libraries, resource pack, dependency notices')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('apk')
    check_apk(parser.parse_args().apk)
