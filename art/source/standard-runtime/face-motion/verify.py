"""Check current house walking facial visibility; no game or resource writes."""
from pathlib import Path
import argparse
import datetime
import hashlib
import json
import subprocess
from PIL import Image

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[3]
TMP = ROOT/'.local/house-face/verification'


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--waddle-only', action='store_true')
    parser.add_argument('--legacy-face-backs', action='store_true', help='Reproduce old opaque face-back occlusion; expected to fail')
    parser.add_argument('--quick', action='store_true', help='Neutral expression at three phases for diagnosis')
    args = parser.parse_args()
    TMP.mkdir(parents=True, exist_ok=True)
    ledger = json.loads((ROOT/'art/runtime-sources.json').read_text(encoding='utf-8'))['models']
    models, fingerprints, texture_fingerprints = {}, {}, {}
    expressions = list(ledger['house_reimu']['textures'])
    for ident in ('house_reimu','house_marisa'):
        record = ledger[ident]
        path = ROOT/record['runtime']
        assert sha(path)==record['runtime_sha256']
        model = json.loads(path.read_text(encoding='utf-8'))
        model['clips'].update(model.pop('behavior_clips', {}))
        models[ident], fingerprints[ident] = model, sha(path)
        assert list(record['textures'])==expressions
        texture_fingerprints[ident] = {}
        for name, texture in record['textures'].items():
            path = ROOT/texture['runtime']
            assert sha(path)==texture['sha256']
            texture_fingerprints[ident][name] = sha(path)
            (TMP/(ident+'-'+name+'.rgba')).write_bytes(Image.open(path).convert('RGBA').tobytes())
    actions = ['waddle'] if args.waddle_only else ['waddle','carry_waddle']
    if args.quick:
        expressions = ['neutral']
    phases = [0,.25,.75] if args.quick else [i/24 for i in range(25)]
    (TMP/'input.json').write_text(json.dumps(dict(models=models, expressions=expressions,
        actions=actions, legacyFaceBacks=args.legacy_face_backs, phases=phases), separators=(',',':')), encoding='utf-8')
    run = subprocess.run(['node',str(HERE/'verify.cjs'),str(TMP),str(ROOT/'standards/yukkuri-library')])
    report = json.loads((TMP/'results.json').read_text(encoding='utf-8'))
    report.update(created_at=datetime.datetime.now(datetime.timezone.utc).isoformat(),
        runtime_sha256=fingerprints, textures=texture_fingerprints,
        scope=dict(actions=actions, expressions=expressions, phases=phases, views=[0,.55],
                   features=['face_eye_left','face_eye_right','face_mouth'], image_size=[320,240]),
        verifier_sha256={p.name:sha(p) for p in (Path(__file__),HERE/'verify.cjs')},
        criterion='Zero unexpected coverage of visible front eye/mouth ink; intentional face-to-face tear overlays retained.',
        slow_walk='Uses the same waddle clip and elapsed game clock; no separate slow animation curve.',
        minecraft='current_targeted_client_recheck_pending', user_confirmation='pending')
    name = 'legacy-face-backs.json' if args.legacy_face_backs else 'quick-check.json' if args.quick else 'waddle-check.json' if args.waddle_only else 'verification.json'
    out = HERE/'reports/current'/name
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
    raise SystemExit(run.returncode)


if __name__=='__main__':
    main()
