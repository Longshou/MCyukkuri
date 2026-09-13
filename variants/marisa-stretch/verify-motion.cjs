const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict'),{poseVertices}=require('./motion.js');
const read=p=>JSON.parse(fs.readFileSync(path.join(__dirname,p),'utf8').replace(/^\uFEFF/,''));
const data=JSON.parse(fs.readFileSync(path.join(__dirname,'preview.html'),'utf8').match(/const DATA=(.*);/)[1]),bb=read('marisa.bbmodel');
const old=JSON.parse(fs.readFileSync(path.join(__dirname,'../marisa-rump/preview.html'),'utf8').match(/const DATA=(.*);/)[1]),oldBB=read('../marisa-rump/marisa.bbmodel'),oldPose=require('../marisa-rump/motion.js').poseVertices;
assert.equal(data.cubes,57);assert.equal(data.rig.bones.length,17);assert.equal(bb.animations.length,2);assert.equal(bb.textures[0].source,oldBB.textures[0].source);
const index=Object.fromEntries(bb.elements.map((e,i)=>[e.name,i]));
function bounds(v,i){const min=[Infinity,Infinity,Infinity],max=[-Infinity,-Infinity,-Infinity];for(let j=i*288;j<(i+1)*288;j+=8)for(let k=0;k<3;k++){min[k]=Math.min(min[k],v[j+k]);max[k]=Math.max(max[k],v[j+k]);}return {min,max};}
let minY=Infinity,maxH=0,maxFaceError=0,maxJoinError=0,maxWaddleError=0;
for(let f=0;f<=280;f++){
 const v=poseVertices(data.vertices,data.rig,f*.01,true,false,'stretch'),face=bounds(v,index.face_surface),body=bounds(v,index.soft_head),crown=bounds(v,index.crown),hat=bounds(v,index.hat_tip_bend);
 for(let i=0;i<v.length;i+=8){minY=Math.min(minY,v[i+1]);assert(v.slice(i,i+8).every(Number.isFinite));assert(Math.abs(Math.hypot(...v.slice(i+5,i+8))-1)<1e-6);}
 assert(Math.abs(body.min[1])<1e-8);maxH=Math.max(maxH,hat.max[1]);maxFaceError=Math.max(maxFaceError,Math.abs(face.max[1]-face.min[1]-8),Math.abs(face.max[0]-face.min[0]-12));maxJoinError=Math.max(maxJoinError,Math.abs(body.max[1]-crown.min[1]),Math.abs(body.max[1]-face.max[1]));
}
assert(minY>=0);assert(maxFaceError<1e-6);assert(maxJoinError<1e-6);assert(Math.abs(maxH-24.25)<1e-6);
assert.deepEqual(poseVertices(data.vertices,data.rig,0),poseVertices(data.vertices,data.rig,2.8));assert.deepEqual(poseVertices(data.vertices,data.rig,2.8),poseVertices(data.vertices,data.rig,5));
for(let f=0;f<=60;f++){
 const a=poseVertices(data.vertices,data.rig,f*.02,true,false,'waddle'),b=oldPose(old.vertices,old.rig,f*.02);
 oldBB.elements.forEach((e,i)=>{const j=index[e.name];for(let k=0;k<288;k++)maxWaddleError=Math.max(maxWaddleError,Math.abs(a[j*288+k]-b[i*288+k]));});
}
// UV-only reassignment on the original front skin is intentional; position/normal comparisons exclude UV.
let geometryError=0;
for(let f=0;f<=60;f++){
 const a=poseVertices(data.vertices,data.rig,f*.02,true,false,'waddle'),b=oldPose(old.vertices,old.rig,f*.02);
 oldBB.elements.forEach((e,i)=>{const j=index[e.name];for(let k=0;k<288;k++)if(k%8!==3&&k%8!==4)geometryError=Math.max(geometryError,Math.abs(a[j*288+k]-b[i*288+k]));});
}
assert(geometryError<1e-6);
const report={status:'passed',stretchSamples:281,waddleSamples:61,minY,maxH,maxFaceError,maxJoinError,waddleGeometryError:geometryError,checks:['ground stays at zero','face remains 12 x 8 throughout stretch','face and crown stay joined to skin','stretch returns to rest and clamps after end','all previous waddle geometry and normals preserved','texture bytes unchanged']};fs.writeFileSync(path.join(__dirname,'motion-verification.json'),JSON.stringify(report,null,2));console.log(report);
