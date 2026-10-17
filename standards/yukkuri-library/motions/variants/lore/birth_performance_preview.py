"""Offline contact sheet of the selected runtime birth clips and two-actor scene.

Uses births.py's shared-depth software renderer in Node, with no browser or game.
This checks authored staging and expressions; the game's outlet clipping and collisions
are not reproduced by the offline scene. Outputs stay in .local/previews/birth-performance.
"""
from pathlib import Path
import argparse, base64, io, json, subprocess
from PIL import Image, ImageDraw
import births as B
import preview as P


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--node', default='node')
    args = ap.parse_args()
    out = P.ROOT/'.local/previews/birth-performance'
    out.mkdir(parents=True, exist_ok=True)
    work = out/'_render'
    work.mkdir(exist_ok=True)
    corrections = json.loads((B.PRESENT/'motion_corrections.json').read_text(encoding='utf-8'))
    data = dict(models={name:B.pack(name, corrections) for name in ('reimu','marisa')}, hat=B.hat_prop(),
                metrics=dict(B.VESSEL, birth=dict(preparation=B.PREP,strain=B.STRAIN,emergence=B.EMERGE,
                                                  rest=B.REST,interval=B.INTERVAL,recovery=B.RECOVERY)))
    scene = B.scene_land_live()
    # Preserve staging from the same scene while bringing the small newborns into view.
    scene.update(span=20, center=9, focus=[3,-8])
    ticks = [(1.5,'接生者走近'),(3.2,'摘帽、放到身前'),(7.9,'母体压低蓄力'),(9.3,'第一次用力'),
             (9.9,'半显露时喘息'),(11.9,'第一只出生'),(18.9,'下一只依次出生'),(22.0,'母体喘息恢复')]
    textures = {}
    for name, model in data['models'].items():
        for face, url in model['textures'].items():
            filename=f'{name}-{face}.rgba'
            with Image.open(io.BytesIO(base64.b64decode(url.split(',')[1]))) as image:
                (work/filename).write_bytes(image.convert('RGBA').tobytes())
            textures.setdefault(name,{})[face]=filename
    with Image.open(io.BytesIO(base64.b64decode(data['hat']['texture'].split(',')[1]))) as image:
        (work/'hat.rgba').write_bytes(image.convert('RGBA').tobytes())
    (work/'data.json').write_text(json.dumps(data),encoding='utf-8')
    engine=B.PAGE.split('// ---- engine (also run offline by the checks) ----')[1].split('// ---- end engine ----')[0]
    width,height=480,360
    js="const fs=require('fs');\nconst dir="+json.dumps(str(work))+";\nconst D=JSON.parse(fs.readFileSync(dir+'/data.json'));\n"+engine
    js+='\nconst textureFiles='+json.dumps(textures)+';\n'
    js+="for(const [id,faces] of Object.entries(textureFiles))for(const [face,file] of Object.entries(faces))MODELS[id].tex[face]=fs.readFileSync(dir+'/'+file);\nHAT.tex=fs.readFileSync(dir+'/hat.rgba');\n"
    js+='const scene='+json.dumps(scene)+';\n'
    for i,(time,_) in enumerate(ticks):
        js+=f"fs.writeFileSync(dir+'/{i}.rgba',renderScene(frameItems(scene,{time}),{width},{height},.32,.36,scene.span,scene.center,0,scene.focus));\n"
    (work/'render.cjs').write_text(js,encoding='utf-8')
    subprocess.run([args.node,str(work/'render.cjs')],check=True)
    sheet=Image.new('RGB',(width*4,(height+50)*2+80),'#f4f1e9')
    draw=ImageDraw.Draw(sheet)
    draw.text((22,12),'胎生演出 · 蓄力、两段用力与喘息、逐只出生、产后恢复',font=P.preview_font(28),fill='#3d342c')
    draw.text((22,51),'当前运行网格与动作的离线分镜；出口裁剪、碰撞和接生成功率仍需游戏实测。',font=P.preview_font(18),fill='#6e6256')
    for i,(time,label) in enumerate(ticks):
        x,y=i%4*width,80+i//4*(height+50)
        draw.text((x+12,y+8),f'{time:.1f}s  {label}',font=P.preview_font(22),fill='#3d342c')
        image=Image.frombytes('RGBA',(width,height),(work/f'{i}.rgba').read_bytes())
        sheet.paste(image.convert('RGB'),(x,y+46))
    sheet.save(out/'current.png')
    (out/'index.html').write_text('<!doctype html><meta charset="utf-8"><title>胎生演出</title><style>body{background:#f4f1e9;font:18px system-ui;margin:30px}img{max-width:100%}</style><h1>胎生演出 · 当前分镜</h1><p>当前运行网格与动作的离线分镜。完整场景播放器见 <a href="../lore/index.html">世界观动作</a>。</p><img src="current.png">',encoding='utf-8')
    # Only this script's scratch children are removed, after verifying their workspace.
    assert work.resolve().is_relative_to((P.ROOT/'.local/previews').resolve())
    for path in work.iterdir(): path.unlink()
    work.rmdir()
    print(out/'current.png')


if __name__=='__main__': main()
