"""HAC1 TCP probe for automatic pairing. LAN-only, unencrypted, no Bonjour dependency."""
import argparse, hashlib, hmac, json, secrets, socket, uuid
from pathlib import Path

def proof(secret, client, receiver, nonce):
    return hmac.new(bytes.fromhex(secret),f'HAC1|{client}|{receiver}|{nonce}'.encode(),hashlib.sha256).hexdigest()

def read_line(stream):
    line=stream.readline(4097)
    if len(line)>4096 or not line.endswith(b'\n'): raise ValueError('Invalid or oversized line')
    message=json.loads(line)
    if not isinstance(message,dict): raise ValueError('Expected object')
    return message

def send(stream, message):
    stream.write(json.dumps(message,separators=(',',':')).encode()+b'\n'); stream.flush()

def session(stream, state, allow_pairing):
    hello=read_line(stream)
    if hello.get('type')!='hello' or hello.get('v')!=1: raise ValueError('Unsupported hello')
    client=str(uuid.UUID(hello['clientId'])).lower()
    nonce=str(uuid.uuid4())+str(uuid.uuid4())
    rid=state['receiverId']
    send(stream,dict(type='challenge',v=1,receiverId=rid,name='HyperAudio-Probe',nonce=nonce))
    request=read_line(stream)
    secret=None
    if request.get('type')=='pair' and allow_pairing:
        secret=secrets.token_hex(32); state['clients'][client]=secret
    elif request.get('type')=='resume' and client in state['clients'] and hmac.compare_digest(
        request.get('proof',''),proof(state['clients'][client],client,rid,nonce)):
        pass
    else:
        send(stream,dict(type='error',reason='pair_required')); return None
    token=secrets.token_hex(4)
    response=dict(type='ready',v=1,receiverId=rid,name='HyperAudio-Probe',token=token,port=50005)
    if secret: response['secret']=secret
    send(stream,response)
    return token

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--bind',default='0.0.0.0'); p.add_argument('--port',type=int,default=50006)
    p.add_argument('--state',default='control-probe-state.json')
    p.add_argument('--allow-pairing',action='store_true',help='Permit first pairing; only use on a trusted test LAN')
    a=p.parse_args(); path=Path(a.state)
    state=json.loads(path.read_text()) if path.exists() else dict(receiverId=str(uuid.uuid4()),clients={})
    with socket.socket() as server:
        server.setsockopt(socket.SOL_SOCKET,socket.SO_REUSEADDR,1); server.bind((a.bind,a.port)); server.listen(4)
        print(f'Listening TCP {a.port}. In Android manual options, enter this PC IP and choose pair by address.',flush=True)
        while True:
            connection,_=server.accept()
            try:
                with connection:
                    connection.settimeout(40)
                    with connection.makefile('rwb') as stream:
                        token=session(stream,state,a.allow_pairing)
                        if token:
                            path.write_text(json.dumps(state),encoding='utf-8')
                            print(f'Accepted. Current UDP audio token: {token}',flush=True)
            except (ValueError,OSError,KeyError) as error: print(f'Rejected: {type(error).__name__}',flush=True)

if __name__=='__main__': main()
