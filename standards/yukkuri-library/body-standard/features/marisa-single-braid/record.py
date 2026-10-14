"""Write candidate.json: scope and fingerprints of the Marisa single-braid revision.

Run after author.py, sync_inputs.py and the export chain listed in README.md. Hashes are
raw file bytes of this checkout; the game fingerprint check also accepts LF/CRLF variants.
`--approve "<user instruction>"` also writes approved.json, the saved standard baseline.
"""
from pathlib import Path
import datetime, hashlib, json, sys

HERE = Path(__file__).resolve().parent
LIB = HERE.parents[2]; ROOT = LIB.parents[1]
sha = lambda p: hashlib.sha256(p.read_bytes()).hexdigest()
rel = lambda p: p.relative_to(ROOT).as_posix()
read = lambda p: json.loads(p.read_text(encoding='utf-8'))

sources = [HERE/'author.py', HERE/'sync_inputs.py', HERE/'export_house_runtime.py', LIB/'components/marisa/hand-left.json',
           LIB/'components/marisa/hand-right.json', LIB/'rigs/marisa.json',
           LIB/'subspecies/deficient/rounded-marisa/recipe.json', LIB/'subspecies/deficient/recipes.json',
           LIB/'subspecies/deficient/bean_hair.py', ROOT/'scripts/export_marisa_deficient_rounded.py',
           ROOT/'scripts/runtime_house_interactions.py', LIB/'subspecies/reimu-dos/build.py',
           LIB/'presentation/placement-fit/recipe.json']
# Game motions adapted to the single braid (braid follow, tsumuri retract, house carry).
motion_sources = [LIB/'motions/variants/contact-safe/recipe.json', LIB/'motions/variants/contact-safe/author.py',
                  LIB/'motions/variants/marisa-tsumuri/shell_retract.json', ROOT/'scripts/runtime_house_interactions.py',
                  ROOT/'src/main/resources/assets/mcyukkuri/yukkuri_presentation/motion_corrections.json']
changed = sorted({e['name'] for side in ('left', 'right')
                  for e in read(LIB/f'components/marisa/hand-{side}.json')['elements']})
models = {}
for ident, folder in [('marisa', LIB/'assembled/marisa'), ('marisa_tsumuri', LIB/'assembled/marisa_tsumuri'),
                      ('marisa_sparse', LIB/'subspecies/deficient/assembled/marisa_sparse'),
                      ('marisa_face', LIB/'subspecies/deficient/assembled/marisa_face'),
                      ('marisa_bean', LIB/'subspecies/deficient/assembled/marisa_bean'),
                      ('house_marisa', LIB/'ecology/house-yukkuri/assembled'),
                      ('marisa_dos', LIB/'subspecies/reimu-dos/assembled/marisa_dos')]:
    record = {'model': rel(folder/f'{ident}.bbmodel'), 'model_sha256': sha(folder/f'{ident}.bbmodel')}
    runtime = ROOT/'src/main/resources/assets/mcyukkuri/yukkuri'/f'{ident}.json'
    if runtime.exists():
        record.update(runtime=rel(runtime), runtime_sha256=sha(runtime))
    models[ident] = record
candidate = {
    'created_at': datetime.datetime.now(datetime.timezone.utc).isoformat(timespec='seconds'),
    'status': 'pending_confirmation',
    'scope': ['Marisa side hair: one thick braid on her own left (+X, hand_right), a short loose lock on the right (-X, hand_left)',
              'Applied to every Marisa form: standard, tsumuri, sparse, uneven face, bean, house and DOS',
              'Derived game data: wounds, badges, attachments, placement fit, motion corrections, hat seats, restraint frames, house grip points'],
    'preserved': ['Rounded body, face, hat and expression textures', 'Reimu and other species exports',
                  'Approved standard-body and tsumuri records as baselines', 'Seven braid interactions v1 (two-braid) as a saved snapshot'],
    'hair_loss': 'braid_lock_* and side_hair_loose* follow the per-side hair-loss rule like other hair parts',
    'baseline_approval': 'standards/yukkuri-library/provenance/standard-body.json',
    'changed_parts': changed,
    'sources': {rel(p): sha(p) for p in sources},
    'frozen_inputs_manifest_sha256': sha(LIB/'body-standard/inputs/manifest.json'),
    'models': models,
    'motions': {'braid_follow': 'contact-safe recipe v8: braid follows body twist, splays out of skin and floor',
                'tsumuri_shell_retract': 'side hair tilts back and splays outward while withdrawing',
                'house_carry': 'both grip points on the braid tip', 'interactions': 'interactions/marisa-braids v2',
                'sources': {rel(p): sha(p) for p in motion_sources}},
    'game_acceptance': 'not_performed',
}
(HERE/'candidate.json').write_text(json.dumps(candidate, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
print('wrote candidate.json;', len(changed), 'changed parts;', len(models), 'models')
if '--approve' in sys.argv:
    approved = dict(candidate, status='user_approved_standard', approved_on=datetime.date.today().isoformat(),
                    instruction=sys.argv[sys.argv.index('--approve')+1],
                    approval_scope='Single-braid shape of all seven Marisa forms and the motions adapted to it; '
                                   'later edits update candidate.json only and must not reuse this record.')
    (HERE/'approved.json').write_text(json.dumps(approved, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
    print('wrote approved.json')
