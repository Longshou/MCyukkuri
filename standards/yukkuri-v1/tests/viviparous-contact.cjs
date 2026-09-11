const assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path');
require('../runtime/growth.js');require('../runtime/stem-birth.js');const birth=require('../runtime/viviparous-birth.js');
const html=fs.readFileSync(path.join(__dirname,'../viviparous/index.html'),'utf8');
const data=JSON.parse(html.match(/const DATA=([^\n]+);/)[1]);
function surfaceAt(time,scale=1,count=3){const state=birth.sample(data,time,scale,count),meshes=state.children.filter(c=>c.visible).map(child=>({child,vertices:birth.childGeometry(data,state,child)}));return {state,surface:birth.outletSurface(data,state,meshes)};}
// A continuous capped surface has one boundary loop, at its attachment to the
// body. Any gap in the rim creates additional boundaries or loose endpoints.
function checkClosure(vertices){
 const key=p=>p.map(x=>x.toFixed(6)).join(','),edges=new Map();
 for(let k=0;k<vertices.length;k+=24)for(let j=0;j<3;j++){
  const a=key(vertices.slice(k+j*8,k+j*8+3)),b=key(vertices.slice(k+(j+1)%3*8,k+(j+1)%3*8+3));
  const id=[a,b].sort().join('|'),e=edges.get(id)||{a,b,count:0};e.count++;edges.set(id,e);
 }
 const adj=new Map();for(const e of edges.values()){assert(e.count<=2,'nonmanifold surface');if(e.count===1){for(const [a,b]of [[e.a,e.b],[e.b,e.a]]){if(!adj.has(a))adj.set(a,[]);adj.get(a).push(b);}}}
 assert(adj.size>0);for(const neighbours of adj.values())assert.equal(neighbours.length,2,'broken rim endpoint');
 const seen=new Set(),todo=[adj.keys().next().value];while(todo.length){const id=todo.pop();if(seen.has(id))continue;seen.add(id);todo.push(...adj.get(id).filter(x=>!seen.has(x)));}
 assert.equal(seen.size,adj.size,'extra open boundary inside outlet');
}
for(const count of [1,2,3,4,5])for(const scale of [.8,1,1.2])for(const dt of [.32,.55,.85,1.25,1.65]){
 const {state,surface}=surfaceAt(14+(count-1)*2.4+dt,scale,count);
 assert(surface.inner.every(p=>p.every(Number.isFinite)));
 if(surface.contact)for(const p of surface.hull)for(let i=0;i<surface.inner.length;i++){
  const a=surface.inner[i],b=surface.inner[(i+1)%surface.inner.length];
  assert((b[0]-a[0])*(p[1]-a[1])-(b[1]-a[1])*(p[0]-a[0])>=-1e-5,'rim intersects newborn contact section');
 }
 const vertices=birth.surfaceGeometry(data,state,surface);assert(vertices.every(Number.isFinite));
}
for(const time of [12.9,14.55,14.85,15.65]){const {state,surface}=surfaceAt(time);checkClosure(birth.surfaceGeometry(data,state,surface));}
const {state,surface}=surfaceAt(14.85),child=state.children[0],planeZ=(surface.plane-child.position[2])/child.scale;
const near=birth.childPoint(data,state,child,[5,4.24,planeZ]),free=birth.childPoint(data,state,child,[5,4.24,-4.6]);
assert(near[0]<5*child.scale*.85,'contact must compress material');assert(free[0]>near[0]*1.15,'squeeze must stay local');
const recovered=birth.sample(data,15.8).children[0],p=[5,4.24,-4.6],position=birth.childPoint(data,birth.sample(data,15.8),recovered,p);
assert.deepEqual(position,p.map((v,k)=>v*recovered.scale+recovered.position[k]));
console.log('Contact checks passed: closed rim surface, newborn section fits opening, local compression and full release, all litter counts and adult scales.');
