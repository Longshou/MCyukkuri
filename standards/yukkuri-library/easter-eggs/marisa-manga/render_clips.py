"""Render the motions of the manga Marisa frame by frame (headless).

  D:/Blender/blender.exe -b <out>/marisa_manga.blend --python render_clips.py -- <out> [--only hop,eat] [--size 960x540]
Writes <out>/clips/<clip>/f####.png (every look in variants.json, three-quarter front view).
Then run clip_sheets.py <out> for the animated WebP and beat strips.
"""
import bpy, json, os, sys
from mathutils import Vector

HERE = os.path.dirname(os.path.abspath(__file__))
args = sys.argv[sys.argv.index('--') + 1:]
OUT = args[0]
ONLY = args[args.index('--only') + 1].split(',') if '--only' in args else None
W, H = (int(x) for x in (args[args.index('--size') + 1] if '--size' in args else '960x540').split('x'))

g = {'__file__': os.path.join(HERE, 'stage.py'), '__name__': 'marisa_stage'}
exec(open(os.path.join(HERE, 'stage.py'), encoding='utf-8').read(), g)
cam = g['stage']()
aim, VARIANTS = g['aim'], g['VARIANTS']

sc = bpy.context.scene
sc.render.resolution_x, sc.render.resolution_y = W, H
sc.eevee.taa_render_samples = 24
spans = json.loads(sc['marisa_clips'])
mid = sum((Vector(v['offset']) for v in VARIANTS.values()), Vector()) / len(VARIANTS)
span = max(abs(v['offset'][0]) for v in VARIANTS.values()) * 2 + max(v['hat']['brim_rx'] for v in VARIANTS.values()) * 2
Hmax = max(v['H'] for v in VARIANTS.values())
for name, c in spans.items():
    if ONLY and name not in ONLY:
        continue
    travel = {'hop': -0.32, 'crawl': -0.37}.get(name, 0.0) * Hmax  # half the clip's forward travel
    target = mid + Vector((0, travel, Hmax * 0.75))
    aim(cam, target + Vector((0.55, -0.8, 0.22)).normalized() * (span * 2.6 + Hmax * 2.0), target, 66)
    d = os.path.join(OUT, 'clips', name)
    os.makedirs(d, exist_ok=True)
    for fr in range(c['start'], c['end'] + 1):
        sc.frame_set(fr)
        sc.render.filepath = os.path.join(d, 'f%04d.png' % (fr - c['start']))
        bpy.ops.render.render(write_still=True)
    json.dump(c, open(os.path.join(d, 'clip.json'), 'w', encoding='utf-8'))
    print('RENDERED', name, c['end'] - c['start'] + 1)
