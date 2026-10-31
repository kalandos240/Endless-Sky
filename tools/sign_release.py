#!/usr/bin/env python3
"""Sign an aligned APK offline and verify the pinned publisher certificate."""
import argparse
import hashlib
import os
from pathlib import Path
import re
import ssl
import subprocess
import tempfile


def sign(args):
    source = Path(args.apk).resolve(strict=True)
    output = Path(args.output).resolve()
    if output.exists():
        raise ValueError('Refusing to overwrite an existing release APK')
    for path in (args.apksigner, args.keystore, args.password_file, args.certificate):
        if not Path(path).is_file():
            raise ValueError('A required signing input is missing')
    certificate = ssl.PEM_cert_to_DER_cert(Path(args.certificate).read_text())
    expected = hashlib.sha256(certificate).hexdigest()
    output.parent.mkdir(parents=True, exist_ok=True)
    java = [args.java, '-jar', str(Path(args.apksigner).resolve())]
    with tempfile.TemporaryDirectory(prefix='apk-sign-', dir=output.parent) as directory:
        signed = Path(directory) / 'signed.apk'
        subprocess.run(java + ['sign', '--ks', str(Path(args.keystore).resolve()),
                              '--ks-type', 'PKCS12', '--ks-key-alias', args.alias,
                              '--ks-pass', 'file:' + str(Path(args.password_file).resolve()),
                              '--key-pass', 'file:' + str(Path(args.password_file).resolve()),
                              '--min-sdk-version', '26', '--v1-signing-enabled', 'false',
                              '--v4-signing-enabled', 'false', '--out', str(signed), str(source)],
                       check=True)
        result = subprocess.check_output(java + ['verify', '--verbose', '--print-certs', str(signed)], text=True)
        digests = re.findall(r'Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]+)', result)
        if [digest.lower() for digest in digests] != [expected]:
            raise ValueError('APK signer does not match the pinned publisher certificate')
        if 'Verified using v2 scheme (APK Signature Scheme v2): true' not in result:
            raise ValueError('APK Signature Scheme v2 was not verified')
        os.replace(signed, output)
        print(result)
    with output.open('rb') as stream:
        digest = hashlib.file_digest(stream, 'sha256').hexdigest()
    print(f'SHA256 {digest}  {output.name}')
    print(f'Certificate SHA256 {expected}')
    return digest


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('apk')
    parser.add_argument('output')
    parser.add_argument('--apksigner', required=True, help='Official Android Build Tools apksigner.jar')
    parser.add_argument('--keystore', required=True)
    parser.add_argument('--password-file', required=True)
    parser.add_argument('--certificate', default=str(Path(__file__).resolve().parents[1] / 'docs/signing/endless-sky-signing.crt'))
    parser.add_argument('--alias', default='endless-sky')
    parser.add_argument('--java', default='java')
    sign(parser.parse_args())
