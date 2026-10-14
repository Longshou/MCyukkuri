'use strict';
const $=id=>document.getElementById(id),catalog=window.REVIEW_CATALOG;
const names={reimu:'灵梦',marisa:'魔理沙',chen:'橙',alice:'爱丽丝',patchouli:'帕秋莉'},ages={adult:'成体',young:'幼语',song:'哼唱'};
const labels={idle:'待机',eat:'进食',shiawase:'しあわせー',drink:'饮水',sleep:'睡眠',wake:'醒来',nuzzle:'擦擦脸',sing:'唱歌',sway:'跟着摇摆',push:'推挤',greet:'问候',tremble:'发抖',droop:'低落',look_around:'张望',shake:'甩水',boat_balance:'帽船平衡',still:'静止',shell_sway:'壳体摇摆',shell_retract:'缩壳',fur_sway:'多毛摇摆',stretch_shape:'伸展造型',side_bend:'侧弯',waddle:'走动',puff:'鼓腮',brace:'撑地',startle:'受惊',turn:'转身',hop:'跳动',stretch:'伸展',exercise_vertical:'纵向体操',exercise_sides:'横向体操',press:'按压',taiso:'完整体操',flee:'逃跑',house_pickup:'俯身夹取',house_carry:'短辫搬运',house_deposit:'俯身放下',house_crawl:'贴地蠕动',predator_fly:'飞行',predator_dive:'俯冲',predator_land:'降落',predator_bite:'捕咬',anatomy_mamu_extend:'麻姆麻姆伸出',anatomy_mamu_retract:'麻姆麻姆收回',anatomy_peni_extend:'佩尼佩尼伸出',anatomy_peni_retract:'佩尼佩尼收回'};
const expressionNames={neutral:'常态',happy:'开心',angry:'生气',sad:'难过',cry:'哭泣',sleep:'睡着',sleepy:'困倦',surprise:'惊讶',scared:'害怕',chewing:'咀嚼张嘴',chewing_closed:'咀嚼闭嘴',closed_smile:'闭嘴微笑',open_smile:'张嘴微笑',confused:'困惑',puff:'鼓腮',satisfied:'满足',smug:'得意',drool:'流口水',teary:'含泪',yawn:'打哈欠'};
const eventNames={hello:'问候',newborn:'出生',hatched:'孵化',introduced:'介绍',peer:'同伴',known_peer:'熟悉的同伴',idle:'待机',wounded:'受伤',death:'死亡',hungry:'饥饿',eat:'进食',eating:'进食',full:'吃饱',sleep:'睡眠',sleeping:'睡眠',wake:'醒来',happy:'开心',angry:'生气',fear:'恐惧',pain:'疼痛',dirty:'脏污',clean:'清洁',rain:'下雨',wet:'潮湿',dry:'干燥',thirst:'口渴',thirsty:'口渴',drink:'饮水',learning:'学习',learn:'学习',learned:'学会',praise:'夸奖',song:'哼唱',nuzzle:'亲昵',want_nuzzle:'想要亲昵',nuzzle_done:'亲昵结束',exercise:'体操',leisure_done:'休闲结束',house_pickup:'家油夹取',house_carry:'家油搬运',house_deposit:'家油放下',house_arrival:'家油到达',accessory_lost:'丢失饰物',accessory_returned:'取回饰物'};
Object.assign(eventNames,{afraid:'害怕',awake:'醒来',birth:'分娩',busy:'忙碌',caregiver:'照料者',chew:'咀嚼',cold:'寒冷',courtship:'求偶',expecting:'孕育中',family_died:'亲属死亡',fed:'被喂食',food_found:'发现食物',gentle:'温柔接触',grown:'成长',heard:'听见呼唤',house_peek:'家油探看',laid_eggs:'产卵',lesson_learned:'学会课程',lesson_progress:'学习进展',lesson_refused:'拒绝教学',lesson_start:'开始学习',lost_accessory:'丢失饰物',lost_parent:'与亲代走散',mine:'宣示所有',need_toilet:'想上厕所',puff:'鼓腮',returned_accessory:'取回饰物',share:'分享',shy:'害羞',take_food:'取走食物',toilet_done:'如厕完成',toy:'玩具',treated:'治疗',unrecognized:'未能认出',wait_family:'等待家人',wait_learned:'学会等待',wait_turn:'等待轮次',washed:'清洗'});
const label=(id,map)=>map[id]||id;
const storageKey='mcyukkuri-material-review-v1';let notes={};
try{notes=JSON.parse(localStorage.getItem(storageKey)||'{}');}catch{notes={};}
function toast(message){$('toast').textContent=message;$('toast').style.display='block';clearTimeout(toast.timer);toast.timer=setTimeout(()=>$('toast').style.display='none',3500);}
function persist(){try{localStorage.setItem(storageKey,JSON.stringify(notes));$('saved').textContent='已自动保存到当前浏览器';}catch{$('saved').textContent='浏览器存储不可用，请及时导出意见';}updateCount();}
function updateCount(){$('note-count').textContent=Object.values(notes).filter(n=>n.status!=='unreviewed'||n.text||n.view).length;}
updateCount();
let selected=catalog.models[0],model=null,pose=null,action='idle',expression='auto',time=0,playing=false,az=.45,el=.15,zoom=1,span=18,fitRadius=0,centerY=9,loadToken=0,dirty=true;
let motionPromise,fitPromise,appliedExpression=null,requestedExpression=null;
expressionNames.auto='随动作自动切换';
expressionNames.cheek_puff='鼓腮噗咕（睁眼噘嘴）';
let renderer,scene,camera,mesh,grid,material,positions,uvs,colors,geometry;
const textureCache=new Map();let expressionToken=0;
function initRenderer(){
 renderer=new THREE.WebGLRenderer({canvas:$('view'),antialias:true,alpha:true});renderer.setPixelRatio(Math.min(devicePixelRatio||1,2));
 scene=new THREE.Scene();camera=new THREE.OrthographicCamera(-18,18,18,-18,.1,1000);
 grid=new THREE.GridHelper(96,12,0xa3b49c,0xd5dece);grid.position.y=-.05;scene.add(grid);
 material=new THREE.MeshBasicMaterial({side:THREE.DoubleSide,vertexColors:true,alphaTest:.05});
 new ResizeObserver(resize).observe($('stage'));
}
function resize(){if(!renderer)return;const w=$('stage').clientWidth,h=$('stage').clientHeight;if(!w||!h)return;renderer.setSize(w,h,false);const fitted=Math.max(span,fitRadius*1.06*h/w)/zoom;camera.top=fitted;camera.bottom=-fitted;camera.left=-fitted*w/h;camera.right=-camera.left;camera.updateProjectionMatrix();dirty=true;}
function cameraPosition(){const distance=150;camera.position.set(-Math.sin(az)*Math.cos(el)*distance,centerY+Math.sin(el)*distance,-Math.cos(az)*Math.cos(el)*distance);camera.lookAt(0,centerY,0);}
function frameGeometry(){
 if(!pose)return;if(expression==='auto'&&pose.expressionFor(action,time)!==appliedExpression)applyExpression();const output=pose.frame(action,time);
 for(let i=0,j=0,k=0;i<output.length;i+=8,j+=3,k+=2){positions[j]=output[i];positions[j+1]=output[i+1];positions[j+2]=output[i+2];uvs[k]=output[i+3];uvs[k+1]=output[i+4];const shade=.78+.22*Math.max(0,output[i+5]*-.35+output[i+6]*.8+output[i+7]*-.48);colors[j]=colors[j+1]=colors[j+2]=shade;}
 geometry.attributes.position.needsUpdate=true;geometry.attributes.uv.needsUpdate=true;geometry.attributes.color.needsUpdate=true;
 geometry.computeBoundingSphere();$('time').value=time;$('clock').textContent=`${time.toFixed(2)} / ${pose.clips[action].duration.toFixed(2)} 秒`;
}
function setPlaying(value){playing=value;$('play').textContent=playing?'暂停':'播放';}
function buildModelList(){
 const query=$('model-search').value.trim().toLowerCase(),container=$('model-list');container.replaceChildren();let group='';
 for(const item of catalog.models){if(!(item.name+item.id+item.group).toLowerCase().includes(query))continue;
  if(item.group!==group){group=item.group;const heading=document.createElement('div');heading.className='group-label';heading.textContent=group;container.append(heading);}
  const button=document.createElement('button');button.className='model-button'+(selected.id===item.id?' active':'');button.dataset.model=item.id;
  const img=document.createElement('img');img.src=`thumbs/${item.id}.png`;img.alt='';img.loading='lazy';img.onerror=()=>img.style.visibility='hidden';
  const text=document.createElement('span');text.textContent=item.name;const sub=document.createElement('small');sub.textContent=`${item.actions.length} 段动作 · ${Object.keys(item.textures).length} 表情`;text.append(sub);button.append(img,text);button.onclick=()=>selectModel(item);container.append(button);
 }
}
async function getTexture(url){
 if(textureCache.has(url))return textureCache.get(url);
 const texture=await new THREE.TextureLoader().loadAsync(url);texture.flipY=false;texture.magFilter=THREE.NearestFilter;texture.minFilter=THREE.NearestFilter;texture.generateMipmaps=false;textureCache.set(url,texture);return texture;
}
async function applyExpression(){
 if(!pose)return;const effective=expression==='auto'?pose.expressionFor(action,time):expression;
 if(requestedExpression===effective)return appliedExpression===effective;
 requestedExpression=effective;const token=++expressionToken,currentId=selected.id;
 try{const texture=await getTexture(selected.textures[effective]);if(token!==expressionToken||selected.id!==currentId||!pose)return false;appliedExpression=effective;pose.expression(effective);material.map=texture;material.needsUpdate=true;frameGeometry();dirty=true;return true;}
 catch{if(token===expressionToken){requestedExpression=null;$('loading').textContent='表情贴图加载失败，请刷新后重试。';$('loading').style.display='grid';}return false;}
}
async function selectModel(item){
 const token=++loadToken;setPlaying(false);selected=item;model=null;pose=null;$('play').disabled=true;
 $('loading').style.display='grid';$('loading').textContent=`正在读取${item.name}…`;
 $('model-name').textContent=item.name;$('model-group').textContent=item.group;$('model-detail').textContent=`${item.actions.length} 段动作 · ${Object.keys(item.textures).length} 套表情 · 当前游戏资源`;
 buildModelList();
 try{
  const response=await fetch(item.url);if(!response.ok)throw new Error(`HTTP ${response.status}`);const data=await response.json();if(token!==loadToken)return;
  motionPromise??=fetch(catalog.motions).then(r=>{if(!r.ok)throw Error('动作资源 HTTP '+r.status);return r.json();});const motions=await motionPromise;if(token!==loadToken)return;
  fitPromise??=fetch(catalog.fit).then(r=>{if(!r.ok)throw Error('位置适配资源 HTTP '+r.status);return r.json();});const fits=await fitPromise;if(token!==loadToken)return;
  model=data;pose=new RuntimePose(model,item.formScale,motions.models[item.id],fits.models[item.id]||null);action=pose.clips[action]?action:'idle';expression=expression==='auto'||item.textures[expression]?expression:'auto';time=0;appliedExpression=requestedExpression=null;
  $('action').replaceChildren(...Object.keys(pose.clips).map(id=>new Option(label(id,labels),id)));$('action').value=action;
  $('expression').replaceChildren(...['auto',...Object.keys(item.textures)].map(id=>new Option(label(id,expressionNames),id)));$('expression').value=expression;
  model=pose.model;const n=model.vertices.length/8;positions=new Float32Array(n*3);uvs=new Float32Array(n*2);colors=new Float32Array(n*3);
  if(mesh){scene.remove(mesh);geometry.dispose();}geometry=new THREE.BufferGeometry();geometry.setAttribute('position',new THREE.BufferAttribute(positions,3));geometry.setAttribute('uv',new THREE.BufferAttribute(uvs,2));geometry.setAttribute('color',new THREE.BufferAttribute(colors,3));mesh=new THREE.Mesh(geometry,material);mesh.scale.x=-1;mesh.frustumCulled=false;scene.add(mesh);
  const output=pose.frame('idle',0);let min=Infinity,max=-Infinity,radius=0;
  for(let i=0;i<output.length;i+=8){min=Math.min(min,output[i+1]);max=Math.max(max,output[i+1]);radius=Math.max(radius,Math.hypot(output[i],output[i+2]));}
  const expanded=pose.frame('puff',pose.clips.puff.duration*.5);for(let i=0;i<expanded.length;i+=8)radius=Math.max(radius,Math.hypot(expanded[i],expanded[i+2]));fitRadius=radius;
  const jump=motions.models[item.id]?.hop_height*item.formScale||0;
  centerY=(min+max+jump*.5)/2;span=Math.max((max-min)*.64+jump*.5,radius*.9,4);az=.45;el=.15;zoom=1;resize();
  $('time').max=pose.clips[action].duration;buildActions();const textureReady=await applyExpression();if(token!==loadToken||!textureReady)return;
  $('loading').style.display='none';$('play').disabled=false;loadNote();frameGeometry();dirty=true;
 }catch(error){if(token!==loadToken)return;$('loading').textContent=`模型未能载入：${error.message}。请通过“打开素材检查.cmd”启动本地服务后访问。`;}
}
function buildActions(){
 const common=['idle','waddle','hop','startle','puff','taiso','side_bend','flee',...Object.keys(model.behavior_clips||{})];$('action-bank').replaceChildren();
 for(const id of common.filter(id=>pose.clips[id])){const b=document.createElement('button');b.textContent=label(id,labels);b.className=id===action?'active':'';b.onclick=()=>setAction(id);$('action-bank').append(b);}
}
function setAction(id){if(!pose)return;action=id;time=0;$('action').value=id;$('time').max=pose.clips[id].duration;buildActions();applyExpression();loadNote();}
function modelKey(){return `model/${selected.id}/${selected.hash}/${action}/${expression}`;}
function loadNote(){const note=notes[modelKey()]||{};$('note-context').textContent=`${selected.name} / ${label(action,labels)} / ${label(expression,expressionNames)}`;$('review-status').value=note.status||'unreviewed';$('review-note').value=note.text||'';$('saved').textContent=note.updated?'已保存此项记录':'自动保存在当前浏览器';}
function saveNote(view=false){const key=modelKey(),old=notes[key]||{};notes[key]={...old,type:'model',model:selected.id,name:selected.name,sourceHash:selected.hash,action,expression,status:$('review-status').value,text:$('review-note').value,updated:new Date().toISOString()};if(view)notes[key].view={seconds:+time.toFixed(3),azimuth:az,elevation:el,zoom};persist();}
$('review-status').onchange=()=>saveNote();$('review-note').oninput=()=>saveNote();$('capture-note').onclick=()=>{saveNote(true);toast('已记录当前动作时间和查看角度');};
$('model-search').oninput=buildModelList;$('action').onchange=e=>setAction(e.target.value);$('expression').onchange=e=>{expression=e.target.value;applyExpression();loadNote();};
$('play').onclick=()=>{if(pose){if(time>=pose.clips[action].duration)time=0;setPlaying(!playing);}};
$('time').oninput=e=>{setPlaying(false);time=+e.target.value;frameGeometry();dirty=true;};
function step(delta){if(!pose)return;setPlaying(false);time=Math.max(0,Math.min(pose.clips[action].duration,time+delta));frameGeometry();dirty=true;}
$('step').onclick=()=>step(.05);$('step-back').onclick=()=>step(-.05);
$('grid').onchange=()=>{grid.visible=$('grid').checked;dirty=true;};$('wire').onchange=()=>{material.wireframe=$('wire').checked;dirty=true;};
for(const button of document.querySelectorAll('[data-angle]'))button.onclick=()=>{az=+button.dataset.angle;el=.1;$('spin').checked=false;dirty=true;};
$('reset-view').onclick=()=>{az=.45;el=.15;zoom=1;$('spin').checked=false;resize();};
let drag=null;$('view').onpointerdown=e=>{drag=[e.clientX,e.clientY];$('view').setPointerCapture(e.pointerId);};$('view').onpointermove=e=>{if(!drag)return;az+=(e.clientX-drag[0])*.009;el=Math.max(-.3,Math.min(1.4,el+(e.clientY-drag[1])*.009));drag=[e.clientX,e.clientY];dirty=true;};$('view').onpointerup=$('view').onpointercancel=()=>drag=null;
$('view').addEventListener('wheel',e=>{e.preventDefault();zoom=Math.max(.5,Math.min(3,zoom*Math.exp(-e.deltaY*.001)));resize();},{passive:false});
document.addEventListener('keydown',e=>{if(e.code==='Space'&&!/INPUT|TEXTAREA|SELECT|BUTTON/.test(e.target.tagName)&&$('models').classList.contains('active')){e.preventDefault();$('play').click();}});
let previous=performance.now(),lastPose=0;
function animate(now){const dt=Math.min(.1,(now-previous)/1000);previous=now;if($('models').classList.contains('active')&&pose){if(playing){time+=dt*+$('speed').value;const duration=pose.clips[action].duration;if(time>=duration){if($('loop').checked)time%=duration;else{time=duration;setPlaying(false);}}if(now-lastPose>40){frameGeometry();lastPose=now;dirty=true;}}if($('spin').checked){az+=dt*.4;dirty=true;}if(dirty){cameraPosition();renderer.render(scene,camera);dirty=false;}}requestAnimationFrame(animate);}
let activeAudio=null,filteredAudio=[],soundToken=0;
function soundKey(item){return `sound/${item.id}/${item.hash}`;}
function renderAudio(){
 const species=$('voice-species').value,age=$('voice-age').value,event=$('voice-event').value,q=$('voice-search').value.trim().toLowerCase();
 filteredAudio=catalog.audio.filter(a=>(!species||a.species===species)&&(!age||a.age===age)&&(!event||a.events.includes(event))&&(!q||(a.id+a.zh+a.ja).toLowerCase().includes(q)));
 $('sound-count').textContent=`${filteredAudio.length} 条`;$('audio-list').replaceChildren();
 if(!filteredAudio.length){const empty=document.createElement('p');empty.className='empty';empty.textContent='没有匹配的声音，试试更换筛选条件。';$('audio-list').append(empty);}
 for(const item of filteredAudio){
  const row=document.createElement('article');row.className='sound-row'+(activeAudio?.id===item.id?' current':'');row.dataset.sound=item.id;
  const play=document.createElement('button');play.className='play-sound';play.textContent='▶';play.setAttribute('aria-label',`播放 ${item.zh||item.id}`);play.onclick=()=>playSound(item);
  const lines=document.createElement('div'),zh=document.createElement('strong'),jp=document.createElement('div'),meta=document.createElement('div');zh.textContent=item.zh||'（无中文台词）';jp.className='jp';jp.textContent=item.ja;meta.className='sound-meta';meta.textContent=`${item.events.map(e=>label(e,eventNames)).join(' · ')} · ${item.origin==='native'?'原版选句':'项目原创'} · ${item.id}`;lines.append(zh,jp,meta);
  const info=document.createElement('div');info.className='sound-meta';info.textContent=`${names[item.species]||item.species} / ${ages[item.age]||item.age}${item.duration?' · '+item.duration.toFixed(2)+'秒':''}`;
  const review=document.createElement('div');review.className='sound-review';const status=document.createElement('select');for(const [value,text]of [['unreviewed','未听审'],['seen','已听过'],['changes','需要调整'],['keep','暂时保留']])status.add(new Option(text,value));const input=document.createElement('input');input.placeholder='音色、语速、语气等意见';input.setAttribute('aria-label',`${item.id} 试听意见`);status.setAttribute('aria-label',`${item.id} 检查状态`);const saved=notes[soundKey(item)]||{};status.value=saved.status||'unreviewed';input.value=saved.text||'';
  const save=()=>{notes[soundKey(item)]={type:'sound',id:item.id,sourceHash:item.hash,species:item.species,age:item.age,zh:item.zh,status:status.value,text:input.value,updated:new Date().toISOString()};persist();};status.onchange=input.oninput=save;review.append(status,input);row.append(play,lines,info,review);$('audio-list').append(row);
 }
}
async function playSound(item){if(!item)return;const token=++soundToken;activeAudio=item;const player=$('player');player.pause();player.src=item.url;player.playbackRate=1;$('audio-error').textContent='';$('now-title').textContent=`${names[item.species]} · ${ages[item.age]} · ${item.id}`;$('now-text').textContent=item.zh||item.ja;for(const row of document.querySelectorAll('.sound-row'))row.classList.toggle('current',row.dataset.sound===item.id);try{await player.play();}catch(e){if(token===soundToken)$('audio-error').textContent='未能播放，请点击播放器重试。';}}
function nextAudio(delta){const index=filteredAudio.findIndex(a=>a.id===activeAudio?.id);const target=index<0?0:index+delta;if(target>=0&&target<filteredAudio.length)playSound(filteredAudio[target]);}
$('audio-prev').onclick=()=>nextAudio(-1);$('audio-next').onclick=()=>nextAudio(1);$('player').onended=()=>{if($('auto-next').checked)nextAudio(1);};$('player').onerror=()=>{$('audio-error').textContent='音频读取失败，请确认本地服务正在运行。';};
$('stop-audio').onclick=()=>{$('player').pause();$('player').currentTime=0;};$('play-all').onclick=()=>{$('auto-next').checked=true;playSound(filteredAudio[0]);};
for(const id of ['voice-species','voice-age','voice-event'])$(id).onchange=()=>{$('player').pause();renderAudio();};$('voice-search').oninput=renderAudio;
for(const event of [...new Set(catalog.audio.flatMap(a=>a.events))].sort())$('voice-event').add(new Option(label(event,eventNames),event));
function switchTab(id){for(const panel of document.querySelectorAll('.panel'))panel.classList.toggle('active',panel.id===id);for(const tab of document.querySelectorAll('[data-tab]'))tab.classList.toggle('active',tab.dataset.tab===id);document.body.classList.toggle('audio-mode',id==='audio');if(id!=='audio')$('player').pause();if(id!=='models')setPlaying(false);else resize();if(id==='audio')renderAudio();location.hash=id;}
for(const tab of document.querySelectorAll('[data-tab]'))tab.onclick=()=>switchTab(tab.dataset.tab);
$('export').onclick=()=>{const entries=Object.values(notes).filter(n=>n.status!=='unreviewed'||n.text||n.view);const report={project:'MCyukkuri',exportedAt:new Date().toISOString(),scope:'User review notes only; does not change approved asset records.',entries};const blob=new Blob([JSON.stringify(report,null,2)],{type:'application/json;charset=utf-8'});const url=URL.createObjectURL(blob),a=document.createElement('a');a.href=url;a.download='油库里检查意见-'+new Date().toISOString().slice(0,10)+'.json';a.click();setTimeout(()=>URL.revokeObjectURL(url),3000);toast(`已导出 ${entries.length} 条检查记录`);};
try{initRenderer();buildModelList();selectModel(catalog.models[0]);requestAnimationFrame(animate);}catch(error){$('loading').textContent='三维预览初始化失败：'+error.message;}
const initialTab=location.hash.slice(1);if(['audio','extras'].includes(initialTab))switchTab(initialTab);
