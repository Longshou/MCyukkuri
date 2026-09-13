const ease=x=>{x=Math.max(0,Math.min(1,x));return x*x*(3-2*x);};
const rigCache=new Map();
function actorRig(strength,fear=false){
 const key=strength+':'+fear;if(rigCache.has(key))return rigCache.get(key);
 const rig=JSON.parse(JSON.stringify(DATA.rig));
 const tracks=rig.clips.puff.tracks;
 for(const [name,chs] of Object.entries(tracks)){
  if(/^puff_(left|right)/.test(name)||/^face_blush_/.test(name))for(const [ch,keys] of Object.entries(chs))for(const [t,v] of Object.entries(keys))keys[t]=v.map((x,axis)=>{const amount=ch==='scale'&&axis===1&&/^puff_(left|right)$/.test(name)?Math.min(1,strength):strength;return ch==='scale'?1+(x-1)*amount:x*amount;});
 }
 if(fear)for(const side of ['left','right']){
  tracks['puff_eye_'+side]={position:{0:[0,0,0]}};
  tracks['face_eye_'+side]={position:{0:[0,0,0]},scale:{0:[1,1,1]}};
  tracks['face_brow_'+side]={};
 }
 if(fear){tracks.puff_mouth={position:{0:[0,0,0]}};tracks.face_mouth={position:{0:[0,0,0]},scale:{0:[1,1,1]}};}
 rigCache.set(key,rig);return rig;
}
function encounter(t,strengths=[1.25,.8]){
 const diff=strengths[0]-strengths[1],winner=Math.abs(diff)<.12?-1:diff>0?0:1;
 const triggered=winner!==-1&&t>=1.95;
 const actors=strengths.map((strength,i)=>{
  const dir=i===0?-1:1,loser=triggered&&i!==winner;
  let phase=Math.min(t,2.5),clip='puff',texture='neutral',x=dir*8.2,y=0,yaw=dir*.20,state=t<.65?'观察对方':'鼓腮';
  let rig=actorRig(strength);
  if(loser){
   rig=actorRig(strength,t>=2.2);texture=t>=2.2?'surprise':'neutral';state='吓一跳';
   phase=3.65+.85*ease((t-1.95)/.25);
   x+=dir*.65*ease((t-1.95)/.35);
   y=.45*Math.sin(Math.PI*Math.min(1,(t-1.95)/.4));
   if(t>=2.65){
    state='转身逃跑';yaw=dir*.2+(-dir*Math.PI/2-dir*.2)*ease((t-2.65)/.4);
    const run=Math.max(0,t-2.85);x+=dir*(2*run+2*run*run);
    y=.28*Math.abs(Math.sin(run*12));clip='waddle';phase=run*1.8;
   }
  }else if(triggered){
   state=t<4?'噗咕！':t<5?'放松下来':'看着对方逃走';
   phase=t<4?2.5:Math.min(5.4,3.65+(t-4));
   if(t>=5.4){clip='stretch';phase=0;texture='smug';}
  }else if(t>=3.6){state='势均力敌，谁也没退';phase=Math.min(5.4,3.65+(t-3.6));}
  const v=poseVertices(DATA.vertices,rig,phase,true,false,clip),c=Math.cos(yaw),s=Math.sin(yaw),scale=.8;
  for(let j=0;j<v.length;j+=8){
   const px=v[j],pz=v[j+2],nx=v[j+5],nz=v[j+7];
   v[j]=x+scale*(px*c+pz*s);v[j+1]=scale*v[j+1]+y;v[j+2]=scale*(-px*s+pz*c);
   v[j+5]=nx*c+nz*s;v[j+7]=-nx*s+nz*c;
  }
  return {vertices:v,texture,x,y,yaw,state,strength};
 });
 return {actors,winner,triggered};
}
if(typeof module!=='undefined')module.exports={encounter,actorRig};
