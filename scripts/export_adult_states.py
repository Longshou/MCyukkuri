"""Export the selected adult-state presentation recipe; no build or game launch."""
from pathlib import Path
import argparse
import json
import math

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / 'standards/yukkuri-library/drafts/rapem-alice'
SNAPSHOT = ROOT / 'art/source/adult-states'
ASSETS = ROOT / 'src/main/resources/assets/mcyukkuri'
LIB = ROOT / 'standards/yukkuri-library'
# Standard confirmed act faces (user feedback rejected the 1.12 composites): the hunter's
# open-eyed drool through hunt/approach and the victim's squeezed tearful wail for every species
# carrying the standard atlas. The flushed tongue-out face is composed from its preset by the face
# library (short tongue, user 2026-11-12), like the other sukkiri faces.
STANDARD_FACES = {
    'alice': ('open_drool', 'squeezed_cry_tears'),
    'reimu': ('squeezed_cry_tears',),
    'marisa': ('squeezed_cry_tears',),
    'chen': ('squeezed_cry_tears',),
    'patchouli': ('squeezed_cry_tears',),
    'marisa_tsumuri': ('squeezed_cry_tears',),
}


TILT_MAX = 12


def tilt(a, b, q):
    """Angle in degrees between the body's up axis and the world's at eased mix q of two [pitch, yaw, roll] keys."""
    m = q*q*(3-2*q)
    pitch, yaw, roll = (math.radians(x+(y-x)*m) for x, y in zip(a, b))
    # Up axis (0,1,0) through Rx(pitch), then Ry(yaw), then Rz(roll); Ry keeps y, so only pitch and roll tilt it.
    y1, z1 = math.cos(pitch), math.sin(pitch)
    x2, y2 = z1*math.sin(yaw), y1
    y3 = x2*math.sin(roll)+y2*math.cos(roll)
    return math.degrees(math.acos(max(-1., min(1., y3))))


def standard_face_outputs(outputs):
    # The standard confirmation set records who may ship; whole-tree export fingerprints predate
    # the 1.12 base-look rebake and no longer match any current atlas, so membership and preset
    # acceptance are checked instead of the stale per-file hashes.
    approved = json.loads((LIB / 'provenance/standard-expressions.json').read_text(encoding='utf-8'))
    accepted = {entry['id'] for entry in approved['entries']}
    for species, faces in STANDARD_FACES.items():
        for name in faces:
            assert name in accepted, 'Not a confirmed standard expression: ' + name
            source = LIB / 'assembled' / species / 'textures' / (name + '.png')
            data = source.read_bytes()
            preset = json.loads((LIB / 'expressions/presets' / (name + '.json')).read_text(encoding='utf-8'))
            assert preset['status'] == 'accepted' and not preset.get('geometry_offsets'), \
                'Requires geometry adaptation: ' + name
            # PNG width/height, checked without altering image bytes (current standard atlases are square).
            width = int.from_bytes(data[16:20], 'big')
            height = int.from_bytes(data[20:24], 'big')
            assert width == height and width in (256, 512), 'Unexpected standard atlas size: ' + species
            outputs[SNAPSHOT / f'{species}_{name}.png'] = data
            outputs[ASSETS / 'textures/entity' / species / f'{name}.png'] = data


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true')
    args = parser.parse_args()
    cues = (SOURCE / 'pose-cues.json').read_bytes()
    data = json.loads(cues)
    assert data['format'] == 1
    assert 0 < data['mamu_rear_offset'] <= 4
    assert data['channels'] == ['pitch', 'yaw', 'roll', 'sx', 'sy', 'sz']
    for branch in ('heat', 'relief', 'act', 'pinned', 'rapem', 'pair_peni', 'pair_mamu'):
        keys = data[branch]
        assert keys[0][0] == 0 and keys[-1][0] == 1
        assert all(len(key) == 7 for key in keys)
        assert all(a[0] < b[0] for a, b in zip(keys, keys[1:]))
        assert all(-35 <= value <= 35 for key in keys for value in key[1:4]), branch
        assert all(.75 <= value <= 1.2 for key in keys for value in key[4:])
        # Whole-body lean of the eased cue (the body's up axis against the world's, Rz*Ry*Rx as the game composes it)
        # stays within the 12 degree pitch-and-roll budget of the body rules; yaw is free.
        worst = max(tilt(a[1:4], b[1:4], q/8) for a, b in zip(keys, keys[1:]) for q in range(9))
        assert worst <= TILT_MAX, f'{branch}: body tilt {worst:.1f} > {TILT_MAX}'
        # heat/relief/rapem return to neutral; the thrust and pinned cycles only close their loop.
        if branch not in ('act', 'pinned', 'pair_peni', 'pair_mamu'):
            assert keys[-1][1:] == [0, 0, 0, 1, 1, 1]
        assert keys[0][1:] == keys[-1][1:], branch
    outputs = {
        SNAPSHOT / 'pose-cues.json': cues,
        ASSETS / 'yukkuri_presentation/adult_states.json': cues,
    }
    # Export newly authored faces. Never fall back to the superseded draft.
    for name in ('breeding_period', 'state_recover'):
        face = (SOURCE / 'variant' / f'{name}.png').read_bytes()
        outputs[SNAPSHOT / f'{name}.png'] = face
        outputs[ASSETS / 'textures/entity/alice' / f'{name}.png'] = face
    # The 発情 rotating mark (hatujyou/rot.bmp); the droplet strip is no longer used.
    for name in ('hatujyou_rot',):
        overlay = (SOURCE / 'overlays' / f'{name}.png').read_bytes()
        outputs[SNAPSHOT / f'{name}.png'] = overlay
        outputs[ASSETS / 'textures/effects' / f'{name}.png'] = overlay
    # Standard confirmed act faces replace the rejected 1.12 composites.
    standard_face_outputs(outputs)
    for target, content in outputs.items():
        if args.check:
            if not target.is_file() or target.read_bytes() != content:
                raise SystemExit(f'Export differs: {target.relative_to(ROOT)}')
        else:
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(content)
    print(f'Adult presentation: {len(outputs)} files ' + ('match source.' if args.check else 'exported.'))


if __name__ == '__main__':
    main()
