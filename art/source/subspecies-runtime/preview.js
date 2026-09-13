/* Preview the exported runtime schema. This page is opened manually, never by the exporter. */
const $=id=>document.getElementById(id), canvas=$('view'), ctx=canvas.getContext('2d');
for(const entry of Object.values(MODELS)) Object.assign(entry.model.clips,entry.model.behavior_clips||{});
const labels={still:'静止',shell_sway:'壳体轻摆',shell_retract:'缩壳探出',fur_sway:'鬓毛轻摆',house_pickup:'俯身夹取',house_carry:'短辫搬运',house_deposit:'俯身放下',house_crawl:'贴地蠕动',idle:'待机',stretch_shape:'伸展造型',side_bend:'侧弯',waddle:'走动',puff:'鼓腮',brace:'撑地',startle:'受惊',turn:'转身',hop:'跳动',stretch:'伸展',exercise_vertical:'纵向体操',exercise_sides:'横向体操',press:'按压',taiso:'完整体操',flee:'逃跑',anatomy_mamu_extend:'麻姆麻姆伸出',anatomy_mamu_retract:'麻姆麻姆收回',anatomy_peni_extend:'佩尼佩尼伸出',anatomy_peni_retract:'佩尼佩尼收回'};
let time=0, playing=true, az=.3, el=.2, zoom=1, last=performance.now(), drawn=0;
const textures={};
const framing={};
for(const [id,entry]of Object.entries(MODELS)){
 let low=Infinity,high=-Infinity,radius=0;
 for(let i=0;i<entry.model.vertices.length;i+=8){const v=entry.model.vertices;low=Math.min(low,v[i+1]);high=Math.max(high,v[i+1]);radius=Math.max(radius,Math.hypot(v[i],v[i+2]));}
 // Keep long tails inside the side/back view as well as the body and hat.
 framing[id]={center:(low+high)/2,span:Math.max((high-low)*.95,radius*canvas.height/canvas.width*1.1)};
}
const clamp=(x,a,b)=>Math.max(a,Math.min(b,x));
function sample(clip,t,poses){
 t=clip.loop?t%clip.duration:clamp(t,0,clip.duration);
 for(const track of clip.tracks){
  const keys=track.keys;let lo=0,hi=keys.length-1;
  while(hi-lo>1){const mid=(lo+hi)>>1;if(keys[mid][0]<=t)lo=mid;else hi=mid;}
  const a=keys[lo],b=keys[Math.min(lo+1,keys.length-1)],q=a[0]===b[0]?0:clamp((t-a[0])/(b[0]-a[0]),0,1);
  const off={position:0,rotation:3,scale:6}[track.channel];
  for(let i=0;i<3;i++)poses[track.bone][off+i]=a[i+1]+(b[i+1]-a[i+1])*q;
 }
}
function posed(model,action,t){
 const poses=model.bones.map(()=>[0,0,0,0,0,0,1,1,1]),world=[];
 sample(model.clips.idle,0,poses);sample(model.clips[action],t,poses);
 for(let i=0;i<model.bones.length;i++){
  const p=model.bones[i].pivot,v=poses[i],[x,y,z]=v.slice(3,6).map(a=>a*Math.PI/180);
  const cx=Math.cos(x),sx=Math.sin(x),cy=Math.cos(y),sy=Math.sin(y),cz=Math.cos(z),sz=Math.sin(z),[a,b,d]=v.slice(6);
  let m=[cz*cy*a,(cz*sy*sx-sz*cx)*b,(cz*sy*cx+sz*sx)*d,0,sz*cy*a,(sz*sy*sx+cz*cx)*b,(sz*sy*cx-cz*sx)*d,0,-sy*a,cy*sx*b,cy*cx*d,0];
  for(let r=0;r<3;r++)m[r*4+3]=p[r]+v[r]-m[r*4]*p[0]-m[r*4+1]*p[1]-m[r*4+2]*p[2];
  const parent=model.bones[i].parent;
  if(parent>=0){const w=world[parent],n=[];for(let r=0;r<3;r++)for(let k=0;k<4;k++)n[r*4+k]=w[r*4]*m[k]+w[r*4+1]*m[k+4]+w[r*4+2]*m[k+8]+(k===3?w[r*4+3]:0);m=n;}
  world.push(m);
 }
 const out=[],src=model.vertices,surface=[];
 for(const part of model.parts){
  if(part.anatomy&&!action.startsWith('anatomy_'+part.anatomy+'_'))continue;
  for(let first=part.start;first<part.start+part.count;first+=3){
   const tri=[];
   for(let i=first;i<first+3;i++){
    const v=src.slice(i*8,i*8+8),[a,b,q]=model.weights[i],ma=world[a],mb=world[b];
    const point=[];for(let r=0;r<3;r++){const off=r*4;const transform=m=>m[off]*v[0]+m[off+1]*v[1]+m[off+2]*v[2]+m[off+3];point[r]=transform(ma)*(1-q)+transform(mb)*q;}
    tri.push([...point,v[3],v[4],0,0,0]);
   }
   const a=tri[1].slice(0,3).map((v,i)=>v-tri[0][i]),b=tri[2].slice(0,3).map((v,i)=>v-tri[0][i]);
   const n=[a[1]*b[2]-a[2]*b[1],a[2]*b[0]-a[0]*b[2],a[0]*b[1]-a[1]*b[0]],length=Math.hypot(...n);
   if(length<1e-8)continue;
   for(let corner=0;corner<3;corner++){
    const v=tri[corner];v.splice(5,3,...n.map(x=>x/length));
    if(part.name==='house_body_surface')surface.push([out.length,first+corner]);
    out.push(...v);
   }
  }
 }
 smoothHouseSurface(model,out,surface);
 return out;
}
function groundVertices(){
 const out=[];
 function quad(x0,z0,x1,z1,y,color){for(const [x,z] of [[x0,z0],[x0,z1],[x1,z1],[x0,z0],[x1,z1],[x1,z0]])out.push(x,y,z,color,0,0,1,0);}
 quad(-40,-40,40,40,-.025,-1);
 for(let i=-40;i<=40;i+=8){quad(i-.025,-40,i+.025,40,-.02,-2);quad(-40,i-.025,40,i+.025,-.02,-2);}
 return out;
}
const groundMesh=groundVertices();
function paint(){
 const id=$('subject').value,entry=MODELS[id],model=entry.model,action=$('action').value;
 const expression=model.house&&action==='puff'?'angry':$('expression').value;
 const texture=textures[id+'/'+expression];if(!texture)return;
 const verts=posed(model,action,time),span=framing[id].span*zoom,centerY=framing[id].center;
 if($('ground').checked)verts.push(...groundMesh);
 ctx.putImageData(new ImageData(renderModel(verts,texture,canvas.width,canvas.height,az,el,span,{centerY,textureSize:model.texture_size||128,colors:[[217,218,201],[190,194,177]]}),canvas.width,canvas.height),0,0);
 const clip=model.clips[action];$('time').value=clip.loop?time%clip.duration:Math.min(time,clip.duration);
 $('status').textContent=`${Number($('time').value).toFixed(2)} / ${clip.duration.toFixed(2)} 秒`;
}
function selectModel(){
 const model=MODELS[$('subject').value];
 const old=$('action').value;$('action').replaceChildren(...Object.keys(model.model.clips).map(id=>new Option(labels[id]||id,id)));
 $('action').value=model.model.clips[old]?old:'idle';$('expression').replaceChildren(...Object.keys(model.textures).map(id=>new Option(id,id)));
 $('expression').value='neutral';time=0;zoom=1;setAction();
}
function setAction(){time=0;$('time').max=MODELS[$('subject').value].model.clips[$('action').value].duration;paint();}
$('subject').onchange=selectModel;$('action').onchange=setAction;$('expression').onchange=paint;
$('ground').onchange=paint;
$('play').onclick=()=>{playing=!playing;$('play').textContent=playing?'暂停':'播放';};
$('reset').onclick=()=>{$('action').value='idle';$('expression').value='neutral';setAction();};
$('time').oninput=()=>{playing=false;$('play').textContent='播放';time=+$('time').value;paint();};
for(const button of document.querySelectorAll('[data-angle]'))button.onclick=()=>{az=+button.dataset.angle;paint();};
let drag=null;canvas.onpointerdown=e=>{drag=[e.clientX,e.clientY];canvas.setPointerCapture(e.pointerId);};
canvas.onpointermove=e=>{if(!drag)return;az+=(e.clientX-drag[0])*.01;el=clamp(el+(e.clientY-drag[1])*.01,-.3,1.4);drag=[e.clientX,e.clientY];paint();};
canvas.onpointerup=canvas.onpointercancel=()=>drag=null;
canvas.onwheel=e=>{e.preventDefault();zoom=clamp(zoom*Math.exp(e.deltaY*.001),.4,3);paint();};
const loading=[];
for(const [id,entry]of Object.entries(MODELS))for(const [expr,url]of Object.entries(entry.textures))loading.push(new Promise((resolve,reject)=>{
 const im=new Image();im.onload=()=>{const c=document.createElement('canvas');c.width=im.width;c.height=im.height;const ctx=c.getContext('2d');ctx.drawImage(im,0,0);textures[id+'/'+expr]=ctx.getImageData(0,0,im.width,im.height).data;resolve();};im.onerror=reject;im.src=url;
}));
const initial=new URLSearchParams(location.search);
if(MODELS[initial.get('subject')])$('subject').value=initial.get('subject');
selectModel();
if(MODELS[$('subject').value].model.clips[initial.get('action')]){$('action').value=initial.get('action');setAction();}
Promise.all(loading).then(()=>{paint();requestAnimationFrame(frame);}).catch(()=>{$('status').textContent='贴图读取失败';});
function frame(now){if(playing)time+=Math.min(.1,(now-last)/1000);last=now;if(playing&&now-drawn>80){paint();drawn=now;}requestAnimationFrame(frame);}
