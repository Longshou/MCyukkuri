const $=id=>document.getElementById(id),canvas=$('view'),ctx=canvas.getContext('2d');
const labels={idle:'静止',waddle:'走路',hop:'跳跃',turn:'转身',flee:'逃跑',shell_sway:'螺壳摆动',shell_retract:'缩壳',fly:'飞行',predator_fly:'飞行'};
const textures={},state={id:'reimu',action:'waddle',time:0,playing:true,az:.55,el:.13,zoom:1};
let last=performance.now(),painted=0,ready=false;
function draw(){
 const e=ALL_MODELS[state.id];if(!textures[state.id])return;
 const pixels=renderModel(posed(e.model,state.action,state.time),textures[state.id],canvas.width,canvas.height,state.az,state.el,e.framing.span/state.zoom,{centerY:e.framing.center,textureSize:e.model.texture_size||128});
 ctx.putImageData(new ImageData(new Uint8ClampedArray(pixels),canvas.width,canvas.height),0,0);
 $('time').value=state.time;$('clock').textContent=state.time.toFixed(2)+' / '+e.model.clips[state.action].duration.toFixed(2)+'s';painted++;
}
function select(id){
 state.id=id;state.action=ALL_MODELS[id].action;state.time=0;state.zoom=1;
 const e=ALL_MODELS[id];$('character').value=id;
 $('action').replaceChildren(...Object.keys(e.model.clips).map(k=>new Option(labels[k]||k,k)));$('action').value=state.action;
 $('time').max=e.model.clips[state.action].duration;
 $('caption').textContent=e.label+' · '+e.group;
 $('views').href=id+'-views.png';$('motion').href=id+'-walk.gif';$('source').href=e.source;
 for(const card of $('cards').children)card.classList.toggle('active',card.dataset.id===id);
 draw();
}
for(const [id,e]of Object.entries(ALL_MODELS)){
 $('character').add(new Option(e.label,id));
 const card=document.createElement('button');card.className='card';card.dataset.id=id;
 const img=document.createElement('img');img.src=id+'.png';img.alt=e.label;img.loading='lazy';
 const strong=document.createElement('strong');strong.textContent=e.label;
 const small=document.createElement('small');small.textContent=e.group;card.append(img,strong,small);card.onclick=()=>{select(id);canvas.scrollIntoView({behavior:'smooth',block:'center'});};$('cards').append(card);
}
$('character').onchange=()=>select($('character').value);
$('action').onchange=()=>{state.action=$('action').value;state.time=0;$('time').max=ALL_MODELS[state.id].model.clips[state.action].duration;draw();};
$('play').onclick=()=>{state.playing=!state.playing;$('play').textContent=state.playing?'暂停':'播放';};
$('time').oninput=()=>{state.time=Number($('time').value);state.playing=false;$('play').textContent='播放';draw();};
for(const button of document.querySelectorAll('[data-angle]'))button.onclick=()=>{state.az=Number(button.dataset.angle);draw();};
$('reset').onclick=()=>{state.az=.55;state.el=.13;state.zoom=1;state.time=0;draw();};
let drag=null;
canvas.onpointerdown=e=>{drag={x:e.clientX,y:e.clientY};canvas.setPointerCapture(e.pointerId);};
canvas.onpointermove=e=>{if(!drag)return;state.az+=(e.clientX-drag.x)*.009;state.el=Math.max(-.3,Math.min(.8,state.el+(e.clientY-drag.y)*.004));drag={x:e.clientX,y:e.clientY};draw();};
canvas.onpointerup=()=>drag=null;canvas.onpointercancel=()=>drag=null;
canvas.onwheel=e=>{e.preventDefault();state.zoom=Math.max(.6,Math.min(2.5,state.zoom*Math.exp(-e.deltaY*.001)));draw();};
Promise.all(Object.entries(ALL_MODELS).map(([id,e])=>new Promise((resolve,reject)=>{const image=new Image();image.onload=()=>{const c=document.createElement('canvas');c.width=image.width;c.height=image.height;const x=c.getContext('2d');x.drawImage(image,0,0);textures[id]=x.getImageData(0,0,c.width,c.height).data;resolve();};image.onerror=()=>reject(new Error('Texture failed: '+id));image.src=e.texture;}))).then(()=>{ready=true;select(new URLSearchParams(location.search).get('character')||'reimu');});
function tick(now){
 const dt=Math.min(.12,(now-last)/1000);last=now;
 if(ready&&state.playing){const c=ALL_MODELS[state.id].model.clips[state.action];state.time=c.loop?(state.time+dt)%c.duration:Math.min(c.duration,state.time+dt);draw();}
 setTimeout(()=>requestAnimationFrame(tick),35);
}requestAnimationFrame(tick);
window.HAIR_GALLERY={get ready(){return ready;},state,get painted(){return painted;},ids:Object.keys(ALL_MODELS),select,
 set(s){if(s.id&&s.id!==state.id)select(s.id);Object.assign(state,s);draw();},model:id=>ALL_MODELS[id].model};
