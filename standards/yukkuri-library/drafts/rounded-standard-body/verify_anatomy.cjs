/* Verify physical hiding, active visibility and the shared independent controller. */
const fs=require('fs'),path=require('path'),vm=require('vm'),crypto=require('crypto');
const here=__dirname,root=path.resolve(here,'../../../..'),lib=path.resolve(here,'../..');
const out=path.join(here,'previews/current'),tmp=path.join(root,'.local/rounded-standard-body/render');
const models=JSON.parse(fs.readFileSync(path.join(out,'preview-data.json')));
const sandbox={Uint8ClampedArray,Float64Array};vm.createContext(sandbox);
vm.runInContext(fs.readFileSync(path.join(lib,'runtime/render.js'),'utf8')+'\n'+fs.readFileSync(path.join(out,'pose.js'),'utf8'),sandbox);
const {AnatomyController}=require(path.join(lib,'runtime/anatomy.js'));
const checks=[],failures=[];
function check(name,value,detail){const c={name,passed:!!value,...detail};checks.push(c);if(!value)failures.push(c);}
function diff(a,b){let n=0;for(let i=0;i<a.length;i+=4)if(a[i]!==b[i]||a[i+1]!==b[i+1]||a[i+2]!==b[i+2]||a[i+3]!==b[i+3])n++;return n;}
function overrides(m,part,amount=1){return Object.fromEntries(m.anatomy.parts.map(p=>{const q=p.id===part?amount:0;return[p.bone,{position:p.offset.map(v=>v*q),...(p.rotation?{rotation:p.rotation.map(v=>v*q)}:{})}];}));}
for(const id of ['reimu','marisa']){
 const m=models[id].model,texture=fs.readFileSync(path.join(tmp,id+'-neutral.rgba'));
 function render(model,action,t,az,ov){return sandbox.renderModel(sandbox.posed(model,action,t,ov),texture,400,400,az,.02,9,{centerY:5,textureSize:256});}
 const absent={...m,parts:m.parts.filter(p=>!p.anatomy)};
 const unsuppressed={...m,parts:m.parts.map(p=>({...p,anatomy:''}))};
 let invisible=0;
 for(const action of ['idle','waddle','hop','stretch','side_bend','startle']){
  const c=m.clips[action];
  for(const phase of [0,.25,.75])for(const az of [0,Math.PI/2,Math.PI,Math.PI*1.5]){
   const delta=diff(render(absent,action,c.duration*phase,az),render(unsuppressed,action,c.duration*phase,az));
   check(id+'/'+action+'/'+phase+'/'+az+'/rest_inside',delta===0,{changedPixels:delta});if(delta===0)invisible++;
  }
 }
 for(const [part,az]of [['peni',.5],['mamu',0]]){
  const delta=diff(render(absent,'idle',0,az),render(m,'idle',0,az,overrides(m,part)));
  check(id+'/'+part+'/visible',delta>5,{changedPixels:delta});
  const anatomical=sandbox.posed({...m,parts:m.parts.filter(p=>p.anatomy===part)},'idle',0,overrides(m,part));
  let minY=Infinity;for(let i=1;i<anatomical.length;i+=8)minY=Math.min(minY,anatomical[i]);
  check(id+'/'+part+'/above_ground',minY>=-1e-5,{minY});
  for(const phase of [.25,.75]){
   const action='side_bend',t=m.clips[action].duration*phase;
   const active=render(m,action,t,az,overrides(m,part));
   check(id+'/'+part+'/side_bend_'+phase,diff(render(absent,action,t,az),active)>0);
  }
 }
 const noMark={...m,parts:m.parts.filter(p=>p.name!=='rounded_rump_mark')};
 check(id+'/anus_visible',diff(render(noMark,'idle',0,Math.PI),render(m,'idle',0,Math.PI))>0);
 const ctrl=new AnatomyController(m.anatomy);check(id+'/controller_default',ctrl.state().amount===0&&ctrl.state().phase==='retracted');
 ctrl.extend('peni');ctrl.update(.24);check(id+'/controller_extend',ctrl.state().part==='peni'&&ctrl.state().amount===1);
 ctrl.selectPart('mamu');ctrl.update(.325);check(id+'/controller_switch_retracts_first',ctrl.state().part==='peni'&&Math.abs(ctrl.state().amount-.5)<1e-9&&ctrl.overrides().anatomy_mamu.position.every(v=>v===0));
 ctrl.update(.325+.24);check(id+'/controller_switch_complete',ctrl.state().part==='mamu'&&Math.abs(ctrl.state().amount-1)<1e-9&&ctrl.overrides().anatomy_peni.position.every(v=>v===0));
 ctrl.retract();ctrl.update(.65);check(id+'/controller_retract',ctrl.state().phase==='retracted'&&ctrl.state().amount===0);
 console.log(id, 'physically hidden renders:',invisible);
}
const sha=p=>crypto.createHash('sha256').update(fs.readFileSync(p)).digest('hex');
const report={createdAt:new Date().toISOString(),status:failures.length?'failed':'passed',previewDataSha256:sha(path.join(out,'preview-data.json')),poseSha256:sha(path.join(out,'pose.js')),checks,failures};
fs.writeFileSync(path.join(here,'reports/current/anatomy.json'),JSON.stringify(report,null,2));
console.log(JSON.stringify({checks:checks.length,failures},null,2));if(failures.length)process.exitCode=1;
