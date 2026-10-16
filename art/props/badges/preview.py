"""Offline badge review using current runtime meshes, poses and item textures.

Runs the repository's software rasterizer in Node; never opens a browser or game.
Outputs are temporary .local/previews/tsumuri-badge files, not art approval evidence.
"""
from pathlib import Path
import argparse, json, subprocess, sys
import numpy as np
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[3]
ASSETS = ROOT/'src/main/resources/assets/mcyukkuri'
sys.path.insert(0, str(ROOT/'standards/yukkuri-library/motions/variants/lore'))
from preview import Posed


def badge_vertices(model, matrices, mount):
    """Same deformed triangle, tangent and surface offset as YukkuriModel.renderBadge."""
    first = mount['triangle']
    source = np.array(model['vertices']).reshape(-1, 8)[first:first+3, :3]
    weights = np.array(model['weights'])[first:first+3]
    points = []
    for point, (a, b, q) in zip(source, weights):
        h = np.r_[point, 1]
        points.append(((matrices[int(a)]@h)*(1-q)+(matrices[int(b)]@h)*q)[:3])
    points = np.array(points)
    normal = np.cross(points[1]-points[0], points[2]-points[0]); normal /= np.linalg.norm(normal)
    rest_right = np.cross([0, 1, 0], mount['rest_normal']); rest_right /= np.linalg.norm(rest_right)
    edges = np.array([source[1]-source[0], source[2]-source[0]])
    u, v = np.linalg.solve(edges@edges.T, edges@rest_right)
    right = (points[1]-points[0])*u+(points[2]-points[0])*v; right /= np.linalg.norm(right)
    up = np.cross(normal, right); up /= np.linalg.norm(up)
    half = mount['diameter']*8
    center = np.array(mount['weights'])@points+normal*max(.0007*16, half*.025)
    corners = np.array([center+right*x*half+up*y*half for x, y in [(-1,-1),(1,-1),(1,1),(-1,1)]])
    uv = np.array([[.5,.0625],[.5625,.0625],[.5625,0],[.5,0]])
    return np.concatenate([np.c_[corners[ix],uv[ix],np.tile(normal,(3,1))] for ix in ([0,1,2],[0,2,3])])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--node', default='node')
    args = parser.parse_args()
    out = ROOT/'.local/previews/tsumuri-badge'; out.mkdir(parents=True, exist_ok=True)
    corrections = json.loads((ASSETS/'yukkuri_presentation/motion_corrections.json').read_text(encoding='utf-8'))
    posed = Posed('marisa_tsumuri', corrections)
    mount = json.loads((ASSETS/'yukkuri/badge_mounts.json').read_text(encoding='utf-8'))['variants']['marisa_tsumuri']
    panels = [('金章 · 正面','gold','idle',0,0),('金章 · 右前','gold','idle',0,.7),
              ('银章 · 右侧','silver','idle',0,1.1),('金章 · 缩壳','gold','shell_retract',1.4,.7)]
    for i, (_, tier, clip, time, _) in enumerate(panels):
        tri, uv, normal = posed.frame(clip,time)
        vertices = np.c_[tri.reshape(-1,3), uv.reshape(-1,2)*.5, np.repeat(normal,3,axis=0)]
        vertices = np.r_[vertices, badge_vertices(posed.model,posed.last_matrices,mount)]
        atlas = Image.new('RGBA',(1024,1024))
        atlas.paste(Image.fromarray(posed.texture(posed.face(clip,time))),(0,0))
        badge = Image.open(ASSETS/f'textures/item/{tier}_yukkuri_badge.png').convert('RGBA')
        atlas.paste(badge.resize((64,64),Image.Resampling.NEAREST),(512,0))
        (out/f'{i}.json').write_text(json.dumps(vertices.flatten().tolist()),encoding='utf-8')
        (out/f'{i}-texture.rgba').write_bytes(atlas.tobytes())
    code = "const fs=require('fs');\n"+(ROOT/'standards/yukkuri-library/runtime/render.js').read_text(encoding='utf-8')+'\n'
    code += 'const dir='+json.dumps(str(out))+';const angles=[0,.7,1.1,.7];\n'
    code += "for(let i=0;i<4;i++){const v=JSON.parse(fs.readFileSync(dir+'/'+i+'.json'));const t=fs.readFileSync(dir+'/'+i+'-texture.rgba');fs.writeFileSync(dir+'/'+i+'-render.rgba',renderModel(v,t,540,540,angles[i],.15,16,{centerY:12,textureSize:1024}));}"
    runner = out/'render.cjs'; runner.write_text(code,encoding='utf-8')
    subprocess.run([args.node,str(runner)],check=True)
    sheet = Image.new('RGB',(1080,1240),(240,238,230)); draw = ImageDraw.Draw(sheet)
    font = ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',24)
    draw.text((20,12),'螺壳魔理沙 · 当前运行模型徽章挂点（离线 / 待用户确认）',font=font,fill='#34362c')
    for i,(label,*_) in enumerate(panels):
        x,y=i%2*540,56+i//2*580
        draw.text((x+20,y),label,font=font,fill='#34362c')
        image=Image.frombytes('RGBA',(540,540),(out/f'{i}-render.rgba').read_bytes())
        sheet.paste(image,(x,y+32),image)
    result=out/'tsumuri-badge.png'; sheet.save(result); print(result)


if __name__ == '__main__': main()
