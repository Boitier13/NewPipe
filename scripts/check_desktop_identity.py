#!/usr/bin/env python3
"""Offline resource contract checks. Not a substitute for a Windows build/UI test."""
from pathlib import Path
import re
import struct
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / 'shared/src/commonMain/composeResources'


class DesktopIdentityTests(unittest.TestCase):
    def test_xml_resources_parse(self):
        for path in RES.rglob('*.xml'):
            with self.subTest(path=path.relative_to(RES)):
                ET.parse(path)

    def test_resource_keys_are_unique_per_locale(self):
        for folder in RES.glob('values*'):
            keys = set()
            for path in folder.glob('*.xml'):
                for node in ET.parse(path).getroot():
                    key = (node.tag, node.attrib.get('name'))
                    self.assertNotIn(key, keys, f'{folder}: {key}')
                    keys.add(key)

    def test_appearance_screen_imports_compose_delegate_operator(self):
        path = ROOT / 'shared/src/commonMain/kotlin/net/newpipe/app/screen/settings/AppearanceSettingsScreen.kt'
        self.assertIn('import androidx.compose.runtime.getValue', path.read_text(encoding='utf-8'))

    def test_desktop_copy(self):
        for locale in ('values', 'values-fr'):
            strings = {e.attrib['name']: e.text for e in ET.parse(RES / locale / 'strings.xml').getroot() if e.tag == 'string'}
            self.assertIn('BoitierTube', strings['title_activity_about'])
            self.assertIn('BoitierTube', strings['app_description'])
            self.assertNotIn('Android', strings['app_description'])
        self.assertEqual(strings['auto_device_theme_title'], 'Système')

    def test_desktop_localization_falls_back_without_android_branding(self):
        # These new keys intentionally have no inherited Android translations.
        names = set()
        for locale in ('values', 'values-fr'):
            strings = {e.attrib['name']: e.text for e in ET.parse(RES / locale / 'desktop_identity.xml').getroot()}
            names.update(strings)
            for key in ('desktop_about_title', 'desktop_about_description', 'desktop_license_title', 'desktop_license_description'):
                self.assertIn('BoitierTube', strings[key])
                self.assertNotIn('Android', strings[key])
            self.assertNotIn('NewPipe', strings['desktop_about_title'])
            self.assertNotIn('NewPipe', strings['desktop_about_description'])
        for path in RES.glob('values*/*.xml'):
            if path.parent.name not in ('values', 'values-fr'):
                self.assertFalse(names.intersection(e.attrib.get('name') for e in ET.parse(path).getroot()), path)

    def test_desktop_screens_use_desktop_copy(self):
        kotlin = ROOT / 'shared/src/commonMain/kotlin/net/newpipe/app'
        required = {
            'screen/about/AboutPage.kt': ('desktop_about_description', 'desktop_upstream_description'),
            'screen/about/AboutScreen.kt': ('desktop_about_title',),
            'screen/about/LicensePage.kt': ('desktop_license_title', 'desktop_license_description'),
            'screen/desktop/SubscriptionsScreen.kt': ('desktop_subscription_import_description',),
            'screen/settings/HistorySettingsScreen.kt': ('desktop_clear_cookie_summary',)
        }
        for file, keys in required.items():
            source = (kotlin / file).read_text(encoding='utf-8')
            for key in keys:
                self.assertIn('Res.string.' + key, source, file)
        source = (kotlin / 'screen/about/LicensePage.kt').read_text(encoding='utf-8')
        self.assertIn('spdxID = "OFL-1.1"', source)
        self.assertEqual(
            (RES / 'files/LICENSES/OFL-1.1.txt').read_bytes(),
            (ROOT / 'shared/src/jvmMain/resources/licenses/source-sans-3-OFL.txt').read_bytes()
        )

    def test_no_stale_desktop_icon_references(self):
        app = ROOT / 'desktopApp'
        gradle = (app / 'build.gradle.kts').read_text(encoding='utf-8')
        main = (app / 'src/main/kotlin/net/newpipe/app/Main.kt').read_text(encoding='utf-8')
        self.assertIn('packageName = "BoitierTube"', gradle)
        self.assertIn('menuGroup = "BoitierTube"', gradle)
        self.assertIn('resources/boitiertube.ico', gradle)
        self.assertIn('"/boitiertube.png"', main)
        self.assertIn('title = windowTitle', main)
        self.assertIn('"BoitierTube — mode portable" else "BoitierTube"', main)
        for old in ('newpipe.png', 'newpipe.ico'):
            self.assertNotIn(old, gradle + main)
            self.assertFalse((app / 'src/main/resources' / old).exists())

    def test_no_red_or_brown_accent_literals(self):
        theme = ROOT / 'shared/src/commonMain/kotlin/net/newpipe/app/theme'
        # Old brand roles and service colors, not semantic scrims or video blacks.
        old_colors = ('CD201F', '904A45', 'FFDAD6', 'E53935', '992722', 'F57C00', 'A35300', 'FF6F00', 'A34700')
        source = ''.join(p.read_text(encoding='utf-8').upper() for p in theme.glob('*.kt'))
        for color in old_colors:
            self.assertNotIn('0XFF' + color, source)
        service = (theme / 'ServiceTheme.kt').read_text(encoding='utf-8')
        self.assertNotIn('lightColorScheme(', service)
        self.assertNotIn('darkColorScheme(', service)
        for name in ('youTube', 'soundCloud', 'mediaCCC', 'peerTube', 'bandCamp'):
            self.assertIn(f'val {name}LightScheme = lightScheme', service)
            self.assertIn(f'val {name}DarkScheme = darkScheme', service)

    def test_live_badge_uses_contrasting_blue_roles(self):
        source = (ROOT / 'shared/src/commonMain/kotlin/net/newpipe/app/screen/desktop/DesktopCommonCards.kt').read_text(encoding='utf-8')
        self.assertIn('if (stream.isLive) MaterialTheme.colorScheme.primary else Color.Black', source)
        self.assertIn('if (stream.isLive) MaterialTheme.colorScheme.onPrimary else Color.White', source)

    def test_generated_string_references_have_defaults(self):
        defaults = {
            element.attrib['name']
            for path in (RES / 'values').glob('*.xml')
            for element in ET.parse(path).getroot()
            if element.tag == 'string'
        }
        for path in (ROOT / 'shared/src').rglob('*.kt'):
            used = set(re.findall(r'Res\.string\.(\w+)', path.read_text(encoding='utf-8')))
            self.assertFalse(used - defaults, f'{path}: missing {used - defaults}')

    def test_bundled_fonts_and_license(self):
        for name in ('regular', 'semibold'):
            data = (RES / f'font/source_sans_3_{name}.ttf').read_bytes()
            self.assertEqual(data[:4], b'\x00\x01\x00\x00')
            self.assertGreater(len(data), 10000)
        license_text = (ROOT / 'shared/src/jvmMain/resources/licenses/source-sans-3-OFL.txt').read_text(encoding='utf-8')
        self.assertIn('SIL OPEN FONT LICENSE Version 1.1', license_text)

    def test_windows_icon_frames_and_png(self):
        resources = ROOT / 'desktopApp/src/main/resources'
        ico = (resources / 'boitiertube.ico').read_bytes()
        reserved, kind, count = struct.unpack_from('<HHH', ico)
        self.assertEqual((reserved, kind), (0, 1))
        sizes = {ico[6 + i * 16] or 256 for i in range(count)}
        self.assertTrue({16, 24, 32, 48, 256}.issubset(sizes))
        for i in range(count):
            length, offset = struct.unpack_from('<II', ico, 6 + i * 16 + 8)
            self.assertLessEqual(offset + length, len(ico))
        png = (resources / 'boitiertube.png').read_bytes()
        self.assertEqual(png[:8], b'\x89PNG\r\n\x1a\n')
        self.assertEqual(struct.unpack_from('>II', png, 16), (512, 512))
        self.assertEqual(png, (RES / 'drawable/ic_newpipe_logo.png').read_bytes())

    def test_palette_contrast(self):
        source = (ROOT / 'shared/src/commonMain/kotlin/net/newpipe/app/theme/Color.kt').read_text(encoding='utf-8')
        colors = dict(re.findall(r'val (\w+) = Color\(0xFF([0-9A-F]{6})\)', source))

        def luminance(hex_color):
            rgb = [int(hex_color[i:i + 2], 16) / 255 for i in (0, 2, 4)]
            linear = [v / 12.92 if v <= .04045 else ((v + .055) / 1.055) ** 2.4 for v in rgb]
            return sum(v * w for v, w in zip(linear, (.2126, .7152, .0722)))

        for mode in ('Light', 'Dark'):
            for role in ('primary', 'primaryContainer', 'secondary', 'secondaryContainer', 'tertiary', 'tertiaryContainer', 'background', 'surface', 'surfaceVariant', 'error', 'errorContainer'):
                fg = 'on' + role[0].upper() + role[1:] + mode
                a, b = sorted([luminance(colors[fg]), luminance(colors[role + mode])])
                with self.subTest(mode=mode, role=role):
                    self.assertGreaterEqual((b + .05) / (a + .05), 4.5)
        self.assertEqual(colors['backgroundLight'], 'FFFFFF')
        self.assertNotEqual(colors['backgroundDark'], '000000')


if __name__ == '__main__':
    unittest.main(verbosity=2)
