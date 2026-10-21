"""Editable block hat candidate, derived from the selected Marisa hat; no runtime writes."""
import argparse
import copy
import importlib.util
import json
import os
from pathlib import Path
import sys
import uuid
import numpy as np

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[3]
ASSETS = ROOT / 'src/main/resources/assets/mcyukkuri'
PREFIXES = ('brim_', 'hat_', 'bow_')
RECIPE = json.loads((HERE / 'recipe.json').read_text())


def read(path):
    return json.loads(path.read_text(encoding='utf-8'))


def write(path, data):
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')


def adapt(name, points):
    points = np.array(points, dtype=float).copy()
    if name in RECIPE['crown_parts']:
        points[..., 1] = RECIPE['hinge_y'] + (points[..., 1] - RECIPE['hinge_y']) * RECIPE['upper_height']
    return points


def vessel_module():
    path = ROOT / 'standards/yukkuri-library/interactions/marisa-hat-vessel/build.py'
    spec = importlib.util.spec_from_file_location('hat_vessel_source', path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def make():
    runtime = read(ASSETS / 'yukkuri/marisa.json')
    candidate = copy.deepcopy(runtime)
    raw = np.array(candidate['vertices']).reshape(-1, 8)
    for part in candidate['parts']:
        start, end = part['start'], part['start'] + part['count']
        raw[start:end, :3] = adapt(part['name'], raw[start:end, :3])
    candidate['vertices'] = raw.reshape(-1).tolist()
    B = vessel_module()
    original = B.worn_boxes(runtime)
    worn = B.worn_boxes(candidate)
    boxes, cavity, levels = B.hollow(worn)
    metrics = read(ASSETS / 'yukkuri_presentation/hat_vessel.json')
    flat, parts = B.triangles(boxes, worn, cavity, np.array(metrics['anchor_worn']))
    boat = dict(vertices=flat, parts=parts)
    _, original_cavity, _ = B.hollow(original)
    # The entire contact geometry is identical; only the closed tip below the floor changes.
    for name in original:
        if name not in RECIPE['crown_parts']:
            assert np.array_equal(original[name]['lo'], worn[name]['lo']), name
            assert np.array_equal(original[name]['hi'], worn[name]['hi']), name
    for old, new in zip(original_cavity, cavity):
        assert all(np.array_equal(a, b) for a, b in zip(old, new))
    assert all(np.all(lo < hi) for _, lo, hi in boxes)
    source = read(ROOT / 'standards/yukkuri-library/assembled/marisa/marisa.bbmodel')
    source['name'] = 'Marisa block hat boat candidate'
    source['elements'] = [e for e in source['elements'] if e['name'].startswith(PREFIXES)]
    for element in source['elements']:
        for key in ('from', 'to'):
            element[key] = adapt(element['name'], element[key]).tolist()
    source['outliner'] = [e['uuid'] for e in source['elements']]
    source.pop('animations', None)
    write(HERE / 'worn-hat.bbmodel', source)
    # Editable cuboids retain the exact carved wall/tread structure of the established hull.
    hull = copy.deepcopy(source)
    hull['name'] = 'Marisa turned block hat boat candidate'
    hull['elements'] = []
    original_elements = {e['name']: e for e in source['elements']}
    for i, (name, lo, hi) in enumerate(boxes):
        element = copy.deepcopy(original_elements[name])
        element['uuid'] = str(uuid.uuid5(uuid.NAMESPACE_URL, 'mcyukkuri/block-hat-boat/' + str(i)))
        # Keep the authored hat axes; Blockbench can flip this complete group for editing.
        element['name'] = name + '_' + str(i)
        element['from'], element['to'] = lo.tolist(), hi.tolist()
        hull['elements'].append(element)
    hull['outliner'] = [dict(name='Turned hat', origin=metrics['anchor_worn'], rotation=[180, 90, 0],
                             uuid=str(uuid.uuid5(uuid.NAMESPACE_URL, 'mcyukkuri/block-hat-boat/group')),
                             children=[e['uuid'] for e in hull['elements']])]
    write(HERE / 'boat-hat.bbmodel', hull)
    return candidate, boat, metrics


def preview(candidate, boat, metrics, out):
    from PIL import Image, ImageDraw
    sys.path.insert(0, str(ROOT / 'standards/yukkuri-library/motions/variants/lore'))
    import hat_motion_preview as H
    import preview as P
    out.mkdir(parents=True, exist_ok=True)
    atlas = H.Atlas()
    texture = Image.open(ASSETS / 'textures/entity/marisa/neutral.png')
    corrections = read(ASSETS / 'yukkuri_presentation/motion_corrections.json')
    def textured(mesh):
        mesh = mesh.copy()
        mesh[:, 5:8] /= np.linalg.norm(mesh[:, 5:8], axis=1, keepdims=True)
        mesh[:, 3:5] = atlas.uv('marisa', texture, mesh[:, 3:5])
        return mesh
    def actor(changed=False, aquatic=False):
        posed = H.SelectedPosed('marisa', corrections, aquatic, worn_hat_scale=metrics['aquatic_scale'])
        if changed:
            offset = 0
            for part in posed.model['parts']:
                if part.get('anatomy'):
                    continue
                size = part['count']
                posed.v[offset:offset+size] = adapt(part['name'], posed.v[offset:offset+size])
                offset += size
        pos, uv, normal = posed.frame('idle', 0)
        mesh = np.concatenate((pos, uv, np.repeat(normal[:, None, :], 3, axis=1)), axis=2).reshape(-1, 8)
        if aquatic:
            mesh = mesh[:-posed.boat]
            mesh[:, :3] /= metrics['aquatic_scale']
            mesh[:, :3] += [0, metrics['bowl']['owner_y'], max(0, metrics['opening'][3]-5.9/metrics['aquatic_scale'])]
        return textured(mesh)
    def hull(data):
        mesh = textured(np.array(data['vertices']).reshape(-1, 8))
        mesh[:, 1] = mesh[:, 1]*metrics['squash']['bowl'] + metrics['bowl']['freeboard']
        return mesh
    old_boat = hull(read(ASSETS / 'yukkuri_props/marisa_hat_turned.json'))
    new_boat = hull(boat)
    body = actor(aquatic=True)
    old_worn, new_worn = actor(), actor(True)
    H.W, H.H, H.BG = 720, 520, (239, 242, 233)
    sheet = Image.new('RGB', (1480, 1880), '#eff2e9')
    draw = ImageDraw.Draw(sheet)
    draw.text((28, 20), '水生魔理沙 · 方块帽船', font=P.preview_font(38), fill='#253d35')
    draw.text((30, 73), '保留宽檐、白带与蝴蝶结；收低上段帽冠，让折尖更紧凑。', font=P.preview_font(23), fill='#58675c')
    cards = [
        ('现版 · 水生佩戴比例', old_worn, .5, .35, 23, 14),
        ('候选 · 水生佩戴比例', new_worn, .5, .35, 23, 14),
        ('现版 · 翻转空帽', old_boat, .5, .82, 12, -1.2),
        ('候选 · 翻转空帽', new_boat, .5, .82, 12, -1.2),
        ('候选 · 母油乘坐', np.concatenate((new_boat, body)), .5, .66, 12, 1.7),
        ('候选 · 侧面船底与折尖', new_boat, 1.1, .12, 12, -1.2),
    ]
    for i, (label, mesh, az, el, span, cy) in enumerate(cards):
        x, y = 20 + (i % 2)*740, 125 + (i // 2)*570
        draw.text((x+12, y), label, font=P.preview_font(26), fill='#253d35')
        pixels, _, _ = H.rasterize(mesh.reshape(-1, 3, 8), np.array(atlas.image), az=az, el=el, span=span, center_y=cy)
        sheet.paste(Image.fromarray(pixels), (x, y+40))
    draw.text((30, 1840), '制作候选 · 原模型离线渲染；内腔与帽檐接触面不变，尚未接入游戏。', font=P.preview_font(21), fill='#58675c')
    sheet.save(out / 'comparison.png')
    hero = Image.new('RGB', (1480, 690), '#eff2e9')
    hd = ImageDraw.Draw(hero)
    hd.text((30, 24), '水生魔理沙 · 方块帽船候选', font=P.preview_font(36), fill='#253d35')
    for i, (label, mesh, span, cy, el) in enumerate([
        ('戴在头上', new_worn, 20, 14, .35),
        ('翻转成船 · 母油乘坐', np.concatenate((new_boat, body)), 11.2, 1.7, .75),
    ]):
        x = 20 + 740*i
        hd.text((x+15, 85), label, font=P.preview_font(25), fill='#58675c')
        pixels, _, _ = H.rasterize(mesh.reshape(-1, 3, 8), np.array(atlas.image), az=.5, el=el, span=span, center_y=cy)
        hero.paste(Image.fromarray(pixels), (x, 120))
    hd.text((30, 650), '实际模型离线预览 · 未替换游戏素材', font=P.preview_font(21), fill='#58675c')
    hero.save(out / 'current.png')
    (out / 'index.html').write_text('<!doctype html><meta charset="utf-8"><title>方块帽船候选</title><style>body{font:18px sans-serif;background:#eff2e9;color:#253d35;margin:24px}img{max-width:100%}</style><a href="../index.html">预览总入口</a><h1>方块帽船候选</h1><p>当前帽子制作源派生；佩戴使用水生 1.5 倍比例，船态使用现有翻转与压缩规则。</p><img src="comparison.png"><p>尚未替换运行素材；行为兼容结论限于保留的接触几何。</p>', encoding='utf-8')
    hub_source = out.parents[2] / 'preview/hub/index.html'
    if not hub_source.is_file():
        hub_source = ROOT / 'preview/hub/index.html'
    hub = hub_source.read_text(encoding='utf-8')
    entry = '<p><a href="block-hat-boat/index.html">水生魔理沙 · 方块帽船候选与现版对照 ↗</a></p>'
    hub = hub.replace('<main id="recent">', '<main id="recent">\n  ' + entry, 1)
    (out.parent / 'index.html').write_text(hub, encoding='utf-8')
    print(out / 'comparison.png')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--out', type=Path, default=Path(os.environ.get('MCY_LOCAL', ROOT / '.local')) / 'previews/block-hat-boat')
    args = parser.parse_args()
    preview(*make(), args.out)
