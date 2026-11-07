"""Builds the grave marker: a small smooth-stone headstone a player's burial leaves at the head end of the mound.

Project-original shape (direction picked from a concept sheet and two rendered candidates, stone over a wooden post).
The stone faces sample the vanilla smooth_stone texture in place (one texel per 1/16 block, interior texels only); the
carved text lines are this project's own 32x32 texture at two texels per 1/16 block, on a quad just in front of the face.
Model frame: 16 units = one block, origin at the marker's ground centre, front facing -X (toward the mound centre and the
burier), width along Z; the base is sunk one unit. client/GraveRenderer places it with body/GraveBlock#markerOffset and
#markerScale, and GraveBlock's outline box uses the same extents (half thickness 1, half width 2, height 6.5).
Outputs:
  src/main/resources/assets/mcyukkuri/yukkuri_props/grave_marker.json
  src/main/resources/assets/mcyukkuri/textures/props/grave_marker.png
  src/main/resources/assets/mcyukkuri/yukkuri_props/index.json   (the 'grave_marker' entry, owner grave_marker)
Usage: python grave_marker.py [--check] [--preview]   (--preview writes the main checkout's .local/previews/grave-marker/)
"""
import io
import json
import os
import sys
from pathlib import Path

from PIL import Image

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[3]
ASSETS = ROOT / 'src/main/resources/assets/mcyukkuri'
MAIN = ROOT.parents[1] if ROOT.parent.name == '.worktrees' else ROOT
STONE = 'minecraft:textures/block/smooth_stone.png'
CARVED = (96, 96, 100, 255)
# (low corner, high corner) in units: the slab, a narrower shoulder and the cap make a stepped round top.
BOXES = [((-1, -1, -2), (1, 5, 2)), ((-1, 5, -1.5), (1, 6, 1.5)), ((-1, 6, -1), (1, 6.5, 1))]
FRONT = -1.04                      # carved-line quad, just in front of the -X face
LINES_TOP = 6.5                    # first row of LINES spans y 6.0..6.5; column c is z = -2 + c/2
LINES = ['........',
         '..####..',               # the name line, shortest
         '........',
         '.#####..',
         '........',
         '..#####.',
         '........',
         '.####...']


def stone_uv(axis, p):
    """Absolute mapping so the slab, shoulder and cap read as one stone: texel (8+z, 12-y) on the X faces."""
    x, y, z = p
    if axis == 0: return (8 + z) / 16, (12 - y) / 16
    if axis == 2: return (8 + x) / 16, (12 - y) / 16
    return (8 + z) / 16, (8 + x) / 16


def lines_uv(p):
    return (16 + 2 * p[2]) / 32, (24 - 2 * p[1]) / 32


def number(value):
    value = round(float(value), 6)
    return 0.0 if value == 0 else value


def quad(corners, normal, uv):
    rows = []
    for k in (0, 1, 2, 0, 2, 3):
        u, v = uv(corners[k])
        rows += [number(c) for c in corners[k]] + [number(u), number(v)] + [number(n) for n in normal]
    return rows


def box(lo, hi):
    (x0, y0, z0), (x1, y1, z1) = lo, hi
    faces = [([(x0, y0, z0), (x0, y0, z1), (x0, y1, z1), (x0, y1, z0)], (-1, 0, 0), 0),
             ([(x1, y0, z1), (x1, y0, z0), (x1, y1, z0), (x1, y1, z1)], (1, 0, 0), 0),
             ([(x1, y0, z0), (x0, y0, z0), (x0, y1, z0), (x1, y1, z0)], (0, 0, -1), 2),
             ([(x0, y0, z1), (x1, y0, z1), (x1, y1, z1), (x0, y1, z1)], (0, 0, 1), 2),
             ([(x0, y1, z0), (x0, y1, z1), (x1, y1, z1), (x1, y1, z0)], (0, 1, 0), 1),
             ([(x0, y0, z1), (x0, y0, z0), (x1, y0, z0), (x1, y0, z1)], (0, -1, 0), 1)]
    rows = []
    for corners, normal, axis in faces:
        rows += quad(corners, normal, lambda p, a=axis: stone_uv(a, p))
    return rows


def lines_texture():
    image = Image.new('RGBA', (32, 32), (0, 0, 0, 0))
    for r, line in enumerate(LINES):
        for c, mark in enumerate(line):
            if mark == '#':
                z, y = -2 + c / 2, LINES_TOP - (r + 1) / 2
                image.putpixel((int(16 + 2 * z), int(24 - 2 * y - 1)), CARVED)
    out = io.BytesIO()
    image.save(out, format='PNG')
    return out.getvalue()


def outputs():
    body = [v for lo, hi in BOXES for v in box(lo, hi)]
    carved = quad([(FRONT, 1, -2), (FRONT, 1, 2), (FRONT, LINES_TOP, 2), (FRONT, LINES_TOP, -2)], (-1, 0, 0), lines_uv)
    vertices = body + carved
    bounds = [[min(vertices[i::8]), max(vertices[i::8])] for i in range(3)]
    mesh = {'format': 1, 'units_per_block': 16, 'id': 'grave_marker', 'vertices': vertices,
            'parts': [{'name': 'stone', 'start': 0, 'count': len(body) // 8, 'texture': STONE},
                      {'name': 'carved_lines', 'start': len(body) // 8, 'count': len(carved) // 8}],
            'bounds': bounds}
    index_path = ASSETS / 'yukkuri_props/index.json'
    index = json.loads(index_path.read_text(encoding='utf-8'))
    index['grave_marker'] = {'bounds': bounds, 'triangles': len(vertices) // 24, 'owner': 'grave_marker'}
    dump = lambda data: (json.dumps(data, ensure_ascii=False, separators=(',', ':')) + '\n').encode('utf-8')
    return {ASSETS / 'yukkuri_props/grave_marker.json': dump(mesh), index_path: dump(index),
            ASSETS / 'textures/props/grave_marker.png': lines_texture()}


def same(path, data):
    if not path.is_file():
        return False
    if path.suffix == '.png':
        return Image.open(path).convert('RGBA').tobytes() == Image.open(io.BytesIO(data)).convert('RGBA').tobytes()
    return path.read_bytes().replace(b'\r\n', b'\n') == data


def preview():
    """Adult mound views (close, 4 and 7 blocks) and the four body sizes, rendered from the exported files."""
    os.environ.setdefault('MCYUKKURI_VANILLA', str(MAIN / '.local/vanilla-ref/tex/assets/minecraft'))
    sys.path[:0] = [str(HERE.parent / 'care-props'), str(ROOT / 'scripts')]
    import numpy as np
    from PIL import ImageDraw, ImageOps
    from render_preview import Source, render
    from build_preview_hub import font

    class Ground(Source):
        def load(self, name):
            if name != 'grass':
                return super().load(name)
            h, k = 20.0, 2.5
            rows = [[-h, -.01, -h, 0, 0, 0, 1, 0], [-h, -.01, h, 0, k, 0, 1, 0], [h, -.01, h, k, k, 0, 1, 0],
                    [-h, -.01, -h, 0, 0, 0, 1, 0], [h, -.01, h, k, k, 0, 1, 0], [h, -.01, -h, k, 0, 0, 1, 0]]
            return ({'parts': [{'name': 'g', 'start': 0, 'count': 6, 'texture': 'minecraft:textures/block/grass_block_top.png',
                                'tint': [124, 189, 107]}], 'bounds': [[-h, h], [0, 0], [-h, h]]}, np.array(rows, dtype=np.float32))

    def grave(scale):   # body/GraveBlock#markerOffset and #markerScale
        return [('burial_mound', 8, 0, 0, 0, scale), ('grave_marker', 8, min(6.8 * scale + .1, 6.9), 0, 0, min(max(scale, .5), 1))]

    source, background = Ground(ROOT), (200, 220, 235, 255)
    out = MAIN / '.local/previews/grave-marker'
    out.mkdir(parents=True, exist_ok=True)
    sheet = Image.new('RGB', (4 * 380, 2 * 420 + 60), '#f3f0e8')
    draw = ImageDraw.Draw(sheet)
    views = [('近看 · 安葬者一侧', 250, 25, None), ('近看 · 侧后', 140, 30, None), ('约 4 格远', 245, 22, 12), ('约 7 格远', 245, 20, 7)]
    for col, (title, az, el, ppu) in enumerate(views):
        if ppu is None:
            image, _ = render(source, grave(1.0), az=az, el=el, ppu=26, bg=background)
            image = ImageOps.contain(image, (360, 360))
        else:
            image, _ = render(source, [('grass', 8, 0, 0, 0, 1)] + grave(1.0), az=az, el=el, ppu=ppu, size=(360, 360), bg=background)
        sheet.paste(image, (col * 380 + 10, 60), image)
        draw.text((col * 380 + 14, 424), f'成体 1.0 · {title}', font=font(20), fill='#253c35')
    for col, scale in enumerate((.15, .5, 1.0, 1.5)):
        image, _ = render(source, grave(scale), az=245, el=28, ppu=18, bg=background)
        image = ImageOps.contain(image, (360, 360)) if max(image.size) > 360 else image
        sheet.paste(image, (col * 380 + 10, 480), image)
        draw.text((col * 380 + 14, 844), f'体型 {scale}', font=font(20), fill='#253c35')
    draw.text((14, 14), '小墓碑（选定：平滑石碑）：立在土冢头端、正面朝向安葬者；下排为赤体／子体／成体／灵胖的土冢', font=font(24), fill='#253c35')
    sheet.save(out / 'grave-marker.png')
    print('wrote', out / 'grave-marker.png')


def main():
    files = outputs()
    if '--check' in sys.argv:
        stale = [path.relative_to(ROOT).as_posix() for path, data in files.items() if not same(path, data)]
        print('\n'.join(stale) if stale else 'grave marker outputs are current')
        sys.exit(1 if stale else 0)
    for path, data in files.items():
        if not same(path, data):
            path.write_bytes(data)
            print('wrote', path.relative_to(ROOT).as_posix())
    if '--preview' in sys.argv:
        preview()


if __name__ == '__main__':
    main()
