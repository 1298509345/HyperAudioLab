"""PC-only probe / synthetic sender / PCM-to-WAV utility. No third-party packages."""
import argparse, math, socket, struct, time, wave
from pathlib import Path

def packet(seq, token, pcm):
    if len(pcm) != 960 or len(token) != 8:
        raise ValueError('480 mono s16le samples and 8 ASCII token chars required')
    return struct.pack('>4sIQ8s',b'HAP1',seq,time.monotonic_ns(),token.encode('ascii'))+pcm

def decode(data, token):
    if len(data) != 984:
        return None
    magic,seq,stamp,key=struct.unpack('>4sIQ8s',data[:24])
    return (seq,stamp,data[24:]) if magic == b'HAP1' and key == token.encode('ascii') else None

def main():
    p=argparse.ArgumentParser(description=__doc__)
    sub=p.add_subparsers(dest='cmd',required=True)
    listen=sub.add_parser('listen'); listen.add_argument('--token',required=True); listen.add_argument('--seconds',type=int,default=10); listen.add_argument('--out',default='probe.wav')
    send=sub.add_parser('send'); send.add_argument('--host',required=True); send.add_argument('--token',required=True); send.add_argument('--seconds',type=int,default=10)
    wav=sub.add_parser('wav'); wav.add_argument('input'); wav.add_argument('output'); wav.add_argument('--channels',type=int,choices=(1,2),default=1)
    a=p.parse_args()
    if a.cmd == 'wav':
        pcm=Path(a.input).read_bytes()
        if len(pcm) % (a.channels*2): p.error('PCM size is not aligned to the selected channel count')
        with wave.open(a.output,'wb') as out:
            out.setparams((a.channels,2,48000,0,'NONE','not compressed')); out.writeframes(pcm)
        return
    if len(a.token) != 8 or any(c not in '0123456789abcdef' for c in a.token): p.error('token must be 8 lower-case hex chars')
    with socket.socket(socket.AF_INET,socket.SOCK_DGRAM) as s:
        if a.cmd == 'send':
            start=time.monotonic()
            for seq in range(a.seconds*100):
                pcm=struct.pack('<480h',*(int(3276*math.sin(2*math.pi*1000*(seq*480+i)/48000)) for i in range(480)))
                s.sendto(packet(seq,a.token,pcm),(a.host,50005))
                time.sleep(max(0,start+(seq+1)*0.01-time.monotonic()))
            return
        s.bind(('0.0.0.0',50005)); s.settimeout(0.5)
        frames={}; end=time.monotonic()+a.seconds
        print('Listening UDP 50005. Allow this port in your local firewall if needed.')
        while time.monotonic()<end:
            try: data,_=s.recvfrom(2048)
            except socket.timeout: continue
            decoded=decode(data,a.token)
            if decoded: frames[decoded[0]]=decoded[2]
        if not frames: raise SystemExit('No matching packets; check IP/token/network permission/firewall.')
        lo,hi=min(frames),max(frames)
        if hi-lo>60000: raise SystemExit('Sequence range too large; restart both peers.')
        pcm=b''.join(frames.get(i,bytes(960)) for i in range(lo,hi+1))
        values=struct.unpack('<%dh'%(len(pcm)//2),pcm)
        rms=math.sqrt(sum(v*v for v in values)/len(values))
        print(f'packets={len(frames)}, missing={hi-lo+1-len(frames)}, peak={max(map(abs,values))}, dBFS={20*math.log10(max(rms/32768,1e-6)):.2f}')
        with wave.open(a.out,'wb') as out:
            out.setparams((1,2,48000,0,'NONE','not compressed')); out.writeframes(pcm)

if __name__=='__main__': main()
