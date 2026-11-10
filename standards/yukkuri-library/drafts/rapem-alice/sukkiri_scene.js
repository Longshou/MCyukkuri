'use strict';
// The ordinary pair sukkiri as the game stages it, sampled offline for review strips: ACCEPT (28 t, face to face, greet),
// ALIGN (she turns her back at 8 deg/t while he walks to her rear), CONTACT (shared clock from contact start, root cue per
// role, layered anatomy, faces) and HEAT_RELIEVED (120 t relief cue, lines at 1/46/91). Positions follow Courtship.rearStand
// and the body push between two yukkuri. 'before' reads a given cue file with the current fixed faces and the 20 t loop;
// 'after' reads pose-cues.json, samples the cue over the whole contact and its face tables.
//   node sukkiri_scene.js <out-dir> [--before <cues.json>]
const fs=require('fs'),path=require('path');
const {render}=require('./render_sukkiri.js');
const {encode}=require('./png.js');
const UNITS=16,ACCEPT=28,REQUIRED=60,RELIEF=120,TURN=8;
const smooth=x=>{x=Math.max(0,Math.min(1,x));return x*x*(3-2*x);};
function sample(keys,progress){
 let i=0;while(i+2<keys.length&&progress>keys[i+1][0])i++;
 const a=keys[i],b=keys[i+1],m=smooth((progress-a[0])/(b[0]-a[0]));
 return a.slice(1).map((v,k)=>v+(b[k+1]-v)*m);
}
function timeline(table,at,fallback){let face=fallback;for(const [t,f] of table||[])if(at>=t)face=f;return face;}
// The accept and align faces are the moment rows the game plays (presentation/moments.json).
const MOMENTS=JSON.parse(fs.readFileSync(path.join(__dirname,'../../presentation/moments.json'),'utf8')).moments;
const OLD_ACCEPT=[[0,'happy'],[.8,'closed_smile'],[1.3,'open_smile']];
const OLD_ALIGN=[[0,'closed_smile'],[.15,'satisfied'],[.3,'closed_smile'],[.55,'satisfied'],[.7,'closed_smile']];
// One pair: sizes, species, centre distance once he stands behind her (blocks) and the court duration.
// Designs: 'before' the previous data and fixed faces; 'live' this data with the game's current placement and fixed contact
// and relief faces; 'after' this data with the specified wiring (face tables, father standing at the rear stand).
function pairState(design,cues,tick,pair){
 const gm=pair.gm||1,gf=pair.gf||1,mother=pair.mother||'reimu',father=pair.father||'marisa';
 const contactDistance=(design==='after'?pair.afterDistance:pair.distance)*UNITS*(gm+gf)/2,startDistance=1.2*UNITS;
 const align=pair.align||24,c0=ACCEPT+align,c1=c0+REQUIRED;
 const faces=cues.faces||{};
 const m={id:mother,g:gm,x:0,z:0,yaw:0,clip:'idle',t:tick/20,face:'neutral',cue:null,anatomy:null};
 const f={id:father,g:gf,x:0,z:contactDistance,yaw:0,clip:'idle',t:tick/20,face:'neutral',cue:null,anatomy:null};
 let phase;
 if(tick<ACCEPT){
  phase='accept';const s=tick/20;
  m.yaw=180;f.z=startDistance;m.clip=f.clip='greet';m.t=f.t=s;
  m.face=f.face=timeline(design==='before'?OLD_ACCEPT:MOMENTS.behavior_pairing_accept.steps[0].faces,s,'happy');
 }else if(tick<c0){
  phase='align';const a=tick-ACCEPT;
  m.yaw=Math.max(0,180-TURN*a);
  const walk=smooth(a/align);f.z=startDistance+(contactDistance-startDistance)*walk;
  f.clip='waddle';f.t=a/20;
  m.clip=MOMENTS.behavior_pairing.steps[0].clip;m.t=a/20;
  if(design==='before'){m.clip='nuzzle';m.face=timeline(OLD_ALIGN,(a/20)%.8,'closed_smile');f.face='open_smile';}
  else m.face=f.face=timeline(MOMENTS.behavior_pairing.steps[0].faces,(a/20)%1,'flushed_smile');
 }else if(tick<c1){
  phase='contact';const c=tick-c0,amount=smooth(c/5);
  m.anatomy={part:'mamu',amount};f.anatomy={part:'peni',amount};
  if(design==='before'){
   m.clip=f.clip='nuzzle';m.t=f.t=c/20;
   m.cue=sample(cues.pair_mamu,(c%20)/20);f.cue=sample(cues.pair_peni,(c%20)/20);
  }else{const p=c/REQUIRED;m.cue=sample(cues.pair_mamu,p);f.cue=sample(cues.pair_peni,p);}
  if(design==='after'){const p=c/REQUIRED;m.face=timeline(faces.pair_mamu,p,'flushed_smile');f.face=timeline(faces.pair_peni,p,'flushed_smile');}
  else{m.face='satisfied';f.face='flushed_tongue';}
 }else{
  phase='relief';const r=tick-c1,p=Math.min(1,r/RELIEF),retract=1-Math.min(1,r/13);
  if(retract>0){m.anatomy={part:'mamu',amount:retract};f.anatomy={part:'peni',amount:retract};}
  m.cue=sample(cues.relief,p);f.cue=sample(cues.relief,p);
  m.face=f.face=design==='after'?timeline(faces.relief,p,'satisfied'):'satisfied';
 }
 return {phase,actors:[m,f]};
}
const VIEWS={
 side:{az:Math.PI/2,el:.12,span:12,centerY:7,shift:-8},
 front:{az:.62,el:.2,span:12,centerY:7,shift:-7},
 rear:{az:2.55,el:.32,span:12,centerY:7,shift:-8}
};
// Centre the pair: shift every actor along z so the contact midpoint sits at the camera's centre.
function framed(actors,view){return actors.map(a=>({...a,z:a.z+view.shift}));}
function strip(file,tiles,width,height){
 const out=Buffer.alloc(width*tiles.length*height*4);
 tiles.forEach((t,i)=>{for(let y=0;y<height;y++)t.copy(out,(y*width*tiles.length+i*width)*4,y*width*4,(y+1)*width*4);});
 encode(width*tiles.length,height,out,file);
}
module.exports={pairState,VIEWS,framed,strip,sample,timeline,ACCEPT,REQUIRED,RELIEF};
if(require.main===module){
 const out=process.argv[2];fs.mkdirSync(out,{recursive:true});
 const at=process.argv.indexOf('--before');
 const read=p=>JSON.parse(fs.readFileSync(p,'utf8'));
 const now=read(path.join(__dirname,'pose-cues.json')),designs={};
 if(at>0)designs.before=read(process.argv[at+1]);
 designs.live=now;designs.after=now;
 const beats=JSON.parse(fs.readFileSync(path.join(__dirname,'sukkiri-beats.json'),'utf8'));
 const W=340,H=260,meta={beats:beats.strip,sizes:beats.sizes,rows:[],animations:[]};
 const rows={before:['side','front'],live:['side','front'],after:['side','front','rear']};
 for(const [design,cues] of Object.entries(designs))for(const name of rows[design]){
  const view=VIEWS[name],tiles=beats.strip.map(b=>render({camera:view,actors:framed(pairState(design,cues,b.tick,beats.pair).actors,view)},W,H));
  const file=`${design}-${name}.png`;strip(path.join(out,file),tiles,W,H);meta.rows.push({design,view:name,file});
 }
 for(const design of Object.keys(designs).filter(d=>d!=='live')){
  const tiles=beats.sizes.map(s=>{const view=VIEWS[s.view||'side'];
   return render({camera:view,actors:framed(pairState(design,designs[design],s.tick,{...beats.pair,...s}).actors,view)},W,H);});
  const file=`${design}-sizes.png`;strip(path.join(out,file),tiles,W,H);meta.rows.push({design,view:'sizes',file});
 }
 // The Alice act: she holds the pinned body from behind at the act's contact (0.15 block box gap) on the 20 t thrust clock.
 for(const design of Object.keys(designs).filter(d=>d!=='live'))for(const name of ['side','front']){
  const view=VIEWS[name],cues=designs[design];
  const tiles=[0,4,8,12,16,20].map(t=>render({camera:view,actors:framed([
   {id:'reimu',g:1,x:0,z:0,yaw:0,clip:'tremble',t:t/20,face:'squeezed_cry_tears',cue:sample(cues.pinned,(t%20)/20),anatomy:{part:'mamu',amount:1}},
   {id:'alice',g:1,x:0,z:.95*UNITS,yaw:0,clip:'idle',t:t/20,face:'flushed_tongue',cue:sample(cues.act,(t%20)/20),anatomy:{part:'peni',amount:1}}],view)},W,H));
  const file=`alice-${design}-${name}.png`;strip(path.join(out,file),tiles,W,H);meta.rows.push({design,view:'alice-'+name,file});
 }
 // Animation frames (front view, every 2 ticks from the end of the accept to the end of the relief) for the review GIFs.
 const a=beats.animation;
 for(const design of Object.keys(designs)){
  const dir=path.join(out,'anim-'+design);fs.mkdirSync(dir,{recursive:true});let n=0;
  for(let t=a.from;t<=a.to;t+=a.step)encode(W,H,render({camera:VIEWS.front,actors:framed(pairState(design,designs[design],t,beats.pair).actors,VIEWS.front)},W,H),path.join(dir,String(n++).padStart(4,'0')+'.png'));
  meta.animations.push({design,dir:'anim-'+design,step:a.step});
 }
 fs.writeFileSync(path.join(out,'strips.json'),JSON.stringify(meta,null,1));
 console.log('wrote',meta.rows.length,'strips and',meta.animations.length,'animations to',out);
}
