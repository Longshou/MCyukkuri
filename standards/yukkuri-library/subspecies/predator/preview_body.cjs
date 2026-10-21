/* Offline selected-body contact views. No browser or game. */
const fs=require('fs'),path=require('path'),vm=require('vm');
const root=path.resolve(__dirname,'../../../..');
const {RuntimePose}=require(path.join(root,'preview/review/pose.js'));
const assets=path.join(root,'src/main/resources/assets/mcyukkuri');
const out=path.join(root,'.local/previews/predator-body');
const corrections=JSON.parse(fs.readFileSync(path.join(assets,'yukkuri_presentation/motion_corrections.json'))).models;
const fits=JSON.parse(fs.readFileSync(path.join(assets,'yukkuri_presentation/placement_fit.json'))).models;
const raster={};vm.runInNewContext(fs.readFileSync(path.join(root,'standards/yukkuri-library/runtime/render.js'),'utf8'),raster);
for(const id of ['remilia','flandre','marisa']){
 const model=JSON.parse(fs.readFileSync(path.join(assets,'yukkuri',id+'.json')));
 const pose=new RuntimePose(model,1,corrections[id],fits[id]||null);
 const tex=fs.readFileSync(path.join(out,id+'.rgba'));
 for(const [view,action,angle] of [['rear','idle',Math.PI],['mamu','anatomy_mamu_extend',0],['peni','anatomy_peni_extend',.6]]){
  const vertices=pose.frame(action,1.5);
  const pixels=raster.renderModel(vertices,tex,480,360,angle,.2,id==='marisa'?11:8.7,{centerY:id==='marisa'?7.8:5.3,textureSize:256});
  fs.writeFileSync(path.join(out,`${id}-${view}.rgba`),Buffer.from(pixels));
 }
}
