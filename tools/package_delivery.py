#!/usr/bin/env python3
"""Package the organized project and verify every extracted file against SHA-256."""
from pathlib import Path
import hashlib
import json
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]
SKIP_DIRS = {'.git', '.gradle', '.kotlin', 'build', '__pycache__', 'xcuserdata', '.serena', '.superpowers'}
SKIP_FILES = {'.DS_Store', 'local.properties', 'MANIFEST.sha256'}
KEY_SUFFIXES = {'.p12', '.p8', '.key', '.jks', '.keystore'}

def sha256(path):
    result = hashlib.sha256()
    with path.open('rb') as f:
        for data in iter(lambda: f.read(1024 * 1024), b''):
            result.update(data)
    return result.hexdigest()

def entries():
    selected = []
    for path in ROOT.rglob('*'):
        parts = path.relative_to(ROOT).parts
        if any(part in SKIP_DIRS or part.startswith('DerivedData') for part in parts):
            continue
        if path.name in SKIP_FILES or path.suffix.lower() in KEY_SUFFIXES:
            continue
        if path.is_symlink():
            raise RuntimeError('Unexpected symbolic link in delivery: ' + str(path))
        if path.is_file() and path.suffix.lower() != '.zip':
            selected.append(path)
    return sorted(selected)

def main():
    required = [ROOT/'安装操作手册.html', ROOT/'releases/android/HyperAudioLab-0.7.apk', ROOT/'releases/ios/HyperAudioReceiver-0.6.5-signed.ipa']
    for path in required:
        if not path.is_file():
            raise RuntimeError('Missing required delivery file: ' + str(path))
    files = entries()
    checks = {path.relative_to(ROOT).as_posix(): sha256(path) for path in files}
    manifest = ROOT/'MANIFEST.sha256'
    manifest.write_text(''.join(digest + '  ' + name + '\n' for name, digest in checks.items()))
    output = ROOT.parent/'HyperAudioLab-0.7-完整交付.zip'
    if output.exists():
        raise RuntimeError('Refusing to replace an existing delivery ZIP: ' + str(output))
    with zipfile.ZipFile(output, 'w', zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        for path in files + [manifest]:
            archive.write(path, ROOT.name + '/' + path.relative_to(ROOT).as_posix())
    with zipfile.ZipFile(output) as archive:
        if archive.testzip() is not None:
            raise RuntimeError('ZIP CRC verification failed')
        with tempfile.TemporaryDirectory(prefix='hyperaudio-delivery-verify-') as directory:
            archive.extractall(directory)
            extracted = Path(directory)/ROOT.name
            for name, digest in checks.items():
                if sha256(extracted/name) != digest:
                    raise RuntimeError('Extracted content mismatch: ' + name)
            if (extracted/'MANIFEST.sha256').read_bytes() != manifest.read_bytes():
                raise RuntimeError('Extracted manifest mismatch')
    result = dict(zip=str(output), bytes=output.stat().st_size, sha256=sha256(output), content_files=len(files), zip_crc_verified=True, extracted_files_verified=len(checks), excluded=['rebuildable build/DerivedData caches', 'IDE user state', 'local.properties', 'signing private keys', 'other ZIP files'])
    report = output.with_suffix('.json')
    report.write_text(json.dumps(result, indent=2, ensure_ascii=False)+'\n')
    print(json.dumps(result, indent=2, ensure_ascii=False))

if __name__ == '__main__':
    main()
