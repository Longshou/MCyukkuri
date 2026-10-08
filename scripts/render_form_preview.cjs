/* Quick offline preview sheet for one or more runtime forms.

   Uses the same splice (placement fit), motion revision and rasterizer as the review room, so the
   picture matches what YukkuriModel draws (no Minecraft lighting). Writes one PNG sheet.

   node scripts/render_form_preview.cjs --ids marisa_sparse,marisa --out .local/preview/hat.png
        [--fit <placement_fit.json>] [--clips idle:0,walk:0.4] [--views front,quarter,side,back,top]
        [--size 360] [--wrap columns] [--span 13] [--center 9] [--expression neutral] [--hide regex] [--no-motion]

   --no-motion skips the motion revision (base clips only), e.g. while the fit changes lock meshes before re-export.

   --fit defaults to the packaged resource; pass a trial file from export_placement_fit.cjs --out.
   Textures are decoded once through Python/Pillow into .local/preview-cache. */
'use strict';
const fs=require('fs'),path=require('path'),vm=require('vm'),zlib=require('zlib'),cp=require('child_process');
const {RuntimePose}=require('../preview/review/pose.js');
const root=path.resolve(__dirname,'..'),assets=path.join(root,'src/main/resources/assets/mcyukkuri');
const args=process.argv.slice(2),value=(n,d)=>{const i=args.indexOf(n);return i>=0?args[i+1]:d;};
const ids=value('--ids','marisa_sparse').split(','),out=path.resolve(root,value('--out','.local/preview/preview.png'));
const fitFile=path.resolve(root,value('--fit',path.join(assets,'yukkuri_presentation/placement_fit.json')));
const clips=value('--clips','idle:0').split(',').map(s=>{const [c,t]=s.split(':');return [c,+(t||0)];});
const VIEWS={front:[0,0.12],quarter:[Math.PI/4,0.2],side:[Math.PI/2,0.05],back:[Math.PI,0.15],rquarter:[-Math.PI*3/4,0.2],low:[0.35,-0.35],top:[0.5,0.9]};
const views=value('--views','front,quarter,side,back').split(',');
const wrap=+value('--wrap',0),size=+value('--size',300),span=+value('--span',13),centerY=+value('--center',9),expression=value('--expression','neutral'),hide=value('--hide',null);
const raster={};vm.runInNewContext(fs.readFileSync(path.join(root,'standards/yukkuri-library/runtime/render.js'),'utf8'),raster);
const fits=JSON.parse(fs.readFileSync(fitFile)).models,revisions=JSON.parse(fs.readFileSync(path.join(assets,'yukkuri_presentation/motion_corrections.json'))).models;
const cache=path.join(root,'.local/preview-cache');fs.mkdirSync(cache,{recursive:true});
function texture(id,name){
 const png=path.join(assets,'textures/entity',id,name+'.png'),raw=path.join(cache,`${id}-${name}.rgba`);
 if(!fs.existsSync(raw)||fs.statSync(raw).mtimeMs<fs.statSync(png).mtimeMs)
  cp.execFileSync('python3',['-I','-c','import sys;from PIL import Image;im=Image.open(sys.argv[1]).convert("RGBA");open(sys.argv[2],"wb").write(im.tobytes())',png,raw]);
 return new Uint8ClampedArray(fs.readFileSync(raw));
}
function writePng(file,w,h,rgba){
 const crc=b=>{let c=~0;for(const x of b){c^=x;for(let k=0;k<8;k++)c=c&1?0xedb88320^(c>>>1):c>>>1;}return ~c>>>0;};
 const chunk=(t,d)=>{const len=Buffer.alloc(4);len.writeUInt32BE(d.length);const td=Buffer.concat([Buffer.from(t),d]),c=Buffer.alloc(4);c.writeUInt32BE(crc(td));return Buffer.concat([len,td,c]);};
 const rows=Buffer.alloc((w*4+1)*h);for(let y=0;y<h;y++){rows[y*(w*4+1)]=0;Buffer.from(rgba.buffer,rgba.byteOffset+y*w*4,w*4).copy(rows,y*(w*4+1)+1);}
 const ihdr=Buffer.alloc(13);ihdr.writeUInt32BE(w,0);ihdr.writeUInt32BE(h,4);ihdr[8]=8;ihdr[9]=6;
 fs.mkdirSync(path.dirname(file),{recursive:true});
 fs.writeFileSync(file,Buffer.concat([Buffer.from([137,80,78,71,13,10,26,10]),chunk('IHDR',ihdr),chunk('IDAT',zlib.deflateSync(rows)),chunk('IEND',Buffer.alloc(0))]));
}
const cells=views.length*clips.length,cols=wrap>0?Math.min(wrap,cells):cells,band=Math.ceil(cells/cols),rowsN=ids.length*band,W=cols*size,H=rowsN*size,sheet=new Uint8ClampedArray(W*H*4);
for(let i=0;i<W*H;i++){sheet[i*4]=198;sheet[i*4+1]=206;sheet[i*4+2]=218;sheet[i*4+3]=255;}
ids.forEach((id,r)=>{
 const model=JSON.parse(fs.readFileSync(path.join(assets,'yukkuri',id+'.json')));
 const pose=new RuntimePose(model,1,args.includes('--no-motion')?null:revisions[id]||null,fits[id]||null);pose.expression(expression);
 const tex=texture(id,expression),texSize=model.texture_size||128,hideRe=hide?new RegExp(hide):null;
 clips.forEach(([clip,t],ci)=>{
  if(!pose.clips[clip])throw Error(id+': no clip '+clip);
  const frame=pose.frame(clip,t);
  if(hideRe)pose.model.parts.forEach(p=>{if(hideRe.test(p.name))frame.fill(0,p.start*8,(p.start+p.count)*8);});
  views.forEach((v,vi)=>{const [az,el]=VIEWS[v];
   const px=raster.renderModel(frame,tex,size,size,az,el,span,{textureSize:texSize,centerY});
   const cell=ci*views.length+vi,ox=(cell%cols)*size,oy=(r*band+Math.floor(cell/cols))*size;
   for(let y=0;y<size;y++)for(let x=0;x<size;x++){const s=(y*size+x)*4;if(px[s+3]<1)continue;const d=((oy+y)*W+ox+x)*4;sheet[d]=px[s];sheet[d+1]=px[s+1];sheet[d+2]=px[s+2];}
  });
 });
});
writePng(out,W,H,sheet);
console.log(`Wrote ${path.relative(root,out)}: rows ${ids.join(', ')}; columns ${clips.map(c=>c.join('@')).join(' | ')} x ${views.join('/')}`);
