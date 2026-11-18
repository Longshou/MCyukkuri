"""Old/new comparison sheets for redone world props (local preview only; nothing here is shipped).

For each prop ID: its 16x16 inventory icon, the previous runtime mesh (from a Git ref) and the current one at the same
camera, at 1x (fitted to a 16 px slot) and 4x (the same pixels enlarged); larger renders of both from two views; a turned
fixed display (mouth hold, item frame) from the holder's front and side when the item model has one; and both
beside an adult and a baby yukkuri at their real size. Meshes and textures are read from the runtime resources, so run the
exporters first. Uses the care-prop software renderer.

Usage: preview.py <out dir> <prop id>... [--before origin/main] [--fill <prop>:<fill id>:<units>] [--turn <prop>]
Then publish with `scripts/preview.py collection <topic> --source <out dir> --decision ...`.
"""
import argparse, io, json, os, subprocess, sys, tarfile, tempfile
from pathlib import Path
from PIL import Image, ImageDraw

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[3]
sys.path.insert(0, str(HERE.parent / 'care-props'))
sys.path.insert(0, str(ROOT / 'scripts'))
from preview import SHARED, font
# The ground plane uses the local vanilla texture copy of the main checkout unless MCYUKKURI_VANILLA names another.
os.environ.setdefault('MCYUKKURI_VANILLA', str(SHARED / '.local/vanilla-ref/tex/assets/minecraft'))
import render_preview as R

ICONS = ROOT / 'art/props/item-icons'
BG = (60, 64, 70, 255)
LABEL = (235, 235, 235)


def before_tree(ref, workdir):
    """Extract the runtime prop meshes and textures of `ref` into workdir (read-only Git archive)."""
    paths = ['src/main/resources/assets/mcyukkuri/yukkuri_props', 'src/main/resources/assets/mcyukkuri/textures/props']
    data = subprocess.check_output(['git', 'archive', '--format=tar', ref, *paths], cwd=ROOT)
    with tarfile.open(fileobj=io.BytesIO(data)) as archive:
        archive.extractall(workdir, filter='data')
    return R.Source(workdir)


def shot(source, items, size, az=225, el=28, lock=None):
    """Render items into exactly `size` pixels; the frame is fitted to the items (or `lock`)."""
    rows = [(*item[:6], 0, source) if len(item) == 6 else item for item in items]
    probe, frame = R.render(source, rows, az=az, el=el, ppu=8, lock=lock)
    w, h = size
    span = max((frame[1] - frame[0]) / w, (frame[3] - frame[2]) / h) * 1.08
    cx, cy = (frame[0] + frame[1]) / 2, (frame[2] + frame[3]) / 2
    frame = (cx - span * w / 2, cx + span * w / 2, cy - span * h / 2, cy + span * h / 2)
    image, _ = R.render(source, rows, az=az, el=el, ppu=1 / span, size=size, lock=frame, pad=1)
    return image, frame


def on(bg, image):
    base = Image.new('RGBA', image.size, BG)
    base.alpha_composite(image)
    return base


def pad(image, width):
    out = Image.new('RGBA', (max(width, image.width), image.height), BG)
    out.paste(image, ((out.width - image.width) // 2, 0))
    return out


def label(image, text, height=26):
    out = Image.new('RGBA', (image.width, image.height + height), BG)
    out.paste(image, (0, height))
    ImageDraw.Draw(out).text((4, 2), text, fill=LABEL, font=font(16))
    return out


def row(images, gap=12):
    out = Image.new('RGBA', (sum(i.width for i in images) + gap * (len(images) - 1), max(i.height for i in images)), BG)
    x = 0
    for image in images:
        out.paste(image, (x, 0))
        x += image.width + gap
    return out


def column(images, gap=16):
    out = Image.new('RGBA', (max(i.width for i in images), sum(i.height for i in images) + gap * (len(images) - 1)), BG)
    y = 0
    for image in images:
        out.paste(image, (0, y))
        y += image.height + gap
    return out


def sheet(name, old, new, fills, turned=False):
    icon = Image.open(ICONS / f'{name}.png').convert('RGBA')
    has_old = (old.assets / f'yukkuri_props/{name}.json').exists()
    sources = [('旧', old)] * has_old + [('新', new)]
    extra = fills.get(name, [])
    def items(src):
        return [(name, 8, 0, 0, 0, 1)] + [(fill, units, 0, 0, 0, 1) for fill, units in extra]
    # 1x: the model fitted into a 16 px slot, the way a dropped item or shelf good reads from a few blocks away.
    native = [('图标 1×', on(BG, icon))] + [(f'{tag} 1×', on(BG, shot(src, items(src), (16, 16))[0])) for tag, src in sources]
    small = row([label(pad(i, 64), t, 22) for t, i in native], 10)
    large = row([label(i.resize((64, 64), Image.NEAREST), t.replace('1×', '4×'), 22) for t, i in native], 10)
    views = []
    for az, el, what in ((225, 28, '斜上'), (180, 8, '正面')):
        frame = None
        tiles = []
        for tag, src in sources:      # same frame for old and new: the union of both
            _, f = shot(src, items(src), (320, 260), az, el)
            frame = f if frame is None else (min(frame[0], f[0]), max(frame[1], f[1]), min(frame[2], f[2]), max(frame[3], f[3]))
        for tag, src in sources:
            image, _ = shot(src, items(src), (320, 260), az, el, lock=frame)
            tiles.append(label(on(BG, image), f'{tag} · {what}'))
        views.append(row(tiles))
    # A turned fixed display (mouth hold, item frame): the new item model's turn, seen from the holder's front and side.
    model = json.loads((new.assets / f'models/item/{name}.json').read_text(encoding='utf-8'))
    turn = model.get('display', {}).get('fixed', {}).get('rotation', [0, 0, 0])[1]
    if turn:
        views.append(row([label(on(BG, shot(new, [(name, 8, 0, 0, 0, 1, turn, new)], (320, 260), az, 8)[0]),
                                f'新 · 固定展示转 {turn:g}° · {what}') for az, what in ((180, '持有者正面'), (270, '持有者侧面'))]))
    if turned:      # a pushed toy slides without turning, so the player sees it from every side: 1x and 4x each 45 degrees
        tiles = [on(BG, shot(new, [(name, 8, 0, 0, 0, 1, yaw, new)], (16, 16))[0]) for yaw in range(0, 360, 45)]
        views.append(label(row([row([pad(t, 24) for t in tiles], 2), row([t.resize((64, 64), Image.NEAREST) for t in tiles], 6)], 24),
                           '新 · 每 45° 一面（1× 与 4×）'))
    world = []
    frame = None
    def scene(src):
        return [('ground:20', 0, 0, 0, 0, 1, 0, src), ('yuk:reimu:neutral', 0, -15, 0, 2, 1, 20, src),
                ('yuk:reimu:neutral', 0, 14, 0, 4, .42, -20, src)] + [(*i, 0, src) for i in items(src)]
    for tag, src in sources:
        _, f = R.render(src, scene(src), az=200, el=22, ppu=8)
        frame = f if frame is None else (min(frame[0], f[0]), max(frame[1], f[1]), min(frame[2], f[2]), max(frame[3], f[3]))
    for tag, src in sources:
        image, _ = shot(src, scene(src), (420, 240), 200, 22, lock=frame)
        world.append(label(on(BG, image), f'{tag} · 与成体、幼体同尺度'))
    title = Image.new('RGBA', (600, 30), BG)
    ImageDraw.Draw(title).text((4, 4), name, fill=LABEL, font=font(20))
    return column([title, row([small, large], 40), *views, row(world)])


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('out')
    parser.add_argument('props', nargs='+')
    parser.add_argument('--before', default='origin/main', help='Git ref holding the previous runtime meshes')
    parser.add_argument('--fill', action='append', default=[], metavar='PROP:FILL:UNITS',
                        help='also draw a fill mesh with the prop, e.g. food_bowl:food_bowl_regular:8')
    parser.add_argument('--turn', action='append', default=[], metavar='PROP', help='also show this prop from eight sides (rolling toys)')
    args = parser.parse_args()
    fills = {}
    for spec in args.fill:
        prop, fill, units = spec.split(':')
        fills.setdefault(prop, []).append((fill, int(units)))
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory() as work:
        old, new = before_tree(args.before, work), R.Source(ROOT)
        for name in args.props:
            path = out / f'{name}.png'
            sheet(name, old, new, fills, name in args.turn).convert('RGB').save(path)
            print(path)


if __name__ == '__main__':
    main()
