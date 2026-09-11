"""Validate portable approved action packages and update their catalog cards."""
from pathlib import Path
import hashlib
import json
import zipfile

ROOT=Path(__file__).resolve().parent
def main():
    index=json.loads((ROOT/'motions/procedural/index.json').read_text(encoding='utf-8'))
    for action in index['actions']:
        archive=ROOT/action['archive']
        with zipfile.ZipFile(archive) as package:
            descriptor=json.loads(package.read(action['descriptor_in_archive']))
            assert descriptor['id']==action['id'] and descriptor['status']==action['status']
            for key in ['preview_in_archive','build_in_archive','runtime_in_archive']:assert action[key] in package.namelist()
            provenance=json.loads(package.read(action.get('provenance_in_archive','viviparous/source-provenance.json')))
            for ref,expected in provenance['source_sha256'].items():
                assert hashlib.sha256(package.read(ref)).hexdigest()==expected,ref
        action['sha256']=hashlib.sha256(archive.read_bytes()).hexdigest()
    (ROOT/'motions/procedural/catalog.js').write_text('const PROCEDURAL_ACTIONS='+json.dumps(index['actions'],ensure_ascii=False,separators=(',',':'))+';\n',encoding='utf-8')
    print(f"Registered and verified {len(index['actions'])} portable procedural composite package(s).")

if __name__=='__main__':main()
