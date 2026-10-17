"""Render current birth poses and the actual flexible outlet without opening a UI.

Reads selected runtime triangles and exported root tracks; these are offline art
previews, not proof of in-game contact, collision or frame-time performance.
"""
import argparse
import json
import subprocess

import numpy as np
from PIL import Image, ImageDraw

import births as B
import mamu_birth_preview as M
import preview as P


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--node', default='node')
    args = parser.parse_args()
    out = P.ROOT/'.local/previews/birth-catching'
    out.mkdir(parents=True, exist_ok=True)
    work = out/'_pose_render'
    work.mkdir(exist_ok=True)
    corrections = json.loads((P.ASSETS/'yukkuri_presentation/motion_corrections.json').read_text(encoding='utf-8'))
    posed = P.Posed('reimu', corrections)
    root = next(i for i,b in enumerate(posed.model['bones']) if b['name']=='root')
    stages = [('蓄力 · 压低收紧', 'labor', 1.4, 1, .5),
              ('第一推 · 俯仰 ≤8°', 'labor', 2.2, 1, .9),
              ('喘息 · 保持出口', 'labor', 2.85, 1, .2),
              ('第二推 · 俯仰 ≤8°', 'labor', 3.9, 1, .9),
              ('出生 · 回到约 4°', 'labor', 4.6, .22, 0),
              ('恢复 · 缓慢回正', 'postpartum', .8, .20, 0)]
    frames = []
    faces = set()
    prefixes = tuple(json.loads((P.ASSETS/'yukkuri_presentation/living.json').read_text(encoding='utf-8'))['variants']['reimu']['accessory_prefixes'])
    rigid = np.asarray([p['name'].startswith(prefixes) for p in posed.model['parts'] if not p.get('anatomy') for _ in range(p['count'])])
    for title, clip, at, belly, contraction in stages:
        xyz, uv, normals = posed.frame(clip, at)
        matrix = posed.last_matrices[root]
        local = (np.linalg.inv(matrix)@np.c_[xyz.reshape(-1,3),np.ones(xyz.size//3)].T).T[:,:3]
        mesh = np.c_[local,uv.reshape(-1,2),np.repeat(normals,3,axis=0)]
        deformed = M.deform(mesh, belly, contraction)
        # Accessories stay rigid and follow the upper body's displacement.
        anchor = np.asarray([[0, 9, 0, 0, 0, 0, 0, 0]],float)
        delta = M.deform(anchor,belly,contraction)[0,:3]-anchor[0,:3]
        deformed[rigid,:3] = mesh[rigid,:3]+delta
        deformed[:,:3] = (matrix@np.c_[deformed[:,:3],np.ones(len(deformed))].T).T[:,:3]
        face = posed.face(clip,at);faces.add(face)
        frames.append(dict(items=[M.mesh_item(deformed,face)]))
    (work/'frames.json').write_text(json.dumps(frames),encoding='utf-8')
    for face in faces:
        (work/(face+'.rgba')).write_bytes(posed.texture(face).tobytes())
    width,height = 500,440
    renderer = 'function renderScene'+B.PAGE.split('function renderScene',1)[1].split('// ---- end engine ----')[0]
    script = "const fs=require('fs');const dir="+json.dumps(str(work))+";\n"+renderer
    script += "\nconst frames=JSON.parse(fs.readFileSync(dir+'/frames.json'));"
    script += f"frames.forEach((f,i)=>{{for(const it of f.items){{it.tex=fs.readFileSync(dir+'/'+it.texture+'.rgba');it.size={posed.model['texture_size']};}}fs.writeFileSync(dir+'/'+i+'.rgba',renderScene(f.items,{width},{height},.9,.12,12,8,0,[0,0]));}});"
    (work/'render.cjs').write_text(script,encoding='utf-8')
    subprocess.run([args.node,str(work/'render.cjs')],check=True,cwd=P.ROOT)
    sheet = Image.new('RGB',(width*3,(height+48)*2+106),'#f4f1e9')
    draw=ImageDraw.Draw(sheet)
    draw.text((24,12),'分娩姿势（整体俯仰 ≤8°）与恢复 · 当前运行模型',font=P.preview_font(30),fill='#3d342c')
    draw.text((24,58),'离线姿势预览；真实幼体、辫子接触和碰撞仍须游戏实测。',font=P.preview_font(21),fill='#6e6256')
    for i,stage in enumerate(stages):
        x,y=i%3*width,100+i//3*(height+48)
        draw.text((x+14,y+8),stage[0],font=P.preview_font(24),fill='#3d342c')
        im=Image.frombytes('RGBA',(width,height),(work/f'{i}.rgba').read_bytes())
        sheet.paste(im,(x,y+45),im)
    sheet.save(out/'mother-poses.png')
    assert work.resolve().is_relative_to(out.resolve())
    for path in work.iterdir():path.unlink()
    work.rmdir()
    page='<meta charset="utf-8"><title>分娩与接生修正</title><style>body{font:18px system-ui;background:#f4f1e9;margin:30px;color:#3d342c}img{max-width:100%}</style><h1>分娩与接生修正</h1><p>当前运行模型与动作导出的离线预览。产道保留原贴图，只沿体表柔性展开；不使用解剖动作的铰链旋转。游戏中的真实幼体、辫子接触、碰撞和多人同步尚未实测。</p><img src="mother-poses.png"><img src="current.png"><p><a href="../index.html">返回总入口</a></p>'
    (out/'index.html').write_text(page,encoding='utf-8')
    # This isolated worktree has only its own current preview topics. Build the
    # local entry from those pages without regenerating unrelated art packages.
    topics=sorted(p for p in out.parent.glob('*/index.html') if not p.parent.name.startswith('_'))
    links=''.join(f'<li><a href="{p.parent.name}/index.html">{p.parent.name}</a></li>' for p in topics)
    (out.parent/'index.html').write_text('<meta charset="utf-8"><title>当前预览</title><h1>当前工作树预览</h1><ul>'+links+'</ul>',encoding='utf-8')
    print(out/'mother-poses.png')


if __name__=='__main__':main()
