"""Verify the current R3.1 authoring handoff without starting Minecraft."""
from pathlib import Path
import hashlib,json,re,zipfile

P=Path(__file__).resolve().parent
ROOT=P.parents[3]
read=lambda f:json.loads(f.read_text(encoding='utf-8'))
sha=lambda f:hashlib.sha256(f.read_bytes()).hexdigest()
report=read(P/'reports/current/exports.json')
assert len(read(P/'catalog.json'))==44
assert len(report['assets'])==43 and len(report['body_variants'])==64 and len(report['wound_scenes'])==14
for name,digest in report['authoring_sources'].items():assert sha(P/name)==digest,name
for key,folder in [('assets','assembled'),('body_variants','body-variants'),('wound_scenes','wound-scenes')]:
    for entry in report[key]:
        base=P/folder/entry['id']
        for file,field in [(entry['id']+'.bbmodel','bbmodel_sha256'),(entry['id']+'.mesh.json','mesh_sha256'),('texture.png','texture_sha256')]:
            assert sha(base/file)==entry[field],file
        if key=='wound_scenes':assert sha(base/'recipe.json')==entry['recipe_sha256']
        else:
            for suffix in ['.obj','.mtl']:assert (base/(entry['id']+suffix)).stat().st_size>0
source=read(P/'wound-source.json')
for species,refs in source['sources'].items():
    for ref in refs:assert sha(ROOT/ref['path'])==ref['sha256'],ref['path']
    assert source['actors'][species]==read(ROOT/refs[0]['path'])
for file in ['body-browser.json','browser.json']:
    b=read(P/'reports/current'/file);assert b['ok'] and not b['errors']
    for field,path in [('previewDataSha256','previews/current/data.js'),('viewerSha256','previews/current/viewer.js'),('exportsSha256','reports/current/exports.json')]:assert b[field]==sha(P/path),file
b=read(P/'reports/current/wound-browser.json');assert b['ok'] and not b['errors']
for field,path in [('sourceSha256','wound-surface.js'),('editorSha256','wound-editor.js'),('dataSha256','previews/current/data.js')]:assert b[field]==sha(P/path),field
b=read(P/'reports/current/wound-static.json');assert b['status']=='passed' and b['sourceSha256']==sha(P/'wound-surface.js')
with zipfile.ZipFile(P/'archive/body-r3.zip') as archive:old=json.loads(archive.read('reports/current/exports.json'))
current={e['id']:e for e in report['assets']}
for old_entry in old['assets']:
    for field in ['bbmodel_sha256','mesh_sha256','texture_sha256']:assert current[old_entry['id']][field]==old_entry[field],old_entry['id']
approval=read(P/'../../ecology/house-egg/approval.json')
assert sha(P/'../../ecology/house-egg/approved-r2.4.zip')==approval['archive_sha256']
assert [e['id'] for e in read(P/'catalog.json') if e.get('confirmation',{}).get('status')=='user_approved']==['house_egg','house_egg_cracked']
docs=[P/f for f in ['README.md','BODY-EFFECTS.md','INTEGRATION.md','WOUND-PLACEMENT.md','REFERENCES-BODY.md','reports/current/visual-review.md','reports/current/body-visual-review.md','reports/current/wound-visual-review.md']]+[ROOT/f for f in ['docs/PROP_ART_INVENTORY.md','art/props/README.md','standards/yukkuri-library/drafts/README.md']]
for file in docs:
    for link in re.findall(r'\]\(([^)]+)\)',file.read_text(encoding='utf-8')):
        if not link.startswith(('http','#')):assert (file.parent/link.split('#')[0]).exists(),(file,link)
result={'date':'2026-09-13','status':'passed','fingerprints':{name:sha(P/name) for name in ['wound-surface.js','wound-editor.js','wound-source.json','previews/current/data.js','previews/current/viewer.js','previews/current/index.html','reports/current/exports.json']},'checks':['44 entries, 64 prior body variants, 14 surface scenes','all authoring and export hashes','raw actor inputs and selected source hashes','browser/static reports match current inputs','all prior 43 asset outputs unchanged from R3 archive','egg approval archive and two approved entries preserved','active documentation links'],'not_checked':['Minecraft runtime integration','Blockbench application import','user art confirmation']}
(P/'reports/current/wound-delivery.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
print(json.dumps({'status':'passed','checks':result['checks']},ensure_ascii=False))
