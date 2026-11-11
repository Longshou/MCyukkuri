"""Draft sukkiri faces and father key poses for the proposal page (project design, pending user review).

Writes, next to this file:
  parts/*.png          four new face parts drawn on the standard part grid (15x12 eye, 14x12 mouth)
  <face>.json          draft presets composed from approved parts plus the new ones
  clips/<clip>.json    draft father clips in runtime units, bones by name (preview.py strip --draft-clip)

    python standards/yukkuri-library/drafts/sukkiri/author.py
"""
from pathlib import Path
import json
from PIL import Image

HERE = Path(__file__).resolve().parent
LIB = HERE.parents[1]
PARTS = 'expressions/parts/'
INK, WHITE, IRIS, IRIS_RIM = (72, 53, 46, 255), (255, 248, 233, 255), (50, 41, 35, 255), (118, 82, 57, 255)
LIP, MOUTH, TONGUE = (73, 54, 45, 255), (166, 84, 72, 255), (220, 137, 116, 255)

# Eyes rolled up: the standard blank eye with only the lower edge of the iris left under the upper lid.
ROLLED = ['...............',
          '.....AAAAA.....',
          '....ACCCCCA....',
          '...ABCCCCCBA...',
          '..ABBEEEEEBBA..',
          '..ABBBBBBBBBA..',
          '..ABBBBBBBBBA..',
          '..ABBBBBBBBBA..',
          '..ABBBBBBBBBA..',
          '...ABBBBBBBA...',
          '....ABBBBBA....',
          '.....AAAAA.....']
# A wide laughing mouth, tongue hanging over the lower lip.
GRIN = ['..............',
        '.AAAAAAAAAAAA.',
        '.ABBBBBBBBBBA.',
        '.ABBBBBBBBBBA.',
        '..ABBBBBBBBA..',
        '..ABBCCCCBBA..',
        '...ABCCCCBA...',
        '....ACCCCA....',
        '.....CCCC.....',
        '.....CCCC.....',
        '.....ACCA.....',
        '......AA......']


def draw(rows, palette):
    image = Image.new('RGBA', (len(rows[0]), len(rows)))
    for y, row in enumerate(rows):
        for x, key in enumerate(row):
            if key != '.':
                image.putpixel((x, y), palette[key])
    return image


def lidded(source, share):
    """Heavy upper lid: drop the top share of the eye's rows and close it with a flat line in the eye's darkest ink."""
    image = Image.open(source).convert('RGBA')
    px = image.load()
    rows = [y for y in range(image.height) if any(px[x, y][3] >= 128 for x in range(image.width))]
    cut = max(1, round(len(rows)*share))
    opaque = [px[x, y] for y in rows for x in range(image.width) if px[x, y][3] >= 128]
    ink = min(opaque, key=lambda c: c[0]+c[1]+c[2])
    for y in rows[:cut]:
        for x in range(image.width):
            px[x, y] = (0, 0, 0, 0)
    for x in range(image.width):
        if px[x, rows[cut]][3] >= 128:
            px[x, rows[cut]] = ink
    return image


def part(name):
    return next(p for p in (LIB/'expressions/parts').glob(name+'*.png'))


def preset(base, ident, label, parts, note, geometry=None):
    source = json.loads((LIB/'expressions/presets'/(base+'.json')).read_text(encoding='utf-8'))
    out = dict(id=ident, label=label, group='特殊状态', status='draft', origin='project design',
               recipe=dict(source='draft-composition', base=base, note=note),
               parts={**source['parts'], **parts})
    offsets = source.get('geometry_offsets') if geometry is None else geometry
    if offsets:
        out['geometry_offsets'] = offsets
    (HERE/(ident+'.json')).write_text(json.dumps(out, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')


def both(slot, path):
    return {f'face_{slot}_left': path, f'face_{slot}_right': path}


def faces():
    (HERE/'parts').mkdir(exist_ok=True)
    draw(ROLLED, dict(A=INK, B=WHITE, C=IRIS, E=IRIS_RIM)).save(HERE/'parts/eye_rolled.png')
    draw(GRIN, dict(A=LIP, B=MOUTH, C=TONGUE)).save(HERE/'parts/mouth_grin.png')
    lidded(part('6b26a19b89'), .3).save(HERE/'parts/eye_dazed.png')
    lidded(part('d126c585f0'), .4).save(HERE/'parts/eye_smug.png')
    rel = lambda name: 'drafts/sukkiri/parts/'+name
    std = lambda name: PARTS+part(name).name
    flush, drool, bridge = std('cef8e0a1eb'), std('3ebf6c7bfe'), std('78921e8cff')
    preset('impact', 'flushed_squeeze', '潮红挤眼喊', {**both('blush', flush), 'face_effect_mouth': bridge},
           'release peak: impact eyes, brows and shout on the flushed blush and blush bridge')
    preset('dull', 'dazed_flushed', '失神流口水',
           {**both('eye', rel('eye_dazed.png')), **both('blush', flush), 'face_mouth': std('312577fa8d'),
            'face_effect_mouth': drool},
           'after release: glazed highlight-less eyes under a heavy lid, slack small mouth, drool, flushed')
    preset('smug', 'smug_flushed', '潮红得意', {**both('eye', rel('eye_smug.png')), **both('blush', flush)},
           'afterglow: the approved smug face with half-lowered lids on the flushed blush')
    preset('blank_happy', 'rolled_grin', '翻白眼咧嘴笑',
           {**both('eye', rel('eye_rolled.png')), **both('blush', flush), 'face_mouth': rel('mouth_grin.png'),
            'face_effect_mouth': std('fd1165af5d')},
           'Alice release: eyes rolled up, wide laughing mouth with the tongue out, flushed', geometry={})


# Father key poses. Forward is -z. The lower slices of the soft body carry the push, the top barely moves,
# so the base drives the stroke instead of a whole-body bow (no extra pitch on top of the contact lean).
SLICES = 32


def track(bone, channel, keys):
    return dict(bone=bone, channel=channel, keys=[[round(v, 4) for v in k] for k in keys])


def shear(push):
    """Slice offsets for a push of `push` model units at the base."""
    rows = []
    for i in range(SLICES):
        s = i/(SLICES-1)
        rows.append((f'soft_head_slice_{i}_bend', -push*(1-s)**1.6 + .12*push*s*s))
    return rows


def thrust_clip(ident, period, push):
    """One loop: push out fast (first 40%), ease back."""
    times = [0, .4*period, period]
    amounts = [0, push, 0]
    tracks = []
    for bone, _ in shear(1):
        tracks.append(track(bone, 'position', [[t, 0, 0, dict(shear(a))[bone]] for t, a in zip(times, amounts)]))
    tracks.append(track('rump_sway', 'position', [[t, 0, 0, -.7*a] for t, a in zip(times, amounts)]))
    # Effort along the stroke: a little longer front to back, a little lower (a physical push, not an emotion).
    tracks.append(track('body_stretch', 'scale', [[t, 1, 1-.035*a, 1+.04*a] for t, a in zip(times, amounts)]))
    return dict(id=ident, duration=period, loop=True, tracks=tracks)


def release_clip():
    """20 ticks: deep push (0-2), held stretch with a fine tremble (2-14), slack return (14-20)."""
    keys = [(0, 0, 0, 1.0), (.1, 1.7, 0, 1.0), (.2, 1.7, 2, 1.05), (.3, 1.6, -2, 1.06), (.4, 1.7, 2, 1.06),
            (.5, 1.6, -2, 1.06), (.6, 1.7, 1.5, 1.06), (.7, 1.6, -1.5, 1.05), (.8, 1.2, 0, 1.03),
            (1.0, 0, 0, 1.0)]
    tracks = []
    for bone, _ in shear(1):
        tracks.append(track(bone, 'position', [[t, 0, 0, dict(shear(p))[bone]] for t, p, _, _ in keys]))
    tracks.append(track('rump_sway', 'position', [[t, 0, 0, -.7*p] for t, p, _, _ in keys]))
    tracks.append(track('body_motion', 'rotation', [[t, 0, 0, r] for t, _, r, _ in keys]))
    tracks.append(track('body_stretch', 'scale', [[t, 2-s, s, 2-s] for t, _, _, s in keys]))
    return dict(id='release', duration=1.0, loop=False, tracks=tracks)


def proud_clip():
    """Marisa afterwards: draws up tall, leans back a little and gives one small chest-out bounce (no squash)."""
    keys = [(0, 0, 1.0, 0), (.5, -3, 1.05, 0), (.8, -4, 1.07, .25), (1.0, -4, 1.06, 0), (1.6, -4, 1.06, 0),
            (2.0, -2, 1.03, 0)]
    return dict(id='after_proud', duration=2.0, loop=False, tracks=[
        track('body_motion', 'rotation', [[t, p, 0, 0] for t, p, _, _ in keys]),
        track('body_stretch', 'scale', [[t, (2-s)**.5, s, (2-s)**.5] for t, _, s, _ in keys]),
        track('body_motion', 'position', [[t, 0, h, 0] for t, _, _, h in keys])])


def dazed_clip():
    """Dazed afterglow: slow unfocused yaw sway with a slight sideways list, no squash."""
    keys = [(0, 0, 0), (.6, 6, 2), (1.2, 0, 0), (1.8, -6, -2), (2.4, 0, 0)]
    return dict(id='after_dazed', duration=2.4, loop=True, tracks=[
        track('body_motion', 'rotation', [[t, 0, y, r] for t, y, r in keys])])


def clips():
    out = HERE/'clips'
    out.mkdir(exist_ok=True)
    for clip in (thrust_clip('thrust_slow', .4, 1.0), thrust_clip('thrust_fast', .2, 1.4),
                 release_clip(), proud_clip(), dazed_clip()):
        (out/(clip['id']+'.json')).write_text(json.dumps(clip, indent=1)+'\n', encoding='utf-8')


if __name__ == '__main__':
    faces()
    clips()
