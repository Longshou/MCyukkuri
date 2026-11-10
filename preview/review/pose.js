/* Core-backed preview adapter. Composition, overlays and skinning live in pose/ only. */
(function(root){
const request=typeof module!=='undefined'?require('../../scripts/pose_client.cjs').request:value=>{
 const xhr=new XMLHttpRequest();xhr.open('POST','/pose',false);xhr.setRequestHeader('Content-Type','application/json');xhr.send(JSON.stringify(value));
 const result=JSON.parse(xhr.responseText);if(result.error)throw Error(result.error);return result;
};
function spliceFit(model,fit){if(!fit)return model;const result=request({op:'register',model,fit});request({op:'release',id:result.id});return result.model;}
class RuntimePose{
 constructor(model,scale=1,revision=null,fit=null){
  const result=request({op:'register',model,revision,fit});this.id=result.id;this.model=result.model;this.scale=scale;
  this.clips={...this.model.clips,...this.model.behavior_clips};this.rootBone=this.model.bones.findIndex(b=>b.name==='root');this.expression('neutral');
 }
 expressionFor(action,t,fallback='neutral'){
  const clip=this.clips[action];t=clip.loop?t%clip.duration:Math.max(0,Math.min(t,clip.duration));
  return (clip.expression_spans||[]).find(s=>t>=s[0]&&t<s[1])?.[2]||fallback;
 }
 expression(name){this.selectedExpression=name;}
 dispose(){request({op:'release',id:this.id});}
 frame(action,t,adjustment=null,intensity=null,care=null){
  const result=request({op:'render',id:this.id,action,time:t,adjustment,intensity,expression:this.selectedExpression,scale:this.scale,...(care?{care}: {}),...(this.retention?{retention:this.retention}:{})});
  this.output=Float64Array.from(result.vertices);this.world=result.world;this.rootPose=result.root_pose;return this.output;
 }
 careFrame(action,t,anchor,target,blend=1){return this.frame(action,t,null,null,{anchor,target,blend});}
 rootPoint(point){const m=this.world[this.rootBone];return [0,1,2].map(r=>m[r*4]*point[0]+m[r*4+1]*point[1]+m[r*4+2]*point[2]+m[r*4+3]);}
}
root.RuntimePose=RuntimePose;root.spliceFit=spliceFit;if(typeof module!=='undefined')module.exports={RuntimePose,spliceFit};
})(typeof window==='undefined'?globalThis:window);
