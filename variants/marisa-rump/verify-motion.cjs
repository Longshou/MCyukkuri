const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict');
const {poseVertices}=require('./motion.js');
const read=p=>JSON.parse(fs.readFileSync(path.join(__dirname,p),'utf8').replace(/^\uFEFF/,''));
const data=JSON.parse(fs.readFileSync(path.join(__dirname,'preview.html'),'utf8').match(/const DATA=(.*);/)[1]);
const bb=read('marisa.bbmodel'),base=read('../marisa-body/marisa.bbmodel');
assert.equal(bb.elements.length,56);assert.equal(bb.textures[0].source,base.textures[0].source);
const old=Object.fromEntries(base.elements.map(e=>[e.name,e]));
for(const e of bb.elements)if(e.name.startsWith('hat_')||e.name.startsWith('bow_')||e.name.startsWith('bang_'))assert.deepEqual(e,old[e.name]);
const geo=read('marisa.geo.json')['minecraft:geometry'][0];assert.equal(geo.bones.length,12);assert.equal(geo.bones.reduce((n,b)=>n+b.cubes.length,0),56);
const index=Object.fromEntries(bb.elements.map((e,i)=>[e.name,i]));
function shape(v,name){const start=index[name]*288;return {points:Array.from({length:36},(_,i)=>v.slice(start+i*8,start+i*8+3)),axes:[0,6,24].map(i=>v.slice(start+i*8+5,start+i*8+8))};}
const dot=(a,b)=>a.reduce((n,x,i)=>n+x*b[i],0),cross=(a,b)=>[a[1]*b[2]-a[2]*b[1],a[2]*b[0]-a[0]*b[2],a[0]*b[1]-a[1]*b[0]];
function intersects(a,b){const axes=[...a.axes,...b.axes,...a.axes.flatMap(x=>b.axes.map(y=>cross(x,y)))];
 return axes.every(axis=>{if(Math.hypot(...axis)<1e-7)return true;const pa=a.points.map(p=>dot(p,axis)),pb=b.points.map(p=>dot(p,axis));return Math.min(...pa)<=Math.max(...pb)+1e-6&&Math.min(...pb)<=Math.max(...pa)+1e-6;});}
let minAll=Infinity,minRump=Infinity,frontX=[],rumpX=[];
for(let f=0;f<=240;f++){
 const v=poseVertices(data.vertices,data.rig,f/200);
 for(let i=0;i<v.length;i+=8){assert(v.slice(i,i+8).every(Number.isFinite));assert(Math.abs(Math.hypot(...v.slice(i+5,i+8))-1)<1e-6);minAll=Math.min(minAll,v[i+1]);}
 const front=shape(v,'soft_head'),rump=shape(v,'rump_core'),tip=shape(v,'rump_round_end');
 assert(intersects(front,rump),`rump disconnected from front at frame ${f}`);assert(intersects(rump,tip),`rump tip detached at ${f}`);
 assert(Math.abs(Math.min(...front.points.map(p=>p[1])))<1e-6);
 minRump=Math.min(minRump,...rump.points.map(p=>p[1]),...tip.points.map(p=>p[1]));
 frontX.push(front.points.reduce((s,p)=>s+p[0],0)/36);rumpX.push(tip.points.reduce((s,p)=>s+p[0],0)/36);
}
assert(minAll>-.001);assert(minRump>-.001);
const rearExcursion=Math.max(...rumpX)-Math.min(...rumpX),faceExcursion=Math.max(...frontX)-Math.min(...frontX);
assert(rearExcursion>4.4);assert(faceExcursion<.001);
assert.deepEqual(poseVertices(data.vertices,data.rig,0),poseVertices(data.vertices,data.rig,1.2));
const java=read('resourcepack/assets/yukkuri/models/item/marisa.json');assert.equal(java.elements.length,56);
for(let i=0;i<56;i++){assert.deepEqual(java.elements[i].from,[bb.elements[i].from[0]+8,bb.elements[i].from[1],bb.elements[i].from[2]+8]);assert.deepEqual(java.elements[i].to,[bb.elements[i].to[0]+8,bb.elements[i].to[1],bb.elements[i].to[2]+8]);}
const report={status:'passed',samples:241,cubes:56,bones:12,minAll,minRump,rearExcursion,faceExcursion,checks:['unchanged texture, bangs, hat and bow','matching static exports','all finite vertices and unit normals','rump continuously intersects front body and rear cap','front stays grounded and rump never crosses ground','rear moves over 4.4 units while face remains horizontally stable','loop endpoints identical']};
fs.writeFileSync(path.join(__dirname,'motion-verification.json'),JSON.stringify(report,null,2));console.log(report);

