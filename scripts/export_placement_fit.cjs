/* Export the placement fit: seated headwear and hair that follows the rounded head.

   Reads standards/yukkuri-library/presentation/placement-fit/recipe.json and the
   selected runtime models (rare forms carry their base's fit); writes
   yukkuri_presentation/placement_fit.json and the source manifest. Base models are
   never rewritten. --check compares without
   writing; --only a,b limits the models; --out <file> writes a trial resource
   elsewhere (manifest untouched). Offline; never launches the game or a UI. */
'use strict';
const fs=require('fs'),path=require('path'),crypto=require('crypto');
const root=path.resolve(__dirname,'..');
const source=path.join(root,'standards/yukkuri-library/presentation/placement-fit');
const {author,encode}=require(path.join(source,'author.cjs'));
const assets=path.join(root,'src/main/resources/assets/mcyukkuri');
const runtime=path.join(assets,'yukkuri_presentation/placement_fit.json');
const manifestPath=path.join(root,'art/source/placement-fit/manifest.json');
const sha=b=>crypto.createHash('sha256').update(b).digest('hex');
const rel=p=>path.relative(root,p).split(path.sep).join('/');
const args=process.argv.slice(2),flag=n=>args.includes(n),value=n=>{const i=args.indexOf(n);return i>=0?args[i+1]:null;};
const updateOnly=value('--update-only')?.split(',');
const check=flag('--check'),only=updateOnly||value('--only')?.split(','),out=value('--out');
if(updateOnly&&(out||value('--only')))throw Error('--update-only cannot be combined with --only or --out');

const recipePath=path.join(source,'recipe.json'),recipe=JSON.parse(fs.readFileSync(recipePath,'utf8'));
// Rare forms are runtime species: they carry their base's authored fit onto their own part indices.
const sub=path.join(root,'standards/yukkuri-library/subspecies/rare-forms');
const formsRecipe=JSON.parse(fs.readFileSync(path.join(sub,'recipe.json'),'utf8'));
const rootBase=form=>formsRecipe.forms[form].base_form?rootBase(formsRecipe.forms[form].base_form):formsRecipe.forms[form].base;
const wrapped=only&&new Set([...only,...Object.keys(formsRecipe.forms).filter(f=>only.includes(f)).map(rootBase)]);
let result={format:1,revision:recipe.revision,models:{}},manifest={revision:recipe.revision,status:recipe.status,
 recipe:rel(recipePath),recipe_sha256:sha(fs.readFileSync(recipePath)),author:rel(path.join(source,'author.cjs')),author_sha256:sha(fs.readFileSync(path.join(source,'author.cjs'))),
 exporter:rel(__filename),exporter_sha256:sha(fs.readFileSync(__filename)),runtime:rel(runtime),models:{}};
for(const [id,entry] of Object.entries(recipe.models)){
 if(wrapped&&!wrapped.has(id))continue;
 const file=path.join(assets,'yukkuri',id+'.json'),bytes=fs.readFileSync(file),model=JSON.parse(bytes);
 if(!entry.steps.length){manifest.models[id]={base:rel(file),base_sha256:sha(bytes),unchanged:entry.note||true};continue;}
 const {work,log}=author(model,entry),fit=encode(model,work);
 result.models[id]={base_sha256:sha(bytes),...fit};
 manifest.models[id]={base:rel(file),base_sha256:sha(bytes),parts:fit.parts.map(p=>p.name),refined:fit.parts.filter(p=>!p.inplace).map(p=>p.name),
  bone_pivots:fit.bone_pivots.map(b=>b.name),log};
 console.log(id+': '+fit.parts.length+' parts ('+fit.parts.filter(p=>!p.inplace).length+' refined), '+fit.bone_pivots.length+' pivots');
}
// Rare forms are runtime species: they carry their base's authored fit onto their own part indices.
for(const [form,spec] of Object.entries(formsRecipe.forms)){
 const base=rootBase(form);
 if(wrapped&&!wrapped.has(form)&&!wrapped.has(base))continue;
 const file=path.join(assets,'yukkuri',form+'.json'),bytes=fs.readFileSync(file),model=JSON.parse(bytes);
 const changed=new Set(),tails=spec.tail_parts||[];
 for(let level=form;level;){ // a form built on another form inherits its part changes
  const derived=JSON.parse(fs.readFileSync(path.join(sub,'assembled',level,'derived.json'),'utf8'));
  for(const op of ['added_parts','collapsed_parts','reshaped_parts'])for(const name of derived.operations[op]||[])changed.add(name);
  level=derived.base_form;
 }
 const index=new Map(model.parts.map((p,i)=>[p.name,i]));
 const baseFit=result.models[base];
 const parts=baseFit.parts.filter(p=>!changed.has(p.name)&&!tails.some(t=>p.name.startsWith(t))&&index.has(p.name))
  .map(p=>({...p,part_index:index.get(p.name)}));
 result.models[form]={base_sha256:sha(bytes),parts,bone_pivots:baseFit.bone_pivots};
 manifest.models[form]={base:rel(file),base_sha256:sha(bytes),derived_from:base,parts:parts.map(p=>p.name),
  refined:parts.filter(p=>!p.inplace).map(p=>p.name),bone_pivots:baseFit.bone_pivots.map(b=>b.name),log:['carried from '+base]};
 console.log(form+': '+parts.length+' parts carried from '+base);
}
if(updateOnly){
 const previous=JSON.parse(fs.readFileSync(runtime,'utf8')),old=JSON.parse(fs.readFileSync(manifestPath,'utf8'));
 const updated=new Set([...updateOnly,...Object.keys(result.models)]); // requested ids plus the forms they rebuilt
 // Models without fit steps live only in the manifest; refresh their record instead of dropping it.
 for(const id of updated){
  if(result.models[id])previous.models[id]=result.models[id];else delete previous.models[id];
  if(manifest.models[id])old.models[id]=manifest.models[id];else delete old.models[id];
 }
 old.exporter_sha256=manifest.exporter_sha256;result=previous;manifest=old;
}
const text=JSON.stringify(result)+'\n';
if(out){fs.mkdirSync(path.dirname(path.resolve(root,out)),{recursive:true});fs.writeFileSync(path.resolve(root,out),text);console.log('Trial written to '+out);}
else if(check){
 if(only&&!updateOnly)throw Error('--check covers the whole recipe');
 if(!fs.existsSync(runtime)||fs.readFileSync(runtime,'utf8')!==text)throw Error('Placement fit differs from the recipe; re-export');
 const old=JSON.parse(fs.readFileSync(manifestPath,'utf8'));if(old.runtime_sha256!==sha(Buffer.from(text)))throw Error('Manifest is stale');
 console.log('Placement fit matches the recipe.');
}else{
 if(only&&!updateOnly)throw Error('Use --out with --only; the runtime resource always holds the whole recipe');
 fs.writeFileSync(runtime,text);manifest.runtime_sha256=sha(Buffer.from(text));
 fs.mkdirSync(path.dirname(manifestPath),{recursive:true});fs.writeFileSync(manifestPath,JSON.stringify(manifest,null,2)+'\n');
 console.log('Wrote '+rel(runtime));
}
