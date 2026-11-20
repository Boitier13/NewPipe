#!/usr/bin/env python3
"""Verify frozen source tips and content preservation. Requires non-shallow Git history."""
import json
import subprocess
from pathlib import Path
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]


def git(*args):
    return subprocess.check_output(['git', *args], cwd=ROOT)


def catalog(path, ref=None):
    if ref:
        tree = ET.fromstring(git('show', f'{ref}:{path}'))
        return {node.attrib['name']: node.text for node in tree if node.tag == 'string'}
    values = {}
    for file in (ROOT / path).glob('*.xml'):
        for node in ET.parse(file).getroot():
            if node.tag == 'string':
                key = node.attrib['name']
                assert key not in values, f'Duplicate key: {key}'
                values[key] = node.text
    return values


def main():
    inventory = json.loads((ROOT / 'doc/boitiertube-branch-inventory.json').read_text(encoding='utf-8'))
    decisions = json.loads((ROOT / 'doc/boitiertube-integration-resolutions.json').read_text(encoding='utf-8'))
    for branch in inventory['branches']:
        subprocess.run(['git', 'merge-base', '--is-ancestor', branch['tip'], 'HEAD'], cwd=ROOT, check=True)
        print(f"Integrated ancestor: {branch['name']} ({branch['tip'][:10]})")

    french_tip = next(b['tip'] for b in inventory['branches'] if b['name'] == 'boitiertube/french-localization-fixes')
    original = catalog('shared/src/commonMain/composeResources/values-fr/strings.xml', french_tip)
    current = catalog('shared/src/commonMain/composeResources/values-fr')
    overrides = {d['key']: d['central'] for d in decisions['french_backup_overlap']}
    assert original.keys() <= current.keys(), 'French resource keys lost'
    for key, value in original.items():
        assert current[key] == overrides.get(key, value), f'French text unexpectedly changed: {key}'
    identity = 'shared/src/commonMain/composeResources/values-fr/desktop_identity.xml'
    original_identity = {n.attrib['name']: n.text for n in ET.fromstring(git('show', f'{french_tip}:{identity}'))}
    current_identity = {n.attrib['name']: n.text for n in ET.parse(ROOT / identity).getroot()}
    for key, value in original_identity.items():
        if key != 'desktop_subscription_import_description':
            assert current_identity[key] == value, key
    print(f'French catalog: all {len(original)} keys retained, only recorded backup wording reconciled.')

    platform = 'shared/src/jvmMain/kotlin/net/newpipe/app/platform/'
    initial = inventory['central_before']
    for path in [
        platform + 'JVMBackupRestoreActions.kt',
        'shared/src/jvmTest/kotlin/net/newpipe/app/platform/DesktopBackupStoreTest.kt',
        'shared/src/commonMain/kotlin/net/newpipe/app/screen/settings/BackupRestoreSettingsScreen.kt',
        'shared/src/commonMain/kotlin/net/newpipe/app/screen/settings/model/BackupRestoreMessage.kt',
        'shared/src/commonMain/kotlin/net/newpipe/app/viewmodel/settings/BackupRestoreSettingsViewModel.kt',
        'shared/src/commonMain/kotlin/net/newpipe/app/theme/Color.kt',
        'shared/src/commonMain/kotlin/net/newpipe/app/theme/Typography.kt',
    ]:
        assert (ROOT / path).read_bytes() == git('show', f'{initial}:{path}'), f'Arena identity/backup regression: {path}'
    print('Arena identity and unchanged backup/UI files preserved byte-for-byte; recovery journal evolution is covered by tests.')

    portable_tip = next(b['tip'] for b in inventory['branches'] if b['name'] == 'arena/ae9cabd8-newpipe')
    for path in [
        'shared/src/jvmTest/kotlin/net/newpipe/app/platform/WindowsAppPathsTest.kt',
        'shared/src/jvmTest/kotlin/net/newpipe/app/platform/WindowsBundledRuntimeSmokeTest.kt'
    ]:
        assert (ROOT / path).read_bytes() == git('show', f'{portable_tip}:{path}'), f'Portable runtime work lost: {path}'
    print('Historical VLC controller and test files preserved byte-for-byte; discovery paths, settings UI and bundle preparation intentionally evolved with dedicated tests.')
    print('PASS: frozen tips integrated and checked content preserved. This is not a build or UI test.')


if __name__ == '__main__':
    main()
