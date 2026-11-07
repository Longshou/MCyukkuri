"""Compare authoring consumers against a Git baseline on identical real inputs (no game)."""
import argparse
import contextlib
import io
import json
from pathlib import Path
import runpy
import subprocess
import sys
from unittest.mock import patch
import numpy as np

ROOT = Path(__file__).resolve().parents[1]
LIB = ROOT/'standards/yukkuri-library'
sys.path.insert(0, str(LIB/'motions/variants/contact-safe'))


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('before_ref')
    ap.add_argument('--lore', action='store_true', help='run every lore export family and its unmodified gates')
    ap.add_argument('--inject-arc', action='store_true', help='negative control: displace a solved joint')
    ap.add_argument('--stow', action='store_true', help='exercise both hat/held-stick gates directly from exported fixtures')
    args = ap.parse_args()
    args.before_ref = subprocess.check_output(['git', 'rev-parse', args.before_ref], cwd=ROOT, text=True).strip()
    original_run = runpy.run_path
    def load(relative, old):
        path = ROOT/relative
        text = subprocess.check_output(['git', 'show', args.before_ref+':'+relative], cwd=ROOT).decode('utf-8') if old else path.read_text(encoding='utf-8')
        scope = dict(__file__=str(path), __name__='pose_fixture')
        exec(compile(text, str(path), 'exec'), scope)
        return scope

    rows = []
    outputs = []
    for old in (True, False):
        braid = load('standards/yukkuri-library/motions/variants/contact-safe/braid_chain.py', old)
        import types
        module = types.ModuleType('braid_chain'); module.__dict__.update(braid); sys.modules['braid_chain'] = module
        aquatic = load('standards/yukkuri-library/motions/variants/lore/aquatic_tools.py', old)
        animation = load('scripts/runtime_animation.py', old)
        model = json.loads((ROOT/'src/main/resources/assets/mcyukkuri/yukkuri/marisa.json').read_text(encoding='utf-8'))
        revision = json.loads((ROOT/'src/main/resources/assets/mcyukkuri/yukkuri_presentation/motion_corrections.json').read_text(encoding='utf-8'))['models']['marisa']
        world = [aquatic['palette'](model, revision['clips']['bank_beckon']['tracks'], t) for t in (0, .13, .4)]
        chain = np.array([[0, -i*2, 0] for i in range(7)], float)
        arcs = [braid['arc'](chain, target) for target in ([4, -5, -2], [1, -1, 0], [0, -20, -2])]
        bones = [dict(name='root', parent=None, pivot=[1, 2, 3]), dict(name='hair', parent='root', pivot=[2, 4, 1])]
        clip = {'tracks': {'root': {'rotation': {'0': [4, 15, 8], '1': [-2, 22, 3]}}, 'hair': {'scale': {'0': [1, .8, 1]}}}}
        tracks = animation['compile_tracks'](clip)
        matrices = [animation['matrices'](bones, [], tracks, t) for t in (0, .13, .4)]
        runtime = np.array([[m[b['name']] for b in bones] for m in matrices])
        sys.path[:0] = [str(LIB/'interactions/marisa-braids'), str(LIB/'tools')]
        render = load('standards/yukkuri-library/interactions/marisa-braids/render_checks.py', old)
        triangles = [np.array(render['triangles'](action, t)) for action in render['build'].recipes['actions'] for t in (0, .35)]
        texture = render['read_png'](LIB/'interactions/marisa-braids/assembled/marisa-braids.png')
        pixels = []
        for ts in triangles:
            image = [[(234, 231, 222)]*160 for _ in range(140)]
            render['draw'](image, 0, (160, 140), ts.tolist(), texture, 32, -16, 4, 8.5)
            pixels.append(np.array(image, dtype=int))
        generated = {}; log = io.StringIO()
        if args.stow:
            import placement_fit
            fitted = placement_fit.fitted(ROOT/'src/main/resources/assets/mcyukkuri/yukkuri/marisa.json', placement_fit.load())
            recipe = json.loads((LIB/'motions/variants/lore/recipe.json').read_text(encoding='utf-8'))
            holds = dict(ashore=revision['clips']['braid_item_hold'], aboard=revision['clips']['boat_paddle'])
            with contextlib.redirect_stdout(log):
                for action in ('hat_off_self_aq', 'hat_on_self_aq'):
                    clip = revision['clips'][action]
                    aquatic['stow_gate'](fitted, action, clip['tracks'], clip['props']['item'], holds, recipe['clips'][action])
        if args.lore:
            def run(path, *a, **kw):
                return aquatic if Path(path).resolve() == (LIB/'motions/variants/lore/aquatic_tools.py').resolve() else original_run(path, *a, **kw)
            with patch.object(runpy, 'run_path', run), contextlib.redirect_stdout(log):
                author = original_run(str(LIB/'motions/variants/lore/author.py'))
                def write(path, text, *a, **kw):
                    generated[path.relative_to(ROOT).as_posix()] = text
                    return len(text)
                with patch.object(Path, 'write_text', write):
                    families = json.loads((LIB/'motions/variants/lore/recipe.json').read_text(encoding='utf-8'))['export_families']
                    for family in families:
                        author['export_family'](family)
                        print(('before' if old else 'after')+' lore '+family+' passed', file=sys.__stdout__, flush=True)
                        # Keep every family's generated runtime, rather than just the last family's.
                        key = 'src/main/resources/assets/mcyukkuri/yukkuri_presentation/motion_corrections.json'
                        generated['family:'+family] = generated[key]
        outputs.append(dict(world=world, arcs=arcs, runtime=runtime, triangles=triangles, pixels=pixels, generated=generated, log=log.getvalue()))
        print(('before' if old else 'after')+' sampled'+(' full lore' if args.lore else ''), flush=True)
    a, b = outputs
    if args.inject_arc:
        b['arcs'][0][1, 0] += 1
    for name, tolerance in [('world', 1e-4), ('arcs', 1e-10), ('runtime', 1e-5), ('triangles', 1e-4)]:
        delta = max(float(np.max(np.abs(x-y))) for x, y in zip(a[name], b[name]))
        assert delta <= tolerance, (name, delta)
        rows.append(dict(consumer=name, maximum_delta=delta, tolerance=tolerance))
    changed = max(float(np.any(x != y, axis=2).mean()) for x, y in zip(a['pixels'], b['pixels']))
    assert changed < .01, ('marisa-braids pixel fraction', changed)
    rows.append(dict(consumer='marisa-braids pixels', changed_fraction=changed, tolerance=.01))
    if args.stow:
        assert a['log'] == b['log'], 'Stow gate observations differ'
        print(a['log'], end='')
        print('PASS both hat clips from both holds: identical stow gate observations / verdicts')
    if args.lore:
        folder = ROOT/'.local/reports/pose-consumers'; folder.mkdir(parents=True, exist_ok=True)
        for side, result in [('before', a), ('after', b)]:
            (folder/(side+'-lore.log')).write_text(result['log'], encoding='utf-8')
            (folder/(side+'-exports.json')).write_text(json.dumps(result['generated']), encoding='utf-8')
        # Gate printouts carry counts and rounded clearances; every real gate still raises on failure.
        assert a['log'].replace('-0.00', '0.00') == b['log'].replace('-0.00', '0.00'), 'Lore gate observations differ'
        print('PASS all lore families: identical gate observations / verdicts', flush=True)
    out = ROOT/'.local/reports/pose-consumers-python.json'; out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(dict(ref=args.before_ref, rows=rows), indent=2), encoding='utf-8')
    for row in rows:
        print('PASS', row)
    from pose_client import request
    bone = dict(name='root', parent=-1, pivot=[0, 0, 0])
    for broken in (dict(op='compose', bones=[dict(bone, parent=0)], values=[[0]*9]),
                   dict(op='compose', bones=[bone], values=[[0]])):
        try:
            request(broken)
        except ValueError as error:
            print('PASS rejected', error)
        else:
            raise AssertionError('Malformed authoring input was accepted')
    assert request(dict(op='compose', bones=[bone], values=[[0, 0, 0, 0, 0, 0, 1, 1, 1]]))['world'][0][0][0] == 1
    print('PASS same warm service accepts the next valid request')


if __name__ == '__main__':
    main()
