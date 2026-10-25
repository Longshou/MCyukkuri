"""Render current aquatic clips without a browser/game. Outputs stay under .local/.

Uses the repository's deterministic Node rasterizer and actual runtime meshes/clips.
The optional Minecraft resource jar supplies the same stick sprite used by the game.
"""
from pathlib import Path
import argparse, io, json, math, subprocess, zipfile
import numpy as np
from PIL import Image, ImageDraw, ImageFont
import preview as P



def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--node', default='node')
    parser.add_argument('--minecraft-resources', type=Path, required=True)
    args = parser.parse_args()
    out = P.ROOT/'.local/previews/aquatic'; out.mkdir(parents=True, exist_ok=True)
    resource = json.loads((P.ASSETS/'yukkuri_presentation/motion_corrections.json').read_text(encoding='utf-8'))
    model = P.Posed('marisa', resource, True)
    with zipfile.ZipFile(args.minecraft_resources) as jar:
        stick = Image.open(io.BytesIO(jar.read('assets/minecraft/textures/item/stick.png'))).convert('RGBA')
    panels = [('boat_paddle', .8, '划桨：推水'), ('boat_paddle', 0, '划桨：抬桨回收'),
              ('boat_gather', .75, '收食入帽'), ('boat_eat', .35, '帽内进食'),
              ('boat_sleep', 1.4, '帽内安睡'), ('boat_wake', .6, '请求返岸姿态')]
    frames = []
    for index, (clip, time, label) in enumerate(panels):
        triangles, uv, normal = model.frame(clip, time)
        # The renderer uses the first vertex's normal unless smooth corner normals are supplied.
        vertices = np.concatenate((triangles, uv*.5, np.repeat(normal[:, None, :], 3, axis=1)), axis=2).reshape(-1, 8)
        if clip == 'boat_paddle':
            tip = next(i for i,b in enumerate(model.model['bones']) if b['name']=='hand_right_tip')
            matrix = model.last_matrices[tip]
            pivot = np.array(model.model['bones'][tip]['pivot'])
            # FIXED item display applies another .5 scale. Keep the tip on the braid grip;
            # The server starts boat_paddle its power_start marker (.2 s) before the hull's stroke clock; lift the return half.
            cycle = ((time-.2) % 1.6)/1.6
            recovery = 105*max(0, -math.sin(cycle*2*math.pi))
            corners = np.array([[-8,-8,0],[8,-8,0],[8,8,0],[-8,8,0]])*(.85*.5)
            corners = (corners@P.euler([0,0,45]).T+[0,-.22*16,0])@P.euler([recovery,0,0]).T+pivot
            corners = (matrix@np.c_[corners,np.ones(4)].T).T[:,:3]
            tex = np.array([[.5, .125],[.625,.125],[.625,0],[.5,0]])
            for order in ([0,1,2],[0,2,3],[2,1,0],[3,2,0]):
                pos = corners[order]; n = np.cross(pos[1]-pos[0],pos[2]-pos[0]); n /= np.linalg.norm(n)
                vertices = np.r_[vertices, np.c_[pos,tex[order],np.tile(n,(3,1))]]
        texture = Image.new('RGBA',(512,512)); texture.paste(Image.fromarray(model.texture(model.face(clip,time))),(0,0))
        texture.paste(stick.resize((64,64),Image.Resampling.NEAREST),(256,0))
        (out/f'{index}.rgba').write_bytes(texture.tobytes())
        (out/f'{index}.json').write_text(json.dumps(vertices.flatten().tolist()),encoding='utf-8')
        frames.append({'index':index,'label':label})
    renderer = P.ROOT/'standards/yukkuri-library/runtime/render.js'
    code = "const fs=require('fs');\n"+renderer.read_text(encoding='utf-8')+'\n'
    code += 'const dir='+json.dumps(str(out))+';\n'
    code += '''for(let i=0;i<6;i++){
      const v=JSON.parse(fs.readFileSync(dir+'/'+i+'.json'));
      const image=renderModel(v,fs.readFileSync(dir+'/'+i+'.rgba'),460,360,.55,.38,16,{centerY:3,textureSize:512});
      fs.writeFileSync(dir+'/'+i+'-render.rgba',image);
    }'''
    runner = out/'render.cjs'; runner.write_text(code,encoding='utf-8'); subprocess.run([args.node,str(runner)],check=True)
    sheet = Image.new('RGB',(1380,860),(233,241,243)); draw = ImageDraw.Draw(sheet)
    font = P.preview_font(23)
    draw.text((28,16),'水生魔理沙 · 当前运行模型与动作（离线预览）',font=font,fill=(32,57,64))
    for row in frames:
        i=row['index']; x=i%3*460; y=58+i//3*400
        draw.rounded_rectangle((x+8,y,x+452,y+388),radius=16,fill=(250,252,251))
        image=Image.frombytes('RGBA',(460,360),(out/f'{i}-render.rgba').read_bytes())
        image.save(out/f'panel-{i + 1}.png')
        sheet.paste(image,(x,y+26),image); draw.text((x+22,y+12),row['label'],font=font,fill=(32,57,64))
    result=out/'aquatic-motions.png'; sheet.save(result); print(result)


if __name__ == '__main__':
    main()
