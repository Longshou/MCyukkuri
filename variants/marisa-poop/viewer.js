const canvas=document.getElementById('view'),ctx=canvas.getContext('2d'),error=document.getElementById('error');
const play=document.getElementById('play'),phase=document.getElementById('phase'),status=document.getElementById('status');
let texture=null,time=0,playing=true,enabled=true,az=65*Math.PI/180,el=.13,span=14,auto=false,drag=null,last=0,dirty=true,inspectFilling=false;
const fillingVertices=DATA.vertices.slice(DATA.depositStart*288);
for(let i=0;i<fillingVertices.length;i+=8){fillingVertices[i+1]+=11.6-2.2;fillingVertices[i+2]-=3;}
document.getElementById('filling').onclick=()=>{inspectFilling=!inspectFilling;playing=false;document.getElementById('filling').classList.toggle('active',inspectFilling);document.getElementById('filling').setAttribute('aria-pressed',String(inspectFilling));sync();dirty=true;};
function showCharacter(){inspectFilling=false;document.getElementById('filling').classList.remove('active');document.getElementById('filling').setAttribute('aria-pressed','false');}
function sync(){play.textContent=playing?'暂停':'播放';play.classList.toggle('active',playing);play.setAttribute('aria-pressed',String(playing));phase.value=time;document.getElementById('time').textContent=time.toFixed(2)+' s';const label=!enabled?'静止造型':time<.5?'准备':time<1.55?'压低身体 · 蓄力':time<3.55?'嗯——嗯—— · 用力排出':time<4.13?'排出 · 落地':time<5.4?'呼～ · 放松恢复':'完成';if(status.textContent!==label)status.textContent=label;}
function resetCamera(){az=65*Math.PI/180;el=.13;span=14;dirty=true;}
play.onclick=()=>{showCharacter();if(time>=6.4)time=0;playing=!playing;enabled=true;sync();dirty=true;};
document.getElementById('replay').onclick=()=>{showCharacter();time=0;playing=true;enabled=true;sync();dirty=true;};
document.getElementById('rest').onclick=()=>{showCharacter();time=0;playing=false;enabled=false;sync();dirty=true;};
phase.oninput=()=>{showCharacter();time=Number(phase.value);playing=false;enabled=true;sync();dirty=true;};
document.getElementById('reset').onclick=resetCamera;
document.querySelectorAll('[data-angle]').forEach(b=>b.onclick=()=>{az=Number(b.dataset.angle)*Math.PI/180;el=b.dataset.angle==='0'?0:.13;document.querySelectorAll('[data-angle]').forEach(x=>x.classList.toggle('active',x===b));dirty=true;});
document.getElementById('auto').onclick=e=>{auto=!auto;e.target.setAttribute('aria-pressed',String(auto));e.target.textContent=auto?'暂停旋转':'自动旋转';};
canvas.onpointerdown=e=>{drag=[e.clientX,e.clientY];canvas.setPointerCapture(e.pointerId);};
canvas.onpointermove=e=>{if(!drag)return;az-=(e.clientX-drag[0])*.008;el=Math.max(-.25,Math.min(.9,el+(e.clientY-drag[1])*.006));drag=[e.clientX,e.clientY];dirty=true;};
for(const event of ['pointerup','pointercancel','lostpointercapture'])canvas.addEventListener(event,()=>drag=null);
canvas.addEventListener('wheel',e=>{e.preventDefault();span=Math.max(11,Math.min(24,span*Math.exp(e.deltaY*.001)));dirty=true;},{passive:false});
canvas.ondblclick=resetCamera;
const img=new Image();img.onload=()=>{const c=document.createElement('canvas');c.width=c.height=128;const x=c.getContext('2d');x.drawImage(img,0,0);texture=x.getImageData(0,0,128,128).data;error.hidden=true;dirty=true;};img.onerror=()=>{error.textContent='贴图加载失败，请重新打开预览。';};img.src=DATA.texture;
window.poopDebug={setTime(t){showCharacter();time=Math.max(0,Math.min(6.4,t));enabled=true;playing=false;sync();dirty=true;},state(){return{time,playing,enabled,ready:!!texture,az,el,span,inspectFilling};},vertices(){return poseVertices(DATA.vertices,DATA.rig,time,enabled,false,'poop');}};
function frame(stamp){try{
 const dt=last?Math.max(0,(stamp-last)/1000):0;last=stamp;
 if(playing){time+=dt;if(time>=6.4){if(document.getElementById('loop').checked)time%=6.4;else{time=6.4;playing=false;}}dirty=true;sync();}if(auto&&!drag){az+=dt*.35;dirty=true;}
 const cw=canvas.clientWidth,ch=canvas.clientHeight,ratio=Math.min(1,850/cw,600/ch),w=Math.max(1,Math.round(cw*ratio)),h=Math.max(1,Math.round(ch*ratio));
 if(canvas.width!==w||canvas.height!==h){canvas.width=w;canvas.height=h;dirty=true;}
 if(texture&&dirty){const effectiveSpan=inspectFilling?Math.max(span/5,2.3*h/w):Math.max(span,12*h/w),v=inspectFilling?fillingVertices:poseVertices(DATA.vertices,DATA.rig,time,enabled,false,'poop'),pixels=ctx.createImageData(w,h);pixels.data.set(renderModel(v,texture,w,h,az,el,effectiveSpan));ctx.putImageData(pixels,0,0);dirty=false;
  function shadow(id,z,width){const s=document.getElementById(id),scale=ch/(2*effectiveSpan);s.style.left=(cw/2+z*Math.sin(az)*scale-width*scale/2)+'px';s.style.top=(ch/2+(11.6*Math.cos(el)-z*Math.cos(az)*Math.sin(el))*scale-6)+'px';s.style.width=width*scale+'px';}
  document.getElementById('shadow').hidden=inspectFilling;shadow('shadow',0,12);const deposit=document.getElementById('deposit-shadow');deposit.hidden=inspectFilling||!enabled||time<3.5;if(!deposit.hidden){const start=DATA.depositStart*288;let z=0;for(let i=0;i<36;i++)z+=v[start+i*8+2]/36;shadow('deposit-shadow',z+1.5,2.3);}
  if(inspectFilling)status.textContent='馅料模型 · 拖动旋转检查形状';
 }
 requestAnimationFrame(frame);
}catch(e){error.hidden=false;error.textContent='预览出错：'+e.message;console.error(e);}}
document.addEventListener('visibilitychange',()=>last=0);sync();requestAnimationFrame(frame);
