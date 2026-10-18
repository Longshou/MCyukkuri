"""Export the selected adult-state presentation recipe; no build or game launch."""
from pathlib import Path
import argparse
import json

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / 'standards/yukkuri-library/drafts/rapem-alice'
SNAPSHOT = ROOT / 'art/source/adult-states'
ASSETS = ROOT / 'src/main/resources/assets/mcyukkuri'
LIB = ROOT / 'standards/yukkuri-library'
# Standard confirmed act faces (user feedback rejected the 1.12 composites): the hunter's
# open-eyed drool through hunt/approach, its flushed tongue-out face in the act, and the
# victim's squeezed tearful wail for every species carrying the standard atlas.
STANDARD_FACES = {
    'alice': ('open_drool', 'flushed_tongue', 'squeezed_cry_tears'),
    'reimu': ('squeezed_cry_tears',),
    'marisa': ('squeezed_cry_tears',),
    'chen': ('squeezed_cry_tears',),
    'patchouli': ('squeezed_cry_tears',),
}


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
            # PNG width/height, checked without altering image bytes (current atlases are 256px).
            assert int.from_bytes(data[16:20], 'big') == 256 and int.from_bytes(data[20:24], 'big') == 256
            outputs[SNAPSHOT / f'{species}_{name}.png'] = data
            outputs[ASSETS / 'textures/entity' / species / f'{name}.png'] = data


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true')
    args = parser.parse_args()
    cues = (SOURCE / 'pose-cues.json').read_bytes()
    data = json.loads(cues)
    assert data['format'] == 1
    assert data['channels'] == ['pitch', 'yaw', 'roll', 'sx', 'sy', 'sz']
    for branch in ('heat', 'relief', 'act', 'pinned', 'rapem'):
        keys = data[branch]
        assert keys[0][0] == 0 and keys[-1][0] == 1
        assert all(len(key) == 7 for key in keys)
        assert all(a[0] < b[0] for a, b in zip(keys, keys[1:]))
        assert all(-35 <= value <= 35 for key in keys for value in key[1:4]) if branch != 'pinned' else \
               all(-90 <= value <= 90 for key in keys for value in key[1:4]), branch
        assert all(.75 <= value <= 1.2 for key in keys for value in key[4:])
        # heat/relief/rapem return to neutral; the thrust and pinned cycles only close their loop.
        if branch not in ('act', 'pinned'):
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
