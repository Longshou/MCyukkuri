"""Motion cache negative controls and opt-in real compiler byte parity proof.

--integration takes several full compiles; all mutations stay in .local/tmp.
"""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import time
import unittest

from motion_compile_cache import MotionCache, fingerprint, lore_inputs

ROOT = Path(__file__).resolve().parents[1]
LORE = Path('standards/yukkuri-library/motions/variants/lore/recipe.json')
COMPILERS = ['scripts/export_motion_corrections.py', 'scripts/placement_fit.py',
             'standards/yukkuri-library/motions/variants/contact-safe/author.py',
             'standards/yukkuri-library/motions/variants/lore/author.py']


class CacheTests(unittest.TestCase):
    def test_motion_selection_tracks_cache_and_external_inputs(self):
        import export
        rows = export.table()
        for source in ['scripts/motion_compile_cache.py', 'scripts/placement_fit.py',
                       'src/main/resources/assets/mcyukkuri/textures/entity/reimu/puff.png',
                       'src/main/resources/assets/mcyukkuri/yukkuri_presentation/placement_fit.json']:
            self.assertIn('motion-corrections', export.affected(rows, {source}))

    def test_bad_entries_and_full_rebuild(self):
        temporary = ROOT/'.local/tmp'
        temporary.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(dir=temporary) as folder:
            cache = MotionCache(folder, ['model', 'compiler'])
            key = cache.key('clip', 'wake', {'duration': 1})
            cache.put(key, {'tracks': []})
            path = next(Path(folder).rglob('*.json'))
            good = path.read_bytes()
            self.assertEqual(cache.get(key), {'tracks': []})
            for entry in [dict(input='stale', sha256=fingerprint({'tracks': []}), value={'tracks': []}),
                          dict(input=key, sha256='wrong', value={'tracks': []})]:
                path.write_text(json.dumps(entry), encoding='utf-8')
                self.assertIsNone(cache.get(key))
            path.write_bytes(b'{truncated')
            self.assertIsNone(cache.get(key))
            path.write_bytes(good)
            self.assertIsNone(cache.get(cache.key('clip', 'wake', {'duration': 2})))
            self.assertIsNone(MotionCache(folder, ['different-model']).get(
                MotionCache(folder, ['different-model']).key('clip', 'wake', {'duration': 1})))
            self.assertIsNone(MotionCache(folder, ['model', 'compiler'], full=True).get(key))

    def test_transitive_recipe_dependencies(self):
        cfg = dict(clips=dict(a={'base': 'b'}, b={'base': 'c'}, c={'mirror': {'of': 'd'}},
                              d={'duration': 1}, unrelated={'duration': 3}), sample_seconds=.04)
        before = fingerprint(lore_inputs(cfg, 'a'))
        cfg['clips']['unrelated']['duration'] = 4
        self.assertEqual(before, fingerprint(lore_inputs(cfg, 'a')))
        cfg['runtime_revision'] = 'another-publication'
        cfg['revision'] = 19
        self.assertEqual(before, fingerprint(lore_inputs(cfg, 'a')))
        cfg['clips']['d']['duration'] = 2
        self.assertNotEqual(before, fingerprint(lore_inputs(cfg, 'a')))


def integration():
    temporary = ROOT/'.local/tmp'
    temporary.mkdir(parents=True, exist_ok=True)
    folder = Path(tempfile.mkdtemp(dir=temporary, prefix='motion-compile-proof-'))
    print('Proof logs: '+str(folder), flush=True)
    baseline, current = folder/'baseline', folder/'current'
    selected = ['src/main/resources/assets/mcyukkuri/yukkuri',
                'src/main/resources/assets/mcyukkuri/textures/entity',
                'src/main/resources/assets/mcyukkuri/yukkuri_presentation',
                'standards/yukkuri-library/motions/variants/contact-safe',
                'standards/yukkuri-library/motions/variants/lore',
                'standards/yukkuri-library/interactions/marisa-hat-vessel',
                'standards/yukkuri-library/interactions/braid-water-rescue',
                'standards/yukkuri-library/body-standard/features/marisa-single-braid',
                'art/source/motion-corrections']
    for name in selected:
        shutil.copytree(ROOT/name, baseline/name)
    (baseline/'scripts').mkdir()
    for name in COMPILERS:
        data = subprocess.check_output(['git', 'show', 'origin/main:'+name], cwd=ROOT)
        (baseline/name).write_bytes(data)
    # Ensure this really is a baseline of origin/main, including runtime inputs.
    changed = subprocess.check_output(['git', 'diff', '--name-only', 'origin/main', '--', *selected], cwd=ROOT)
    assert set(changed.decode().splitlines()) <= set(COMPILERS), changed.decode()
    shutil.copytree(baseline, current)
    for name in [*COMPILERS, 'scripts/motion_compile_cache.py']:
        shutil.copyfile(ROOT/name, current/name)
    results = []
    def run(root, label, *args, expected=0):
        start = time.monotonic()
        with (folder/(label+'.log')).open('w', encoding='utf-8') as log:
            proc = subprocess.run([sys.executable, '-u', 'scripts/export_motion_corrections.py', *args],
                                  cwd=root, stdout=log, stderr=subprocess.STDOUT)
        row = dict(label=label, seconds=round(time.monotonic()-start, 3), exit=proc.returncode)
        results.append(row)
        (folder/'results.json').write_text(json.dumps(results, indent=2)+'\n', encoding='utf-8')
        print(row, flush=True)
        assert proc.returncode == expected, folder/(label+'.log')
    def digest(root):
        return hashlib.sha256((root/'src/main/resources/assets/mcyukkuri/yukkuri_presentation/motion_corrections.json').read_bytes()).hexdigest()
    def outputs(root):
        files = ['src/main/resources/assets/mcyukkuri/yukkuri_presentation/motion_corrections.json',
                 *[p.relative_to(root).as_posix() for p in (root/'src/main/resources/assets/mcyukkuri/textures/entity').glob('*/cheek_puff.png')]]
        return {p: hashlib.sha256((root/p).read_bytes()).hexdigest() for p in files}
    run(baseline, 'origin-full')
    run(baseline, 'origin-check', '--check')
    run(current, 'new-full', '--full')
    assert outputs(current) == outputs(baseline)
    print('PASS baseline runtime SHA-256 A='+digest(baseline), flush=True)
    entry = next((current/'.local/cache/motion-compile').rglob('*.json'))
    data = json.loads(entry.read_bytes()); data['input'] = 'deliberately-stale'
    entry.write_text(json.dumps(data), encoding='utf-8')
    run(current, 'stale-entry-check', '--check')
    assert json.loads(entry.read_bytes())['input'] == entry.stem
    print('PASS stale input hash detected and recompiled', flush=True)
    run(current, 'warm-check', '--check')
    resource = current/'src/main/resources/assets/mcyukkuri/yukkuri_presentation/motion_corrections.json'
    original = resource.read_bytes()
    resource.write_bytes(original+b' ')
    run(current, 'tampered-runtime-check', '--check', expected=1)
    resource.write_bytes(original)
    # One real clip edit in both isolated trees; compare with the unmodified
    # origin compiler as an independent full reference, not a cache-aware path.
    for root in (baseline, current):
        cfg = json.loads((root/LORE).read_bytes())
        cfg['clips']['call_child']['duration'] += .04
        cfg['runtime_revision'] += '-cache-proof'
        (root/LORE).write_text(json.dumps(cfg, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
    run(current, 'one-clip-incremental')
    run(current, 'one-clip-check', '--check')
    run(baseline, 'one-clip-full')
    assert outputs(current) == outputs(baseline)
    print('PASS one-clip byte parity '+digest(current), flush=True)
    # Apply a real placement pivot change to one model, leaving other fits intact.
    fit_rel = Path('src/main/resources/assets/mcyukkuri/yukkuri_presentation/placement_fit.json')
    for root in (baseline, current):
        fit = json.loads((root/fit_rel).read_bytes())
        fit['models']['reimu']['bone_pivots'][0]['pivot'][0] += .01
        (root/fit_rel).write_text(json.dumps(fit, ensure_ascii=False)+'\n', encoding='utf-8')
    run(current, 'placement-incremental')
    run(baseline, 'placement-full')
    assert outputs(current) == outputs(baseline)
    print('PASS placement byte parity '+digest(current), flush=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--integration', action='store_true')
    args, remaining = parser.parse_known_args()
    if args.integration:
        integration()
    else:
        unittest.main(argv=[sys.argv[0], *remaining])
