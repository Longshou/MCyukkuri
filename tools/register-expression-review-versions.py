"""Keep old and new expression versions visible for user review."""
from pathlib import Path
from PIL import Image
import json, shutil, hashlib
ROOT=Path(__file__).resolve().parent.parent
R=ROOT/'standards/yukkuri-library'
TMP=ROOT/'reference/recovered-expression-versions'
def read(p):return json.loads(p.read_text(encoding='utf-8-sig'))
def save(p,v):p.parent.mkdir(parents=True,exist_ok=True);p.write_text(json.dumps(v,ensure_ascii=False,indent=2),encoding='utf-8')
(TMP/'components/common').mkdir(parents=True,exist_ok=True)
shutil.copy2(R/'components/common/face.json',TMP/'components/common/face.json')
source=(ROOT/'tools/author-library-expressions.py').read_text(encoding='utf-8')
source=source.split('# Overlay-only source sprites')[0].replace(";R=ROOT/'standards/yukkuri-library';",";R=ROOT/'reference/recovered-expression-versions';")
exec(compile(source,'recover-new-expression-versions','exec'),{'__file__':str(ROOT/'tools/author-library-expressions.py')})
idx=read(R/'expressions/index.json');parts=read(R/'expressions/parts/index.json')
known={}
for f in (R/'expressions/parts').glob('*.png'):
 im=Image.open(f).convert('RGBA');known[(im.size,im.tobytes())]=f.relative_to(R).as_posix()
restored=read(R/'expression-restoration.json')['restored']
for id in restored:
 new_id=id+'__new';source_id='waking' if id=='sleepy' else id
 p=read(TMP/f'expressions/presets/{source_id}.json')
 for n,ref in p['parts'].items():
  im=Image.open(TMP/ref).convert('RGBA');sig=(im.size,im.tobytes())
  if sig not in known:
   shutil.copy2(TMP/ref,R/ref);known[sig]=ref;parts[Path(ref).stem]=dict(path=ref,size=list(im.size),sha256=hashlib.sha256(im.tobytes()).hexdigest())
  p['parts'][n]=known[sig]
 p.update(id=new_id,review_pair=id,status='pending-review',aliases=[])
 save(R/f'expressions/presets/{new_id}.json',p)
 shutil.copy2(TMP/f'expressions/thumbnails/{source_id}.png',R/f'expressions/thumbnails/{new_id}.png')
 idx['presets'][new_id]=f'expressions/presets/{new_id}.json'
# Stable review identifiers are stored on each preset, so later additions do not renumber them.
old_i=new_i=0;signatures={};rows=[]
for id,ref in idx['presets'].items():
 p=read(R/ref);old=id in restored
 if old:old_i+=1;code=f'O{old_i:02}'
 else:new_i+=1;code=f'N{new_i:03}'
 p.update(review_code=p.get('review_code',code),origin='original' if old else 'new',status='retained' if old else 'pending-review')
 p['review_pair']=id+'__new' if old else p.get('review_pair')
 sig=json.dumps({k:p.get(k,default) for k,default in [('parts',{}),('requires',[]),('cheek_scale',1),('geometry_offsets',{})]},sort_keys=True)
 if sig in signatures:p['duplicate_of']=signatures[sig]
 else:signatures[sig]=id
 save(R/ref,p);rows.append(dict(code=p['review_code'],id=id,label=p['label'],origin=p['origin'],pair=p['review_pair']))
save(R/'expressions/index.json',idx);save(R/'expressions/parts/index.json',parts);save(R/'expressions/review-list.json',rows)
(R/'expressions/review-list.md').write_text('# 表情核对清单\n\nO 为原有版，N 为新增版。可直接反馈编号和修改要求。\n\n| 编号 | 表情 | 版本 | ID |\n| --- | --- | --- | --- |\n'+''.join(f"| {r['code']} | {r['label']} | {'原有' if r['origin']=='original' else '新增'} | {r['id']} |\n" for r in rows),encoding='utf-8')
p=R/'build.py';s=p.read_text(encoding='utf-8').replace("status=e['status'],requires=","status=e['status'],origin=e['origin'],review_code=e['review_code'],review_pair=e.get('review_pair'),requires=");p.write_text(s,encoding='utf-8')
p=R/'validate.py';s=p.read_text(encoding='utf-8').replace("['retained','needs-redraw']","['retained','needs-redraw','pending-review']").replace("assert sig not in recipe_hashes,(id,recipe_hashes.get(sig));recipe_hashes[sig]=id","assert sig not in recipe_hashes or p.get('duplicate_of')==recipe_hashes[sig],(id,recipe_hashes.get(sig));recipe_hashes.setdefault(sig,id)");p.write_text(s,encoding='utf-8')
for file in ['compare.html','runtime/viewer.html']:
 p=R/file;s=p.read_text(encoding='utf-8')
 s=s.replace("e.label+(e.status==='needs-redraw'?'（待重绘）':'（原有）')","e.review_code+' · '+e.label+(e.origin==='new'?'（新增）':'（原有）')")
 s=s.replace("(e.label+' '+e.id+", "(e.review_code+' '+e.label+' '+e.id+")
 s=s.replace("(exprs[expression].status==='needs-redraw'?'草稿，待重绘':'保留原有表情')","(exprs[expression].origin==='new'?'新增版 · 待核对':'原有版')+' · '+exprs[expression].review_code")
 p.write_text(s,encoding='utf-8')
p=R/'compare.html';s=p.read_text(encoding='utf-8').replace('<span id="exprSource"', '<button id="toggleVersion">切换同名版本</button><span id="exprSource"')
s=s.replace("function sync(){", "get('toggleVersion').onclick=()=>{const pair=exprs[expression]?.review_pair;if(pair){expression=pair;expressionOptions(get('filter').value);sync()}};\nfunction sync(){get('toggleVersion').disabled=!exprs[expression]?.review_pair;")
p.write_text(s,encoding='utf-8')
p=R/'index.html';s=p.read_text(encoding='utf-8').replace('<select id="group"','<select id="origin" aria-label="表情版本"><option value="">原有＋新增</option><option value="original">仅原有版</option><option value="new">仅新增版</option></select><select id="group"')
s=s.replace("filter(e=>(!group||e.group===group)","filter(e=>(!get('origin').value||e.origin===get('origin').value)&&(!group||e.group===group)")
s=s.replace("(e.id+' '+e.label+", "(e.review_code+' '+e.id+' '+e.label+")
s=s.replace("name.textContent=e.label;","name.textContent=e.review_code+' · '+e.label;")
s=s.replace("(e.status==='retained'?'原有表情':'草稿 · 待重绘')","(e.origin==='original'?'原有版':'新增版 · 待核对')")
s=s.replace("get('group').onchange=render;","get('group').onchange=render;get('origin').onchange=render;")
s=s.replace('每个角色使用同一配方。','原有版与新增版均可查看；O 为原有版，N 为新增版，可按编号反馈重绘要求。<a href="expressions/review-list.md">完整编号清单</a>。每个角色使用同一配方。')
p.write_text(s,encoding='utf-8')
p=R/'README.md';s=p.read_text(encoding='utf-8');s='# 表情核对版本\n\n原有与新增均登记在选择器及图鉴，O 编号为原有，N 编号为新增。相同语义可用“切换同名版本”按钮对照；像素一致的版本共用素材。新增状态为待核对，等待用户重绘清单；不覆盖原有版。完整编号见 expressions/review-list.md。\n\n'+s;p.write_text(s,encoding='utf-8')
print('Review versions:',old_i,'original,',new_i,'new')
