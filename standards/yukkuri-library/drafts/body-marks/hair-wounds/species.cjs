'use strict';
const catalog=require('./species.json');
function modelInfo(id){const m=catalog.models[id];if(!m)throw Error('Unregistered wound receiver '+id);return m;}
function fitFor(id){
  const {scale:s,offset:o}=modelInfo(id).fit;
  const normalize=n=>{const l=Math.hypot(...n);return l?n.map(v=>v/l):[0,1,0];};
  return {scale:s,offset:o,
    toCanonical:p=>p.map((v,i)=>(v-o[i])/s[i]),toModel:p=>p.map((v,i)=>v*s[i]+o[i]),
    normalToCanonical:n=>normalize(n.map((v,i)=>v*s[i])),normalToModel:n=>normalize(n.map((v,i)=>v/s[i]))};
}
module.exports={modelInfo,fitFor};
