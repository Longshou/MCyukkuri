const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict');
const {poseVertices}=require('./motion.js'),{renderModel}=require('./software-viewer.js');
const root=__dirname,read=p=>JSON.parse(fs.readFileSync(path.join(root,p),'utf8').replace(/^\uFEFF/,''));
const data=JSON.parse(fs.readFileSync(path.join(root,'preview.html'),'utf8').match(/const DATA=(.*);/)[1]);
const bb=read('marisa.bbmodel'),base=JSON.parse(fs.readFileSync(path.join(root,'../marisa-body/marisa.bbmodel'),'utf8'));
assert.deepEqual(bb.elements,base.elements);assert.equal(bb.textures[0].source,base.textures[0].source);
const geo=read('marisa.geo.json')['minecraft:geometry'][0];assert.equal(geo.bones.length,11);assert.equal(geo.bones.reduce((n,b)=>n+b.cubes.length,0),53);
const groups=new Map();function visit(n){groups.set(n.uuid,n);for(const c of n.children)if(typeof c==='object')visit(c)}visit(bb.outliner[0]);
for(const id in bb.animations[0].animators)assert(groups.has(id));
const start=poseVertices(data.vertices,data.rig,0),end=poseVertices(data.vertices,data.rig,1.2);assert.deepEqual(start,end);
let lowest=Infinity,highestGround=-Infinity;
for(let f=0;f<=240;f++){
 const v=poseVertices(data.vertices,data.rig,f/200);let min=Infinity;
 for(let i=0;i<v.length;i+=8){assert(Number.isFinite(v[i]));min=Math.min(min,v[i+1]);const n=Math.hypot(...v.slice(i+5,i+8));assert(Math.abs(n-1)<1e-6)}
 lowest=Math.min(lowest,min);highestGround=Math.max(highestGround,min);assert(min>-.001&&min<.001,`floor at ${f}: ${min}`);
}
const texture=Buffer.from(bb.textures[0].source.split(',')[1],'base64');
const report={status:'passed',checks:['rest geometry and texture identical to selected base','53 cubes / 11 linked bones','all Blockbench animator references resolve','241 samples of complete cycle: floor error below 0.001 model units','unit normals and finite posed vertices','loop endpoints identical'],lowest,highestGround};
fs.writeFileSync(path.join(root,'motion-verification.json'),JSON.stringify(report,null,2));
fs.writeFileSync(path.join(root,'motion-frames.json'),JSON.stringify(Array.from({length:32},(_,i)=>poseVertices(data.vertices,data.rig,i*1.2/32))));
console.log(report);
