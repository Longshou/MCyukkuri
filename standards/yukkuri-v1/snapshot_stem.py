"""Freeze the approved procedural action and its exact build dependencies."""
from pathlib import Path
import hashlib
import json
import zipfile

ROOT = Path(__file__).resolve().parent
DESCRIPTOR = 'actions/reimu-stem-birth-v2.json'
def digest(b): return hashlib.sha256(b).hexdigest()

def main():
    provenance = json.loads((ROOT/'lifecycle/source-provenance.json').read_text(encoding='utf-8'))
    action = json.loads((ROOT/DESCRIPTOR).read_text(encoding='utf-8'))['id']
    refs = set(provenance['source_sha256']) | {
        'characters/reimu.json', 'lifecycle/index.html', 'lifecycle/source-provenance.json',
        'lifecycle/verification.json', 'LIFECYCLE.md', DESCRIPTOR,
        'tests/lifecycle.cjs', 'tests/lifecycle-browser.cjs', 'snapshot_stem.py',
    }
    contents = {ref:(ROOT/ref).read_bytes() for ref in sorted(refs)}
    for ref,expected in provenance['source_sha256'].items():
        assert digest(contents[ref]) == expected, f'Rebuild preview before saving: {ref}'
    entries = {ref:dict(bytes=len(b),sha256=digest(b)) for ref,b in contents.items()}
    token = digest(json.dumps(entries,sort_keys=True).encode())
    folder = ROOT/'actions/snapshots'; folder.mkdir(exist_ok=True)
    archive = folder/(token[:20]+'.zip')
    manifest = folder/(token[:20]+'.manifest.json')
    if not archive.exists():
        with zipfile.ZipFile(archive,'x',zipfile.ZIP_DEFLATED) as z:
            for ref,b in contents.items(): z.writestr(ref,b)
        manifest.write_text(json.dumps(dict(action=action,files=entries),ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    assert json.loads(manifest.read_text(encoding='utf-8')) == dict(action=action,files=entries)
    with zipfile.ZipFile(archive) as z:
        assert set(z.namelist()) == set(entries)
        for ref,e in entries.items(): assert digest(z.read(ref)) == e['sha256'], ref
    index = ROOT/'actions/approved-snapshots.json'
    records = json.loads(index.read_text(encoding='utf-8')) if index.exists() else []
    record = dict(action=action,archive=archive.relative_to(ROOT).as_posix(),manifest=manifest.relative_to(ROOT).as_posix(),sha256=digest(archive.read_bytes()),files=len(entries))
    if record not in records: records.append(record)
    index.write_text(json.dumps(records,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(f'Frozen and hash-verified {len(entries)} files: {archive}')

if __name__ == '__main__': main()
