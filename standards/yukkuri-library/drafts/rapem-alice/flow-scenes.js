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
  {at:3.4,label:'双方亲近 · 非露骨演出',note:'pairing：复用 nuzzle，发情期内1.6秒；双方持续安全、可见并接纳。'},
  {at:5,label:'成功 · 双方高兴回应',note:'仅对应 conceive 成功；双方复用 shiawase 2秒。'},
  {at:7,label:'随后 · 正常早期茎生示例',note:'选取STEM结果示例；早期绿芽来自family.json，无幼体画面。'}]},
 abnormal:{duration:15,title:'异常分支｜接近 / 回避 → 镜头外 → 事后照护',samples:[0,3.1,4.8,6.25,8.5,13],phases:[
  {at:0,label:'正常对照 · 两位成体保持距离',note:'蓝眼配色适配1.12普通成体；专属异常原作形态尚未确认。'},
  {at:1.5,label:'异常接近 · 对方转开回避',note:'Alice前倾为项目状态改编；另一方明确退开，始终不显示强制接触。'},
  {at:4,label:'对方继续离开 · 保持间距',note:'撤离距离为离线编排；不是游戏寻路验收。'},
  {at:5.5,label:'镜头外事件 · 过程省略',note:'完整切走画面；不展示侵犯过程。此页不表示接近必然产生后果。'},
  {at:7,label:'独立事后示例 · 茎生与疲惫',note:'展示已建立的STEM后果及aftermathBurden/Weak；不画事件过程。'},
  {at:10,label:'食物与陪伴 · 照护示意',note:'成年同伴与现有食物；陪护中，虚弱不会立即解除。不是新增自动送食AI。'}]}
};
function sampleScene(name,t){
 const scene=SCENES[name];t=clamp(t,0,scene.duration);
 const phase=scene.phases.filter(p=>p.at<=t).at(-1);
 let actors=[],food=false,card=false;
 if(name==='normal'){
  const x=t<2?mix(13,8.8,t/2):8.8;
  const yaw=t<2?mix(0,45,t/2):t<5?45:mix(45,12,(t-5)/2);
  let action='waddle',seconds=t,face='happy';
  if(t>=2&&t<3.4){action='greet';seconds=t-2;face=null;}
  else if(t>=3.4&&t<5){action='nuzzle';seconds=t-3.4;face=null;}
  else if(t>=5&&t<7){action='shiawase';seconds=t-5;face=null;}
  else if(t>=7){action='idle';seconds=0;face='satisfied';}
  actors=[actor('alice',-x,-yaw,action,seconds,t<2?'breeding_period':face),actor('reimu',x,yaw,action,seconds,face)];
  if(t>=7){actors[1].action='brood';actors[1].seconds=t-7;actors[1].face='closed_smile';actors[1].sprout=true;}
 }else{
  if(t<1.5)actors=[actor('alice',-12,0,'idle',0,'breeding_period'),actor('reimu',9,0,'idle',0,'neutral')];
  else if(t<5.5){
   const q=(t-1.5)/4;
   const ax=mix(-12,-6,q),rx=mix(9,17,q),ry=mix(0,-45,(t-2)/2);
   actors=[actor('alice',ax,-42,'rapem_onset',Math.min(5.3,1.5+(t-1.5)),'alice_agitated'),
           actor('reimu',rx,ry,t<2.1?'startle':'waddle',t-1.5,t<2.1?'surprise':'teary')];
  }else if(t<7){card=true;}
  else{
   actors=[actor('reimu',-8,8,t<10?'droop':t<12?'eat':'droop',t-7,t<10?'teary':t<12?'chewing_closed':'teary',{sprout:true})];
   if(t>=9){const q=(t-9)/2;actors.push(actor('marisa',mix(18,8,q),24,t<11?'waddle':'idle',t-9,'neutral'));}
   food=t>=10;
  }
 }
 return {name,t,phase,actors,food,card};
}
module.exports={SCENES,sampleScene};
