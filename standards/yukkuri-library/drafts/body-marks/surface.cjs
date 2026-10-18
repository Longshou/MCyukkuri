/* Offline G-buffer for the body-material study. No browser, game or build.
 * Projection, culling and lighting follow library/runtime/render.js.
 * Additional position/normal buffers let preview.py evaluate authored materials
 * on the visible skin only, with hair, ornaments and face parts occluding them.
 */
'use strict';
const fs=require('fs'),path=require('path');
const root=path.resolve(__dirname,'../../../..');
const {RuntimePose}=require(path.join(root,'preview/review/pose.js'));
const assets=path.join(root,'src/main/resources/assets/mcyukkuri');
const fits=JSON.parse(fs.readFileSync(path.join(assets,'yukkuri_presentation/placement_fit.json'))).models;
const jobs=JSON.parse(fs.readFileSync(process.argv[2],'utf8'));
for(const job of jobs){
  const model=JSON.parse(fs.readFileSync(path.join(assets,'yukkuri',job.model+'.json')));
  const pose=new RuntimePose(model,1,null,fits[job.model]||null);
  const vertices=pose.frame('idle',0),skin=pose.model.parts.find(p=>p.name==='rounded_body_surface');
  if(!skin)throw Error('Missing body surface: '+job.model);
  const paintable=pose.model.parts.filter(p=>p===skin||p.name.startsWith('face_blush_'));
  const texture=fs.readFileSync(job.texture),ts=model.texture_size||128;
  const w=job.width,h=job.height,n=w*h;
  const pixels=new Uint8Array(n*4),depth=new Float64Array(n).fill(-Infinity);
  // xyz, normal xyz, diffuse lighting, skin flag.
  const buffer=new Float32Array(n*8);
  const s=Math.sin(job.az),c=Math.cos(job.az),se=Math.sin(job.el),ce=Math.cos(job.el);
  const right=[c,0,s],up=[-s*se,ce,c*se],eye=[s*ce,se,-c*ce],scale=h/(2*job.span);
  for(let i=0;i<vertices.length;i+=24){
    const norm=[vertices[i+5],vertices[i+6],vertices[i+7]];
    if(norm.reduce((sum,v,k)=>sum+v*eye[k],0)<=0)continue;
    const light=.78+.22*Math.max(0,norm[0]*-.35+norm[1]*.8+norm[2]*-.48);
    const tri=[];
    for(let j=0;j<3;j++){
      const k=i+j*8,x=vertices[k]-(job.cx||0),y=vertices[k+1]-job.cy,z=vertices[k+2]-(job.cz||0);
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
      const u=Math.max(0,Math.min(ts-1,Math.floor(wa*a[3]+wb*b[3]+wc*d[3])));
      const v=Math.max(0,Math.min(ts-1,Math.floor(wa*a[4]+wb*b[4]+wc*d[4]))),t=(v*ts+u)*4;
      if(texture[t+3]<128)continue;
      depth[index]=z;
      for(let k=0;k<3;k++){
        pixels[p+k]=Math.floor(texture[t+k]*light);
        buffer[q+k]=wa*vertices[i+k]+wb*vertices[i+8+k]+wc*vertices[i+16+k];
        buffer[q+3+k]=norm[k];
      }
      pixels[p+3]=255;buffer[q+6]=light;
      buffer[q+7]=paintable.some(part=>i/8>=part.start&&i/8<part.start+part.count)?1:0;
    }
  }
  fs.writeFileSync(job.out+'.rgba',pixels);
  fs.writeFileSync(job.out+'.f32',Buffer.from(buffer.buffer));
  console.log('Surface: '+job.model+' / '+path.basename(job.out));
}
