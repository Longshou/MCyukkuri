'use strict';
// CPU-only rendering. No browser or desktop automation is used.
const fs=require('fs'),path=require('path'),vm=require('vm');
const {decode,encode}=require('./png.js');
const root=path.resolve(__dirname,'../../../..');
const {RuntimePose}=require(path.join(root,'preview/review/pose.js'));
const {buildHeatClips}=require('./motion-clips.js');
const raster={};vm.runInNewContext(fs.readFileSync(path.join(root,'standards/yukkuri-library/runtime/render.js'),'utf8'),raster);
const model=JSON.parse(fs.readFileSync(path.join(root,'src/main/resources/assets/mcyukkuri/yukkuri/alice.json'),'utf8'));
const pose=new RuntimePose(model,1,null,null);Object.assign(pose.clips,buildHeatClips(model,{variant:'alice_rapem'}));
const textures={};for(const face of ['neutral','confused','surprise','satisfied','open_drool','flushed_tongue'])textures[face]=decode(path.join(root,'standards/yukkuri-library/assembled/alice/textures',face+'.png'));
for(const face of ['breeding_period','state_recover'])textures[face]=decode(path.join(__dirname,'variant',face+'.png'));
const visibleParts=model.parts.filter(p=>!p.anatomy&&!/^(peni_taper|mamu_)/.test(p.name));
const peniParts=model.parts.filter(p=>p.anatomy==='peni');
const peniCount=peniParts.reduce((n,p)=>n+p.count,0);
// RuntimePose hides anatomy parts unless the action name says anatomy_; the act render copies the
// extended peni ranges from a second anatomy pass so the offline GIF shows the real model part.
function posedVertices(clip,t,withPeni){
 const body=Float64Array.from(pose.frame(clip,t));
 if(!withPeni||!peniCount)return body;
 pose.frame('anatomy_peni_extend',Math.min(t,.24));
 const out=pose.output;
 // The anatomy pass is unrotated; carry the copied peni through the same act transform the
 // body gets from the posture track, so it stays fixed relative to the body.
 const peni=transformPose(Float64Array.from(out),sampleChannel('act',t%1));
 for(const part of peniParts)body.set(peni.subarray(part.start*8,(part.start+part.count)*8),part.start*8);
 return body;
}
function visible(out){
 const count=visibleParts.reduce((n,p)=>n+p.count,0)+(out.penii?peniCount:0);
 const parts=out.penii?visibleParts.concat(peniParts):visibleParts;
 const v=new Float32Array(count*8);let cursor=0,minY=Infinity;
 for(const part of parts){v.set(out.buffer.subarray(part.start*8,(part.start+part.count)*8),cursor);cursor+=part.count*8;}
 // Grounding follows the game: anatomy never feeds the lift, but the lift moves every part.
 for(const part of visibleParts){const base=cursorBase(parts,part);for(let i=base+1;i<base+part.count*8;i+=8)minY=Math.min(minY,v[i]);}
 const lift=Math.max(0,.02-minY);for(let i=1;i<v.length;i+=8)v[i]+=lift;
 return v;
}
function cursorBase(parts,part){let base=0;for(const p of parts){if(p===part)return base;base+=p.count*8;}return base;}
const W=600,H=440,outDir=path.join(root,'.local/previews/rapem-alice/frames');fs.mkdirSync(outDir,{recursive:true});
// The pinned victim (rear-mount demo): reimu pressed face-forward, transformed by the pinned channel.
const victimModel=JSON.parse(fs.readFileSync(path.join(root,'src/main/resources/assets/mcyukkuri/yukkuri/reimu.json'),'utf8'));
const victimPose=new RuntimePose(victimModel,1,null,null);
const victimTex=decode(path.join(root,'standards/yukkuri-library/assembled/reimu/textures/squeezed_cry_tears.png'));
const victimMamuParts=victimModel.parts.filter(p=>p.anatomy==='mamu');
const poseCues=JSON.parse(fs.readFileSync(path.join(__dirname,'pose-cues.json'),'utf8'));
function sampleChannel(channel,progress){
 const keys=poseCues[channel];let i=0;
 while(i+2<keys.length&&progress>keys[i+1][0])i++;
 const a=keys[i],b=keys[i+1],q=Math.max(0,Math.min(1,(progress-a[0])/(b[0]-a[0]))),m=q*q*(3-2*q);
 const v=a.slice(1).map((x,k)=>x+(b[k+1]-x)*m);
 return {pitch:v[0],yaw:v[1],roll:v[2],sx:v[3],sy:v[4],sz:v[5]};
}
function samplePinned(progress){return sampleChannel('pinned',progress);}
function transformPose(out,pose){
 // Same convention as the game and RuntimePose: scale first, then Rz(roll)*Ry(yaw)*Rx(pitch).
 const rad=Math.PI/180,cp=Math.cos(pose.pitch*rad),sp=Math.sin(pose.pitch*rad),cy=Math.cos(pose.yaw*rad),syw=Math.sin(pose.yaw*rad),cr=Math.cos(pose.roll*rad),sr=Math.sin(pose.roll*rad);
 for(let i=0;i<out.length;i+=8){
  const x0=out[i]*pose.sx,y0=out[i+1]*pose.sy,z0=out[i+2]*pose.sz;
  const y1=y0*cp-z0*sp,z1=y0*sp+z0*cp,x1=x0;
  const x2=x1*cy+z1*syw,z2=-x1*syw+z1*cy,y2=y1;
  const x3=x2*cr-y2*sr,y3=x2*sr+y2*cr,z3=z2;
  out[i]=x3;out[i+1]=y3;out[i+2]=z3;
 }
 return out;
}
function applyPose(out,pose,offsetX,offsetZ){
 transformPose(out,pose);
 let minY=Infinity;
 for(let i=1;i<out.length;i+=8)minY=Math.min(minY,out[i]);
 const lift=Math.max(0,.02-minY);
 for(let i=1;i<out.length;i+=8){out[i]+=lift;out[i-1]+=offsetX;out[i+1]+=offsetZ;}
 return out;
}
// The act review shot: the game's real rear spacing (0.675 blocks = 10.8 units) at a raised
// three-quarter camera, so the prone victim and the hunter over her both read on screen.
// The interactive page (index.html) offers the same scene under a freely rotatable camera.
const ACT_CAM={az:.95,el:.28,span:13.5,attackerX:6,victimX:6,victimZ:-10.8};
for(const branch of ['heat','relief','act','rapem']){
 const maxFrame=branch==='heat'?80:branch==='relief'?60:branch==='act'?60:100;
 const cam=branch==='act'?ACT_CAM:{az:.35,el:.03,span:10.3};
 for(let frame=0;frame<=maxFrame;frame++){
  const t=frame/10,clip=branch==='relief'?'heat_relief':branch==='act'?'rapem_act':branch+'_onset',face=pose.expressionFor(clip,t,'neutral');
  const withPeni=branch==='act';
  const posed=posedVertices(clip,t,withPeni);
  const out={buffer:posed,penii:withPeni};
  const attacker=visible(out);
  if(branch==='act')for(let i=0;i<attacker.length;i+=8)attacker[i]+=ACT_CAM.attackerX;
  let layers=[raster.renderModel(attacker,textures[face].data,W,H,cam.az,cam.el,cam.span,{centerY:4.8,textureSize:256})];
  if(branch==='act'){
   // The pinned reimu in front: same thrust clock, pressed face-forward.
   const pinnedBody=Float64Array.from(victimPose.frame('idle',0));
   const mamu=victimPose.frame('anatomy_mamu_extend',.24);
   for(const part of victimMamuParts)pinnedBody.set(mamu.subarray(part.start*8,(part.start+part.count)*8),part.start*8);
   const pinned=applyPose(pinnedBody,samplePinned((frame%10)/10),ACT_CAM.victimX,ACT_CAM.victimZ);
   layers.push(raster.renderModel(pinned,victimTex.data,W,H,cam.az,cam.el,cam.span,{centerY:4.8,textureSize:256}));
  }
  const canvas=Buffer.alloc(W*H*4);
  for(let y=0;y<H;y++)for(let x=0;x<W;x++){
   const i=(y*W+x)*4,glow=Math.max(0,1-Math.hypot((x-W/2)/W,(y-H*.5)/H)*1.3);
   const floor=H/2+4.8*(H/(2*cam.span));
   const shadow=Math.exp(-(((x-W/2)/(W*.24))**2+((y-floor)/(H*.035))**2))*.28;
   let c=[235+9*glow,239+8*glow,236+7*glow].map(v=>v*(1-shadow));
   if(Math.abs(y-floor)<.65)c=[170,185,174];
   canvas[i]=c[0];canvas[i+1]=c[1];canvas[i+2]=c[2];canvas[i+3]=255;
  }
  // Paint the attacker first, the pinned victim last (it stands nearer the camera).
  for(const buffer of layers){
   for(let p=0;p<W*H;p++){
    const i=p*4,a=buffer[i+3]/255;
    if(a<=0)continue;
    for(let j=0;j<3;j++)canvas[i+j]=canvas[i+j]*(1-a)+buffer[i+j]*a;
   }
  }
  encode(W,H,canvas,path.join(outDir,branch+'-'+String(frame).padStart(3,'0')+'.png'));
 }
 console.log('rendered',branch,maxFrame+1,'frames');
}
