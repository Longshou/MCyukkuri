const fs=require('fs'),path=require('path'),vm=require('vm'),crypto=require('crypto');
const here=__dirname,lib=path.resolve(here,'..'),root=path.resolve(here,'../../..'),out=path.join(root,'.local/previews/body-standard'),reports=path.join(root,'.local/reports/body-standard'),tmp=path.join(root,'.local/previews/_work/body-standard/standard-render');
const box={Uint8ClampedArray,Float64Array};vm.createContext(box);
for(const p of [path.join(lib,'runtime/render.js'),path.join(out,'pose.js'),path.join(lib,'../yukkuri-v1/runtime/growth.js'),path.join(lib,'../yukkuri-v1/runtime/viviparous-birth.js'),path.join(here,'runtime/birth.js')])vm.runInContext(fs.readFileSync(p,'utf8'),box);
const api=vm.runInContext('RoundedBirth',box),catalog=JSON.parse(fs.readFileSync(path.join(out,'preview-data.json'))),checks=[];
const check=(name,passed,detail={})=>checks.push({name,passed:!!passed,...detail});
const hash=p=>crypto.createHash('sha256').update(fs.readFileSync(p)).digest('hex');
const bounds=v=>[0,1,2].map(k=>{let min=Infinity,max=-Infinity;for(let i=k;i<v.length;i+=8){min=Math.min(min,v[i]);max=Math.max(max,v[i]);}return [min,max];});
for(const id of Object.keys(catalog).filter(id=>!process.argv[2]||id===process.argv[2])){
 const m=catalog[id].model,site=m.anatomy.surface_site,anchor=m.birth.viviparous.birth_outlet.anchor;
 check(id+'/shared_site',Math.abs(site[0]-anchor[0])<1e-9&&Math.abs(site[1]-anchor[1])<1e-9&&Math.abs(site[2]-anchor[2]-.05*(m.overall_scale||1))<1e-9);
 const peni=m.bones.find(b=>b.name==='anatomy_peni'),travel=m.anatomy.parts.find(p=>p.id==='peni').offset;
 check(id+'/axis_surface_intersection',Math.abs(site[1]-peni.pivot[1]-Math.tan(25*Math.PI/180)*(peni.pivot[2]+travel[2]-site[2]))<1e-7);
 for(const count of [1,3,5]){
  let born=0,contacts=0;
  for(const t of [0,9,13,14,14.3,14.9,15.5,15.8,16.5,17.3,18.2,19.5,20.6,21.9,23,24.9,26.5,29,32]){
   const s=api.geometry(m,t,count),state=s.state;
   check(id+'/'+count+'/'+t+'/finite',s.batches.every(b=>b.vertices.every(Number.isFinite)));
   check(id+'/'+count+'/'+t+'/birth_order',state.born>=born&&state.born<=count);born=state.born;
   check(id+'/'+count+'/'+t+'/identity',state.children.every(c=>c.species===id&&c.scale===.18)&&new Set(state.children.map(c=>c.id)).size===count);
   check(id+'/'+count+'/'+t+'/ground',s.batches.filter(b=>b.kind==='child'&&b.vertices.length).every(b=>bounds(b.vertices)[1][0]>=-1e-7));
   if(s.contact)contacts++;
   if(t===0)check(id+'/'+count+'/initial_clear',s.batches.length===1&&s.hole.length===0&&state.belly===0);
   if(t===32){
    check(id+'/'+count+'/complete',state.born===count&&state.children.every(c=>c.landed)&&state.belly===0&&s.hole.length===0);
    const children=s.batches.filter(b=>b.kind==='child').map(b=>bounds(b.vertices));
    check(id+'/'+count+'/newborn_spacing',children.every((a,i)=>children.every((b,j)=>i===j||a[0][1]<b[0][0]||b[0][1]<a[0][0]||a[2][1]<b[2][0]||b[2][1]<a[2][0])));
   }
  }
  check(id+'/'+count+'/contact_sampled',contacts>0);
 }
 const tex=fs.readFileSync(path.join(tmp,id+'-neutral.rgba'));
 let smallestGap=Infinity,pregnantSamples=0;
 for(let time=0;time<=26;time+=.5){
  const state=api.sample(m,time,3);
  // Rest keeps the original mesh, including transparent chin margins. Its
  // visible pixels are checked separately below; fit clearance applies to the
  // pregnancy adapter while it is active.
  if(state.belly===0)continue;
  pregnantSamples++;
  const face=api.maternalGeometry(m,state,true).face;
  for(let i=0;i<face.length;i+=24){
   const t=[face.slice(i,i+3),face.slice(i+8,i+11),face.slice(i+16,i+19)];
   for(const q of [[1,0,0],[0,1,0],[0,0,1],[1/3,1/3,1/3],[.5,.5,0],[0,.5,.5],[.5,0,.5]]){
    const y=t.reduce((s,p,k)=>s+p[1]*q[k],0),z=t.reduce((s,p,k)=>s+p[2]*q[k],0);
    smallestGap=Math.min(smallestGap,api.skinZ(m,state,y)-z);
   }
  }
 }
 check(id+'/face_clearance_through_pregnancy',smallestGap>=.03,{minimumClearance:smallestGap,sampledTimes:pregnantSamples,triangleSamples:7});
 for(const time of [0,3,6,9,13,19])for(const angle of [-.6,0,.6]){
  const state=api.sample(m,time,3),geometry=api.maternalGeometry(m,state,true);
  const opts={centerY:6,textureSize:m.texture_size};
  const isolated=box.renderModel(geometry.face,tex,400,400,angle,.06,9,opts);
  const combined=box.renderModel(geometry.vertices,tex,400,400,angle,.06,9,opts);
  let occluded=0,painted=0;
  for(let i=0;i<isolated.length;i+=4)if(isolated[i+3]){painted++;if([0,1,2,3].some(k=>isolated[i+k]!==combined[i+k]))occluded++;}
  check(id+'/face_pixels_'+time+'_'+angle,painted>100&&occluded===0,{paintedPixels:painted,occludedPixels:occluded});
 }
 const before=box.renderModel(box.posed(m,'idle',0),tex,360,360,.4,.1,14,{centerY:9,textureSize:m.texture_size});
 const birth=api.render(m,{neutral:tex,angry:tex,sleep:tex},0,3,360,360,.4,.1,14,{centerY:9}).pixels;
 let different=0;for(let i=0;i<before.length;i++)if(before[i]!==birth[i])different++;
 check(id+'/neutral_render_matches',different===0,{differentChannels:different});
 // Seeking is a pure reconstruction, with no cumulative deformation or duplicate births.
 const signature=t=>crypto.createHash('sha256').update(JSON.stringify(api.geometry(m,t,3))).digest('hex');
 const expected=signature(14.9);signature(32);signature(0);check(id+'/seek_repeatable',signature(14.9)===expected);
 console.log(id,'birth checks completed');
}
const failures=checks.filter(c=>!c.passed);
const report={createdAt:new Date().toISOString(),status:failures.length?'failed':'passed',inputs:{previewData:hash(path.join(out,'preview-data.json')),birthAdapter:hash(path.join(here,'runtime/birth.js'))},checks,failures};
fs.mkdirSync(reports,{recursive:true});fs.writeFileSync(path.join(reports,(process.argv[2]?process.argv[2]+'-':'')+'birth.json'),JSON.stringify(report,null,2));
console.log(JSON.stringify({checks:checks.length,failures},null,2));if(failures.length)process.exitCode=1;
