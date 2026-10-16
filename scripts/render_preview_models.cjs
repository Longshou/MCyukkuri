/* Offline thumbnails from the same pose evaluator as the model viewer. No browser or game. */
'use strict';
const fs=require('fs'),path=require('path'),vm=require('vm');
const {RuntimePose}=require('../preview/review/pose.js');
const root=path.resolve(__dirname,'..'),out=path.join(root,'.local/previews'),work=path.join(out,'_work');
const catalogContext={window:{}};
vm.runInNewContext(fs.readFileSync(path.join(out,'models/catalog.js'),'utf8'),catalogContext);
const catalog=catalogContext.window.REVIEW_CATALOG;
const motions=JSON.parse(fs.readFileSync(path.join(root,catalog.motions),'utf8')).models;
const fits=JSON.parse(fs.readFileSync(path.join(root,catalog.fit),'utf8')).models;
const raster={};
vm.runInNewContext(fs.readFileSync(path.join(root,'standards/yukkuri-library/runtime/render.js'),'utf8'),raster);
const tasks=JSON.parse(fs.readFileSync(path.join(work,'render-tasks.json'),'utf8'));
for(const task of tasks){
 const item=catalog.models.find(m=>m.id===task.model);
 if(!item)throw Error('Unknown model '+task.model);
 const model=JSON.parse(fs.readFileSync(path.join(root,item.url),'utf8'));
 const pose=new RuntimePose(model,item.formScale,motions[item.id],fits[item.id]||null);
 if(!pose.clips[task.action])throw Error('Unknown clip '+task.model+'/'+task.action);
 pose.expression(task.face||pose.expressionFor(task.action,task.time));
 const vertices=pose.frame(task.action,task.time);
 let min=Infinity,max=-Infinity,radius=0;
 for(let i=0;i<vertices.length;i+=8){min=Math.min(min,vertices[i+1]);max=Math.max(max,vertices[i+1]);radius=Math.max(radius,Math.hypot(vertices[i],vertices[i+2]));}
 const texture=fs.readFileSync(path.join(work,task.texture));
 if(texture.length!==item.textureSize**2*4)throw Error('Texture dimensions '+task.model);
 const image=raster.renderModel(vertices,texture,task.width,task.height,task.angle??.45,.18,
   Math.max((max-min)*.66,radius*.95,4),{centerY:(min+max)/2,textureSize:item.textureSize});
 fs.writeFileSync(path.join(work,task.id+'.rgba'),Buffer.from(image));
}
console.log('Rendered '+tasks.length+' current model views offline.');
