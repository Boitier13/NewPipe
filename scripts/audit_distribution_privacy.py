#!/usr/bin/env python3
"""Read-only privacy gate for generated distributions, extracted MSI payloads and nested JARs.

Reject recognizable local profiles, databases, logs, cookies and downloaded media. This is a
structural gate, not a claim that arbitrary secrets can be detected by their content.
"""
import argparse
import io
import os
from pathlib import Path, PurePosixPath
import sys
import zipfile

PRIVATE_NAMES = {
    'preferences.checked-json', '.portable-profile.lock', 'newpipe.json', 'settings.json',
    'cookies.txt', 'cookies.json', 'history.json', 'subscriptions.json', 'subscriptions.csv',
}
PRIVATE_SUFFIXES = {'.db', '.sqlite', '.sqlite3', '.log', '.mp4', '.mp3', '.m4a', '.webm', '.flac', '.wav', '.ogg'}


def check_name(name):
    path = PurePosixPath(name.replace('\\', '/').lower())
    leaf = path.name
    if (leaf in PRIVATE_NAMES or path.suffix in PRIVATE_SUFFIXES or
            leaf.endswith(('.db-wal', '.db-shm', '.db-journal', '.log.old', '.checked-json')) or
            any(part in {'newpipedesktop', 'tmp_downloads', '.backup-recovery', 'profiles'} for part in path.parts) or
            'data' in path.parts):
        raise ValueError(f'Possible personal data in distributable: {name}')


def audit_zip(source, label, depth=0):
    if depth > 4:
        raise ValueError(f'Excessive nested archive depth: {label}')
    count = 0
    with zipfile.ZipFile(source) as archive:
        for item in archive.infolist():
            if item.is_dir():
                continue
            check_name(item.filename)
            count += 1
            if item.filename.lower().endswith(('.jar', '.zip')):
                if item.file_size > 512 * 1024 * 1024:
                    raise ValueError(f'Nested archive too large for inspection: {label}/{item.filename}')
                count += audit_zip(io.BytesIO(archive.read(item)), f'{label}/{item.filename}', depth + 1)
    return count


def audit(path):
    if path.is_dir():
        count = 0
        for file in path.rglob('*'):
            if not file.is_file():
                continue
            name = file.relative_to(path).as_posix()
            check_name(name)
            count += 1
            if file.suffix.lower() in {'.jar', '.zip'}:
                count += audit_zip(file, name)
        return count
    check_name(path.name)
    return audit_zip(path, path.name)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('paths', type=Path, nargs='+')
    args = parser.parse_args()
    try:
        for path in args.paths:
            if not path.exists():
                raise ValueError(f'Missing input: {path}')
            count = audit(path)
            print(f'Privacy structure audit PASS: {path} ({count} files including nested archives)')
    except (ValueError, zipfile.BadZipFile) as error:
        print(error, file=sys.stderr)
        if os.environ.get('GITHUB_ACTIONS') == 'true':
            detail = str(error).replace('%', '%25').replace('\r', '%0D').replace('\n', '%0A')
            print(f'::error::{detail}')
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
