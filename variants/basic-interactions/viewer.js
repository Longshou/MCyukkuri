(async function(){
  const $=id=>document.getElementById(id),canvas=$('view'),player=new Interactions.Player();
  let az=0,el=.2,span=23,species='marisa',last=0,ready=false,dirty=true;
  const pointers=new Map();
  function fail(e){$('error').style.display='block';$('error').textContent='预览加载失败：'+e.message;console.error(e);}
  try {
    const renderer=new THREE.WebGLRenderer({canvas,antialias:true,alpha:true,preserveDrawingBuffer:true});
    renderer.setPixelRatio(Math.min(devicePixelRatio,1.5));renderer.setClearColor(0,0);
    const scene=new THREE.Scene(),camera=new THREE.OrthographicCamera(-40,40,23,-23,.1,300);
    scene.add(new THREE.HemisphereLight(0xfff9e8,0xa2a88e,.8));
    const sun=new THREE.DirectionalLight(0xffffff,.25);sun.position.set(-30,60,-40);scene.add(sun);
    const floor=new THREE.Mesh(new THREE.PlaneGeometry(350,350),new THREE.MeshLambertMaterial({color:0xd4ddc3}));floor.rotation.x=-Math.PI/2;floor.position.y=-.08;scene.add(floor);
    const grid=new THREE.GridHelper(150,20,0xc5cfb8,0xcbd4bd);grid.position.y=-.06;grid.material.transparent=true;grid.material.opacity=.35;scene.add(grid);
    const textureLoader=new THREE.TextureLoader();
    function loadTexture(src){return new Promise((res,rej)=>textureLoader.load(src,t=>{t.magFilter=THREE.NearestFilter;t.minFilter=THREE.NearestFilter;t.generateMipmaps=false;res(t);},undefined,rej));}
    // Cubes use the same UV orientation as the existing feeding viewer.
    function geometry(elements){
      const positions=[],uvs=[];
      for(const e of elements){
        const [x,y,z]=e.from,[X,Y,Z]=e.to,origin=new THREE.Vector3(...(e.origin||[0,0,0]));
        const rotation=new THREE.Euler(...(e.rotation||[0,0,0]).map(v=>v*Math.PI/180),'ZYX');
        const quads={north:[[X,Y,z],[x,Y,z],[x,y,z],[X,y,z]],south:[[x,Y,Z],[X,Y,Z],[X,y,Z],[x,y,Z]],east:[[X,Y,Z],[X,Y,z],[X,y,z],[X,y,Z]],west:[[x,Y,z],[x,Y,Z],[x,y,Z],[x,y,z]],up:[[x,Y,z],[X,Y,z],[X,Y,Z],[x,Y,Z]],down:[[x,y,Z],[X,y,Z],[X,y,z],[x,y,z]]};
        for(const [side,q] of Object.entries(quads)){
          const face=e.faces[side];if(!face||face.texture===null)continue;
          const [u,v,U,V]=face.uv,uv=[[u,v],[U,v],[U,V],[u,V]],turn=(face.rotation||0)/90;
          for(const i of [0,2,1,0,3,2]){const p=new THREE.Vector3(...q[i]).sub(origin).applyEuler(rotation).add(origin);positions.push(p.x,p.y,p.z);const tex=uv[(i+turn)%4];uvs.push(tex[0]/128,1-tex[1]/128);}
        }
      }
      const g=new THREE.BufferGeometry();g.setAttribute('position',new THREE.Float32BufferAttribute(positions,3));g.setAttribute('uv',new THREE.Float32BufferAttribute(uvs,2));g.computeVertexNormals();g.userData.rest=g.attributes.position.array.slice();return g;
    }
    function makeFace(kind){
      // These small transparent marks are newly drawn; source sprites are never loaded.
      const c=document.createElement('canvas');c.width=128;c.height=80;const x=c.getContext('2d');
      x.strokeStyle='#49352a';x.lineWidth=3;x.lineCap='round';
      for(const cx of [30,98]){
        x.beginPath();x.moveTo(cx-14,24);x.quadraticCurveTo(cx,kind==='lick'?38:9,cx+14,24);x.stroke();
        x.lineWidth=2;x.beginPath();x.moveTo(cx-10,9);x.quadraticCurveTo(cx,3,cx+10,9);x.stroke();x.lineWidth=3;
        x.fillStyle='#edb1a0';x.beginPath();x.ellipse(cx,45,11,4,0,0,Math.PI*2);x.fill();
      }
      x.fillStyle='#9b423a';x.beginPath();x.moveTo(51,40);x.quadraticCurveTo(64,44,77,40);x.quadraticCurveTo(77,65,64,63);x.quadraticCurveTo(51,64,51,40);x.fill();x.stroke();
      x.fillStyle='#e87a77';x.beginPath();x.ellipse(64,58,8,3,0,0,Math.PI*2);x.fill();
      const t=new THREE.CanvasTexture(c);t.magFilter=THREE.NearestFilter;t.minFilter=THREE.NearestFilter;
      const m=new THREE.MeshBasicMaterial({map:t,transparent:true,alphaTest:.05,side:THREE.DoubleSide,depthWrite:true});
      const mesh=new THREE.Mesh(new THREE.PlaneGeometry(10,6.25),m);mesh.rotation.y=Math.PI;mesh.position.set(0,3.9,-4.612);return mesh;
    }
    function shadow(){const c=document.createElement('canvas');c.width=c.height=64;const x=c.getContext('2d'),g=x.createRadialGradient(32,32,4,32,32,32);g.addColorStop(0,'rgba(56,65,41,.3)');g.addColorStop(1,'rgba(56,65,41,0)');x.fillStyle=g;x.fillRect(0,0,64,64);const m=new THREE.Mesh(new THREE.PlaneGeometry(23,21),new THREE.MeshBasicMaterial({map:new THREE.CanvasTexture(c),transparent:true,depthWrite:false}));m.rotation.x=-Math.PI/2;m.position.y=-.04;scene.add(m);return m;}
    async function createActor(name){
      const data=MODEL_DATA[name],texture=await loadTexture(data.texture),material=new THREE.MeshLambertMaterial({map:texture,alphaTest:.05});
      const root=new THREE.Group();scene.add(root);const chunks={body:[],face:[],hat:[]};
      for(const e of data.elements){const hat=/^(hat_|brim_|bow_)/.test(e.name);chunks[e.name.startsWith('face_')?'face':hat?'hat':'body'].push(e);}
      const parts={};for(const [key,els] of Object.entries(chunks)){parts[key]=new THREE.Mesh(geometry(els),material);parts[key].frustumCulled=false;parts[key].geometry.computeBoundingBox();root.add(parts[key]);}
      const smile=makeFace('smile'),lick=makeFace('lick');root.add(smile,lick);
      const tongueRoot=new THREE.Group();tongueRoot.position.set(0,1.9,-4.65);root.add(tongueRoot);
      const tongue=new THREE.Mesh(new THREE.BoxGeometry(.95,.25,2),new THREE.MeshLambertMaterial({color:0xe56f76}));tongue.position.z=-1;tongueRoot.add(tongue);
      const tip=new THREE.Mesh(new THREE.SphereGeometry(.48,8,4),tongue.material);tip.scale.set(1,.3,1);tip.position.z=-2;tongueRoot.add(tip);
      const line=new THREE.Mesh(new THREE.BoxGeometry(.04,.012,1.2),new THREE.MeshBasicMaterial({color:0xa84450}));line.position.set(0,.13,-1.15);tongueRoot.add(line);
      return {name,root,parts,smile,lick,tongueRoot,shadow:shadow()};
    }
    const actors=await Promise.all([createActor('marisa'),createActor('reimu')]);
    const hands=new THREE.Group();scene.add(hands);
    const handMaterial=new THREE.MeshLambertMaterial({color:0xf1d3ad,transparent:true,opacity:.82});
    function makeHand(){const group=new THREE.Group();const palm=new THREE.Mesh(new THREE.BoxGeometry(2.7,.65,3.1),handMaterial);group.add(palm);for(let i=0;i<4;i++){const f=new THREE.Mesh(new THREE.BoxGeometry(.56,.55,1.5),handMaterial);f.position.set(-.98+i*.66,.03,-2.1);group.add(f);}const thumb=new THREE.Mesh(new THREE.BoxGeometry(1,.6,1.6),handMaterial);thumb.position.set(1.65,0,-.6);thumb.rotation.y=-.35;group.add(thumb);hands.add(group);return group;}
    const handL=makeHand(),handR=makeHand();
    const hearts=new THREE.Group();scene.add(hearts);
    const heartShape=new THREE.Shape();heartShape.moveTo(0,0);heartShape.bezierCurveTo(-1,.7,-1,1.6,-.45,1.6);heartShape.bezierCurveTo(0,1.6,0,1.2,0,1.2);heartShape.bezierCurveTo(0,1.2,0,1.6,.45,1.6);heartShape.bezierCurveTo(1,1.6,1,.7,0,0);
    for(let i=0;i<3;i++){const m=new THREE.Mesh(new THREE.ShapeGeometry(heartShape),new THREE.MeshBasicMaterial({color:0xcc8278,side:THREE.DoubleSide}));m.position.set((i-1)*3,15+(i%2)*2,-1);hearts.add(m);}
    function pose(model,p){
      model.root.position.set(p.x,p.y,p.z);model.root.rotation.set(0,p.yaw,p.tilt);
      for(const mesh of Object.values(model.parts)){
        const attr=mesh.geometry.attributes.position,rest=mesh.geometry.userData.rest;
        for(let i=0;i<attr.count;i++){const j=i*3,y=rest[j+1],w=Math.max(0,Math.min(1,y/8));attr.setXYZ(i,rest[j]+p.lean*w,y,rest[j+2]);}
        attr.needsUpdate=true;mesh.geometry.computeVertexNormals();
      }
      // A bow rests flat; a hat keeps its crown upright.
      model.parts.hat.rotation.x=model.name==='reimu'?-Math.PI/2*p.hat:0;
      const hatBounds=model.parts.hat.geometry.boundingBox.clone().applyMatrix4(new THREE.Matrix4().makeRotationX(model.parts.hat.rotation.x));
      // Lift clear of the hair before bringing an accessory down in front.
      model.parts.hat.position.set(0,(.08-hatBounds.min.y)*p.hat+6*Math.sin(Math.PI*p.hat),(-7-hatBounds.max.z)*p.hat);
      model.parts.face.visible=p.face==='rest';model.smile.visible=p.face==='smile';model.lick.visible=p.face==='lick';
      for(const face of [model.smile,model.lick])face.position.x=p.lean*.49;
      model.tongueRoot.visible=p.tongue>.01;model.tongueRoot.scale.set(1,1,Math.max(.01,p.tongue));model.tongueRoot.rotation.set(p.tongueAngle,0,0);model.tongueRoot.position.x=p.lean*.24;
      model.root.updateMatrixWorld(true);
      const bottom=new THREE.Box3().setFromObject(model.parts.body).min.y;
      if(bottom<0)model.root.position.y-=bottom;
      model.shadow.position.x=p.x;model.shadow.position.z=p.z;model.shadow.material.opacity=1-p.y*.075;
    }
    function refresh(s){
      const a=Interactions.actions[player.id];$('status').textContent=s.phase;$('speech').textContent=s.speech;$('time').max=a.duration;$('time').value=player.time;$('time-label').textContent=player.time.toFixed(2)+' / '+a.duration.toFixed(2)+' 秒';$('play').textContent=player.playing?'暂停':'播放';$('play').setAttribute('aria-pressed',String(player.playing));$('detail').textContent=a.detail;
      document.querySelectorAll('[data-action]').forEach(b=>{const on=b.dataset.action===player.id;b.classList.toggle('active',on);b.setAttribute('aria-pressed',String(on));});
    }
    function draw(){
      const s=player.snapshot(),ordered=species==='marisa'?actors:[actors[1],actors[0]];
      ordered.forEach((model,i)=>{model.root.visible=i===0||s.pair;model.shadow.visible=model.root.visible;pose(model,s.actors[i]);});
      // Point the tongue at the actual receiving cheek instead of licking empty space.
      if(s.id==='lick'){
        const active=ordered[s.active],receiver=ordered[1-s.active];
        active.root.updateMatrixWorld(true);receiver.root.updateMatrixWorld(true);
        const target=receiver.root.localToWorld(new THREE.Vector3(s.active===0?-3.7:3.7,2.05,-4.63));
        active.root.worldToLocal(target);const delta=target.sub(active.tongueRoot.position),length=delta.length();
        active.tongueRoot.quaternion.setFromUnitVectors(new THREE.Vector3(0,0,-1),delta.normalize());
        active.tongueRoot.scale.z=Math.max(.001,length/2.48*s.actors[s.active].tongue);
      }
      if(s.id==='accessory'&&s.actors[0].tongue>.01){
        const model=ordered[0];model.root.updateMatrixWorld(true);
        const start=model.tongueRoot.getWorldPosition(new THREE.Vector3());
        const center=new THREE.Box3().setFromObject(model.parts.hat).getCenter(new THREE.Vector3());
        const ray=new THREE.Raycaster(start,center.sub(start).normalize());
        const hit=ray.intersectObject(model.parts.hat,false)[0];
        if(hit){const delta=model.root.worldToLocal(hit.point.clone()).sub(model.tongueRoot.position),length=delta.length();model.tongueRoot.quaternion.setFromUnitVectors(new THREE.Vector3(0,0,-1),delta.normalize());model.tongueRoot.scale.z=Math.max(.001,length/2.48*s.actors[0].tongue);}
      }
      hands.visible=s.hand>.01;handMaterial.opacity=.82*s.hand;
      if(s.handMode==='cheek'){
        handL.visible=true;handR.visible=false;handL.position.set(-5.5+s.actors[0].lean*.4,3.5,-5.5);handL.rotation.set(0,.55,Math.PI/2);
      }else{handL.visible=handR.visible=true;for(const [i,h] of [handL,handR].entries()){h.position.set(i===0?-6.7:6.7,s.actors[0].y+.12,-3.5);h.rotation.set(0,i===0?-.15:.15,0);}}
      hearts.visible=s.heart>.01;hearts.scale.setScalar(.6+.4*s.heart);hearts.position.y=s.heart*1.2;hearts.rotation.y=az;
      const w=canvas.clientWidth,h=canvas.clientHeight;renderer.setSize(w,h,false);const aspect=w/h,fit=Math.max(span,27/aspect);
      camera.left=-fit*aspect;camera.right=fit*aspect;camera.top=fit;camera.bottom=-fit;camera.updateProjectionMatrix();
      camera.position.set(Math.sin(az)*100*Math.cos(el),8+Math.sin(el)*100,-Math.cos(az)*100*Math.cos(el));camera.lookAt(0,8,0);
      refresh(s);renderer.render(scene,camera);dirty=false;
    }
    for(const [id,a] of Object.entries(Interactions.actions)){const b=document.createElement('button');b.textContent=a.name;b.dataset.action=id;b.onclick=()=>{player.select(id);dirty=true;};$('actions').append(b);}
    $('species').onchange=e=>{species=e.target.value;dirty=true;};
    $('play').onclick=()=>{if(player.time>=Interactions.actions[player.id].duration)player.time=0;player.playing=!player.playing;dirty=true;};
    $('replay').onclick=()=>{player.select(player.id);dirty=true;};$('stop').onclick=()=>{player.seek(Interactions.actions[player.id].duration);dirty=true;};
    $('time').oninput=e=>{player.seek(Number(e.target.value));dirty=true;};$('loop').onchange=e=>player.loop=e.target.checked;$('speed').onchange=e=>player.speed=Number(e.target.value);
    document.querySelectorAll('[data-view]').forEach(b=>b.onclick=()=>{az={front:0,three:.65,side:Math.PI/2,back:Math.PI}[b.dataset.view];el=.2;dirty=true;});$('camera-reset').onclick=()=>{az=0;el=.2;span=23;dirty=true;};
    function distance(){const p=[...pointers.values()];return p.length===2?Math.hypot(p[0][0]-p[1][0],p[0][1]-p[1][1]):0;}
    canvas.onpointerdown=e=>{pointers.set(e.pointerId,[e.clientX,e.clientY]);canvas.setPointerCapture(e.pointerId);};
    canvas.onpointermove=e=>{if(!pointers.has(e.pointerId))return;const old=pointers.get(e.pointerId),d=distance();pointers.set(e.pointerId,[e.clientX,e.clientY]);if(pointers.size===1){az-=(e.clientX-old[0])*.008;el=Math.max(.04,Math.min(1.15,el+(e.clientY-old[1])*.007));}else if(d>0&&distance()>0)span=Math.max(16,Math.min(40,span*d/distance()));dirty=true;};
    for(const event of ['pointerup','pointercancel','lostpointercapture'])canvas.addEventListener(event,e=>pointers.delete(e.pointerId));
    canvas.addEventListener('wheel',e=>{e.preventDefault();span=Math.max(16,Math.min(40,span*Math.exp(e.deltaY*.001)));dirty=true;},{passive:false});
    document.addEventListener('visibilitychange',()=>last=0);new ResizeObserver(()=>dirty=true).observe(canvas);
    ready=true;window.interactionPreview={ready:()=>ready,player,draw,snapshot:()=>({...player.snapshot(),species,camera:{az,el,span}}),seek(t){player.seek(t);draw();},select(id){player.select(id);draw();},setCamera(a,e){az=a;el=e;draw();},models:actors};
    function frame(now){const dt=last?Math.min(.1,(now-last)/1000):0;last=now;if(player.playing){player.update(dt);dirty=true;}if(dirty)draw();requestAnimationFrame(frame);}requestAnimationFrame(frame);
  }catch(e){fail(e);}
})();
