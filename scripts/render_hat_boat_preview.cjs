/* Offline preview of a Marisa riding the hat boat: boat prop + rider model in one depth buffer.

   node scripts/render_hat_boat_preview.cjs --out .local/preview/boat.png [--rider marisa] [--sink -3.3]
        [--shift 2.35] [--squash 0.93] [--roll hand_right_tip:-50] [--clip idle:0] [--views front,quarter,side,back,top] [--size 320]

   The rider is placed like HatBoatEntity.positionRider (0.055 blocks above the boat origin) plus the
   render-only `sink` (model units) that YukkuriRenderer applies on the boat, and `shift` toward the stern
   so she sits over the crown. Her hat parts are hidden: the hat is the boat. A flat water plane 0.025 blocks below
   the boat origin (HatBoatEntity buoyancy target) is drawn for reference. Same rasterizer and pose code as the review room. */
'use strict';
const fs=require('fs'),path=require('path'),vm=require('vm'),zlib=require('zlib'),cp=require('child_process');
const {RuntimePose}=require('../preview/review/pose.js');
const root=path.resolve(__dirname,'..'),assets=path.join(root,'src/main/resources/assets/mcyukkuri');
const args=process.argv.slice(2),value=(n,d)=>{const i=args.indexOf(n);return i>=0?args[i+1]:d;};
const rider=value('--rider','marisa'),out=path.resolve(root,value('--out','.local/preview/hat_boat.png'));
const sink=+value("--sink",0),shift=+value("--shift",0),squash=+value("--squash",1),widen=+value("--widen",1),size=+value("--size",320);
const [clip,clipT]=value('--clip','idle:0').split(':');
const VIEWS={front:[0,0.25],quarter:[Math.PI/4,0.35],side:[Math.PI/2,0.15],back:[Math.PI,0.3],top:[0.5,1.0],low:[0.4,0.05],rside:[-Math.PI/2,0.15],rlow:[-0.6,0.05],slow:[Math.PI/2,0.02]};
const views=value('--views','front,quarter,side,back,top').split(',');
const raster={};vm.runInNewContext(fs.readFileSync(path.join(root,'standards/yukkuri-library/runtime/render.js'),'utf8'),raster);
const cache=path.join(root,'.local/preview-cache');fs.mkdirSync(cache,{recursive:true});
function rgba(png){const raw=path.join(cache,path.basename(path.dirname(png))+'-'+path.basename(png,'.png')+'.rgba');
 if(!fs.existsSync(raw)||fs.statSync(raw).mtimeMs<fs.statSync(png).mtimeMs)
  cp.execFileSync('python3',['-I','-c','import sys;from PIL import Image;im=Image.open(sys.argv[1]).convert("RGBA");open(sys.argv[2],"wb").write(im.tobytes())',png,raw]);
 return new Uint8Array(fs.readFileSync(raw));}
// Rider pose (fit + motion revision as in the game), hat parts hidden.
const fits=JSON.parse(fs.readFileSync(path.join(assets,'yukkuri_presentation/placement_fit.json'))).models;
const revisions=JSON.parse(fs.readFileSync(path.join(assets,'yukkuri_presentation/motion_corrections.json'))).models;
const model=JSON.parse(fs.readFileSync(path.join(assets,'yukkuri',rider+'.json')));
const pose=new RuntimePose(model,1,args.includes('--no-motion')?null:revisions[rider]||null,fits[rider]||null);pose.expression('neutral');
// Afloat the side locks tuck inside the crown (YukkuriModel.AFLOAT_ROLL): extra roll about Z per bone, e.g.
// --roll hand_right_tip:-50,hand_left:8
const rolls=Object.fromEntries(value('--roll','').split(',').filter(Boolean).map(r=>{const [b,d]=r.split(':');return [b,+d];}));
const frame=Float64Array.from(pose.frame(clip,+clipT,(poses,bones)=>bones.forEach((b,i)=>{if(rolls[b.name])poses[i][5]+=rolls[b.name];})));
for(const p of pose.model.parts)if(/^(brim_|hat_|bow_)/.test(p.name))frame.fill(0,p.start*8,(p.start+p.count)*8);
const boat=JSON.parse(fs.readFileSync(path.join(assets,'yukkuri_props/hat_boat.json')));
// Shared 512 atlas: rider texture left, boat texture right (both 256 or smaller).
const T=512,atlas=new Uint8ClampedArray(T*T*4),put=(src,w,ox)=>{for(let y=0;y<w;y++)for(let x=0;x<w;x++){const s=(y*w+x)*4,d=(y*T+ox+x)*4;for(let k=0;k<4;k++)atlas[d+k]=src[s+k];}};
const rs=model.texture_size||128;put(rgba(path.join(assets,'textures/entity',rider,'neutral.png')),rs,0);put(rgba(path.join(assets,'textures/props/hat_boat.png')),256,256);
const verts=[];
const y0=0.055*16+sink;
for(let i=0;i<frame.length;i+=8){if(!frame[i]&&!frame[i+1]&&!frame[i+2]&&!frame[i+3])continue;
 verts.push(frame[i]*widen,frame[i+1]*squash+y0,frame[i+2]*widen+shift,frame[i+3]*rs/T,frame[i+4]*rs/T,frame[i+5],frame[i+6],frame[i+7]);}
// keep whole triangles only
const tri=[];for(let i=0;i+24<=verts.length;i+=24)tri.push(...verts.slice(i,i+24));
const bv=boat.vertices;for(let i=0;i<bv.length;i+=8)tri.push(bv[i],bv[i+1],bv[i+2],(256+bv[i+3]*256)/T,bv[i+4]*256/T,bv[i+5],bv[i+6],bv[i+7]);
// water plane (solid colour via negative u)
const W=13,wy=-0.4,water=[[-W,wy,-W+2],[W,wy,-W+2],[W,wy,W+2],[-W,wy,W+2]];
for(const [a,b,c] of [[0,2,1],[0,3,2]])for(const k of [a,b,c])tri.push(...water[k],-1,0,0,1,0);
const all=new Float64Array(tri);
const cols=views.length,sheet=new Uint8ClampedArray(cols*size*size*4);
for(let i=0;i<cols*size*size;i++){sheet[i*4]=198;sheet[i*4+1]=206;sheet[i*4+2]=218;sheet[i*4+3]=255;}
views.forEach((v,vi)=>{const [az,el]=VIEWS[v];const px=raster.renderModel(all,atlas,size,size,az,el,13,{textureSize:T,centerY:2,colors:[[96,150,196]]});
 for(let y=0;y<size;y++)for(let x=0;x<size;x++){const s=(y*size+x)*4;if(px[s+3]<1)continue;const d=(y*cols*size+vi*size+x)*4;sheet[d]=px[s];sheet[d+1]=px[s+1];sheet[d+2]=px[s+2];}});
const crc=b=>{let c=~0;for(const x of b){c^=x;for(let k=0;k<8;k++)c=c&1?0xedb88320^(c>>>1):c>>>1;}return ~c>>>0;};
const chunk=(t,d)=>{const l=Buffer.alloc(4);l.writeUInt32BE(d.length);const td=Buffer.concat([Buffer.from(t),d]),c=Buffer.alloc(4);c.writeUInt32BE(crc(td));return Buffer.concat([l,td,c]);};
const Wd=cols*size,rows=Buffer.alloc((Wd*4+1)*size);for(let y=0;y<size;y++){rows[y*(Wd*4+1)]=0;Buffer.from(sheet.buffer,y*Wd*4,Wd*4).copy(rows,y*(Wd*4+1)+1);}
const ihdr=Buffer.alloc(13);ihdr.writeUInt32BE(Wd,0);ihdr.writeUInt32BE(size,4);ihdr[8]=8;ihdr[9]=6;
fs.mkdirSync(path.dirname(out),{recursive:true});fs.writeFileSync(out,Buffer.concat([Buffer.from([137,80,78,71,13,10,26,10]),chunk('IHDR',ihdr),chunk('IDAT',zlib.deflateSync(rows)),chunk('IEND',Buffer.alloc(0))]));
console.log(`Wrote ${path.relative(root,out)}: ${rider} sink ${sink} shift ${shift} squash ${squash} ${clip}@${clipT} x ${views.join('/')}`);
