/* Validate the current export and sample every preview action's real skinning. */
const fs=require('fs'),path=require('path'),vm=require('vm'),crypto=require('crypto');
const here=__dirname,out=path.join(here,'previews/current');
const catalog=JSON.parse(fs.readFileSync(path.join(out,'preview-data.json')));
const sandbox={};vm.createContext(sandbox);vm.runInContext(fs.readFileSync(path.join(out,'pose.js'),'utf8'),sandbox);
const results=[];
for(const id of ['reimu','marisa']){
 const e=catalog[id],m=e.model,bb=JSON.parse(fs.readFileSync(path.join(here,'assembled',id,id+'.bbmodel')));
 const rig=JSON.parse(fs.readFileSync(path.join(here,'assembled',id,'animation-rig.json')));
 if(m.vertices.length!==m.weights.length*8||rig.groups.length!==bb.elements.length)throw new Error('Geometry/binding count mismatch');
 if(m.weights.some(([a,b,q])=>a<0||b<0||a>=m.bones.length||b>=m.bones.length||q<0||q>1))throw new Error('Invalid weights');
 const body=m.parts.find(p=>p.name==='rounded_body_surface');
 if(!body||m.parts.some(p=>/^(soft_head|upper_rear|left_cheek|right_cheek|rump_core|rump_round_end)_slice_/.test(p.name)))throw new Error('Old body remains');
 const actionResults=[];
 for(const [action,c]of Object.entries(m.clips)){
  let low=[Infinity,Infinity,Infinity],high=[-Infinity,-Infinity,-Infinity];
  const hashes=new Set();
  for(let i=0;i<=24;i++){
   const p=sandbox.posed(m,action,c.duration*i/24);
   if(p.some(x=>!Number.isFinite(x)))throw new Error('Nonfinite pose '+id+'/'+action);
   hashes.add(crypto.createHash('sha256').update(JSON.stringify(p)).digest('hex'));
   for(let j=0;j<p.length;j+=8)for(let k=0;k<3;k++){low[k]=Math.min(low[k],p[j+k]);high[k]=Math.max(high[k],p[j+k]);}
  }
  if(action!=='idle'&&hashes.size<3)throw new Error('Static action '+action);
  actionResults.push({action,samples:25,distinctPoses:hashes.size,min:low,max:high});
 }
 results.push({id,elements:bb.elements.length,vertices:m.weights.length,actions:actionResults});
}
const fingerprint=p=>crypto.createHash('sha256').update(fs.readFileSync(p)).digest('hex');
const report={status:'passed',createdAt:new Date().toISOString(),manifestSha256:fingerprint(path.join(here,'manifest.json')),previewDataSha256:fingerprint(path.join(out,'preview-data.json')),scope:'geometry counts, bone weights, removed old body, finite and changing poses',models:results};
fs.mkdirSync(path.join(here,'reports/current'),{recursive:true});fs.writeFileSync(path.join(here,'reports/current/structure.json'),JSON.stringify(report,null,2));
console.log(JSON.stringify(report,null,2));
