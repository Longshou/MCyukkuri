"""Fit the reviewed injury language to every registered character, without runtime selection."""
from pathlib import Path
from concurrent.futures import ThreadPoolExecutor
import argparse
import gzip
import hashlib
import html
import io
import json
import math
import subprocess
import sys
import numpy as np
from PIL import Image
import build
from species import registry, LABELS, ASSETS
from dry_face import face_boxes, apply_style

HERE=build.HERE;ROOT=build.ROOT;OUT=HERE/'assembled/species';WORK=build.WORK/'species'
CAT=registry();MODELS=CAT['models'];KINDS=build.KINDS

def encoded(obj):return (json.dumps(obj,ensure_ascii=False,indent=2)+'\n').encode()
def put(path,data,check):build.write_or_check(path,data,check)

def damage(model,grade,core=False):
    data=build.sample_damage(grade,core,build.RULES)
    health=MODELS[model]['max_health'];loss=math.ceil(health*.7) if core else data['acceptedLoss']
    if MODELS[model]['house']:
        if core:raise ValueError('House art has no core exposure')
        if grade!=3:raise ValueError('House burns directly use grade-three art')
        return dict(maxHealth=health,healthBefore=health,acceptedLoss=1,healthAfter=health-1,
            grade=3,actualLossGrade=1,previousWoundGrade=0,fittedOpening=False,
            selection='house_burn_direct_grade_three',source='Explicit user rule: house burns directly use grade three',
            runtime_selected=False,label='家油受烧直接用三级外观；实际扣血不因此改写，运行选择待接入')
    return {**data,'maxHealth':health,'healthBefore':health,'acceptedLoss':loss,'healthAfter':health-loss,
        'label':f'实际扣 {loss:g} 点，生命 {health:g} → {health-loss:g}/{health:g}；'+('满足露核条件' if core else '不露中枢')}

def meshes(model):
    if MODELS[model]['house']:return []
    jobs=[]
    for site in ['hair','skin']:
        for style in KINDS:
            for grade in [1,2,3]:
                for state in ['normal']+(['intact'] if grade==3 and style in build.CORE_KINDS else []):
                    ident=f'{site}-{style}-{grade}-{state}'
                    spec=dict(kind=build.EVIDENCE['causes'][build.CAUSES[style]]['kind'],style=style,cause=build.CAUSES[style],severity=grade,
                        core=state=='intact',state=state,**MODELS[model]['sample_sites'][site],x=0,damage=damage(model,grade,state=='intact'))
                    # Bean forms have a short head above a long torso. Fit the
                    # opening to that real head instead of moving it onto the belly.
                    if model.endswith('_bean'):
                        radius=build.RECIPE['core_openings'][style] if state=='intact' else build.RECIPE['grades'][style][grade-1]['radius']
                        spec['radius']=[radius[0],radius[1]*(.4 if site=='hair' else .55)]
                    jobs.append(dict(id=f'{model}-{ident}',model=model,site=site,spec=spec,output=str(OUT/model/(ident+'.json.gz'))))
    return jobs

def thermal(model,scope,grade):return dict(model=model,scope=scope,severity=grade,cause='hot_floor' if scope=='sole' else 'body_fire',damage=damage(model,grade))

def run(node,script,jobs,label,check=False):
    path=WORK/(label+'.json');path.write_bytes(encoded(jobs))
    proc=subprocess.run([node,str(script),str(path)]+(['--check'] if check else []),capture_output=True,text=True,encoding='utf-8')
    if proc.returncode:raise RuntimeError(label+'\n'+proc.stdout[-1200:]+'\n'+proc.stderr[-2400:])

def produce(model,args):
    folder=OUT/model;folder.mkdir(parents=True,exist_ok=True);info=MODELS[model]
    if info['house']:
        # Only this generator's former samples are retired; no source inputs.
        for old in [*folder.glob('*.json.gz'),*folder.glob('dry-*-1.png'),*folder.glob('dry-*-2.png')]:
            if args.check:raise ValueError('House mechanical sample must be removed: '+str(old))
            old.unlink()
    m=json.loads((ASSETS/f'yukkuri/{model}.json').read_text(encoding='utf-8'));m['texture_size']=info['texture_size'];boxes=face_boxes(m)
    expressions=[]
    for path in sorted((ASSETS/f'textures/entity/{model}').glob('*.png')):
        expressions.append(path.stem)
        for grade in info['thermal_grades']:
            image=apply_style(Image.open(path),boxes,path.stem,grade)
            b=io.BytesIO();image.save(b,format='PNG');put(folder/f'dry-{path.stem}-{grade}.png',b.getvalue(),args.check)
    jobs=meshes(model);run(args.node,HERE/'export.cjs',jobs,'export-'+model,args.check)
    growth=CAT['growth']['house']['stages'] if info['house'] else CAT['growth']['stages']
    meta=dict(model=model,label=info['label'],runtime_selected=False,body_part=info['body_part'],fit=info['fit'],
        receivers=[] if info['house'] else ['hair','skin'],styles=[] if info['house'] else KINDS,grades=info['thermal_grades'],meshes=[Path(j['output']).name for j in jobs],
        expressions=expressions,face_boxes=boxes,texture_size=info['texture_size'],
        thermal=[thermal(model,scope,g) for scope in ['sole','whole_body'] for g in info['thermal_grades']],
        growth=dict(stages=growth,stage_health=info['stage_health'],adult_only=info['adult_only'],form_scale=1.5 if info['adult_only'] else 1,
            rule='Use the SAME actual body anchor/profile, followed by the same growth transform as the character; never use fixed world-size decals.'),
        core_example=None if info['house'] else damage(model,3,True),protected_parts=info['protected_parts'])
    put(folder/'manifest.json',encoded(meta),args.check)
    if args.preview:render(model,args,jobs)
    print(('Verified ' if args.check else 'Adapted ')+model+f': {len(jobs)} meshes, {len(expressions)*len(info['thermal_grades'])} face atlases',flush=True)

def camera(model,close=False,site='skin'):
    info=MODELS[model];s=info['fit']['scale'];o=info['fit']['offset']
    # Per-axis body fit determines actual positions; cameras only frame the result.
    center=[3.5,4.5+info['sample_sites'][site]['y'],.5] if close and site=='hair' else [0,4.5+info['sample_sites'][site]['y'],-2] if close else [0,4.5,.5]
    center=[center[i]*s[i]+o[i] for i in range(3)]
    size=max(s[0],s[1]);span=(5.5 if close else 9.6)*size
    if not close:
        if model in ['remilia','flandre']:span=11.0
        if model.startswith('reimu_wasa'):span=10.5
        if model=='marisa_tsumuri':span=17;center[1]=8
    return dict(width=480,height=440,az=1.7 if close and site=='hair' else .22 if close else .45,el=.05 if close else .08,span=span,cx=center[0],cy=center[1],cz=center[2])

def render(model,args,mesh_jobs):
    folder=WORK/model;folder.mkdir(parents=True,exist_ok=True);info=MODELS[model]
    texture=folder/'neutral.rgba';texture.write_bytes(Image.open(ASSETS/f'textures/entity/{model}/neutral.png').convert('RGBA').tobytes())
    for g in info['thermal_grades']:(folder/f'dry-{g}.rgba').write_bytes(Image.open(OUT/model/f'dry-neutral-{g}.png').convert('RGBA').tobytes())
    jobs=[]
    for m in mesh_jobs:
        spec=m['spec'];key=Path(m['output']).name.removesuffix('.json.gz')
        jobs.append(dict(**camera(model,True,m['site']),model=model,hairWound=spec,woundMesh=m['output'],texture=str(texture),
            woundDecal=str(WORK/f"{spec['style']}-{spec['severity']}.rgba"),out=str(folder/key)))
    jobs.append(dict(**camera(model),model=model,texture=str(texture),out=str(folder/'healthy')))
    for scope in ['sole','whole_body']:
        for g in info['thermal_grades']:
            cam=camera(model);cam['el']=-.30 if scope=='sole' else .08
            jobs.append(dict(**cam,model=model,thermal=thermal(model,scope,g),thermalTexture=str(WORK/f'thermal-{g}.rgba'),
                texture=str(folder/f'dry-{g}.rgba') if scope=='whole_body' else str(texture),out=str(folder/f'{scope}-{g}')))
    # Headwear removal and actual hair absence demonstrate occlusion boundaries.
    if not info['house']:
        base=next(j for j in jobs if Path(j['out']).name=='skin-dent-2-normal')
        jobs.append({**base,'hideAccessory':True,'out':str(folder/'hatless-dent')})
        jobs.append({**base,'hideAccessory':True,'hideHair':True,'out':str(folder/'hairless-dent')})
    scales=[1.5] if info['adult_only'] else [.32,.65,1.0] if info['house'] else [.14,.5,1.0,1.2]
    for scale in scales:
        growth_cases=[('burn',next(j for j in jobs if Path(j['out']).name==('whole_body-3' if info['house'] else 'whole_body-2')))]
        if not info['house']:growth_cases.insert(0,('bite',next(j for j in jobs if Path(j['out']).name=='skin-bite-1-normal')))
        for label,base in growth_cases:
            cam=camera(model);cam['span']*=max(scales);cam['cy']*=max(scales)
            jobs.append({**base,**cam,'displayScale':scale,'out':str(folder/f'growth-{label}-{scale}')})
    if model=='marisa':
        aquatic=CAT['aliases']['aquatic_marisa']
        cam={**camera(model),'span':16,'cy':7}
        jobs.append(dict(**cam,model=model,texture=str(texture),accessoryScale=aquatic['accessory_scale'],accessoryAnchor=aquatic['accessory_anchor'],out=str(folder/'aquatic-healthy')))
        for g in [1,2,3]:
            base=next(j for j in jobs if Path(j['out']).name==f'whole_body-{g}')
            aquatic=CAT['aliases']['aquatic_marisa']
            jobs.append({**base,**cam,'accessoryScale':aquatic['accessory_scale'],'accessoryAnchor':aquatic['accessory_anchor'],'out':str(folder/f'aquatic-{g}')})
    run(args.node,HERE.parent/'surface.cjs',jobs,'render-'+model)
    images={Path(j['out']).name:Image.fromarray(np.fromfile(j['out']+'.rgba',np.uint8).reshape(j['height'],j['width'],4)) for j in jobs}
    dest=args.preview_root/'.local/previews/hair-wounds/species'/model;dest.mkdir(parents=True,exist_ok=True)
    footer='制作源适配 · 当前运行素材的静态对照 · 原伤因与真实扣血规则不变 · 动态效果由试玩确认'
    if info['house']:
        cards=[('健康','同一模型与高度',images['healthy']),('全身烧伤 · 直接三级','全身焦枯，头饰同步变色',images['whole_body-3']),('足部烧伤 · 直接三级','仅足底，不缩小全身',images['sole-3'])]
        build.source.sheet(info['label']+' · 受烧直接三级','用户限定家油仅烧伤，直接使用三级外观；实际扣血独立结算，不增加机械伤或露核。',cards,3,(380,320),footer).save(dest/'thermal.png')
        build.source.sheet(info['label']+' · 烧伤适配','',cards,3,(350,270),footer).save(dest/'overview.png')
        build.source.sheet(info['label']+' · 成长与烧伤','受烧直接三级外观；赤/子/成生命1/2/4，存活取决于真实扣血。',[(f'{scale:g} 倍 · 三级烧伤','家油实际成长范围',images[f'growth-burn-{scale}']) for scale in scales],3,(330,300),footer).save(dest/'growth.png')
        (dest/'index.html').write_text('<!doctype html><meta charset="utf-8"><title>'+info['label']+'烧伤适配</title><style>body{background:#f4f0e6;font:18px system-ui}img{display:block;width:100%;max-width:1450px;margin:20px auto}</style><a href="../index.html">全部品种</a><h1>家油仅烧伤，受烧直接三级</h1>'+''.join(f'<img src="{n}.png">' for n in ['overview','thermal','growth']),encoding='utf-8')
        for n in ['hair','skin','core','face']:(dest/(n+'.png')).unlink(missing_ok=True)
        return
    for site in ['hair','skin']:
        cards=[(build.LABELS[k]+f' · {g}级',build.NOTES[k][g-1],images[f'{site}-{k}-{g}-normal']) for k in KINDS for g in [1,2,3]]
        build.source.sheet(info['label']+(' · 覆发区域' if site=='hair' else ' · 面部皮面'),
            '八种机械伤各三级；现有稀发/不足结构不补回头发，真实缺损之外保留五官。',cards,3,(400,320),footer).save(dest/f'{site}.png')
    cards=[(f'{build.LABELS[k]} · 完好中枢外露',f"实际扣 {damage(model,3,True)['acceptedLoss']} 点，剩 {damage(model,3,True)['healthAfter']}/{info['max_health']}；通道成立",images[f'{site}-{k}-3-intact']) for site in ['hair','skin'] for k in build.CORE_KINDS]
    build.source.sheet(info['label']+' · 中枢外露','有真实大缺损和低血量条件才显示；无损坏/取出空腔分支。',cards,4,(330,300),footer).save(dest/'core.png')
    cards=[('健康','同一模型与高度',images['healthy'])]+[(f'全身烧伤 {g}级','高度不变、脸颊收窄；完整饰物保持刚体',images[f'whole_body-{g}']) for g in [1,2,3]]
    cards += [('健康','足底范围对照',images['healthy'])]+[(f'足部烧伤 {g}级','只覆盖该模型足底，不缩小全身',images[f'sole-{g}']) for g in [1,2,3]]
    build.source.sheet(info['label']+' · 三级热损','按模型体表坐标连续覆盖；耳尾/翼架与头发、帽壳/彩晶分别登记。',cards,4,(330,300),footer).save(dest/'thermal.png')
    cards=[(f'{scale:g} 倍 · '+('一级咬伤' if k=='bite' else '二级烧伤'),'同一世界视距；成长与伤口一起缩放',images[f'growth-{k}-{scale}']) for k in ['bite','burn'] for scale in scales]
    build.source.sheet(info['label']+' · 成长与体型','灵胖只展示成年 1.5 倍；家油以自身成体为基准，其余按现有成长范围。',cards,len(scales),(330,300),footer).save(dest/'growth.png')
    cards=[('一级咬伤','牙印不串联',images['skin-bite-1-normal']),('二级凹伤','皮面/眼睛同场凹陷',images['skin-dent-2-normal']),('摘饰凹伤','真实头发仍在',images['hatless-dent']),('无发皮面凹伤','不加肤色底误盖五官',images['hairless-dent'])]
    build.source.sheet(info['label']+' · 五官与遮挡','各自 UV、虹膜颜色与既有表情；无底伤纹。',cards,4,(330,300),footer).save(dest/'face.png')
    cards=[('健康','',images['healthy']),('二级裂伤','局部断发',images['hair-tear-2-normal']),('二级咬伤','保留齿缘',images['skin-bite-2-normal']),('二级烧伤','干瘦焦枯',images['whole_body-2'])]
    build.source.sheet(info['label']+' · 适配概览','',cards,4,(280,270),footer).save(dest/'overview.png')
    if model=='marisa':
        build.source.sheet('水生魔理沙 · 共用身体、扩大帽饰','同一伤口/烧伤素材；帽饰按实际水生倍率 1.5 显示。',[('健康','水生1.5倍帽饰',images['aquatic-healthy'])]+[(f'烧伤 {g}级','布帽同步焦枯，保持尺寸',images[f'aquatic-{g}']) for g in [1,2,3]],4,(330,320),footer).save(dest/'aquatic.png')
    sections=['overview','thermal','face','growth','hair','skin','core']+(['aquatic'] if model=='marisa' else [])
    (dest/'index.html').write_text('<!doctype html><meta charset="utf-8"><title>'+info['label']+'伤口适配</title><style>body{background:#f4f0e6;font:18px system-ui}img{display:block;width:100%;max-width:1450px;margin:20px auto}nav{padding:20px}</style><nav><a href="../index.html">全部品种</a> · '+info['label']+'</nav>'+''.join(f'<img loading="lazy" src="{n}.png" alt="{n}">' for n in sections),encoding='utf-8')

def main():
    ap=argparse.ArgumentParser(description=__doc__);ap.add_argument('--node',default='node');ap.add_argument('--check',action='store_true');ap.add_argument('--preview',action='store_true');ap.add_argument('--preview-root',type=Path,default=ROOT);ap.add_argument('--models',nargs='*');ap.add_argument('--workers',type=int,default=4)
    args=ap.parse_args();WORK.mkdir(parents=True,exist_ok=True);OUT.mkdir(parents=True,exist_ok=True)
    put(HERE/'species.json',encoded(CAT),args.check)
    for name in [f'{k}-{g}' for k in KINDS+['thermal'] for g in [1,2,3]]:
        (WORK/(name+'.rgba')).write_bytes(Image.open(build.OUT/(name+'.png')).convert('RGBA').tobytes())
    names=args.models or list(MODELS)
    with ThreadPoolExecutor(max_workers=args.workers) as pool:list(pool.map(lambda name:produce(name,args),names))
    if args.models:return
    inputs=[HERE/n for n in ['adapt.py','species.py','species.cjs','species.json','author.cjs','thermal.cjs','dry_face.py','recipe.json','materials.py','export.cjs','build.py','grading.py','sources.json']]+[HERE.parent/'surface.cjs',HERE.parent/'cavity.cjs',HERE.parent/'recipe.json',ROOT/'preview/review/pose.js']
    inputs += [ASSETS/'yukkuri_presentation'/n for n in ['living.json','subspecies.json','family.json','placement_fit.json','hat_vessel.json']]
    inputs += [ROOT/'src/main/java/io/github/longshou/mcyukkuri'/n for n in ['rare/MyonLife.java','entity/YukkuriEntity.java','subspecies/SubspeciesAssets.java','subspecies/HatVessel.java','family/GrowthSize.java','body/BodyConstants.java','body/BodyLife.java','body/InjuryModel.java','body/BodySigns.java']]
    inputs += list((ASSETS/'yukkuri').glob('*.json'))
    inputs += [p for m in MODELS for p in (ASSETS/f'textures/entity/{m}').glob('*.png')]
    outputs={p.relative_to(OUT).as_posix():hashlib.sha256(p.read_bytes()).hexdigest() for m in MODELS for p in (OUT/m).iterdir()}
    data=dict(format='mcyukkuri.wound-species-coverage.v1',runtime_selected=False,model_count=len(MODELS),models=list(MODELS),aliases=CAT['aliases'],
        mesh_count=sum(len(meshes(m)) for m in MODELS),face_atlas_count=sum(1 for n in outputs if n.endswith('.png')),thermal_sample_count=sum(len(v['thermal_grades'])*2 for v in MODELS.values()),
        filling_palettes={key:[name for name,value in MODELS.items() if value['filling_palette']==key] for key in build.RECIPE['filling_palettes']},
        source_sha256={p.relative_to(ROOT).as_posix():hashlib.sha256(p.read_bytes() if p.suffix=='.png' else p.read_text(encoding='utf-8').encode()).hexdigest() for p in inputs},outputs=outputs)
    put(OUT/'manifest.json',encoded(data),args.check)
    if args.preview:
        dest=args.preview_root/'.local/previews/hair-wounds/species';rows=''
        palette_cards=[]
        for model in ['reimu','marisa','alice','chen','myon','remilia','flandre','patchouli']:
            info=MODELS[model];palette=build.RECIPE['filling_palettes'][info['filling_palette']]
            rgba=np.fromfile(WORK/model/'skin-tear-3-intact.rgba',np.uint8).reshape(440,480,4)
            palette_cards.append((info['label']+' · '+palette['label'],'同一三级裂伤露核样例；RGB 为项目美术',Image.fromarray(rgba)))
        build.source.sheet('同一创腔 · 品种内馅配色','灵梦／魔理沙为豆沙，爱丽丝为卡仕达，橙为巧克力，妖梦为白巧克力，捕食种为肉包；帕秋莉等未明确品种沿用默认色，不断言口味。',palette_cards,4,(360,320),
            '制作源静态预览 · 馅料名称见 Wiki／项目登记 · RGB 色板为项目美术 · 动态效果待试玩').save(dest.parent/'filling-palettes.png')
        (dest.parent/'filling-palettes.html').write_text('<!doctype html><meta charset="utf-8"><title>品种内馅配色</title><style>body{background:#f4f0e6;font:18px system-ui;margin:24px}img{display:block;width:100%;max-width:1400px;margin:auto}a{color:#456447}</style><a href="index.html">伤口总览</a> · <a href="species/index.html">全部品种</a><img src="filling-palettes.png" alt="豆沙、卡仕达、巧克力、白巧克力与肉包创腔对照">',encoding='utf-8')
        for k,v in MODELS.items():
            links=f'<a href="{k}/thermal.png">三级烧伤</a> · <a href="{k}/growth.png">成长</a>'
            if not v['house']:links+=f' · <a href="{k}/hair.png">覆发伤级</a> · <a href="{k}/skin.png">皮面伤级</a>'
            rows+=f'<article><h2>{v["label"]}'+(' · 仅烧伤' if v['house'] else '')+f'</h2><a href="{k}/index.html"><img loading="lazy" src="{k}/overview.png"></a><p>{links}</p></article>'
        (dest/'index.html').write_text('<!doctype html><meta charset="utf-8"><title>全品种伤口适配</title><style>body{background:#f4f0e6;font:18px system-ui;margin:24px}article{max-width:1450px;margin:30px auto}img{width:100%}a{color:#456447}</style><a href="../index.html">伤口总览</a> · <a href="../filling-palettes.html">品种内馅配色</a><h1>24 套模型 · 全品种伤口适配</h1><p>家油仅足部/全身烧伤，受烧直接用三级外观；其余22套另有八种机械伤各三级及受条件限制的完好中枢外露。按各自发型、体表、UV 与生命值适配。水生魔理沙复用身体，另展示1.5倍帽饰；灵胖只展示成年形态。全身烧伤同时影响布质头饰，壳与彩晶受烟熏；独立附件不随脸颊压窄。制作源静态适配；实际伤位、动态遮挡、治疗与中枢命中仍由动作博物馆确认。</p>'+rows,encoding='utf-8')
        root=dest.parent/'index.html';page=root.read_text(encoding='utf-8');link='<section id="species-link"><h2><a href="species/index.html">查看全部 24 套品种与形态适配 →</a></h2><p>含家油、捕食种、亚种、先天不足、灵胖及成长对照；另见<a href="filling-palettes.html">品种内馅配色</a>。</p></section>'
        if 'id="species-link"' not in page:page=page.replace('<nav>',link+'<nav>',1)
        else:
            start=page.index('<section id="species-link">');end=page.index('</section>',start)+len('</section>')
            page=page[:start]+link+page[end:]
        root.write_text(page,encoding='utf-8')
    print(f'Coverage: {len(MODELS)} models / {data["mesh_count"]} meshes / {data["face_atlas_count"]} face atlases / {data["thermal_sample_count"]} thermal samples')

if __name__=='__main__':main()
