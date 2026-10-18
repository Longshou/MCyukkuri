'use strict';
// Multi-adult offline staging. Movement routes and the cutaway are preview choices;
// timing for accepted normal pairing follows FamilyLife (28 + 32 ticks in HEAT).
const clamp=(v,a=0,b=1)=>Math.max(a,Math.min(b,v));
const smooth=v=>{v=clamp(v);return v*v*(3-2*v);};
const mix=(a,b,t)=>a+(b-a)*smooth(t);
const actor=(id,x,yaw,action,seconds,face,extra={})=>({id,x,z:0,yaw,action,seconds,face,adult:true,...extra});
const SCENES={
 normal:{duration:11,title:'正常成体｜互相接纳 → 亲近 → 成功回应',samples:[0,2.7,4.2,5.7,7.8,10.5],phases:[
  {at:0,label:'接近 · 双方可选择是否接纳',note:'courtship；仍未接纳，不推进配对计时。'},
  {at:2,label:'双方接纳 · 同步问候',note:'pairing_accept：复用完整 greet，1.4秒。'},
  {at:3.4,label:'双方亲近 · 同步动作',note:'pairing：复用 nuzzle，发情期内1.6秒；双方持续安全、可见并接纳。'},
  {at:5,label:'成功 · 双方高兴回应',note:'仅对应 conceive 成功；双方复用 shiawase 2秒。'},
  {at:7,label:'随后 · 正常早期茎生示例',note:'选取STEM结果示例；早期绿芽来自family.json。'}]},
 abnormal:{duration:25,title:'异常事后｜反应 → 茎生消耗 → 营养耗竭分支',samples:[0,3.1,8.3,11.8,13.8,16.5,18.5,21.5,24.5],phases:[
  {at:0,label:'正常对照 · 两位成体保持距离',note:'蓝眼配色适配1.12普通成体；专属异常原作形态尚未确认。'},
  {at:1.5,label:'异常接近 · 对方转开回避',note:'Alice前倾为项目状态改编；另一方转开并退后。'},
  {at:4,label:'对方继续离开 · 保持间距',note:'撤离距离为离线编排；不是游戏寻路验收。'},
  {at:5.5,label:'场景切换 · 事后状态',note:'事件结束后，茎生进度仍为0。'},
  {at:7,label:'事后反应 · 进度0，尚无茎',note:'先出现受惊与泪眼反应；胎次刚建立时不画茎。'},
  {at:10,label:'时间经过 · 有营养才逐渐长茎',note:'压缩展示进度0→0.12；茎长随实际进度连续增长。'},
  {at:14,label:'消耗分支 · 母体营养持续下降',note:'茎生持续消耗母体营养。营养仍足够可继续发育；以下演示过度消耗分支。'},
  {at:15.5,label:'过度消耗分支 · 停育仍继续负担',note:'营养低于30时停止生长；茎生负担仍继续消耗母体营养与体力。'},
  {at:18,label:'营养耗竭 · 生命开始下降',note:'持续茎生消耗使营养归零，随后进入现有耗竭伤害结算。'},
  {at:20,label:'濒死阶段 · 生命保留1，继续耗馅',note:'营养仍未恢复时，后续耗竭伤害消耗剩余馅量。'},
  {at:23,label:'严重结果 · 馅耗尽后再次受伤',note:'茎生消耗过重、营养及馅量耗尽后，进入现有死亡结算。'}]}
};
function sampleScene(name,t){
 const scene=SCENES[name];t=clamp(t,0,scene.duration);
 const phase=scene.phases.filter(p=>p.at<=t).at(-1);
 let actors=[],card=false;
 if(name==='normal'){
  const x=t<2?mix(13,8.8,t/2):8.8;
  const yaw=t<2?mix(0,45,t/2):t<5?45:mix(45,12,(t-5)/2);
  let action='waddle',seconds=t,face='happy';
  if(t>=2&&t<3.4){action='greet';seconds=t-2;face=null;}
  else if(t>=3.4&&t<5){action='nuzzle';seconds=t-3.4;face=null;}
  else if(t>=5&&t<7){action='shiawase';seconds=t-5;face=null;}
  else if(t>=7){action='idle';seconds=0;face='satisfied';}
  actors=[actor('alice',-x,-yaw,action,seconds,t<2?'breeding_period':face),actor('reimu',x,yaw,action,seconds,face)];
  if(t>=7){actors[1].action='brood';actors[1].seconds=t-7;actors[1].face='closed_smile';actors[1].stemProgress=.08*smooth((t-7)/2.5);}
 }else{
  if(t<1.5)actors=[actor('alice',-12,0,'idle',0,'breeding_period'),actor('reimu',9,0,'idle',0,'neutral')];
  else if(t<5.5){
   const q=(t-1.5)/4;
   const ax=mix(-12,-6,q),rx=mix(9,17,q),ry=mix(0,-45,(t-2)/2);
   actors=[actor('alice',ax,-42,'rapem_onset',Math.min(5.3,1.5+(t-1.5)),'open_drool'),
           actor('reimu',rx,ry,t<2.1?'startle':'waddle',t-1.5,t<2.1?'surprise':'teary')];
  }else if(t<7)card={title:'事后状态',lines:['事后反应 → 进度0 → 逐渐萌茎']};
  else if(t<10)actors=[actor('reimu',0,8,t<7.8?'startle':'nervous',t-7,t<7.8?'surprise':'teary',{stemProgress:0})];
  else if(t<14)actors=[actor('reimu',0,8,'brood',t-10,'teary',{stemProgress:.12*smooth((t-10)/4)})];
  else if(t<15.5)card={title:'茎生消耗后的两种分支',lines:['营养仍足够 → 继续发育','持续消耗超过储备 → 衰弱、营养耗竭','接下来：耗竭分支']};
  else if(t<23)actors=[actor('reimu',0,8,'droop',t-15.5,'teary',{stemProgress:.12})];
  else actors=[actor('reimu',0,8,'idle',0,'sleep',{remains:true,stemProgress:0})];
 }
 return {name,t,phase,actors,card};
}
module.exports={SCENES,sampleScene};
