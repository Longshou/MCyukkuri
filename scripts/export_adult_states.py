"""Export the selected adult-state presentation recipe; no build or game launch."""
from pathlib import Path
import argparse
import json

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / 'standards/yukkuri-library/drafts/rapem-alice'
SNAPSHOT = ROOT / 'art/source/adult-states'
ASSETS = ROOT / 'src/main/resources/assets/mcyukkuri'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true')
    args = parser.parse_args()
    cues = (SOURCE / 'pose-cues.json').read_bytes()
    data = json.loads(cues)
    assert data['format'] == 1
    assert data['channels'] == ['pitch', 'yaw', 'roll', 'sx', 'sy', 'sz']
    for branch in ('heat', 'relief', 'rapem'):
        keys = data[branch]
        assert keys[0][0] == 0 and keys[-1][0] == 1
        assert all(len(key) == 7 for key in keys)
        assert all(a[0] < b[0] for a, b in zip(keys, keys[1:]))
        assert all(-35 <= value <= 35 for key in keys for value in key[1:4])
        assert all(.75 <= value <= 1.2 for key in keys for value in key[4:])
        assert keys[-1][1:] == [0, 0, 0, 1, 1, 1]
    outputs = {
        SNAPSHOT / 'pose-cues.json': cues,
        ASSETS / 'yukkuri_presentation/adult_states.json': cues,
    }
    # Export newly authored faces. Never fall back to the superseded draft.
    for name in ('alice_agitated', 'breeding_period', 'state_recover'):
        face = (SOURCE / 'variant' / f'{name}.png').read_bytes()
        outputs[SNAPSHOT / f'{name}.png'] = face
        outputs[ASSETS / 'textures/entity/alice' / f'{name}.png'] = face
    # The 1.12 act and heat marks (green-screen BMPs converted by build_overlays_112.py).
    for name in ('rapem_act_strip', 'hatujyou_rot'):
        overlay = (SOURCE / 'overlays' / f'{name}.png').read_bytes()
        outputs[SNAPSHOT / f'{name}.png'] = overlay
        outputs[ASSETS / 'textures/effects' / f'{name}.png'] = overlay
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
