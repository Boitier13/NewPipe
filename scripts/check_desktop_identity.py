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

    def test_desktop_copy(self):
        for locale in ('values', 'values-fr'):
            strings = {e.attrib['name']: e.text for e in ET.parse(RES / locale / 'strings.xml').getroot() if e.tag == 'string'}
            self.assertIn('BoitierTube', strings['title_activity_about'])
            self.assertIn('BoitierTube', strings['app_description'])
            self.assertNotIn('Android', strings['app_description'])
        self.assertEqual(strings['auto_device_theme_title'], 'Système')

    def test_bundled_fonts_and_license(self):
        for name in ('regular', 'semibold'):
            data = (RES / f'font/source_sans_3_{name}.ttf').read_bytes()
            self.assertEqual(data[:4], b'\x00\x01\x00\x00')
            self.assertGreater(len(data), 10000)
        license_text = (ROOT / 'shared/src/jvmMain/resources/licenses/source-sans-3-OFL.txt').read_text()
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
        source = (ROOT / 'shared/src/commonMain/kotlin/net/newpipe/app/theme/Color.kt').read_text()
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
