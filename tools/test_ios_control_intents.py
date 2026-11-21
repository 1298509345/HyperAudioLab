"""Exercise the production AppIntent action against a receiver boundary stub."""
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]


@unittest.skipUnless(sys.platform == 'darwin', 'requires Apple AppIntents SDK')
class ReceiverControlIntentTests(unittest.TestCase):
    def test_production_single_control_action(self):
        with tempfile.TemporaryDirectory(prefix='hyperaudio-control-intents-') as temp:
            binary = str(Path(temp) / 'ControlIntentTests')
            subprocess.run([
                'xcrun', 'swiftc', '-parse-as-library', '-D', 'HYPERAUDIO_APP',
                '-o', binary,
                str(ROOT / 'ios/HyperAudioReceiver/ReceiverIntents.swift'),
                str(ROOT / 'tools/ios-control-intents/ReceiverStub.swift'),
                str(ROOT / 'tools/ios-control-intents/Main.swift')
            ], check=True, timeout=90)
            output = subprocess.check_output([binary], text=True, timeout=15)
            self.assertIn('RECEIVER_CONTROL_INTENT_CHECKS_PASSED', output)


if __name__ == '__main__':
    unittest.main(verbosity=2)
