// Compare mixed-element poses with independent 36-vertex chunks on each bone.
const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict'),crypto=require('node:crypto');
const {poseVertices}=require('../../runtime/motion.js');
const html=fs.readFileSync(path.join(__dirname,'index.html'),'utf8');
const raw=html.split('const DATA=')[1].split(/;\r?\n/)[0],data=JSON.parse(raw);
let comparisons=0;
for(const model of Object.values(data.characters)){
 for(const [clip,animation] of Object.entries(model.rig.clips)){
  for(const time of [0,animation.duration*.24,animation.duration*.71]){
   const actual=poseVertices(model.vertices,model.rig,time,true,false,clip);let start=0;
   for(let e=0;e<model.rig.groups.length;e++){
    const length=model.rig.element_vertex_counts[e]*8,block=model.vertices.slice(start,start+length);
    const chunks=Math.ceil(block.length/288);
    while(block.length<chunks*288)block.push(...block.slice(-8));
    const rig={...model.rig,groups:Array(chunks).fill(model.rig.groups[e])};delete rig.element_vertex_counts;
    const expected=poseVertices(block,rig,time,true,false,clip);
    for(let i=0;i<length;i++)assert.ok(Number.isFinite(actual[start+i])&&Math.abs(actual[start+i]-expected[i])<1e-10,`${clip} element ${e} offset ${i}`);
    start+=length;comparisons++;
   }
   assert.equal(start,actual.length);
  }
 }
}
for(const model of Object.values(data.ordinaryCharacters)){
 const rig={...model.rig,element_vertex_counts:model.rig.groups.map(()=>36)};
 assert.deepEqual(poseVertices(model.vertices,rig,.24,true,false,'idle'),poseVertices(model.vertices,model.rig,.24,true,false,'idle'));
}
const sha=p=>crypto.createHash('sha256').update(fs.readFileSync(p)).digest('hex');
const report={executed_utc:new Date().toISOString(),environment:{node:process.version,platform:process.platform},mixed_element_comparisons:comparisons,legacy_cube_rigs_equal:true,scope:'All exported character clips at three sample times; per-element poses compared against fixed 36-vertex chunks.',motion_sha256:sha(path.join(__dirname,'../../runtime/motion.js')),index_sha256:sha(path.join(__dirname,'index.html'))};
fs.writeFileSync(path.join(__dirname,'reports/current/motion-consistency.json'),JSON.stringify(report,null,2)+'\n');
console.log(JSON.stringify(report));
