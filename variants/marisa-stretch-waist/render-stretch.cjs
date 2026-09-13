const fs=require('node:fs'),path=require('node:path');const {poseVertices}=require('./motion.js'),{renderModel}=require('./software-viewer.js');
const d=JSON.parse(fs.readFileSync(path.join(__dirname,'preview.html'),'utf8').match(/const DATA=(.*);/)[1]),tex=fs.readFileSync(path.join(__dirname,'texture.rgba'));
for(let i=0;i<56;i++)for(const [name,az] of [['front',0],['quarter',-.65]]){const v=poseVertices(d.vertices,d.rig,i*.05,true,false,'stretch');fs.writeFileSync(path.join(__dirname,`stretch-${name}-${i}.rgba`),renderModel(v,tex,420,540,az,.12,16));}
console.log('Rendered 112 stretch views');
