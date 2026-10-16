"""Export an empty-stalk item from the selected stem centerline; optional offline software preview."""
from pathlib import Path
import argparse, hashlib, json, math, subprocess
import numpy as np
from PIL import Image, ImageDraw, ImageFont

HERE=Path(__file__).resolve().parent
ROOT=HERE.parents[2]
ASSETS=ROOT/'src/main/resources/assets/mcyukkuri'


def build():
    recipe=json.loads((HERE/'shed-stem.json').read_text(encoding='utf-8'))
    stem_path=ROOT/recipe['stem_source'];stem=json.loads(stem_path.read_text(encoding='utf-8'))
    count=recipe['sample_count'];length=stem['capacity_scale'][str(count)];unit=stem['reference_units_to_model']
    points=np.array([[z*unit*length,(y-stem['reference_origin'][1])*unit*length,0]for z,y in stem['centerline']])
    radii=np.linspace(*stem['radius'],len(points))*recipe['sample_radius_scale']
    tangents=np.array([points[min(i+1,len(points)-1)]-points[max(i-1,0)]for i in range(len(points))])
    tangents/=np.linalg.norm(tangents,axis=1)[:,None]
    rings=[]
    for p,t,r in zip(points,tangents,radii):
        u=np.cross(t,[0,0,1]);u/=np.linalg.norm(u);v=np.cross(t,u)
        rings.append([p+r*(u*math.cos(a*math.pi/4)+v*math.sin(a*math.pi/4))for a in range(8)])
    vertices=[]
    def triangle(a,b,c,uv):
        n=np.cross(b-a,c-a);n/=np.linalg.norm(n)
        for point in [a,b,c]:vertices.extend([*point,*uv,*n])
    for i in range(len(points)-1):
        for j in range(8):
            a,b,c,d=rings[i][j],rings[i+1][j],rings[i+1][(j+1)%8],rings[i][(j+1)%8]
            triangle(a,d,c,[.25,.5]);triangle(a,c,b,[.25,.5])
    for j in range(8):
        triangle(points[0],rings[0][(j+1)%8],rings[0][j],[.75,.5])
        triangle(points[-1],rings[-1][j],rings[-1][(j+1)%8],[.25,.5])
    v=np.array(vertices).reshape(-1,8);bounds=[[float(v[:,k].min()),float(v[:,k].max())]for k in range(3)]
    mesh={'format':1,'units_per_block':16,'vertices':vertices,'parts':[{'name':'empty_main_stalk','start':0,'count':len(v)}],'bounds':bounds}
    item={'parent':'minecraft:builtin/entity','gui_light':'front','textures':{'particle':'mcyukkuri:props/yukkuri_stem'},
          'display':{'gui':{'rotation':[15,180,0],'scale':[.85,.85,.85]},'ground':{'translation':[0,3,0],'scale':[.5,.5,.5]},
                     'firstperson_righthand':{'translation':[0,2,0],'scale':[.7,.7,.7]},'thirdperson_righthand':{'translation':[0,3,0],'scale':[.5,.5,.5]}}}
    texture=Image.new('RGBA',(2,2),tuple(stem['material']['stem'])+(255,))
    for y in range(2):texture.putpixel((1,y),tuple(recipe['root_cut_color'])+(255,))
    import io
    stream=io.BytesIO();texture.save(stream,format='PNG')
    encode=lambda d:(json.dumps(d,ensure_ascii=False,indent=2)+'\n').encode('utf-8')
    outputs={ASSETS/'yukkuri_props/yukkuri_stem.json':encode(mesh),ASSETS/'models/item/yukkuri_stem.json':encode(item),
             ASSETS/'textures/props/yukkuri_stem.png':stream.getvalue(),ASSETS/'yukkuri_props/family-index.json':encode({'yukkuri_stem':{'source':'art/props/family-life/shed-stem.json'}})}
    digest=lambda p:hashlib.sha256(p.read_bytes().replace(b'\r\n',b'\n')).hexdigest()
    provenance={'status':recipe['status'],'source':{'art/props/family-life/shed-stem.json':digest(HERE/'shed-stem.json'),recipe['stem_source']:digest(stem_path)},
                'outputs':{str(p.relative_to(ROOT)).replace('\\','/'):hashlib.sha256(raw).hexdigest()for p,raw in outputs.items()}}
    outputs[HERE/'shed-stem-provenance.json']=encode(provenance)
    return outputs,mesh,texture


def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--check',action='store_true');parser.add_argument('--preview',action='store_true');parser.add_argument('--node',default='node');args=parser.parse_args()
    outputs,mesh,texture=build()
    for path,raw in outputs.items():
        if args.check:
            assert path.exists(),str(path)
            assert json.loads(path.read_bytes())==json.loads(raw)if path.suffix=='.json'else path.read_bytes()==raw,str(path)
        else:path.parent.mkdir(parents=True,exist_ok=True);path.write_bytes(raw)
    print('Empty stem source and five exported files '+('match.'if args.check else'exported.'))
    if args.preview:
        out=ROOT/'.local/previews/shed-stem';out.mkdir(parents=True,exist_ok=True)
        atlas=texture.resize((16,16),Image.Resampling.NEAREST);(out/'texture.rgba').write_bytes(atlas.tobytes())
        v=np.array(mesh['vertices']).reshape(-1,8);center=(v[:,:3].max(axis=0)+v[:,:3].min(axis=0))/2;v[:,:3]-=center
        (out/'model.json').write_text(json.dumps(v.flatten().tolist()),encoding='utf-8')
        code="const fs=require('fs');\n"+(ROOT/'standards/yukkuri-library/runtime/render.js').read_text(encoding='utf-8')+'\nconst dir='+json.dumps(str(out))+';\n'
        code+="const v=JSON.parse(fs.readFileSync(dir+'/model.json')),t=fs.readFileSync(dir+'/texture.rgba');for(let i=0;i<2;i++)fs.writeFileSync(dir+'/'+i+'.rgba',renderModel(v,t,500,540,[0,.65][i],.18,6,{centerY:0,textureSize:16}));"
        runner=out/'render.cjs';runner.write_text(code,encoding='utf-8');subprocess.run([args.node,str(runner)],check=True)
        sheet=Image.new('RGB',(1000,610),(239,240,227));d=ImageDraw.Draw(sheet);font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',24)
        d.text((24,15),'油库里的茎 · 当前导出物品（离线预览 / 待用户确认）',font=font,fill='#30472f')
        for i in range(2):
            im=Image.frombytes('RGBA',(500,540),(out/f'{i}.rgba').read_bytes());sheet.paste(im,(i*500,60),im)
        path=out/'yukkuri-stem.png';sheet.save(path);print(path)


if __name__=='__main__':main()
