import hashlib
import json
from pathlib import Path
import sys
import tempfile
import unittest
import zipfile

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'tools'))
from prepare_android import package_resources, RESOURCE_DIRS, RESOURCE_FILES


def make_source(root, value=b'galaxy'):
    for name in RESOURCE_DIRS:
        directory = root / name
        directory.mkdir(parents=True)
        (directory / 'sample.txt').write_bytes(value)
    for name in RESOURCE_FILES:
        (root / name).write_bytes(b'license / credits')
    (root / 'data' / 'test unicode Земля.txt').write_bytes(b'planet')
    (root / 'keys.txt').write_bytes(b'')


class ResourcePackagingTests(unittest.TestCase):
    def test_stable_bytes_and_content_identity(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            make_source(root / 'source')
            first = package_resources(root / 'source', root / 'a')
            second = package_resources(root / 'source', root / 'b')
            self.assertEqual(first, second)
            for name in ('resources.zip', 'resources.manifest'):
                self.assertEqual((root / 'a' / name).read_bytes(), (root / 'b' / name).read_bytes())
            manifest = (root / 'a' / 'resources.manifest').read_text()
            header, body = manifest.split('\n', 1)
            self.assertEqual(header.split('\t')[1], hashlib.sha256(body.encode()).hexdigest())
            with zipfile.ZipFile(root / 'a' / 'resources.zip') as archive:
                for line in body.splitlines():
                    digest, size, path = line.split('\t')
                    data = archive.read(path)
                    self.assertEqual(len(data), int(size))
                    self.assertEqual(hashlib.sha256(data).hexdigest(), digest)
            (root / 'source' / 'data' / 'sample.txt').write_bytes(b'new galaxy')
            self.assertNotEqual(first, package_resources(root / 'source', root / 'c'))

    def test_symlink_rejected(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            make_source(root / 'source')
            (root / 'outside').write_bytes(b'private')
            (root / 'source' / 'data' / 'link').symlink_to(root / 'outside')
            with self.assertRaises(ValueError):
                package_resources(root / 'source', root / 'assets')

    def test_missing_resources_rejected(self):
        with tempfile.TemporaryDirectory() as temporary:
            with self.assertRaises(ValueError):
                package_resources(Path(temporary) / 'missing', Path(temporary) / 'assets')

    def test_sdl_java_matches_native_version_lock(self):
        specs = json.loads((ROOT / 'tools' / 'dependencies.json').read_text())
        manifest = json.loads((ROOT / 'android' / 'native' / 'vcpkg.json').read_text())
        self.assertEqual(specs['vcpkg']['revision'], manifest['builtin-baseline'])
        sdl_override = next(item for item in manifest['overrides'] if item['name'] == 'sdl2')
        self.assertEqual(specs['sdl2']['version'], sdl_override['version'])
        version_file = ROOT / 'build' / 'vcpkg' / 'ports' / 'sdl2' / 'vcpkg.json'
        if version_file.exists():
            self.assertEqual(specs['sdl2']['version'], json.loads(version_file.read_text())['version'])


if __name__ == '__main__':
    unittest.main()
