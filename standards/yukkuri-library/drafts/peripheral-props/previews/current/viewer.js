/* No CDN, no service, no network dependency; supports direct file opening. */
const $=id=>document.getElementById(id), textures=new Map();
const groups={block:['方块类','油粮颗粒三档同形 · 剪短散草、横放麦穗与鱼骨厨余'],item:['物品类','独立便便、袋装油粮与照料用品 · 遗体待做'],attachment:['附属物','竖长家油卵与短茎组合 · 不作为独立方块或可拾取物品'],body:['伤情与效果','在真实角色上切换程度、体型与漏馅过程 · 静态美术和效果候选']};
const stateGroups=[['food_bowl','food_bowl_regular','food_bowl_budget','food_bowl_premium','food_bowl_forage','food_bowl_wheat','food_bowl_garbage'],['oil_grain_regular','oil_grain_budget','oil_grain_premium'],['yukkuri_toilet','yukkuri_toilet_dirty'],['house_egg','house_egg_cracked'],['birth_family_reimu','birth_family_marisa']];
let filter='all',selected=null,az=.5,el=.42,zoom=1,drag=null,ready=false,parameters={},bodyPlayback=0;
for(const tab of document.querySelectorAll('[data-filter]'))tab.querySelector('span').textContent=tab.dataset.filter==='all'?ASSETS.length:ASSETS.filter(a=>a.category===tab.dataset.filter).length;
$('stamp').innerHTML='素材预览 R3.1<br>'+ASSETS.length+' 个条目';
function framing(a){return {centerY:a.dims[1]/2,span:Math.max(a.dims[1],Math.hypot(a.dims[0],a.dims[2])*.87)*.67};}
function triangle(out,a,b,c,uv){const u=b.map((v,i)=>v-a[i]),v=c.map((w,i)=>w-a[i]);let n=[u[1]*v[2]-u[2]*v[1],u[2]*v[0]-u[0]*v[2],u[0]*v[1]-u[1]*v[0]],length=Math.hypot(...n);if(length<1e-9)return;n=n.map(x=>x/length);for(const p of [a,b,c])out.push(...p,...uv,...n);}
function tube(out,path,r,uv,sides=8){
 let last=null;const rings=[];
 for(let i=0;i<path.length;i++){
  const a=path[Math.max(0,i-1)],b=path[Math.min(path.length-1,i+1)];let t=b.map((v,k)=>v-a[k]),l=Math.hypot(...t);t=t.map(v=>v/l);let u;
  if(last){const d=last.reduce((s,v,k)=>s+v*t[k],0);u=last.map((v,k)=>v-d*t[k]);}
  else{const ref=Math.abs(t[1])<.9?[0,1,0]:[1,0,0];u=[t[1]*ref[2]-t[2]*ref[1],t[2]*ref[0]-t[0]*ref[2],t[0]*ref[1]-t[1]*ref[0]];}
  l=Math.hypot(...u);u=u.map(v=>v/l);last=u;const v=[t[1]*u[2]-t[2]*u[1],t[2]*u[0]-t[0]*u[2],t[0]*u[1]-t[1]*u[0]];
  rings.push(Array.from({length:sides},(_,j)=>path[i].map((p,k)=>p+r*(u[k]*Math.cos(j*Math.PI*2/sides)+v[k]*Math.sin(j*Math.PI*2/sides)))));
 }
 for(let i=0;i<rings.length-1;i++)for(let j=0;j<sides;j++){const a=rings[i][j],b=rings[i][(j+1)%sides],c=rings[i+1][(j+1)%sides],d=rings[i+1][j];triangle(out,a,b,c,uv);triangle(out,a,c,d,uv);}
}
const familyCache=new Map();
function familyGeometry(a,count){
 const key=a.id+'/'+count;if(familyCache.has(key))return familyCache.get(key);
 const f=a.family,layout=f.layouts[String(count)],out=[];
 function actor(offset,scale,child){const src=f.base_vertices;for(let i=0;i<src.length;i+=8)out.push(src[i]*scale+offset[0],src[i+1]*scale+offset[1],src[i+2]*scale+offset[2],src[i+3]*.5+(child ? .5 : 0),src[i+4]*.5,src[i+5],src[i+6],src[i+7]);}
 actor([0,0,0],1,false);for(const c of layout.children)actor(c.offset,c.scale,true);
 tube(out,layout.arc,layout.radius,f.stem_uv);for(const c of layout.children)tube(out,[c.socket,c.anchor],.07,f.stem_uv,6);
 familyCache.set(key,out);return out;
}
function bodyState(a,params={}){
 const species=['reimu','marisa'][params.species??0],effect=a.bodyEffects.effect;
 const state=effect==='leak'?(params.mode??1):(params.severity??2);
 return {species,effect,state,...BODY_EFFECTS.states[effect][species][String(state)]};
}
function bodyGeometry(a,params={}){
 const state=bodyState(a,params),src=state.model.vertices,out=[];
 const quantity=params.quantity??4,time=params.time??.5,scale=params.ownerScale??1;
 for(const part of state.model.parts){
  if(part.unit>0&&part.unit>quantity)continue;
  let center=null,squash=1,spread=1;
  if(part.motion){
   const m=part.motion,age=m.duration*time;
   center=[m.origin[0]+m.velocity[0]*age,m.origin[1]+m.velocity[1]*age-m.gravity*age*age/2,m.origin[2]+m.velocity[2]*age];
   if(time>=.98){center[1]=.09;squash=.28;spread=1.35;}
  }
  for(let i=part.start*8;i<(part.start+part.count)*8;i+=8){
   const v=src.slice(i,i+8);
   if(center){for(let k=0;k<3;k++)v[k]=center[k]+(v[k]-part.motion.center[k])*(k===1?squash:spread);const n=[v[5]/spread,v[6]/squash,v[7]/spread],l=Math.hypot(...n)||1;for(let k=0;k<3;k++)v[5+k]=n[k]/l;}
   for(let k=0;k<3;k++)v[k]*=scale;
   out.push(...v);
  }
 }
 return out;
}
function geometry(a,params={}){
 if(a.bodyEffects)return bodyGeometry(a,params);
 if(a.family)return familyGeometry(a,params.broodCount??3);
 const quantity=params.quantity??a.controls?.quantity?.value;let out=a.model.vertices;
 if(quantity!==undefined){out=[];for(const p of a.model.parts)if(p.unit===0||p.unit<=quantity){const end=(p.start+p.count)*8;for(let i=p.start*8;i<end;i++)out.push(a.model.vertices[i]);}}
 const scale=params.ownerScale??1;if(scale!==1){out=out.slice();for(let i=0;i<out.length;i+=8)for(let k=0;k<3;k++)out[i+k]*=scale;}
 return out;
}
function draw(canvas,a,yaw=.5,pitch=.42,z=1,params={}){
 const state=a.bodyEffects?bodyState(a,params):null,t=textures.get(state?'body:'+state.species:a.id);if(!t)return;
 let f=framing(a),verts=geometry(a,params);
 if(state)f=BODY_EFFECTS.frame;
 if(a.family){
  const ref=familyGeometry(a,5),lo=[Infinity,Infinity,Infinity],hi=[-Infinity,-Infinity,-Infinity];
  for(let i=0;i<ref.length;i+=8)for(let k=0;k<3;k++){lo[k]=Math.min(lo[k],ref[i+k]);hi[k]=Math.max(hi[k],ref[i+k]);}
  const cx=(lo[0]+hi[0])/2,cz=(lo[2]+hi[2])/2;verts=verts.slice();for(let i=0;i<verts.length;i+=8){verts[i]-=cx;verts[i+2]-=cz;}
  f={centerY:(lo[1]+hi[1])/2,span:Math.max(hi[1]-lo[1],Math.hypot(hi[0]-lo[0],hi[2]-lo[2])*.8)*.66};
 }
 canvas.getContext('2d').putImageData(new ImageData(renderModel(verts,t,canvas.width,canvas.height,yaw,pitch,f.span*z,{centerY:f.centerY,textureSize:a.model.texture_size}),canvas.width,canvas.height),0,0);
}
function gallery(){
 const query=$('search').value.trim().toLowerCase(),root=$('gallery');root.replaceChildren();let count=0;
 for(const [category,[label,note]] of Object.entries(groups)){
  if(filter!=='all'&&filter!==category)continue;
  const assets=ASSETS.filter(a=>a.category===category&&(!query||(a.label+' '+a.id).toLowerCase().includes(query)));if(!assets.length)continue;
  const section=document.createElement('section');section.dataset.category=category;
  const heading=document.createElement('div');heading.className='section-heading';
  const h=document.createElement('h2');h.textContent=label;const p=document.createElement('p');p.textContent=note;heading.append(h,p);
  const grid=document.createElement('div');grid.className='grid';
  for(const a of assets){
   const b=document.createElement('button');b.className='card';b.dataset.id=a.id;b.setAttribute('aria-label','查看'+a.label);
   const im=document.createElement('div');im.className='image-wrap';const c=document.createElement('canvas');c.width=380;c.height=260;
   const num=document.createElement('span');num.className='num';num.textContent=String(ASSETS.indexOf(a)+1).padStart(2,'0');
   const tag=document.createElement('span');tag.className='tag';tag.textContent=(a.confirmation?.status==='user_approved'?'已确认 · ':'')+(a.state==='常态'?'3D':a.state);
   const cap=document.createElement('div');cap.className='caption';const name=document.createElement('h3');name.textContent=a.label;const code=document.createElement('small');code.textContent=a.id;
   cap.append(name,code);im.append(c,num,tag);b.append(im,cap);b.onclick=()=>openAsset(a.id);grid.append(b);draw(c,a,a.family ? 1.15 : a.bodyEffects?.effect==='dent' ? .7 : a.bodyEffects?.effect==='leak' ? .35 : .5,a.family||a.bodyEffects ? .12 : .42);count++;
  }
  section.append(heading,grid);
  if(category==='item'&&(!query||'油库里遗体 待做'.includes(query))){const pending=document.createElement('p');pending.className='state-note';pending.textContent='油库里遗体：待做。后续按死因分别制作，本轮不再用侧卧模型代表所有遗体。';section.append(pending);}
  root.append(section);
 }
 if(!count){const e=document.createElement('p');e.className='empty';e.textContent='没有匹配的素材。';root.append(e);}
}
function paint(){if(selected){draw($('view'),selected,az,el,zoom,parameters);if(selected.controls?.ownerScale)$('metadata').textContent=`当前尺寸 ${selected.dims.map(n=>(n*(parameters.ownerScale??1)).toFixed(2)).join(' × ')} 单位（宽 × 高 × 深）。体型采用对象实际缩放值。`;
 if(selected.bodyEffects){const state=bodyState(selected,parameters);$('download').href=state.bbmodel;$('download').textContent=state.effect==='leak'?'下载当前阶段场景（12 份分组）':'下载当前角色与程度模型';$('metadata').textContent=`${state.species==='reimu'?'灵梦':'魔理沙'} · 体型 ${parameters.ownerScale??1} 倍。角色和效果共同缩放，取 0 级可检查恢复状态。`+(state.effect==='leak'?'下载含 12 份可隐藏分组；飞散模型为中间帧。':'');}
 for(const [key,c] of Object.entries(selected.controls||{})){const wrap=document.querySelector(`[data-control="${key}"]`);if(wrap)wrap.hidden=!!c.when&&Object.entries(c.when).some(([k,v])=>parameters[k]!==v);}
 const play=$('body-play');if(play)play.hidden=parameters.mode!==2;
}}
function openAsset(id){
 bodyPlayback++;selected=ASSETS.find(a=>a.id===id);az=selected.family ? 1.15 : selected.bodyEffects ? .35 : .5;el=selected.family||selected.bodyEffects ? .12 : .42;zoom=1;parameters={};
 $('detail-title').textContent=selected.label;$('detail-type').textContent=groups[selected.category][0];$('detail-note').textContent=(selected.confirmation?.status==='user_approved'?'已确认标准造型（'+selected.confirmation.approved_on+'）。':'')+selected.note;
 $('metadata').textContent=`${selected.family?'默认三胎组合尺寸':'基准尺寸'} ${selected.dims.map(n=>n.toFixed(2)).join(' × ')} 单位（宽 × 高 × 深） · ${selected.triangles.toLocaleString()} 三角面`;
 $('download').href=selected.bbmodel;
 $('download').textContent=selected.family?'下载可编辑默认三胎组合':'下载可编辑 Blockbench 模型';
 $('controls').replaceChildren();
 for(const [key,c] of Object.entries(selected.controls||{})){
  parameters[key]=c.value;const wrap=document.createElement('label');wrap.dataset.control=key;wrap.style.cssText='display:block;margin:15px 0;font-size:12px';const label=document.createElement('span'),value=document.createElement('strong');
  const textValue=v=>c.choices?c.choices[v]:c.labels?c.labels[v]:v+(c.unit??' 倍');
  label.textContent=c.label+'：';value.id='value-'+key;value.textContent=textValue(c.value);const input=document.createElement(c.choices?'select':'input');
  if(c.choices)c.choices.forEach((name,i)=>{const o=document.createElement('option');o.value=i;o.textContent=name;input.append(o);});
  else{input.type='range';input.min=c.min;input.max=c.max;input.step=c.step;}
  input.id='control-'+key;input.value=c.value;input.style.cssText='display:block;width:100%;padding:8px 0';input.setAttribute('aria-label',c.label);
  input.oninput=()=>{bodyPlayback++;parameters[key]=+input.value;value.textContent=textValue(+input.value);paint();};wrap.append(label,value,input);$('controls').append(wrap);
 }
 if(selected.bodyEffects?.effect==='leak'){
  const play=document.createElement('button');play.id='body-play';play.className='small-button';play.textContent='播放一次飞散';
  play.onclick=()=>{const token=++bodyPlayback,start=performance.now();let drawn=0;function step(now){if(token!==bodyPlayback||!$('detail').open)return;if(now-drawn>75){parameters.time=Math.min(1,(now-start)/1500);$('control-time').value=parameters.time;$('value-time').textContent=parameters.time.toFixed(2);paint();drawn=now;}if(parameters.time<1)requestAnimationFrame(step);}parameters.time=0;requestAnimationFrame(step);};$('controls').append(play);
 }
 $('states').replaceChildren();const family=stateGroups.find(g=>g.includes(id));
 if(family)for(const k of family){const a=ASSETS.find(x=>x.id===k),b=document.createElement('button');b.className='small-button'+(k===id?' active':'');b.textContent=a.state==='常态'?a.label:a.state;b.onclick=()=>openAsset(k);$('states').append(b);}
 if(!$('detail').open)$('detail').showModal();paint();
}
for(const b of document.querySelectorAll('[data-filter]'))b.onclick=()=>{filter=b.dataset.filter;for(const tab of document.querySelectorAll('[data-filter]'))tab.setAttribute('aria-selected',String(tab===b));gallery();};
$('search').oninput=gallery;
document.querySelector('.close').onclick=()=>$('detail').close();
$('detail').onclick=e=>{if(e.target===$('detail')){const r=$('detail').getBoundingClientRect();if(e.clientX<r.left||e.clientX>r.right||e.clientY<r.top||e.clientY>r.bottom)$('detail').close();}};
for(const b of document.querySelectorAll('[data-view]'))b.onclick=()=>{const angles={front:[0,.06],oblique:selected?.family?[1.1,.18]:selected?.bodyEffects?[.45,.12]:[.5,.42],side:[Math.PI/2,.12],back:[Math.PI,.18],top:[0,1.45],bottom:[0,-1.45]};[az,el]=angles[b.dataset.view];paint();};
$('reset').onclick=()=>{az=selected?.family ? 1.15 : selected?.bodyEffects ? .35 : .5;el=(selected?.family||selected?.bodyEffects) ? .12 : .42;zoom=1;paint();};
$('view').onpointerdown=e=>{drag=[e.clientX,e.clientY];$('view').setPointerCapture(e.pointerId);};
$('view').onpointermove=e=>{if(!drag)return;az+=(e.clientX-drag[0])*.01;el=Math.max(-1.4,Math.min(1.5,el+(e.clientY-drag[1])*.01));drag=[e.clientX,e.clientY];paint();};
$('view').onpointerup=$('view').onpointercancel=()=>drag=null;
$('view').onwheel=e=>{e.preventDefault();zoom=Math.max(.4,Math.min(2.5,zoom*Math.exp(e.deltaY*.001)));paint();};
const textureSources=[...ASSETS.map(a=>({id:a.id,texture:a.texture})),...Object.entries(BODY_EFFECTS.textures).map(([id,texture])=>({id:'body:'+id,texture}))];
Promise.all(textureSources.map(a=>new Promise((resolve,reject)=>{const im=new Image();im.onload=()=>{const c=document.createElement('canvas');c.width=im.width;c.height=im.height;const ctx=c.getContext('2d');ctx.drawImage(im,0,0);textures.set(a.id,ctx.getImageData(0,0,im.width,im.height).data);resolve();};im.onerror=reject;im.src=a.texture;}))).then(()=>{ready=true;gallery();}).catch(e=>{$('gallery').textContent='贴图读取失败：'+e;});
window.propsPreview={ready:()=>ready,open:openAsset,assets:()=>ASSETS.map(({id,category})=>({id,category})),state:()=>({selected:selected?.id,az,el,zoom,filter,parameters:{...parameters}}),draw,geometry:(id,p)=>geometry(ASSETS.find(a=>a.id===id),p),setParameter:(key,value)=>{parameters[key]=value;const c=$('control-'+key);if(c){c.value=value;c.dispatchEvent(new Event('input'));}else paint();}};
