/* Compare the migrated consumers with a Git baseline on shared runtime inputs. No browser. */
'use strict';
const fs=require('fs'),path=require('path'),vm=require('vm'),cp=require('child_process');
const root=path.resolve(__dirname,'..');if(!process.argv[2])throw Error('Usage: node scripts/check_pose_consumers.cjs <before-ref>');
const ref=cp.execFileSync('git',['rev-parse',process.argv[2]],{cwd:root,encoding:'utf8'}).trim();
const before=p=>cp.execFileSync('git',['show',ref+':'+p],{cwd:root,encoding:'utf8',maxBuffer:32*1024*1024});
const old={module:{exports:{}}};vm.runInNewContext(before('preview/review/pose.js'),old);
const {RuntimePose:New}=require('../preview/review/pose.js'),Old=old.module.exports.RuntimePose;
const read=p=>JSON.parse(fs.readFileSync(path.join(root,'src/main/resources/assets/mcyukkuri',p),'utf8'));
const motions=read('yukkuri_presentation/motion_corrections.json').models,fits=read('yukkuri_presentation/placement_fit.json').models;
const raster={};vm.runInNewContext(fs.readFileSync(path.join(root,'standards/yukkuri-library/runtime/render.js'),'utf8'),raster);
const actionSource=before('scripts/render_preview_actions.cjs'),legacy={};
vm.runInNewContext(actionSource.slice(actionSource.indexOf('function bodyFloor('),actionSource.indexOf('function careTarget('))+
 actionSource.slice(actionSource.indexOf('function groundedCarePose('),actionSource.indexOf('function actorPerformance(')),legacy);
const rows=[];
for(const id of ['reimu','marisa','house_reimu','reimu_deibu']){
 const model=read('yukkuri/'+id+'.json'),scale=id.includes('deibu')?1.5:1;
 const a=new Old(model,scale,motions[id],fits[id]),b=new New(model,scale,motions[id],fits[id]);
 for(const clip of ['idle','hop','puff','first_toilet_assist','bank_beckon','boat_paddle'])for(const t of [0,.13,.4]){
  if(!a.clips[clip])continue;
  a.expression(a.expressionFor(clip,t));b.expression(b.expressionFor(clip,t));
  const x=a.frame(clip,t),y=b.frame(clip,t);if(x.length!==y.length)throw Error('Vertex count '+id);
  if(process.argv.includes('--inject-vertex'))y[0]+=1;
  let max=0;for(let i=0;i<x.length;i++)if(i%8<3)max=Math.max(max,Math.abs(x[i]-y[i]));
  const texture=new Uint8Array([205,185,155,255]),options={centerY:8,textureSize:1};
  const px=raster.renderModel(x,texture,128,128,.45,.15,20,options),py=raster.renderModel(y,texture,128,128,.45,.15,20,options);
  let changed=0,sum=0;for(let i=0;i<px.length;i+=4){let delta=0;for(let c=0;c<3;c++){delta+=Math.abs(px[i+c]-py[i+c]);sum+=Math.abs(px[i+c]-py[i+c]);}if(delta)changed++;}
  const fraction=changed/(128*128),mean=sum/(128*128*3);
  rows.push({id,clip,t,maxPosition:max,changedFraction:fraction,meanChannelDelta:mean});
  // Float core vs double preview: 1e-4 mu, <1% changed pixels, <1 channel level mean.
  if(max>1e-4||fraction>.01||mean>1)throw Error('Parity '+JSON.stringify(rows.at(-1)));
 }
 b.dispose();
}
const model=read('yukkuri/reimu.json'),anchor=read('yukkuri_presentation/living.json').variants.reimu.mouth;
const a=new Old(model,1,motions.reimu,fits.reimu),b=new New(model,1,motions.reimu,fits.reimu);
for(const t of [0,.13,.4]){
 const x=legacy.groundedCarePose(a,'feed_child',t,anchor,[0,5,-6]),y=b.careFrame('feed_child',t,anchor,[0,5,-6]);
 let max=0;for(let i=0;i<x.length;i++)if(i%8<3)max=Math.max(max,Math.abs(x[i]-y[i]));
 if(max>1e-4)throw Error('Feeding care parity '+max);
 console.log('PASS action feeding solver',t,'max position',max);
}
legacy.groundedCarePose(a,'first_toilet_assist',.2,anchor,[0,-6,-5]);b.careFrame('first_toilet_assist',.2,anchor,[0,-6,-5]);
if(!(a.rootPose[3]<-12&&b.rootPose[3]>=-12))throw Error('Stale 28 degree care control did not expose old solver');
console.log('PASS stale care control: old pitch',a.rootPose[3],'core pitch',b.rootPose[3]);b.dispose();
const out=path.join(root,'.local/reports/pose-consumers-render.json');fs.mkdirSync(path.dirname(out),{recursive:true});fs.writeFileSync(out,JSON.stringify({ref,tolerance:{position:1e-4,changedFraction:.01,meanChannelDelta:1},rows},null,2));
console.log('PASS RuntimePose / hub renderer:',rows.length,'frames; max position',Math.max(...rows.map(r=>r.maxPosition)),'max pixel fraction',Math.max(...rows.map(r=>r.changedFraction)));
