/* Compare the current facial ink against the complete model at the same pose. */
const fs=require('fs'),path=require('path'),dir=process.argv[2],lib=process.argv[3];
const rendererSource=fs.readFileSync(path.join(lib,'runtime/render.js'),'utf8');
const culling='if(nx*eye[0]+ny*eye[1]+nz*eye[2]<=0)continue;';
if(!rendererSource.includes(culling))throw Error('Shared rasterizer changed; review the NoCull adapter.');
const renderer=rendererSource.replace(culling,'');
const poseSource=fs.readFileSync(path.resolve(lib,'../../.local/previews/body-standard/pose.js'),'utf8');
const sampling='sample(model.clips.idle,0,poses);sample(model.clips[action],t,poses);';
if(!poseSource.includes(sampling))throw Error('Shared pose sampler changed; review the carry overlay adapter.');
const triangleLoop='for(let first=part.start;first<part.start+part.count;first+=3){';
if(!poseSource.includes(triangleLoop))throw Error('Shared pose topology changed; review face filtering.');
const poseCode=poseSource.replace(sampling,"sample(model.clips.idle,0,poses);if(action==='carry_waddle'){sample(model.clips.house_carry,t+.37,poses);sample(model.clips.waddle,t,poses);}else sample(model.clips[action],t,poses);")
 .replace(triangleLoop,triangleLoop+"if(model.face_front_only&&part.name.startsWith('face_')&&src[first*8+7]>=-.15)continue;");
const {posed,renderModel}=new Function(renderer+'\n'+poseCode+'\nreturn {posed,renderModel};')();
const {models,expressions,actions,legacyFaceBacks=false,phases=Array.from({length:25},(_,i)=>i/24)}=JSON.parse(fs.readFileSync(path.join(dir,'input.json')));
const failures=[],counts={};let samples=0,hiddenPixels=0;
for(const [id,m] of Object.entries(models))for(const expr of expressions){
 const texture=fs.readFileSync(path.join(dir,id+'-'+expr+'.rgba'));
 m.clips.carry_waddle=m.clips.waddle;
 for(const action of actions)for(const az of [0,.55])for(const phase of phases){
  const time=m.clips[action].duration*phase,opts={centerY:2.1,textureSize:128};
  const draw=(parts,face_front_only)=>renderModel(posed({...m,parts,face_front_only},action,time),texture,320,240,az,.06,3.2,opts);
  // Expected facial ink NEVER includes opaque card backs, including in the
  // legacy reproduction. Otherwise two equally obscured images falsely pass.
  const full=draw(m.parts,!legacyFaceBacks),expected=draw(m.parts.filter(p=>p.name.startsWith('face_')),true);
  let visible=0;
  for(const name of ['face_eye_left','face_eye_right','face_mouth']){
   const face=posed({...m,parts:m.parts.filter(p=>p.name===name),face_front_only:true},action,time);
   const pixels=renderModel(face,texture,320,240,az,.06,3.2,opts);
   let total=0,hidden=0;
   for(let i=0;i<pixels.length;i+=4)if(pixels[i+3]){
    total++;if(expected[i]!==full[i]||expected[i+1]!==full[i+1]||expected[i+2]!==full[i+2])hidden++;
   }
   samples++;hiddenPixels+=hidden;visible+=total;
   counts[action]=(counts[action]||0)+1;
   if(hidden)failures.push({id,expression:expr,action,az,phase,time,name,total,hidden});
  }
  if(!visible)throw Error('Face is entirely outside the verification view: '+id+' '+action+' '+phase);
 }
}
const result={status:failures.length?'failed':'passed',samples,counts,hidden_pixels:hiddenPixels,failures,
 rendering:{material:'NoCull',body_and_accessories:'all_faces',face_front_filter:!legacyFaceBacks,
 expected:'front_face_ink_only',front_normal_z_threshold:-.15}};
fs.writeFileSync(path.join(dir,'results.json'),JSON.stringify(result,null,2));
console.log(JSON.stringify({samples,counts,hidden_pixels:hiddenPixels,failures:failures.length}));
if(failures.length)process.exitCode=1;
