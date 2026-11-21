"""Export only this lab's files and relevant build/package/logcat diagnostics."""
import argparse, subprocess
from pathlib import Path

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--adb',default='adb'); p.add_argument('--out',default='diagnostics')
    a=p.parse_args(); out=Path(a.out); out.mkdir(parents=True,exist_ok=True)
    def run(args):
        r=subprocess.run([a.adb,*args],stdout=subprocess.PIPE,stderr=subprocess.PIPE)
        if r.returncode: raise RuntimeError(r.stderr.decode(errors='replace'))
        return r.stdout
    props=['ro.product.model','ro.build.version.release','ro.build.version.sdk','ro.build.display.id','ro.mi.os.version.name','ro.build.version.incremental']
    lines=[]
    for prop in props:
        lines.append(f'{prop}={run(["shell","getprop",prop]).decode(errors="replace").strip()}')
    for package in ['lab.hyperaudio','tv.danmaku.bili','com.ss.android.ugc.aweme','air.tv.douyu.android']:
        raw=run(['shell','dumpsys','package',package]).decode(errors='replace')
        lines += [package+': '+line.strip() for line in raw.splitlines() if 'versionName=' in line or 'versionCode=' in line]
    (out/'device.txt').write_text('\n'.join(lines),encoding='utf-8')
    (out/'hyperaudio-logcat.txt').write_bytes(run(['logcat','-d','-s','HyperAudio:I','*:S']))
    # Binary-safe export (works on Windows PowerShell too); no shell redirection.
    (out/'sessions.tar').write_bytes(run(['exec-out','run-as','lab.hyperaudio','tar','-cf','-','-C','/storage/emulated/0/Android/data/lab.hyperaudio/files','.']))
    print(f'Exported to {out.resolve()}; extract sessions.tar manually. Only known package names queried; variants may differ.')

if __name__=='__main__': main()
