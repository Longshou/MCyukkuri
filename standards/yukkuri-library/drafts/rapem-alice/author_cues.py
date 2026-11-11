"""Author the outer adult-state poses in pose-cues.json.

Each cue key is [progress, pitch, yaw, roll, sx, sy, sz]. The sukkiri lore clips
own the contact motion and faces. These cues add only the shared contact lean,
the pinned pose and the existing completion stretch.
"""
import json
from pathlib import Path

HERE = Path(__file__).resolve().parent
NEUTRAL = [0, 0, 0, 1, 1, 1]


def pose(pitch=0., yaw=0., roll=0., sy=1., sx=None, sz=None):
    if sx is None:
        sx = 1+(1-sy)*.5
    if sz is None:
        sz = sx
    return [pitch, yaw, roll, sx, sy, sz]


def keys(beats, length):
    """Ease between the authored beats and return to rest."""
    return [[0]+NEUTRAL, *[[round(tick/length, 4)]+[round(v, 3) for v in value] for tick, value in beats],
            [1]+NEUTRAL]


# Existing 120-tick completion stretch.
RELIEF = [
    (3, pose(0, 0, 0, .95)), (10, pose(3, 0, 0, 1.06, .97, .97)), (24, pose(0, 0, 0, 1)),
    (48, pose(0, 0, 0, .94)), (56, pose(4, 0, 0, 1.08, .96, .96)), (72, pose(0, 0, 0, 1)),
    (88, pose(0, 0, 0, .94)), (95, pose(7, 0, 0, 1.13, .94, .94)), (106, pose(6, 0, 0, 1.12, .945, .945)),
    (116, pose(0, 0, 0, .99)),
]


def main():
    path = HERE/'pose-cues.json'
    data = json.loads(path.read_text(encoding='utf-8'))
    for name, value in {
        'pair_peni': [-6, 0, 0, 1, 1, 1],
        'pair_mamu': [3, 0, 0, 1, 1, 1],
        'act': [-6, 0, 0, 1, 1, 1],
        'pinned': [-6, 0, 0, 1.06, .9, 1.04],
    }.items():
        data[name] = [[0]+value, [1]+value]
    data['relief'] = keys(RELIEF, 120)
    data.pop('faces', None)
    for key in data['rapem']:
        key[1] = max(key[1], -10)
    order = ['format', 'mamu_rear_offset', 'channels', 'heat', 'relief', 'act', 'rapem', 'pair_peni', 'pair_mamu', 'pinned']
    lines = ['{']
    for i, name in enumerate(order):
        value = data[name]
        tail = ',' if i < len(order)-1 else ''
        if name in ('format', 'mamu_rear_offset', 'channels'):
            lines.append(f'  "{name}": {json.dumps(value)}{tail}')
        else:
            rows = ['    '+json.dumps([round(x, 4) if isinstance(x, float) else x for x in key]) for key in value]
            lines.append(f'  "{name}": [\n'+',\n'.join(rows)+f'\n  ]{tail}')
    lines.append('}')
    path.write_text('\n'.join(lines)+'\n', encoding='utf-8', newline='\n')
    print('pose-cues.json authored')


if __name__ == '__main__':
    main()
