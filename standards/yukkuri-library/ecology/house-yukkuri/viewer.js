const $=id=>document.getElementById(id), canvas=$('view'), ctx=canvas.getContext('2d');
const S={time:0,playing:true,az:.30,el:.38,span:10,removedAt:null,ready:false};
const anatomy=new AnatomyController(DATA.anatomy);
const clamp=(x,a=0,b=1)=>Math.max(a,Math.min(b,x));
const smooth=x=>{x=clamp(x);return x*x*(3-2*x)};
const mix=(a,b,t)=>a.map((x,i)=>x+(b[i]-x)*t);
const atlas=document.createElement('canvas');atlas.width=atlas.height=1024;
const ac=atlas.getContext('2d'), images={};let pixels,lastAtlas='';
const homes={marisa:[-3.1,.16,1.7],reimu:[.6,.16,1.7]};
const eggPositions=Array.from({length:DATA.config.demo_egg_count},(_,i)=>[[3.65,.04,1.8],[4.75,.04,2],[4.15,.04,3.1]][i%3].map((v,k)=>v+(k===2?Math.floor(i/3)*1.1:0)));
function updateAtlas(a,b){
  const key=a+'/'+b;if(key===lastAtlas)return;
  ac.clearRect(0,0,1024,1024);ac.drawImage(images['marisa/'+a],0,0);ac.drawImage(images['reimu/'+b],256,0);ac.drawImage(images.props,512,0);
  ac.drawImage(images['ordinary/marisa'],640,0);ac.drawImage(images['ordinary/reimu'],768,0);
  pixels=ac.getImageData(0,0,1024,1024).data;lastAtlas=key;
}
function mesh(model,position=[0,0,0],scale=1,angle=0,clip='idle',time=0,slot=512){
  const v=poseVertices(model.vertices,model.rig,time,true,false,clip),c=Math.cos(angle),s=Math.sin(angle);
  for(let i=0;i<v.length;i+=8){
    const x=v[i]*scale,y=v[i+1]*scale,z=v[i+2]*scale,nx=v[i+5],nz=v[i+7];
    v[i]=c*x+s*z+position[0];v[i+1]=y+position[1];v[i+2]=-s*x+c*z+position[2];
    v[i+3]=(v[i+3]*model.textureSize+slot)/1024;v[i+4]=v[i+4]*model.textureSize/1024;
    v[i+5]=c*nx+s*nz;v[i+7]=-s*nx+c*nz;
  }
  return v;
}
function trip(home,target,t){
  const q=t/18;let p,moving,dir;
  if(q<.38){p=mix(home,target,smooth(q/.38));moving=true;dir=target.map((x,i)=>x-home[i]);}
  else if(q<.60){p=target;moving=false;dir=[0,0,-1];}
  else if(q<.95){p=mix(target,home,smooth((q-.60)/.35));moving=true;dir=home.map((x,i)=>x-target[i]);}
  else{p=home;moving=false;dir=[0,0,-1];}
  return {p,clip:moving?'waddle':'idle',angle:Math.atan2(-dir[0],-dir[2]),carrying:q>=.55&&q<.95};
}
function paint(){
  if(!S.ready)return;
  const subject=$('subject').value,action=$('action').value,t=S.time,expr=$('expression').value;
  const state=HouseLifecycle.snapshot(action==='hatch'?t:0,S.removedAt,DATA.config.demo_egg_count);
  const removed=S.removedAt!==null&&t>=S.removedAt;
  const auto=action==='rest'?'sleep':action==='flee'?'surprise':'neutral';
  updateAtlas(expr==='auto'?auto:expr,expr==='auto'?(action==='forage'?'sleep':auto):expr);
  const arrays=[],add=(m,p,s,a,c,tm,slot)=>arrays.push(mesh(m,p,s,a,c,tm,slot));
  const char=(id,p,scale=1,angle=0,clip='idle',tm=t)=>{
    DATA.characters[id].rig.boneOverrides=scale===1?anatomy.overrides():{};
    add(DATA.characters[id],p,scale,angle,clip,tm,id==='marisa'?0:256);
  };
  const prop=(id,p=[0,0,0],scale=1,angle=0)=>add(DATA.props[id],p,scale,angle,'idle',0,512);
  let centerY=2;
  if(subject==='marisa'||subject==='reimu'){
    centerY=2.65;
    char(subject,[0,0,0],1,0,action==='flee'?'flee':action==='forage'||action==='build'?'waddle':'idle');
    $('viewLabel').textContent='家油'+(subject==='marisa'?'魔理沙':'灵梦')+' · 成体';
  }else if(subject==='pair'){
    const clip=action==='flee'?'flee':action==='forage'||action==='build'?'waddle':'idle';
    centerY=2.7;char('marisa',[-2.5,0,0],1,0,clip);char('reimu',[2.3,0,0],1,0,clip);
    $('viewLabel').textContent='部件挂接调整 v9 · 下宽上窄、底缘连贯';
  }else if(subject==='compare'){
    add(DATA.ordinaryCharacters.marisa,[-11,0,0],1,0,'idle',t,640);add(DATA.ordinaryCharacters.reimu,[11,0,0],1,0,'idle',t,768);
    char('marisa',[-3,0,-8]);char('reimu',[3,0,-8]);centerY=8;
    $('viewLabel').textContent='后排普通成体 · 前排家油成体 · 相同世界尺度';
  }else{
    if(subject==='nest')centerY=.65;
    if(subject==='family'&&$('room').value!=='hidden')prop('room');
    if(!removed){prop('nest',[-1.3,0,1.7]);prop('nest',[4.2,0,2.4],.48);}
    if(subject==='family'){
      for(const id of ['marisa','reimu']){
        let p=homes[id],angle=0,clip='idle';
        if((action==='forage'||action==='build')&&id==='marisa'){
          const travel=trip(homes[id],[-5,0,-4.5],t);({p,angle,clip}=travel);
          if(action==='build'&&travel.carrying&&!removed){
            const forward=[-Math.sin(angle),0,-Math.cos(angle)];
            prop('lint',[p[0]+forward[0]*1.28,p[1]+1.75,p[2]+forward[2]*1.28]);
          }
        }
        if(action==='flee'){
          const start=[id==='marisa'?-3:1,0,-4];const q=smooth((t-1)/3);
          p=mix(start,homes[id],q);angle=q<1?Math.PI:0;clip=t<5?'flee':'idle';
        }
        if(removed && action==='rest')p=[p[0],0,p[2]];
        char(id,p,1,angle,clip);
      }
      for(const [i,p] of [[0,[-5,0,-5.9]],[1,[-4.65,0,-6.1]],[2,[-5.35,0,-5.95]]]){
        const eaten=action==='forage'&&t>7+i*.65;
        if(!eaten)prop('crumb',p,.75);
      }
      if(action==='build' && t<9.9)prop('lint',[-5,0,-5.9]);
      if(action==='build' && t>=17.1&&!removed)prop('lint',[-3, .45, .1]);
    }
    for(let i=0;i<state.eggs.length;i++){
      const e=state.eggs[i],p=eggPositions[i];
      if(action!=='hatch'){if(!removed)prop('egg',p);continue;}
      if(e.status==='incubating'){
        const wobble=t>e.hatchAt-1?Math.sin(t*24)*.035:0;
        prop('egg',[p[0]+wobble,p[1],p[2]]);
      }
      if(e.status==='hatched'){
        if(!removed)prop('shell',p);
        const age=t-e.hatchAt,q=smooth(age/1.4);
        char(e.species,mix([p[0],.06,p[2]],[3+i*1.35,.06,-.25+(i%2)*.1],q),DATA.config.hatchling_scale_relative_to_house_adult,0,age>1.4&&age<3?'waddle':'idle',age);
      }
    }
    $('viewLabel').textContent=subject==='nest'?'棉絮巢 · 卵区与破壳幼体':action==='forage'?'夜间 · 搜寻碎食':'家具下 · 棉絮巢';
  }
  const vertices=arrays.flat();
  const width=Math.min(1000,Math.round(canvas.clientWidth)),height=Math.round(canvas.clientHeight);
  if(canvas.width!==width||canvas.height!==height){canvas.width=width;canvas.height=height;}
  const fit=['family','compare','pair'].includes(subject)?Math.max(1,height/width*1.3):1;
  const rendered=renderModel(vertices,pixels,width,height,S.az,S.el,S.span*fit,{textureSize:1024,centerY});
  ctx.clearRect(0,0,width,height);ctx.putImageData(new ImageData(rendered,width,height),0,0);
  $('stage').classList.toggle('night',action==='forage');
  $('time').value=t;$('clock').textContent=t.toFixed(1)+' / 18 s';
  const captions={rest:'白天在家具下休息。棉絮围成低矮的巢，卵集中在旁侧。',forage:'魔理沙离巢寻找碎食，停下来进食，再回到巢边。',build:'魔理沙搬回一团棉絮，补到巢的边缘。棉絮与食物分别展示。',hatch:'依次展示巢内出现卵、临近孵化时轻晃、破壳后幼体离开壳。',flee:'受惊后向家具下躲避，回到藏身处后停下。'};
  $('caption').textContent=subject==='pair'?'最宽处位于下腹，底缘圆收，肩部逐段收窄。沿用统一五官、表情与饰品；背面可查看油菊，标准身体部件可单独伸缩。':captions[action]+(subject==='compare'?' 家油保留较高的体态，与普通成体在相同世界尺度下对比。':'');
  $('state').textContent=action==='hatch'?`巢：${state.nest.intact?'完整':'已移除'} · 待孵 ${state.eggs.filter(e=>e.status==='incubating').length} · 已出生 ${state.young.length} · 失巢卵 ${state.eggs.filter(e=>e.status==='lost').length}`:(removed?'巢已移除；恢复场景可重新观察。':'棉絮巢 / 碎食 / 浅色圆卵');
  const partState=anatomy.state(),partLabel=DATA.anatomy.parts.find(p=>p.id===partState.part).label;
  $('anatomyState').textContent=partState.amount===0&&!partState.targetExtended?'已收回（常态）':
    `${partLabel} · ${{extended:'已伸出',extending:'伸出中',retracting:'收回中',switching:'切换中'}[partState.phase]||'已收回'}`;
}
function resetCamera(){
  S.az=.30;S.el=.25;S.span=$('subject').value==='compare'?23:['marisa','reimu'].includes($('subject').value)?3.8:$('subject').value==='nest'?5:$('subject').value==='pair'?4.6:10;
}
function resetAnatomy(){
  anatomy.retract();anatomy.amount=0;anatomy.activePart=anatomy.selectedPart;S.needsPaint=true;
}
function reset(){S.time=0;S.removedAt=null;S.playing=true;resetAnatomy();$('play').textContent='暂停';paint();}
$('extendPart').onclick=()=>anatomy.extend($('anatomyPart').value);
$('retractPart').onclick=()=>anatomy.retract();
$('anatomyPart').onchange=()=>anatomy.selectPart($('anatomyPart').value);
$('play').onclick=()=>{S.playing=!S.playing;$('play').textContent=S.playing?'暂停':'播放';};
$('restart').onclick=reset;$('restore').onclick=reset;
$('removeNest').onclick=()=>{S.removedAt=S.time;paint();};
$('time').oninput=()=>{S.time=+$('time').value;S.playing=false;$('play').textContent='播放';paint();};
$('action').onchange=reset;$('subject').onchange=()=>{resetAnatomy();resetCamera();paint();};
for(const id of ['expression','room'])$(id).onchange=paint;
for(const b of document.querySelectorAll('[data-angle]'))b.onclick=()=>{S.az=+b.dataset.angle;S.el=.2;paint();};
$('top').onclick=()=>{S.el=1.30;paint();};$('resetCamera').onclick=()=>{resetCamera();paint();};
let drag=null;
canvas.onpointerdown=e=>{drag=[e.clientX,e.clientY];canvas.setPointerCapture(e.pointerId);};
canvas.onpointermove=e=>{if(!drag)return;S.az+=(e.clientX-drag[0])*.009;S.el=clamp(S.el+(e.clientY-drag[1])*.006,-.25,1.5);drag=[e.clientX,e.clientY];paint();};
canvas.onpointerup=canvas.onpointercancel=()=>drag=null;
canvas.onwheel=e=>{e.preventDefault();S.span=clamp(S.span*Math.exp(e.deltaY*.001),2,38);paint();};
window.addEventListener('resize',paint);
let last=performance.now(),drawAt=0;
function frame(now){
  const dt=Math.min(.25,(now-last)/1000);last=now;
  const anatomyChanged=anatomy.update(dt);
  S.needsPaint=!!S.needsPaint||anatomyChanged;
  if(S.ready&&S.playing){
    S.time+=dt;
    if(S.time>=18){
      if($('action').value==='hatch'||S.removedAt!==null){S.time=18;S.playing=false;$('play').textContent='播放';}
      else S.time%=18;
    }
  }
  if(S.ready&&(S.playing||S.needsPaint)&&now-drawAt>65){paint();drawAt=now;S.needsPaint=false;}
  requestAnimationFrame(frame);
}
const promises=[];
const expressionSelect=$('expression');expressionSelect.replaceChildren(new Option('表情随生活片段','auto'));
for(const [id,label]of Object.entries(DATA.expressionLabels))expressionSelect.add(new Option(label+' · 统一',id));
expressionSelect.value='neutral';
for(const species of ['marisa','reimu'])for(const [expression,url]of Object.entries(DATA.characters[species].textures))promises.push(new Promise((resolve,reject)=>{const im=new Image();im.onload=()=>{images[species+'/'+expression]=im;resolve();};im.onerror=reject;im.src=url;}));
promises.push(new Promise((resolve,reject)=>{const im=new Image();im.onload=()=>{images.props=im;resolve();};im.onerror=reject;im.src=DATA.props.nest.texture;}));
for(const species of ['marisa','reimu'])promises.push(new Promise((resolve,reject)=>{const im=new Image();im.onload=()=>{images['ordinary/'+species]=im;resolve();};im.onerror=reject;im.src=DATA.ordinaryCharacters[species].texture;}));
Promise.all(promises).then(()=>{S.ready=true;resetCamera();paint();requestAnimationFrame(frame);}).catch(()=>{$('caption').textContent='贴图加载失败，请重新构建预览。';});
window.houseDebug={ready:()=>S.ready,state:()=>({...S}),anatomy:()=>anatomy.state(),setTime:t=>{S.time=t;S.playing=false;$('play').textContent='播放';paint();},snapshot:()=>HouseLifecycle.snapshot(S.time,S.removedAt,DATA.config.demo_egg_count),render:paint};
