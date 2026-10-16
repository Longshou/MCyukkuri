/* Current exported poses only. This player never runs or controls Minecraft. */
(() => {
  'use strict';
  const $ = id => document.getElementById(id);
  const fail = message => {
    $('loading').hidden = false;
    $('loading').replaceChildren(Object.assign(document.createElement('strong'), {textContent: message}));
  };
  if (!window.THREE || !window.HAT_PLAYBACK) { fail('播放数据还未载入，请刷新这个页面。'); return; }
  const data = window.HAT_PLAYBACK, tickRate = data.tickRate || 20, MAX_MASKS = 8;
  const vertexShader = count => `
    precision highp float;
    attribute vec3 binding;
    uniform mat4 bones[${count}];
    varying vec2 textureUV;
    varying vec3 worldPosition;
    varying vec3 worldNormal;
    void main() {
      mat4 pose = bones[int(binding.x)] * (1.0-binding.z) + bones[int(binding.y)] * binding.z;
      vec4 world = pose * vec4(position,1.0);
      textureUV=uv; worldPosition=world.xyz;
      worldNormal=normalize((pose*vec4(normal,0.0)).xyz);
      gl_Position=projectionMatrix*viewMatrix*world;
    }`;
  const fragmentShader = `
    precision highp float;
    uniform sampler2D image;
    varying vec2 textureUV;
    varying vec3 worldPosition;
    varying vec3 worldNormal;
    void main() {
      vec4 sampleColor=texture2D(image,textureUV);
      if(sampleColor.a<0.5)discard;
      float light=.78+.22*max(0.0,dot(normalize(worldNormal),normalize(vec3(-.35,.8,-.48))));
      vec3 color=sampleColor.rgb*light;
      float wet=clamp(-worldPosition.y/4.5,0.0,.58);
      color=mix(color,vec3(.23,.55,.63),wet);
      gl_FragColor=vec4(color,1.0);
    }`;
  const waterVertex = `
    varying vec3 at;
    void main(){at=position;gl_Position=projectionMatrix*viewMatrix*vec4(position,1.0);}`;
  const waterFragment = `
    precision highp float;
    uniform float time;
    uniform int maskCount;
    uniform vec2 masks[${MAX_MASKS * 4}];
    varying vec3 at;
    float edge(vec2 a,vec2 b,vec2 p){vec2 d=b-a,q=p-a;return d.x*q.y-d.y*q.x;}
    void main(){
      for(int i=0;i<${MAX_MASKS};i++){
        if(i<maskCount){
          float a=edge(masks[i*4],masks[i*4+1],at.xz);
          float b=edge(masks[i*4+1],masks[i*4+2],at.xz);
          float c=edge(masks[i*4+2],masks[i*4+3],at.xz);
          float d=edge(masks[i*4+3],masks[i*4],at.xz);
          if((a>=0.0&&b>=0.0&&c>=0.0&&d>=0.0)||(a<=0.0&&b<=0.0&&c<=0.0&&d<=0.0))discard;
        }
      }
      float wave=sin(at.x*.21+at.z*.34+time*.8)*sin(at.z*.19-at.x*.11+time*.2);
      float shine=smoothstep(.77,.91,wave);
      gl_FragColor=vec4(mix(vec3(.36,.67,.75),vec3(.72,.88,.9),shine*.55),.48);
    }`;
  let renderer;
  try { renderer = new THREE.WebGLRenderer({canvas:$('view'),antialias:true,alpha:false}); }
  catch (_) { fail('这个窗口暂时无法启用三维播放，请用支持 WebGL 的浏览器打开此页。'); return; }
  renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 1.75));
  renderer.setClearColor(0xc0dfe5,1);
  const world = new THREE.Scene(), camera = new THREE.OrthographicCamera(-20,20,20,-20,.1,1200);
  const waterMaterial = new THREE.ShaderMaterial({
    uniforms:{time:{value:0},maskCount:{value:0},masks:{value:Array.from({length:MAX_MASKS*4},()=>new THREE.Vector2())}},
    vertexShader:waterVertex,fragmentShader:waterFragment,transparent:true,depthWrite:false,side:THREE.DoubleSide
  });
  const waterGeometry = new THREE.PlaneGeometry(600,600);
  waterGeometry.rotateX(-Math.PI/2);waterGeometry.translate(0,-.015,0);
  const water = new THREE.Mesh(waterGeometry,waterMaterial);water.frustumCulled=false;water.renderOrder=10;world.add(water);
  function embeddedFloats(encoded){
    const binary=atob(encoded),raw=new Uint8Array(binary.length);
    for(let i=0;i<binary.length;i++)raw[i]=binary.charCodeAt(i);
    return new Float32Array(raw.buffer);
  }
  const values = embeddedFloats(data.bufferBase64);
  // Embedded data works on file pages without cross-origin fetch or a local server.
  delete data.bufferBase64;
  const geometries = new Map(), definitions = new Map(data.meshes.map(mesh=>[mesh.id,mesh]));
  for(const mesh of data.meshes){
    const buffer=new THREE.InterleavedBuffer(values.subarray(mesh.vertexOffset,mesh.vertexOffset+mesh.vertexCount*mesh.stride),mesh.stride);
    const geometry=new THREE.BufferGeometry();
    geometry.setAttribute('position',new THREE.InterleavedBufferAttribute(buffer,3,0));
    geometry.setAttribute('uv',new THREE.InterleavedBufferAttribute(buffer,2,3));
    geometry.setAttribute('normal',new THREE.InterleavedBufferAttribute(buffer,3,5));
    geometry.setAttribute('binding',new THREE.InterleavedBufferAttribute(buffer,3,8));
    geometries.set(mesh.id,geometry);
  }
  const textures = new Map(), actors = new Map();
  let sceneIndex=0,clip,tick=0,playing=true,speed=1,lastTime=0,ready=false,enabledSound=false;
  let viewAz=0,viewEl=0,viewZoom=1,drag=null,voice=null,pendingVoice=null,voiceText='',stageButtons=[];
  const timeLabel = seconds => `${Math.floor(seconds/60)}:${Math.floor(seconds%60).toString().padStart(2,'0')}`;
  const lerp = (a,b,t) => a+(b-a)*t;
  const angle = (a,b,t) => a+Math.atan2(Math.sin(b-a),Math.cos(b-a))*t;
  function stopVoice(){if(voice){voice.pause();voice=null;}pendingVoice=null;voiceText='';$('subtitle').replaceChildren();}
  function setPlaying(next){playing=next;$('play').textContent=playing?'暂停':'播放';$('play').setAttribute('aria-label',playing?'暂停播放':'继续播放');if(!next&&voice)voice.pause();else if(next&&voice)voice.play().catch(()=>{});}
  function seek(next){tick=Math.max(0,Math.min(clip.durationTicks,next));stopVoice();draw();}
  function startVoice(cue){
    const recording=window.HAT_AUDIO?.[cue.event];
    if(!enabledSound||!recording?.src)return;
    // Finish the spoken sentence. Keep only the newest nearby reply, so dialogue
    // does not overlap or lag several actions behind the visible contact.
    if(voice){pendingVoice=cue;return;}
    stopVoice();voice=new Audio(recording.src);voice.playbackRate=speed;voiceText=recording.text||cue.text||'';
    const current=voice;current.onended=()=>{
      if(voice!==current)return;
      const next=pendingVoice;stopVoice();
      if(next&&tick-next.tick<=tickRate*2)startVoice(next);
    };
    current.play().catch(()=>{if(voice===current)stopVoice();});
  }
  function choose(index){
    stopVoice();sceneIndex=(index+data.scenes.length)%data.scenes.length;clip=data.scenes[sceneIndex];tick=0;
    viewAz=viewEl=0;viewZoom=1;
    for(const actor of actors.values()){world.remove(actor.mesh);actor.mesh.material.dispose();}actors.clear();
    // Instance IDs and topology stay fixed throughout each continuous scene.
    for(const frame of clip.frames)for(const instance of frame.instances){
      if(actors.has(instance.id))continue;
      const definition=definitions.get(instance.mesh);
      const matrices=Array.from({length:definition.boneCount},()=>new THREE.Matrix4());
      const material=new THREE.ShaderMaterial({uniforms:{bones:{value:matrices},image:{value:textures.get(instance.texture)}},
        vertexShader:vertexShader(definition.boneCount),fragmentShader,side:THREE.DoubleSide});
      const mesh=new THREE.Mesh(geometries.get(instance.mesh),material);mesh.frustumCulled=false;mesh.visible=false;world.add(mesh);
      actors.set(instance.id,{mesh,matrices});
    }
    document.querySelectorAll('[data-scene]').forEach(button=>button.setAttribute('aria-selected',String(Number(button.dataset.scene)===sceneIndex)));
    $('seek').max=clip.durationTicks;$('stages').replaceChildren();stageButtons=[];
    let previous='';
    for(const frame of clip.frames){
      if(frame.stage===previous||!frame.stage)continue;previous=frame.stage;
      const button=document.createElement('button');button.textContent=frame.stage;button.onclick=()=>seek(frame.tick);
      $('stages').append(button);stageButtons.push({button,tick:frame.tick});
    }
    setPlaying(true);draw();
    for(const cue of clip.cues||[])if(cue.tick===0&&cue.event)startVoice(cue);
  }
  function pair(){
    const frames=clip.frames;let lo=0,hi=frames.length-1;
    while(lo<hi){const mid=Math.ceil((lo+hi)/2);if(frames[mid].tick<=tick)lo=mid;else hi=mid-1;}
    const a=frames[lo],b=frames[Math.min(lo+1,frames.length-1)];
    return [a,b,b.tick>a.tick?Math.max(0,Math.min(1,(tick-a.tick)/(b.tick-a.tick))):0];
  }
  function draw(){
    if(!clip)return;
    const [a,b,q]=pair(),next=new Map(b.instances.map(instance=>[instance.id,instance]));
    for(const actor of actors.values())actor.mesh.visible=false;
    for(const instance of a.instances){
      const actor=actors.get(instance.id),end=next.get(instance.id)||instance;
      actor.mesh.visible=instance.visible!==false;if(!actor.mesh.visible)continue;
      actor.mesh.material.uniforms.image.value=textures.get(instance.texture);
      for(let bone=0;bone<actor.matrices.length;bone++){
        const target=actor.matrices[bone].elements,start=instance.bonesOffset+bone*16,finish=end.bonesOffset+bone*16;
        for(let k=0;k<16;k++)target[k]=lerp(values[start+k],values[finish+k],q);
      }
    }
    const detailed=$('detail').checked;
    const ca=detailed&&a.detailCamera?a.detailCamera:a.camera,cb=detailed&&b.detailCamera?b.detailCamera:b.camera;
    const az=angle(ca.az,cb.az,q)+viewAz,el=Math.max(.08,Math.min(1.3,lerp(ca.el,cb.el,q)+viewEl));
    const span=lerp(ca.span,cb.span,q)*viewZoom,target=ca.target.map((value,i)=>lerp(value,cb.target[i],q));
    const rect=$('viewport').getBoundingClientRect(),aspect=rect.width/rect.height;
    camera.left=-span*aspect;camera.right=span*aspect;camera.top=span;camera.bottom=-span;camera.updateProjectionMatrix();
    // Match the authored orthographic right/up basis of the existing offline previews.
    camera.projectionMatrix.elements[0]*=-1;
    camera.position.set(target[0]+Math.sin(az)*Math.cos(el)*250,target[1]+Math.sin(el)*250,target[2]-Math.cos(az)*Math.cos(el)*250);
    camera.lookAt(...target);camera.updateMatrixWorld();
    waterMaterial.uniforms.time.value=tick/tickRate;
    const masks=a.masks||[],nextMasks=b.masks||[];waterMaterial.uniforms.maskCount.value=Math.min(MAX_MASKS,masks.length);
    for(let i=0;i<Math.min(MAX_MASKS,masks.length);i++)for(let j=0;j<4;j++){
      const current=masks[i],end=nextMasks[i]||current;
      waterMaterial.uniforms.masks.value[i*4+j].set(lerp(current[j*3],end[j*3],q),lerp(current[j*3+2],end[j*3+2],q));
    }
    renderer.render(world,camera);
    $('phase').textContent=a.stage||clip.title;$('scene-time').textContent=`${sceneIndex+1} / ${data.scenes.length} · ${clip.title}`;
    $('seek').value=tick;$('time').textContent=`${timeLabel(tick/tickRate)} / ${timeLabel(clip.durationTicks/tickRate)}`;
    let active=-1;stageButtons.forEach((stage,i)=>{if(stage.tick<=tick)active=i;});
    stageButtons.forEach((stage,i)=>stage.button.setAttribute('aria-current',String(i===active)));
    const cue=[...(clip.cues||[])].reverse().find(item=>item.tick<=tick);
    $('description').textContent=cue?.text||clip.description||'';
    if(voiceText){
      if($('subtitle').textContent!==voiceText)$('subtitle').replaceChildren(Object.assign(document.createElement('span'),{textContent:voiceText}));
    }else if(!voice)$('subtitle').replaceChildren();
  }
  function resize(){const rect=$('viewport').getBoundingClientRect();renderer.setSize(Math.max(1,rect.width),Math.max(1,rect.height),false);draw();}
  function animate(time){
    const elapsed=lastTime?Math.min(.1,(time-lastTime)/1000):0;lastTime=time;
    if(ready){
      if(playing&&!document.hidden){
        const before=tick;tick+=elapsed*tickRate*speed;
        for(const cue of clip.cues||[])if(cue.tick>before&&cue.tick<=tick&&cue.event)startVoice(cue);
        if(tick>clip.durationTicks)choose($('sequence').checked?sceneIndex+1:sceneIndex);
      }
      draw();
    }
    requestAnimationFrame(animate);
  }
  $('play').onclick=()=>setPlaying(!playing);$('replay').onclick=()=>{seek(0);setPlaying(true);};
  $('seek').oninput=event=>seek(Number(event.target.value));
  $('speed').onchange=event=>{speed=Number(event.target.value);if(voice)voice.playbackRate=speed;};
  $('detail').onchange=()=>{viewAz=viewEl=0;viewZoom=1;draw();};
  $('reset-view').onclick=()=>{viewAz=viewEl=0;viewZoom=1;draw();};
  $('sound').onclick=()=>{
    enabledSound=!enabledSound;$('sound').setAttribute('aria-pressed',String(enabledSound));$('sound').textContent=enabledSound?'声音已开启':'开启声音';
    if(!enabledSound)stopVoice();
    else {const cue=[...(clip.cues||[])].reverse().find(item=>item.event&&item.tick<=tick&&tick-item.tick<=60);if(cue)startVoice(cue);}
  };
  $('fullscreen').onclick=async()=>{try{if(document.fullscreenElement)await document.exitFullscreen();else await document.querySelector('.player').requestFullscreen();}catch(_){$('description').textContent='这个窗口未启用全屏；可放大预览面板继续观看。';}};
  document.querySelectorAll('[data-scene]').forEach(button=>button.onclick=()=>{if(ready)choose(Number(button.dataset.scene));});
  $('view').addEventListener('pointerdown',event=>{drag={x:event.clientX,y:event.clientY,az:viewAz,el:viewEl};$('view').setPointerCapture(event.pointerId);});
  $('view').addEventListener('pointermove',event=>{if(!drag)return;viewAz=drag.az+(event.clientX-drag.x)*.006;viewEl=drag.el+(event.clientY-drag.y)*.004;draw();});
  for(const name of ['pointerup','pointercancel'])$('view').addEventListener(name,()=>drag=null);
  $('view').addEventListener('wheel',event=>{event.preventDefault();viewZoom=Math.max(.55,Math.min(1.9,viewZoom*Math.exp(event.deltaY*.001)));draw();},{passive:false});
  document.addEventListener('keydown',event=>{
    if(!ready||['INPUT','SELECT','BUTTON'].includes(document.activeElement?.tagName))return;
    if(event.code==='Space'){event.preventDefault();setPlaying(!playing);}
    else if(event.code==='ArrowLeft'){event.preventDefault();seek(tick-tickRate);}
    else if(event.code==='ArrowRight'){event.preventDefault();seek(tick+tickRate);}
  });
  document.addEventListener('visibilitychange',()=>{lastTime=0;if(document.hidden&&voice)voice.pause();else if(playing&&voice)voice.play().catch(()=>{});});
  window.addEventListener('resize',resize);new ResizeObserver(resize).observe($('viewport'));
  Promise.all(Object.entries(data.images).map(async([id,url])=>{
    const texture=await new THREE.TextureLoader().loadAsync(url);texture.flipY=false;
    texture.magFilter=THREE.NearestFilter;texture.minFilter=THREE.NearestFilter;texture.generateMipmaps=false;textures.set(id,texture);
  })).then(()=>{
    ready=true;$('loading').hidden=true;$('play').disabled=$('replay').disabled=$('seek').disabled=false;
    $('sound').disabled=!Object.keys(window.HAT_AUDIO||{}).length;
    const requested=new URLSearchParams(window.location.search).get('scene');
    choose(Math.max(0,data.scenes.findIndex(scene=>scene.id===requested)));resize();requestAnimationFrame(animate);
  }).catch(()=>fail('模型贴图未能载入，请重新打开当前预览页。'));
})();
