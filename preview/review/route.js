/* Shared URL contract for direct motion-review links; also checked without a browser. */
(function(root){
function viewerRoute(search){
 const params=new URLSearchParams(search);
 return {model:params.get('model'),action:params.get('clip')||params.get('action')||'idle',
         expression:params.get('expression')||'auto',autoplay:params.get('autoplay')==='1'};
}
root.viewerRoute=viewerRoute;
if(typeof module!=='undefined')module.exports={viewerRoute};
})(typeof window==='undefined'?globalThis:window);
