"""Save approved N020 and recolor the approved N033 shapes to warm brown-gray."""
from pathlib import Path
from PIL import Image
import copy
import hashlib
import json

R=Path(__file__).resolve().parents[1]
D=R/'drafts/expression-review/round6'
O=R/'expressions/component-review'
DATE='2026-09-12'
read=lambda p:json.loads(p.read_text(encoding='utf-8-sig'))
sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()


def save(path,data):
    path.parent.mkdir(parents=True,exist_ok=True)
    content=json.dumps(data,ensure_ascii=False,indent=2)
    if not path.exists() or path.read_text(encoding='utf-8')!=content:path.write_text(content,encoding='utf-8')


previous_path=R/'drafts/expression-review/round5/candidates.json'
previous=read(previous_path)['variants']['N033']
variants=[]
top=(74,67,63);middle=(116,107,96);bottom=(153,143,128)
for v in previous:
    p=copy.deepcopy(v['preset']);letter=v['key']
    for side in ['left','right']:
        slot='face_eye_'+side
        with Image.open(R/p['parts'][slot]) as source:im=source.convert('RGBA')
        old_outline=(42,31,31,255) if letter in ['A','B'] else (43,34,38,255)
        white=(247,240,224,255) if letter in ['A','B'] else (245,239,225,255)
        for y in range(im.height):
            for x in range(im.width):
                c=im.getpixel((x,y))
                if not c[3] or c==white:continue
                if c==old_outline:new=(63,51,46,255)
                elif letter=='B' and c==(30,18,20,255):new=(45,40,36,255)
                else:
                    low,high=(24,223) if letter in ['A','B'] else (40,175)
                    t=max(0,min(1,(c[0]-low)/(high-low)))
                    a,b,u=(top,middle,t*2) if t<=.5 else (middle,bottom,(t-.5)*2)
                    new=tuple(round(A+(B-A)*u) for A,B in zip(a,b))+(c[3],)
                im.putpixel((x,y),new)
        digest=hashlib.sha256(im.tobytes()).hexdigest()
        target=D/'parts'/(digest[:20]+'.png');target.parent.mkdir(parents=True,exist_ok=True)
        if not target.exists():im.save(target)
        p['parts'][slot]=target.relative_to(R).as_posix()
    label={'A':'棕灰无反光','B':'棕灰暗瞳孔','C':'棕灰半垂眼'}[letter]
    p['recipe']['eyes']='warm-brown-gray-'+letter.lower()
    variants.append(dict(key=letter,label=label,preset=p,
        description='沿用第五轮眼型与加宽瞳仁，只把眼线和虹膜改为此前的偏棕灰色阶。',
        review_note='眼型与瞳仁宽度已认可，本轮查看棕灰配色。'))
save(D/'candidates.json',dict(date=DATE,status='color-awaiting-review',variants={'N033':variants},
    style_review=dict(status='accepted-direction',source=previous_path.relative_to(R).as_posix(),sha256=sha(previous_path),
                      note='用户认可样式，未单独指定 A/B/C；保留三款，统一改色。'),
    palette=dict(top=top,middle=middle,bottom=bottom,outline=[63,51,46],pupil=[45,40,36])))

idx=read(R/'expressions/index.json')
path=R/idx['presets']['puff_teary'];p=read(path)
snapshot=O/'before-n020-round6-acceptance.json'
if not snapshot.exists():save(snapshot,p)
p['status']='accepted';p['art']='User accepted round-5 B expression with E3-PUFF eyes and fitted tears on '+DATE
p['review_decision']=dict(status='accepted',date=DATE,selected_base='round4/N020-B',accepted_eye_components='E3-PUFF',
    note='用户确认“20通过”：保存第五轮闭眼线与泪花适配的完整展示版本。')
save(path,p)
decisions=read(O/'n016-n040-decisions.json')
if 'N020' not in decisions['accepted_new']:decisions['accepted_new'].append('N020')
decisions.update(round=6,redrawn=['N033'])
decisions['notes'].update(N020='第五轮完整展示版已通过。',N033='样式已认可；三款统一改回偏棕灰，配色待看。')
paths=[idx['presets']['puff_teary'],*p['parts'].values()]
decisions['confirmed_versions']['N020']=dict(date=DATE,selection='第五轮 B 构图、已确认闭眼线、贴合泪花',
    files={ref:sha(R/ref) for ref in sorted(set(paths))})
decisions['selected_components_N020']['status']='complete expression accepted in round 6'
decisions['N033_style_review']=dict(source='drafts/expression-review/round5/candidates.json',status='accepted direction; recolor pending')
save(O/'n016-n040-decisions.json',decisions)
progress=read(O/'review-progress.json')
for row in progress['records']:
    if row['code']=='N020':row['status']='accepted'
progress['pending_order']=[id for id in progress['pending_order'] if id!='puff_teary']
progress['pending_count']=len(progress['pending_order'])
progress['accepted_new_codes']=sorted(row['code'] for row in progress['records'] if row['status']=='accepted')
save(O/'review-progress.json',progress)
ps={id:read(R/ref) for id,ref in idx['presets'].items()}
md='# 待确认表情\n\n剩余 %d 项；本批次只剩 N033 棕灰配色待看，眼型方向已认可。N020 已确认第五轮完整展示版。\n\n| 编号 | 表情 |\n| --- | --- |\n'%progress['pending_count']
md+=''.join('| %s | %s |\n'%(ps[id]['review_code'],ps[id]['label']) for id in progress['pending_order'])
(O/'pending-expressions.md').write_text(md,encoding='utf-8')
print('N020 accepted; N033 A/B/C recolored, shape and iris widths preserved.')
