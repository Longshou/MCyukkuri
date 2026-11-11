"""Pixel textures for the excited-leak visual (project design; pending user review): a sugar-water puddle and a drop.

F37 (wiki/hakkei-reference/lore/entities-sources.md) gives the liquid as sugar water from an opening below the chin;
the pale amber colour, the shapes and the sizes are project art.

puddle: 32x32 for the radial ground splat (UV centre 0.5, radius 0.5, as BodyEffectsRenderer.splat maps it): a lobed
  translucent pool within radius 11 px, a darker deep core, a 1 px rim, two light streaks, and four separate splash
  drops inside radius 14 px so the splat's notched outline never clips them.
drop: 8x8 camera-facing falling drop, pointed top and round bottom, with a catch-light.

    python standards/yukkuri-library/drafts/body-marks/sugar-water/build.py [--check]
"""
from pathlib import Path
import argparse, hashlib, io, json, math
from PIL import Image

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[4]
OUT = ROOT/'src/main/resources/assets/mcyukkuri/textures/effects'
FILL, DEEP, RIM, SHINE = (240, 222, 150, 150), (226, 196, 112, 170), (201, 165, 88, 200), (255, 252, 232, 215)


def puddle():
    image = Image.new('RGBA', (32, 32)); px = image.load(); c = 15.5
    def radius(angle): return 10.2+1.1*math.sin(3*angle+.6)+.7*math.sin(5*angle+2.1)
    for y in range(32):
        for x in range(32):
            dx, dy = x-c, y-c; d = math.hypot(dx, dy); r = radius(math.atan2(dy, dx))
            if d <= r-1: px[x, y] = DEEP if d < r*.45 else FILL
            elif d <= r: px[x, y] = RIM
    for x, y in [(10, 12), (11, 11), (12, 11), (13, 10), (19, 20), (20, 19)]: px[x, y] = SHINE
    for cx, cy, size in [(3, 13, 2), (27, 9, 1), (25, 26, 2), (8, 27, 1)]:
        for y in range(cy, cy+size):
            for x in range(cx, cx+size): px[x, y] = FILL if size > 1 else RIM
        px[cx, cy] = SHINE
    return image


def drop():
    rows = ['...RR...', '...FF...', '..RFFR..', '..FSFF..', '.RFSFFR.', '.RFFFFR.', '..RFFR..', '...RR...']
    image = Image.new('RGBA', (8, 8)); px = image.load(); colours = {'R': RIM, 'F': FILL[:3]+(190,), 'S': SHINE}
    for y, row in enumerate(rows):
        for x, key in enumerate(row):
            if key != '.': px[x, y] = colours[key]
    return image


def outputs():
    files = {}
    for name, image in [('sugar_water_puddle', puddle()), ('sugar_water_drop', drop())]:
        data = io.BytesIO(); image.save(data, format='PNG'); files[OUT/f'{name}.png'] = data.getvalue()
    manifest = {'status': 'pending_user_review', 'origin': 'project design', 'source': 'F37 sugar water (wiki/hakkei-reference/lore/entities-sources.md)',
                'builder': Path(__file__).relative_to(ROOT).as_posix(), 'builder_sha256': hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
                'runtime': {p.relative_to(ROOT).as_posix(): hashlib.sha256(d).hexdigest() for p, d in files.items()}}
    files[HERE/'manifest.json'] = (json.dumps(manifest, indent=2)+'\n').encode()
    return files


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__); parser.add_argument('--check', action='store_true'); args = parser.parse_args()
    for path, data in outputs().items():
        if args.check: assert path.is_file() and path.read_bytes() == data, 'Outdated: '+str(path)
        else: path.write_bytes(data)
    print('sugar-water textures '+('verified' if args.check else 'written'))
