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


SOURCE = read(ROOT / 'standards/yukkuri-library/assembled/marisa/marisa.bbmodel')
BOUNDS = {e['name']: (np.array(e['from']), np.array(e['to']))
          for e in SOURCE['elements'] if e['name'].startswith(PREFIXES)}


def adapt(name, points):
    points = np.array(points, dtype=float).copy()
    if name in RECIPE['boxes']:
        old_lo, old_hi = BOUNDS[name]
        box = RECIPE['boxes'][name]
        new_lo, new_hi = np.array(box['from']), np.array(box['to'])
        points = new_lo + (points-old_lo)/(old_hi-old_lo)*(new_hi-new_lo)
    elif name.startswith('brim_'):
        low, high = BOUNDS[name]
        a, b = RECIPE['brim_y']
        points[..., 1] = a + (points[..., 1]-low[1])/(high[1]-low[1])*(b-a)
    elif name in RECIPE['crown_parts']:
        points[..., 1] = RECIPE['upper_base_y'] + (points[..., 1]-RECIPE['hinge_y'])*RECIPE['upper_height']
    return np.round(points, 5)


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
    B.R = copy.deepcopy(B.R)
    B.R['front_tread']['inner_z'] = RECIPE['front_tread_inner_z']
    boxes, cavity, levels = B.hollow(worn)
    # Retain a real intermediate front step; aligned tier fronts would make a new vertical drop.
    cutoff = -RECIPE['deep_front_z']
    old_front = levels['holes'][-1][1][0]
    boxes.append(('hat_taper_2', np.array([cutoff, levels['depths'][-2], levels['holes'][-1][0][2]]),
                  np.array([old_front, levels['depths'][-1], levels['holes'][-1][1][2]])))
    levels['holes'][-1][1][0] = cutoff
    cavity[-1][1][0] = cutoff
    metrics = read(ASSETS.parents[4] / 'standards/yukkuri-library/interactions/marisa-hat-vessel/geometry.json')
    flat, parts = B.triangles(boxes, worn, cavity, np.array(metrics['anchor_worn']))
    boat = dict(vertices=flat, parts=parts)
    turned = B.Turned(np.array(flat).reshape(-1, 8)[:, :3], levels, np.array(metrics['anchor_worn']), worn)
    for field in ('opening', 'well', 'floor', 'ledge', 'brim', 'brim_rects', 'keel'):
        metrics[field] = getattr(turned, field)
    metrics['cavity_sections'] = turned.sections
    metrics['squash'] = turned.squash
    metrics['bowl']['owner_y'] = metrics['bowl']['freeboard'] - metrics['ledge']*metrics['squash']['bowl']
    assert all(np.all(lo < hi) for _, lo, hi in boxes)
    # Shared worn/turned geometry: the brim becomes one real horizontal contact plane.
    for name, box in worn.items():
        if name.startswith('brim_'):
            assert abs(box['lo'][1]-metrics['anchor_worn'][1]) < 1e-4
            assert np.allclose(box['lo'][[0, 2]], original[name]['lo'][[0, 2]], atol=1e-4)
            assert np.allclose(box['hi'][[0, 2]], original[name]['hi'][[0, 2]], atol=1e-4)
        if name.startswith('bow_') or name in ('hat_base', 'hat_band'):
            assert np.allclose(box['lo'], original[name]['lo'], atol=1e-4)
            assert np.allclose(box['hi'], original[name]['hi'], atol=1e-4)
    layout = read(ASSETS / 'yukkuri_presentation/hat_cargo_layout.json')
    floor_rect = metrics['cavity_sections'][-1]['rect']
    cargo_x = (floor_rect[0]+floor_rect[1])/2
    cargo_delta = np.array([cargo_x-layout['tiers'][1][0]['x'],
                            read(ASSETS.parents[4] / 'standards/yukkuri-library/interactions/marisa-hat-vessel/geometry.json')['floor']-metrics['floor'], RECIPE['cargo_z']-layout['tiers'][1][0]['z']])
    for tier in layout['tiers'][1:]:
        for zone in tier:
            zone['x'] += cargo_delta[0]
            zone['depth'] -= cargo_delta[1]
            zone['z'] += cargo_delta[2]
            # The entire conservative cargo cylinder, not only the visible mesh, fits the new floor.
            assert zone['x']-zone['radius'] >= floor_rect[0]+.025
            assert zone['x']+zone['radius'] <= floor_rect[1]-.025
            assert zone['z']-zone['radius'] >= floor_rect[2]+.15
            assert zone['z']+zone['radius'] <= floor_rect[3]-.025
    metrics['cargo_delta'] = cargo_delta.tolist()
    geometry = {key: metrics[key] for key in ('anchor_worn', 'opening', 'well', 'floor', 'ledge', 'brim',
                'brim_rects', 'keel', 'cavity_sections', 'squash', 'scale', 'bowl', 'cargo_delta')}
    verify_geometry(B, boxes, boat, metrics, layout)
    geometry.update(status='pending_user_review', units='unsquashed_hat_units',
                    source='recipe.json', cargo_tiers=layout['tiers'])
    write(HERE / 'geometry.json', geometry)
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


def surface(data, x, z):
    for section in reversed(data['cavity_sections']):
        a, b, c, d = section['rect']
        if a-1e-5 <= x <= b+1e-5 and c-1e-5 <= z <= d+1e-5:
            return -section['depth']
    if any(a <= x <= b and c <= z <= d for a, b, c, d in data['brim_rects']):
        return 0.
    return -np.inf


def verify_geometry(B, boxes, boat, metrics, layout):
    """Read-only resource checks: source support planes and full cargo envelopes."""
    anchor = np.array(metrics['anchor_worn'])
    solids = []
    for _, lo, hi in boxes:
        corners = B.worn_to_vessel(np.array([lo, hi]), anchor)
        solids.append((corners.min(axis=0), corners.max(axis=0)))
    checked = 0
    for x in np.arange(-metrics['brim'][0]+.13, metrics['brim'][0], .4):
        for z in np.arange(-metrics['brim'][1]+.17, metrics['brim'][1], .4):
            expected = surface(metrics, x, z)
            if not np.isfinite(expected):
                continue
            actual = max((hi[1] for lo, hi in solids if lo[0] <= x <= hi[0] and lo[2] <= z <= hi[2]), default=-np.inf)
            assert abs(actual-expected) < 1e-4, (x, z, expected, actual)
            checked += 1
    provisions = read(ASSETS / 'yukkuri_props/hat_provisions.json')
    raw = np.array(provisions['vertices']).reshape(-1, 8)
    for part in provisions['parts']:
        if part.get('unit') not in (1, 2, 3):
            continue
        points = raw[part['start']:part['start']+part['count'], :3] + metrics['cargo_delta']
        assert all(y >= surface(metrics, x, z)+.024 for x, y, z in points), part['name']
    body = read(ASSETS/'yukkuri_presentation/body_geometry.json')['variants']['marisa']
    half = (body['bounds'][0][1]-body['bounds'][0][0])/2/metrics['scale']['aquatic_marisa']
    rear = max(0, metrics['opening'][3]-half-.05)
    support = max(surface(metrics, dx*(half+.025), rear+dz*(half+.025)) for dx in (-1, 0, 1) for dz in (-1, 0, 1))
    top, squash = metrics['bowl']['freeboard'], metrics['squash']['bowl']
    parent_y = top+support*squash
    # HatInteriorLayout.overlaps: the finite cargo cylinder must also clear the captain's complete collision box.
    for tier in layout['tiers'][1:]:
        for zone in tier:
            bottom, height = top-zone['depth']*squash, zone['height']*squash
            dx = max(0, abs(zone['x'])-half)
            dz = max(0, abs(zone['z']-rear)-half)
            horizontal = dx*dx+dz*dz < (zone['radius']+.025)**2
            assert not horizontal or parent_y >= bottom+height-.025, 'cargo intersects captain'
    child_half = half*.20
    front = metrics['cavity_sections'][1]['rect'][2]
    child_z = (metrics['opening'][2]+front)/2
    child_support = -metrics['cavity_sections'][0]['depth']
    for dx in (-1, 0, 1):
        for dz in (-1, 0, 1):
            assert abs(surface(metrics, dx*(child_half+.025), child_z+dz*(child_half+.025))-child_support) < 1e-4
    # A 0.20 child keeps its own enlarged hat; reserve the hat, not only the body, before the mother.
    assert child_z+metrics['brim'][1]*.20+.025 < rear-half
    old = read(ASSETS.parents[4] / 'standards/yukkuri-library/interactions/marisa-hat-vessel/geometry.json')
    area = lambda data: (lambda r: (r[1]-r[0])*(r[3]-r[2]))(data['cavity_sections'][-1]['rect'])
    print(f'Support samples={checked}; all three cargo meshes and conservative cylinders clear the candidate floor.')
    print(f'Floor area {area(old):.4f} -> {area(metrics):.4f}; cargo clears captain; 0.20 child fully supported with its hat clear of captain.')


def dimension_sheet(metrics, out):
    from PIL import Image, ImageDraw
    import preview as P
    old = read(ASSETS.parents[4] / 'standards/yukkuri-library/interactions/marisa-hat-vessel/geometry.json')
    old_layout = read(ASSETS / 'yukkuri_presentation/hat_cargo_layout.json')
    picture = Image.new('RGB', (1520, 1160), '#f2f4ee')
    draw = ImageDraw.Draw(picture)
    ink, muted = '#263d35', '#617369'
    draw.text((30, 20), '交互空间 · 实际尺寸对照', font=P.preview_font(35), fill=ink)
    draw.text((30, 70), '俯视内腔与中心剖面；颜色仅用于区分支撑层，未画进模型贴图。', font=P.preview_font(22), fill=muted)
    for col, (title, data) in enumerate((('现版', old), ('交互几何候选', metrics))):
        left = 30+col*760
        cx, cy, scale = left+340, 370, 20
        draw.text((left, 115), title, font=P.preview_font(28), fill=ink)
        def rect(r):
            return (cx+r[0]*scale, cy+r[2]*scale, cx+r[1]*scale, cy+r[3]*scale)
        for brim in data['brim_rects']:
            draw.rectangle(rect(brim), fill='#d9dfd5')
        colors = ['#e9d8a7', '#c9d4be', '#aebfa9', '#7da38b']
        for section, color in zip(data['cavity_sections'], colors):
            draw.rectangle(rect(section['rect']), fill=color, outline='#f2f4ee', width=2)
        zones = old_layout['tiers'] if col == 0 else read(HERE/'geometry.json')['cargo_tiers']
        for tier in (3, 2, 1):
            zone = zones[tier][0]; x, z, r = zone['x'], zone['z'], zone['radius']
            draw.ellipse(rect([x-r, x+r, z-r, z+r]), outline='#8a5736', width=2)
        draw.text((left+35, 605), '棕线：3 / 7 / 12 份粮堆的保守占位边界', font=P.preview_font(20), fill=muted)
        draw.text((left+35, 640), '绿色：最深平底　米黄：前踏台　灰色：帽檐', font=P.preview_font(20), fill=muted)
        # Longitudinal section through X=0, using the same measured sections as the body planner.
        ox, oy, sx, sy = left+340, 740, 26, 28
        line = []
        cuts = sorted({-data['brim'][1], data['brim'][1]} | {v for sec in data['cavity_sections'] for v in sec['rect'][2:]})
        for a, b in zip(cuts, cuts[1:]):
            depth = -surface(data, 0, (a+b)/2)*data['squash']['bowl']
            if np.isfinite(depth):
                line.extend([(ox+a*sx, oy+depth*sy), (ox+b*sx, oy+depth*sy)])
        draw.line([(left+45, oy), (left+650, oy)], fill='#bbc5ba', width=2)
        draw.line(line, fill='#344c41', width=5)
        floor = data['cavity_sections'][-1]['rect']
        area = (floor[1]-floor[0])*(floor[3]-floor[2])
        front = data['cavity_sections'][1]['rect'][2]-data['opening'][2]
        text = [f'平底面积  {area:.2f}', f'货底深度  {data["floor"]:.3f}', f'前踏台纵深  {front:.2f}']
        for row, value in enumerate(text):
            draw.text((left+35, 925+row*43), value, font=P.preview_font(24), fill=ink)
    draw.text((30, 1085), '单位：帽船模型单位（面积为平方单位）。未孕成体宽 8.53，仍不能在宽 7.00 的舱底通行。', font=P.preview_font(20), fill=muted)
    draw.text((30, 1120), '0.20 初航子油可完整站在新前踏台，自戴帽留出母油净空；动作与交接仍需游戏验证。', font=P.preview_font(20), fill=muted)
    picture.save(out/'contact-layout.png')


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
        posed = H.SelectedPosed('marisa', corrections, aquatic, worn_hat_scale=metrics['scale']['aquatic_marisa'])
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
            mesh[:, :3] /= metrics['scale']['aquatic_marisa']
            mesh[:, :3] += [0, metrics['bowl']['owner_y'], max(0, metrics['opening'][3]-6.4/metrics['scale']['aquatic_marisa']-.05)]
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
    draw.text((30, 73), '扩宽下腔、加宽前踏台、压平帽檐；保留深货底与母油的上下间隔。', font=P.preview_font(23), fill='#58675c')
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
    draw.text((30, 1840), '制作候选 · 接触几何已调整；静态空间检查通过不代表游戏动作通过。', font=P.preview_font(21), fill='#58675c')
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
    (out / 'index.html').write_text('<!doctype html><meta charset="utf-8"><title>方块帽船候选</title><style>body{font:18px sans-serif;background:#eff2e9;color:#253d35;margin:24px}img{max-width:100%}</style><a href="../index.html">预览总入口</a><h1>方块帽船候选</h1><p>当前帽子制作源派生；佩戴使用水生 1.5 倍比例，船态使用现有翻转与压缩规则。</p><img src="comparison.png"><img src="contact-layout.png"><p>下腔与货堆位置从同一模型导出；尚未替换运行素材或验证游戏动作。</p>', encoding='utf-8')
    hub_source = out.parents[2] / 'preview/hub/index.html'
    if not hub_source.is_file():
        hub_source = ROOT / 'preview/hub/index.html'
    hub = hub_source.read_text(encoding='utf-8')
    entry = '<p><a href="block-hat-boat/index.html">水生魔理沙 · 方块帽船候选与现版对照 ↗</a></p>'
    hub = hub.replace('<main id="recent">', '<main id="recent">\n  ' + entry, 1)
    (out.parent / 'index.html').write_text(hub, encoding='utf-8')
    dimension_sheet(metrics, out)
    print(out / 'comparison.png')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--out', type=Path, default=Path(os.environ.get('MCY_LOCAL', ROOT / '.local')) / 'previews/block-hat-boat')
    args = parser.parse_args()
    preview(*make(), args.out)
