"""Review current game geometry through the same authored hat root and carry transform."""
from pathlib import Path
import base64,json,subprocess,os
from PIL import Image,ImageDraw,ImageFont
ROOT=Path(__file__).resolve().parents[1]
LIB=ROOT/'standards/yukkuri-library'
ASSETS=ROOT/'src/main/resources/assets/mcyukkuri'
OUT=ROOT/'art/source/hat-interactions/previews/current'
TMP=ROOT/'.local/hat-interactions/render'
def read(p):return json.loads(p.read_text(encoding='utf-8'))
def main():
    OUT.mkdir(parents=True,exist_ok=True);TMP.mkdir(parents=True,exist_ok=True)
    hats=read(ASSETS/'yukkuri_presentation/hat_interactions.json')['variants']
    presentation=read(ASSETS/'yukkuri_presentation/living.json')['variants']|read(ASSETS/'yukkuri_presentation/subspecies.json')
    models={i:read(ASSETS/'yukkuri'/f'{i}.json') for i in list(hats)+['reimu','house_reimu']}
    textures={};names={'marisa':'魔理沙','marisa_sparse':'稀毛魔理沙','marisa_face':'歪脸魔理沙','marisa_bean':'豆形魔理沙','house_marisa':'家魔理沙'}
    for ident in hats:
        child='house_reimu' if ident=='house_marisa' else 'marisa_bean' if ident=='marisa_bean' else 'reimu'
        atlas=Image.new('RGBA',(512,512))
        for x,key in [(0,ident),(256,child)]:atlas.paste(Image.open(ASSETS/'textures/entity'/key/'neutral.png').convert('RGBA').resize((256,256),Image.Resampling.NEAREST),(x,0))
        atlas.save(TMP/f'{ident}.png');(TMP/f'{ident}.rgba').write_bytes(atlas.tobytes())
        textures[ident]='data:image/png;base64,'+base64.b64encode((TMP/f'{ident}.png').read_bytes()).decode()
    data='const HATS='+json.dumps(hats,separators=(',',':'))+';const MODELS='+json.dumps(models,separators=(',',':'))+';const PREFIXES='+json.dumps({i:presentation[i]['accessory_prefixes'] for i in hats})+';const TEX='+json.dumps(textures)+';\n'
    pose=(LIB/'body-standard/previews/current/pose.js').read_text(encoding='utf-8')
    pose=pose.replace(' const out=[],src=model.vertices,surface=[];',' hatWorld=world;\n const out=[],src=model.vertices,surface=[];')
    engine='let hatWorld;\n'+(LIB/'runtime/render.js').read_text(encoding='utf-8')+'\n'+pose+r'''
function hatScene(id,mode,q){
 const m=MODELS[id],hat=HATS[id],carry=mode==='carry',bare=mode==='bare';
 let model=bare?{...m,parts:m.parts.filter(p=>!PREFIXES[id].some(prefix=>p.name.startsWith(prefix)))}:m;
 let overrides=null;
 if(mode==='remove'||mode==='return'){
  const phase=mode==='return'?1-q:q,smooth=x=>x*x*(3-2*x);
  overrides={hat_follow:{position:[0,hat.lift*smooth(Math.min(1,phase/.55)),hat.forward*smooth(Math.max(0,(phase-.4)/.6))]}};
 }
 const vertices=posed(model,carry?'waddle':'idle',carry?q*m.clips.waddle.duration:0,overrides),world=hatWorld[hat.bone];
 for(let i=0;i<vertices.length;i+=8){vertices[i+3]*=.5;vertices[i+4]*=.5;}
 if(carry){
  const child=MODELS[id==='house_marisa'?'house_reimu':id==='marisa_bean'?'marisa_bean':'reimu'],ratio=.12;
  const v=posed(child,'idle',0);
  for(let i=0;i<v.length;i+=8){
   const p=[v[i]*ratio+hat.seat[0],v[i+1]*ratio+hat.seat[1],v[i+2]*ratio+hat.seat[2]];
   for(let r=0;r<3;r++)v[i+r]=world[r*4]*p[0]+world[r*4+1]*p[1]+world[r*4+2]*p[2]+world[r*4+3];
   const n=v.slice(i+5,i+8);for(let r=0;r<3;r++)v[i+5+r]=world[r*4]*n[0]+world[r*4+1]*n[1]+world[r*4+2]*n[2];
   v[i+3]=v[i+3]*.5+.5;v[i+4]*=.5;
  }for(const value of v)vertices.push(value);
 }
 const scale=id==='house_marisa'?1/.32:1;
 for(let i=0;i<vertices.length;i+=8)for(let k=0;k<3;k++)vertices[i+k]*=scale;
 return vertices;
}
'''
    (OUT/'models.js').write_text(data,encoding='utf-8');(OUT/'engine.js').write_text(engine,encoding='utf-8')
    jobs=[]
    for ident in hats:
        for mode in ['worn','bare','remove','return','carry']:
            for phase in ([0] if mode in ['worn','bare'] else range(13)):
                # House newborns have a larger minimum size; no invented undersized house baby is previewed.
                if ident=='house_marisa' and mode=='carry':continue
                jobs.append(dict(id=ident,mode=mode,q=phase/12,file=f'{ident}-{mode}-{phase}'))
    script="const fs=require('fs');\n"+data+engine+'\nconst jobs='+json.dumps(jobs)+';const dir='+json.dumps(str(TMP))+';\n'
    script+="for(const j of jobs){const v=hatScene(j.id,j.mode,j.q);if(v.some(n=>!Number.isFinite(n)))throw Error(j.file);fs.writeFileSync(dir+'/'+j.file+'.rgba',renderModel(v,fs.readFileSync(dir+'/'+j.id+'.rgba'),440,440,.52,.15,13.7,{centerY:11.5,textureSize:512}));}"
    (TMP/'render.cjs').write_text(script,encoding='utf-8');subprocess.run(['node',str(TMP/'render.cjs')],check=True)
    font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',20)
    def tile(name):
        im=Image.frombytes('RGBA',(440,440),(TMP/f'{name}.rgba').read_bytes());bg=Image.new('RGB',im.size,(241,237,227));bg.paste(im,mask=im.getchannel('A'));return bg
    overview=Image.new('RGB',(1760,5*485),(241,237,227));draw=ImageDraw.Draw(overview)
    for row,ident in enumerate(hats):
        modes=[('worn',0,'戴帽'),('bare',0,'去帽 · 头发保留'),('remove',8,'独立脱帽'),('carry',4,'小幼油帽托')]
        if ident=='house_marisa':modes[-1]=('return',6,'独立戴回')
        for col,(mode,phase,label) in enumerate(modes):
            draw.text((col*440+10,row*485+10),names[ident]+' / '+label,font=font,fill='#303229');overview.paste(tile(f'{ident}-{mode}-{phase}'),(col*440,row*485+42))
        for mode in ['remove','return','carry']:
            if ident=='house_marisa' and mode=='carry':continue
            frames=[tile(f'{ident}-{mode}-{i}') for i in range(13)]
            frames[0].save(OUT/f'{ident}-{mode}.gif',save_all=True,append_images=frames[1:],duration=90,loop=0)
    overview.save(OUT/'overview.png')
    (OUT/'index.html').write_text('''<!doctype html><meta charset="utf-8"><title>魔理沙帽子交互</title><style>body{margin:30px;background:#f1ede3;font:17px system-ui;color:#303229}canvas{max-width:100%;background:#f1ede3}select,button,input{font:inherit;margin:8px;padding:8px}p{max-width:850px}</style><h1>魔理沙帽子交互</h1><p>帽子独立取下、戴回，头发留在身体上。帽托展示单只可放稳的小幼油；家油当前帽檐容不下正常出生尺寸的家幼油。拖动查看方向，暂停检查接触。当前造型待确认。</p><select id="subject">'''+''.join(f'<option value="{i}">{n}</option>' for i,n in names.items())+'''</select><select id="mode"><option value="worn">戴帽</option><option value="bare">去帽</option><option value="remove">取帽</option><option value="return">戴回</option><option value="carry">帽托移动</option></select><button id="pause">暂停</button><input id="phase" type="range" min="0" max="1" step=".01" value="0"><br><canvas id="view" width="700" height="650"></canvas><script src="models.js"></script><script src="engine.js"></script><script>
const canvas=document.querySelector('#view'),ctx=canvas.getContext('2d'),subject=document.querySelector('#subject'),mode=document.querySelector('#mode'),phase=document.querySelector('#phase');let playing=true,q=0,az=.52,last=0;const textures={};
for(const [id,src]of Object.entries(TEX)){const im=new Image();im.onload=()=>{const c=document.createElement('canvas');c.width=c.height=512;const x=c.getContext('2d');x.drawImage(im,0,0);textures[id]=x.getImageData(0,0,512,512).data;};im.src=src;}
function paint(){let selected=mode.value;if(subject.value==='house_marisa'&&selected==='carry')selected='worn';if(textures[subject.value])ctx.putImageData(new ImageData(renderModel(hatScene(subject.value,selected,q),textures[subject.value],700,650,az,.15,21,{centerY:11.5,textureSize:512}),700,650),0,0);}
document.querySelector('#pause').onclick=()=>{playing=!playing;document.querySelector('#pause').textContent=playing?'暂停':'播放';};phase.oninput=()=>{q=+phase.value;playing=false;paint();};subject.onchange=()=>{q=0;paint();};mode.onchange=()=>{q=0;paint();};let drag=null;canvas.onpointerdown=e=>{drag=e.clientX;canvas.setPointerCapture(e.pointerId);};canvas.onpointermove=e=>{if(drag!==null){az+=(e.clientX-drag)*.01;drag=e.clientX;paint();}};canvas.onpointerup=()=>drag=null;
function frame(t){if(playing&&last)q=(q+Math.min(t-last,100)/1800)%1;last=t;phase.value=q;paint();requestAnimationFrame(frame);}requestAnimationFrame(frame);
</script>''',encoding='utf-8')
    print('Rendered',len(jobs),'actual geometry poses and independent-hat/carry animations.')
if __name__=='__main__':main()
