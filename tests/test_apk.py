"""Regression checks for the ABI mismatch found during the first native build."""
from pathlib import Path
import struct
import sys
import tempfile
import unittest
import zipfile

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'tools'))
from check_apk import check_apk, REQUIRED_LIBRARIES


def apk_fixture(path, wrong_machine=False, missing_sdl=False):
    with zipfile.ZipFile(path, 'w') as archive:
        for library in sorted(REQUIRED_LIBRARIES):
            header = bytearray(64)
            header[:6] = b'\x7fELF\x02\x01'
            struct.pack_into('<H', header, 18, 40 if wrong_machine and library == 'libmain.so' else 183)
            archive.writestr('lib/arm64-v8a/' + library, header)
        archive.writestr('assets/resources.zip', b'pack')
        archive.writestr('assets/resources.manifest', b'manifest')
        archive.writestr('assets/licenses/vcpkg/example/copyright', b'notice')
        if not missing_sdl:
            archive.writestr('assets/licenses/SDL2.txt', b'SDL notice')


class ApkChecks(unittest.TestCase):
    def test_arm64_package_with_runtime_and_notices(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / 'test.apk'
            apk_fixture(path)
            check_apk(path)

    def test_mislabeled_arm32_library_rejected(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / 'test.apk'
            apk_fixture(path, wrong_machine=True)
            with self.assertRaisesRegex(ValueError, 'AArch64'):
                check_apk(path)

    def test_missing_dependency_notice_rejected(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / 'test.apk'
            apk_fixture(path, missing_sdl=True)
            with self.assertRaisesRegex(ValueError, 'SDL license'):
                check_apk(path)


if __name__ == '__main__':
    unittest.main()
