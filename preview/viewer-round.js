/* Native 3D mesh preview. Everything shown is exported in the editable assets. */
const canvas=document.querySelector('canvas');
const renderer=new THREE.WebGLRenderer({canvas,antialias:true,preserveDrawingBuffer:true});
renderer.setPixelRatio(Math.min(devicePixelRatio,2));renderer.outputEncoding=THREE.sRGBEncoding;renderer.toneMapping=THREE.ACESFilmicToneMapping;renderer.toneMappingExposure=1;
renderer.shadowMap.enabled=true;renderer.shadowMap.type=THREE.PCFSoftShadowMap;
const scene=new THREE.Scene();scene.background=new THREE.Color('#ebe9e2').convertSRGBToLinear();
const camera=new THREE.OrthographicCamera(-33,33,22,-22,.1,250);camera.position.set(0,25,85);camera.lookAt(0,7.7,0);
scene.add(new THREE.HemisphereLight(0xfff9ee,0xc4b7a2,.83));
const light=new THREE.DirectionalLight(0xfffbf3,.67);light.position.set(-25,45,42);light.castShadow=true;
light.shadow.mapSize.set(2048,2048);Object.assign(light.shadow.camera,{left:-42,right:42,top:32,bottom:-32,far:140});light.shadow.bias=-.0002;light.shadow.normalBias=.02;scene.add(light);
const gradient=new THREE.DataTexture(new Uint8Array([110,190,245]),3,1,THREE.LuminanceFormat);gradient.minFilter=gradient.magFilter=THREE.NearestFilter;gradient.needsUpdate=true;
const colors={skin:'#ffdda1',hair:'#35291e',hair_light:'#48382a',hair_dark:'#291f19',gold:'#ffd326',gold_light:'#ffe35d',gold_dark:'#eba510',hat:'#28282a',hat_light:'#333335',white:'#fffdf1',white_shadow:'#e0dfd8',red:'#ec202a',red_light:'#fa4144',red_dark:'#b71822',ink:'#30251f'};
function material(color){return new THREE.MeshLambertMaterial({color:new THREE.Color(color).convertSRGBToLinear(),side:THREE.DoubleSide})}
const ground=new THREE.Mesh(new THREE.PlaneGeometry(400,400),new THREE.MeshLambertMaterial({color:new THREE.Color('#ebe9e2').convertSRGBToLinear()}));ground.rotation.x=-Math.PI/2;ground.position.y=-.7;ground.receiveShadow=true;scene.add(ground);
let groups=[],meshes=[],outlines=[],yaw=-.2,pitch=0,zoom=1,spin=false,wire=false;
const outlineMat=new THREE.MeshBasicMaterial({color:'#30271e',side:THREE.BackSide});
outlineMat.onBeforeCompile=s=>{s.vertexShader=s.vertexShader.replace('#include <begin_vertex>','vec3 transformed = vec3(position) + normalize(normal) * 0.033;')};
for(let i=0;i<ROUND_MODELS.length;i++){
 const model=ROUND_MODELS[i],g=new THREE.Group();g.position.x=(i-.5)*28;scene.add(g);groups.push(g);
 const disk=new THREE.Mesh(new THREE.CylinderGeometry(12,12.2,.35,80),new THREE.MeshLambertMaterial({color:new THREE.Color('#d9dccc').convertSRGBToLinear()}));disk.position.set(g.position.x,-.30,0);disk.receiveShadow=true;scene.add(disk);
 const face=new THREE.TextureLoader().load(model.texture);face.encoding=THREE.sRGBEncoding;face.minFilter=THREE.LinearMipmapLinearFilter;face.magFilter=THREE.LinearFilter;face.anisotropy=renderer.capabilities.getMaxAnisotropy();
 for(const m of model.meshes){
  const geo=new THREE.BufferGeometry();geo.setAttribute('position',new THREE.Float32BufferAttribute(m.vertices.flat(),3));geo.setAttribute('uv',new THREE.Float32BufferAttribute(m.uv.flat(),2));geo.setIndex(m.faces.flat());geo.computeVertexNormals();
  const mat=m.color==='face'?new THREE.MeshLambertMaterial({map:face,side:THREE.DoubleSide}):material(colors[m.color]);
  const mesh=new THREE.Mesh(geo,mat);mesh.name=m.name;mesh.castShadow=true;mesh.receiveShadow=false;g.add(mesh);meshes.push(mesh);
  // Thin ink silhouettes, with open-mesh boundary lines for the pointed fringe.
  const outline=new THREE.Mesh(geo,outlineMat);g.add(outline);outlines.push(outline);
  if(m.name.includes('pointed_bang')){const line=new THREE.LineSegments(new THREE.EdgesGeometry(geo,70),new THREE.LineBasicMaterial({color:model.kind==='marisa'?'#be850e':'#281c16'}));g.add(line);outlines.push(line)}
 }
}
function resize(){const w=innerWidth,h=innerHeight;renderer.setSize(w,h);const span=Math.max(66,42*w/h)/zoom;camera.left=-span/2;camera.right=span/2;camera.top=span*h/w/2;camera.bottom=-span*h/w/2;camera.updateProjectionMatrix()}
addEventListener('resize',resize);resize();
function view(v){yaw=({three:-.2,front:0,side:-Math.PI/2,back:Math.PI})[v];pitch=0;camera.position.set(0,v==='front'?8:25,85);camera.lookAt(0,v==='front'?8:7.7,0);spin=false;document.querySelector('#spin').classList.remove('active');document.querySelectorAll('[data-view]').forEach(b=>b.classList.toggle('active',b.dataset.view===v))}
document.querySelectorAll('[data-view]').forEach(b=>b.onclick=()=>view(b.dataset.view));
document.querySelector('#spin').onclick=e=>{spin=!spin;e.target.classList.toggle('active',spin)};
document.querySelector('#wire').onclick=e=>{wire=!wire;e.target.classList.toggle('active',wire);meshes.forEach(m=>m.material.wireframe=wire);outlines.forEach(m=>m.visible=!wire)};
let drag=null;canvas.onpointerdown=e=>{drag=[e.clientX,e.clientY];canvas.setPointerCapture(e.pointerId)};canvas.onpointerup=()=>drag=null;canvas.onpointercancel=()=>drag=null;
canvas.onpointermove=e=>{if(drag){yaw+=(e.clientX-drag[0])*.009;pitch=Math.max(-.5,Math.min(.5,pitch+(e.clientY-drag[1])*.006));drag=[e.clientX,e.clientY]}};
canvas.onwheel=e=>{e.preventDefault();zoom=Math.max(.65,Math.min(1.6,zoom-e.deltaY*.0007));resize()};
const requested=new URLSearchParams(location.search).get('view');if(['front','three','side','back'].includes(requested))view(requested);
let previous=performance.now();function render(now){if(spin)yaw+=(now-previous)*.0003;previous=now;groups.forEach(g=>{g.rotation.y=yaw;g.rotation.x=pitch});renderer.render(scene,camera);requestAnimationFrame(render)}requestAnimationFrame(render);window.previewReady=true;
