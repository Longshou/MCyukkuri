// Deterministic orthographic rasterizer: shared triangle data, per-pixel depth.
// Also exported to Node so verification executes the actual viewer renderer.
function renderModel(vertices, texture, width, height, az, el, span) {
  const pixels=new Uint8ClampedArray(width*height*4), depth=new Float64Array(width*height);
  depth.fill(-Infinity);
  const s=Math.sin(az),c=Math.cos(az),se=Math.sin(el),ce=Math.cos(el);
  const right=[c,0,s],up=[-s*se,ce,c*se],eye=[s*ce,se,-c*ce],scale=height/(2*span);
  for(let i=0;i<vertices.length;i+=24){
    const nx=vertices[i+5],ny=vertices[i+6],nz=vertices[i+7];
    if(nx*eye[0]+ny*eye[1]+nz*eye[2]<=0)continue;
    const light=.78+.22*Math.max(0,nx*-.35+ny*.8+nz*-.48);
    const tri=[];
    for(let j=0;j<3;j++){
      const k=i+j*8,x=vertices[k],y=vertices[k+1]-5.8,z=vertices[k+2];
      tri.push([width/2+(x*right[0]+z*right[2])*scale,
        height/2-(x*up[0]+y*up[1]+z*up[2])*scale,
        x*eye[0]+y*eye[1]+z*eye[2],vertices[k+3]*128,vertices[k+4]*128]);
    }
    const [a,b,d]=tri;
    const det=(b[1]-d[1])*(a[0]-d[0])+(d[0]-b[0])*(a[1]-d[1]);
    if(Math.abs(det)<1e-8)continue;
    const x0=Math.max(0,Math.floor(Math.min(a[0],b[0],d[0]))),x1=Math.min(width-1,Math.ceil(Math.max(a[0],b[0],d[0])));
    const y0=Math.max(0,Math.floor(Math.min(a[1],b[1],d[1]))),y1=Math.min(height-1,Math.ceil(Math.max(a[1],b[1],d[1])));
    for(let y=y0;y<=y1;y++)for(let x=x0;x<=x1;x++){
      const wa=((b[1]-d[1])*(x+.5-d[0])+(d[0]-b[0])*(y+.5-d[1]))/det;
      const wb=((d[1]-a[1])*(x+.5-d[0])+(a[0]-d[0])*(y+.5-d[1]))/det,wc=1-wa-wb;
      if(wa< -1e-7||wb< -1e-7||wc< -1e-7)continue;
      const z=wa*a[2]+wb*b[2]+wc*d[2],index=y*width+x;
      // Keep first coplanar face, preventing overlapping cuboids from flickering.
      if(z<=depth[index]+1e-7)continue;
      depth[index]=z;
      const u=Math.max(0,Math.min(127,Math.floor(wa*a[3]+wb*b[3]+wc*d[3])));
      const v=Math.max(0,Math.min(127,Math.floor(wa*a[4]+wb*b[4]+wc*d[4])));
      const t=(v*128+u)*4,p=index*4;
      pixels[p]=Math.floor(texture[t]*light);pixels[p+1]=Math.floor(texture[t+1]*light);pixels[p+2]=Math.floor(texture[t+2]*light);pixels[p+3]=255;
    }
  }
  return pixels;
}
if(typeof module!=='undefined')module.exports={renderModel};
if(typeof document!=='undefined'){
const canvas=document.getElementById('view'),fallback=document.getElementById('error'),status=document.getElementById('status');
let az=-.55,el=.2,span=9.8,auto=false,drag=null,dirty=true,texture=null,last=0,failed=false;
function fail(e){failed=true;fallback.style.display='block';status.textContent='已显示静态模型。预览错误：'+e.message;console.error(e);}
try{
const ctx=canvas.getContext('2d');if(!ctx)throw Error('浏览器未提供 Canvas 2D');
const img=new Image(),timer=setTimeout(()=>{if(!texture)fail(Error('内嵌贴图加载超时'));},10000);
img.onload=()=>{clearTimeout(timer);try{const t=document.createElement('canvas');t.width=128;t.height=128;const tc=t.getContext('2d');tc.drawImage(img,0,0);texture=tc.getImageData(0,0,128,128).data;dirty=true;}catch(e){fail(e);}};
img.onerror=()=>{clearTimeout(timer);fail(Error('贴图解码失败'));};img.src=DATA.texture;
function reset(){az=-.55;el=.2;span=9.8;dirty=true;}
canvas.addEventListener('pointerdown',e=>{drag=[e.clientX,e.clientY];canvas.setPointerCapture(e.pointerId);});
canvas.addEventListener('pointermove',e=>{if(!drag)return;az-=(e.clientX-drag[0])*.008;el=Math.max(-.55,Math.min(1.25,el+(e.clientY-drag[1])*.006));drag=[e.clientX,e.clientY];dirty=true;});
for(const event of ['pointerup','pointercancel','lostpointercapture'])canvas.addEventListener(event,()=>drag=null);
canvas.addEventListener('wheel',e=>{e.preventDefault();span=Math.max(7,Math.min(24,span*Math.exp(e.deltaY*.001)));dirty=true;},{passive:false});
canvas.addEventListener('dblclick',reset);document.getElementById('reset').onclick=reset;
document.querySelectorAll('[data-angle]').forEach(b=>b.onclick=()=>{az=Number(b.dataset.angle)*Math.PI/180;el=Number(b.dataset.angle)===0?0:.2;dirty=true;});
document.getElementById('auto').onclick=e=>{auto=!auto;e.target.classList.toggle('active',auto);e.target.setAttribute('aria-pressed',String(auto));e.target.textContent=auto?'暂停旋转':'自动旋转';};
function frame(t){if(failed)return;try{
  const dt=Math.min((t-last)/1000,.05);last=t;if(auto&&!drag){az+=dt*.35;dirty=true;}
  const cw=canvas.clientWidth,ch=canvas.clientHeight;
  if(cw&&ch){const ratio=Math.min(1,720/cw,640/ch),w=Math.max(1,Math.round(cw*ratio)),h=Math.max(1,Math.round(ch*ratio));
    if(canvas.width!==w||canvas.height!==h){canvas.width=w;canvas.height=h;dirty=true;}
    if(texture&&dirty){const effectiveSpan=Math.max(span,9*h/w);const shadow=document.querySelector('.shadow');shadow.style.top=(50+5.8*Math.cos(el)/(2*effectiveSpan)*100)+'%';shadow.style.bottom='auto';shadow.style.transform='translateY(-50%)';shadow.style.width=(12*h/(2*effectiveSpan*w)*100)+'%';shadow.style.left=(50-6*h/(2*effectiveSpan*w)*100)+'%';const data=ctx.createImageData(w,h);data.data.set(renderModel(DATA.vertices,texture,w,h,az,el,effectiveSpan));ctx.putImageData(data,0,0);fallback.style.display='none';dirty=false;}
  }
  requestAnimationFrame(frame);
}catch(e){fail(e);}}
requestAnimationFrame(frame);
}catch(e){fail(e);}
}
