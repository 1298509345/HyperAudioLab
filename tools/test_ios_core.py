"""Compile actual Swift Packet/ControlServer and probe it on macOS (not iOS audio).
Run explicitly: python3 tools/test_ios_core.py
Requires Command Line Tools or Xcode; TCP 50006 must be free.
"""
import json
import os
from pathlib import Path
import socket
import subprocess
import sys
import tempfile
import time
import unittest
import uuid

from control_probe import proof, read_line, send

ROOT = Path(__file__).resolve().parents[1]


class SwiftCoreTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        if sys.platform != "darwin":
            raise unittest.SkipTest("Actual Swift core checks require macOS")
        cls.temp = tempfile.TemporaryDirectory(prefix="hyperaudio-ios-core-")
        cls.addClassCleanup(cls.temp.cleanup)
        binary = Path(cls.temp.name) / ("HyperAudioCoreSmoke" + uuid.uuid4().hex)
        subprocess.run([
            "swiftc", "-swift-version", "5", "-o", str(binary),
            str(ROOT / "ios/HyperAudioReceiver/Packet.swift"),
            str(ROOT / "ios/HyperAudioReceiver/ControlServer.swift"),
            str(ROOT / "ios/HyperAudioReceiver/ReceiverPresentation.swift"),
            str(ROOT / "tools/ios-core/main.swift"),
        ], check=True, timeout=180)
        # Do not accidentally probe another app's listener.
        with socket.socket() as check:
            check.bind(("0.0.0.0", 50006))
        cls.name = "smoke-" + uuid.uuid4().hex[:16]
        cls.log = open(Path(cls.temp.name) / "server.log", "w+")
        cls.addClassCleanup(cls.log.close)
        cls.server = subprocess.Popen([str(binary)], stdout=cls.log, stderr=subprocess.STDOUT,
                                     env={**os.environ, "HYPERAUDIO_TEST_NAME": cls.name})
        cls.addClassCleanup(cls.stop_server)
        for _ in range(100):
            if cls.server.poll() is not None:
                cls.log.seek(0)
                raise RuntimeError(cls.log.read())
            try:
                with socket.create_connection(("127.0.0.1", 50006), timeout=.1):
                    break
            except OSError:
                time.sleep(.05)
        else:
            raise RuntimeError("Swift control listener did not start")

    @classmethod
    def stop_server(cls):
        cls.server.terminate()
        try:
            cls.server.wait(timeout=5)
        except subprocess.TimeoutExpired:
            cls.server.kill()
            cls.server.wait()
        cls.log.seek(0)
        print(cls.log.read(), end="")

    def connect(self):
        s = socket.create_connection(("127.0.0.1", 50006), timeout=2)
        s.settimeout(2)
        self.addCleanup(s.close)
        stream = s.makefile("rwb")
        self.addCleanup(stream.close)
        return stream

    def hello(self, stream, client, name=None):
        send(stream, dict(type="hello", v=1, clientId=client, name=name or self.name))
        challenge = read_line(stream)
        self.assertEqual(challenge["type"], "challenge")
        return challenge

    def test_pair_resume_and_rotate_token(self):
        client = str(uuid.uuid4())
        first = self.connect()
        self.hello(first, client)
        send(first, dict(type="pair"))
        paired = read_line(first)
        self.assertEqual(paired["type"], "ready")
        self.assertEqual(len(bytes.fromhex(paired["secret"])), 32)
        self.assertEqual(paired["port"], 50005)
        self.assertEqual(paired["audioFormats"], ["HAP1", "HAP2"])
        first.close()
        second = self.connect()
        c = self.hello(second, client)
        send(second, dict(type="resume", proof=proof(paired["secret"], client, c["receiverId"], c["nonce"])))
        resumed = read_line(second)
        self.assertEqual(resumed["type"], "ready")
        self.assertNotIn("secret", resumed)
        self.assertNotEqual(resumed["token"], paired["token"])
        second.close()
        third = self.connect()
        self.hello(third, client)
        send(third, dict(type="resume", proof="0" * 64))
        self.assertEqual(read_line(third)["reason"], "pair_required")

    def test_denied_pair(self):
        stream = self.connect()
        self.hello(stream, str(uuid.uuid4()), name="unapproved")
        send(stream, dict(type="pair"))
        self.assertEqual(read_line(stream), dict(type="error", reason="denied"))

    def test_inspect_is_authenticated_and_does_not_rotate_session(self):
        client = str(uuid.uuid4())
        first = self.connect()
        self.hello(first, client)
        send(first, dict(type="pair"))
        paired = read_line(first)
        first.close()
        inspection = self.connect()
        challenge = self.hello(inspection, client)
        send(inspection, dict(type="inspect", proof=proof(paired["secret"], client,
             challenge["receiverId"], challenge["nonce"])))
        status = read_line(inspection)
        self.assertEqual(status["type"], "status")
        self.assertEqual(status["receiverId"], challenge["receiverId"])
        self.assertEqual(status["received"], 42)
        self.assertTrue(status["bluetooth"])
        self.assertNotIn("token", status)
        self.assertNotIn("secret", status)
        inspection.close()
        resumed = self.connect()
        challenge = self.hello(resumed, client)
        send(resumed, dict(type="resume", proof=proof(paired["secret"], client,
             challenge["receiverId"], challenge["nonce"])))
        self.assertEqual(int(read_line(resumed)["token"], 16), int(paired["token"], 16) + 1)

    def test_inspect_rejects_untrusted_client_without_pairing(self):
        stream = self.connect()
        self.hello(stream, str(uuid.uuid4()))
        send(stream, dict(type="inspect", proof="0" * 64))
        self.assertEqual(read_line(stream), dict(type="error", reason="pair_required"))

    def test_fragmented_hello(self):
        stream = self.connect()
        message = json.dumps(dict(type="hello", v=1, clientId=str(uuid.uuid4()), name=self.name)).encode() + b"\n"
        stream.write(message[:8]); stream.flush()
        time.sleep(.02)
        stream.write(message[8:]); stream.flush()
        self.assertEqual(read_line(stream)["type"], "challenge")

    def test_invalid_inputs_close_connection(self):
        for raw in [b"[]\n", b"{broken}\n", b"a" * 4097]:
            with self.subTest(raw=raw[:16]):
                stream = self.connect()
                stream.write(raw); stream.flush()
                try:
                    self.assertEqual(stream.read(1), b"")
                except ConnectionResetError:
                    pass
                stream.close()


if __name__ == "__main__":
    unittest.main(verbosity=2)
