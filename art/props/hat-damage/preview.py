#!/usr/bin/env python3
"""Preview of accessory damage: intact, frayed, torn, pieced together, and the three shard items, for each species.

Reads the exported accessory props (yukkuri_props/accessory_*.json and their textures) and applies the same
classification as client/HatDamageMask to the recipe beside this file. Software rendering only (numpy + Pillow).
Output: .local/previews/hat-damage/hat-damage.png and index.html.
"""
import json, math, sys
from pathlib import Path
import numpy as np
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[3]
ASSETS = ROOT / 'src/main/resources/assets/mcyukkuri'
# The shared preview root is the main checkout's .local (a worktree passes it as the first argument).
OUT = Path(sys.argv[1]) if len(sys.argv) > 1 else ROOT / '.local/previews/hat-damage'
RECIPE = json.loads((Path(__file__).parent / 'recipe.json').read_text(encoding='utf-8'))
SPECIES = [('reimu', '灵梦'), ('marisa', '魔理沙'), ('chen', '橙'), ('alice', '爱丽丝'), ('patchouli', '帕秋莉'), ('remilia', '蕾米莉亚')]
COLUMNS = ['完好', '轻损（1）', '撕裂（2）', '拼回（碎片聚齐）', '碎片 1', '碎片 2', '碎片 3']
CELL = 220

def classify(v, level, piece):
    n = len(v) // 8
    pos = np.array(v, dtype=np.float32).reshape(n, 8)[:, :3]
    lo, hi = pos.min(0), pos.max(0); span = np.where(hi - lo < 1e-5, 1, hi - lo)
    cent = pos.reshape(-1, 3, 3).mean(1)
    norm = np.where(hi - lo < 1e-5, .5, (cent - lo) / span)
    codes = np.zeros(len(norm), dtype=np.int8)
    if piece >= 0:
        p = RECIPE['pieces'][piece % len(RECIPE['pieces'])]
        inside = ((norm - p['center']) ** 2).sum(1) < p['radius'] ** 2
        return np.where(inside, 0, 2)
    spec = {1: RECIPE.get('frayed'), 2: RECIPE.get('torn'), 3: RECIPE.get('pieced')}.get(level)
    if not spec: return codes
    fray = spec.get('fray', 0)
    for t, c in enumerate(norm):
        code = 0
        for h in spec.get('holes', []):
            d = ((c - h['center']) ** 2).sum()
            if d < h['radius'] ** 2: code = 2; break
            if d < (h['radius'] + fray) ** 2: code = 1
        if code == 0:
            for k in spec.get('cracks', []):
                nrm = np.array(k['normal'], dtype=np.float32); nrm /= np.linalg.norm(nrm)
                if abs(float(nrm @ c) - k['offset']) < k['width']: code = 1; break
        codes[t] = code
    return codes

def render(name, level, piece):
    model = json.loads((ASSETS / f'yukkuri_props/{name}.json').read_text(encoding='utf-8'))
    ref = model['parts'][0].get('texture', f'mcyukkuri:textures/props/{name}.png').split(':', 1)[1]
    tex = np.asarray(Image.open(ASSETS / ref).convert('RGBA'), dtype=np.float32)
    v = model['vertices']; n = len(v) // 8
    data = np.array(v, dtype=np.float32).reshape(n, 8)
    codes = classify(v, level, piece)
    tint = np.array(RECIPE['fray_tint'], dtype=np.float32) / 255
    keep = [t for t in range(n // 3) if codes[t] != 2]
    if not keep: return Image.new('RGBA', (CELL, CELL), (0, 0, 0, 0))
    # 3/4 view from the front-left and slightly above (front is -z)
    yaw, pitch = math.radians(30), math.radians(-20)
    ry = np.array([[math.cos(yaw), 0, math.sin(yaw)], [0, 1, 0], [-math.sin(yaw), 0, math.cos(yaw)]])
    rx = np.array([[1, 0, 0], [0, math.cos(pitch), -math.sin(pitch)], [0, math.sin(pitch), math.cos(pitch)]])
    rot = rx @ ry
    pos = data[:, :3] @ rot.T; nrm = data[:, 5:8] @ rot.T
    sel = np.concatenate([np.arange(t * 3, t * 3 + 3) for t in keep])
    lo, hi = pos[sel].min(0), pos[sel].max(0)
    scale = (CELL - 24) / max(hi[0] - lo[0], hi[1] - lo[1], 1e-3)
    sx = (pos[:, 0] - (lo[0] + hi[0]) / 2) * scale + CELL / 2
    sy = CELL / 2 - (pos[:, 1] - (lo[1] + hi[1]) / 2) * scale
    depth = np.full((CELL, CELL), np.inf); img = np.zeros((CELL, CELL, 4), dtype=np.float32)
    light = np.array([-.3, .6, -.75]); light /= np.linalg.norm(light)
    th, tw = tex.shape[:2]
    for t in keep:
        i = [t * 3, t * 3 + 1, t * 3 + 2]
        x, y, z = sx[i], sy[i], pos[i, 2]
        x0, x1 = int(max(0, math.floor(x.min()))), int(min(CELL - 1, math.ceil(x.max())))
        y0, y1 = int(max(0, math.floor(y.min()))), int(min(CELL - 1, math.ceil(y.max())))
        den = (y[1] - y[2]) * (x[0] - x[2]) + (x[2] - x[1]) * (y[0] - y[2])
        if abs(den) < 1e-9 or x1 < x0 or y1 < y0: continue
        gx, gy = np.meshgrid(np.arange(x0, x1 + 1) + .5, np.arange(y0, y1 + 1) + .5)
        a = ((y[1] - y[2]) * (gx - x[2]) + (x[2] - x[1]) * (gy - y[2])) / den
        b = ((y[2] - y[0]) * (gx - x[2]) + (x[0] - x[2]) * (gy - y[2])) / den
        c = 1 - a - b
        m = (a >= -1e-4) & (b >= -1e-4) & (c >= -1e-4)
        if not m.any(): continue
        zz = a * z[0] + b * z[1] + c * z[2]
        u = a * data[i[0], 3] + b * data[i[1], 3] + c * data[i[2], 3]
        w = a * data[i[0], 4] + b * data[i[1], 4] + c * data[i[2], 4]
        tx = np.clip((u * tw).astype(int), 0, tw - 1); ty = np.clip((w * th).astype(int), 0, th - 1)
        col = tex[ty, tx]
        sub = depth[y0:y1 + 1, x0:x1 + 1]
        draw = m & (zz < sub) & (col[..., 3] > 20)
        nn = nrm[i].mean(0); nn = nn / (np.linalg.norm(nn) + 1e-9)
        shade = .55 + .45 * abs(float(nn @ light))
        rgb = col[..., :3] * shade * (tint if codes[t] == 1 else 1)
        sub[draw] = zz[draw]
        region = img[y0:y1 + 1, x0:x1 + 1]
        region[draw, :3] = rgb[draw]; region[draw, 3] = 255
    return Image.fromarray(np.clip(img, 0, 255).astype(np.uint8), 'RGBA')

def shard(name, piece):
    # Same fallback as PropItemRenderer: an empty piece region borrows the next piece.
    for i in range(3):
        cell = render(name, 0, piece + i)
        if cell.getbbox(): return cell
    return cell

def main():
    OUT.mkdir(parents=True, exist_ok=True)
    try: font = ImageFont.truetype('msyh.ttc', 18)
    except OSError: font = ImageFont.load_default()
    head, side = 36, 110
    sheet = Image.new('RGBA', (side + CELL * len(COLUMNS), head + CELL * len(SPECIES)), (238, 234, 226, 255))
    d = ImageDraw.Draw(sheet)
    for ci, title in enumerate(COLUMNS): d.text((side + ci * CELL + 10, 8), title, fill=(40, 40, 40), font=font)
    for ri, (sid, label) in enumerate(SPECIES):
        name = f'accessory_{sid}'
        d.text((10, head + ri * CELL + CELL // 2 - 10), label, fill=(40, 40, 40), font=font)
        cells = [render(name, lv, -1) for lv in range(4)] + [shard(name, p) for p in range(3)]
        for ci, cell in enumerate(cells):
            box = (side + ci * CELL, head + ri * CELL)
            d.rectangle([box[0] + 2, box[1] + 2, box[0] + CELL - 3, box[1] + CELL - 3], fill=(250, 248, 244, 255))
            sheet.alpha_composite(cell, box)
    sheet.save(OUT / 'hat-damage.png')
    (OUT / 'index.html').write_text('<!doctype html><meta charset="utf-8"><title>头饰损坏与碎片</title>'
        '<h1>头饰第二生命：损坏两档、拼回与碎片物品</h1><p>制作源 art/props/hat-damage/recipe.json；与 client/HatDamageMask 同一规则，'
        '作用于现有饰物几何。暗色为撕裂毛边／裂缝。</p><img src="hat-damage.png" style="max-width:100%">', encoding='utf-8')
    print(OUT / 'hat-damage.png')

if __name__ == '__main__':
    sys.exit(main())
