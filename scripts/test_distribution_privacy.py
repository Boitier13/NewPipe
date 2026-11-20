#!/usr/bin/env python3
"""Synthetic tests for the read-only distribution privacy gate (no user files)."""
import io
from pathlib import Path
import tempfile
import unittest
import zipfile
from audit_distribution_privacy import audit


class DistributionPrivacyTests(unittest.TestCase):
    def test_clean_runtime_licenses_and_configuration_are_allowed(self):
        with tempfile.TemporaryDirectory() as root:
            path = Path(root) / 'portable.zip'
            with zipfile.ZipFile(path, 'w') as archive:
                archive.writestr('BoitierTube/app/BoitierTube.cfg', '[JavaOptions]')
                archive.writestr('BoitierTube/app/resources/vlc/COPYING.txt', 'GPL')
                archive.writestr('BoitierTube/GUIDE.md', 'Guide')
                archive.writestr('androidx/compose/foundation/text/contextmenu/data/TextContextMenuComponent.class', b'compiled code')
            self.assertEqual(4, audit(path))

    def test_profiles_databases_cookies_media_and_logs_are_rejected(self):
        for name in ['data/profiles/account/newpipe.db', 'app/settings.json', 'app/private.sqlite',
                     'app/history.json', 'app/cookies.txt', 'app/download_mux.log', 'app/movie.mp4',
                     'app/preferences.checked-json', 'app/newpipe.db-wal']:
            with self.subTest(name=name), tempfile.TemporaryDirectory() as root:
                path = Path(root) / 'portable.zip'
                with zipfile.ZipFile(path, 'w') as archive:
                    archive.writestr(name, 'TEST PRIVATE CANARY')
                with self.assertRaises(ValueError):
                    audit(path)

    def test_nested_jar_resources_are_inspected(self):
        inner = io.BytesIO()
        with zipfile.ZipFile(inner, 'w') as archive:
            archive.writestr('local/newpipe.db', 'TEST PRIVATE CANARY')
        with tempfile.TemporaryDirectory() as root:
            path = Path(root) / 'portable.zip'
            with zipfile.ZipFile(path, 'w') as archive:
                archive.writestr('BoitierTube/app/shared.jar', inner.getvalue())
            with self.assertRaises(ValueError):
                audit(path)

    def test_unpacked_installer_payload_is_inspected(self):
        with tempfile.TemporaryDirectory() as root:
            (Path(root) / 'newpipe.db').write_text('TEST PRIVATE CANARY')
            with self.assertRaises(ValueError):
                audit(Path(root))


if __name__ == '__main__':
    unittest.main(verbosity=2)
