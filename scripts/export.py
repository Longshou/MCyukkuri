"""Declared asset export pipeline. No game, browser, build or temporary authoring script."""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time
from export_registry import load as read, save as write

ROOT = Path(__file__).resolve().parents[1]
LORE = 'standards/yukkuri-library/motions/variants/lore'
sys.dont_write_bytecode = True


# Dependencies are execution requirements, not inferred from filenames. Source builders
# with no check are listed too; default exports run runtime owners, never legacy builders.
def exporter(ident, path, sources, deps=(), check=('--check',), args=(), note=''):
    return dict(id=ident, path=path, sources=list(sources)+[path], deps=deps,
                check=check, args=args, note=note)


TABLE = [
    exporter('rounded', 'scripts/export_rounded_runtime.py', ['standards/yukkuri-library/body-standard/'], check=None),
    exporter('placement-fit', 'scripts/export_placement_fit.cjs', ['standards/yukkuri-library/presentation/placement-fit/', 'src/main/resources/assets/mcyukkuri/yukkuri/']),
    exporter('motion-corrections', 'scripts/export_motion_corrections.py', ['standards/yukkuri-library/motions/', 'art/source/motion-corrections/',
             'standards/yukkuri-library/interactions/braid-water-rescue/', 'standards/yukkuri-library/interactions/marisa-hat-vessel/',
             'standards/yukkuri-library/interactions/aquatic-marisa-hat/'], ['placement-fit', 'braid-water-rescue']),
    exporter('braid-water-rescue', 'standards/yukkuri-library/interactions/braid-water-rescue/author.py', ['standards/yukkuri-library/interactions/braid-water-rescue/'], check=None),
    exporter('hat-interactions', 'scripts/export_hat_interactions.py', ['art/source/hat-interactions/'], ['placement-fit'], check=None),
    exporter('rounded-attachments', 'scripts/export_rounded_attachments.py', ['standards/yukkuri-library/body-standard/'], check=None),
    exporter('wound-surfaces', 'scripts/export_wound_surfaces.cjs', ['art/source/standard-runtime/', 'src/main/resources/assets/mcyukkuri/yukkuri/']),
    exporter('rounded-wounds', 'scripts/rounded_wound_surface.cjs', ['art/source/rounded-runtime/'], check=None),
    exporter('subspecies-wounds', 'scripts/export_subspecies_wounds.cjs', ['art/source/subspecies-runtime/'], check=None),
    exporter('subspecies', 'scripts/export_subspecies.py', ['standards/yukkuri-library/subspecies/deficient/'], check=('--check-textures',), note='Check covers textures only, not geometry.'),
    exporter('rare-forms', 'scripts/export_rare_forms.py', ['standards/yukkuri-library/subspecies/rare-forms/']),
    exporter('predator-assets', 'scripts/export_predator_assets.py', ['standards/yukkuri-library/subspecies/predator/']),
    exporter('predator-language', 'scripts/export_predator_language.py', ['standards/yukkuri-library/subspecies/predator/'], check=None),
    exporter('reimu-deficient', 'scripts/export_reimu_deficient_rounded.py', ['standards/yukkuri-library/subspecies/deficient/rounded-reimu/'], check=None),
    exporter('marisa-deficient', 'scripts/export_marisa_deficient_rounded.py', ['standards/yukkuri-library/subspecies/deficient/rounded-marisa/'], check=None),
    exporter('bean-assets', 'scripts/export_bean_assets.py', ['standards/yukkuri-library/subspecies/deficient/'], check=None),
    exporter('living-assets', 'scripts/export_living_assets.py', ['art/props/care-facilities/']),
    exporter('family-assets', 'scripts/export_family_assets.py', ['art/props/family-life/', 'standards/yukkuri-library/motions/procedural/']),
    exporter('house-assets', 'scripts/export_house_assets.py', ['art/props/house-care/']),
    exporter('peripheral-assets', 'scripts/export_peripheral_assets.py', ['art/props/runtime-peripheral/']),
    exporter('pet-goods-icons', 'scripts/export_pet_goods_icons.py', ['art/props/pet-goods/']),
    exporter('supply-icons', 'scripts/export_supply_icons.py', ['art/props/supply-icons/']),
    exporter('badges', 'scripts/export_badges.py', ['art/props/badges/']),
    exporter('human-world', 'scripts/export_human_world.py', ['art/structures/human-world/']),
    exporter('dialogue', 'scripts/export_dialogue.py', ['art/dialogue/', 'art/audio/aquestalk/']),
    exporter('ecology-expressions', 'scripts/export_ecology_expressions.py', ['standards/yukkuri-library/expressions/', 'art/source/ecology/']),
    exporter('adult-states', 'scripts/export_adult_states.py', ['standards/yukkuri-library/drafts/rapem-alice/']),
    exporter('alice-appearance', 'scripts/export_alice_appearance.py', ['standards/yukkuri-library/body-standard/features/alice-hakkei/']),
    exporter('hat-damage', 'scripts/export_hat_damage.py', ['art/props/hat-damage/']),
    exporter('house-face-motion', 'scripts/export_house_face_motion.py', ['art/source/standard-runtime/face-motion/'], check=None, args=('--publish',)),
    exporter('house-carry-motion', 'scripts/export_house_carry_motion.py', ['art/source/standard-runtime/carry-motion/'], check=None),
    exporter('runtime-models', 'scripts/export_runtime_models.py', ['art/source/standard-runtime/'], check=None, note='Legacy seven-model recipe; refuses current rounded selections.'),
    exporter('moments', 'scripts/export_moments.py', ['standards/yukkuri-library/presentation/moments.json'], ['ecology-expressions'], note='wp-bu exporter; may not yet be on this checkout.'),
    exporter('ecology-audio', 'scripts/build_ecology_audio.py', ['art/audio/ecology/']),
    exporter('lore-faces', LORE+'/faces.py', [LORE+'/faces.py']),
]

# Package-local authoring commands: kept in their source directories. These have no
# read-only export check; importing compile-only modules is deliberately not execution.
BUILDERS = [
    'art/structures/human-world/build.py', 'art/props/body-marks/build.py', 'art/props/predators/build.py',
    'standards/yukkuri-library/build.py', 'standards/yukkuri-library/body-standard/build.py',
    *['standards/yukkuri-library/body-standard/features/'+p+'/author.py' for p in
      ('marisa-single-braid', 'hair-contact', 'deibu-mature', 'alice-hakkei')],
    *['standards/yukkuri-library/interactions/'+p+'/build.py' for p in
      ('toilet-contact', 'marisa-hat-vessel', 'marisa-braids', 'aquatic-marisa-hat')],
    'standards/yukkuri-library/interactions/aquatic-marisa-hat/export.py',
    *['standards/yukkuri-library/subspecies/'+p+'/build.py' for p in ('reimu-dos', 'rare-forms', 'predator')],
    *['standards/yukkuri-library/ecology/'+p+'/build.py' for p in ('wild-shelters', 'house-yukkuri', 'house-nest', 'house-repellent')],
    *['standards/yukkuri-library/drafts/'+p+'/build.py' for p in
      ('remains', 'playgrounds', 'cold-storage', 'care-props', 'body-marks/hair-wounds', 'consumables', 'operation-tools', 'care-expansion')],
    'standards/yukkuri-library/drafts/remains/export.py',
    'standards/yukkuri-library/drafts/body-marks/export_wounds.py',
    'art/props/family-life/export_shed_stem.py', 'art/props/cold-storage/export_guide.py',
    'standards/yukkuri-library/body-standard/features/marisa-single-braid/export_house_runtime.py',
]
CHECKED_BUILDERS = {
    'art/props/body-marks/build.py', 'art/props/family-life/export_shed_stem.py', 'art/props/cold-storage/export_guide.py',
    *['standards/yukkuri-library/interactions/'+p for p in ('toilet-contact/build.py', 'marisa-hat-vessel/build.py',
       'aquatic-marisa-hat/build.py', 'aquatic-marisa-hat/export.py')],
    'standards/yukkuri-library/subspecies/rare-forms/build.py',
    'standards/yukkuri-library/ecology/house-nest/build.py',
    'standards/yukkuri-library/ecology/house-repellent/build.py',
    *['standards/yukkuri-library/drafts/'+p for p in ('playgrounds/build.py', 'cold-storage/build.py',
       'care-props/build.py', 'body-marks/hair-wounds/build.py', 'consumables/build.py',
       'operation-tools/build.py', 'body-marks/export_wounds.py', 'remains/build.py', 'remains/export.py', 'care-expansion/build.py')],
}
TABLE += [exporter('source:'+p, p, [str(Path(p).parent)+'/'], check=('--check',) if p in CHECKED_BUILDERS else None,
                   note='Source authoring command.') for p in BUILDERS]
TABLE += [exporter('source:'+p, p, [str(Path(p).parent)+'/']) for p in [
    'art/props/body-marks/core_filling.py', 'standards/yukkuri-library/emotes/emotes.py',
    'art/props/family-life/fit_stem.py',
    'standards/yukkuri-library/expressions/derived/faces.py',
    *['standards/yukkuri-library/drafts/care-props/'+p+'.py' for p in
      ('wear', 'supplies', 'poop_block', 'large_nests')],
    'standards/yukkuri-library/drafts/body-marks/hair-wounds/select_runtime.py',
]]

# Authoring prerequisites order only the selected builders; selecting a runtime
# owner alone must not silently rebuild its previously accepted source geometry.
SOURCE_ORDER = {
    'rounded': ['standards/yukkuri-library/body-standard/build.py'],
    'rare-forms': ['standards/yukkuri-library/subspecies/rare-forms/build.py'],
    'predator-assets': ['standards/yukkuri-library/subspecies/predator/build.py'],
    'human-world': ['art/structures/human-world/build.py'],
    'peripheral-assets': ['standards/yukkuri-library/drafts/care-props/build.py'],
    'placement-fit': ['standards/yukkuri-library/body-standard/build.py'],
    'motion-corrections': ['standards/yukkuri-library/interactions/marisa-hat-vessel/build.py',
                           'standards/yukkuri-library/interactions/aquatic-marisa-hat/export.py'],
}
for row in TABLE:
    row['after'] = ['source:'+p for p in SOURCE_ORDER.get(row['id'], [])]


def table():
    rows = list(TABLE)
    cfg = read(ROOT/LORE/'recipe.json')
    for name in cfg['export_families']:
        rows.append(exporter(name, LORE+'/author.py', [LORE+'/'], ['placement-fit'], args=('--family', name)))
    return {row['id']: row for row in rows}


def ordered(rows, selected, dependencies=True):
    result, visiting = [], set()
    chosen = set(selected)
    def visit(ident):
        if ident in visiting:
            raise ValueError('Dependency cycle: '+ident)
        if ident in result:
            return
        visiting.add(ident)
        if dependencies:
            for dep in rows[ident]['deps']:
                visit(dep)
            for dep in rows[ident].get('after', []):
                if dep in chosen:
                    visit(dep)
        visiting.remove(ident)
        result.append(ident)
    for ident in selected:
        visit(ident)
    return [rows[key] for key in result]


def changed_files(rev):
    # Include committed changes from rev, staged/unstaged changes and untracked sources.
    def git(*args):
        return subprocess.check_output(['git', *args], cwd=ROOT).decode('utf-8').split('\0')
    return set(git('diff', '--name-only', '-z', rev, '--')+
               git('ls-files', '--others', '--exclude-standard', '-z'))-{''}


def affected(rows, files):
    selected = {key for key, row in rows.items() if any(
        f.startswith(p) if p.endswith('/') else f == p for f in files for p in row['sources'])}
    # Consumers must rebuild when a producer changes too.
    while True:
        more = {key for key, row in rows.items() if selected.intersection(row['deps'])}
        if more <= selected:
            return selected
        selected |= more


def run(row, check, logs):
    start = time.monotonic()
    if check and row['check'] is None:
        status, detail = 'NO CHECK', row['note'] or 'Exporter has no read-only check.'
    elif not (ROOT/row['path']).is_file():
        status, detail = 'MISSING', row['path']
    else:
        command = [sys.executable if row['path'].endswith('.py') else 'node', row['path'], *row['args']]
        if check:
            command += list(row['check'])
        logs.mkdir(parents=True, exist_ok=True)
        log = logs/(re.sub(r'[^a-zA-Z0-9_-]', '_', row['id'])+'.log')
        with log.open('w', encoding='utf-8') as stream:
            try:
                process = subprocess.run(command, cwd=ROOT, stdout=stream, stderr=subprocess.STDOUT,
                                         env={**os.environ, 'PYTHONUTF8': '1', 'PYTHONDONTWRITEBYTECODE': '1'})
                status = 'PASS' if process.returncode == 0 else 'FAIL'
            except OSError as error:
                stream.write(str(error)+'\n')
                status = 'FAIL'
        detail = str(log)
    result = dict(id=row['id'], status=status, seconds=round(time.monotonic()-start, 3), detail=detail)
    print(f"{row['id']}: {status} {result['seconds']:.3f}s {detail}", flush=True)
    return result


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('command', nargs='?', choices=['motion'])
    ap.add_argument('clips', nargs='*')
    select = ap.add_mutually_exclusive_group()
    select.add_argument('--family', action='append')
    select.add_argument('--changed', nargs='?', const='HEAD', metavar='REV')
    select.add_argument('--rebuild-motion', action='store_true')
    select.add_argument('--register', type=Path, metavar='ENTRY')
    select.add_argument('--registries', action='store_true')
    select.add_argument('--import-registries', action='store_true')
    ap.add_argument('--check', action='store_true')
    ap.add_argument('--list', action='store_true')
    ap.add_argument('--beats', type=Path)
    ap.add_argument('--model', default='reimu')
    ap.add_argument('--metrics', default='tilt', help='Per-frame metrics (tilt,pass,wet,gap,held); verdicts are reports, not acceptance gates')
    ap.add_argument('--context', type=Path, help='Explicit pose evidence JSON for additional metrics')
    ap.add_argument('--water', type=float)
    ap.add_argument('--report', type=Path, default=ROOT/'.local/reports/export/results.json')
    args = ap.parse_args()
    args.report = args.report.resolve()
    if args.beats:
        args.beats = args.beats.resolve()
    if args.context:
        args.context = args.context.resolve()
    rows = table()
    if args.list:
        for row in rows.values():
            print(f"{row['id']} | {row['path']} | check={row['check']} | requires={','.join(row['deps'])} | after={','.join(row.get('after', []))} | {row['note']}")
        return 0
    from export_registry import registries, register, import_registries
    if args.register:
        if args.check:
            ap.error('--register cannot be combined with --check')
        register(ROOT, read(args.register))
        return 0
    if args.import_registries:
        if args.check:
            ap.error('--import-registries cannot be combined with --check')
        import_registries(ROOT)
        return 0
    if args.registries:
        registries(ROOT, args.check)
        return 0
    if args.command:
        if not args.clips or not args.beats:
            ap.error('motion requires clips and --beats')
        if set(args.metrics.split(','))-{'tilt', 'pass', 'wet', 'gap', 'held'}:
            ap.error('motion beats support per-frame metrics; use pose_check.py --frames for temporal metrics')
        return motion(args, rows)
    selected = args.family
    if args.changed is not None:
        selected = sorted(affected(rows, changed_files(args.changed)))
        if 'motion-corrections' in selected:
            families = read(ROOT/LORE/'recipe.json')['export_families']
            selected = [key for key in selected if key not in families or
                        families[key].get('birth_pose') or families[key].get('side_profile')]
    elif args.rebuild_motion:
        selected = ['motion-corrections'] + [name for name, data in read(ROOT/LORE/'recipe.json')['export_families'].items()
                                           if data.get('birth_pose') or data.get('side_profile')]
    elif not selected:
        if not args.check:
            ap.error('select --family, --changed or --rebuild-motion (use --list to see families)')
        selected = list(rows)
    unknown = set(selected)-rows.keys()
    if unknown:
        ap.error('Unknown families: '+', '.join(sorted(unknown)))
    results = []
    failed = set()
    for row in ordered(rows, selected):
        # Independent checks always run, even when a producer check fails.
        if not args.check and failed.intersection(row['deps']):
            result = dict(id=row['id'], status='BLOCKED', seconds=0, detail='Failed dependency')
            print(f"{row['id']}: BLOCKED 0s Failed dependency", flush=True)
        else:
            before = {p: (ROOT/p).read_bytes() for p in ('art/runtime-sources.json', 'art/catalog.json')} if not args.check else {}
            result = run(row, args.check, args.report.parent/'logs')
            if not args.check:
                # Existing exporters still write aggregates. Import only their actual edits;
                # newly registered shards never get overwritten by an unchanged aggregate.
                import_registries(ROOT, before)
        results.append(result)
        if result['status'] in ('FAIL', 'MISSING', 'BLOCKED'):
            failed.add(row['id'])
    if not args.check:
        registries(ROOT, False)
    else:
        try:
            registries(ROOT, True)
            results.append(dict(id='registries', status='PASS', seconds=0, detail='Aggregate matches shards'))
        except ValueError as error:
            results.append(dict(id='registries', status='FAIL', seconds=0, detail=str(error)))
            failed.add('registries')
    write(args.report, results)
    return int(bool(failed))


def motion(args, rows):
    # Reuse preview's parser: the exact same seconds feed rendering and pose metrics.
    from preview import beat_times
    times = beat_times(args)
    cfg = read(ROOT/LORE/'recipe.json')
    import runpy
    members = {name: data['clips'] if 'clips' in data else runpy.run_path(str(ROOT/LORE/data['clip_source']))[data['clip_field']]
               for name, data in cfg['export_families'].items()}
    families = []
    for clip in args.clips:
        matches = [name for name, clips in members.items() if clip in clips]
        if not matches:
            raise ValueError('No declared family for clip: '+clip)
        families += matches[:1]
    for name in dict.fromkeys(families):
        if run(rows[name], False, args.report.parent/'logs')['status'] != 'PASS':
            return 1
    for clip in args.clips:
        topic = 'motion-'+clip.replace('_', '-')
        subprocess.run([sys.executable, 'scripts/preview.py', 'strip', topic, '--model', args.model,
                        '--clip', clip, '--beats', str(args.beats)], cwd=ROOT, check=True)
        if not (ROOT/'scripts/pose_check.py').exists():
            print('REMAINING: pose_check.py is not on this checkout; G2 metrics not run.', flush=True)
            return 1
        folder = args.report.parent/'motion'/clip
        folder.mkdir(parents=True, exist_ok=True)
        requests = []
        for i, t in enumerate(times):
            request = dict(model=args.model, clip=clip, t=str(t), metric=args.metrics.split(','),
                           pose_out=str(folder/f'pose-{i:03}.jsonl'))
            if args.context:
                request['context'] = read(args.context)
            if args.water is not None:
                request['water'] = args.water
            requests.append(request)
        # G2 supports one time or a uniform range, so its JSON-line service preserves
        # arbitrary storyboard beats without rounding them onto a uniform grid.
        result = subprocess.run([sys.executable, 'scripts/pose_check.py', '--serve'], cwd=ROOT,
                                input=''.join(json.dumps(r, allow_nan=False)+'\n' for r in requests),
                                stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, encoding='utf-8')
        (folder/'metrics.jsonl').write_text(result.stdout, encoding='utf-8')
        (folder/'stderr.log').write_text(result.stderr, encoding='utf-8')
        if result.returncode:
            print(f'{clip}: pose input/check error; see {folder}', flush=True)
            return result.returncode
        responses = [json.loads(line) for line in result.stdout.splitlines() if line.strip()]
        if len(responses) != len(times) or any('error' in row for row in responses):
            print(f'{clip}: pose service did not return all beat results; see {folder}', flush=True)
            return 2
        counts = {}
        for response in responses:
            for row in response['results']:
                counts[row['verdict']] = counts.get(row['verdict'], 0)+1
        print(f'{clip}: pose report {counts}; {folder}', flush=True)
        if counts.get('error'):
            return 2
    return 0


if __name__ == '__main__':
    try:
        sys.exit(main())
    except (ValueError, OSError, subprocess.CalledProcessError) as error:
        print(f'export: {error}', file=sys.stderr)
        sys.exit(1)
