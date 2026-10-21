"""Export evidenced mechanical wounds, extent-based burns and continuous central filling."""
from pathlib import Path
import argparse
import hashlib
import io
import json
import subprocess
import sys
import numpy as np
from PIL import Image

HERE=Path(__file__).resolve().parent
ROOT=HERE.parents[4]
sys.path.insert(0,str(HERE.parent))
import preview as source
from materials import material
from coverage import build_coverage, preview_table
from dry_face import apply_style, face_boxes
from grading import read_rules, sample_damage, examples

RECIPE=json.loads((HERE/'recipe.json').read_text(encoding='utf-8'))
RULES=read_rules(ROOT)
if RULES['grade_divisor']!=RECIPE['grading']['divisor'] or RULES['core_health_ratio']!=RECIPE['grading']['core_health_ratio']:
    raise ValueError('Art recipe must be updated to match actual injury constants')
OUT=HERE/'assembled'
WORK=ROOT/'.local/hair-wounds-work'
KINDS=['scratch','tear','dent','puncture','thorn','impale','bite','blast']
CORE_KINDS=['tear','dent','bite','blast']
CAUSES={'scratch':'friction_contact','tear':'blade_cut','dent':'blunt_impact','puncture':'arrow_impact','thorn':'cactus_contact','impale':'dripstone_impact','bite':'wolf_bite','blast':'explosion_impact'}
EVIDENCE=json.loads((HERE/'sources.json').read_text(encoding='utf-8'))
LABELS={'scratch':'擦伤','tear':'裂伤','dent':'凹伤','puncture':'窄刺伤','thorn':'细刺接触','impale':'尖锥穿压','bite':'咬伤','blast':'爆裂伤'}
NOTES={
 'scratch':['少量断续浅擦痕；头发保留','较多擦痕与局部毛束磨损','密集擦损与碎屑状浅破皮；不露核'],
 'tear':['细窄闭合切痕；不露馅','裂口张开、断发参差；露普通馅','不规则深撕裂；保留翻折毛束'],
 'dent':['轻度压陷；皮面与头发一起下沉','明显凹陷与压折纹；不删发','凹陷内压碎开裂；折发仍附着'],
 'puncture':['窄小入口与短裂纹','入口加深；不扩成横切口','深窄刺口；不为露核放大'],
 'thorn':['少数离散细刺痕；不剃发','接触区细刺痕增多','接触区密集细刺痕；不造大洞'],
 'impale':['尖锥初入的纵向刺痕','宽尖锥造成纵向楔口','穿压加深；与窄箭孔区分'],
 'bite':['上下咬合痕；未大范围缺损','咬合边缘张开，露出普通馅','参差咬缺；保留外围头发'],
 'blast':['冲击压纹与放射短裂','不规则撕开，非长直刀口','压力撕裂的大缺损；不自动烧焦']}


def mesh_jobs():
    jobs=[]
    for model,site in [('reimu','hair'),('marisa','hair'),('reimu','skin')]:
        for kind in KINDS:
            for grade in [1,2,3]:
                states=['normal']+(['intact'] if grade==3 and kind in CORE_KINDS else [])
                for state in states:
                    ident=f'{model}-{site}-{kind}-{grade}-{state}'
                    spec=dict(kind=EVIDENCE['causes'][CAUSES[kind]]['kind'],style=kind,cause=CAUSES[kind],severity=grade,core=state!='normal',state=state,
                              az=1.7 if site=='hair' else .22,x=0,y=1.15 if site=='hair' else -.6,damage=sample_damage(grade,state=='intact',RULES))
                    jobs.append(dict(id=ident,model=model,site=site,spec=spec,output=str(OUT/f'{ident}.json.gz')))
    return jobs


def write_or_check(path,data,check):
    if check:
        if not path.exists() or path.read_bytes()!=data:raise ValueError(f'Stale output: {path}')
    else:
        path.parent.mkdir(parents=True,exist_ok=True);path.write_bytes(data)


def main():
    ap=argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--node',default='node');ap.add_argument('--check',action='store_true')
    ap.add_argument('--preview',action='store_true');ap.add_argument('--preview-root',type=Path,default=ROOT)
    args=ap.parse_args();WORK.mkdir(parents=True,exist_ok=True)
    for cause in EVIDENCE['causes'].values():
        if not cause['evidence'] or not cause['locator']:raise ValueError('Missing cause evidence')
        for ref in cause['evidence']:
            evidence=EVIDENCE['references'][ref]
            if 'path' in evidence and not (ROOT/evidence['path']).is_file():raise ValueError('Missing source path: '+ref)
            if evidence.get('sha256') and hashlib.sha256((ROOT/evidence['path']).read_bytes()).hexdigest()!=evidence['sha256']:raise ValueError('Changed visual reference: '+ref)
    textures={f'{kind}-{grade}.png':material(kind,grade,RECIPE['texture_size']) for kind in KINDS for grade in [1,2,3]}
    textures['scar.png']=material('scar',size=RECIPE['texture_size'])
    textures.update({f'thermal-{g}.png':material('thermal',g,RECIPE['texture_size']) for g in [1,2,3]})
    face_samples={};texture_inputs=[]
    for model in RECIPE['models']:
        model_data=json.loads((ROOT/f'src/main/resources/assets/mcyukkuri/yukkuri/{model}.json').read_text(encoding='utf-8'))
        boxes=face_boxes(model_data)
        for path in sorted((ROOT/f'src/main/resources/assets/mcyukkuri/textures/entity/{model}').glob('*.png')):
            texture_inputs.append(path)
            for grade in [1,2,3]:
                name=f'dry-face-{model}-{path.stem}-{grade}.png';image=apply_style(Image.open(path),boxes,path.stem,grade)
                buf=io.BytesIO();image.save(buf,format='PNG');textures[name]=buf.getvalue()
                face_samples[name]=dict(model=model,expression=path.stem,grade=grade,scope='whole_body',boxes=boxes)
    for kind in KINDS:
        for grade in [1,2,3]:
            pixels=np.asarray(Image.open(io.BytesIO(textures[f'{kind}-{grade}.png'])))
            alpha=pixels[...,3]
            if np.any(alpha[[0,-1],:]) or np.any(alpha[:,[0,-1]]) or np.any(pixels[alpha==0,:3]):raise ValueError('Opaque or dirty exterior: '+kind)
    meshes=mesh_jobs();thermal_specs={f'{model}-{scope}-{g}':dict(model=model,scope=scope,severity=g,cause='hot_floor' if scope=='sole' else 'body_fire',damage=sample_damage(g,False,RULES)) for model in RECIPE['models'] for scope in ['sole','whole_body'] for g in [1,2,3]}
    coverage=build_coverage(json.loads((HERE/'mc-damage-map.json').read_text(encoding='utf-8')),KINDS)
    expected=set(textures)|{'thermal.json','source-coverage.json','damage-examples.json','dry-face.json'}|{Path(m['output']).name for m in meshes}|{'manifest.json'}
    for entry in coverage['sources']:
        for level in entry['levels']:
            for choice in level['choices']:
                for name in ([choice['decal']] if 'decal' in choice else [])+choice.get('mesh_templates',[]):
                    if name not in expected:raise ValueError('Missing source/grade asset: '+name)
                if any(name not in thermal_specs for name in choice.get('thermal_samples',[])):raise ValueError('Missing thermal source/grade asset')
    # Delete only obsolete generated outputs listed by this package's manifest.
    old=json.loads((OUT/'manifest.json').read_text(encoding='utf-8')) if (OUT/'manifest.json').exists() else {}
    for name in set(old.get('outputs',{}))-expected:
        candidate=(OUT/name).resolve()
        if candidate.parent!=OUT.resolve():raise ValueError('Output outside package')
        if candidate.exists():
            if args.check:raise ValueError(f'Obsolete generated output: {candidate}')
            candidate.unlink()
    for name,data in textures.items():write_or_check(OUT/name,data,args.check)
    write_or_check(OUT/'thermal.json',(json.dumps(dict(format='mcyukkuri.authored-thermal.v1',samples=thermal_specs,mask=RECIPE['thermal'],material='thermal-{severity}.png',no_cutout=True),ensure_ascii=False,indent=2)+'\n').encode(),args.check)
    write_or_check(OUT/'source-coverage.json',(json.dumps(coverage,ensure_ascii=False,indent=2)+'\n').encode(),args.check)
    write_or_check(OUT/'damage-examples.json',(json.dumps(examples(RULES),ensure_ascii=False,indent=2)+'\n').encode(),args.check)
    write_or_check(OUT/'dry-face.json',(json.dumps(dict(samples=face_samples,source='body-standard/features/deibu-mature/author.py',runtime_selected=False),ensure_ascii=False,indent=2)+'\n').encode(),args.check)
    jobs=WORK/'export.json';jobs.write_text(json.dumps(meshes),encoding='utf-8')
    subprocess.run([args.node,str(HERE/'export.cjs'),str(jobs)]+(['--check'] if args.check else []),check=True)
    sources=[HERE/n for n in ['recipe.json','author.cjs','export.cjs','build.py','materials.py','sources.json','mc-damage-map.json','coverage.py','thermal.cjs','dry_face.py','grading.py']]
    sources += [HERE/n for n in ['species.json','species.cjs','species.py']]
    sources += [HERE.parent/n for n in ['cavity.cjs','recipe.json','preview.py','export_wounds.py','surface.cjs']]
    sources += [ROOT/'src/main/java/io/github/longshou/mcyukkuri/body/InjuryModel.java',ROOT/'src/main/java/io/github/longshou/mcyukkuri/body/BodySigns.java',ROOT/'src/main/java/io/github/longshou/mcyukkuri/interaction/OperationLife.java']
    sources += [ROOT/'src/main/java/io/github/longshou/mcyukkuri/body/BodyLife.java',ROOT/'src/main/java/io/github/longshou/mcyukkuri/body/BodyCavity.java',ROOT/'standards/yukkuri-library/body-standard/features/deibu-mature/author.py']
    sources += [ROOT/'preview/review/pose.js',ROOT/'src/main/resources/assets/mcyukkuri/yukkuri_presentation/placement_fit.json']
    sources += [ROOT/f'src/main/resources/assets/mcyukkuri/yukkuri/{model}.json' for model in RECIPE['models']]
    manifest=dict(id='hair-wounds',status='authored_pending_review',runtime_selected=False,
      coverage=dict(injury_types=['scratch','tear','dent','scorch'],visual_styles=KINDS,grades=[1,2,3],core_causes=CORE_KINDS,core_states=['intact'],
                    receivers=['reimu:hair','marisa:hair','reimu:skin'],mesh_count=len(meshes),texture_count=len(textures),dry_face_count=len(face_samples),thermal_scopes=['sole','whole_body'],thermal_sample_count=len(thermal_specs),minecraft_sources=47,source_grade_entries=141),
      derived_marks=dict(scar='assembled/scar.png; healed mark, not an active injury',
         dirt='../export_wounds.py: dirt_1..3',mold='../export_wounds.py: mold_1..4',char='../export_wounds.py: char (whole sole)',
         paste='Ordinary filling in every open wound, never a surface dot',core='Derived from authored large grade-three cut/crush/bite/blast; narrow punctures excluded; never an independent round hole'),
      provenance='sources.json; sample.spec.cause is mandatory',
      source_sha256={p.relative_to(ROOT).as_posix():hashlib.sha256(p.read_text(encoding='utf-8').encode()).hexdigest() for p in sources},
      texture_input_sha256={p.relative_to(ROOT).as_posix():hashlib.sha256(p.read_bytes()).hexdigest() for p in texture_inputs},
      outputs={name:hashlib.sha256((OUT/name).read_bytes()).hexdigest() for name in sorted(expected-{'manifest.json'})})
    write_or_check(OUT/'manifest.json',(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n').encode(),args.check)
    if args.preview:make_preview(args.node,args.preview_root,meshes,thermal_specs,coverage)
    print(f'{"Verified" if args.check else "Exported"}: {len(KINDS)*3} mechanical grades, 4 intact core openings, 3 receivers; 12 thermal extent samples; {len(meshes)} meshes, {len(textures)} textures; 47 sources x 3 levels')


def make_preview(node,preview_root,meshes,thermal_specs,coverage):
    jobs=[]
    for model in RECIPE['models']:
        path=ROOT/f'src/main/resources/assets/mcyukkuri/textures/entity/{model}/neutral.png'
        (WORK/f'{model}.rgba').write_bytes(Image.open(path).convert('RGBA').tobytes())
        for grade in [1,2,3]:
            (WORK/f'dry-face-{model}-{grade}.rgba').write_bytes(Image.open(OUT/f'dry-face-{model}-neutral-{grade}.png').convert('RGBA').tobytes())
    for name in [f'{kind}-{grade}' for kind in KINDS for grade in [1,2,3]]+[f'thermal-{g}' for g in [1,2,3]]:
        (WORK/f'{name}.rgba').write_bytes(Image.open(OUT/f'{name}.png').convert('RGBA').tobytes())
    for mesh in meshes:
        spec=mesh['spec'];hair=mesh['site']=='hair'
        jobs.append(dict(model=mesh['model'],width=640,height=580,az=spec['az'],el=.05,span=5.5,
           cx=3.5 if hair else 0,cy=5.6 if hair else 4,cz=.5 if hair else -2,
           hairWound=spec,woundMesh=mesh['output'],texture=str(WORK/f"{mesh['model']}.rgba"),
           woundDecal=str(WORK/f"{spec['style']}-{spec['severity']}.rgba"),out=str(WORK/mesh['id'])))
    for model in RECIPE['models']:
        jobs.append(dict(model=model,width=640,height=580,az=.65,el=.08,span=8.5,cx=0,cy=4.5,cz=.5,texture=str(WORK/f'{model}.rgba'),out=str(WORK/f'{model}-healthy')))
    jobs.append(dict(model='reimu',width=640,height=580,az=.22,el=.05,span=5.5,cx=0,cy=4,cz=-2,texture=str(WORK/'reimu.rgba'),out=str(WORK/'reimu-skin-healthy')))
    for ident,spec in thermal_specs.items():
        atlas=f"dry-face-{spec['model']}-{spec['severity']}" if spec['scope']=='whole_body' else spec['model']
        jobs.append(dict(model=spec['model'],width=640,height=580,az=.65,el=-.28 if spec['scope']=='sole' else .08,span=8.5,cx=0,cy=4.5,cz=.5,thermal=spec,thermalTexture=str(WORK/f"thermal-{spec['severity']}.rgba"),texture=str(WORK/f'{atlas}.rgba'),out=str(WORK/ident)))
    for model in RECIPE['models']:
        jobs.append(dict(model=model,width=640,height=580,az=0,el=0,span=7.5,cx=0,cy=4.5,cz=.5,texture=str(WORK/f'{model}.rgba'),out=str(WORK/f'{model}-dry-front-0')))
        for grade in [1,2,3]:
            base=next(j for j in jobs if j['out']==str(WORK/f'{model}-whole_body-{grade}'))
            jobs.append({**base,'az':0,'el':0,'span':7.5,'out':str(WORK/f'{model}-dry-front-{grade}')})
    for kind in ['tear','dent']:
        mesh=next(m for m in meshes if m['id']==f'reimu-hair-{kind}-3-intact')
        jobs.append({**next(j for j in jobs if j['out']==str(WORK/mesh['id'])),
                     'az':.9,'span':6.0,'out':str(WORK/f'side-{kind}')})
    job_file=WORK/'preview.json';job_file.write_text(json.dumps(jobs),encoding='utf-8')
    subprocess.run([node,str(HERE.parent/'surface.cjs'),str(job_file)],check=True)
    output=preview_root/'.local/previews/hair-wounds';output.mkdir(parents=True,exist_ok=True)
    images={Path(j['out']).name:Image.fromarray(np.fromfile(j['out']+'.rgba',np.uint8).reshape(j['height'],j['width'],4)) for j in jobs}
    (output/'sources').mkdir(exist_ok=True)
    for kind in KINDS:
        for grade in [1,2,3]:images[f'reimu-skin-{kind}-{grade}-normal'].resize((320,290),Image.Resampling.LANCZOS).save(output/f'sources/{kind}-{grade}.png')
    for scope in ['sole','whole_body']:
        for grade in [1,2,3]:images[f'marisa-{scope}-{grade}'].resize((320,290),Image.Resampling.LANCZOS).save(output/f'sources/{scope}-{grade}.png')
    footer='制作源静态预览 · 来源与采用范围见素材说明 · 尚未接入游戏伤因选择、烧伤范围与动态蒙皮'
    for model,site,title in [('reimu','hair','黑发'),('marisa','hair','金发'),('reimu','skin','裸露皮面')]:
        cards=[(f'{LABELS[kind]} · {grade}级',EVIDENCE['causes'][CAUSES[kind]]['label']+' · '+NOTES[kind][grade-1],images[f'{model}-{site}-{kind}-{grade}-normal']) for kind in KINDS for grade in [1,2,3]]
        source.sheet(title+' · MC 伤害机制 × 三级','每行一种伤口，从左到右为 1、2、3 级；形状、开放程度与头发反应随等级改变。',cards,3,(450,360),footer).save(output/f'{model}-{site}-grades.png')
        cards=[(f'{LABELS[kind]} · 完好中枢外露','实际扣 7 点：10 → 3/10；三级深伤且通道成立',images[f'{model}-{site}-{kind}-3-intact']) for kind in CORE_KINDS]
        source.sheet(title+' · 满足实际条件才露出中枢','只保留完好中枢外露。不是每个三级伤都露核，也不是随机挑选的贴图。',cards,2,(560,410),footer).save(output/f'{model}-{site}-core.png')
    cards=[(f'{LABELS[kind]} · 三级深伤',NOTES[kind][2],images[f'reimu-hair-{kind}-3-normal']) for kind in ['tear','dent']]
    cards += [(f'{LABELS[kind]} · 中枢外露','同色同质馅料，中枢保留真实位置与深度',images[f'reimu-hair-{kind}-3-intact']) for kind in ['tear','dent']]
    source.sheet('有来源的伤口 · 连续的馅料材质','刀刃切割：参差断端 ｜ 钝击：压折与碎裂',cards,2,(560,410),footer).save(output/'current.png')
    cards=[('未受伤','相同模型、表情与相机',images['reimu-skin-healthy'])]
    cards += [(LABELS[k]+' · '+str(g)+'级','无肤色外框；真实损伤以外的眼睛保留',images[f'reimu-skin-{k}-{g}-normal']) for k,g in [('dent',1),('dent',2),('tear',2),('tear',3),('dent',3)]]
    source.sheet('眼部叠加 · 无底伤缘与共同凹陷','保留原五官贴图；仅实际缺损范围可以遮蔽器官。',cards,3,(450,360),footer).save(output/'eye-overlap.png')
    cards=[(LABELS[kind]+' · 一级 / 实际扣 2 点',label+' · 闭合伤痕，尚不露馅',images[f'{model}-{site}-{kind}-1-normal']) for kind in ['tear','bite'] for model,site,label in [('reimu','hair','黑发'),('marisa','hair','金发'),('reimu','skin','皮面')]]
    source.sheet('一级裂伤与咬伤 · 加强辨识','清晰切痕与两排分离齿痕，齿间不连线；一级仍为闭合表面。',cards,3,(450,360),footer).save(output/'first-grade.png')
    cards=[('零扣血 · 不新增伤口','护甲／无敌帧后没有实际损失',images['reimu-skin-healthy'])]
    cards += [(f'实际扣 {loss} 点 · {grade}级',f'生命 10 → {10-loss}/10；'+('中枢外露，须通道成立' if core else '闭合痕' if grade==1 else '露普通馅，不露中枢'),images[f'reimu-skin-tear-{grade}-'+('intact' if core else 'normal')]) for loss,grade,core in [(2,1,False),(4,2,False),(5,3,False),(7,3,True)]]
    source.sheet('真实扣血 → 伤级 → 显示','例：最大生命 10 的同类刀割命中；不假定武器面板伤害等于实际损失。',cards,3,(450,360),'同处反复命中另按已有伤势加重；完整公式与当前运行差距见页面下方。').save(output/'damage-thresholds.png')
    yy,xx=np.indices((512,512));board=np.where(((xx//32+yy//32)%2)[...,None],np.array([207,212,213,255]),np.array([239,242,241,255])).astype(np.uint8)
    cards=[(LABELS[k]+f' · {g}级','棋盘格表示透明区域；创腔另由真实几何表现',Image.alpha_composite(Image.fromarray(board),Image.open(OUT/f'{k}-{g}.png').convert('RGBA'))) for k in KINDS for g in [1,2,3]]
    source.sheet('透明贴图 · 无肤色底','所有机械伤三级的 RGBA 贴图；外围透明，不覆盖原有眼睛或头发。',cards,3,(450,310),footer).save(output/'transparent-decals.png')
    cards=[(f'{LABELS[kind]} · 侧向','腔壁与残留头发真实遮挡中枢',images[f'side-{kind}']) for kind in ['tear','dent']]
    source.sheet('保留发缘 · 侧面检查','毛束仍与外侧头发连接，深处中枢不会移到发面上。',cards,3,(450,360),footer).save(output/'sides.png')
    for model in RECIPE['models']:
        cards=[(('足部接触烧伤' if scope=='sole' else '全身烧伤')+f' · {g}级',('热地板接触足底；上部皮面和头发保留' if scope=='sole' else '高度不变；颊部收窄与干枯表情'),images[f'{model}-{scope}-{g}']) for scope in ['sole','whole_body'] for g in [1,2,3]]
        source.sheet(('黑发' if model=='reimu' else '金发')+' · 烧伤只分足部与全身','来源：本次用户明确规则；等级控制同一受热范围内的材质变化，不复制局部伤口。',cards,3,(450,360),footer).save(output/f'{model}-thermal.png')
    cards=[(LABELS[kind]+' · 2级',EVIDENCE['causes'][CAUSES[kind]]['label'],images[f'marisa-hair-{kind}-2-normal']) for kind in KINDS]
    source.sheet('按 MC 实际伤害机制区分','这是项目美术分类，不增加运行伤害枚举；所有样例须有实际扣血和伤因。',cards,3,(450,360),footer).save(output/'mechanisms.png')
    cards=[('未烧伤','同一高度与站位',images['marisa-healthy'])]+[(f'全身烧伤 · {g}级','颊部收窄；高度和前后深度不变',images[f'marisa-whole_body-{g}']) for g in [1,2,3]]
    source.sheet('全身烧伤 · 保持高度的干瘦','按高度分布收窄脸颊与下脸，头发贴合；头饰跟随挂点，保持自身尺寸。',cards,4,(350,350),footer).save(output/'thermal-size.png')
    cards=[(('灵梦' if model=='reimu' else '魔理沙')+(' · 健康' if grade==0 else f' · 干枯 {grade}级'),'炭化、短干纹与暗淡唇色；无下巴描线',images[f'{model}-dry-front-{grade}']) for model in RECIPE['models'] for grade in [0,1,2,3]]
    source.sheet('干瘦脸部 · 正面同高对照','参考灵胖的分部位像素画法；不复制灵胖体型，各表情分别派生。',cards,4,(350,350),footer).save(output/'dry-face.png')
    names=['thermal-size','dry-face','first-grade','damage-thresholds','eye-overlap','mechanisms','reimu-hair-grades','marisa-hair-grades','reimu-skin-grades','transparent-decals','current','reimu-hair-core','marisa-hair-core','reimu-skin-core','sides','reimu-thermal','marisa-thermal']
    for name in ['reimu.png','marisa.png','details.png','impact.png']:
        p=(output/name).resolve()
        if p.parent!=output.resolve():raise ValueError('Preview outside topic')
        if p.exists():p.unlink()
    grading='<section><h2>实际伤害与露核规则</h2><p>新伤按实际扣血：0 不造伤；0 &lt; 损失 ≤ 2 为一级；2 &lt; 损失 ≤ 4 为二级；损失 &gt; 4 为三级。同部位同类型旧伤：min(3, max(旧等级, 本次等级) + [本次损失≥2])；伤口满八处时，现有代码加重最近旧伤一级。</p><p>普通露核须为三级深伤、剩余生命≤30%、伤位能拟合通向中枢的创腔；本包只对刀割、压裂、咬缺、爆裂的大缺损制作完整中枢外露。窄刺口与烧伤不为展示中枢而扩洞。</p><p>当前代码差距：已坏中枢可绕过血量阈值，深烧伤仍可能露核，旧 TEAR 也未区分窄刺与大缺损；这些不是本次新增的合法美术分支。热锥另有最低二级特例。制作源尚未替换运行玩法，完整核对记录在 damage-examples.json。</p></section>'
    (output/'index.html').write_text('<!doctype html><meta charset="utf-8"><title>完整伤口等级美术</title><style>body{margin:0;background:#f4f0e6;font-family:system-ui;color:#303b32}img{display:block;width:100%;max-width:1550px;margin:auto}nav{padding:24px;text-align:center}section{padding:24px;max-width:1450px;margin:auto}table{width:100%;border-collapse:collapse}th,td{border:1px solid #ccc;padding:12px;vertical-align:top}th{width:16%}td{width:28%}figure{margin:0 0 20px}figure img{max-width:320px}small{color:#777}thead{position:sticky;top:0;background:#f4f0e6}</style><nav><a href="../index.html">全部预览</a> · <a href="#source-coverage">47 种 MC 来源三级对应</a> · 8 种机械外形各三级 + 足部／全身烧伤各三级 · 制作源，未接入游戏</nav>'+''.join(f'<img loading="lazy" src="{name}.png" alt="{name}">' for name in names)+grading+preview_table(coverage,LABELS),encoding='utf-8')
    print(output/'current.png')


if __name__=='__main__':main()
