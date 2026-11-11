"""Draft sukkiri faces and father key poses for the proposal page (project design, pending user review).

Writes, next to this file:
  parts/sukkiri_*.png  new face parts drawn on the standard part grid (15x12 eye, 14x12 mouth)
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
MOUTH, TONGUE = (166, 84, 72, 255), (220, 137, 116, 255)

# New components, named sukkiri_* so they cannot collide with other packages' parts. References (wiki/art-references):
# EH27-007 lower panel (flat upper lid over the pupil, small soft mouth), EH17-002 top panel (":3" smug mouth, blush),
# M02-003 (lidded smug Marisa). Long hanging tongues and mouth-side drool were rejected by the user.
PALETTE = dict(A=INK, B=WHITE, C=IRIS, E=IRIS_RIM, F=(150, 112, 84, 255), G=(196, 158, 122, 255), M=MOUTH, T=TONGUE, L=(240, 170, 150, 255))
ART = {
    # Eyes rolled up: the blank eye with only the lower edge of the iris left under the upper lid.
    'eye_rolled': ['...............',
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
                   '.....AAAAA.....'],
    # Smug: a flat, slightly flicked upper lid sitting on the pupil, the highlight kept (EH27-007, M02-003).
    'eye_smug': ['...............',
                 '...............',
                 '...............',
                 '...............',
                 '..AAAAAAAAAA...',
                 '...ACCCCCCCA...',
                 '...ACBBCCCCA...',
                 '...ACBCCCCCA...',
                 '....ACCCCBA....',
                 '.....EEEEA.....',
                 '......AAA......',
                 '...............'],
    # Dazed: heavy lids, the pupil drifted up under the lid, a lighter lower iris and a small low shine (blissed out).
    'eye_dazed': ['...............',
                 '...............',
                 '...............',
                 '...............',
                 '...............',
                 '...AAAAAAAAA...',
                 '...AFFCCCFFA...',
                 '...AGGGGGGGA...',
                 '...AGGGGGBGA...',
                 '....AGGGGGA....',
                 '.....AAAAA.....',
                 '...............'],
    # A small rounded open mouth with a short round tongue tip poking out past the lip (no outline under the tip).
    'mouth_tongue': ['..............',
                    '..............',
                    '..............',
                    '....AAAAAA....',
                    '...AMMMMMMA...',
                    '...AMMMMMMA...',
                    '....AATTAA....',
                    '....ATTLTA....',
                    '.....TTTT.....',
                    '..............',
                    '..............',
                    '..............'],
    # Slack, softly open small mouth; no drool.
    'mouth_slack': ['..............',
                   '..............',
                   '..............',
                   '.....AAAA.....',
                   '....AMMMMA....',
                   '....AMMMMA....',
                   '....AMTTMA....',
                   '.....AAAA.....',
                   '..............',
                   '..............',
                   '..............',
                   '..............'],
    # The yukkuri ":3" smug mouth (EH17-002), closed.
    'mouth_cat': ['..............',
                  '..............',
                  '..............',
                  '..............',
                  '..A...AA...A..',
                  '..A..A..A..A..',
                  '...AA....AA...',
                  '..............',
                  '..............',
                  '..............',
                  '..............',
                  '..............'],
    # A wide, round-bottomed laughing mouth; the tongue stays inside.
    'mouth_grin': ['..............',
                  '.AAAAAAAAAAAA.',
                  '.AMMMMMMMMMMA.',
                  '.AMMMMMMMMMMA.',
                  '.AMMMMMMMMMMA.',
                  '..AMMTTTTMMA..',
                  '..AMTTLTTTMA..',
                  '...ATTTTTTA...',
                  '....AAAAAA....',
                  '..............',
                  '..............',
                  '..............'],
}


def draw(rows, palette):
    image = Image.new('RGBA', (len(rows[0]), len(rows)))
    for y, row in enumerate(rows):
        for x, key in enumerate(row):
            if key != '.':
                image.putpixel((x, y), palette[key])
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
    for old in list(HERE.glob('*.json'))+list((HERE/'parts').glob('*.png')):
        old.unlink()
    for name, rows in ART.items():
        image = draw(rows, PALETTE)
        image.save(HERE/f'parts/sukkiri_{name}.png')
        if name.startswith('eye_'):
            image.transpose(Image.Transpose.FLIP_LEFT_RIGHT).save(HERE/f'parts/sukkiri_{name}_r.png')
    rel = lambda name: 'drafts/sukkiri/parts/sukkiri_'+name+'.png'
    eyes = lambda name: {'face_eye_left': rel(name), 'face_eye_right': rel(name+'_r')}
    std = lambda name: PARTS+part(name).name
    flush, bridge, blank = std('cef8e0a1eb'), std('78921e8cff'), std('fd1165af5d')
    preset('impact', 'sukkiri_squeeze', '潮红挤眼喊', {**both('blush', flush), 'face_effect_mouth': bridge},
           'release peak: impact eyes, brows and shout on the flushed blush and blush bridge')
    preset('flushed_tongue', 'sukkiri_tongue', '潮红闭眼吐舌尖', {'face_mouth': rel('mouth_tongue')},
           'flushed_tongue with a short rounded tongue tip instead of the long hanging tongue')
    preset('teary_tongue', 'sukkiri_teary_tongue', '含泪吐舌尖', {'face_mouth': rel('mouth_tongue')},
           'teary_tongue with the same short tongue tip')
    preset('open_smile', 'sukkiri_dazed', '失神', {**eyes('eye_dazed'), **both('blush', flush),
           'face_mouth': rel('mouth_slack'), 'face_effect_mouth': blank},
           'dazed: lowered lid, unfocused soft iris without highlight, slack small mouth, flushed; no drool', geometry={})
    preset('smug', 'sukkiri_smug', '潮红得意', {**eyes('eye_smug'), **both('blush', flush),
           'face_mouth': rel('mouth_cat'), 'face_effect_mouth': blank},
           'afterglow smug: flat lid on the pupil, the yukkuri :3 mouth, flushed', geometry={})
    preset('blank_happy', 'sukkiri_grin', '翻白眼咧嘴笑', {**eyes('eye_rolled'), **both('blush', flush),
           'face_mouth': rel('mouth_grin'), 'face_effect_mouth': blank},
           'Alice release: eyes rolled up, wide laughing mouth, short tongue tip inside, flushed', geometry={})


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
