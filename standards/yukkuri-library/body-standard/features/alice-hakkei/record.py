"""Write candidate.json: scope and fingerprints of Alice's Hakkei-based base-look revision.

Run after author.py, sync_inputs.py and the export chain in README.md. Hashes are raw
bytes of this checkout; the game fingerprint check also accepts LF/CRLF variants.
`--approve "<user instruction>"` also writes approved.json (the saved standard); later
edits must only update candidate.json and never reuse an approval record.
"""
from pathlib import Path
import datetime, hashlib, json, runpy, sys

HERE = Path(__file__).resolve().parent
LIB = HERE.parents[2]; ROOT = LIB.parents[1]
ASSETS = ROOT/'src/main/resources/assets/mcyukkuri'
sha = lambda p: hashlib.sha256(p.read_bytes()).hexdigest()
rel = lambda p: p.relative_to(ROOT).as_posix()
read = lambda p: json.loads(p.read_text(encoding='utf-8'))

sources = [HERE/'author.py', HERE/'sync_inputs.py', LIB/'components/alice/hair.json', LIB/'components/alice/hand-left.json',
           LIB/'components/alice/hand-right.json', LIB/'components/alice/accessories.json', LIB/'textures/alice/base.png',
           LIB/'presentation/placement-fit/recipe.json', ROOT/'art/props/family-life/recipe.json']
outputs = [LIB/'assembled/alice/alice.bbmodel', LIB/'assembled/alice/runtime.model.json', LIB/'assembled/alice/textures/neutral.png',
           ASSETS/'yukkuri/alice.json', ASSETS/'textures/entity/alice/neutral.png', ASSETS/'yukkuri_props/accessory_alice.json',
           ASSETS/'yukkuri_presentation/placement_fit.json', ASSETS/'yukkuri_presentation/motion_corrections.json',
           ASSETS/'yukkuri_wounds/alice.json.gz', ASSETS/'yukkuri/badge_mounts.json']
parts = sorted(e['name'] for name in ('hair', 'hand-left', 'hand-right', 'accessories')
               for e in read(LIB/f'components/alice/{name}.json')['elements'])
palette = runpy.run_path(str(HERE/'author.py'))['PALETTE']
candidate = {
    'created_at': datetime.datetime.now(datetime.timezone.utc).isoformat(timespec='seconds'),
    'status': 'pending_user_confirmation',
    'scope': ['Alice base look only: hair shape, headband, palette; rounded body, face and expressions unchanged',
              'Lemon-blonde bob (Hakkei U sprite 7607 hue) distinct from Marisa\'s honey blonde',
              'Red headband worn at the hairline with a white scalloped lace edge over the fringe roots (Hakkei U 7038)',
              'Fringe with long temple locks, a centre spike and a shorter parting; face-framing locks curl in at the jaw; spiky back locks tuck under',
              'Derived game data refreshed for Alice only: runtime model and atlases, placement fit, wounds, accessory prop, badge mount, '
              'stem hanging anchor, cheek-puff face and motion-correction entry, lore faces (dazed, blank_cry)'],
    'preserved': ['Rounded body standard (provenance/standard-body.json)', 'Bones, 19 runtime clips, expression UVs and face pixels',
                  'Accessory prefixes alice_band_/alice_lace_ and badge part alice_band_red_2', 'Every other species export'],
    'not_included': ['Adult-state faces (alice_agitated, breeding_period, state_recover) are another task and still carry the previous palette cells'],
    'references': ['wiki/art-references/game/standard-species/hakkei-contact.png (slot 2: 7607 hair, 7038 headband, 6880 side tips)',
                   'pict/arisu (Hakkei ver1.12 layers, user-supplied, not copied)',
                   'wiki/hakkei-reference/lore/species/alice/README.md (blonde, red headband, short hair)'],
    'hair_loss': 'Every hair part name contains hair or bang, so per-side hair loss now hides Alice\'s hair (the old alice_cap/alice_side_* names never hid)',
    'palette': {k: '#%02x%02x%02x' % v for k, v in palette.items()},
    'parts': parts,
    'sources': {rel(p): sha(p) for p in sources},
    'frozen_inputs_manifest_sha256': sha(LIB/'body-standard/inputs/manifest.json'),
    'outputs': {rel(p): sha(p) for p in outputs},
    'game_acceptance': 'not_performed',
}
(HERE/'candidate.json').write_text(json.dumps(candidate, ensure_ascii=False, indent=2)+'\n', encoding='utf-8', newline='\n')
print('wrote candidate.json;', len(parts), 'parts')
if '--approve' in sys.argv:
    approved = dict(candidate, status='user_approved_standard', approved_on=datetime.date.today().isoformat(),
                    instruction=sys.argv[sys.argv.index('--approve')+1])
    (HERE/'approved.json').write_text(json.dumps(approved, ensure_ascii=False, indent=2)+'\n', encoding='utf-8', newline='\n')
    print('wrote approved.json')
