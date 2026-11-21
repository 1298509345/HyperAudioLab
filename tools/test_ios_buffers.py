from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]


@unittest.skipUnless(sys.platform == 'darwin', 'requires AVFoundation')
class AudioBufferTests(unittest.TestCase):
    def test_production_pool_ownership(self):
        source = ROOT / 'ios/HyperAudioReceiver/AudioBufferPool.swift'
        self.assertTrue(source.exists(), 'bounded production audio buffer pool is missing')
        with tempfile.TemporaryDirectory(prefix='hyperaudio-buffers-') as temp:
            binary = str(Path(temp) / 'Buffers')
            subprocess.run(['swiftc', '-o', binary, str(source),
                            str(ROOT / 'tools/ios-buffers/main.swift')], check=True, timeout=90)
            output = subprocess.check_output([binary], text=True, timeout=10)
            self.assertIn('BUFFER_OWNERSHIP_CHECKS_PASSED', output)


if __name__ == '__main__':
    unittest.main(verbosity=2)
