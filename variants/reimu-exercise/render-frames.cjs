// Stream raw frames to Pillow; avoids hundreds of intermediate files on disk.
const fs=require('node:fs'),path=require('node:path'),{once}=require('node:events');
const {poseVertices}=require('./motion.js'),{renderModel}=require('./software-viewer.js');
const data=JSON.parse(fs.readFileSync(path.join(__dirname,'preview.html'),'utf8').match(/const DATA=(.*);/)[1]);
const texture=fs.readFileSync(path.join(__dirname,'texture.rgba'));
const [clip='taiso',width='360',height='420',az='-.55']=process.argv.slice(2),w=Number(width),h=Number(height),duration=data.rig.clips[clip].duration;
(async()=>{for(let i=0;i<Math.round(duration*50);i++){
 const vertices=poseVertices(data.vertices,data.rig,i*.02,true,false,clip);
 const buffer=renderModel(vertices,texture,w,h,Number(az),.12,Math.max(11.5,13*h/w));
 if(!process.stdout.write(Buffer.from(buffer)))await once(process.stdout,'drain');
}})().catch(e=>{console.error(e);process.exit(1)});
