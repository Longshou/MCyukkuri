"""Freeze the approved compound delivery action, including shared dependencies."""
from pathlib import Path
import hashlib
import json
import zipfile

ROOT = Path(__file__).resolve().parent
ACTION = 'action.reimu.viviparous_birth.v1'

def digest(data): return hashlib.sha256(data).hexdigest()

def main():
    provenance = json.loads((ROOT/'viviparous/source-provenance.json').read_text(encoding='utf-8'))
    refs = set(provenance['source_sha256']) | {
        'viviparous/index.html', 'viviparous/source-provenance.json',
        'viviparous/verification.json', 'viviparous/contact-closeup.png',
        'LIFECYCLE.md', 'actions/reimu-viviparous-birth.json',
        'tests/viviparous.cjs', 'tests/viviparous-contact.cjs',
        'tests/viviparous-browser.cjs', 'snapshot_viviparous.py',
    }
    contents = {ref:(ROOT/ref).read_bytes() for ref in sorted(refs)}
    for ref,expected in provenance['source_sha256'].items():
        assert digest(contents[ref]) == expected, f'Rebuild preview before saving: {ref}'
    entries = {ref:dict(bytes=len(data),sha256=digest(data)) for ref,data in contents.items()}
    token = digest(json.dumps(entries,sort_keys=True).encode())[:20]
    folder = ROOT/'actions/snapshots'; folder.mkdir(exist_ok=True)
    archive = folder/(token+'.zip'); manifest = folder/(token+'.manifest.json')
    if not archive.exists():
        with zipfile.ZipFile(archive,'x',zipfile.ZIP_DEFLATED) as z:
            for ref,data in contents.items(): z.writestr(ref,data)
        manifest.write_text(json.dumps(dict(action=ACTION,files=entries),ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    assert json.loads(manifest.read_text(encoding='utf-8')) == dict(action=ACTION,files=entries)
    with zipfile.ZipFile(archive) as z:
        assert set(z.namelist()) == set(entries)
        for ref,e in entries.items(): assert digest(z.read(ref)) == e['sha256'], ref
    index = ROOT/'actions/approved-snapshots.json'
    records = json.loads(index.read_text(encoding='utf-8')) if index.exists() else []
    record = dict(action=ACTION,archive=archive.relative_to(ROOT).as_posix(),manifest=manifest.relative_to(ROOT).as_posix(),sha256=digest(archive.read_bytes()),files=len(entries))
    if record not in records: records.append(record)
    index.write_text(json.dumps(records,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(f'Frozen and hash-verified {len(entries)} files: {archive}')

if __name__ == '__main__': main()
