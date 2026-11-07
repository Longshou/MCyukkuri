"""Preview page for the emote symbols: the atlas at 1x, each icon at 4x, and every icon over a rendered head.

The in-context image is 1280x720 with each body about 1/8 of the screen height (museum viewing distance) and the icon
at half the body height (3x its 16 px), over sky and over grass. Heads come from the shared offline renderer
(`scripts/preview.py`); the page goes to `.local/previews/emote-atlas/` and is registered on the preview hub.

    python standards/yukkuri-library/emotes/preview.py
"""
from pathlib import Path
import argparse, json, sys
from PIL import Image, ImageDraw

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
sys.path.insert(0, str(ROOT/'scripts'))
import preview  # noqa: E402
from build_preview_hub import register_topics  # noqa: E402

TOPIC = 'emote-atlas'
SCREEN, BODY, ICON_SCALE = (1280, 720), 90, 3


def head(model, stage):
    args = argparse.Namespace(model=model, prop=None, clip='idle', partner=None, size=[480, 480], view=[26, 10],
                              node='node', before=None)
    frame = preview.render_strip(args, stage, [0.0])[0]
    frame = frame.crop(frame.getbbox())
    return frame.resize((round(frame.width*BODY/frame.height), BODY), Image.LANCZOS)


def scene(icons, heads):
    w, h = SCREEN
    image = Image.new('RGBA', SCREEN, (128, 178, 228, 255))
    ImageDraw.Draw(image).rectangle((0, h//2, w, h), fill=(84, 128, 62, 255))
    for i, icon in enumerate(icons):
        body = heads[i % 2]
        cx = (i % 6)*w//6 + w//12
        floor = h//2 - 40 if i < 6 else h - 60
        image.alpha_composite(body, (cx - body.width//2, floor - body.height))
        big = icon.resize((16*ICON_SCALE, 16*ICON_SCALE), Image.NEAREST)
        image.alpha_composite(big, (cx - big.width//2, floor - body.height - big.height - 4))
    return image


def grid(icons, ids, scale=4):
    size, pad, label = 16*scale, 12, 28
    cell = size + 2*pad
    image = Image.new('RGBA', (cell*6, (cell+label)*2), (238, 232, 221, 255))
    draw, font = ImageDraw.Draw(image), preview.font(16)
    for i, (icon, ident) in enumerate(zip(icons, ids)):
        x, y = i % 6*cell, i//6*(cell+label)
        draw.rectangle((x+pad-2, y+pad-2, x+pad+size+1, y+pad+size+1), fill=(150, 150, 150, 255))
        image.alpha_composite(icon.resize((size, size), Image.NEAREST), (x+pad, y+pad))
        draw.text((x+pad, y+cell), ident, font=font, fill='#222')
    return image


def main():
    registry = json.loads((HERE/'assembled/emotes.json').read_text(encoding='utf-8'))
    ids = [e['id'] for e in registry['emotes']]
    icons = [Image.open(HERE/f'assembled/{i}.png').convert('RGBA') for i in ids]
    with preview.staging(TOPIC) as stage:
        heads = [head(m, stage) for m in ('reimu', 'marisa')]
        (stage/'camera.json').unlink()
        scene(icons, heads).save(stage/'current.png')
        grid(icons, ids).save(stage/'icons-4x.png')
        Image.open(HERE/'assembled/atlas.png').save(stage/'atlas-1x.png')
        rows = ''.join(f'<tr><td>{preview.E(e["id"])}</td><td>{preview.E(e["meaning"])}</td>'
                       f'<td>{preview.E(e["use"])}</td></tr>' for e in registry['emotes'])
        body = ('<p>头顶情绪符号：16×16 像素图集，由 <code>standards/yukkuri-library/emotes/emotes.py</code> 逐像素绘制。'
                '游戏内显示（广告牌、同步）随演出数据包接入，本页只看样子。</p>'
                + preview.media('current.png', '博物馆距离：身体约占画面 1/8 高，符号为身体高度的一半；上排天空、下排草地')
                + preview.media('icons-4x.png', '4 倍')
                + '<figure><img src="atlas-1x.png"><img src="atlas-1x.png" style="image-rendering:pixelated;width:256px;margin-left:24px">'
                  '<figcaption>图集原尺寸（左，64×48）与 4 倍（右）</figcaption></figure>'
                + '<table><tr><th>ID</th><th>含义</th><th>建议用途</th></tr>'+rows+'</table>')
        (stage/'index.html').write_text(preview.page('头顶情绪符号', body), encoding='utf-8')
        preview.write_json(stage/'topic.json', dict(title='头顶情绪符号', command='emotes/preview.py', inputs={}))
    register_topics(preview.OUT)
    print('http://127.0.0.1:8765/.local/previews/'+TOPIC+'/index.html')


if __name__ == '__main__':
    main()
