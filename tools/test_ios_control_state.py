"""Verify production control state queries across independent OS processes."""
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
import subprocess
import select
import plistlib
import sys
import tempfile
import time
import unittest
import uuid

ROOT = Path(__file__).resolve().parents[1]


@unittest.skipUnless(sys.platform == 'darwin', 'requires Darwin Notify')
class ReceiverControlStateTests(unittest.TestCase):
    def test_live_state_and_process_exit(self):
        source = ROOT / 'ios/HyperAudioReceiver/ReceiverControlState.swift'
        self.assertTrue(source.exists(), 'live control state implementation is missing')
        with tempfile.TemporaryDirectory(prefix='hyperaudio-live-state-') as temp:
            directory = Path(temp)
            binary = directory / 'LiveStateProbe'
            subprocess.run(['xcrun', 'swiftc', '-parse-as-library', '-o', str(binary),
                            str(source), str(ROOT / 'tools/ios-control-state/Main.swift')],
                           check=True, timeout=90)
            host = directory / 'Renamed.app'
            extension = host / 'PlugIns' / 'RandomName.appex'
            extension.mkdir(parents=True)
            for bundle, identifier in [(host, 'com.owner.renamed'), (extension, 'com.unrelated.plugin.rewritten')]:
                (bundle / 'Info.plist').write_bytes(plistlib.dumps({'CFBundleIdentifier': identifier}))
            host_namespace = subprocess.check_output([str(binary), 'namespace', str(host)], text=True).strip()
            extension_namespace = subprocess.check_output([str(binary), 'namespace', str(extension)], text=True).strip()
            self.assertEqual(host_namespace, 'com.owner.renamed.control.state.v1')
            self.assertEqual(extension_namespace, host_namespace, 're-signed extension must use the actual host ID')
            namespace = 'lab.hyperaudio.control.test.' + uuid.uuid4().hex
            state = directory / 'state'
            ready = directory / 'ready'
            state.write_text('off')

            def read():
                return subprocess.check_output([str(binary), 'read', namespace],
                                               text=True, timeout=5).strip()

            self.assertEqual(read(), 'off')
            server = subprocess.Popen([str(binary), 'serve', namespace, str(state), str(ready)],
                                      stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
            def set_state(value):
                server.stdin.write(value + '\n')
                server.stdin.flush()
                self.assertTrue(select.select([server.stdout], [], [], 5)[0], 'publisher did not acknowledge')
                self.assertEqual(server.stdout.readline().strip(), 'ACK')
            try:
                deadline = time.monotonic() + 5
                while not ready.exists() and server.poll() is None and time.monotonic() < deadline:
                    time.sleep(0.02)
                self.assertTrue(ready.exists(), 'state responder failed to start')
                self.assertEqual(read(), 'off')
                set_state('on')
                self.assertEqual(read(), 'on')
                with ThreadPoolExecutor(max_workers=2) as pool:
                    self.assertEqual(list(pool.map(lambda _: read(), range(2))), ['on', 'on'])
                set_state('off')
                self.assertEqual(read(), 'off')
                set_state('on')
                self.assertEqual(read(), 'on')
            finally:
                server.kill()
                server.communicate(timeout=5)
            self.assertEqual(read(), 'off', 'terminated receiver must not leave a cached on state')


if __name__ == '__main__':
    unittest.main(verbosity=2)
