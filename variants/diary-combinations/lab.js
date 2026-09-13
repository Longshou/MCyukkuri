const canvas=document.getElementById('model'),ctx=canvas.getContext('2d');
let selected=0,mesh=0,params={},base=null,dirty=true,az=-.4,el=.14,span=12.6,drag=null,blink=false,phase=-1,failed=false;
const fallback=document.getElementById('fallback');
const ids=['aperture','gazeX','gazeY','mouth'];
function choose(i){selected=i;params={...COMBINATIONS[i]};mesh=params.hair==='sweep'?1:0;document.getElementById('hair').value=String(mesh);for(const id of ids)document.getElementById(id).value=params[id];document.getElementById('apertureValue').textContent=Math.round(params.aperture*100)+'%';document.querySelectorAll('[data-preset]').forEach(b=>b.classList.toggle('active',Number(b.dataset.preset)===i));dirty=true;}
document.querySelectorAll('[data-preset]').forEach(b=>b.onclick=()=>choose(Number(b.dataset.preset)));
for(const id of ids)document.getElementById(id).oninput=e=>{params[id]=Number(e.target.value);document.getElementById('apertureValue').textContent=Math.round(params.aperture*100)+'%';dirty=true;};
document.getElementById('hair').onchange=e=>{mesh=Number(e.target.value);dirty=true;};
document.getElementById('restore').onclick=()=>choose(selected);
document.getElementById('blink').onclick=e=>{blink=!blink;e.target.classList.toggle('active',blink);e.target.textContent=blink?'停止眨眼':'试试眨眼';phase=-1;dirty=true;};
document.querySelectorAll('[data-view]').forEach(b=>b.onclick=()=>{az={front:0,three:-.4,side:Math.PI/2}[b.dataset.view];el=b.dataset.view==='front'?0:.14;dirty=true;});
document.getElementById('reset').onclick=()=>{az=-.4;el=.14;span=12.6;dirty=true;};
canvas.onpointerdown=e=>{drag=[e.clientX,e.clientY];canvas.setPointerCapture(e.pointerId);};canvas.onpointerup=canvas.onpointercancel=()=>drag=null;
canvas.onpointermove=e=>{if(!drag)return;az-=(e.clientX-drag[0])*.008;el=Math.max(-.5,Math.min(1,el+(e.clientY-drag[1])*.006));drag=[e.clientX,e.clientY];dirty=true;};
canvas.addEventListener('wheel',e=>{e.preventDefault();span=Math.max(10.5,Math.min(22,span*Math.exp(e.deltaY*.001)));dirty=true;},{passive:false});
function fail(e){failed=true;fallback.style.display='block';document.getElementById('status').textContent='预览错误：'+e.message;console.error(e);}
choose(0);const image=new Image();image.onload=()=>{try{const c=document.createElement('canvas');c.width=c.height=128;const tc=c.getContext('2d');tc.drawImage(image,0,0);base=tc.getImageData(0,0,128,128).data;dirty=true;}catch(e){fail(e);}};image.onerror=()=>fail(Error('贴图加载失败'));image.src=COMBINATIONS[0].texture;
window.labState=()=>({selected,mesh,aperture:params.aperture,blink,ready:!!base&&!failed});
function frame(t){if(failed)return;try{const cw=canvas.clientWidth,ch=canvas.clientHeight,r=Math.min(1,760/cw,640/ch),w=Math.round(cw*r),h=Math.round(ch*r);if(w&&h){if(canvas.width!==w||canvas.height!==h){canvas.width=w;canvas.height=h;dirty=true;}const cycle=t%3200,close=blink&&cycle<250?Math.sin(Math.PI*cycle/250):0;if(close!==phase){phase=close;dirty=true;}if(base&&dirty){const tex=FaceLayers.faceAtlas({...params,aperture:params.aperture*(1-close)},base);const out=ctx.createImageData(w,h);out.data.set(renderModel(COMBINATIONS[mesh].vertices,tex,w,h,az,el,Math.max(span,11*h/w)));ctx.putImageData(out,0,0);fallback.style.display='none';dirty=false;window.lastTexture=tex;}}requestAnimationFrame(frame);}catch(e){fail(e);}}requestAnimationFrame(frame);
