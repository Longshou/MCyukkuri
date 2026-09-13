"""Archive verified current screenshots and exact tested class hashes without changing runtime content."""
from datetime import datetime,timezone
import hashlib
import json
from pathlib import Path
import shutil

ROOT=Path(__file__).resolve().parents[3]
WORK=ROOT/'.local/p7-freezer-notebook'
REPORT=ROOT/'docs/validation/cold-storage'
ART=ROOT/'art/props/cold-storage/previews/current'
test=json.loads((WORK/'latest-test.json').read_text(encoding='utf-8'))
preview=json.loads((WORK/'active-preview.json').read_text(encoding='utf-8'))
testroot=Path(test['snapshot']);previewroot=Path(preview['snapshot'])
checks=json.loads(Path(test['report']).read_text(encoding='utf-8'))
client=json.loads((previewroot/'client-evidence.json').read_text(encoding='utf-8'))
assert test['exit']==0 and checks and all(c['pass'] for c in checks)
page_checks=[c['result']['pageHeights'] for c in client if c.get('op')=='book-page']
assert len(page_checks)==2 and all(max(heights)<=128 for heights in page_checks)
states=[c for c in client if 'stored' in c]
assert any(c['stored']==1 for c in states) and states[-1]['stored']==0 and states[-1]['targetId']>=0 and not states[-1]['heldToken']
REPORT.mkdir(parents=True,exist_ok=True);ART.mkdir(parents=True,exist_ok=True)
shutil.copy2(test['report'],REPORT/'freezer-checks.json')
shutil.copy2(previewroot/'client-evidence.json',REPORT/'client-evidence.json')
names=['freezer-overview','freezer-front','freezer-side','freezer-back','notebook-guide','notebook-storage','notebook-observation']
images={}
for name in names:
    source=previewroot/'p0-control/screenshots'/f'{name}-0000.png'
    destination=ART/f'{name}.png';shutil.copy2(source,destination)
    images[str(destination.relative_to(ROOT)).replace('\\','/')]=hashlib.sha256(destination.read_bytes()).hexdigest()
classes={}
source_hashes={}
java=ROOT/'src/main/java/io/github/longshou/mcyukkuri'
paths=list((java/'freezer').glob('*.java'))+list((java/'notebook').glob('*.java'))+[java/'item/CarrierItem.java',java/'item/NotebookItem.java',java/'life/YukkuriWorldData.java']
for path in paths:
    source_hashes[str(path.relative_to(ROOT)).replace('\\','/')]=hashlib.sha256(path.read_bytes()).hexdigest()
    relative=Path('classes/java/main')/path.relative_to(ROOT/'src/main/java').with_suffix('.class')
    test_bytes=(testroot/relative).read_bytes();preview_bytes=(previewroot/relative).read_bytes()
    assert test_bytes==preview_bytes,relative
    classes[str(relative).replace('\\','/')]=hashlib.sha256(test_bytes).hexdigest()
manifest={'recorded_utc':datetime.now(timezone.utc).isoformat(),'scope':'cold-storage, carrier actual-hand fix, notebook, survival advancements',
          'source_confirmation':'cold-storage visual design pending user confirmation',
          'game_test':{'checks':len(checks),'failed':0,'exit':test['exit'],'snapshot':str(testroot.relative_to(ROOT)).replace('\\','/')},
          'client':{'snapshot':str(previewroot.relative_to(ROOT)).replace('\\','/'),'owned_pid_stopped':preview['pid'],
                    'actual_controls':['capture','store','break','rebuild','retrieve','release','open_guide','observe'],
                    'guide_pages':len(page_checks[0]),'guide_max_height':max(page_checks[0]),'observation_pages':len(page_checks[1]),'observation_max_height':max(page_checks[1]),'font_locale':'zh_cn'},
          'not_run':['dedicated multiplayer','long survival balance','power-loss recovery','English/Japanese client layout'],
          'source_sha256':source_hashes,'matching_test_and_client_class_sha256':classes,'screenshots_sha256':images}
(REPORT/'manifest.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
cards=''.join(f'<figure><a href="{name}.png"><img src="{name}.png" alt="{name}"></a><figcaption>{name}</figcaption></figure>' for name in names)
(ART/'index.html').write_text('<!doctype html><meta charset="utf-8"><title>冷冻收容与观察手册 · 当前实机</title><style>body{background:#edf1ed;color:#234151;font:17px system-ui;margin:32px}main{display:grid;grid-template-columns:repeat(auto-fit,minmax(440px,1fr));gap:20px}figure{margin:0;background:white;padding:12px;border-radius:8px}img{width:100%}a{color:inherit}p{max-width:850px;line-height:1.7}</style><h1>冷冻收容与观察手册</h1><p>本轮实际客户端画面。47项引擎检查通过，真实取放与拆柜重建恢复通过；中文指南34页、个体观察14页均未溢出。柜体造型待用户确认，未进行多人专服或长期生存平衡验收。</p><p><a href="model-sheet.png">制作源多角度预览</a> · <a href="../../../../../docs/validation/cold-storage/manifest.json">指纹与验证范围</a></p><main>'+cards+'</main>',encoding='utf-8')
print('Archived',len(checks),'passing checks,',len(names),'screenshots, matching test/client classes')
