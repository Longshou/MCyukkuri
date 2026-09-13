/* Gallery extension for independent wounds. Geometry and downloads share the
 * package's authoring module; no approximate duplicate is drawn by the UI. */
(function(){
'use strict';
const W=WoundSurface,input=BODY_EFFECTS.surface_input,frame={centerY:11,span:18};
let recipe=W.defaults(),active=0,pickMode=false,cacheKey='',cached=null,serial=1,playToken=0;
const copy=x=>JSON.parse(JSON.stringify(x));
function current(){return recipe.wounds[active];}
function nextId(){let id;do{id='w'+(++serial);}while(recipe.wounds.some(w=>w.id===id));return id;}
function scene(){const key=JSON.stringify(recipe);if(key!==cacheKey){cached=W.scene(input,recipe);cacheKey=key;}return cached;}
function update(){const list=$('w-active');if(list)Array.from(list.options).forEach((o,i)=>{const w=recipe.wounds[i];if(w)o.textContent=`${i+1}. ${W.kinds[w.kind]} · ${W.sides[w.side].label} · ${w.severity}级`;});paint();}
function orient(side){const angles={front:[0,.08],right:[Math.PI/2,.08],back:[Math.PI,.08],left:[-Math.PI/2,.08],top:[0,1.48],bottom:[0,-1.48]};[az,el]=angles[side];}
function field(parent,id,label,value,options,change){
 const row=document.createElement('label');row.style.cssText='display:block;font-size:12px;margin:10px 0';row.append(document.createTextNode(label+'：'));
 const control=document.createElement(options.choices?'select':'input');control.id=id;control.setAttribute('aria-label',label);control.style.cssText='display:block;width:100%;padding:7px;margin-top:5px';
 if(options.choices)for(const [key,text]of Object.entries(options.choices)){const o=document.createElement('option');o.value=key;o.textContent=text;control.append(o);}
 else{control.type='range';control.min=options.min;control.max=options.max;control.step=options.step||1;const output=document.createElement('strong');output.id=id+'-value';output.textContent=String(value);row.append(output);}
 control.value=value;control.oninput=()=>{playToken++;const v=options.choices?control.value:Number(control.value);const out=$(id+'-value');if(out)out.textContent=String(v);change(v);};row.append(control);parent.append(row);return control;
}
function button(parent,id,text,click){const b=document.createElement('button');b.className='small-button';b.id=id;b.textContent=text;b.onclick=click;parent.append(b);return b;}
function rebuild(){
 const root=$('controls');root.replaceChildren();
 const hint=document.createElement('p');hint.style.cssText='font-size:12px;line-height:1.7';hint.textContent='选择一处伤口编辑；新增后各处独立保留。顶部或背部可能被头发、帽子遮住，可切换皮面检查。';root.append(hint);
 field(root,'w-species','角色',recipe.species,{choices:{reimu:'灵梦',marisa:'魔理沙'}},v=>{recipe.species=v;update();});
 field(root,'w-cover','查看方式',recipe.showCover?'covered':'skin',{choices:{covered:'完整角色（保留遮挡）',skin:'仅看皮面（检查挂接）'}},v=>{recipe.showCover=v==='covered';update();});
 field(root,'w-scale','体型',recipe.scale,{min:.3,max:1.2,step:.05},v=>{recipe.scale=v;update();});
 const list=document.createElement('div');list.style.cssText='border-top:1px solid #ccc;padding-top:8px;margin-top:15px';root.append(list);
 const choices=Object.fromEntries(recipe.wounds.map((w,i)=>[i,`${i+1}. ${W.kinds[w.kind]} · ${W.sides[w.side].label} · ${w.severity}级`]));
 if(recipe.wounds.length)field(list,'w-active','当前伤口',active,{choices},v=>{active=+v;rebuild();update();});
 const buttons=document.createElement('div');buttons.style.cssText='display:flex;gap:6px;flex-wrap:wrap';list.append(buttons);
 button(buttons,'w-add','新增伤口',()=>{if(recipe.wounds.length>=8)return;recipe.wounds.push({...W.defaults().wounds[0],id:nextId(),side:'right',u:0,v:-.72});active=recipe.wounds.length-1;rebuild();update();});
 button(buttons,'w-remove','移除当前',()=>{recipe.wounds.splice(active,1);active=Math.max(0,active-1);rebuild();update();}).disabled=!recipe.wounds.length;
 button(buttons,'w-clear','清除全部',()=>{recipe.wounds=[];active=0;rebuild();update();});
 $('w-add').disabled=recipe.wounds.length>=8;
 const w=current();
 if(w){
  const pick=button(list,'w-pick',pickMode?'取消皮面定位':'点击皮面定位当前伤口',()=>{pickMode=!pickMode;rebuild();});pick.style.cssText='width:100%;margin:12px 0';
  field(list,'w-kind','伤情类型',w.kind,{choices:W.kinds},v=>{w.kind=v;if(v!=='tear')w.quantity=0;rebuild();update();});
  field(list,'w-side','身体部位',w.side,{choices:Object.fromEntries(Object.entries(W.sides).map(([k,v])=>[k,v.label]))},v=>{w.side=v;w.u=0;w.v=['top','bottom'].includes(v)?0:-.7;orient(v);rebuild();update();});
  field(list,'w-u','部位内横向位置',w.u,{min:-1,max:1,step:.02},v=>{w.u=v;update();});
  field(list,'w-v','部位内纵向位置',w.v,{min:-1,max:1,step:.02},v=>{w.v=v;update();});
  field(list,'w-severity','这处伤情程度',w.severity,{min:0,max:3},v=>{w.severity=v;update();});
  field(list,'w-size','这处伤口范围',w.size,{min:.4,max:1.4,step:.1},v=>{w.size=v;update();});
  if(w.kind==='tear'){
   field(list,'w-quantity','这处漏馅量',w.quantity||0,{min:0,max:12},v=>{w.quantity=v;update();});
   field(list,'w-mode','这处漏馅阶段',String(w.mode??1),{choices:{0:'渗出',1:'拉丝',2:'飞散',3:'落地'}},v=>{w.mode=+v;rebuild();update();});
   if(w.mode===2){field(list,'w-flight','飞散进度',recipe.flight,{min:0,max:1,step:.02},v=>{recipe.flight=v;update();});button(list,'w-play-flight','播放一次飞散',()=>play('flight'));}
  }
 }
 const pose=document.createElement('details');pose.style.cssText='border-top:1px solid #ccc;padding-top:12px;margin-top:18px';const summary=document.createElement('summary');summary.textContent='检查变形与动作';pose.append(summary);root.append(pose);
 field(pose,'w-deform','身体状态',recipe.deform,{choices:{none:'正常',compression:'受压',desiccation:'干瘪'}},v=>{recipe.deform=v;update();});
 field(pose,'w-amount','身体变化程度',recipe.amount,{min:0,max:3},v=>{recipe.amount=v;update();});
 field(pose,'w-action','动作',recipe.action,{choices:{none:'静止',side_bend:'侧弯',stretch_shape:'伸展',waddle:'走动',press:'按压'}},v=>{recipe.action=v;recipe.phase=0;$('w-phase').value=0;$('w-phase-value').textContent='0';update();});
 field(pose,'w-phase','动作进度',recipe.phase,{min:0,max:1,step:.02},v=>{recipe.phase=v;update();});
 button(pose,'w-play-pose','播放一次动作',()=>play('phase'));
 button(root,'w-save-recipe','下载当前伤口配方',()=>download(JSON.stringify(recipe,null,2),'wounds-'+recipe.species+'.json'));
 const upload=document.createElement('input');upload.type='file';upload.accept='.json';upload.hidden=true;upload.id='w-load-recipe';root.append(upload);
 button(root,'w-import','导入伤口配方',()=>upload.click());upload.onchange=async()=>{try{setRecipe(JSON.parse(await upload.files[0].text()));}catch(e){$('metadata').textContent='配方无法读取：'+e.message;}};
}
function download(content,name){const url=URL.createObjectURL(new Blob([content],{type:'application/json'})),a=document.createElement('a');a.href=url;a.download=name;a.click();setTimeout(()=>URL.revokeObjectURL(url),10000);}
function play(key){const token=++playToken,start=performance.now();let last=0;function step(now){if(token!==playToken||!$('detail').open)return;if(now-last>90){recipe[key]=Math.min(1,(now-start)/1800);const id=key==='phase'?'w-phase':'w-flight';if($(id)){$(id).value=recipe[key];$(id+'-value').textContent=recipe[key].toFixed(2);}update();last=now;}if(recipe[key]<1)requestAnimationFrame(step);}recipe[key]=0;requestAnimationFrame(step);}
function setRecipe(value){
 if(!['reimu','marisa'].includes(value.species)||!Array.isArray(value.wounds)||value.wounds.length>8)throw Error('需要角色和不超过 8 处伤口');
 if(value.wounds.some(w=>!W.kinds[w.kind]||!W.sides[w.side]||!Number.isInteger(w.severity)||w.severity<0||w.severity>3||![w.u,w.v,w.size].every(Number.isFinite)))throw Error('伤口类型、位置或程度无效');
 if(value.wounds.some(w=>typeof w.id!=='string'||!w.id||Math.abs(w.u)>1||Math.abs(w.v)>1||w.size<.4||w.size>1.4||!Number.isInteger(w.quantity??0)||(w.quantity??0)<0||(w.quantity??0)>12||![0,1,2,3].includes(w.mode??1))||new Set(value.wounds.map(w=>w.id)).size!==value.wounds.length)throw Error('伤口记录的标识、范围或份量无效');
 if(!Number.isFinite(value.scale)||value.scale<.3||value.scale>1.2||!['none','compression','desiccation'].includes(value.deform)||!['none','side_bend','stretch_shape','waddle','press'].includes(value.action)||![value.amount,value.phase,value.flight].every(Number.isFinite)||value.amount<0||value.amount>3||value.phase<0||value.phase>1||value.flight<0||value.flight>1)throw Error('体型或姿态参数无效');
 recipe={...W.defaults(),...copy(value)};active=0;serial+=recipe.wounds.length;rebuild();update();
}
const originalDraw=draw,originalGeometry=geometry,originalOpen=openAsset,originalPaint=paint;
draw=function(canvas,a,yaw=.5,pitch=.42,z=1,params={}){if(!a.bodyWounds)return originalDraw(canvas,a,yaw,pitch,z,params);const main=canvas.id==='view',s=main?scene():a.model,t=textures.get('body:'+(main?recipe.species:'reimu'));if(!t)return;canvas.getContext('2d').putImageData(new ImageData(renderModel(s.vertices,t,canvas.width,canvas.height,yaw,pitch,frame.span*z,{centerY:frame.centerY,textureSize:512}),canvas.width,canvas.height),0,0);};
geometry=function(a,params={}){return a.bodyWounds?W.scene(input,{...recipe,...params}).vertices:originalGeometry(a,params);};
paint=function(){originalPaint();if(selected?.bodyWounds){const s=scene(),valid=s.anchors.length;const status=recipe.wounds.filter(w=>w.severity>0).length===valid?'':' 部分位置未落在皮面上，请调整偏移。';$('metadata').textContent=`${recipe.wounds.length} 处记录，${valid} 处有效伤情；零级恢复，头发与饰物仍会遮挡皮面。每处裂口独立记录漏馅量。`+status;$('download').textContent='下载当前组合与姿态模型';}};
openAsset=function(id){playToken++;pickMode=false;$('download').onclick=null;$('detail').classList.toggle('wound-editing',id==='body_wounds');if(id==='body_wounds'){recipe=W.defaults();active=0;}originalOpen(id);
 if(selected.bodyWounds){az=.4;el=.12;rebuild();$('download').onclick=e=>{e.preventDefault();download(JSON.stringify(W.bbmodel(scene(),input.textures[recipe.species])),'wounds-'+recipe.species+'.bbmodel');};paint();}
 else if(selected.bodyEffects){button($('controls'),'w-open-editor','调整伤口位置／多处共存',()=>{const effect=selected.bodyEffects.effect;openAsset('body_wounds');recipe=W.defaults(effect);if(effect==='leak')recipe.wounds[0].quantity=4;if(['compression','desiccation'].includes(effect))recipe.deform=effect;rebuild();paint();});}
};
const down=$('view').onpointerdown,move=$('view').onpointermove,up=$('view').onpointerup;
$('view').onpointerdown=e=>{if(!selected?.bodyWounds||!pickMode)return down(e);e.preventDefault();const canvas=$('view'),r=canvas.getBoundingClientRect(),x=(e.clientX-r.left)*canvas.width/r.width,y=(e.clientY-r.top)*canvas.height/r.height,s=Math.sin(az),c=Math.cos(az),se=Math.sin(el),ce=Math.cos(el),right=[c,0,s],upper=[-s*se,ce,c*se],eye=[s*ce,se,-c*ce],scale=canvas.height/(2*frame.span*zoom),point=[0,frame.centerY,0].map((v,k)=>v+right[k]*(x-canvas.width/2)/scale+upper[k]*(canvas.height/2-y)/scale),origin=point.map((v,k)=>v+eye[k]*80),hit=W.pick(scene(),origin,eye.map(v=>-v));if(hit&&current()){Object.assign(current(),{side:hit.side,u:hit.u,v:hit.v});pickMode=false;rebuild();update();}else $('metadata').textContent='没有点中皮面；可旋转模型或切换为仅看皮面。';};
$('view').onpointermove=e=>{if(!pickMode)move(e);};$('view').onpointerup=e=>{if(!pickMode)up(e);};
window.woundEditor={read:()=>copy(recipe),set:setRecipe,scene:()=>scene(),select:i=>{active=i;rebuild();},edit:patch=>{Object.assign(current(),patch);rebuild();update();},add:w=>{if(recipe.wounds.length>=8)throw Error('Eight wound limit');recipe.wounds.push({...W.defaults().wounds[0],...w,id:w.id||nextId()});active=recipe.wounds.length-1;rebuild();update();},frame};
window.propsPreview.open=openAsset;window.propsPreview.draw=draw;
})();
