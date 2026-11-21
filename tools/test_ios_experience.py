"""Run production presentation, pairing metadata and report behavior on macOS."""
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]


@unittest.skipUnless(sys.platform == 'darwin', 'requires Swift on macOS')
class ReceiverExperienceTests(unittest.TestCase):
    def test_production_experience(self):
        source = ROOT / 'ios/HyperAudioReceiver/ReceiverPresentation.swift'
        self.assertTrue(source.exists(), '0.6 production pairing/report/state module is missing')
        with tempfile.TemporaryDirectory(prefix='hyperaudio-experience-') as temp:
            binary = str(Path(temp) / 'ExperienceTests')
            subprocess.run(['swiftc', '-o', binary, str(source),
                            str(ROOT / 'ios/HyperAudioReceiver/ControlServer.swift'),
                            str(ROOT / 'tools/ios-experience/main.swift')],
                           check=True, timeout=90)
            output = subprocess.check_output([binary], text=True, timeout=15)
            self.assertIn('RECEIVER_EXPERIENCE_CHECKS_PASSED', output)


if __name__ == '__main__':
    unittest.main(verbosity=2)
