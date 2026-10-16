"""Render current house ecology assets and staged motion samples without a browser."""
from pathlib import Path
import hashlib
import json
import subprocess
from PIL import Image, ImageDraw, ImageFont

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[3]
ASSETS = ROOT/'src/main/resources/assets/mcyukkuri'
OUT = ROOT/'.local/previews/house-ecology'
WORK = OUT/'_work'


def font(size):
    return ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',size)


def main():
    OUT.mkdir(parents=True,exist_ok=True); WORK.mkdir(parents=True,exist_ok=True)
    recipe = json.loads((HERE/'recipe.json').read_text(encoding='utf-8'))
    models, textures, sources = {}, {}, []
    atlas = Image.new('RGBA',(1024,1024))
    def add_texture(key,path):
        image = Image.open(path).convert('RGBA')
        i = len(textures); x,y=(i%4)*256,(i//4)*256
        if image.width>256 or image.height!=image.width:
            raise ValueError('Unexpected texture size: '+str(path))
        atlas.paste(image,(x,y)); textures[key]=dict(x=x,y=y,size=image.width);sources.append(path)
    for ident in ('nest_lint','house_lint_bedding','house_eggshell','house_egg','house_egg_cracked','food_scrap','house_crevice'):
        p = ASSETS/'yukkuri_props'/(ident+'.json')
        models[ident]=dict(model=p.relative_to(ROOT).as_posix());sources.append(p)
        add_texture(ident,ASSETS/'textures/props'/(ident+'.png'))
    for ident,expressions in [('house_reimu',('neutral','happy','sleep')),('house_marisa',('neutral','happy'))]:
        p=ASSETS/'yukkuri'/(ident+'.json')
        models[ident]=dict(model=p.relative_to(ROOT).as_posix(),character=True);sources.append(p)
        for expr in expressions:
            add_texture(ident+'/'+expr,ASSETS/'textures/entity'/ident/(expr+'.png'))
    (WORK/'atlas.rgba').write_bytes(atlas.tobytes())
    def item(ident,x=0,y=0,z=0,**extra):
        return dict(id=ident,at=[x,y,z],**extra)
    def block_at(pos):
        return [(pos[0]-.5)*16,pos[1]*16,(pos[2]-.5)*16]
    display=recipe['display']
    beds=[dict(id='house_lint_bedding',at=block_at(display['sleeping_center']),scale=display['sleeping_scale']),
          dict(id='house_lint_bedding',at=block_at(display['egg_center']),scale=display['egg_scale'])]
    room=item('house_crevice',omit=['lintel','front_trim','left_jamb','right_jamb','grain_mark'])
    slots=[block_at(p) for p in display['egg_slots']]
    def egg(slot,ident='house_egg'):
        return dict(id=ident,at=slots[slot])
    pantry=[item('food_scrap',-3,.1,-3.7,scale=[.55]*3)]
    shots=[
        dict(id='night-peek',title='01  夜深，先探头再觅食',subtitle='家具缝隙提供藏身处；碎食与棉絮各有用途。',
             note='探身动作：house_peek · 0.75 秒',background='#dce2dc',
             instances=[room,*beds,egg(0),egg(1),item('house_marisa',-2.7,.16,-3.2,clip='house_peek',time=.75,angle=-.18),
                        item('food_scrap',1.1,.10,-5.8,scale=[.55]*3)]),
        dict(id='carry-lint',title='02  衔絮回来，铺成低矮的小窝',subtitle='絮团可见、库存真实消耗；魔理沙沿用单辫夹取。',
             note='搬运动作：house_carry · 0.55 秒',background='#e9e5da',
             instances=[room,*beds,egg(0),*pantry,item('house_marisa',-3.1,.16,-1.7,clip='house_carry',time=.55,angle=.20,key='carrier'),
                        item('nest_lint',socketOf='carrier',scale=[.65]*3)]),
        dict(id='guard-eggs',title='03  左侧休息，右侧守着卵',subtitle='卵有固定位置；照看时低头停顿、再听一听动静。',
             note='护卵动作：house_tend · 0.65 秒',background='#eee8dc',
             instances=[room,*beds,*[egg(i) for i in range(4)],*pantry,
                        item('house_reimu',-3.0,.16,.7,clip='house_tend',time=.65,face='happy',angle=.45)]),
        dict(id='staggered-hatch',title='04  小家伙破壳，空壳暂留原位',subtitle='破壳逐枚发生；已出生幼体保留自己的身份与去向。',
             note='倾听动作：house_listen · 1.10 秒；幼体缩放 0.32',background='#efe9dc',
             instances=[room,*beds,egg(0,'house_eggshell'),egg(1,'house_egg_cracked'),egg(3),*pantry,
                        item('house_reimu',-3.3,.16,.4,clip='house_listen',time=1.10,face='happy',angle=.45),
                        item('house_marisa',1.4,.58,-.7,scale=[.32]*3,clip='newborn_crawl',time=.32,face='happy',angle=-.35)]),
        dict(id='materials',title='棉絮团 · 棉絮巢垫 · 已认可完整卵 · 原创空残壳',subtitle='棉絮和灰尘用于筑巢，食物碎屑用于进食。完整卵与裂壳卵的认可几何未改变。',
             background='#ede7db',span=8.2,centerY=.7,el=.55,az=.16,
             instances=[item('nest_lint',-5.8,0,0,scale=[1.5]*3),item('house_lint_bedding',-.5,0,0,scale=[.95]*3),
                        item('house_egg',4.5,0,0,scale=[1.35]*3),item('house_eggshell',6.3,0,0,scale=[1.35]*3)])
    ]
    width,height=880,600
    scene=dict(models=models,textures=textures,atlasSize=1024,shots=shots,width=width,height=height)
    (WORK/'scene.json').write_text(json.dumps(scene,ensure_ascii=False),encoding='utf-8')
    subprocess.run(['node',str(HERE/'render_scene.cjs'),str(WORK/'scene.json'),str(WORK)],check=True,cwd=ROOT)
    sheets=[]
    for shot in shots:
        pixels=Image.frombytes('RGBA',(width,height),(WORK/(shot['id']+'.rgba')).read_bytes())
        panel=Image.new('RGB',(width,744),shot['background']);panel.paste(pixels,(0,72),pixels)
        d=ImageDraw.Draw(panel)
        d.text((28,22),shot['title'],font=font(29),fill='#32443a')
        d.text((28,646),shot['subtitle'],font=font(20),fill='#41554a')
        d.text((28,687),shot.get('note','当前运行模型离线渲染 · 新增造型待确认'),font=font(18),fill='#657469')
        panel.save(OUT/(shot['id']+'.png'));sheets.append(panel)
    overview=Image.new('RGB',(1800,1638),'#f4f0e6');d=ImageDraw.Draw(overview)
    d.text((36,24),'家具缝隙里的一家',font=font(48),fill='#2e453a')
    d.text((38,89),'当前资源与真实动作的离线分镜 · 家具剖视展示 · 游戏内路线与孵化由服务器决定',font=font(23),fill='#657469')
    for i,panel in enumerate(sheets[:4]):
        overview.paste(panel,(14+(i%2)*892,138+(i//2)*746))
    overview.save(OUT/'house-ecology.png')
    sources.extend([ASSETS/'yukkuri_presentation/house_ecology.json',HERE/'recipe.json',HERE/'render_scene.cjs',HERE/'render_preview.py'])
    manifest=dict(scope='Offline current runtime geometry, texture and motion samples; staged layout, no game or browser validation.',
                  inputs={p.relative_to(ROOT).as_posix():hashlib.sha256(p.read_bytes()).hexdigest() for p in sources})
    (WORK/'manifest.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2),encoding='utf-8')
    (OUT/'index.html').write_text('<!doctype html><meta charset="utf-8"><title>家油生态分镜</title><style>body{background:#f4f0e6;margin:24px;font:18px sans-serif;color:#2e453a}img{max-width:100%;height:auto}a{color:#526d55}</style><a href="../index.html">回到预览总入口</a><p>当前游戏网格与动作的离线分镜；构图为演出安排，游戏内效果待实测。</p><img src="house-ecology.png"><img src="materials.png">',encoding='utf-8')
    print(OUT/'house-ecology.png')


if __name__=='__main__':
    main()
