#!/usr/bin/env python3
"""Render the maintained Markdown manual into portable, offline HTML."""
from pathlib import Path
import base64
import html
import sys

try:
    import markdown2
except ImportError:
    raise SystemExit('Install optional docs dependency: python3 -m pip install -r tools/requirements-docs.txt')

root = Path(__file__).resolve().parents[1]
source = root / 'docs/guide/安装操作手册.md'
body = markdown2.markdown(source.read_text(), extras=['tables', 'fenced-code-blocks', 'header-ids', 'toc'])
icon = root / 'android/app/src/main/res/drawable-nodpi/launcher_art.png'
data = base64.b64encode(icon.read_bytes()).decode('ascii')
css = '''
:root{color-scheme:light;--ink:#25283e;--muted:#656b80;--accent:#5863d6;--line:#e3e5f0}
*{box-sizing:border-box}body{margin:0;background:#f5f5fa;color:var(--ink);font:16px/1.85 -apple-system,BlinkMacSystemFont,"Segoe UI","Microsoft YaHei",sans-serif}
a{color:#4450c0;text-underline-offset:3px}header{background:linear-gradient(125deg,#313d9c,#6544bc);color:white;padding:38px max(24px,calc((100vw - 1180px)/2))}header img{width:74px;height:74px;border-radius:19px;float:left;margin:4px 20px 10px 0}header h1{font-size:29px;line-height:1.3;margin:0 0 8px}header p{margin:0;opacity:.9}.downloads{display:flex;gap:12px;flex-wrap:wrap;margin-top:22px}.downloads a{background:white;color:#3e49ad;border-radius:12px;padding:9px 18px;text-decoration:none;font-weight:600}
.layout{display:grid;grid-template-columns:240px minmax(0,1fr);gap:28px;max-width:1228px;padding:28px 24px 60px;margin:auto}nav{position:sticky;top:20px;max-height:90vh;overflow:auto;font-size:13px;padding:14px 8px;align-self:start}nav ul{list-style:none;padding-left:13px}nav>ul{padding-left:0}nav a{text-decoration:none;color:var(--muted)}nav li{margin:7px 0}main{padding:28px 36px;background:white;border-radius:22px;min-width:0}main>h1{font-size:26px;margin-top:0}h2{font-size:23px;line-height:1.45;margin:42px 0 16px;padding-top:16px;border-top:1px solid var(--line);scroll-margin-top:20px}h3{font-size:18px;margin:26px 0 12px;scroll-margin-top:20px}p{margin:12px 0}li{margin:6px 0}table{display:block;overflow:auto;border-collapse:collapse;width:100%;font-size:14px;margin:18px 0}th,td{padding:11px 13px;border:1px solid var(--line);vertical-align:top;text-align:left;min-width:115px}th{background:#f0f1fb}tr:nth-child(even){background:#fafaff}code{font-family:ui-monospace,Consolas,monospace;font-size:.88em;background:#f1f2fa;padding:2px 5px;border-radius:5px;overflow-wrap:anywhere}pre{white-space:pre-wrap;word-break:break-word;background:#f2f3fa;padding:16px;border-radius:12px;line-height:1.6}pre code{padding:0}blockquote{border-left:4px solid #a3aae8;padding:6px 18px;margin:16px 0;background:#f7f7ff}footer{color:var(--muted);font-size:13px;margin-top:28px}.print{border:1px solid #c9cef0;background:transparent;color:white;padding:8px 15px;border-radius:10px;cursor:pointer}
@media(max-width:850px){.layout{display:block;padding:16px}nav{position:static;max-height:230px;margin-bottom:16px;background:#edeef8;border-radius:14px}main{padding:20px}header{padding:28px 22px}header h1{font-size:24px}h2{font-size:21px}}@media print{body{background:white;font-size:11pt;line-height:1.6}header{background:white;color:#222;padding:0 0 20px;border-bottom:1px solid #ddd}header img{width:52px;height:52px}.layout{display:block;padding:0;max-width:none}nav,.downloads,.print{display:none}main{padding:0;border-radius:0}h2{break-after:avoid}h3{break-after:avoid}tr,img,pre{break-inside:avoid}table{font-size:9pt;overflow:visible;display:table}th,td{min-width:0}a{color:#222}footer{display:none}@page{size:A4;margin:18mm}}
'''
for output, prefix in [(root / '安装操作手册.html', ''), (root / 'docs/guide/安装操作手册.html', '../../')]:
    rendered_body = body.replace('href="SelfRefresh安装与续期.md"', f'href="{prefix}docs/guide/SelfRefresh安装与续期.md"')
    document = f'''<!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>HyperAudio 安装操作手册</title><style>{css}</style></head><body>
<header><img alt="HyperAudio 蓝紫波形图标" src="data:image/png;base64,{data}"><h1>HyperAudio 安装与操作手册</h1><p>Android 0.7 · iOS 0.6.6 · Windows / macOS · 不使用 TestFlight</p><div class="downloads"><a href="{prefix}releases/android/HyperAudioLab-0.7.apk">Android 安装包</a><a href="{prefix}releases/ios/HyperAudioReceiver-0.6.6-SelfRefresh-signed.ipa">iOS IPA（需签名安装）</a><a href="{prefix}releases/RELEASES.md">签名期限与版本</a><button class="print" onclick="window.print()">打印 / 存为 PDF</button></div></header>
<div class="layout"><nav aria-label="手册目录"><strong>阅读目录</strong>{body.toc_html}</nav><main>{rendered_body}<footer>离线手册，无统计脚本或外部字体。解压后阅读；基础安装资料核对于 2026-11-08；签名维护补充于 2026-11-20。</footer></main></div></body></html>'''
    output.write_text(document)
    print(output.relative_to(root))
