const fs=require('node:fs'),path=require('node:path');const {poseVertices}=require('./motion.js'),{renderModel}=require('./software-viewer.js');
const data=JSON.parse(fs.readFileSync(path.join(__dirname,'preview.html'),'utf8').match(/const DATA=(.*);/)[1]);const tex=fs.readFileSync(path.join(__dirname,'texture.rgba'));
for(let i=0;i<32;i++)for(const [name,az] of [['front',-.65],['rear',2.65]]){const v=poseVertices(data.vertices,data.rig,i*1.2/32);fs.writeFileSync(path.join(__dirname,`motion-${name}-${i}.rgba`),renderModel(v,tex,440,440,az,.17,12.6));}
console.log('Rendered 64 animated views');
