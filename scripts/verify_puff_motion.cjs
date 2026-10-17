/* Whole-body puff regression (v10). Offline; never drives a browser or the game.

   Per model: the puff is a root-only transform clip of 2.8 s that starts and ends at rest, no cheek morph
   data is shipped, the swell stays inside the amplitude budget, whole-body pitch and roll stay far under
   the 12 degree limit, the lowest point never leaves the floor, the face timeline is neutral-free until the
   swell and ends on cheek_puff, injury faces yield, and taiso re-uses the same fractions for 10.4-13.6 s. */
const fs=require('fs'),path=require('path');
const {RuntimePose,spliceFit}=require('../preview/review/pose.js');
const root=path.resolve(__dirname,'..'),assets=path.join(root,'src/main/resources/assets/mcyukkuri');
const fits=JSON.parse(fs.readFileSync(path.join(assets,'yukkuri_presentation/placement_fit.json'))).models;
const motion=JSON.parse(fs.readFileSync(path.join(assets,'yukkuri_presentation/motion_corrections.json')));
const fail=m=>{throw Error(m);};
function minimum(data){let min=Infinity;for(let k=0;k<data.length;k+=24){if(Math.hypot(data[k+5],data[k+6],data[k+7])<.5)continue;for(let j=0;j<3;j++)min=Math.min(min,data[k+j*8+1]);}return min;}
let checked=0;
for(const [id,rev] of Object.entries(motion.models)){
 const clip=rev.clips.puff,taiso=rev.clips.taiso,names=rev.bone_names;
 if(rev.puff_surface||rev.puff_attachments||rev.puff_offsets)fail(`${id}: cheek morph data still shipped`);
 if(clip.puff_envelope||taiso.puff_envelope)fail(`${id}: puff envelope still shipped`);
 if(Math.abs(clip.duration-2.8)>1e-9||!clip.loop)fail(`${id}: puff must be a 2.8 s loop`);
 const rootBone=names.indexOf('root'),bean=/bean/.test(id),deibu=/deibu/.test(id);
 let peakW=0,peakH=0,pitch=0,roll=0;
 for(const tr of clip.tracks){
  if(tr.bone!==rootBone)fail(`${id}: puff drives ${names[tr.bone]}`);
  const first=tr.keys[0],last=tr.keys[tr.keys.length-1];
  for(let i=1;i<4;i++)if(Math.abs(first[i]-last[i])>1e-6)fail(`${id}: puff ${tr.channel} does not return to rest`);
  if(tr.channel==='scale'){for(const k of tr.keys){peakW=Math.max(peakW,k[1]);peakH=Math.max(peakH,k[2]);}if(first.slice(1).some(v=>Math.abs(v-1)>1e-6))fail(`${id}: scale rest`);}
  if(tr.channel==='rotation')for(const k of tr.keys){pitch=Math.max(pitch,Math.abs(k[1]));roll=Math.max(roll,Math.abs(k[3]));if(Math.abs(k[2])>1e-9)fail(`${id}: puff yaws`);}
 }
 const limit=deibu?1.12:bean?1.17:1.22;
 if(peakW<1.06||peakW>limit)fail(`${id}: swell width ${peakW}`);
 if(bean&&peakH>1.08)fail(`${id}: bean stretches ${peakH}`);
 if(pitch>4||roll>2)fail(`${id}: tilt pitch ${pitch} roll ${roll}`);
 const model=spliceFit(JSON.parse(fs.readFileSync(path.join(assets,'yukkuri',id+'.json'))),fits[id]||null),scale=id.includes('deibu')?1.5:1;
 const pose=new RuntimePose(model,scale,rev),floor=minimum(pose.frame('idle',0));let worst=0;
 for(let i=0;i<=56;i++){const gap=minimum(pose.frame('puff',2.8*i/56))-floor;worst=Math.max(worst,Math.abs(gap));}
 if(worst>.035*scale)fail(`${id}: puff leaves the floor by ${worst}`);
 const spans=clip.expression_spans;
 if(spans.length!==2||spans[0][0]<.25||spans[1][2]!=='cheek_puff'||Math.abs(spans[1][1]-2.8)>1e-6)fail(`${id}: face timeline ${JSON.stringify(spans)}`);
 if(!['puff','cheek_puff'].includes(spans[0][2]))fail(`${id}: swell face`);
 if(!(clip.expression_yield||[]).includes('cry'))fail(`${id}: puff face does not yield to injury`);
 const t=taiso.expression_spans;
 if(!t||Math.abs(t[0][0]-(10.4+3.2*.1))>1e-6||Math.abs(t[1][1]-13.6)>1e-6)fail(`${id}: taiso face timeline`);
 checked++;console.log(id,'swell',peakW.toFixed(3),'x',peakH.toFixed(3),'pitch',pitch.toFixed(2),'roll',roll.toFixed(2),'floor gap',worst.toExponential(1));
}
console.log('Puff motion verified for',checked,'models');
