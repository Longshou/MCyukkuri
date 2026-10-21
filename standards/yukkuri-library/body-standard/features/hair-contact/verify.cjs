/* Ray-test the actual deformed rear skin against its hair through a full walk.
 * Pass a catalog path to reproduce this check on an earlier export.
 */
const fs=require('fs'),path=require('path'),vm=require('vm'),crypto=require('crypto');
const here=__dirname,body=path.resolve(here,'../..'),root=path.resolve(body,'../../..'),preview=path.join(root,'.local/previews/body-standard'),out=path.join(root,'.local/reports/body-standard');
const input=process.argv[2]||path.join(preview,'preview-data.json');
const catalog=JSON.parse(fs.readFileSync(input)),context={};vm.createContext(context);
vm.runInContext(fs.readFileSync(path.join(body,'../runtime/render.js'),'utf8'),context);
vm.runInContext(fs.readFileSync(path.join(preview,'pose.js'),'utf8'),context);
const hash=p=>crypto.createHash('sha256').update(fs.readFileSync(p)).digest('hex');
function rayGrid(vertices,cell){
 const grid=new Map();
 for(let i=0;i<vertices.length;i+=24){
  const a=vertices.slice(i,i+3),b=vertices.slice(i+8,i+11),c=vertices.slice(i+16,i+19);
  const det=(b[1]-c[1])*(a[0]-c[0])+(c[0]-b[0])*(a[1]-c[1]);if(Math.abs(det)<1e-9)continue;
  const tri={a,b,c,det};
  for(let x=Math.floor(Math.min(a[0],b[0],c[0])/cell);x<=Math.floor(Math.max(a[0],b[0],c[0])/cell);x++)
   for(let y=Math.floor(Math.min(a[1],b[1],c[1])/cell);y<=Math.floor(Math.max(a[1],b[1],c[1])/cell);y++){
    const key=x+','+y;if(!grid.has(key))grid.set(key,[]);grid.get(key).push(tri);
   }
 }
 return p=>{
  let z=-Infinity;
  for(const {a,b,c,det}of grid.get(Math.floor(p[0]/cell)+','+Math.floor(p[1]/cell))||[]){
   const u=((b[1]-c[1])*(p[0]-c[0])+(c[0]-b[0])*(p[1]-c[1]))/det;
   const v=((c[1]-a[1])*(p[0]-c[0])+(a[0]-c[0])*(p[1]-c[1]))/det;
   if(u>=-1e-6&&v>=-1e-6&&u+v<=1+1e-6)z=Math.max(z,u*a[2]+v*b[2]+(1-u-v)*c[2]);
  }return z;
 };
}
const models=[];
for(const [id,e]of Object.entries(catalog)){
 const m=e.model,scale=m.overall_scale||1;
 if(!m.clips.waddle){models.push({id,walk:'not_present_in_standard_export'});continue;}
 const skin=m.parts.find(p=>p.name==='rounded_body_surface');
 const source=JSON.parse(fs.readFileSync(path.resolve(body,'components',id+'.json')));
 const hairNames=new Set(source.elements.filter(e=>e.bone==='hair').map(e=>e.name));
 const hair=m.parts.filter(p=>hairNames.has(p.name)),samples=[];
 const v=m.vertices;
 // Interior rear triangles, away from the deliberately open hem and sideburns.
 for(let i=skin.start;i<skin.start+skin.count;i+=3){
  for(const q of [[1/3,1/3,1/3],[.6,.2,.2],[.2,.6,.2],[.2,.2,.6]]){
   const p=[0,1,2].map(k=>q.reduce((s,w,j)=>s+w*v[(i+j)*8+k],0));
   if(p[1]/scale>=4.9&&p[1]/scale<=8.0&&Math.abs(p[0]/scale)<4.2&&p[2]/scale>4.3)samples.push({i:(i-skin.start)*8,q});
  }
 }
 let failed=0,total=0,minimum=Infinity;const failures=[];
 const phases=121;
 for(let phase=0;phase<phases;phase++){
  const t=m.clips.waddle.duration*phase/(phases-1);
  const hairVertices=context.posed({...m,parts:hair},'waddle',t),lookup=rayGrid(hairVertices,.7*scale);
  const bodyVertices=context.posed({...m,parts:[skin]},'waddle',t);
  for(const {i,q}of samples){
   const p=[0,1,2].map(k=>q.reduce((s,w,j)=>s+w*bodyVertices[i+j*8+k],0));
   const clearance=(lookup(p)-p[2])/scale;total++;minimum=Math.min(minimum,clearance);
   if(clearance<.015){failed++;if(failures.length<4)failures.push({phase,time:t,point:p,clearance});}
  }
 }
 models.push({id,phases,surfaceSamples:samples.length,total,failed,minClearance:minimum,failures});
}
const report={createdAt:new Date().toISOString(),status:models.some(m=>m.failed)?'failed':'passed',
 scope:'rear skin occlusion by deformed hair at 121 walk times, interior rear only; no Minecraft claim',input,sha256:hash(input),models};
fs.mkdirSync(out,{recursive:true});fs.writeFileSync(path.join(out,process.argv[2]?'hair-contact-before.json':'hair-contact.json'),JSON.stringify(report,null,2));
console.log(JSON.stringify(report));if(report.status==='failed')process.exitCode=1;
