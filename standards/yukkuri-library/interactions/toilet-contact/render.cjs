/* Offline selected pose render. No browser, game or GPU automation. */
const fs=require('fs'),path=require('path'),vm=require('vm');
const root=path.resolve(__dirname,'../../../..'),out=path.join(root,'.local/previews/toilet-contact');
const {RuntimePose}=require(path.join(root,'preview/review/pose.js'));
const raster={};vm.runInNewContext(fs.readFileSync(path.join(root,'standards/yukkuri-library/runtime/render.js'),'utf8'),raster);
const revisions=JSON.parse(fs.readFileSync(path.join(root,'src/main/resources/assets/mcyukkuri/yukkuri_presentation/toilet_contact.json'),'utf8')).models;
const fits=JSON.parse(fs.readFileSync(path.join(root,'src/main/resources/assets/mcyukkuri/yukkuri_presentation/placement_fit.json'),'utf8')).models;
const models={};
for(const task of JSON.parse(fs.readFileSync(path.join(out,'tasks.json'),'utf8'))){
 if(!models[task.model])models[task.model]=JSON.parse(fs.readFileSync(path.join(root,`src/main/resources/assets/mcyukkuri/yukkuri/${task.model}.json`),'utf8'));
 const pose=new RuntimePose(models[task.model],1,revisions[task.model],fits[task.model]);pose.expression(task.face);
 const vertices=pose.frame(task.action,task.time);let min=Infinity,max=-Infinity,radius=0;
 for(let i=0;i<vertices.length;i+=8){min=Math.min(min,vertices[i+1]);max=Math.max(max,vertices[i+1]);radius=Math.max(radius,Math.hypot(vertices[i],vertices[i+2]));}
 const raw=fs.readFileSync(path.join(out,task.texture));const size=Math.sqrt(raw.length/4);
 const image=raster.renderModel(vertices,raw,420,310,.25,.16,Math.max((max-min)*.66,radius*.95,4),{centerY:(min+max)/2,textureSize:size});
 fs.writeFileSync(path.join(out,task.id+'.rgba'),Buffer.from(image));
}
console.log('Rendered 21 current runtime poses.');
// Adult/0.2-scale child care pose at the service contact peak, using the game's pitch budget.
{
 const rawModel=id=>models[id]||JSON.parse(fs.readFileSync(path.join(root,`src/main/resources/assets/mcyukkuri/yukkuri/${id}.json`),'utf8'));
 const parent=new RuntimePose(rawModel('marisa'),1,revisions.marisa,fits.marisa),child=new RuntimePose(rawModel('reimu'),1,revisions.reimu,fits.reimu);
 parent.expression('chewing');child.expression('puff');
 const anchor=JSON.parse(fs.readFileSync(path.join(root,'src/main/resources/assets/mcyukkuri/yukkuri_presentation/living.json'),'utf8')).variants.marisa.mouth;
 const childScale=.2,childZ=-((.8+.8*childScale)*.5+.05)*16,target=[0,.04*16,childZ+.8*childScale*.55*16];
 const floorOf=pose=>{let floor=Infinity;for(const part of pose.model.parts)if(part.name.includes('body_surface'))for(let i=part.start;i<part.start+part.count;i++)floor=Math.min(floor,pose.output[i*8+1]);return floor;};
 parent.frame('first_toilet_assist',.225);const floor=floorOf(parent),basePitch=parent.rootPose[3];let low=0,high=28+basePitch;
 for(let i=0;i<7;i++){const mid=(low+high)/2;parent.frame('first_toilet_assist',.225,{pitch:-mid});if(parent.rootPoint(anchor)[1]+Math.max(0,floor-floorOf(parent))>target[1]+.035*16)low=mid;else high=mid;}
 parent.frame('first_toilet_assist',.225,{pitch:-high});let mouth=parent.rootPoint(anchor);const dz=Math.max(-.18*16,Math.min(.18*16,target[2]+.07*16-mouth[2]));
 parent.frame('first_toilet_assist',.225,{pitch:-high,translation:[0,0,dz]});const lift=Math.max(0,floor-floorOf(parent));
 const pv=parent.frame('first_toilet_assist',.225,{pitch:-high,translation:[0,lift,dz]});mouth=parent.rootPoint(anchor);
 const cv=child.frame('first_toilet_stimulated',.225),vertices=[];
 function add(v,scale,z,col,row){for(let i=0;i<v.length;i+=8)vertices.push(v[i]*scale,v[i+1]*scale,v[i+2]*scale+z,(v[i+3]+col)/2,(v[i+4]+row)/2,v[i+5],v[i+6],v[i+7]);}
 add(pv,1,0,0,0);add(cv,childScale,childZ,1,0);
 const delta=target.map((x,i)=>x-mouth[i]),distance=Math.hypot(...delta),direction=delta.map(x=>x/distance),side=[1,0,0],up=[0,-direction[2],direction[1]],rings=[];
 if(distance<=.22*16){
  for(let r=0;r<7;r++){const t=r/6,taper=r===6?.08:1-.25*t,centre=mouth.map((x,i)=>x+t*delta[i]-.008*16*Math.sin(Math.PI*t)*up[i]);rings.push(Array.from({length:8},(_,k)=>centre.map((x,i)=>x+Math.cos(k*Math.PI/4)*.045*8*taper*side[i]+Math.sin(k*Math.PI/4)*.012*8*taper*up[i])));}
  for(let r=0;r<6;r++)for(let k=0;k<8;k++){const n=(k+1)%8;for(const tri of [[rings[r][k],rings[r+1][k],rings[r+1][n]],[rings[r][k],rings[r+1][n],rings[r][n]]]){const a=tri[1].map((x,i)=>x-tri[0][i]),b=tri[2].map((x,i)=>x-tri[0][i]),normal=[a[1]*b[2]-a[2]*b[1],a[2]*b[0]-a[0]*b[2],a[0]*b[1]-a[1]*b[0]],length=Math.hypot(...normal);for(const p of tri)vertices.push(...p,.25,.75,...normal.map(x=>x/length));}}
 }
 const texture=fs.readFileSync(path.join(out,'contact-atlas.texture')),size=Math.sqrt(texture.length/4);
 const image=raster.renderModel(vertices,texture,1340,650,1.08,.13,14,{centerY:10,textureSize:size});
 fs.writeFileSync(path.join(out,'contact-scene.rgba'),Buffer.from(image));
 console.log(`Adult/0.2 child tongue reach ${(distance/16).toFixed(4)} blocks; authored limit .22.`);
}
