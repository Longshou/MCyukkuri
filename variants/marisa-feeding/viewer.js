(function(){
const $=id=>document.getElementById(id),canvas=$('view'),sim=new FeedingAPI.Feeding();
let az=.78,el=.37,span=25,auto=false,drag=null,last=0,speed=1;
const labels={waiting:'等待添粮',approach:'正在走近食盆',bite:'正在进食',recover:'抬头等待添粮',satisfied:'吃饱了，真幸福',full:'吃饱休息中'};
function fail(e){$('error').style.display='block';$('error').textContent='三维预览加载失败：'+e.message;console.error(e);}
try{
const renderer=new THREE.WebGLRenderer({canvas,antialias:true,alpha:true,preserveDrawingBuffer:true});renderer.setPixelRatio(Math.min(devicePixelRatio,1.5));renderer.setClearColor(0x000000,0);
const scene=new THREE.Scene(),camera=new THREE.OrthographicCamera(-30,30,25,-25,.1,250);
scene.add(new THREE.HemisphereLight(0xfff9e3,0x8c9d7e,.8));const light=new THREE.DirectionalLight(0xffffff,.35);light.position.set(-30,55,-40);scene.add(light);
const texture=new THREE.TextureLoader().load(DATA.texture);texture.magFilter=THREE.NearestFilter;texture.minFilter=THREE.NearestFilter;texture.generateMipmaps=false;
const material=new THREE.MeshLambertMaterial({map:texture});
function vertices(els){
 const out=[];const ns={north:[0,0,-1],south:[0,0,1],east:[1,0,0],west:[-1,0,0],up:[0,1,0],down:[0,-1,0]};
 for(const e of els){const [x,y,z]=e.from,[X,Y,Z]=e.to;
  const quads={north:[[X,Y,z],[x,Y,z],[x,y,z],[X,y,z]],south:[[x,Y,Z],[X,Y,Z],[X,y,Z],[x,y,Z]],east:[[X,Y,Z],[X,Y,z],[X,y,z],[X,y,Z]],west:[[x,Y,z],[x,Y,Z],[x,y,Z],[x,y,z]],up:[[x,Y,z],[X,Y,z],[X,Y,Z],[x,Y,Z]],down:[[x,y,Z],[X,y,Z],[X,y,z],[x,y,z]]};
  for(const [s,q] of Object.entries(quads)){const [u,v,U,V]=e.faces[s].uv,uv=[[u,v],[U,v],[U,V],[u,V]];for(const i of [0,2,1,0,3,2])out.push(...q[i],uv[i][0]/256,uv[i][1]/256,...ns[s]);}
 }return out;
}
function meshFrom(v){const g=new THREE.BufferGeometry(),pos=[],normal=[],uv=[];for(let i=0;i<v.length;i+=8){pos.push(...v.slice(i,i+3));uv.push(v[i+3],1-v[i+4]);normal.push(...v.slice(i+5,i+8));}g.setAttribute('position',new THREE.Float32BufferAttribute(pos,3));g.setAttribute('normal',new THREE.Float32BufferAttribute(normal,3));g.setAttribute('uv',new THREE.Float32BufferAttribute(uv,2));const mesh=new THREE.Mesh(g,material);mesh.frustumCulled=false;scene.add(mesh);return mesh;}
const rest=vertices(DATA.marisa.elements),marisa=meshFrom(rest),bowlRoot=new THREE.Group();scene.add(bowlRoot);bowlRoot.position.z=-13.9;
const bowlParts={};for(const name of [...new Set(DATA.bowl.groups)]){const v=vertices(DATA.bowl.elements.filter((e,i)=>DATA.bowl.groups[i]===name));const m=meshFrom(v);bowlRoot.add(m);bowlParts[name]=m;}
const floor=new THREE.Mesh(new THREE.PlaneGeometry(1500,1500),new THREE.MeshLambertMaterial({color:0xd5dfc8,transparent:true,opacity:.6}));floor.rotation.x=-Math.PI/2;floor.position.y=-.05;scene.add(floor);
const grid=new THREE.GridHelper(96,12,0xc0cdb5,0xcad5bd);grid.position.y=-.035;grid.material.transparent=true;grid.material.opacity=.4;scene.add(grid);
const shadowCanvas=document.createElement('canvas');shadowCanvas.width=128;shadowCanvas.height=128;const sc=shadowCanvas.getContext('2d'),gr=sc.createRadialGradient(64,64,8,64,64,64);gr.addColorStop(0,'rgba(52,67,43,.26)');gr.addColorStop(1,'rgba(52,67,43,0)');sc.fillStyle=gr;sc.fillRect(0,0,128,128);
function shadow(w,h){const s=new THREE.Mesh(new THREE.PlaneGeometry(w,h),new THREE.MeshBasicMaterial({map:new THREE.CanvasTexture(shadowCanvas),transparent:true,depthWrite:false}));s.rotation.x=-Math.PI/2;s.position.y=.005;scene.add(s);return s;}
const marisaShadow=shadow(24,17),bowlShadow=shadow(15,10);bowlShadow.position.z=-13.9;
const morsel=new THREE.Mesh(new THREE.BoxGeometry(.42,.36,.38),new THREE.MeshLambertMaterial({color:0xd4a05b}));scene.add(morsel);
function applyPose(){
 let clip='idle',t=sim.time;
 if(sim.state==='approach')clip='walk';else if(sim.state==='bite')clip='bite';else if(sim.state==='recover'){clip='bite';t=.64+Math.min(1,sim.time/.5)*.4;}else if(sim.state==='satisfied')clip='satisfied';
 const v=poseVertices(rest,DATA.marisa.rig,t,true,false,clip),p=marisa.geometry.attributes.position,n=marisa.geometry.attributes.normal;
 for(let i=0,j=0;i<v.length;i+=8,j++){p.setXYZ(j,v[i],v[i+1],v[i+2]);n.setXYZ(j,v[i+5],v[i+6],v[i+7]);}p.needsUpdate=true;n.needsUpdate=true;marisa.position.z=sim.z;marisaShadow.position.z=sim.z;
 for(let i=0;i<8;i++)bowlParts['food_'+i].visible=i<sim.food;
 morsel.visible=sim.state==='bite'&&sim.time>.4&&sim.time<.79&&(sim.food>0||sim.mouthful);
 if(morsel.visible){const q=Math.min(1,(sim.time-.4)/.22);morsel.position.set(0,1.3+q*.7,-11.4+q*.3);morsel.rotation.set(q*2,q,0);morsel.scale.setScalar(sim.time>.65?Math.max(.01,1-(sim.time-.65)/.14):1);}
}
function refresh(){
 $('food').textContent=sim.food+' / 8 份';$('foodbar').style.width=sim.food/8*100+'%';$('fullness').textContent=Math.round(sim.fullness/8*100)+'%';$('fullbar').style.width=sim.fullness/8*100+'%';
 let label=labels[sim.state];if(sim.state==='bite')label=sim.time<.92?'低头取食':sim.time<2.18?'闭眼咀嚼':'吞咽 · 回正';if(sim.paused)label='已暂停 · '+label;
 if($('status').textContent!==label)$('status').textContent=label;
 $('speech').textContent={waiting:sim.food?'油粮准备好了！':'给魔理沙添一盆油粮吧。',approach:'有好吃的油粮呢！',bite:sim.time<.92?'啊呜～':'嚼嚼……真好吃！',recover:'再添一点油粮吧。',satisfied:'吃饱了，真幸福～',full:'悠闲地休息一下吧。'}[sim.state];
 $('fill').disabled=sim.food>=8;$('empty').disabled=sim.food===0;$('eat').disabled=sim.food===0||sim.fullness>=8||!['waiting','full'].includes(sim.state);
 $('pause').textContent=sim.paused?'继续':'暂停';
}
function draw(){const w=canvas.clientWidth,h=canvas.clientHeight;renderer.setSize(w,h,false);const aspect=w/h,fit=Math.max(span,24/aspect);camera.left=-fit*aspect;camera.right=fit*aspect;camera.top=fit;camera.bottom=-fit;camera.updateProjectionMatrix();const target=new THREE.Vector3(0,6,-2);camera.position.set(Math.sin(az)*Math.cos(el)*90,6+Math.sin(el)*90,-2-Math.cos(az)*Math.cos(el)*90);camera.lookAt(target);applyPose();refresh();renderer.render(scene,camera);}
 $('fill').onclick=()=>{sim.fill();sim.start();refresh();};$('empty').onclick=()=>{sim.empty();refresh();};$('eat').onclick=()=>{sim.start();refresh();};$('pause').onclick=()=>{sim.paused=!sim.paused;refresh();};$('reset').onclick=()=>{sim.reset();refresh();};$('speed').oninput=e=>{speed=Number(e.target.value);$('speedvalue').textContent=speed+'×';};
 document.querySelectorAll('[data-view]').forEach(b=>b.onclick=()=>{auto=false;az={three:.78,front:0,side:Math.PI/2}[b.dataset.view];el=b.dataset.view==='side'?.15:.37;});$('orbit').onclick=()=>{auto=!auto;$('orbit').setAttribute('aria-pressed',String(auto));};
 canvas.onpointerdown=e=>{drag=[e.clientX,e.clientY];canvas.setPointerCapture(e.pointerId);};canvas.onpointermove=e=>{if(!drag)return;az-=(e.clientX-drag[0])*.008;el=Math.max(.05,Math.min(1.25,el+(e.clientY-drag[1])*.006));drag=[e.clientX,e.clientY];};for(const event of ['pointerup','pointercancel','lostpointercapture'])canvas.addEventListener(event,()=>drag=null);canvas.addEventListener('wheel',e=>{e.preventDefault();span=Math.max(15,Math.min(40,span*Math.exp(e.deltaY*.001)));},{passive:false});
 // Deterministic inspection API uses the same state machine and rendered rig.
 window.feedingPreview={sim,draw,advance:seconds=>{sim.update(seconds);draw();},setCamera:(a,e)=>{az=a;el=e;draw();},snapshot:()=>sim.snapshot(),pause:()=>{sim.paused=true;draw();}};
 function frame(now){try{const dt=last?Math.min((now-last)/1000,.1):0;last=now;sim.update(dt*speed);if(auto&&!drag)az+=dt*.25;draw();requestAnimationFrame(frame);}catch(e){fail(e);}}requestAnimationFrame(frame);
}catch(e){fail(e);}
})();
