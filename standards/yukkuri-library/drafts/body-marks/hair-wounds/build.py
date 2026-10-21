"""Author hair-wound decals and layered meshes, with offline model previews."""
from pathlib import Path
import argparse
import hashlib
import io
import json
import subprocess
import sys

import numpy as np
from PIL import Image

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[4]
sys.path.insert(0, str(HERE.parent))
import preview as source
from export_wounds import Tile

RECIPE = json.loads((HERE / 'recipe.json').read_text(encoding='utf-8'))
OUT = HERE / 'assembled'
WORK = ROOT / '.local/hair-wounds-work'


class HairTile(Tile):
    def __init__(self):
        grid = (np.arange(RECIPE['texture_size'], dtype=np.float32) + .5) / RECIPE['texture_size']
        self.x, self.y = np.meshgrid(grid * 2 - 1, 1 - grid * 2)
        self.u, self.v = self.x, self.y
        self.color = np.zeros((*self.x.shape, 3), dtype=np.float32)
        self.alpha = np.zeros(self.x.shape, dtype=np.float32)


def material(kind):
    s = HairTile()
    x, y = s.x, s.y
    stroke, smooth = source.stroke, source.smooth
    if kind == 'scratch':
        for j, (cx, length) in enumerate([(-.28, .64), (.03, .84), (.3, .56)]):
            path = [(cx-.12, -length/2), (cx, .04), (cx+.12, length/2)]
            s.put((35, 28, 24), stroke(x, y, path, .045)*.85)
            s.put((208, 179, 132), stroke(x-.025, y+.012, path, .019)*.88)
            s.put((240, 215, 171), stroke(x-.018, y+.015, path, .009)*.64)
    elif kind == 'dent':
        r = np.sqrt((x/.74)**2+(y/.57)**2)
        s.put((37, 29, 27), (1-smooth(.18, 1.05, r))*.5)
        path = [(-.65, -.17), (-.3, -.4), (.25, -.42), (.65, -.19)]
        s.put((215, 185, 139), stroke(x, y, path, .018)*.46)
        for j in range(8):
            xx = -.49+j*.14
            s.put((45, 33, 28), stroke(x, y, [(xx-.05,.28),(xx,-.02),(xx+.06,-.24)], .008)*.3)
    elif kind == 'scar':
        path = [(-.82, -.09), (-.43, -.035), (-.1,.04), (.25,.02), (.78,.14)]
        s.put((46, 34, 28), stroke(x, y+.014, path, .038)*.62)
        s.put((199, 170, 127), stroke(x, y, path, .025)*.80)
        s.put((233, 210, 170), stroke(x, y-.007, path, .010)*.77)
    elif kind == 'scorch':
        r = source.blob(x, y, .7, .65, 28, .16)
        s.put((126, 85, 46), (1-smooth(.65, 1.19, r))*.55)
        s.put((41, 34, 29), (1-smooth(.56, .95, r))*.96)
        s.put((26, 24, 23), (1-smooth(.05, .67, r))*.76)
        for j in range(9):
            xx = -.47+j*.113
            p = [(xx-.06,-.4), (xx+.025,-.14), (xx-.035,.08), (xx+.05,.4)]
            s.put((105, 88, 66), stroke(x, y, p, .01)*(1-smooth(.65,.95,r))*.9)
    color = s.color/np.maximum(s.alpha[...,None], 1e-8)
    rgba = np.dstack((np.clip(color,0,255),s.alpha*255)).round().astype(np.uint8)
    rgba[rgba[...,3]==0,:3] = 0
    out = io.BytesIO()
    Image.fromarray(rgba).save(out, format='PNG')
    return out.getvalue()


def mesh_jobs():
    jobs=[]
    for model in RECIPE['models']:
        for kind, state in [('dent','intact'),('tear','intact'),('core','intact'),('core','broken'),('core','empty')]:
            ident=f'{model}-{kind}-{state}'
            spec=dict(kind=kind,state=state,az=2.1,x=0,y=1.15)
            jobs.append(dict(id=ident,model=model,spec=spec,output=str(OUT/f'{ident}.json.gz')))
    return jobs


def write_or_check(path, data, check):
    if check:
        if not path.exists() or path.read_bytes()!=data:
            raise ValueError(f'Stale output: {path}')
    else:
        path.parent.mkdir(parents=True,exist_ok=True)
        path.write_bytes(data)


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--node',default='node')
    parser.add_argument('--check',action='store_true')
    parser.add_argument('--preview',action='store_true')
    parser.add_argument('--preview-root',type=Path,default=ROOT)
    args=parser.parse_args()
    WORK.mkdir(parents=True,exist_ok=True)
    for kind in ['scratch','dent','scar','scorch']:
        write_or_check(OUT/f'{kind}.png',material(kind),args.check)
    jobs=mesh_jobs()
    job_file=WORK/'export.json'
    job_file.write_text(json.dumps(jobs),encoding='utf-8')
    subprocess.run([args.node,str(HERE/'export.cjs'),str(job_file)]+(['--check'] if args.check else []),check=True)
    source_files=[HERE/'recipe.json',HERE/'author.cjs',HERE/'export.cjs',HERE/'build.py',HERE.parent/'cavity.cjs',HERE.parent/'recipe.json',HERE.parent/'preview.py',HERE.parent/'export_wounds.py',HERE.parent/'surface.cjs',ROOT/'preview/review/pose.js']
    input_files=[ROOT/f'src/main/resources/assets/mcyukkuri/yukkuri/{model}.json' for model in RECIPE['models']]
    input_files.append(ROOT/'src/main/resources/assets/mcyukkuri/yukkuri_presentation/placement_fit.json')
    manifest=dict(id='hair-wounds',status='authored_pending_review',runtime_selected=False,
                  source_sha256={p.relative_to(ROOT).as_posix():hashlib.sha256(p.read_text(encoding='utf-8').encode()).hexdigest() for p in source_files+input_files},
                  outputs={p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(OUT.iterdir()) if p.name!='manifest.json'},
                  scope='Four transparent hair-surface decals; two models with skin-and-hair indentation, tear, exposed intact core, broken core and empty cavity. Broken hair fragments vanish; no loose hair. Source geometry, no runtime behavior changes.')
    write_or_check(OUT/'manifest.json',(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n').encode(),args.check)
    if args.preview:
        make_preview(args.node,args.preview_root,jobs)
    print('Verified hair-wound art' if args.check else 'Exported hair-wound art')


def make_preview(node, preview_root, meshes):
    jobs=[]
    for model in RECIPE['models']:
        texture=ROOT/f'src/main/resources/assets/mcyukkuri/textures/entity/{model}/neutral.png'
        raw=WORK/f'{model}.rgba'
        raw.write_bytes(Image.open(texture).convert('RGBA').tobytes())
        for kind in ['scratch','dent','scorch','scar','tear','core','broken','empty']:
            mesh=next((m for m in meshes if m['id']==f"{model}-{'core' if kind in ['core','broken','empty'] else kind}-{'intact' if kind in ['core','tear','dent'] else kind}"),None)
            spec=mesh['spec'] if mesh else dict(kind=kind,az=2.1,x=0,y=1.15,radius=[1.9,1.35])
            job=dict(model=model,width=760,height=680,az=2.0,el=.10,span=7.5,cy=6.0,cz=.6,
                     hairWound=spec,texture=str(raw),out=str(WORK/f'{model}-{kind}'))
            if mesh:job['woundMesh']=mesh['output']
            if not mesh or kind=='dent':
                decal=WORK/f'{kind}.rgba'
                decal.write_bytes(Image.open(OUT/f'{kind}.png').convert('RGBA').tobytes())
                job['woundDecal']=str(decal)
            jobs.append(job)
        # Same real core from a second angle, and a full character for scale.
        for suffix,az,span in [('side',1.3,7.5),('whole',2.0,11.7)]:
            mesh=next(m for m in meshes if m['id']==f'{model}-core-intact')
            jobs.append(dict(model=model,width=760,height=680,az=float(az),el=.1,span=span,cy=8 if suffix=='whole' else 6,cz=.6,
                             hairWound=mesh['spec'],woundMesh=mesh['output'],texture=str(raw),out=str(WORK/f'{model}-{suffix}')))
        for kind in ['dent','tear','core','broken','empty']:
            mesh=next(m for m in meshes if m['id']==f"{model}-{kind if kind in ['dent','tear'] else 'core'}-{'intact' if kind in ['dent','tear','core'] else kind}")
            jobs.append(dict(model=model,width=760,height=680,az=2.1,el=.05,span=3.4,cy=5.5,cx=4,cz=2.7,
                             hairWound=mesh['spec'],woundMesh=mesh['output'],texture=str(raw),out=str(WORK/f'{model}-detail-{kind}')))
        dent=next(m for m in meshes if m['id']==f'{model}-dent-intact')
        for before in [True,False]:
            job=dict(model=model,width=760,height=680,az=1.55,el=.05,span=4.2,cy=5.8,cx=3.4,cz=2.3,
                     hairWound={**dent['spec'],'kind':'clean' if before else 'dent'},texture=str(raw),out=str(WORK/f"{model}-impact-{'before' if before else 'after'}"))
            if not before:job['woundMesh']=dent['output']
            jobs.append(job)
    job_file=WORK/'preview.json';job_file.write_text(json.dumps(jobs),encoding='utf-8')
    subprocess.run([node,str(HERE.parent/'surface.cjs'),str(job_file)],check=True)
    output=preview_root/'.local/previews/hair-wounds';output.mkdir(parents=True,exist_ok=True)
    titles={'scratch':'擦伤','dent':'压伤','scorch':'焦伤','scar':'旧疤','tear':'开放裂伤','core':'中枢外露','broken':'中枢破裂','empty':'取出后空腔','side':'侧向遮挡','whole':'整只比例'}
    notes={'scratch':'发面短划痕，毛束保留','dent':'头发与皮面一起凹陷，不缺发','scorch':'局部焦黑，闭合处保留头发','scar':'淡色收口，无开放创腔','tear':'局部毛束断裂，断发直接消失','core':'实际缺损通道内截断覆盖头发','broken':'同一核心的裂损状态','empty':'保留创腔，不再显示核心','side':'真实深度决定中枢可见范围','whole':'沿用当前模型及正常饰品遮挡'}
    images={}
    for job in jobs:
        key=Path(job['out']).name
        rgba=np.fromfile(job['out']+'.rgba',np.uint8).reshape(job['height'],job['width'],4)
        images[key]=Image.fromarray(rgba)
    footer='制作源离线静态预览 · 使用导出网格与贴图 · 游戏发面裁切、动态蒙皮及实际伤势接入待完成'
    for model,name in [('reimu','黑发灵梦'),('marisa','金发魔理沙')]:
        cards=[(titles[k],notes[k],images[f'{model}-{k}']) for k in ['scratch','dent','scorch','scar','tear','core','broken','empty']]
        source.sheet(name+' · 同一伤位分层表现','浅伤贴发面；开放伤切开覆盖毛束，露出下方真实创腔。',cards,4,(400,350),footer).save(output/f'{model}.png')
    cards=[('砸击 · 共同凹陷','头发保留，与体表一起下陷',images['reimu-impact-after']),('裂伤 · 局部断发','断发直接消失，不制作散落毛发',images['reimu-tear']),
           ('黑发 · 中枢外露','真实缺损通道，周围头发保留',images['reimu-core']),('金发 · 中枢外露','同一分层结构适配浅色头发',images['marisa-core']),
           ('侧向遮挡','核心没有向镜头移动',images['reimu-side']),('取出后的空腔','保留缺损，不复制或补回核心',images['reimu-empty'])]
    source.sheet('头发覆盖区 · 通用伤口美术','同一身体伤位，按断发、面皮、普通馅、中央中枢逐层表现。',cards,3,(480,420),footer).save(output/'current.png')
    cards=[(titles[k],notes[k],images[f'{model}-detail-{k}']) for model in ['reimu','marisa'] for k in ['tear','core','broken','empty']]
    source.sheet('创腔近看 · 黑发与金发','断发边缘与面皮厚度保留；中枢在身体中央，不随伤口或镜头移动。',cards,4,(400,350),footer).save(output/'details.png')
    cards=[('砸击前' if before else '砸击后','同一模型、同一角度；无删发' if not before else '完整皮面与覆盖头发',images[f"{model}-impact-{'before' if before else 'after'}"]) for model in ['reimu','marisa'] for before in [True,False]]
    source.sheet('砸击对照 · 头发与体表一起凹陷','同一位移场作用于面皮和覆盖毛束；不使用透明裁切，不生成散落头发。',cards,2,(540,440),footer).save(output/'impact.png')
    (output/'index.html').write_text('<!doctype html><meta charset="utf-8"><title>发面伤口美术</title><style>body{margin:0;background:#f4f0e6}img{display:block;width:100%;max-width:1750px;margin:auto}nav{padding:20px;text-align:center}</style><nav><a href="../index.html">全部预览</a> · 制作源静态预览，尚未接入游戏发面裁切</nav>'+''.join(f'<img src="{name}.png" alt="{name}">' for name in ['current','impact','reimu','marisa','details']),encoding='utf-8')
    print(output/'current.png')


if __name__=='__main__':
    main()
