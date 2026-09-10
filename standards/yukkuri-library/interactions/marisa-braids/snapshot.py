"""Save the approved braid actions and their exact rebuild dependencies."""
from pathlib import Path
import hashlib
import json
import zipfile

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
ACTION = 'action.marisa.braid_interactions.v1'

def digest(data): return hashlib.sha256(data).hexdigest()
def read(p): return json.loads(p.read_text(encoding='utf-8-sig'))

def main():
    report = read(HERE/'verification.json')
    assert report['errors'] == [] and report['hatCare']['turnedTowardWorker']
    assert report['gripInterpolation'] and report['twoBraidSupport'] and report['recovery']
    dependencies = ['geometry.py', 'runtime/motion.js', 'runtime/render.js',
                    'assembled/marisa/index.html', 'assembled/marisa/marisa.bbmodel',
                    'assembled/marisa/marisa.png']
    provenance = read(HERE/'assembled/provenance.json')
    assert digest((ROOT/'assembled/marisa/marisa.bbmodel').read_bytes()) == provenance['sha256']
    files = [p for p in HERE.rglob('*') if p.is_file()
             and not {'snapshots','__pycache__'} & set(p.relative_to(HERE).parts)
             and p.name not in {'approved-snapshots.json'} and p.suffix != '.pyc']
    files.extend(ROOT/p for p in dependencies)
    contents = {p.relative_to(ROOT).as_posix():p.read_bytes() for p in sorted(set(files))}
    entries = {p:dict(bytes=len(data),sha256=digest(data)) for p,data in contents.items()}
    token = digest(json.dumps(entries,sort_keys=True).encode())[:20]
    folder = HERE/'snapshots'; folder.mkdir(exist_ok=True)
    archive = folder/(token+'.zip'); manifest = folder/(token+'.manifest.json')
    if not archive.exists():
        with zipfile.ZipFile(archive,'x',zipfile.ZIP_DEFLATED) as z:
            for name,data in contents.items():
                info=zipfile.ZipInfo(name,date_time=(2026,9,11,0,0,0));info.compress_type=zipfile.ZIP_DEFLATED
                z.writestr(info,data)
        manifest.write_text(json.dumps(dict(action=ACTION,files=entries),ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    assert read(manifest) == dict(action=ACTION,files=entries)
    with zipfile.ZipFile(archive) as z:
        assert set(z.namelist()) == set(entries)
        for name,e in entries.items():assert digest(z.read(name)) == e['sha256'],name
    for p,data in contents.items():assert (ROOT/p).read_bytes() == data,'Source changed while saving: '+p
    index = HERE/'approved-snapshots.json'
    records = read(index) if index.exists() else []
    record = dict(action=ACTION,archive=archive.relative_to(HERE).as_posix(),
                  manifest=manifest.relative_to(HERE).as_posix(),sha256=digest(archive.read_bytes()),
                  files=len(entries),verified=True,
                  preview='interactions/marisa-braids/index.html',
                  rebuild='python interactions/marisa-braids/build.py')
    if record not in records:records.append(record)
    index.write_text(json.dumps(records,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(json.dumps(record,ensure_ascii=False))

if __name__ == '__main__': main()
