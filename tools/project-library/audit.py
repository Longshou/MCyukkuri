"""Check classification, critical source coverage, project links and Git ignore rules."""
from pathlib import Path
import json,re,subprocess
from urllib.parse import unquote
R=Path(__file__).resolve().parents[2];L=R/'art/library'
catalog=json.loads((L/'catalog.json').read_text(encoding='utf-8'))
retired=set(json.loads((L/'collections.json').read_text(encoding='utf-8')).get('retired',{}))
saved={}
for c in catalog['collections']:
    manifest=json.loads((R/c['package']['manifest']).read_text(encoding='utf-8'))
    for e in manifest['files']:saved[e['path']]=e
missing=[];critical=[]
for p in (R/'variants').rglob('*'):
    if retired & set(p.relative_to(R).parts):continue
    if not p.is_file() or {'reference','hakkei-reference','frames','__pycache__'}&set(p.parts):continue
    if p.name.startswith('reference-'):continue
    if p.suffix in {'.bbmodel','.py','.js','.cjs'} or p.name.endswith(('.animation.json','-rig.json')):
        critical.append(p.relative_to(R).as_posix())
        if critical[-1] not in saved:missing.append(critical[-1])
assert not missing,missing
assert len(catalog['collections'])==len({c['id'] for c in catalog['collections']})
ids={c['id'] for c in catalog['collections']}
assert not ids & retired,ids & retired
for c in catalog['collections']:
    assert set(c['dependencies'])<=ids,c['id']
    if c['preview']:assert (R/c['preview']).exists(),c['preview']
bad=[];links=0
docs=[R/'README.md',R/'PROJECT_MAP.md',R/'art/README.md',R/'docs/ASSET_WORKFLOW.md',R/'archives/README.md',*L.rglob('*.md'),*(R/'art/props').rglob('*.md')]
for doc in docs:
    if retired & set(doc.relative_to(R).parts):continue
    for target in re.findall(r'\]\(([^)]+)\)',doc.read_text(encoding='utf-8')):
        if '://' in target or target.startswith('#'):continue
        target=unquote(target.split('#')[0]);links+=1
        if not (doc.parent/target).exists():bad.append((doc.relative_to(R).as_posix(),target))
assert not bad,bad
paths=[c['package']['archive'] for c in catalog['collections']]+['tools/project-library/manage.py','art/library/catalog.json','PROJECT_MAP.md']
result=subprocess.run(['git','check-ignore','--stdin'],cwd=R,input='\n'.join(paths)+'\n',text=True,capture_output=True)
assert result.returncode==1 and not result.stdout,result.stdout+result.stderr
report=dict(critical_files_preserved=len(critical),classified_collections=len(ids),links_checked=links,broken_links=[],source_packages_ignored_by_git=False,retired_excluded_from_catalog=True,retired_workspace_dirs_remaining=[name for name in sorted(retired) if (R/'variants'/name).exists()])
(L/'audit.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8');print(report)
