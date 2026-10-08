/* Export the placement fit: seated headwear and hair that follows the rounded head.

   Reads standards/yukkuri-library/presentation/placement-fit/recipe.json and the
   selected runtime models; writes yukkuri_presentation/placement_fit.json and the
   source manifest. Base models are never rewritten. --check compares without
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
const check=flag('--check'),only=value('--only')?.split(','),out=value('--out');

const recipePath=path.join(source,'recipe.json'),recipe=JSON.parse(fs.readFileSync(recipePath,'utf8'));
const result={format:1,revision:recipe.revision,models:{}},manifest={revision:recipe.revision,status:recipe.status,
 recipe:rel(recipePath),recipe_sha256:sha(fs.readFileSync(recipePath)),author:rel(path.join(source,'author.cjs')),author_sha256:sha(fs.readFileSync(path.join(source,'author.cjs'))),
 exporter:rel(__filename),exporter_sha256:sha(fs.readFileSync(__filename)),runtime:rel(runtime),models:{}};
for(const [id,entry] of Object.entries(recipe.models)){
 if(only&&!only.includes(id))continue;
 const file=path.join(assets,'yukkuri',id+'.json'),bytes=fs.readFileSync(file),model=JSON.parse(bytes);
 if(!entry.steps.length){manifest.models[id]={base:rel(file),base_sha256:sha(bytes),unchanged:entry.note||true};continue;}
 const {work,log}=author(model,entry),fit=encode(model,work);
 result.models[id]={base_sha256:sha(bytes),...fit};
 manifest.models[id]={base:rel(file),base_sha256:sha(bytes),parts:fit.parts.map(p=>p.name),refined:fit.parts.filter(p=>!p.inplace).map(p=>p.name),
  bone_pivots:fit.bone_pivots.map(b=>b.name),log};
 console.log(id+': '+fit.parts.length+' parts ('+fit.parts.filter(p=>!p.inplace).length+' refined), '+fit.bone_pivots.length+' pivots');
}
const text=JSON.stringify(result)+'\n';
if(out){fs.mkdirSync(path.dirname(path.resolve(root,out)),{recursive:true});fs.writeFileSync(path.resolve(root,out),text);console.log('Trial written to '+out);}
else if(check){
 if(only)throw Error('--check covers the whole recipe');
 if(!fs.existsSync(runtime)||fs.readFileSync(runtime,'utf8')!==text)throw Error('Placement fit differs from the recipe; re-export');
 const old=JSON.parse(fs.readFileSync(manifestPath,'utf8'));if(old.runtime_sha256!==sha(Buffer.from(text)))throw Error('Manifest is stale');
 console.log('Placement fit matches the recipe.');
}else{
 if(only)throw Error('Use --out with --only; the runtime resource always holds the whole recipe');
 fs.writeFileSync(runtime,text);manifest.runtime_sha256=sha(Buffer.from(text));
 fs.mkdirSync(path.dirname(manifestPath),{recursive:true});fs.writeFileSync(manifestPath,JSON.stringify(manifest,null,2)+'\n');
 console.log('Wrote '+rel(runtime));
}
