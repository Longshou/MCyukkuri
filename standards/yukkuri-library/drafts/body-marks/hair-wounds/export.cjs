/* Export deterministic wound-only meshes; full character models stay untouched. */
'use strict';
const fs=require('fs'),path=require('path'),zlib=require('zlib');
const {createHairWound}=require('./author.cjs');
const root=path.resolve(__dirname,'../../../../..');
const {RuntimePose}=require(path.join(root,'preview/review/pose.js'));
const assets=path.join(root,'src/main/resources/assets/mcyukkuri');
const fits=JSON.parse(fs.readFileSync(path.join(assets,'yukkuri_presentation/placement_fit.json'))).models;
const jobs=JSON.parse(fs.readFileSync(process.argv[2],'utf8')),check=process.argv.includes('--check');
for(const job of jobs){
  const model=JSON.parse(fs.readFileSync(path.join(assets,'yukkuri',job.model+'.json')));
  const pose=new RuntimePose(model,1,null,fits[job.model]||null),vertices=pose.frame('idle',0);
  const wound=createHairWound(vertices,pose.model.parts,{...job.spec,model:job.model});
  if(!wound.vertices.every(Number.isFinite))throw Error('Non-finite vertices '+job.id);
  const coreTriangles=wound.materials.filter(m=>m==='core').length;
  if((job.spec.state==='empty'||job.spec.kind==='tear')&&coreTriangles)throw Error('Unexpected core '+job.id);
  const result={format:'mcyukkuri.authored-hair-wound.v1',model:job.model,spec:job.spec,
    pose:'idle:0',vertexLayout:'xyz, negative material index minus one, zero, normal xyz',
    twoSided:true,profile:wound.profile,hairSamples:wound.hairSamples,
    vertices:wound.vertices.map(q=>Number(q.toFixed(6))),colors:wound.colors,materials:wound.materials,
    surfaceEdits:wound.surfaceEdits.map(row=>row.map(q=>Number(q.toFixed(6)))),
    integration:'Static fitted authoring sample; apply the profile to runtime skin/hair and supply actual bone weights. Do not render over an uncut character.'};
  const data=zlib.gzipSync(Buffer.from(JSON.stringify(result)+'\n'),{level:9});
  if(check){if(!fs.readFileSync(job.output).equals(data))throw Error('Stale mesh '+job.id);}
  else {fs.mkdirSync(path.dirname(job.output),{recursive:true});fs.writeFileSync(job.output,data);}
  console.log(`${check?'Verified':'Exported'} ${job.id}: ${wound.materials.length} triangles / ${wound.hairSamples} hair rim samples / ${wound.surfaceEdits.length} indented vertices`);
}
