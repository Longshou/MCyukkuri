"""In-use sheets for the playground equipment (local preview only; nothing here is shipped).

For each facility, old (a Git ref) and current at the same camera: an adult and a baby yukkuri standing at the PlayLife
route points (tunnel floor, slide deck and chute, trampoline bed, pool side step), from the entrance side and from
the far side. The trampoline also gets its bed pressed down 2 units (unit 2 drawn lower, as the renderer will) beside
the rest pose, so a gap or a spring would show. Pair with block-props/preview.py for the icon, 1x/4x and two-view rows.

Usage: preview.py <out dir> [--before origin/main]
"""
import argparse, importlib.util, tempfile
from pathlib import Path
from PIL import Image

HERE = Path(__file__).resolve().parent
_spec = importlib.util.spec_from_file_location('block_props_preview', HERE.parent / 'block-props/preview.py')
P = importlib.util.module_from_spec(_spec)    # block-props/preview.py: sheet helpers, render_preview as P.R
_spec.loader.exec_module(P)

R = P.R
ADULT, BABY = 'yuk:reimu:neutral', 'yuk:marisa:neutral'
# (prop, [(x, y, z, scale, yaw)]) in model units: route points of PlayLife at the current heights.
USE = {
    'ceramic_tunnel': [(0, 2, -4, 1, 0), (0, 2, 12, .42, 180)],
    'yukkuri_slide': [(0, 16, -1, 1, 0), (0, 9, 18, .42, 0)],
    'yukkuri_trampoline': [(-4, 8, 0, 1, 20), (9, 8, 6, .42, -30)],
    'yukkuri_pool': [(-15, 6, 0, 1, 90), (-15, 0, 16, .42, 0)],
}


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


def scene(src, name, pressed=0):
    bounds = src.load(name)[0]['bounds']
    top, reach = bounds[1][1], max(abs(v) for v in bounds[0] + bounds[2])
    lift = 0 if name != 'yukkuri_trampoline' or top > 7 else -3        # bodies on the old bed (top 5) for the old model
    rows = [(f'ground:{reach + 4:g}', 0, 0, 0, 0, 1, 0, src)]
    if pressed:
        rows += [(f'{name}@0', 99, 0, 0, 0, 1, 0, src), (f'{name}@2', 99, 0, -pressed, 0, 1, 0, src)]
    else:
        rows.append((name, 99, 0, 0, 0, 1, 0, src))
    for x, y, z, s, yaw in USE[name]:
        dy = lift - (pressed if name == 'yukkuri_trampoline' and y == 8 else 0)
        rows.append((ADULT if s == 1 else BABY, 0, x, y + dy, z, s, yaw, src))
    return rows


SIZE = (520, 380)


def tile(sources, name, az, el, what, pressed=0):
    frame = None
    for _, src in sources:
        _, f = R.render(src, scene(src, name), az=az, el=el, ppu=8)
        frame = f if frame is None else (min(frame[0], f[0]), max(frame[1], f[1]), min(frame[2], f[2]), max(frame[3], f[3]))
    out = []
    for tag, src in sources:
        image, _ = P.shot(src, scene(src, name), SIZE, az, el, lock=frame)
        out.append(P.label(P.on(P.BG, image), f'{tag} · {what}'))
    if pressed:
        src = sources[-1][1]
        image, _ = P.shot(src, scene(src, name, pressed), SIZE, az, el, lock=frame)
        out.append(P.label(P.on(P.BG, image), f'新 · 床面压下 {pressed} 单位 · {what}'))
    return P.row(out)


def sheet(name, old, new):
    sources = [('旧', old), ('新', new)]
    pressed = 2 if name == 'yukkuri_trampoline' else 0
    title = Image.new('RGBA', (600, 30), P.BG)
    P.ImageDraw.Draw(title).text((4, 4), f'{name} · 使用中', fill=P.LABEL, font=P.font(20))
    return P.column([title, tile(sources, name, 210, 24, '入口侧', pressed), tile(sources, name, 30, 24, '对侧', pressed)])


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
