"""Verify current bean expressions using the shared pose sampler and rasterizer."""
from pathlib import Path
import datetime
import hashlib
import json
import subprocess
from PIL import Image

HERE=Path(__file__).resolve().parent
LIB=HERE.parents[1]
ROOT=LIB.parents[1]
TMP=ROOT/'.local/bean-face-verification'
TMP.mkdir(parents=True,exist_ok=True)
read=lambda p:json.loads(p.read_text(encoding='utf-8'))
sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
ledger=read(ROOT/'art/runtime-sources.json')['models']
models={};fingerprints={}
expressions=list(ledger['reimu_bean']['textures'])
for ident in ('reimu_bean','marisa_bean'):
    entry=ledger[ident];path=ROOT/entry['runtime']
    assert sha(path)==entry['runtime_sha256']
    assert list(entry['textures'])==expressions
    models[ident]=read(path);fingerprints[ident]=sha(path)
    for name,texture in entry['textures'].items():
        path=ROOT/texture['runtime'];assert sha(path)==texture['sha256']
        (TMP/(ident+'-'+name+'.rgba')).write_bytes(Image.open(path).convert('RGBA').tobytes())
(TMP/'input.json').write_text(json.dumps({'models':models,'expressions':expressions},separators=(',',':')),encoding='utf-8')
run=subprocess.run(['node',str(HERE/'verify_face_motion.cjs'),str(TMP),str(LIB)],check=False)
report=read(TMP/'results.json')
report.update(created_at=datetime.datetime.now(datetime.timezone.utc).isoformat(),source_fingerprints=fingerprints,
    scope={'expressions':expressions,'motions':['waddle','hop','flee'],'samples_per_motion':25,
           'views':['front','oblique'],'features':['left_eye','right_eye','mouth'],'render_size':[320,240]},
    criterion='Zero unexpected coverage of front eye/mouth ink; expected face composite includes intentional tear overlap. Skin-coloured card sides are excluded.',
    commands=['python standards/yukkuri-library/subspecies/deficient/verify_face_motion.py'],
    verifier_sha256={p.name:sha(p)for p in [Path(__file__),HERE/'verify_face_motion.cjs']},
    minecraft='dynamic_recheck_pending',user_confirmation='pending')
out=HERE/'reports/current/face-motion.json';out.parent.mkdir(parents=True,exist_ok=True)
out.write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
raise SystemExit(run.returncode)
