/* Static rear-care reach audit; numeric pose sampling only, no game or Java compilation. */
const fs=require('fs'),path=require('path');
const root=path.resolve(__dirname,'../../../..'),assets=path.join(root,'src/main/resources/assets/mcyukkuri');
const read=p=>JSON.parse(fs.readFileSync(path.join(assets,p),'utf8'));
const {RuntimePose}=require(path.join(root,'preview/review/pose.js'));
const cfg=read('yukkuri_presentation/toilet_contact.json'),fits=read('yukkuri_presentation/placement_fit.json').models;
const anchors={...read('yukkuri_presentation/living.json').variants,...read('yukkuri_presentation/subspecies.json')};
const bodies=read('yukkuri_presentation/body_geometry.json').variants;
function floorOf(pose){let floor=Infinity;for(const part of pose.model.parts)if(part.name.includes('body_surface')||part.name.startsWith('soft_head_slice_'))for(let i=part.start;i<part.start+part.count;i++)floor=Math.min(floor,pose.output[i*8+1]);return floor;}
function width(id){return bodies[id]?(bodies[id].bounds[0][1]-bodies[id].bounds[0][0])/16:id.startsWith('house')?.27:['remilia','flandre'].includes(id)?.8:id.endsWith('_bean')?.75*.82:.75;}
const results=[],cases=Object.keys(cfg.models).map(id=>({id,child:id}));
cases.push({id:'reimu',child:'reimu_bean'},{id:'marisa',child:'marisa_bean'});
for(const {id,child} of cases){
 const revision=cfg.models[id];
 const pose=new RuntimePose(read(`yukkuri/${id}.json`),1,revision,fits[id]),anchor=anchors[id].mouth;
 const form=id.includes('deibu')?1.5:1,childForm=child.includes('deibu')?1.5:1;
 const pw=width(id)*form,cw=width(child)*.2*childForm,distance=(pw+cw)*.5+.05,target=[0,.04*16/form,(-distance+cw*.55)*16/form];let maximum=0;
 for(let tick=0;tick<10;tick+=.5){
  const time=tick/20;pose.frame('first_toilet_assist',time);const floor=floorOf(pose),basePitch=pose.rootPose[3];let low=0,high=12+basePitch;
  for(let i=0;i<7;i++){const mid=(low+high)/2;pose.frame('first_toilet_assist',time,{pitch:-mid});if(pose.rootPoint(anchor)[1]+Math.max(0,floor-floorOf(pose))>target[1]+.035*16)low=mid;else high=mid;}
  pose.frame('first_toilet_assist',time,{pitch:-high});let mouth=pose.rootPoint(anchor),dz=Math.max(-.18*16,Math.min(.18*16,target[2]+.07*16-mouth[2]));
  pose.frame('first_toilet_assist',time,{pitch:-high,translation:[0,0,dz]});const lift=Math.max(0,floor-floorOf(pose));
  pose.frame('first_toilet_assist',time,{pitch:-high,translation:[0,lift,dz]});mouth=pose.rootPoint(anchor);
  maximum=Math.max(maximum,Math.hypot(...target.map((v,i)=>v-mouth[i]))/16);
 }
 results.push({id,child,caregiverEligibility:id.endsWith('_bean')?'ineligible_caregiver':'eligible_caregiver',adultScale:form,childScale:.2*childForm,standingDistance:distance,maxTongueLength:maximum,reachable:maximum<=cfg.tongue.max_reach});
}
const output=path.join(root,'.local/reports/toilet-contact-reach.json');fs.mkdirSync(path.dirname(output),{recursive:true});fs.writeFileSync(output,JSON.stringify({note:'Offline same-form adults/0.2 children plus ordinary parents with bean-form children at nominal standing distance. Bean-form adults are excluded by parentAvailable/rearContact; their measured distance remains diagnostic. No collision/AI simulation.',limit:cfg.tongue.max_reach,results},null,2)+'\n');
for(const r of results)console.log(`${r.id} -> ${r.child}: ${r.maxTongueLength.toFixed(4)} / ${cfg.tongue.max_reach} ${r.caregiverEligibility==='ineligible_caregiver'?'INELIGIBLE_CAREGIVER':r.reachable?'PASS':'UNREACHABLE'}`);
if(results.some(r=>r.caregiverEligibility==='eligible_caregiver'&&!r.reachable))process.exitCode=1;
