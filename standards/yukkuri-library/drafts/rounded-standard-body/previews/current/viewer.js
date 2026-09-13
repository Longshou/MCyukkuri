/* Two synchronized views of the actual exported geometry and numeric rig. */
const $=id=>document.getElementById(id),canvas=$('view'),ctx=canvas.getContext('2d');
const state={mode:'pair',action:'waddle',expression:'neutral',time:0,playing:true,az:.6,el:.16,zoom:1,bodyOnly:false,focus:false};
const anatomy=new AnatomyController(MODELS.reimu.model.anatomy);
const decoded={};let ready=false,last=performance.now(),drawn=0,anatomyDirty=false;
function resetAnatomy(){anatomy.amount=0;anatomy.targetExtended=false;anatomy.selectedPart=anatomy.activePart='peni';$('anatomy-part').value='peni';anatomyDirty=true;}
function anatomyOverrides(model){
 const s=anatomy.state(),q=s.amount*s.amount*(3-2*s.amount);
 return Object.fromEntries(model.anatomy.parts.map(p=>{const weight=p.id===s.part?q:0;return [p.bone,{position:p.offset.map(v=>v*weight),...(p.rotation?{rotation:p.rotation.map(v=>v*weight)}:{})}];}));
}
function anatomyStatus(){if(state.action==='birth'){$('anatomy-status').textContent='胎生出口随产程变化';return;}const s=anatomy.state(),labels={retracted:'已收回',extending:'伸出中',extended:'已伸出',retracting:'收回中',switching:'切换 · 先收回'};$('anatomy-status').textContent=labels[s.phase]+' · '+Math.round(s.amount*100)+'%';}
function subjects(){return state.mode==='pair'?['reimu','marisa']:[state.mode+'_baseline',state.mode];}
function clip(){return state.action==='birth'?{duration:MODELS.reimu.model.birth.viviparous.preview_seconds.end,loop:false}:MODELS[subjects()[1]].model.clips[state.action];}
function visibleModel(model){
 if(!state.bodyOnly)return model;
 return {...model,parts:model.parts.filter(p=>p.name==='rounded_body_surface'||p.name==='rounded_rump_mark'||p.anatomy||/^(soft_head|upper_rear|left_cheek|right_cheek|rump_core|rump_round_end)_slice_/.test(p.name)||p.name.startsWith('face_'))};
}
function paint(){
 if(!ready)return;
 const ids=subjects(),width=canvas.width/2,height=canvas.height;
 const labels=state.mode==='pair'?['灵梦 · 圆润身体','魔理沙 · 圆润身体']:['原标准身体','捕食种身体移植'];
 $('labelLeft').textContent=labels[0];$('labelRight').textContent=labels[1];
 ctx.clearRect(0,0,canvas.width,canvas.height);
 let span=state.mode==='reimu'?9.2:12.8,center=state.mode==='reimu'?6.5:10.4;
 if(state.action==='stretch'||state.action==='side_bend'){span*=1.35;center+=3.5;}
 if(state.bodyOnly){span=8.9;center=5.4;if(state.action==='stretch'){span=12.5;center=8;}}
 if(state.focus){span=7.1;center=3.5;}
 if(state.action==='birth'){span=state.bodyOnly?12:16;center=state.bodyOnly?5:10;}
 ids.forEach((id,i)=>{
  const e=MODELS[id],model=visibleModel(e.model),tex=decoded[id+'/'+state.expression]||decoded[id+'/neutral'];
  if(state.action==='birth'){
   const textures=Object.fromEntries(['neutral','angry','sleep'].map(k=>[k,decoded[id+'/'+k]]));
   const {pixels,scene}=RoundedBirth.render(e.model,textures,state.time,+$('birth-count').value,width,height,state.az,state.el,span*state.zoom,{centerY:center},state.bodyOnly);
   ctx.putImageData(new ImageData(pixels,width,height),i*width,0);
   $('birth-status').textContent=scene.state.phase+' · 已出生 '+scene.state.born+' / '+scene.state.count;
   return;
  }
  const points=posed(model,state.action,state.time,anatomyOverrides(e.model));
  const pixels=renderModel(points,tex,width,height,state.az,state.el,span*state.zoom,{centerY:center,textureSize:e.model.texture_size||128});
  ctx.putImageData(new ImageData(pixels,width,height),i*width,0);
 });
 const c=clip(),t=state.playing?state.time%c.duration:Math.min(state.time,c.duration);
 $('time').max=c.duration;$('time').value=t;$('status').textContent=`${t.toFixed(2)} / ${c.duration.toFixed(2)} 秒`;
 anatomyStatus();
}
function updateUI(){
 $('mode').value=state.mode;$('expression').value=state.expression;$('bodyOnly').checked=state.bodyOnly;
 $('play').textContent=state.playing?'暂停':'播放';
 const birth=state.action==='birth';
 for(const id of ['mode','expression','anatomy-part','anatomy-extend','anatomy-retract'])$(id).disabled=birth;
 $('birth-count').disabled=!birth;
 if(!birth)$('birth-status').textContent='胎生时使用同一前下方出口，并锁定伸缩部件';
 document.querySelectorAll('[data-action]').forEach(b=>b.classList.toggle('active',b.dataset.action===state.action));
 paint();
}
function setState(patch){
 if(patch.action&&patch.action!==state.action&&(patch.action==='birth'||state.action==='birth'))resetAnatomy();
 if(patch.action==='birth'){patch.mode='pair';patch.focus=false;anatomy.activePart=anatomy.selectedPart='mamu';$('anatomy-part').value='mamu';}
 Object.assign(state,patch);updateUI();
}
$('mode').onchange=()=>{resetAnatomy();setState({mode:$('mode').value,time:0,zoom:1,focus:false});};
$('expression').onchange=()=>setState({expression:$('expression').value});
$('bodyOnly').onchange=()=>setState({bodyOnly:$('bodyOnly').checked});
$('play').onclick=()=>setState({playing:!state.playing,...(state.time>=clip().duration?{time:0}:{})});
$('time').oninput=()=>setState({time:+$('time').value,playing:false});
$('birth-count').onchange=()=>setState({time:0,playing:true});
$('reset').onclick=()=>{resetAnatomy();setState({action:'idle',expression:'neutral',time:0,playing:false,az:.6,el:.16,zoom:1,bodyOnly:false,focus:false});};
document.querySelectorAll('[data-action]').forEach(b=>b.onclick=()=>setState({action:b.dataset.action,time:0,playing:true}));
document.querySelectorAll('[data-angle]').forEach(b=>b.onclick=()=>setState({az:+b.dataset.angle,focus:false}));
$('anatomy-part').onchange=()=>{anatomy.selectPart($('anatomy-part').value);anatomyDirty=true;anatomyStatus();};
$('anatomy-extend').onclick=()=>{anatomy.extend($('anatomy-part').value);anatomyDirty=true;anatomyStatus();};
$('anatomy-retract').onclick=()=>{anatomy.retract();anatomyDirty=true;anatomyStatus();};
$('anatomy-front').onclick=()=>setState({az:0,el:.035,focus:true,zoom:1});
$('anatomy-rear').onclick=()=>setState({az:Math.PI,el:.035,focus:true,zoom:1});
let drag=null;canvas.onpointerdown=e=>{drag=[e.clientX,e.clientY];canvas.setPointerCapture(e.pointerId);};
canvas.onpointermove=e=>{if(!drag)return;state.az+=(e.clientX-drag[0])*.01;state.el=clamp(state.el+(e.clientY-drag[1])*.008,-.3,1.3);drag=[e.clientX,e.clientY];paint();};
canvas.onpointerup=canvas.onpointercancel=()=>drag=null;
canvas.onwheel=e=>{e.preventDefault();state.zoom=clamp(state.zoom*Math.exp(e.deltaY*.001),.55,2);paint();};
const loading=[];
for(const [id,e]of Object.entries(MODELS))for(const [expr,url]of Object.entries(e.textures))loading.push(new Promise((resolve,reject)=>{
 const im=new Image();im.onload=()=>{const c=document.createElement('canvas');c.width=im.width;c.height=im.height;const x=c.getContext('2d');x.drawImage(im,0,0);decoded[id+'/'+expr]=x.getImageData(0,0,im.width,im.height).data;resolve();};im.onerror=()=>reject(new Error(id+'/'+expr));im.src=url;
}));
window.standardAnatomy={extend(id){if(state.action==='birth')return;anatomy.extend(id);$('anatomy-part').value=id;anatomyDirty=true;anatomyStatus();},retract(){if(state.action==='birth')return;anatomy.retract();anatomyDirty=true;anatomyStatus();},selectPart(id){if(state.action==='birth')return;anatomy.selectPart(id);$('anatomy-part').value=id;anatomyDirty=true;},state:()=>anatomy.state()};
window.PREVIEW_API={state,setState,paint,subjects,ready:false,anatomy:window.standardAnatomy,advanceAnatomy(dt){anatomy.update(dt);paint();return anatomy.state();}};
Promise.all(loading).then(()=>{ready=window.PREVIEW_API.ready=true;updateUI();requestAnimationFrame(frame);}).catch(e=>{$('status').textContent='贴图读取失败：'+e.message;});
function frame(now){const dt=Math.min(.25,(now-last)/1000);let ended=false;if(state.playing){const c=clip();state.time=c.loop===false?Math.min(state.time+dt,c.duration):(state.time+dt)%c.duration;if(c.loop===false&&state.time===c.duration){state.playing=false;ended=true;$('play').textContent='播放';}}anatomyDirty=anatomy.update(dt)||anatomyDirty;last=now;if((state.playing||anatomyDirty||ended)&&now-drawn>80){paint();drawn=now;anatomyDirty=false;}requestAnimationFrame(frame);}
