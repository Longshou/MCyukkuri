'use strict';
// Editable parameter/vector face authoring. External pixels are never copied.
const fs = require('fs');
const path = require('path');
const {decode, encode} = require('./png.js');
const design = JSON.parse(fs.readFileSync(path.join(__dirname, 'face-design.json'), 'utf8'));
const root = path.resolve(__dirname, '../../../..');
const base = decode(path.join(root, 'standards/yukkuri-library/assembled/alice/textures/neutral.png'));
const regions = {leftEye:[58,0,15,12],rightEye:[73,0,15,12],leftBrow:[102,0,17,6],rightBrow:[102,6,17,6],mouth:[0,0,28,24],leftCheek:[88,11,10,6],rightCheek:[58,12,10,6],tearLeft:[28,0,15,16],tearRight:[43,0,15,16],effect:[88,0,14,11]};
const pal=design.palette;
function ellipse(x,y,cx,cy,rx,ry){return ((x-cx)/rx)**2+((y-cy)/ry)**2<=1;}
function line(x,y,x1,y1,x2,y2,r){const dx=x2-x1,dy=y2-y1,q=Math.max(0,Math.min(1,((x-x1)*dx+(y-y1)*dy)/(dx*dx+dy*dy)));return Math.hypot(x-x1-dx*q,y-y1-dy*q)<=r;}
function paint(atlas, region, fn){const [ox,oy,w,h]=region;for(let y=0;y<h;y++)for(let x=0;x<w;x++){const color=fn(x+.5,y+.5)||[0,0,0,0],i=((oy+y)*atlas.w+ox+x)*4;for(let c=0;c<4;c++)atlas.data[i+c]=color[c];}}
for(const [name,face] of Object.entries(design.faces)){
 const atlas={w:base.w,h:base.h,data:Buffer.from(base.data)};
 for(const region of Object.values(regions))paint(atlas,region,()=>null);
 for(const side of ['left','right']){
  const mirror=side==='left';
  paint(atlas,regions[side+'Eye'],(xx,y)=>{
   const x=mirror?15-xx:xx;
   if(face.eyes==='closed'){
    const curve=5.3+0.10*(x-7.5)**2;
    return x>2&&x<13&&Math.abs(y-curve)<.72?pal.ink:null;
   }
   const top=face.eyes==='tired'?5.0:0.6+face.upper_lid_slope*(15-x);
   if(y<top||!ellipse(x,y,7.5,6.9,6.7,5.0))return null;
   if(y<top+.85)return pal.ink;
   if(!ellipse(x,y,7.6,6.9,...face.pupil_radius))return pal.sclera;
   if(ellipse(x,y,9.1,4.5,1.65,1.55)||ellipse(x,y,5.6,9.5,1.05,.65))return pal.highlight;
   return pal.ink;
  });
  paint(atlas,regions[side+'Brow'],(xx,y)=>{const x=mirror?17-xx:xx;const yy=1.4+face.brow_slope*(14-x);return x>2&&x<15&&Math.abs(y-yy)<.82?pal.ink:null;});
  paint(atlas,regions[side+'Cheek'],(x,y)=>ellipse(x,y,5,3,4.3,1.5)?[...pal.cheek.slice(0,3),255]:null);
 }
 paint(atlas,regions.mouth,(x,y)=>{
  if(face.mouth==='tight_line')return line(x,y,6.5,12.2,21.5,13.0,.90)?pal.ink:null;
  if(face.mouth==='closed')return line(x,y,6,13,22,13,.65)?pal.ink:null;
  if(face.mouth==='exhale'){
   if(!ellipse(x,y,14,12.6,6.8,3.8))return null;
   return ellipse(x,y,14,12.6,5.5,2.6)?pal.mouth:pal.ink;
  }
  if(!ellipse(x,y,14,12.5,9.5,5.5))return null;
  if(y<9.5+Math.abs(x-14)*.08)return pal.ink;
  return ellipse(x,y,14,12.9,8.2,4.3)?pal.mouth:pal.ink;
 });
 encode(atlas.w,atlas.h,atlas.data,path.join(__dirname,'variant',name+'.png'));
 console.log('wrote',name+'.png');
}
