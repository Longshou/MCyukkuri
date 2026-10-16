#!/usr/bin/env python3
"""Shop-goods readability pass for the R2.3 peripheral snapshot (oil-grain bags, juice/remover bottles, cord).

Edits the selected snapshot files in place (the original authoring generator was deleted with the draft library).
Every step is idempotent; run, then re-export with scripts/export_peripheral_assets.py.

- Oil-grain bags: tier marks (1/2/3 gold stars, premium also gets a gold frame) replace the tiny sub-lines on the
  printed label; premium bag swatch is lifted so it no longer reads black in an inventory slot; the printed label is
  repeated on the back (+Z) so a label faces the camera whichever way the GUI transform turns the model.
- orange_juice / mold_remover: the wrapped label (orange slice / crossed-out mould) is repeated on the back.
- companion_cord: tube thickened (hex ring pushed out along its normal), loops dark rope, knot and tassels red.
- GUI display tweaks live in recipe.json ("gui_display"), applied by export_peripheral_assets.py.
"""
import argparse, base64, io, json, math, uuid, hashlib, sys
from pathlib import Path
from PIL import Image, ImageDraw

HERE = Path(__file__).resolve().parent
SEL = HERE / 'selected'
ROOT = HERE.parents[2]

BAGS = {'oil_grain_budget': 1, 'oil_grain_regular': 2, 'oil_grain_premium': 3}
BOTTLES = ('orange_juice', 'mold_remover')
GOLD, GOLD_LIGHT = (232, 170, 48), (252, 214, 104)
LABEL = (256, 0, 512, 384)          # printed_front uv box in the 512 atlas
STAR_TOP = 298                      # rows below hold only sub-lines that unreadable at inventory size
CORD_GROW = 0.26                    # extra tube radius in mesh units (original ~0.13)


def read_json(path):
    return json.loads(path.read_text(encoding='utf-8'))


def write_json(path, data):
    path.write_bytes((json.dumps(data, ensure_ascii=False, separators=(',', ':')) + '\n').encode('utf-8'))


def png_bytes(image):
    out = io.BytesIO(); image.save(out, format='PNG'); return out.getvalue()


def star(draw, cx, cy, r, fill, outline, width):
    pts = []
    for i in range(10):
        a = -math.pi / 2 + i * math.pi / 5
        k = r if i % 2 == 0 else r * .46
        pts.append((cx + k * math.cos(a), cy + k * math.sin(a)))
    draw.polygon(pts, fill=outline)
    inner = []
    for i in range(10):
        a = -math.pi / 2 + i * math.pi / 5
        k = (r - width) if i % 2 == 0 else (r - width) * .46
        inner.append((cx + k * math.cos(a), cy + (k * math.sin(a))))
    draw.polygon(inner, fill=fill)
    # top-left light: small highlight wedge on the upper left arm
    draw.polygon([(cx, cy - r * .55), (cx - r * .15, cy - r * .12), (cx - r * .5, cy - r * .1)], fill=GOLD_LIGHT)


def paint_bag_label(image, tier):
    x0, y0, x1, y1 = LABEL
    bg = image.getpixel((x0 + 14, 300))
    header = image.getpixel((x0 + 44, 10))             # tier colour, also used by the title glyphs
    scale = 4
    w, h = x1 - x0, y1 - STAR_TOP
    big = Image.new('RGB', (w * scale, h * scale), bg[:3])
    d = ImageDraw.Draw(big)
    centres = {1: [128], 2: [86, 170], 3: [46, 128, 210]}[tier]
    r = 38 if tier < 3 else 36
    for cx in centres:
        star(d, cx * scale, (h / 2 + 1) * scale, r * scale, GOLD, header[:3], 5 * scale)
    if tier == 3:   # gold frame: pattern difference that survives greyscale and tiny sizes
        t = 7 * scale
        d.rectangle((0, h * scale - t, w * scale, h * scale), fill=GOLD)
        d.rectangle((0, 0, t, h * scale), fill=GOLD)
        d.rectangle((w * scale - t, 0, w * scale, h * scale), fill=GOLD)
    small = big.resize((w, h), Image.LANCZOS).convert('RGBA')
    image.paste(small, (x0, STAR_TOP))
    if tier == 3:   # gold frame continues beside the face and name rows
        draw = ImageDraw.Draw(image)
        draw.rectangle((x0, 60, x0 + 6, STAR_TOP), fill=GOLD + (255,))
        draw.rectangle((x1 - 7, 60, x1 - 1, STAR_TOP), fill=GOLD + (255,))


def lift_premium_swatch(image):
    """Dark navy bag cell (x64-95, y192-223) -> lifted indigo, keeping the per-row shading."""
    for y in range(192, 224):
        for x in range(64, 96):
            r, g, b, a = image.getpixel((x, y))
            if .299 * r + .587 * g + .114 * b < 95:
                image.putpixel((x, y), (min(255, round(r * 1.6)), min(255, round(g * 1.6)), min(255, round(b * 1.6)), a))


def rotate_back(mesh, bb, names, cz, prefix):
    """Append a 180-degree-about-Y copy of the named parts (rotation keeps winding): x -> -x, z -> 2*cz - z."""
    keep = [p for p in mesh['parts'] if not p['name'].startswith(prefix)]
    vertices = mesh['vertices'][:keep[-1]['start'] * 8 + keep[-1]['count'] * 8] if keep else []
    if len(keep) != len(mesh['parts']):
        mesh['parts'] = keep; mesh['vertices'] = vertices
    src = {p['name']: p for p in mesh['parts']}
    for name in names:
        p = src[name]; start = len(mesh['vertices']) // 8
        for i in range(p['start'], p['start'] + p['count']):
            x, y, z, u, v, nx, ny, nz = mesh['vertices'][i * 8:i * 8 + 8]
            mesh['vertices'].extend([-x if x else 0.0, y, round(2 * cz - z, 6), u, v, -nx if nx else 0.0, ny, -nz if nz else 0.0])
        mesh['parts'].append({**p, 'name': prefix + name, 'start': start})
    # editable model: same transform on the matching elements, deterministic ids
    bb['elements'] = [e for e in bb['elements'] if not e['name'].startswith(prefix)]
    root = bb['outliner'][0]
    root['children'] = [c for c in root['children'] if c in {e['uuid'] for e in bb['elements']}]
    for name in names:
        e = next(e for e in bb['elements'] if e['name'] == name)
        copy = json.loads(json.dumps(e)); copy['name'] = prefix + name
        copy['uuid'] = str(uuid.uuid5(uuid.NAMESPACE_URL, f'mcyukkuri/shop_goods_v2/{bb["name"]}/{prefix}{name}'))
        copy['vertices'] = {k: [round(-p[0] + 0.0, 6) or 0.0, p[1], round(2 * cz - p[2], 6)] for k, p in e['vertices'].items()}
        bb['elements'].append(copy); root['children'].append(copy['uuid'])


def set_bb_texture(bb, png):
    bb['textures'][0]['source'] = 'data:image/png;base64,' + base64.b64encode(png).decode()


def do_bag(name, tier):
    folder = SEL / name
    tex = Image.open(folder / 'texture.png').convert('RGBA')
    paint_bag_label(tex, tier)
    if tier == 3:
        lift_premium_swatch(tex)
    png = png_bytes(tex)
    mesh = read_json(folder / f'{name}.mesh.json'); bb = read_json(folder / f'{name}.bbmodel')
    bag = next(p for p in mesh['parts'] if p['name'] == 'sealed_bag')
    zs = [mesh['vertices'][i * 8 + 2] for i in range(bag['start'], bag['start'] + bag['count'])]
    rotate_back(mesh, bb, ['printed_front'], (min(zs) + max(zs)) / 2, 'back_')
    (folder / 'texture.png').write_bytes(png); set_bb_texture(bb, png)
    write_json(folder / f'{name}.mesh.json', mesh); write_json(folder / f'{name}.bbmodel', bb)


def do_bottle(name):
    folder = SEL / name
    mesh = read_json(folder / f'{name}.mesh.json'); bb = read_json(folder / f'{name}.bbmodel')
    labels = [p['name'] for p in mesh['parts'] if p['name'].startswith('label_')]
    rotate_back(mesh, bb, labels, 0.0, 'back_')
    write_json(folder / f'{name}.mesh.json', mesh); write_json(folder / f'{name}.bbmodel', bb)


def do_cord():
    folder = SEL / 'companion_cord'; name = 'companion_cord'
    mesh = read_json(folder / f'{name}.mesh.json'); bb = read_json(folder / f'{name}.bbmodel')
    V = mesh['vertices']
    loop = mesh['parts'][0]
    ys = [V[i * 8 + 1] for i in range(loop['start'], loop['start'] + loop['count'])]
    if max(ys) - min(ys) < .5:      # original tube is ~0.26 thick; skip when already grown
        # one averaged outward normal per position so cap and wall vertices move together (no cracks)
        acc = {}
        for i in range(len(V) // 8):
            key = tuple(round(c, 5) for c in V[i * 8:i * 8 + 3])
            n = acc.setdefault(key, [0.0, 0.0, 0.0])
            for k in range(3): n[k] += V[i * 8 + 5 + k]
        def push(pos):
            n = acc[tuple(round(c, 5) for c in pos)]; m = math.sqrt(sum(c * c for c in n)) or 1
            return [round(pos[k] + CORD_GROW * n[k] / m, 6) for k in range(3)]
        new = list(V)
        for i in range(len(V) // 8):
            new[i * 8:i * 8 + 3] = push(V[i * 8:i * 8 + 3])
        for e in bb['elements']:
            e['vertices'] = {k: push(p) for k, p in e['vertices'].items()}
        mesh['vertices'] = new; V = new
    # palette cells are 32 px: loops -> dark rope cell (col1,row4), knot + tassels -> red cell (col7,row3)
    cells = {'paired_loop': (1, 4), 'knot': (7, 3), 'end': (7, 3)}
    for part in mesh['parts']:
        cx, cy = cells[part['name']]
        for i in range(part['start'], part['start'] + part['count']):
            u, v = V[i * 8 + 3], V[i * 8 + 4]
            V[i * 8 + 3] = round((cx * 32 + round(u * 256, 3) % 32) / 256, 6)
            V[i * 8 + 4] = round((cy * 32 + round(v * 256, 3) % 32) / 256, 6)
    bb_parts = [p for p in mesh['parts']]
    for e, part in zip(bb['elements'], bb_parts):
        cx, cy = cells[part['name']]
        for f in e['faces'].values():
            f['uv'] = {k: [round(cx * 32 + uv[0] % 32, 3), round(cy * 32 + uv[1] % 32, 3)] for k, uv in f['uv'].items()}
    write_json(folder / f'{name}.mesh.json', mesh); write_json(folder / f'{name}.bbmodel', bb)


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def update_selection(names):
    path = HERE / 'selection.json'
    data = read_json(path)
    for entry in data['files'].values():
        snap = ROOT / entry['snapshot']
        if snap.parent.name in names and entry['sha256'] != sha(snap):
            entry['sha256'] = sha(snap)
            entry['edited_by'] = 'art/props/runtime-peripheral/shop_goods_v2.py'
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')


def main():
    argparse.ArgumentParser(description=__doc__).parse_args()
    for name, tier in BAGS.items():
        do_bag(name, tier)
    for name in BOTTLES:
        do_bottle(name)
    do_cord()
    update_selection(set(BAGS) | set(BOTTLES) | {'companion_cord'})
    print('shop goods v2 applied')


if __name__ == '__main__':
    main()
