"""Meaningful wire-format checks against the independent Swift/Kotlin packet layout."""
import struct, unittest
import subprocess, sys, tempfile, wave
from pathlib import Path
from pcm_tool import packet, decode

class ProtocolTests(unittest.TestCase):
    def test_wire_offsets_endianness_and_audio(self):
        pcm=struct.pack('<480h',-32768,32767,*([1234]*478))
        b=packet(0x01020304,'1a2b3c4d',pcm)
        self.assertEqual(len(b),984)
        self.assertEqual(b[:8],b'HAP1\x01\x02\x03\x04')
        self.assertEqual(b[16:24],b'1a2b3c4d')
        self.assertEqual(b[24:28],b'\x00\x80\xff\x7f')
        self.assertEqual(decode(b,'1a2b3c4d')[2],pcm)
    def test_reject_truncation_wrong_token_and_magic(self):
        b=packet(0,'1234abcd',bytes(960))
        for bad in [b[:-1],b+bytes(1),b'BAD!'+b[4:]]:
            self.assertIsNone(decode(bad,'1234abcd'))
        self.assertIsNone(decode(b,'abcd1234'))
    def test_stereo_archive_converts_to_two_channel_wav(self):
        with tempfile.TemporaryDirectory(prefix='hyperaudio-stereo-wav-') as folder:
            raw=Path(folder)/'stereo.pcm'; output=Path(folder)/'stereo.wav'
            pcm=struct.pack('<6h',-32768,32767,1000,-1000,0,0); raw.write_bytes(pcm)
            result=subprocess.run([sys.executable,str(Path(__file__).with_name('pcm_tool.py')),
                'wav',str(raw),str(output),'--channels','2'],capture_output=True,text=True)
            self.assertEqual(result.returncode,0,result.stderr)
            with wave.open(str(output),'rb') as audio:
                self.assertEqual((audio.getnchannels(),audio.getnframes(),audio.getframerate()),(2,3,48000))
                self.assertEqual(audio.readframes(3),pcm)

if __name__=='__main__': unittest.main()
