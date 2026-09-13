const fs=require('node:fs'),path=require('node:path');
const {poseVertices}=require('./motion.js'),{renderModel}=require('./software-viewer.js');
const [folder='.',clip='taiso',t='0',az='-.55',el='.12']=process.argv.slice(2);
const r=path.join(__dirname,folder);
const data=JSON.parse(fs.readFileSync(path.join(r,'preview.html'),'utf8').match(/const DATA=(.*);/)[1]);
const texture=fs.readFileSync(path.join(r,'texture.rgba'));
const v=poseVertices(data.vertices,data.rig,Number(t),true,false,clip);
process.stdout.write(Buffer.from(renderModel(v,texture,480,480,Number(az),Number(el),12.5)));
