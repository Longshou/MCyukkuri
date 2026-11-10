"""Author the adult-state root cues and face tables in pose-cues.json from beat lists (project design).

Each cue key is [progress, pitch, yaw, roll, sx, sy, sz]; the runtime eases every segment (smoothstep) and the whole
body turns about its feet. Pitch and roll together stay within the 12 degree body budget; yaw is free. Squash only
where a body really presses or is pressed (the father pushing into her back, her body taking the push, the pinned
victim under the attacker); release and joy are stretches and looks upward.

pair_peni / pair_mamu span the whole court contact (heat 60 t, otherwise 120 t): rub, steady thrusts, faster thrusts,
a held quivering press, then the release back to rest just before the contact ends. relief spans the 120 t
HEAT_RELIEVED beat with its lines at 1 / 46 / 91 t (yu!, su!, sukkiri~!). act / pinned are the Alice act's 20 t loop.
Faces are [progress, face] tables over the same clocks.
"""
import json
from pathlib import Path

HERE = Path(__file__).resolve().parent
NEUTRAL = [0, 0, 0, 1, 1, 1]


def pose(pitch=0., yaw=0., roll=0., sy=1., sx=None, sz=None):
    # A press bulges sideways about half as much as it shortens; a stretch narrows the same way.
    if sx is None:
        sx = 1+(1-sy)*.5
    if sz is None:
        sz = sx
    return [pitch, yaw, roll, sx, sy, sz]


def keys(beats, length):
    """beats: [(tick, pose)] -> keyframes over 0..1; first and last are the rest pose."""
    out = [[0]+NEUTRAL]
    for tick, value in beats:
        out.append([round(tick/length, 4)]+[round(v, 3) for v in value])
    out.append([1]+NEUTRAL)
    return out


# Father (peni): he leans in onto her back and rubs his cheek side to side, then pushes in a steady rhythm that
# quickens, holds the deepest push with a quiver, and lets go. Each push is a reach: he stretches up and forward over
# her back (so his brim rides up over her head instead of sinking into it) and settles back between pushes; the
# squash of the push is hers. Ticks of the 60 t heat contact: cheek rub 0-22 (three strokes, sukkiri's suri-suri),
# three steady pushes, four quick ones, the held quiver 52-57, release by 60.
PENI = [
    (5, pose(-5, 13, 0, 1.02)), (11, pose(-6, -13, 0, 1.02)), (17, pose(-5, 10, 0, 1.02)), (22, pose(-4, 0, 0, 1.)),
    *[b for t in (24, 30, 36) for b in ((t, pose(-8, 0, 0, 1.06, .98, 1.)), (t+3, pose(-3, 3 if t == 30 else -3, 0, .98)))],
    *[b for t in (40, 43, 46, 49) for b in ((t, pose(-9, 0, 0, 1.07, .975, 1.)), (t+1.5, pose(-4, 0, 0, .985)))],
    (52, pose(-9, 0, 0, 1.08, .97, 1.)),
    (53, pose(-9, 0, 2, 1.07, .975, 1.)), (54, pose(-9, 0, -2, 1.08, .97, 1.)), (55, pose(-9, 0, 2, 1.07, .975, 1.)),
    (56, pose(-9, 0, -2, 1.08, .97, 1.)), (57, pose(-8, 0, 1, 1.07, .975, 1.)),
]
# Mother (mamu): she leans back into him and looks back over each shoulder in turn as he rubs, then takes each push
# (pressed forward and squashed a tick later) and settles back toward him between pushes; she quivers with his held press.
MAMU = [
    (6, pose(3, -7, 0, 1)), (12, pose(3, 7, 0, 1)), (18, pose(3, -5, 0, 1)), (22, pose(2, 0, 0, 1)),
    *[b for t in (25, 31, 37) for b in ((t, pose(-4, 0, 0, .94, 1.04, .95)), (t+3, pose(3, 0, 0, 1.02)))],
    *[b for t in (41, 44, 47, 50) for b in ((t, pose(-5, 0, 0, .92, 1.05, .94)), (t+1.5, pose(3, 0, 0, 1.02)))],
    (53, pose(-5, 0, 0, .91, 1.05, .93)),
    (54, pose(-5, 0, -2, .93, 1.04, .94)), (55, pose(-5, 0, 2, .91, 1.05, .93)), (56, pose(-5, 0, -2, .93, 1.04, .94)),
    (57, pose(-4, 0, 1, .92, 1.05, .94)),
]
# HEAT_RELIEVED, 120 t: a strained shudder and pop with each of yu! (1 t) and su! (46 t), a crouch and the big
# upward stretch with the head thrown back for sukkiri~! (91 t), held, then down to rest.
RELIEF = [
    (3, pose(0, 0, 0, .95)), (10, pose(3, 0, 0, 1.06, .97, .97)), (24, pose(0, 0, 0, 1)),
    (48, pose(0, 0, 0, .94)), (56, pose(4, 0, 0, 1.08, .96, .96)), (72, pose(0, 0, 0, 1)),
    (88, pose(0, 0, 0, .94)), (95, pose(7, 0, 0, 1.13, .94, .94)), (106, pose(6, 0, 0, 1.12, .945, .945)),
    (116, pose(0, 0, 0, .99)),
]
# Alice act (attacker), 20 t loop: two pushes a second into the pinned body.
ACT = [(4, pose(-10, 0, 0, .9, 1.05, 1.06)), (8, pose(-5, 0, 0, 1.04)), (12, pose(-10, 0, 1, .89, 1.06, 1.07)), (16, pose(-5, 0, 0, 1.04))]
# Pinned victim, 20 t loop that starts and ends pressed down: squashed under each push, never tipped on her face.
PINNED_REST = pose(-6, 0, 0, .9, 1.06, 1.04)
PINNED = [(4, pose(-8, 0, 0, .86, 1.08, 1.05)), (8, pose(-5, 0, 0, .91, 1.05, 1.03)), (12, pose(-8, 0, 1, .85, 1.08, 1.05)), (16, pose(-5, 0, 0, .91, 1.05, 1.03))]

FACES = {
    'pair_peni': [[0, 'flushed_smile'], [.38, 'flushed_tongue'], [.85, 'impact']],
    'pair_mamu': [[0, 'flushed_smile'], [.45, 'flushed_tongue'], [.87, 'impact']],
    'relief': [[0, 'impact'], [.2, 'flushed_smile'], [.38, 'impact'], [.6, 'flushed_smile'], [.74, 'happy'], [.93, 'satisfied']],
}


def main():
    path = HERE/'pose-cues.json'
    data = json.loads(path.read_text(encoding='utf-8'))
    data['pair_peni'] = keys(PENI, 60)
    data['pair_mamu'] = keys(MAMU, 60)
    data['relief'] = keys(RELIEF, 120)
    data['act'] = keys(ACT, 20)
    data['pinned'] = [[0]+PINNED_REST]+[[round(t/20, 4)]+v for t, v in PINNED]+[[1]+PINNED_REST]
    rapem = data['rapem']
    for key in rapem:
        key[1] = max(key[1], -10)
    data['faces'] = FACES
    order = ['format', 'mamu_rear_offset', 'channels', 'heat', 'relief', 'act', 'rapem', 'pair_peni', 'pair_mamu', 'pinned', 'faces']
    lines = ['{']
    for i, name in enumerate(order):
        value = data[name]
        tail = ',' if i < len(order)-1 else ''
        if name in ('format', 'mamu_rear_offset', 'channels'):
            lines.append(f'  "{name}": {json.dumps(value)}{tail}')
        elif name == 'faces':
            rows = [f'    "{k}": {json.dumps(v)}' for k, v in value.items()]
            lines.append(f'  "{name}": {{\n'+',\n'.join(rows)+f'\n  }}{tail}')
        else:
            rows = ['    '+json.dumps([round(x, 4) if isinstance(x, float) else x for x in key]) for key in value]
            lines.append(f'  "{name}": [\n'+',\n'.join(rows)+f'\n  ]{tail}')
    lines.append('}')
    path.write_text('\n'.join(lines)+'\n', encoding='utf-8', newline='\n')
    print('pose-cues.json authored')


if __name__ == '__main__':
    main()
