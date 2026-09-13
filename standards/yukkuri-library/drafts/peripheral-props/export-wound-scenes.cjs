/* Export exactly the same surface authoring function used by the interactive page. */
const fs=require('fs'),path=require('path'),crypto=require('crypto'),W=require('./wound-surface.js');
const input=JSON.parse(fs.readFileSync(path.join(__dirname,'wound-source.json'),'utf8'));
const write=(f,x)=>fs.writeFileSync(f,JSON.stringify(x));
const sha=f=>crypto.createHash('sha256').update(fs.readFileSync(f)).digest('hex');
const checks=[];let entry;
for(const species of ['reimu','marisa'])for(const side of [...Object.keys(W.sides),'multiple']){
 const recipe=W.defaults();recipe.species=species;
 recipe.wounds=side==='multiple'?[
  {...recipe.wounds[0],id:'front_cut',quantity:3},
  {...recipe.wounds[0],id:'side_dent',kind:'dent',side:'right',u:.4,v:-.72,severity:3},
  {...recipe.wounds[0],id:'back_scrape',kind:'scratch',side:'back',u:.35,v:-.64,severity:2}
 ]:[{...recipe.wounds[0],side,u:side==='front'?-.55:0,v:['top','bottom'].includes(side)?0:-.7,quantity:2}];
 const id='wounds_'+species+'_'+side,dir=path.join(__dirname,'wound-scenes',id);fs.mkdirSync(dir,{recursive:true});
 const scene=W.scene(input,recipe);delete scene.picking;
 if(!scene.vertices.every(Number.isFinite)||scene.vertices.length%24)throw Error('Invalid geometry '+id);
 if(scene.vertices.some((x,i)=>(i%8===3||i%8===4)&&(x<0||x>1)))throw Error('Invalid UV '+id);
 if(scene.anchors.length!==recipe.wounds.length)throw Error('Missing attachment '+id);
 const bb=path.join(dir,id+'.bbmodel'),mesh=path.join(dir,id+'.mesh.json'),tex=path.join(dir,'texture.png');
 write(bb,W.bbmodel(scene,input.textures[species]));write(mesh,scene);write(path.join(dir,'recipe.json'),recipe);fs.writeFileSync(tex,Buffer.from(input.textures[species].split(',')[1],'base64'));
 checks.push({id,triangles:scene.vertices.length/24,bbmodel_sha256:sha(bb),mesh_sha256:sha(mesh),texture_sha256:sha(tex),recipe_sha256:sha(path.join(dir,'recipe.json')),anchors:scene.anchors});
 if(species==='reimu'&&side==='multiple')entry={id:'body_wounds',label:'全身伤口 · 多处挂接',category:'body',note:'点击皮面定位；每处伤口独立设置类型、程度和漏馅量。保留头发与饰物遮挡，可切换身体动作检查挂接。',state:'位置与多处编辑',dims:scene.dims,triangles:scene.vertices.length/24,model:scene,texture:input.textures.reimu,bodyWounds:true,source:input.sources.reimu,controls:{},bbmodel:'../../wound-scenes/'+id+'/'+id+'.bbmodel'};
 console.log('Exported '+id);
}
write(path.join(__dirname,'wound-exports.json'),{entry,checks});
