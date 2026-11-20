#!/usr/bin/env python3
"""Offline audit contracts. These checks are not a security scanner or Windows execution."""
from pathlib import Path
import re
import json
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]


class FinalAuditTests(unittest.TestCase):
    def text(self, path):
        return (ROOT / path).read_text()

    def test_french_resource_coverage_and_placeholders(self):
        base = ROOT / 'shared/src/commonMain/composeResources'
        def catalog(locale):
            return {node.attrib['name']: node.text or ''
                    for file in (base / locale).glob('*.xml')
                    for node in ET.parse(file).getroot() if node.tag == 'string'}
        default, french = catalog('values'), catalog('values-fr')
        self.assertFalse(default.keys() - french.keys())
        # Desktop file-picker text deliberately drops an obsolete Android preference parameter.
        exceptions = {'no_appropriate_file_manager_message'}
        for key in default.keys() & french.keys() - exceptions:
            with self.subTest(key=key):
                pattern = r'%(?:\d+\$)?[dsf]'
                self.assertEqual(sorted(re.findall(pattern, default[key])), sorted(re.findall(pattern, french[key])))

    def test_bundle_paths_checked_before_any_deletion_or_download(self):
        source = self.text('.github/scripts/prepare-windows-bundle.ps1')
        for guard in ['OutputDir must be the generated directory', 'ReparsePoint', 'if ($ValidatePathsOnly)']:
            self.assertLess(source.index(guard), source.index('Invoke-WebRequest -Uri $spec.url'))
        self.assertNotIn('Remove-Item', source)
        self.assertLess(source.index('Assert-RuntimeArchive -Path $archive'), source.index('& $sevenZip t'))
        self.assertIn('Join-Path $repositoryRoot $OutputDir', source)
        self.assertIn('[guid]::NewGuid()', source)

    def test_read_only_path_test_covers_rejected_and_accepted_paths(self):
        source = self.text('scripts/test-windows-bundle-paths.ps1')
        self.assertIn('-ValidatePathsOnly', source)
        self.assertIn('$LASTEXITCODE -eq 0', source)
        self.assertIn('$LASTEXITCODE -ne 0', source)
        self.assertNotIn('Remove-Item', source)

    def test_ci_publishes_distribution_and_test_reports(self):
        source = self.text('.github/workflows/ci.yml')
        for token in ['boitiertube-windows-portable', 'Compress-Archive', 'boitiertube-test-reports',
                      'if: always()', 'test-windows-bundle-paths.ps1', ':desktopApp:packageExe',
                      ':desktopApp:packageMsi', 'WindowsBundledRuntimeSmokeTest']:
            self.assertIn(token, source)

    def test_pinned_runtime_provenance_is_versioned_and_checked(self):
        manifest = json.loads(self.text('.github/windows-runtimes.json'))
        for name, item in manifest.items():
            self.assertRegex(item['sha256'], r'^[a-f0-9]{64}$')
            self.assertIn(item['version'], item['url'])
            self.assertNotIn('/latest', item['url'])
            self.assertTrue(item['url'].startswith('https://'))
            self.assertIn('provenance', item)
        script = self.text('.github/scripts/prepare-windows-bundle.ps1')
        self.assertLess(script.index('Assert-RuntimeListing -Listing'), script.index('& $sevenZip x'))
        self.assertIn("'COPYING.txt'", script)

    def test_recovery_journal_precedes_substitution_and_blocks_initial_open(self):
        script = self.text('shared/src/jvmMain/kotlin/net/newpipe/app/platform/DesktopBackupStore.kt')
        self.assertLess(script.index('writeRecoveryState(work, "pending")'), script.index('closeDatabase()'))
        self.assertIn('state != "completed"', script)
        gateway = self.text('shared/src/jvmMain/kotlin/net/newpipe/app/platform/JVMDesktopDatabase.kt')
        self.assertLess(gateway.index('assertNoPendingRecovery(dbFile)'), gateway.index('driver.open(dbFile.absolutePath)'))

    def test_portable_mode_is_explicit_without_installed_profile_migration(self):
        source = self.text('shared/src/jvmMain/kotlin/net/newpipe/app/platform/PortableProfile.kt')
        for token in ['BOITIERTUBE_PORTABLE_ROOT', 'ATOMIC_MOVE', 'SHA-256', 'tryLock()', 'startsWith(data.canonicalFile.toPath())']:
            self.assertIn(token, source)
        self.assertNotIn('Preferences.userRoot()', source)
        self.assertIn('setlocal', self.text('scripts/Start-BoitierTube-Portable.cmd'))

    def test_runtime_settings_use_resources_instead_of_english_literals(self):
        source = self.text('shared/src/jvmMain/kotlin/net/newpipe/app/platform/JVMPlayerSettingsSections.kt')
        self.assertNotRegex(source, r'(?:title|summary) = "')
        self.assertNotIn('VLC detected', source)
        self.assertIn('stringResource', source)

    def test_backup_bounds_and_schema_precede_live_changes(self):
        source = self.text('shared/src/jvmMain/kotlin/net/newpipe/app/platform/DesktopBackupStore.kt')
        for token in ['CRC32', 'decodeToString(throwOnInvalidSequence = true)', 'rejectDuplicateKeys',
                      '4096', '8192', 'trusted_schema = OFF', 'integrity_check', 'foreign_key_check',
                      'previous-settings.json', 'preserveRecovery', 'rollback-copy']:
            self.assertIn(token, source)
        self.assertLess(source.index('prepareDatabase(candidate)'), source.index('closeDatabase()'))
        self.assertIn('NonCancellable', self.text('shared/src/jvmMain/kotlin/net/newpipe/app/platform/JVMDesktopDatabase.kt'))


if __name__ == '__main__':
    unittest.main(verbosity=2)
