"""Render current exported house models with the preview's own rasterizer.

Uses existing Node.js and Pillow; no browser, server or game is launched.
Scratch data stays under the project .local directory. PNGs carry a manifest
with model/source hashes so a reviewer can identify the exact reviewed version.
"""
from pathlib import Path
import argparse, base64, hashlib, io, json, subprocess
from PIL import Image, ImageDraw, ImageFont

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[3]
LIB = HERE.parents[1]

NODE_RENDERER = r"""
const fs=require('fs'),vm=require('vm'),path=require('path');
const [input,runtime,out]=process.argv.slice(1);
const data=JSON.parse(fs.readFileSync(input,'utf8'));
const context=vm.createContext({});
vm.runInContext(fs.readFileSync(path.join(runtime,'render.js'),'utf8'),context);
vm.runInContext(fs.readFileSync(path.join(runtime,'motion.js'),'utf8'),context);
for(const [species,model] of Object.entries(data.characters)){
 const texture=fs.readFileSync(path.join(out,species+'.texture.rgba'));
 for(const view of data.views){
  const vertices=context.poseVertices(model.vertices,model.rig,view.time||0,true,false,view.clip||'idle');
  const pixels=context.renderModel(vertices,texture,data.width,data.height,view.az,view.el,view.span||3.55,
   {textureSize:model.textureSize,centerY:view.centerY??2.65});
  fs.writeFileSync(path.join(out,species+'-'+view.id+'.rgba'),Buffer.from(pixels));
 }
}
"""


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output',type=Path,default=HERE/'previews/current')
    parser.add_argument('--label',default='current')
    args=parser.parse_args()
    output=args.output.resolve();output.relative_to(ROOT)
    output.mkdir(parents=True,exist_ok=True)
    scratch=ROOT/'.local/house-yukkuri-render';scratch.mkdir(parents=True,exist_ok=True)
    html=(HERE/'index.html').read_text(encoding='utf-8')
    data=json.JSONDecoder().raw_decode(html.split('const DATA=',1)[1])[0]
    views=[dict(id='front',az=0,el=0),dict(id='three-quarter',az=.65,el=.14),
           dict(id='side',az=1.5707963268,el=0),dict(id='back',az=3.1415926536,el=.10)]
    views.extend([dict(id='lower-front',az=.60,el=-.12,span=2.3,centerY=.9),
        dict(id='rear-quarter',az=2.50,el=.12),
        dict(id='rump-close',az=3.1415926536,el=0,span=1.35,centerY=.85),
        dict(id='bow-close',az=2.75,el=.12,span=1.65,centerY=3.55),
        dict(id='waddle',az=.65,el=.14,clip='waddle',time=.24)])
    for part,az in [('peni',.65),('mamu',2.5)]:
        for state,time in [('retracted',0),('half',.12),('extended',.24)]:
            views.append(dict(id=part+'-'+state,az=az,el=.05,span=1.65,centerY=.90,
                clip='anatomy_'+part+'_extend',time=time))
    width=height=640
    scene=dict(characters=data['characters'],views=views,width=width,height=height)
    for species,model in scene['characters'].items():
        with Image.open(io.BytesIO(base64.b64decode(model['textures']['neutral'].split(',',1)[1]))) as im:
            (scratch/(species+'.texture.rgba')).write_bytes(im.convert('RGBA').tobytes())
    scene_path=scratch/'scene.json'
    scene_path.write_text(json.dumps(scene,separators=(',',':')),encoding='utf-8')
    subprocess.run(['node','-e',NODE_RENDERER,str(scene_path),str(LIB/'runtime'),str(scratch)],check=True)
    sheet=Image.new('RGB',(width*4,height*2+100),'#eee9df')
    draw=ImageDraw.Draw(sheet)
    font=ImageFont.truetype('C:/Windows/Fonts/arial.ttf',24)
    draw.text((24,12),'HOUSE YUKKURI / '+args.label+' / exported geometry + viewer renderer',font=font,fill='#403a32')
    files=[]
    for row,species in enumerate(['reimu','marisa']):
        for col,view in enumerate(views):
            name=species+'-'+view['id']+'.png'
            im=Image.frombytes('RGBA',(width,height),(scratch/(species+'-'+view['id']+'.rgba')).read_bytes())
            bg=Image.new('RGBA',im.size,'#eee9df');bg.alpha_composite(im)
            tile=bg.convert('RGB');ImageDraw.Draw(tile).text((20,16),species+' / '+view['id'],font=font,fill='#403a32')
            tile.save(output/name)
            if col<4:sheet.paste(tile,(col*width,64+row*height))
            files.append(name)
    sheet.save(output/'turnaround.png');files.append('turnaround.png')
    anatomy_sheet=Image.new('RGB',(width*3,height*2),'#eee9df')
    for row,part in enumerate(['peni','mamu']):
        for col,state in enumerate(['retracted','half','extended']):
            with Image.open(output/('reimu-'+part+'-'+state+'.png')) as tile:
                anatomy_sheet.paste(tile,(col*width,row*height))
    anatomy_sheet.save(output/'anatomy-review.png');files.append('anatomy-review.png')
    sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
    manifest=dict(design=data['config']['design'],label=args.label,
        renderer='../../../../runtime/render.js',renderer_sha256=sha(LIB/'runtime/render.js'),
        index_sha256=sha(HERE/'index.html'),source_sha256=sha(HERE/'reference_design.py'),
        models={s:sha(HERE/'assembled'/('house_'+s+'.bbmodel')) for s in scene['characters']},
        views=views,width=width,height=height,images={n:sha(output/n) for n in files},
        scope='Neutral expression; four general views, four defect closeups, one waddle sample, adult anatomy at rest/midpoint/extension; no browser or game runtime validation.')
    (output/'render-manifest.json').write_text(json.dumps(manifest,indent=2)+'\n',encoding='utf-8')
    print(output/'turnaround.png')


if __name__=='__main__':
    main()
