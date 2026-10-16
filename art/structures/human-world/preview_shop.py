"""Render the current shop, staff and exam props offline from authored layouts and runtime assets.

Requires Pillow, NumPy, Node and an existing Minecraft resource jar. No game or browser.
Generated media stays in .local/previews/shop-badge-pets; no source assets are modified.
"""
from pathlib import Path
import argparse, functools, io, json, math, runpy, shutil, subprocess, sys, zipfile
import numpy as np
from PIL import Image, ImageDraw, ImageFont, ImageOps
import build

ROOT = Path(__file__).resolve().parents[3]
ASSETS = ROOT/'src/main/resources/assets'
FACES = {
    'north': ([0,0,-1], [0,3,2,1]), 'south': ([0,0,1], [5,6,7,4]),
    'west': ([-1,0,0], [4,7,3,0]), 'east': ([1,0,0], [1,2,6,5]),
    'up': ([0,1,0], [3,7,6,2]), 'down': ([0,-1,0], [4,0,1,5]),
}

def font(size):
    for p in ('C:/Windows/Fonts/msyh.ttc','/System/Library/Fonts/STHeiti Light.ttc','/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc'):
        if Path(p).exists(): return ImageFont.truetype(p,size)
    raise RuntimeError('A Chinese font is required')

def rotation(axis, angle):
    a=math.radians(angle); c,s=math.cos(a),math.sin(a)
    return np.array({'x':[[1,0,0],[0,c,-s],[0,s,c]],'y':[[c,0,s],[0,1,0],[-s,0,c]],'z':[[c,-s,0],[s,c,0],[0,0,1]]}[axis])

class Assets:
    def __init__(self, jar):
        self.jar=zipfile.ZipFile(jar); self.atlas=Image.new('RGBA',(2048,2048)); self.slots={}; self.missing=set()
    def read(self, name, category, suffix):
        ns,key=name.split(':',1) if ':' in name else ('minecraft',name)
        path=f'{ns}/{category}/{key}.{suffix}'; local=ASSETS/path
        if local.exists(): return local.read_bytes()
        return self.jar.read('assets/'+path)
    @functools.lru_cache(None)
    def model(self, name):
        raw=json.loads(self.read(name,'models','json')); parent=raw.get('parent','')
        base={} if not parent or 'builtin' in parent else self.model(parent)
        return {**base,**raw,'textures':{**base.get('textures',{}),**raw.get('textures',{})}}
    def tex(self, name):
        if name not in self.slots:
            im=Image.open(io.BytesIO(self.read(name,'textures','png'))).convert('RGBA')
            im=im.crop((0,0,im.width,min(im.width,im.height))).resize((32,32),Image.Resampling.NEAREST)
            if any(s in name for s in ('leaves','grass_block_top')):
                a=np.array(im); a[:,:,:3]=(a[:,:,:3]*np.array([.55,.85,.36])).astype('uint8'); im=Image.fromarray(a)
            i=len(self.slots); assert i<4096; self.slots[name]=i
            self.atlas.paste(im,((i%64)*32,(i//64)*32))
        i=self.slots[name]
        return np.array([i%64,i//64])/64
    def faces(self, model, offset=(0,0,0), xrot=0, yrot=0):
        data=self.model(model); textures=data.get('textures',{}); out=[]
        r=rotation('y',-yrot)@rotation('x',-xrot)
        for e in data.get('elements',[]):
            x0,y0,z0=e['from']; x1,y1,z1=e['to']
            corners=np.array([[x0,y0,z0],[x1,y0,z0],[x1,y1,z0],[x0,y1,z0],[x0,y0,z1],[x1,y0,z1],[x1,y1,z1],[x0,y1,z1]],float)
            er=np.eye(3)
            if 'rotation' in e:
                spec=e['rotation']; origin=np.array(spec['origin']); er=rotation(spec['axis'],spec['angle'])
                corners=(corners-origin)@er.T+origin
            corners=((corners-8)@r.T+8)/16+offset
            defaults={'north':[16-x1,16-y1,16-x0,16-y0],'south':[x0,16-y1,x1,16-y0],
                      'west':[z0,16-y1,z1,16-y0],'east':[16-z1,16-y1,16-z0,16-y0],
                      'up':[x0,z0,x1,z1],'down':[x0,16-z1,x1,16-z0]}
            for direction,f in e['faces'].items():
                texture=f['texture']; seen=set()
                while texture.startswith('#'):
                    if texture in seen: raise ValueError(texture)
                    seen.add(texture); texture=textures[texture[1:]]
                origin=self.tex(texture); u0,v0,u1,v1=f.get('uv',defaults[direction])
                uv=np.array([[u0,v1],[u0,v0],[u1,v0],[u1,v1]],float)/1024+origin
                uv=np.roll(uv,int(f.get('rotation',0)/90),axis=0)
                normal,ix=FACES[direction]; normal=r@er@normal
                quad=corners[ix]
                for tri in ((0,1,2),(0,2,3)):
                    for i in tri: out.append([*quad[i],*uv[i],*normal])
        return np.array(out).reshape(-1,8)
    @staticmethod
    def matches(when, props):
        if 'OR' in when: return any(Assets.matches(x,props) for x in when['OR'])
        if 'AND' in when: return all(Assets.matches(x,props) for x in when['AND'])
        return all(str(props.get(k,'false')) in str(v).split('|') for k,v in when.items())
    @functools.lru_cache(None)
    def block(self,name,props_tuple):
        if name in ('minecraft:air','minecraft:light','minecraft:barrier'): return np.empty((0,8))
        props=dict(props_tuple)
        try:
            state=json.loads(self.read(name,'blockstates','json')); models=[]
            if 'variants' in state:
                for key,value in state['variants'].items():
                    when=dict(pair.split('=',1) for pair in key.split(',') if pair)
                    if self.matches(when,props): models.append(value); break
            else:
                models=[part['apply'] for part in state['multipart'] if self.matches(part.get('when',{}),props)]
            out=[]
            for m in models:
                if isinstance(m,list): m=m[0]
                v=self.faces(m['model'],xrot=m.get('x',0),yrot=m.get('y',0))
                if len(v): out.append(v)
            return np.concatenate(out) if out else np.empty((0,8))
        except (KeyError,FileNotFoundError) as error:
            self.missing.add(name); return np.empty((0,8))

def render(a, vertices, out, name, width, height, az, el, span, center):
    work=out/'_work'; work.mkdir(exist_ok=True)
    vertices=np.asarray(vertices,dtype='<f4').reshape(-1,8)
    (work/f'{name}.bin').write_bytes(vertices.tobytes()); (work/'atlas.rgba').write_bytes(a.atlas.tobytes())
    runner="const fs=require('fs');\n"+(ROOT/'standards/yukkuri-library/runtime/render.js').read_text(encoding='utf8')
    runner+=f"\nconst d={json.dumps(str(work))};const b=fs.readFileSync(d+'/{name}.bin');const v=new Float32Array(b.buffer,b.byteOffset,b.length/4);const t=fs.readFileSync(d+'/atlas.rgba');fs.writeFileSync(d+'/{name}.rgba',renderModel(v,t,{width},{height},{az},{el},{span},{{centerY:{center},textureSize:2048}}));"
    (work/'render.cjs').write_text(runner,encoding='utf8')
    subprocess.run(['node',str(work/'render.cjs')],check=True)
    im=Image.frombytes('RGBA',(width,height),(work/f'{name}.rgba').read_bytes())
    bg=Image.new('RGBA',im.size,'#edf1eb'); bg.alpha_composite(im); bg.convert('RGB').save(out/f'{name}.png')
    return bg

def label(image,title,subtitle):
    result=Image.new('RGB',(image.width,image.height+100),'#edf1eb'); result.paste(image,(0,100))
    d=ImageDraw.Draw(result);d.text((30,16),title,font=font(30),fill='#273e37');d.text((30,62),subtitle,font=font(17),fill='#596f65')
    return result

def shop_views(a,out):
    shop=build.shop(); meshes=[]; interior=[]
    for pos,(name,props,_) in shop.blocks.items():
        # Internal solid blocks have no visible surface. Keep all partial and transparent blocks.
        opaque=lambda n: any(s in n for s in ('planks','quartz_block','mud_bricks','concrete','terracotta','log','wool'))
        if opaque(name) and all(opaque(shop.blocks.get(tuple(pos[i]+delta[i] for i in range(3)),('air',{},None))[0]) for delta,_ in FACES.values()): continue
        v=a.block(name,tuple(sorted(props.items()))).copy()
        if not len(v): continue
        v[:,:3]+=np.array(pos)-np.array([20,0,16]); meshes.append(v)
        if pos[1]<=4: interior.append(v)
    render(a,np.concatenate(meshes),out,'shop-exterior',1400,900,.55,.36,22,6)
    render(a,np.concatenate(interior),out,'shop-cutaway',1400,1000,.12,1.08,23,2)
    for name,title,sub in [('shop-exterior','新宠物店 · 街边外观','41 × 16 × 33 格 · 当前制作源与运行方块贴图 · 离线光照，未放置场景实体'),
                           ('shop-cutaway','销售、繁育与考试 · 去顶布局','剖切至 y=4，便于查看房间 · 前场展栏 / 后场库存 / 繁育区 / 右侧考场')]:
        p=out/f'{name}.png';label(Image.open(p),title,sub).save(p)

def skins(out):
    roles=[('shopkeeper','店主'),('examiner','考官 · 新岗位'),('keeper','饲养员 · 新岗位'),('brother','爱派大哥哥'),('sister','爱派大姐姐'),('factory_worker','工厂工人'),('oniisan','鬼威惨')]
    sheet=Image.new('RGB',(1400,680),'#edf1eb');d=ImageDraw.Draw(sheet)
    d.text((30,20),'七套人类皮肤 · 正面与背面',font=font(30),fill='#273e37')
    d.text((30,65),'直接读取当前 64×64 运行贴图；平展开显示，不含头发附加几何。',font=font(17),fill='#596f65')
    for i,(ident,title) in enumerate(roles):
        skin=Image.open(ASSETS/f'mcyukkuri/textures/entity/human/{ident}.png').convert('RGBA')
        for back in (False,True):
            body=Image.new('RGBA',(16,32))
            for rect,at in ([((24,8,32,16),(4,0)),((32,20,40,32),(4,8)),((52,20,56,32),(0,8)),((52,20,56,32),(12,8)),((12,20,16,32),(4,20)),((12,20,16,32),(8,20))] if back else [((8,8,16,16),(4,0)),((20,20,28,32),(4,8)),((44,20,48,32),(0,8)),((44,20,48,32),(12,8)),((4,20,8,32),(4,20)),((4,20,8,32),(8,20))]):
                body.alpha_composite(skin.crop(rect),at)
            body.alpha_composite(skin.crop((56,8,64,16) if back else (40,8,48,16)),(4,0))
            image=body.resize((128,256),Image.Resampling.NEAREST);x=36+i*195;y=370 if back else 106
            sheet.paste(image,(x,y),image)
        d.text((25+i*195,640),title,font=font(19),fill='#273e37')
    sheet.save(out/'staff.png')

def props(a,out):
    icons=[('bronze_yukkuri_badge','铜章'),('silver_yukkuri_badge','银章'),('gold_yukkuri_badge','金章'),('badge_handbook','徽章手帖')]
    sheet=Image.new('RGB',(1200,380),'#edf1eb');d=ImageDraw.Draw(sheet)
    d.text((30,20),'铜、银、金徽章与手帖',font=font(30),fill='#273e37')
    for i,(ident,title) in enumerate(icons):
        im=Image.open(ASSETS/f'mcyukkuri/textures/item/{ident}.png').convert('RGBA').resize((192,192),Image.Resampling.NEAREST)
        sheet.paste(im,(50+i*300,95),im);d.text((110+i*300,320),title,font=font(24),fill='#273e37')
    sheet.save(out/'badges.png')
    items=[('exam_podium',{'facing':'north'},'答题台'),('exam_hide_box',{'facing':'north'},'躲藏箱'),('exam_mark',{'kind':'gold'},'金章考位')]
    for ident,props,title in items:
        v=a.block('mcyukkuri:'+ident,tuple(sorted(props.items()))).copy(); v[:,:3]-=[.5,0,.5]
        im=render(a,v,out,ident,420,400,.65,.45,.8,.35)
        label(im,title,'实际方块模型与贴图').save(out/f'{ident}.png')

def goods(out):
    from types import SimpleNamespace
    items=[('oil_grain_budget','廉价油粮'),('oil_grain_regular','普通油粮'),('oil_grain_premium','高级油粮'),
           ('orange_juice','橙汁'),('mold_remover','除霉剂'),('companion_cord','陪伴绳')]
    sheet=Image.new('RGB',(1440,880),'#edf1eb');d=ImageDraw.Draw(sheet)
    d.text((30,20),'商店用品 · 标签、等级与轮廓修订',font=font(30),fill='#273e37')
    d.text((30,65),'当前运行网格与贴图的离线渲染；家油缝隙另调整了背包展示朝向。',font=font(17),fill='#596f65')
    for i,(ident,title) in enumerate(items):
        model=json.loads((ASSETS/f'mcyukkuri/yukkuri_props/{ident}.json').read_text(encoding='utf8'))
        texture=Image.open(ASSETS/f'mcyukkuri/textures/props/{ident}.png').convert('RGBA').resize((2048,2048),Image.Resampling.NEAREST)
        v=np.array(model['vertices']).reshape(-1,8);bounds=model['bounds'];center=(bounds[1][0]+bounds[1][1])/2
        span=max((b[1]-b[0]) for b in bounds)*.72
        im=render(SimpleNamespace(atlas=texture),v,out,ident,470,310,.35,.3,span,center).convert('RGB')
        x=10+i%3*480;y=105+i//3*380;sheet.paste(im,(x,y));d.text((x+180,y+325),title,font=font(23),fill='#273e37')
    sheet.save(out/'goods.png')

def worn_badges(out):
    from types import SimpleNamespace
    sys.path.insert(0,str(ROOT/'standards/yukkuri-library/motions/variants/lore'))
    from preview import Posed
    attach=runpy.run_path(str(ROOT/'art/props/badges/preview.py'))['badge_vertices']
    corrections=json.loads((ASSETS/'mcyukkuri/yukkuri_presentation/motion_corrections.json').read_text(encoding='utf8'))
    mounts=json.loads((ASSETS/'mcyukkuri/yukkuri/badge_mounts.json').read_text(encoding='utf8'))['variants']
    sheet=Image.new('RGB',(1440,600),'#edf1eb');d=ImageDraw.Draw(sheet)
    d.text((30,20),'佩戴位置 · 正面偏右视角',font=font(30),fill='#273e37')
    for i,(ident,title) in enumerate([('reimu','灵梦'),('marisa','魔理沙'),('chen','橙'),('patchouli','帕秋莉')]):
        posed=Posed(ident,corrections);tri,uv,n=posed.frame('idle',0)
        v=np.c_[tri.reshape(-1,3),uv.reshape(-1,2)*.5,np.repeat(n,3,axis=0)]
        v=np.r_[v,attach(posed.model,posed.last_matrices,mounts[ident])]
        atlas=Image.new('RGBA',(1024,1024));tex=Image.fromarray(posed.texture('neutral')).resize((512,512),Image.Resampling.NEAREST)
        atlas.paste(tex,(0,0));badge=Image.open(ASSETS/'mcyukkuri/textures/item/gold_yukkuri_badge.png').convert('RGBA').resize((64,64),Image.Resampling.NEAREST);atlas.paste(badge,(512,0))
        bounds=tri.reshape(-1,3);center=(bounds[:,1].min()+bounds[:,1].max())/2;span=max(np.ptp(bounds,axis=0))*.7
        im=render(SimpleNamespace(atlas=atlas.resize((2048,2048),Image.Resampling.NEAREST)),v,out,'badge-'+ident,360,450,.4,.12,span,center)
        sheet.paste(im.convert('RGB'),(i*360,80));d.text((i*360+130,540),title,font=font(24),fill='#273e37')
    sheet.save(out/'badge-mounts.png')

def main():
    ap=argparse.ArgumentParser(description=__doc__);ap.add_argument('--minecraft-resources',type=Path,required=True);ap.add_argument('--out',type=Path,default=ROOT/'.local/previews/shop-badge-pets')
    args=ap.parse_args();args.out.mkdir(parents=True,exist_ok=True);a=Assets(args.minecraft_resources)
    shop_views(a,args.out);skins(args.out);props(a,args.out);goods(args.out);worn_badges(args.out)
    overview=Image.new('RGB',(1560,1650),'#eff2eb');d=ImageDraw.Draw(overview)
    d.text((40,24),'宠物店、徽章考试与养成 · 当前资源预览',font=font(38),fill='#273e37')
    d.text((42,82),'当前运行素材的离线画面；界面为示例数据，完整页面含15 组动作 GIF。',font=font(20),fill='#596f65')
    cards=[('新店外观与考场','shop-exterior.png'),('商店与考试界面','ui/shop_live_zh_cn_x2.png'),
           ('七套 NPC 皮肤','staff.png'),('徽章佩戴位置','badge-mounts.png'),('油粮、瓶装与陪伴绳','goods.png'),('铜银金章与手帖','badges.png')]
    for i,(title,relative) in enumerate(cards):
        x=30+i%2*765;y=130+i//2*500;d.rounded_rectangle((x,y,x+735,y+480),14,fill='#fffdf7')
        im=ImageOps.contain(Image.open(args.out/relative).convert('RGB'),(705,415))
        overview.paste(im,(x+(735-im.width)//2,y+12+(415-im.height)//2));d.text((x+22,y+435),title,font=font(24),fill='#273e37')
    overview.save(args.out/'overview.png')
    print('Rendered current shop, staff, badges and exam props:',args.out)
    print('Special block renderers omitted:',', '.join(sorted(a.missing)) or 'none')
    shutil.copy2(ROOT/'preview/hub/shop-badge-pets.html',args.out/'index.html')
    work=args.out/'_work'
    for p in work.iterdir(): p.unlink()
    work.rmdir()

if __name__=='__main__': main()
