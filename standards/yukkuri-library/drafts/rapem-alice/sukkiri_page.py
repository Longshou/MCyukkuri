"""Before/after review page of the ordinary pair sukkiri: strips, size matrix and front-view GIFs (offline renders).

    <py> sukkiri_page.py <out-dir> --before <git-ref>

Renders with sukkiri_scene.js (CPU raster through the runtime pose core), so it needs Node.js, Pillow and the pose
core's JDK. The before side reads pose-cues.json at <git-ref>.
"""
import argparse
import html
import json
import subprocess
from pathlib import Path

from PIL import Image

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
DESIGN = {
    'before': '改前（当前主线）',
    'live': '改后·本包已生效（现有站位与固定接触脸）',
    'after': '改后·接线完成后（父方贴到后方站位、接触与完成各段换脸）',
}
VIEW = {'side': '侧面', 'front': '正侧 3/4', 'rear': '背后 3/4', 'sizes': '体型与种类（加快段 46t）',
        'alice-side': '爱丽丝攻击素鸡立 动作段 侧面', 'alice-front': '爱丽丝攻击素鸡立 动作段 正侧 3/4'}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('out')
    parser.add_argument('--before', required=True)
    args = parser.parse_args()
    out = Path(args.out).resolve()
    out.mkdir(parents=True, exist_ok=True)
    before = out/'before-cues.json'
    before.write_bytes(subprocess.run(['git', 'show', f'{args.before}:standards/yukkuri-library/drafts/rapem-alice/pose-cues.json'],
                                      cwd=ROOT, check=True, capture_output=True).stdout)
    subprocess.run(['node', str(HERE/'sukkiri_scene.js'), str(out), '--before', str(before)], cwd=ROOT, check=True)
    meta = json.loads((out/'strips.json').read_text(encoding='utf-8'))
    for anim in meta['animations']:
        frames = [Image.open(p).convert('RGB') for p in sorted((out/anim['dir']).glob('*.png'))]
        frames[0].save(out/f"{anim['design']}.gif", save_all=True, append_images=frames[1:], duration=anim['step']*50, loop=0)
    beats = ''.join(f'<th>{html.escape(b["label"])}</th>' for b in meta['beats'])
    act = ''.join(f'<th>顶送循环 {t}t</th>' for t in (0, 4, 8, 12, 16, 20))
    sizes = ''.join(f'<th>{html.escape(s["label"])}</th>' for s in meta['sizes'])
    rows = []
    for row in meta['rows']:
        head = sizes if row['view'] == 'sizes' else act if row['view'].startswith('alice') else beats
        # The Alice rows keep the act's real tangent placement: their after side is already live.
        design = DESIGN['live' if row['view'].startswith('alice') and row['design'] == 'after' else row['design']]
        rows.append(f'<h3>{design} · {VIEW[row["view"]]}</h3><table><tr>{head}</tr></table>'
                    f'<img src="{row["file"]}" loading="lazy">')
    gifs = ''.join(f'<figure><img src="{a["design"]}.gif"><figcaption>{DESIGN[a["design"]]}</figcaption></figure>'
                   for a in meta['animations'])
    page = f'''<!doctype html><meta charset="utf-8"><title>素鸡立演出 改前／改后</title>
<style>body{{font:14px sans-serif;margin:16px;background:#f4f6f3}}img{{max-width:none;display:block}}
table{{border-collapse:collapse;table-layout:fixed}}th{{width:340px;font-weight:normal;font-size:12px;text-align:left;padding:2px 4px}}
figure{{display:inline-block;margin:0 12px 12px 0}}.wide{{overflow-x:auto}}</style>
<h1>普通素鸡立（adult_pairing）演出 改前／改后</h1>
<p>离线渲染：真实运行模型、动作、表情与根部姿态（与游戏同序合成），按服务端站位与时钟取样；不是游戏录屏。
发情期接触 60 tick，完成段 120 tick（台词在 1／46／91 tick）。</p>
<h2>正侧动图（每 2 tick 一帧）</h2>{gifs}
<h2>节拍渲染条</h2><div class="wide">{"".join(rows)}</div>'''
    (out/'index.html').write_text(page, encoding='utf-8')
    print('wrote', out/'index.html')


if __name__ == '__main__':
    main()
