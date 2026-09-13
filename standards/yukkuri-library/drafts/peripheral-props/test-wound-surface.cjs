const fs=require('fs'),path=require('path'),assert=require('assert/strict'),crypto=require('crypto'),W=require('./wound-surface.js');
const input=JSON.parse(fs.readFileSync(path.join(__dirname,'wound-source.json'),'utf8')),checks=[];
const distance=(a,b)=>Math.hypot(...a.map((x,i)=>x-b[i]));
function part(s,name){const p=s.parts.find(x=>x.name===name);return s.vertices.slice(p.start*8,(p.start+p.count)*8);}
for(const species of ['reimu','marisa'])for(const side of Object.keys(W.sides)){
 const r=W.defaults();r.species=species;r.wounds[0]={...r.wounds[0],side,u:0,v:['top','bottom'].includes(side)?0:-.7};const s=W.scene(input,r),a=s.anchors[0];
 assert(a&&a.side===side);assert(Math.abs(a.weights.reduce((v,[,w])=>v+w,0)-1)<1e-8);
 const hit=W.pick(s,a.position.map((x,i)=>x+a.normal[i]*2),a.normal.map(x=>-x));assert(hit&&hit.side===side,`${species} ${side} pick binding`);
 assert(Math.abs(hit.u-r.wounds[0].u)<.015&&Math.abs(hit.v-r.wounds[0].v)<.015,`${species} ${side} inverse hit`);
}
checks.push('two actors: six surface locations and inverse hit positioning');
const recipe=W.defaults();recipe.wounds.push({...recipe.wounds[0],id:'other',side:'back',u:.3,v:-.72,kind:'scratch'});
const before=W.scene(input,recipe);recipe.wounds[0].severity=3;recipe.wounds[0].quantity=5;const after=W.scene(input,recipe);
assert.deepEqual(part(before,'wound_other'),part(after,'wound_other'));
recipe.wounds[0].u=.6;const moved=W.scene(input,recipe);assert(distance(moved.anchors[0].position,after.anchors[0].position)>3);assert.deepEqual(moved.anchors[1],after.anchors[1]);
recipe.wounds[0].quantity=0;const zero=W.scene(input,recipe);assert(!zero.parts.some(p=>p.woundId===recipe.wounds[0].id&&p.unit>0));
recipe.wounds=[];const clear=W.scene(input,recipe);assert.equal(clear.anchors.length,0);assert(!clear.parts.some(p=>p.woundId));checks.push('independent placement/severity/quantity and clear-all');
for(const action of ['none','side_bend','stretch_shape','waddle','press']){
 const r=W.defaults();r.action=action;r.phase=.4;const s=W.scene(input,r),shared=new Map();
 for(const tri of s.picking)tri.rest.forEach((p,i)=>{const k=p.map(v=>v.toFixed(5)).join(',');if(shared.has(k))assert(distance(shared.get(k),tri.points[i])<1e-5,'Skin seam '+action);else shared.set(k,tri.points[i]);});
 const a=s.anchors[0],hit=W.pick(s,a.position.map((x,i)=>x+a.normal[i]*1),a.normal.map(x=>-x));assert(hit&&Math.abs(hit.u-r.wounds[0].u)<.04&&Math.abs(hit.v-r.wounds[0].v)<.04,'Moved emitter detached '+action);
}
checks.push('shared skin vertices remain welded through four source clips; outlet follows posed skin');
const r=W.defaults();r.wounds[0].quantity=4;r.wounds[0].mode=2;
for(const flight of [0,.5,1]){
 r.flight=flight;r.scale=1;const a=W.scene(input,r);r.scale=.3;const b=W.scene(input,r);assert.equal(a.vertices.length,b.vertices.length);a.vertices.forEach((v,i)=>assert(Math.abs(b.vertices[i]-v*(i%8<3?.3:1))<1e-5,'Owner scale mismatch'));
 for(const p of a.parts.filter(p=>p.unit))for(let i=p.start;i<p.start+p.count;i++)assert(a.vertices[i*8+1]>=-.00001,'Filling below floor');
}
for(const flight of [0,.25,.5,.75,1]){const r=W.defaults();r.wounds[0]={...r.wounds[0],side:'bottom',u:0,v:0,mode:2,quantity:12};r.flight=flight;const s=W.scene(input,r);for(const p of s.parts.filter(p=>p.unit))for(let i=p.start;i<p.start+p.count;i++)assert(s.vertices[i*8+1]>=-.00001,'Bottom outlet crossed ground');}
checks.push('body and released material scale together; flight landing stays above floor');
const report={date:new Date().toISOString(),status:'passed',sourceSha256:crypto.createHash('sha256').update(fs.readFileSync(path.join(__dirname,'wound-surface.js'))).digest('hex'),checks};
fs.writeFileSync(path.join(__dirname,'reports/current/wound-static.json'),JSON.stringify(report,null,2));console.log(JSON.stringify(report));
