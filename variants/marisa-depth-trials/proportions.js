function variantData(id){return DATA.models[id];}
function fleePose(id,t,angle=2.3){
 const model=variantData(id),run=Math.max(0,t-1),q=Math.max(0,Math.min(1,(t-.6)/.4)),yaw=angle*q*q*(3-2*q);
 const v=poseVertices(model.vertices,model.rig,run*1.8,true,false,'waddle');
 const c=Math.cos(yaw),s=Math.sin(yaw),hop=(t<.6?.3*Math.sin(Math.PI*t/.6):.28*Math.abs(Math.sin(run*12)));
 for(let j=0;j<v.length;j+=8){const x=v[j],z=v[j+2],nx=v[j+5],nz=v[j+7];v[j]=x*c+z*s;v[j+2]=-x*s+z*c;v[j+1]+=hop;v[j+5]=nx*c+nz*s;v[j+7]=-nx*s+nz*c;}
 return {vertices:v,state:t<.6?'受惊后缩':t<1?'转身':'逃跑 · 跟随观察',spec:model.spec};
}
