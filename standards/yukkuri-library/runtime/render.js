// Deterministic orthographic rasterizer: shared triangle data, per-pixel depth.
// Also exported to Node so verification executes the actual viewer renderer.
function renderModel(vertices, texture, width, height, az, el, span, options = {}) {
  const textureSize=options.textureSize ?? 128;
  const pixels=new Uint8ClampedArray(width*height*4), depth=new Float64Array(width*height);
  depth.fill(-Infinity);
  const s=Math.sin(az),c=Math.cos(az),se=Math.sin(el),ce=Math.cos(el);
  const right=[c,0,s],up=[-s*se,ce,c*se],eye=[s*ce,se,-c*ce],scale=height/(2*span);
  for(let i=0;i<vertices.length;i+=24){
    let nx=vertices[i+5],ny=vertices[i+6],nz=vertices[i+7];
    const light=.78+.22*Math.max(0,nx*-.35+ny*.8+nz*-.48);
    if(nx!==vertices[i+13]||ny!==vertices[i+14]||nz!==vertices[i+15]||
       nx!==vertices[i+21]||ny!==vertices[i+22]||nz!==vertices[i+23]){
      // Culling follows triangle winding, independently of smoothed lighting.
      const a=[0,1,2].map(k=>vertices[i+8+k]-vertices[i+k]);
      const b=[0,1,2].map(k=>vertices[i+16+k]-vertices[i+k]);
      nx=a[1]*b[2]-a[2]*b[1];ny=a[2]*b[0]-a[0]*b[2];nz=a[0]*b[1]-a[1]*b[0];
    }
    if(nx*eye[0]+ny*eye[1]+nz*eye[2]<=0)continue;
    const lightB=.78+.22*Math.max(0,vertices[i+13]*-.35+vertices[i+14]*.8+vertices[i+15]*-.48);
    const lightC=.78+.22*Math.max(0,vertices[i+21]*-.35+vertices[i+22]*.8+vertices[i+23]*-.48);
    const tri=[];
    for(let j=0;j<3;j++){
      const k=i+j*8,x=vertices[k],y=vertices[k+1]-(options.centerY ?? 9.5),z=vertices[k+2];
      tri.push([width/2+(x*right[0]+z*right[2])*scale,
        height/2-(x*up[0]+y*up[1]+z*up[2])*scale,
        x*eye[0]+y*eye[1]+z*eye[2],vertices[k+3]*textureSize,vertices[k+4]*textureSize]);
    }
    const [a,b,d]=tri;
    const det=(b[1]-d[1])*(a[0]-d[0])+(d[0]-b[0])*(a[1]-d[1]);
    if(Math.abs(det)<1e-8)continue;
    const x0=Math.max(0,Math.floor(Math.min(a[0],b[0],d[0]))),x1=Math.min(width-1,Math.ceil(Math.max(a[0],b[0],d[0])));
    const y0=Math.max(0,Math.floor(Math.min(a[1],b[1],d[1]))),y1=Math.min(height-1,Math.ceil(Math.max(a[1],b[1],d[1])));
    for(let y=y0;y<=y1;y++)for(let x=x0;x<=x1;x++){
      const wa=((b[1]-d[1])*(x+.5-d[0])+(d[0]-b[0])*(y+.5-d[1]))/det;
      const wb=((d[1]-a[1])*(x+.5-d[0])+(a[0]-d[0])*(y+.5-d[1]))/det,wc=1-wa-wb;
      if(wa< -1e-7||wb< -1e-7||wc< -1e-7)continue;
      const z=wa*a[2]+wb*b[2]+wc*d[2],index=y*width+x;
      // Keep first coplanar face, preventing overlapping cuboids from flickering.
      if(z<=depth[index]+1e-7)continue;

      const solid=vertices[i+3]<0 ? options.colors?.[-1-vertices[i+3]] : null;
      const u=Math.max(0,Math.min(textureSize-1,Math.floor(wa*a[3]+wb*b[3]+wc*d[3])));
      const v=Math.max(0,Math.min(textureSize-1,Math.floor(wa*a[4]+wb*b[4]+wc*d[4])));
      const t=(v*textureSize+u)*4,p=index*4;
      if(!solid && texture[t+3]<128)continue;
      depth[index]=z;
      const shading=lightB===light&&lightC===light?light:wa*light+wb*lightB+wc*lightC;
      pixels[p]=Math.floor((solid?solid[0]:texture[t])*shading);pixels[p+1]=Math.floor((solid?solid[1]:texture[t+1])*shading);pixels[p+2]=Math.floor((solid?solid[2]:texture[t+2])*shading);pixels[p+3]=255;
    }
  }
  return pixels;
}

// Match YukkuriModel's continuous house skin shading. References map each
// emitted body vertex back to its authored vertex; hidden parts cannot shift it.
// Position and authored normal define a group, preserving the existing hard edges.
function smoothHouseSurface(model, vertices, references) {
  if(!model.id?.startsWith('house_')||!references.length)return;
  const source=model.vertices,groups=new Map(),keys=[];
  for(const [output,input] of references){
    const p=input*8;
    const key=[0,1,2].map(k=>Math.round(source[p+k]*100000)).join(',')+':'+
      [5,6,7].map(k=>Math.round(source[p+k]*1000)).join(',');
    keys.push(key);if(!groups.has(key))groups.set(key,[0,0,0]);
  }
  for(let i=0;i<references.length;i+=3){
    const a=references[i][0],b=references[i+1][0],c=references[i+2][0];
    const u=[0,1,2].map(k=>vertices[b+k]-vertices[a+k]);
    const v=[0,1,2].map(k=>vertices[c+k]-vertices[a+k]);
    const n=[u[1]*v[2]-u[2]*v[1],u[2]*v[0]-u[0]*v[2],u[0]*v[1]-u[1]*v[0]];
    for(let corner=0;corner<3;corner++){
      const sum=groups.get(keys[i+corner]);for(let k=0;k<3;k++)sum[k]+=n[k];
    }
  }
  for(let i=0;i<references.length;i++){
    const normal=groups.get(keys[i]),length=Math.hypot(...normal);
    if(length>1e-8)for(let k=0;k<3;k++)vertices[references[i][0]+5+k]=normal[k]/length;
  }
}
