"""In-use sheets for the playground equipment (local preview only; nothing here is shipped).

For each facility, old (a Git ref) and current at the same camera, with an adult and a young yukkuri where play puts
them (tunnel floor, slide deck and chute, trampoline bed, tub seats), from the entrance side and from the far side.
The trampoline adds its bed pressed 1 and 2 units (unit 2 drawn lower, as PlaygroundRenderer does) with the adult
sunk with it, close up; the tub adds every juice level 0-4 (yukkuri_pool_juice units 1..k) with an adult on the
floor and a young one on the bench, and two adults side by side. Pair with block-props/preview.py for the icon,
1x/4x and two-view rows.

Usage: preview.py <out dir> [--before origin/main]
"""
import argparse, importlib.util, json, tempfile
from pathlib import Path
from PIL import Image

HERE = Path(__file__).resolve().parent
_spec = importlib.util.spec_from_file_location('block_props_preview', HERE.parent / 'block-props/preview.py')
P = importlib.util.module_from_spec(_spec)    # block-props/preview.py: sheet helpers, render_preview as P.R
_spec.loader.exec_module(P)

R = P.R
ADULT, YOUNG = 'yuk:reimu:neutral', 'yuk:marisa:neutral'
YOUNG_SCALE = .42
# (x, y, z, scale, yaw) in model units at the current heights; the old models keep their own route heights.
USE = {
    'ceramic_tunnel': {'old': [(0, 2, -4, 1, 0), (0, 2, 12, YOUNG_SCALE, 180)], 'new': [(0, 3, -4, 1, 0), (0, 3, 12, YOUNG_SCALE, 180)]},
    'yukkuri_slide': {'old': [(0, 16, -1, 1, 0), (0, 9, 18, YOUNG_SCALE, 0)], 'new': [(0, 16, -1, 1, 0), (0, 9, 18, YOUNG_SCALE, 0)]},
    'yukkuri_trampoline': {'old': [(-4, 8, 0, 1, 20), (9, 8, 6, YOUNG_SCALE, -30)], 'new': [(-4, 8, 0, 1, 20), (9, 8, 6, YOUNG_SCALE, -30)]},
    'yukkuri_pool': {'old': [(-15, 6, 0, 1, 90), (-15, 0, 16, YOUNG_SCALE, 0)], 'new': None},
}


def tub_seats(src):
    """Seats of the tub from its exported data: (x, y, z, scale, yaw) for one adult and one young body."""
    data = json.loads((src.root / 'src/main/resources/data/mcyukkuri/playgrounds/yukkuri_pool.json').read_text(encoding='utf-8'))['tub']
    seats = {s['id']: s for s in data['seats']}
    return seats, data


class Split(R.Source):
    """A Source that can also load one prop's parts by unit: '<name>@<unit>' keeps only that unit's parts."""

    def load(self, name):
        if '@' not in name:
            return super().load(name)
        base, unit = name.split('@')
        data, verts = super().load(base)
        return {**data, 'parts': [p for p in data['parts'] if p.get('unit', 0) == int(unit)]}, verts

    def tex_of(self, name, part):
        return super().tex_of(name.split('@')[0], part)


def bodies(spots, src, dy=0):
    return [(ADULT if s == 1 else YOUNG, 0, x, y + dy, z, s, yaw, src) for x, y, z, s, yaw in spots]


def scene(src, name, spots, pressed=0, level=0):
    bounds = src.load(name)[0]['bounds']
    reach = max(abs(v) for v in bounds[0] + bounds[2])
    rows = [(f'ground:{reach + 4:g}', 0, 0, 0, 0, 1, 0, src)]
    if pressed:
        rows += [(f'{name}@0', 99, 0, 0, 0, 1, 0, src), (f'{name}@2', 99, 0, -pressed, 0, 1, 0, src)]
    else:
        rows.append((name, 99, 0, 0, 0, 1, 0, src))
    if level:
        rows.append(('yukkuri_pool_juice', level, 0, 0, 0, 1, 0, src))
    return rows + bodies(spots, src, -pressed)


SIZE = (520, 380)


def frame_of(scenes, az, el):
    frame = None
    for rows in scenes:
        _, f = R.render(rows[0][7], rows, az=az, el=el, ppu=8)
        frame = f if frame is None else (min(frame[0], f[0]), max(frame[1], f[1]), min(frame[2], f[2]), max(frame[3], f[3]))
    return frame


def tiles(labelled, az, el, size=SIZE):
    """[(label, scene rows)] rendered at one camera and one frame."""
    frame = frame_of([rows for _, rows in labelled], az, el)
    return P.row([P.label(P.on(P.BG, P.shot(rows[0][7], rows, size, az, el, lock=frame)[0]), text) for text, rows in labelled])


def title(text):
    image = Image.new('RGBA', (900, 30), P.BG)
    P.ImageDraw.Draw(image).text((4, 4), text, fill=P.LABEL, font=P.font(20))
    return image


def sheet(name, old, new):
    spots = USE[name]
    if name == 'yukkuri_pool':
        seats, data = tub_seats(new)
        adult, bench = seats['adult_alone'], seats['small_bench_right']
        spots = {**spots, 'new': [(adult['x'], adult['y'], adult['z'], 1, 25), (bench['x'], bench['y'], bench['z'], YOUNG_SCALE, 10)]}
    pairs = [('旧', old, spots['old']), ('新', new, spots['new'])]
    rows = [title(f'{name} · 使用中')]
    for az, el, what in ((210, 24, '入口侧'), (30, 24, '对侧')):
        rows.append(tiles([(f'{tag} · {what}', scene(src, name, s)) for tag, src, s in pairs], az, el))
    if name == 'yukkuri_trampoline':
        centre = [(0, 8, 0, 1, 20)]
        rows.append(tiles([(f'新 · 床面压下 {k} 单位' + (' · 成体随床面下沉（需代码）' if k else ' · 静止'), scene(new, name, centre, k))
                           for k in (0, 1, 2)], 200, 32, (420, 300)))
        rows.append(tiles([(f'新 · 床面压下 {k} 单位 · 无人', scene(new, name, [], k)) for k in (0, 1, 2)], 200, 40, (420, 260)))
    if name == 'yukkuri_pool':
        seats, data = tub_seats(new)
        a, b, small = seats['adult_left'], seats['adult_right'], seats['small_bench_right']
        pair = [(a['x'], a['y'], a['z'], 1, 25), (small['x'], small['y'], small['z'], YOUNG_SCALE, 15)]
        rows.append(tiles([(f'新 · 橙汁 {k} 档' + (f'（液面 {data["levels"][k - 1]:g}）' if k else '（空）'), scene(new, name, pair, level=k))
                           for k in range(5)], 200, 30, (330, 250)))
        two = [(a['x'], a['y'], a['z'], 1, 20), (b['x'], b['y'], b['z'], 1, -15)]
        rows.append(tiles([(f'新 · 两只成体 · 橙汁 {k} 档', scene(new, name, two, level=k)) for k in (2, 4)]
                          + [('新 · 侧面 · 橙汁 4 档', scene(new, name, pair, level=4))], 160, 14, (420, 280)))
    return P.column(rows)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('out')
    parser.add_argument('--before', default='origin/main')
    args = parser.parse_args()
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory() as work:
        P.before_tree(args.before, work)
        old, new = Split(work), Split(P.ROOT)
        for name in USE:
            path = out / f'{name}_use.png'
            sheet(name, old, new).convert('RGB').save(path)
            print(path)


if __name__ == '__main__':
    main()
