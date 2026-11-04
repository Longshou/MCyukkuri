"""Side view of where the stool leaves each body: the rump mark (anus) carried by the straining clip, tick by tick, and the stool at the drop tick.
Offline, no game or UI. Output: .local/previews/toilet-contact/outlet-alignment.png"""
from pathlib import Path
import json, math
import numpy as np
from PIL import Image, ImageDraw, ImageFont
HERE = Path(__file__).resolve().parent; ROOT = HERE.parents[3]
OUT = ROOT / '.local/previews/toilet-contact'; OUT.mkdir(parents=True, exist_ok=True)
data = json.loads((ROOT / 'src/main/resources/assets/mcyukkuri/yukkuri_presentation/toilet_contact.json').read_text(encoding='utf8'))
FORMS = [('reimu', '标准灵梦（成体）'), ('marisa_bean', '豆形不足油'), ('house_reimu', '家油灵梦'), ('flandre', '捕食种芙兰')]
SCALE, W, H = 150, 440, 330


def outlet_track(export, clip):
    """The anus (site_px) under the clip's root pose at every tick, in blocks: the same posing as life/ToiletContact."""
    names, outlet = export['bone_names'], export['outlet']; root = names.index('root')
    tracks = {t['channel']: t['keys'] for t in export['clips'][clip]['tracks'] if t['bone'] == root}
    site, pivot = np.array(outlet['site_px']), np.array(outlet['pivot_px']); out = []
    for pos, rot, scale in zip(tracks['position'], tracks['rotation'], tracks['scale']):
        x, y, z = np.radians(rot[1:]); cx, sx, cy, sy, cz, sz = math.cos(x), math.sin(x), math.cos(y), math.sin(y), math.cos(z), math.sin(z)
        R = np.array([[cz*cy, cz*sy*sx-sz*cx, cz*sy*cx+sz*sx], [sz*cy, sz*sy*sx+cz*cx, sz*sy*cx-cz*sx], [-sy, cy*sx, cy*cx]])
        out.append((R@((site-pivot)*scale[1:])+pivot+pos[1:])/16)
    return out
font = lambda n: ImageFont.truetype('C:/Windows/Fonts/msyh.ttc', n)
sheet = Image.new('RGB', (W * len(FORMS), H + 90), '#f3f0e8'); draw = ImageDraw.Draw(sheet)
draw.text((20, 14), '排便出口 · 油菊（后侧）随用力动作的轨迹；棕色为 34 tick 排出的排泄物', font=font(22), fill='#253c35')
for col, (ident, label) in enumerate(FORMS):
    model = json.loads((ROOT / f'src/main/resources/assets/mcyukkuri/yukkuri/{ident}.json').read_text(encoding='utf-8-sig'))
    raw = np.array(model['vertices']).reshape(-1, 8)
    body = np.concatenate([raw[p['start']:p['start'] + p['count'], :3] for p in model['parts'] if p['name'].endswith('body_surface')]) / 16
    x0 = col * W + W * .62; floor = 60 + H - 40
    def pt(z, y): return (x0 + z * SCALE * -1, floor - y * SCALE)  # +z is the rear: drawn to the left
    draw.line([(col * W + 15, floor), (col * W + W - 15, floor)], fill='#6b6b6b', width=2)
    for y, z in body[::2, [1, 2]]:
        draw.ellipse([*(c - 1 for c in pt(z, y)), *(c + 1 for c in pt(z, y))], fill='#c9b8a3')
    track = outlet_track(data['models'][ident], 'ground_toilet')
    for tick, (x, y, z) in enumerate(track):
        px, py = pt(z, y); draw.ellipse([px - 2, py - 2, px + 2, py + 2], fill='#d94f4f' if tick == 34 else '#4a7fb5')
    x, y, z = track[34]; size = {'house_reimu': .32, 'marisa_bean': 1}.get(ident, 1)
    dx, dy, dz = 0, -.26, .965; length = .165 * (.4 if ident == 'house_reimu' else 1)
    a = pt(z - dz * .0, y); b = pt(z + dz * length, y + dy * length)
    draw.line([a, b], fill='#60401f', width=int(.07 * SCALE * (.4 if ident == 'house_reimu' else 1)))
    draw.text((col * W + 20, H + 30), f'{label}\n{ident} · 油菊 y={track[0][1]:.3f} 后 {track[0][2]:.3f} 格（静止）', font=font(15), fill='#253c35')
sheet.save(OUT / 'outlet-alignment.png'); print(OUT / 'outlet-alignment.png')
