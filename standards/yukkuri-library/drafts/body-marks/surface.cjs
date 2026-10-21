/* Offline G-buffer for the body-material study. No browser, game or build.
 * Projection, culling and lighting follow library/runtime/render.js.
 * Additional position/normal buffers let preview.py evaluate authored materials
 * on the visible skin only, with hair, ornaments and face parts occluding them.
 */
'use strict';
const fs=require('fs'),path=require('path');
const root=path.resolve(__dirname,'../../../..');
const {RuntimePose}=require(path.join(root,'preview/review/pose.js'));
const {createCavity}=require('./cavity.cjs');
const {createRuntimeCavity}=require('./preview_cavity.cjs');
const {createHairWound}=require('./hair-wounds/author.cjs');
const {createThermal}=require('./hair-wounds/thermal.cjs');
const recipe=JSON.parse(fs.readFileSync(path.join(__dirname,'recipe.json'),'utf8'));
const assets=path.join(root,'src/main/resources/assets/mcyukkuri');
const fits=JSON.parse(fs.readFileSync(path.join(assets,'yukkuri_presentation/placement_fit.json'))).models;
const jobs=JSON.parse(fs.readFileSync(process.argv[2],'utf8'));
for(const job of jobs){
  const model=JSON.parse(fs.readFileSync(path.join(assets,'yukkuri',job.model+'.json')));
  const pose=new RuntimePose(model,1,null,fits[job.model]||null);
  let vertices=pose.frame('idle',0);
  const skin=pose.model.parts.find(p=>p.name==='rounded_body_surface');
  if(!skin)throw Error('Missing body surface: '+job.model);
  const paintable=pose.model.parts.filter(p=>p===skin||p.name.startsWith('face_blush_'));
  const cuttable=pose.model.parts.filter(p=>p===skin||p.name.startsWith('face_'));
  const cavity=job.hairWound?createHairWound(vertices,pose.model.parts,{...job.hairWound,model:job.model}):job.cavityRuntime?createRuntimeCavity(vertices,skin,job.cavityRuntime):job.cavity?createCavity(vertices,skin,recipe.core_cavity,job.cavity==='broken'):null;
  if(job.woundMesh){const data=JSON.parse(require('zlib').gunzipSync(fs.readFileSync(job.woundMesh)));for(const key of ['vertices','colors','materials','surfaceEdits'])cavity[key]=data[key];}
  if(cavity?.surfaceEdits)for(const [index,x,y,z,nx,ny,nz] of cavity.surfaceEdits){const i=index*8;vertices[i]=x;vertices[i+1]=y;vertices[i+2]=z;vertices[i+5]=nx;vertices[i+6]=ny;vertices[i+7]=nz;}
  const decal=job.woundDecal?fs.readFileSync(job.woundDecal):null,decalSize=job.decalSize||512;
  const thermal=job.thermal?createThermal({...job.thermal,model:job.model},pose.model.parts):null;
  const thermalPixels=thermal?fs.readFileSync(job.thermalTexture):null;
  if(cavity)vertices=Float64Array.from([...vertices,...cavity.vertices]);
  const texture=fs.readFileSync(job.texture),ts=model.texture_size||128;
  const w=job.width,h=job.height,n=w*h;
  const pixels=new Uint8Array(n*4),depth=new Float64Array(n).fill(-Infinity);
  // xyz, normal xyz, diffuse lighting, skin flag.
  const buffer=new Float32Array(n*8);
  const s=Math.sin(job.az),c=Math.cos(job.az),se=Math.sin(job.el),ce=Math.cos(job.el);
  const right=[c,0,s],up=[-s*se,ce,c*se],eye=[s*ce,se,-c*ce],scale=h/(2*job.span);
  for(let i=0;i<vertices.length;i+=24){
    const part=pose.model.parts.find(p=>i/8>=p.start&&i/8<p.start+p.count);
    const solid=vertices[i+3]<0?cavity.colors[-1-vertices[i+3]]:null;
    const cut=cavity&&!solid&&(job.hairWound?cavity.cuttable.has(part?.name):cuttable.some(part=>i/8>=part.start&&i/8<part.start+part.count));
    const norm=[vertices[i+5],vertices[i+6],vertices[i+7]];
    const ab=[0,1,2].map(k=>vertices[i+8+k]-vertices[i+k]),ac=[0,1,2].map(k=>vertices[i+16+k]-vertices[i+k]);
    const geometric=[ab[1]*ac[2]-ab[2]*ac[1],ab[2]*ac[0]-ab[0]*ac[2],ab[0]*ac[1]-ab[1]*ac[0]];
    if(!(job.hairWound&&solid)&&geometric.reduce((sum,v,k)=>sum+v*eye[k],0)<=0)continue;
    const lights=[0,8,16].map(j=>.78+.22*Math.max(0,vertices[i+j+5]*-.35+vertices[i+j+6]*.8+vertices[i+j+7]*-.48));
    const tri=[];
    for(let j=0;j<3;j++){
      const k=i+j*8,renderPoint=[0,1,2].map(axis=>thermal?thermal.scaleOrigin[axis]+(vertices[k+axis]-thermal.scaleOrigin[axis])*thermal.displayScale:vertices[k+axis]);
      const x=renderPoint[0]-(job.cx||0),y=renderPoint[1]-job.cy,z=renderPoint[2]-(job.cz||0);
      tri.push([w/2+(x*right[0]+z*right[2])*scale,h/2-(x*up[0]+y*up[1]+z*up[2])*scale,
        x*eye[0]+y*eye[1]+z*eye[2],vertices[k+3]*ts,vertices[k+4]*ts]);
    }
    const [a,b,d]=tri,det=(b[1]-d[1])*(a[0]-d[0])+(d[0]-b[0])*(a[1]-d[1]);
    if(Math.abs(det)<1e-8)continue;
    const x0=Math.max(0,Math.floor(Math.min(a[0],b[0],d[0]))),x1=Math.min(w-1,Math.ceil(Math.max(a[0],b[0],d[0])));
    const y0=Math.max(0,Math.floor(Math.min(a[1],b[1],d[1]))),y1=Math.min(h-1,Math.ceil(Math.max(a[1],b[1],d[1])));
    for(let y=y0;y<=y1;y++)for(let x=x0;x<=x1;x++){
      const wa=((b[1]-d[1])*(x+.5-d[0])+(d[0]-b[0])*(y+.5-d[1]))/det;
      const wb=((d[1]-a[1])*(x+.5-d[0])+(a[0]-d[0])*(y+.5-d[1]))/det,wc=1-wa-wb;
      if(wa< -1e-7||wb< -1e-7||wc< -1e-7)continue;
      const z=wa*a[2]+wb*b[2]+wc*d[2],index=y*w+x,p=index*4,q=index*8;
      if(z<=depth[index]+1e-7)continue;
      const point=[0,1,2].map(k=>wa*vertices[i+k]+wb*vertices[i+8+k]+wc*vertices[i+16+k]);
      if(cut&&cavity.inside(...point,part?.name))continue;
      const u=Math.max(0,Math.min(ts-1,Math.floor(wa*a[3]+wb*b[3]+wc*d[3])));
      const v=Math.max(0,Math.min(ts-1,Math.floor(wa*a[4]+wb*b[4]+wc*d[4]))),t=(v*ts+u)*4;
      if(!solid&&texture[t+3]<128)continue;
      depth[index]=z;
      let light=wa*lights[0]+wb*lights[1]+wc*lights[2];
      if(solid&&['filling','core'].includes(cavity.materials[-1-vertices[i+3]])){
        const hash=Math.sin(Math.floor(point[0]*32)*127.1+Math.floor(point[1]*32)*311.7+Math.floor(point[2]*32)*74.7)*43758.5453;
        light*=.94+.12*(hash-Math.floor(hash));
      }
      for(let k=0;k<3;k++){
        pixels[p+k]=Math.floor((solid?solid[k]:texture[t+k])*light);
        buffer[q+k]=point[k];
        buffer[q+3+k]=norm[k];
      }
      if(decal&&!solid&&cavity.paintable.has(part?.name)){
        const [du,dv,dn]=cavity.project(point);
        if(dn>.15&&Math.abs(du)<1&&Math.abs(dv)<1){
          const tx=Math.min(decalSize-1,Math.floor((du+1)*.5*decalSize)),ty=Math.min(decalSize-1,Math.floor((1-dv)*.5*decalSize));
          const t=(ty*decalSize+tx)*4,alpha=decal[t+3]/255;
          for(let k=0;k<3;k++)pixels[p+k]=Math.floor(pixels[p+k]*(1-alpha)+decal[t+k]*light*alpha);
        }
      }
      if(thermal&&!solid){
        const coverage=thermal.coverage(point,part?.name),uv=thermal.uv(point,norm);
        const ti=(Math.min(511,Math.floor(uv[1]*512))*512+Math.min(511,Math.floor(uv[0]*512)))*4;
        const alpha=coverage*thermalPixels[ti+3]/255;
        for(let k=0;k<3;k++)pixels[p+k]=Math.floor(pixels[p+k]*(1-alpha)+thermalPixels[ti+k]*light*alpha);
      }
      pixels[p+3]=255;buffer[q+6]=light;
      buffer[q+7]=paintable.some(part=>i/8>=part.start&&i/8<part.start+part.count)?1:0;
    }
  }
  fs.writeFileSync(job.out+'.rgba',pixels);
  fs.writeFileSync(job.out+'.f32',Buffer.from(buffer.buffer));
  console.log('Surface: '+job.model+' / '+path.basename(job.out));
}
