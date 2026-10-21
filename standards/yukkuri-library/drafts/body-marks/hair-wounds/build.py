"""Export the complete 4 x 3 injury matrix and cause-specific core cavities."""
from pathlib import Path
import argparse
import hashlib
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

RECIPE=json.loads((HERE/'recipe.json').read_text(encoding='utf-8'))
OUT=HERE/'assembled'
WORK=ROOT/'.local/hair-wounds-work'
KINDS=['scratch','tear','dent','scorch']
LABELS={'scratch':'擦伤','tear':'裂伤','dent':'凹伤','scorch':'灼伤'}
NOTES={
 'scratch':['少量断续浅擦痕；头发保留','较多擦痕与局部毛束磨损','密集擦损与碎屑状浅破皮；不露核'],
 'tear':['细窄闭合切痕；不露馅','裂口张开、断发参差；露普通馅','不规则深撕裂；保留翻折毛束'],
 'dent':['轻度压陷；皮面与头发一起下沉','明显凹陷与压折纹；不删发','凹陷内压碎开裂；折发仍附着'],
 'scorch':['局部褐变与轻度焦痕；不缺发','焦黑、卷曲和干裂；未开放','焦壳破裂、短焦茬；露普通馅']}


def mesh_jobs():
    jobs=[]
    for model,site in [('reimu','hair'),('marisa','hair'),('reimu','skin')]:
        for kind in KINDS:
            for grade in [1,2,3]:
                states=['normal']+(['intact','broken','empty'] if grade==3 and kind!='scratch' else [])
                for state in states:
                    ident=f'{model}-{site}-{kind}-{grade}-{state}'
                    spec=dict(kind=kind,severity=grade,core=state!='normal',state=state,
                              az=1.7 if site=='hair' else .22,x=0,y=1.15 if site=='hair' else -.6)
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
    textures={f'{kind}-{grade}.png':material(kind,grade,RECIPE['texture_size']) for kind in KINDS for grade in [1,2,3]}
    textures['scar.png']=material('scar',size=RECIPE['texture_size'])
    meshes=mesh_jobs();expected=set(textures)|{Path(m['output']).name for m in meshes}|{'manifest.json'}
    # Delete only obsolete generated outputs listed by this package's manifest.
    old=json.loads((OUT/'manifest.json').read_text(encoding='utf-8')) if (OUT/'manifest.json').exists() else {}
    for name in set(old.get('outputs',{}))-expected:
        candidate=(OUT/name).resolve()
        if candidate.parent!=OUT.resolve():raise ValueError('Output outside package')
        if candidate.exists():
            if args.check:raise ValueError(f'Obsolete generated output: {candidate}')
            candidate.unlink()
    for name,data in textures.items():write_or_check(OUT/name,data,args.check)
    jobs=WORK/'export.json';jobs.write_text(json.dumps(meshes),encoding='utf-8')
    subprocess.run([args.node,str(HERE/'export.cjs'),str(jobs)]+(['--check'] if args.check else []),check=True)
    sources=[HERE/n for n in ['recipe.json','author.cjs','export.cjs','build.py','materials.py']]
    sources += [HERE.parent/n for n in ['cavity.cjs','recipe.json','preview.py','export_wounds.py','surface.cjs']]
    sources += [ROOT/'preview/review/pose.js',ROOT/'src/main/resources/assets/mcyukkuri/yukkuri_presentation/placement_fit.json']
    sources += [ROOT/f'src/main/resources/assets/mcyukkuri/yukkuri/{model}.json' for model in RECIPE['models']]
    manifest=dict(id='hair-wounds',status='authored_pending_review',runtime_selected=False,
      coverage=dict(injury_types=KINDS,grades=[1,2,3],core_causes=['tear','dent','scorch'],core_states=['intact','broken','empty'],
                    receivers=['reimu:hair','marisa:hair','reimu:skin'],mesh_count=len(meshes),texture_count=len(textures)),
      derived_marks=dict(scar='assembled/scar.png; healed mark, not an active injury',
         dirt='../export_wounds.py: dirt_1..3',mold='../export_wounds.py: mold_1..4',char='../export_wounds.py: char (whole sole)',
         paste='Ordinary filling in every open wound, never a surface dot',core='Derived from grade-three tear/dent/scorch; never an independent round hole'),
      source_sha256={p.relative_to(ROOT).as_posix():hashlib.sha256(p.read_text(encoding='utf-8').encode()).hexdigest() for p in sources},
      outputs={name:hashlib.sha256((OUT/name).read_bytes()).hexdigest() for name in sorted(expected-{'manifest.json'})})
    write_or_check(OUT/'manifest.json',(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n').encode(),args.check)
    if args.preview:make_preview(args.node,args.preview_root,meshes)
    print(f'{"Verified" if args.check else "Exported"}: 12 injury grades, 9 core variants, 3 receivers; {len(meshes)} meshes, {len(textures)} textures')


def make_preview(node,preview_root,meshes):
    jobs=[]
    for model in RECIPE['models']:
        path=ROOT/f'src/main/resources/assets/mcyukkuri/textures/entity/{model}/neutral.png'
        (WORK/f'{model}.rgba').write_bytes(Image.open(path).convert('RGBA').tobytes())
    for name in [f'{kind}-{grade}' for kind in KINDS for grade in [1,2,3]]:
        (WORK/f'{name}.rgba').write_bytes(Image.open(OUT/f'{name}.png').convert('RGBA').tobytes())
    for mesh in meshes:
        spec=mesh['spec'];hair=mesh['site']=='hair'
        jobs.append(dict(model=mesh['model'],width=640,height=580,az=spec['az'],el=.05,span=5.5,
           cx=3.5 if hair else 0,cy=5.6 if hair else 4,cz=.5 if hair else -2,
           hairWound=spec,woundMesh=mesh['output'],texture=str(WORK/f"{mesh['model']}.rgba"),
           woundDecal=str(WORK/f"{spec['kind']}-{spec['severity']}.rgba"),out=str(WORK/mesh['id'])))
    for kind in ['tear','dent','scorch']:
        mesh=next(m for m in meshes if m['id']==f'reimu-hair-{kind}-3-intact')
        jobs.append({**next(j for j in jobs if j['out']==str(WORK/mesh['id'])),
                     'az':.9,'span':6.0,'out':str(WORK/f'side-{kind}')})
    job_file=WORK/'preview.json';job_file.write_text(json.dumps(jobs),encoding='utf-8')
    subprocess.run([node,str(HERE.parent/'surface.cjs'),str(job_file)],check=True)
    output=preview_root/'.local/previews/hair-wounds';output.mkdir(parents=True,exist_ok=True)
    images={Path(j['out']).name:Image.fromarray(np.fromfile(j['out']+'.rgba',np.uint8).reshape(j['height'],j['width'],4)) for j in jobs}
    footer='制作源静态预览 · 周围头发保留，破口边缘依伤因处理 · 尚未接入游戏伤级选择、发面裁切与动态蒙皮'
    for model,site,title in [('reimu','hair','黑发'),('marisa','hair','金发'),('reimu','skin','裸露皮面')]:
        cards=[(f'{LABELS[kind]} · {grade}级',NOTES[kind][grade-1],images[f'{model}-{site}-{kind}-{grade}-normal']) for kind in KINDS for grade in [1,2,3]]
        source.sheet(title+' · 四类伤口 × 三级','每行一种伤口，从左到右为 1、2、3 级；形状、开放程度与头发反应随等级改变。',cards,3,(450,360),footer).save(output/f'{model}-{site}-grades.png')
        cards=[(f'{LABELS[kind]} · {label}',{'tear':'沿撕裂保留参差断端和翻折毛束','dent':'压陷内开裂，折发仍连接外层','scorch':'焦壳崩裂，烧卷短茬围住破口'}[kind],images[f'{model}-{site}-{kind}-3-{state}']) for kind in ['tear','dent','scorch'] for state,label in [('intact','中枢完好'),('broken','中枢受损'),('empty','取出后空腔')]]
        source.sheet(title+' · 三种伤因的中枢外露','中枢外露继承深伤的伤因；原发面保留至参差断口，没有统一剃空的环带。',cards,3,(450,360),footer).save(output/f'{model}-{site}-core.png')
    cards=[(f'{LABELS[kind]} · 三级深伤',NOTES[kind][2],images[f'reimu-hair-{kind}-3-normal']) for kind in ['tear','dent','scorch']]
    cards += [(f'{LABELS[kind]} · 中枢外露','同一伤因继续形成深部通道，周围头发保留',images[f'reimu-hair-{kind}-3-intact']) for kind in ['tear','dent','scorch']]
    source.sheet('伤因决定伤口，也决定头发怎么损坏','撕裂：参差断端 ｜ 砸击：压折与碎裂 ｜ 烧伤：焦卷与脆断',cards,3,(470,390),footer).save(output/'current.png')
    cards=[(f'{LABELS[kind]} · 侧向','腔壁与残留头发真实遮挡中枢',images[f'side-{kind}']) for kind in ['tear','dent','scorch']]
    source.sheet('保留发缘 · 侧面检查','毛束仍与外侧头发连接，深处中枢不会移到发面上。',cards,3,(450,360),footer).save(output/'sides.png')
    names=['current','reimu-hair-grades','marisa-hair-grades','reimu-skin-grades','reimu-hair-core','marisa-hair-core','reimu-skin-core','sides']
    for name in ['reimu.png','marisa.png','details.png','impact.png']:
        p=(output/name).resolve()
        if p.parent!=output.resolve():raise ValueError('Preview outside topic')
        if p.exists():p.unlink()
    (output/'index.html').write_text('<!doctype html><meta charset="utf-8"><title>完整伤口等级美术</title><style>body{margin:0;background:#f4f0e6}img{display:block;width:100%;max-width:1550px;margin:auto}nav{padding:24px;text-align:center}</style><nav><a href="../index.html">全部预览</a> · 4 类 × 3 级活动伤 + 3 伤因 × 3 中枢状态 · 制作源，未接入游戏</nav>'+''.join(f'<img src="{name}.png" alt="{name}">' for name in names),encoding='utf-8')
    print(output/'current.png')


if __name__=='__main__':main()
