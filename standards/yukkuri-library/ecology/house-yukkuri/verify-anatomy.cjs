// Check attachment against the exported body, including the controller's rest pose.
const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict'),crypto=require('node:crypto');
const {poseVertices}=require('../../runtime/motion.js');
const {AnatomyController}=require('../../runtime/anatomy.js');
const html=fs.readFileSync(path.join(__dirname,'index.html'),'utf8');
const data=JSON.parse(html.split('const DATA=')[1].split(/;\r?\n/)[0]);
const result={};
for(const [species,model] of Object.entries(data.characters)){
 const bb=JSON.parse(fs.readFileSync(path.join(__dirname,'assembled','house_'+species+'.bbmodel'),'utf8'));
 const body=bb.elements.find(e=>e.name==='house_body_surface');
 const rows=Object.values(body.vertices).filter((_,i)=>i%8===2).map(p=>[p[1],p[2]]);
 const rear=y=>{for(let i=1;i<rows.length;i++){const [a,r]=rows[i-1],[b,s]=rows[i];if(y<=b)return r+(s-r)*(y-a)/(b-a);}throw Error('Outside body height');};
 let start=0;
 const ranges=model.rig.element_vertex_counts.map(n=>{const a=start;start+=n*8;return [a,start];});
 const points=(v,prefix)=>bb.elements.flatMap((e,i)=>{
  if(!e.name.startsWith(prefix))return [];
  const [a,b]=ranges[i],out=[];for(let j=a;j<b;j+=8)out.push(v.slice(j,j+3));return out;
 });
 const controller=new AnatomyController(data.anatomy);
 const current=()=>poseVertices(model.vertices,{...model.rig,boneOverrides:controller.overrides()},0,true,false,'idle');
 const rest=current(),mamuRest=points(rest,'mamu_'),peniRest=points(rest,'peni_');
 assert.ok(Math.max(...mamuRest.map(p=>p[2]-rear(p[1])))<-.05,'Rear part must be inside at rest');
 assert.ok(Math.min(...peniRest.map(p=>p[2]))>-1.48+.10,'Front part must be inside at rest');
 const metrics={};
 for(const part of ['peni','mamu']){
  controller.extend(part);controller.update(2);
  const extended=current();
  const clip='anatomy_'+part+'_extend',duration=model.rig.clips[clip].duration;
  const exported=poseVertices(model.vertices,model.rig,duration,true,false,clip);
  assert.ok(extended.every((v,i)=>Math.abs(v-exported[i])<1e-8),'Controller and exported endpoint differ');
  if(part==='peni'){
   const all=points(extended,'peni_'),root=points(extended,'peni_taper_00');
   const reach=-1.48-Math.min(...all.map(p=>p[2]));
   assert.ok(reach>1&&reach<1.3,'Visible reach must fit the current body-width ratio');
   assert.ok(Math.max(...root.map(p=>p[2]))>-1.48&&Math.min(...root.map(p=>p[2]))<-1.48,'Root must cross the belly surface');
   metrics.front_visible_reach=reach;
  }else{
   const mark=points(extended,'mamu_mark'),base=points(extended,'mamu_base');
   const gaps=mark.map(p=>p[2]-rear(p[1]));
   assert.ok(Math.min(...gaps)>0&&Math.max(...gaps)<.02,'Rear marking must lie just outside the belly');
   assert.ok(Math.min(...base.map(p=>p[2]-rear(p[1])))<-.05,'Rear base must remain embedded');
   metrics.rear_mark_clearance=[Math.min(...gaps),Math.max(...gaps)];
  }
  controller.retract();controller.update(2);
  assert.deepEqual(current(),rest,'Retracting must return both parts completely to rest');
 }
 result[species]=metrics;
}
const sha=p=>crypto.createHash('sha256').update(fs.readFileSync(p)).digest('hex');
const report={executed_utc:new Date().toISOString(),environment:{node:process.version,platform:process.platform},design:data.config.design,index_sha256:sha(path.join(__dirname,'index.html')),checks:result,rest_hidden:true,roots_embedded:true,controller_export_endpoints_equal:true,scope:'Current adult geometry in idle pose; direct exported clips and controller extension/retraction endpoints.'};
fs.writeFileSync(path.join(__dirname,'reports/current/anatomy-consistency.json'),JSON.stringify(report,null,2)+'\n');
console.log(JSON.stringify(report));
