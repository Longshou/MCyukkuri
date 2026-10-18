'use strict';
const fs=require('fs'),path=require('path'),vm=require('vm');
const {decode,encode}=require('./png.js');
const root=path.resolve(__dirname,'../../../..');
const {RuntimePose}=require(path.join(root,'preview/review/pose.js'));
const {buildHeatClips}=require('./heat-clips.js');
const raster={};vm.runInNewContext(fs.readFileSync(path.join(root,'standards/yukkuri-library/runtime/render.js'),'utf8'),raster);
const model=JSON.parse(fs.readFileSync(path.join(root,'src/main/resources/assets/mcyukkuri/yukkuri/alice.json'),'utf8'));
const heat=decode(path.join(__dirname,'variant/heat.png'));
const pose=new RuntimePose(model,1,null,null);
Object.assign(pose.clips,buildHeatClips(model,{variant:'alice_rapem'}));
function visible(out){
 const chunks=[];for(const part of pose.model.parts){if(part.anatomy)continue;for(let i=part.start;i<part.start+part.count;i++){const o=i*8;chunks.push(out.subarray(o,o+8));}}
 const v=new Float32Array(chunks.length*8);chunks.forEach((c,i)=>v.set(c,i*8));
 // ground clamp for a clean still
 let min=Infinity;for(let i=0;i<v.length;i+=8)min=Math.min(min,v[i+1]);
 const lift=min<0.02?0.02-min:0;if(lift)for(let i=1;i<v.length;i+=8)v[i]+=lift;
 return v;
}
const W=480,H=480,cols=3,rows=2;
const cells=[
 {clip:'heat_onset',t:0.85,aura:0.35},
 {clip:'heat_onset',t:2.9,aura:0.62},
 {clip:'heat_onset',t:5.55,aura:0.95},
 {clip:'heat_loop',t:1.2,aura:0.82},
 {clip:'rapem_onset',t:3.45,aura:0.82,feral:true},
 {clip:'rapem_onset',t:5.55,aura:1,feral:true}
];
const sheet=Buffer.alloc(W*cols*H*rows*4);
for(let cell=0;cell<cells.length;cell++){
 const {clip,t,aura,feral}=cells[cell];
 const out=pose.frame(clip,t);const v=visible(out);
 const px=raster.renderModel(v,heat.data,W,H,0.34,0.12,13.8,{centerY:4.8,textureSize:256});
 const ox=(cell%cols)*W,oy=Math.floor(cell/cols)*H;
 for(let y=0;y<H;y++)for(let x=0;x<W;x++){
  const i=(y*W+x)*4,di=((oy+y)*W*cols+ox+x)*4;
  const dx=(x-W/2)/W,dy=(y-H*0.43)/H;
  const glow=Math.max(0,1-Math.hypot(dx*1.55,dy*1.35)*1.6);
  let r=9+30*glow,g=16+40*glow,b=14+32*glow;
  // floor shadow
  const sx=(x-W/2)/(W*0.28),sy=(y-H*0.675)/(H*0.045);
  const sh=Math.exp(-(sx*sx+sy*sy))*0.55;
  r*=1-sh;g*=1-sh;b*=1-sh;
  if(feral){const fg=Math.max(0,1-Math.hypot(dx*1.3,dy*1.1)*1.8);r+=38*fg;g+=8*fg;b+=4*fg;}
  const a=px[i+3]/255;
  if(a>0){r=r*(1-a)+px[i]*a;g=g*(1-a)+px[i+1]*a;b=b*(1-a)+px[i+2]*a;}
  sheet[di]=Math.max(0,Math.min(255,r));
  sheet[di+1]=Math.max(0,Math.min(255,g));
  sheet[di+2]=Math.max(0,Math.min(255,b));
  sheet[di+3]=255;
 }
}
// subtle separators
for(let x=0;x<W*cols;x++){for(const y of [0,H-1,H,2*H-1]){const di=(y*W*cols+x)*4;sheet[di]=23;sheet[di+1]=38;sheet[di+2]=33;}}
for(let y=0;y<H*rows;y++){for(const x of [0,W-1,W,2*W-1,2*W,3*W-1]){const di=(y*W*cols+x)*4;sheet[di]=23;sheet[di+1]=38;sheet[di+2]=33;}}
encode(W*cols,H*rows,sheet,path.join(root,'.local/previews/rapem-alice/contact.png'));
console.log('wrote contact');
