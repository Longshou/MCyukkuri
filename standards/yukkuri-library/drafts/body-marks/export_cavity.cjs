/* Export the existing cavity study: shared outline/loft and the one central core.
 * This adapter changes no art dimensions, colours or source geometry. */
'use strict';
const fs=require('fs'),path=require('path'),zlib=require('zlib'),crypto=require('crypto');
const root=path.resolve(__dirname,'../../../..');
const {createCavity}=require('./cavity.cjs');
const {RuntimePose}=require(path.join(root,'preview/review/pose.js'));
const assets=path.join(root,'src/main/resources/assets/mcyukkuri');
const recipe=JSON.parse(fs.readFileSync(path.join(__dirname,'recipe.json'),'utf8'));
const model=JSON.parse(fs.readFileSync(path.join(assets,'yukkuri/reimu.json'),'utf8'));
const fits=JSON.parse(fs.readFileSync(path.join(assets,'yukkuri_presentation/placement_fit.json'),'utf8'));
const pose=new RuntimePose(model,1,null,fits.models.reimu||null),vertices=pose.frame('idle',0);
const skin=pose.model.parts.find(p=>p.name==='rounded_body_surface');
if(!skin)throw Error('Missing reference skin');
const bounds=[Infinity,Infinity,Infinity,-Infinity,-Infinity,-Infinity];
for(let i=skin.start;i<skin.start+skin.count;i++)for(let k=0;k<3;k++){
  bounds[k]=Math.min(bounds[k],vertices[i*8+k]);bounds[k+3]=Math.max(bounds[k+3],vertices[i*8+k]);
}
const cavity=createCavity(vertices,skin,recipe.core_cavity),broken=createCavity(vertices,skin,recipe.core_cavity,true);
const span=[0,1,2].map(k=>bounds[k+3]-bounds[k]);
const shared={version:1,reference_bounds:bounds,
  center:recipe.core_cavity.core_center.map((v,k)=>(v-bounds[k])/span[k]),
  core_radius:recipe.core_cavity.core_radius.map((v,k)=>v/span[k]),
  opening_radius:[recipe.core_cavity.opening_radius[0]/span[0],recipe.core_cavity.opening_radius[1]/span[1]],
  reference_depth:recipe.core_cavity.core_center[2]-bounds[2],
  outline:cavity.outline,levels:cavity.levels};
const core={vertices:[],colours:[],broken_colours:[]};
for(let i=0;i<cavity.vertices.length;i+=24){
  const material=-1-cavity.vertices[i+3];if(cavity.materials[material]!=='core')continue;
  for(let j=0;j<3;j++){
    for(let k=0;k<3;k++)core.vertices.push((cavity.vertices[i+j*8+k]-recipe.core_cavity.core_center[k])/span[k]);
    core.vertices.push(...cavity.vertices.slice(i+j*8+5,i+j*8+8).map((v,k)=>v*span[k]));
  }
  core.colours.push(...cavity.colors[material]);core.broken_colours.push(...broken.colors[material]);
}
const normalJson=value=>Buffer.from(JSON.stringify(value)+'\n');
const files=new Map();
function both(name,data){files.set('art/source/body-core-cavity/'+name,data);files.set('src/main/resources/assets/mcyukkuri/yukkuri_presentation/'+name,data);}
both('body_cavity.json',normalJson(shared));
both('body_core.json.gz',zlib.gzipSync(normalJson(core),{level:9}));
// Technical white texel for vertex-coloured source triangles, not a new art palette.
function crc(data){let n=0xffffffff;for(const v of data){n^=v;for(let k=0;k<8;k++)n=(n>>>1)^((n&1)?0xedb88320:0);}return (n^0xffffffff)>>>0;}
function chunk(type,data){const t=Buffer.from(type),head=Buffer.alloc(4),tail=Buffer.alloc(4);head.writeUInt32BE(data.length);tail.writeUInt32BE(crc(Buffer.concat([t,data])));return Buffer.concat([head,t,data,tail]);}
const ihdr=Buffer.alloc(13);ihdr.writeUInt32BE(1,0);ihdr.writeUInt32BE(1,4);ihdr[8]=8;ihdr[9]=6;
const white=Buffer.concat([Buffer.from([137,80,78,71,13,10,26,10]),chunk('IHDR',ihdr),chunk('IDAT',zlib.deflateSync(Buffer.from([0,255,255,255,255]))),chunk('IEND',Buffer.alloc(0))]);
files.set('src/main/resources/assets/mcyukkuri/textures/props/body_cavity_white.png',white);
const sha=data=>crypto.createHash('sha256').update(data).digest('hex');
const sources={};for(const name of ['cavity.cjs','export_cavity.cjs'])sources[name]=sha(Buffer.from(fs.readFileSync(path.join(__dirname,name),'utf8').replace(/\r\n/g,'\n')));
const manifest={source:'standards/yukkuri-library/drafts/body-marks/cavity.cjs',recipe_section:'core_cavity',
  recipe_core_cavity_sha256:sha(Buffer.from(JSON.stringify(recipe.core_cavity))),
  reference_model:'src/main/resources/assets/mcyukkuri/yukkuri/reimu.json',reference_model_sha256:sha(Buffer.from(JSON.stringify(model))),
  placement_fit:'src/main/resources/assets/mcyukkuri/yukkuri_presentation/placement_fit.json',placement_fit_sha256:sha(Buffer.from(JSON.stringify(fits))),
  source_sha256:sources,text_fingerprint_newlines:'LF',status:'selected_pending_runtime_review',
  scope:'Existing authored cavity outline, dough lip, recessed filling loft and single central core; runtime fits the same shape to a real deep injury.',
  outputs:Object.fromEntries([...files].map(([name,data])=>[name,sha(data)]))};
files.set('art/source/body-core-cavity/manifest.json',normalJson(manifest));
const check=process.argv.includes('--check'),stale=[];
for(const [name,data]of files){const p=path.join(root,name);if(check){
  const current=fs.existsSync(p)?(name.endsWith('.json')?Buffer.from(fs.readFileSync(p,'utf8').replace(/\r\n/g,'\n')):fs.readFileSync(p)):null;
  if(!current||!current.equals(data))stale.push(name);
}else{fs.mkdirSync(path.dirname(p),{recursive:true});fs.writeFileSync(p,data);}}
if(stale.length)throw Error('Stale cavity exports: '+stale.join(', '));
console.log(`${check?'Verified':'Exported'} shared cavity outline, ${shared.levels.length} loft levels and ${core.colours.length/3} core triangles`);
