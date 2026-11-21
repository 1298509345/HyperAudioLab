"""Run production receiver state/diagnosis logic without audio hardware."""
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]


@unittest.skipUnless(sys.platform == "darwin", "requires Swift on macOS")
class ReceiverPolicyTests(unittest.TestCase):
    def test_production_policy_and_diagnostics(self):
        with tempfile.TemporaryDirectory(prefix="hyperaudio-policy-") as temp:
            binary = str(Path(temp) / "ReceiverPolicyTests")
            subprocess.run(["swiftc", "-o", binary,
                            str(ROOT / "ios/HyperAudioReceiver/ReceiverPolicy.swift"),
                            str(ROOT / "ios/HyperAudioReceiver/AudioExperience.swift"),
                            str(ROOT / "tools/ios-policy/main.swift")], check=True, timeout=90)
            output = subprocess.check_output([binary], text=True, timeout=10)
            self.assertIn("RECEIVER_POLICY_CHECKS_PASSED", output)


if __name__ == "__main__":
    unittest.main(verbosity=2)
