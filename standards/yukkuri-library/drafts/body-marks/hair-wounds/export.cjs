/* Export deterministic wound-only meshes; full character models stay untouched. */
'use strict';
const fs=require('fs'),path=require('path'),zlib=require('zlib');
const {createHairWound}=require('./author.cjs');
const root=path.resolve(__dirname,'../../../../..');
const {RuntimePose}=require(path.join(root,'preview/review/pose.js'));
const assets=path.join(root,'src/main/resources/assets/mcyukkuri');
const fits=JSON.parse(fs.readFileSync(path.join(assets,'yukkuri_presentation/placement_fit.json'))).models;
const jobs=JSON.parse(fs.readFileSync(process.argv[2],'utf8')),check=process.argv.includes('--check');
const models=new Map();
function writeGenerated(file,data){
  // Replace atomically so a transient Windows file-filter error cannot leave a
  // truncated asset. Retry only transient errors; every attempt has full bytes.
  const temporary=file+'.tmp-'+process.pid;
  for(let attempt=0;;attempt++){
    try{fs.writeFileSync(temporary,data);fs.renameSync(temporary,file);return;}
    catch(error){
      try{fs.unlinkSync(temporary);}catch{}
      if(attempt>=7||!['UNKNOWN','EBUSY','EPERM'].includes(error.code))throw error;
      Atomics.wait(new Int32Array(new SharedArrayBuffer(4)),0,0,75*(attempt+1));
    }
  }
}
for(const job of jobs){
  if(!models.has(job.model)){
    const model=JSON.parse(fs.readFileSync(path.join(assets,'yukkuri',job.model+'.json'))),pose=new RuntimePose(model,1,null,fits[job.model]||null);
    models.set(job.model,{parts:pose.model.parts,vertices:pose.frame('idle',0)});
  }
  const {parts,vertices}=models.get(job.model);
  const wound=createHairWound(vertices,parts,{...job.spec,model:job.model});
  if(!wound.vertices.every(Number.isFinite))throw Error('Non-finite vertices '+job.id);
  const patched=new Set();
  for(const patch of wound.surfacePatches){
    const part=parts.find(p=>p.name===patch.part);
    if(patched.has(patch.triangle)||!part||patch.triangle<part.start||patch.triangle+3>part.start+part.count||patch.vertices.length%24||!patch.vertices.every(Number.isFinite))throw Error('Invalid receiver patch '+job.id);
    patched.add(patch.triangle);
  }
  if(job.spec.kind==='dent'&&!wound.surfacePatches.length)throw Error('Missing shared-field receiver patches '+job.id);
  const coreTriangles=wound.materials.filter(m=>m==='core').length;
  if(!job.spec.core&&coreTriangles)throw Error('Unexpected core '+job.id);
  if(job.spec.core&&!coreTriangles)throw Error('Missing core '+job.id);
  if(!wound.profile.open&&wound.materials.some(m=>m==='filling'))throw Error('Filling in closed injury '+job.id);
  const result={format:'mcyukkuri.authored-hair-wound.v2',model:job.model,spec:job.spec,
    pose:'idle:0',vertexLayout:'xyz, negative material index minus one, zero, normal xyz',
    twoSided:true,profile:wound.profile,hairSamples:wound.hairSamples,
    vertices:wound.vertices.map(q=>Number(q.toFixed(6))),colors:wound.colors,materials:wound.materials,
    surfaceEdits:wound.surfaceEdits.map(row=>row.map(q=>Number(q.toFixed(6)))),
    surfacePatches:wound.surfacePatches.map(p=>({...p,vertices:p.vertices.map(q=>Number(q.toFixed(6)))})),
    integration:'Static fitted authoring sample; apply the profile to runtime skin/hair and supply actual bone weights. Do not render over an uncut character.'};
  const data=zlib.gzipSync(Buffer.from(JSON.stringify(result)+'\n'),{level:9});
  if(check){if(!fs.readFileSync(job.output).equals(data))throw Error('Stale mesh '+job.id);}
  else {fs.mkdirSync(path.dirname(job.output),{recursive:true});writeGenerated(job.output,data);}
  console.log(`${check?'Verified':'Exported'} ${job.id}: ${wound.materials.length} triangles / ${wound.hairSamples} hair rim samples / ${wound.surfaceEdits.length} indented vertices`);
}
