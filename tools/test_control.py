import json, socket, threading, unittest, uuid
from control_probe import read_line, send, session, proof

class ControlTests(unittest.TestCase):
    def setUp(self): self.state=dict(receiverId=str(uuid.uuid4()),clients={}); self.client=str(uuid.uuid4())
    def exchange(self, answer, allow=True):
        # Real TCP on loopback, without touching the production port.
        server=socket.socket(); server.bind(('127.0.0.1',0)); server.listen(1)
        error=[]
        def serve():
            try:
                conn,_=server.accept()
                with conn, conn.makefile('rwb') as stream: session(stream,self.state,allow)
            except Exception as e: error.append(e)
            finally: server.close()
        t=threading.Thread(target=serve); t.start()
        with socket.create_connection(server.getsockname(),timeout=2) as s, s.makefile('rwb') as stream:
            send(stream,dict(type='hello',v=1,clientId=self.client,name='test'))
            challenge=read_line(stream); send(stream,answer(challenge)); response=read_line(stream)
        t.join(3); self.assertFalse(t.is_alive()); self.assertFalse(error)
        return response
    def test_pair_resume_and_new_session_token(self):
        first=self.exchange(lambda _:dict(type='pair'))
        self.assertEqual(first['type'],'ready'); self.assertEqual(len(first['secret']),64)
        second=self.exchange(lambda c:dict(type='resume',proof=proof(first['secret'],self.client,c['receiverId'],c['nonce'])))
        self.assertEqual(second['type'],'ready'); self.assertNotIn('secret',second)
        self.assertNotEqual(first['token'],second['token'])
    def test_reject_invalid_proof_and_unapproved_pair(self):
        self.exchange(lambda _:dict(type='pair'))
        self.assertEqual(self.exchange(lambda _:dict(type='resume',proof='0'*64))['type'],'error')
        self.assertEqual(self.exchange(lambda _:dict(type='pair'),allow=False)['type'],'error')
    def test_cross_language_hmac_vector(self):
        self.assertEqual(proof(bytes(range(32)).hex(),'00112233-4455-6677-8899-aabbccddeeff',
            'ffeeddcc-bbaa-9988-7766-554433221100','test-nonce-01'),
            'b664d32f0d3c8ff7b9605f7d7f6b9f1d342ad67985eda8ba4f4910e7285cac9f')
    def test_reject_oversize_and_nonobject(self):
        import io
        for raw in [b'a'*4097,b'[]\n']:
            with self.assertRaises(ValueError): read_line(io.BytesIO(raw))

if __name__=='__main__': unittest.main()
