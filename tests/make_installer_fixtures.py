"""Create Python-packaged resources to exercise the production Java installer."""
import hashlib
from pathlib import Path
import shutil
import sys
import zipfile

from test_resources import make_source, package_resources, ROOT


def main():
    root = ROOT / 'build' / 'installer-fixtures'
    if root.exists():
        shutil.rmtree(root)
    root.mkdir(parents=True)
    source = root / 'source'
    make_source(source)
    package_resources(source, root / 'good')
    (source / 'data' / 'sample.txt').write_bytes(b'another galaxy')
    package_resources(source, root / 'updated')
    for variant in ('corrupt', 'missing', 'extra', 'duplicate', 'oversized'):
        destination = root / variant
        destination.mkdir()
        shutil.copy(root / 'good' / 'resources.manifest', destination)
        with zipfile.ZipFile(root / 'good' / 'resources.zip') as original, zipfile.ZipFile(
                destination / 'resources.zip', 'w') as output:
            for name in original.namelist():
                if variant == 'missing' and name == 'data/sample.txt':
                    continue
                data = original.read(name)
                if name == 'data/sample.txt':
                    if variant == 'corrupt':
                        data = b'broken'
                    elif variant == 'oversized':
                        data += b'x'
                output.writestr(name, data)
            if variant == 'extra':
                output.writestr('../outside.txt', b'escape')
            if variant == 'duplicate':
                output.writestr('data/sample.txt', b'galaxy')
    traversal = root / 'traversal'
    traversal.mkdir()
    text = (root / 'good' / 'resources.manifest').read_text()
    header, body = text.split('\n', 1)
    body = body.replace('data/sample.txt', 'data/../../outside.txt')
    fields = header.split('\t')
    fields[1] = hashlib.sha256(body.encode()).hexdigest()
    (traversal / 'resources.manifest').write_text('\t'.join(fields) + '\n' + body)
    print(root)


if __name__ == '__main__':
    main()
