// Deterministic orthographic rasterizer: shared triangle data, per-pixel depth.
// Also exported to Node so verification executes the actual viewer renderer.
function renderModel(vertices, texture, width, height, az, el, span) {
  const pixels=new Uint8ClampedArray(width*height*4), depth=new Float64Array(width*height);
  depth.fill(-Infinity);
  const s=Math.sin(az),c=Math.cos(az),se=Math.sin(el),ce=Math.cos(el);
  const right=[c,0,s],up=[-s*se,ce,c*se],eye=[s*ce,se,-c*ce],scale=height/(2*span);
  for(let i=0;i<vertices.length;i+=24){
    const nx=vertices[i+5],ny=vertices[i+6],nz=vertices[i+7];
    if(nx*eye[0]+ny*eye[1]+nz*eye[2]<=0)continue;
    const light=.78+.22*Math.max(0,nx*-.35+ny*.8+nz*-.48);
    const tri=[];
    for(let j=0;j<3;j++){
      const k=i+j*8,x=vertices[k],y=vertices[k+1]-11.6,z=vertices[k+2];
      tri.push([width/2+(x*right[0]+z*right[2])*scale,
        height/2-(x*up[0]+y*up[1]+z*up[2])*scale,
        x*eye[0]+y*eye[1]+z*eye[2],vertices[k+3]*128,vertices[k+4]*128]);
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

      const u=Math.max(0,Math.min(127,Math.floor(wa*a[3]+wb*b[3]+wc*d[3])));
      const v=Math.max(0,Math.min(127,Math.floor(wa*a[4]+wb*b[4]+wc*d[4])));
      const t=(v*128+u)*4,p=index*4;
      if(texture[t+3]<128)continue;
      depth[index]=z;
      pixels[p]=Math.floor(texture[t]*light);pixels[p+1]=Math.floor(texture[t+1]*light);pixels[p+2]=Math.floor(texture[t+2]*light);pixels[p+3]=255;
    }
  }
  return pixels;
}
if(typeof module!=='undefined')module.exports={renderModel};
