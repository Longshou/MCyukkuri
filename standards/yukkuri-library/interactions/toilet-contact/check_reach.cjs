/* Static rear-care reach audit through the pure pose CLI; no game. */
const fs=require('fs'),path=require('path');
const root=path.resolve(__dirname,'../../../..'),assets=path.join(root,'src/main/resources/assets/mcyukkuri');
const read=p=>JSON.parse(fs.readFileSync(path.join(assets,p),'utf8'));
const {RuntimePose}=require(path.join(root,'preview/review/pose.js'));
const cfg=read('yukkuri_presentation/toilet_contact.json'),fits=read('yukkuri_presentation/placement_fit.json').models;
const anchors={...read('yukkuri_presentation/living.json').variants,...read('yukkuri_presentation/subspecies.json')};
const bodies=read('yukkuri_presentation/body_geometry.json').variants;
function width(id){return bodies[id]?(bodies[id].bounds[0][1]-bodies[id].bounds[0][0])/16:id.startsWith('house')?.27:['remilia','flandre'].includes(id)?.8:id.endsWith('_bean')?.75*.82:.75;}
const results=[],cases=Object.keys(cfg.models).map(id=>({id,child:id}));
cases.push({id:'reimu',child:'reimu_bean'},{id:'marisa',child:'marisa_bean'});
for(const {id,child} of cases){
 const revision=cfg.models[id];
 const pose=new RuntimePose(read(`yukkuri/${id}.json`),1,revision,fits[id]),anchor=anchors[id].mouth;
 const form=id.includes('deibu')?1.5:1,childForm=child.includes('deibu')?1.5:1;
 const pw=width(id)*form,cw=width(child)*.2*childForm,distance=(pw+cw)*.5+.05,target=[0,.04*16/form,(-distance+cw*.55)*16/form];let maximum=0;
 for(let tick=0;tick<10;tick+=.5){
  const time=tick/20;pose.careFrame('first_toilet_assist',time,anchor,target);const mouth=pose.rootPoint(anchor);
  maximum=Math.max(maximum,Math.hypot(...target.map((v,i)=>v-mouth[i]))/16);
 }
 results.push({id,child,caregiverEligibility:id.endsWith('_bean')?'ineligible_caregiver':'eligible_caregiver',adultScale:form,childScale:.2*childForm,standingDistance:distance,maxTongueLength:maximum,reachable:maximum<=cfg.tongue.max_reach});
 pose.dispose();
}
const output=path.join(root,'.local/reports/toilet-contact-reach.json');fs.mkdirSync(path.dirname(output),{recursive:true});fs.writeFileSync(output,JSON.stringify({note:'Offline same-form adults/0.2 children plus ordinary parents with bean-form children at nominal standing distance. Bean-form adults are excluded by parentAvailable/rearContact; their measured distance remains diagnostic. No collision/AI simulation.',limit:cfg.tongue.max_reach,results},null,2)+'\n');
for(const r of results)console.log(`${r.id} -> ${r.child}: ${r.maxTongueLength.toFixed(4)} / ${cfg.tongue.max_reach} ${r.caregiverEligibility==='ineligible_caregiver'?'INELIGIBLE_CAREGIVER':r.reachable?'PASS':'UNREACHABLE'}`);
if(results.some(r=>r.caregiverEligibility==='eligible_caregiver'&&!r.reachable))process.exitCode=1;
