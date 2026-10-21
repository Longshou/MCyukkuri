/* Extent masks for authored heat damage. No isolated thermal wound or cutout. */
'use strict';
const recipe=require('./recipe.json'),sources=require('./sources.json');
function createThermal(spec,parts,vertices){
  const cause=sources.causes[spec.cause];
  if(!cause||cause.kind!=='scorch'||cause.scope!==spec.scope)throw Error('Thermal cause/scope mismatch');
  if(![1,2,3].includes(spec.severity))throw Error('Invalid thermal severity');
  const model=recipe.models[spec.model];if(!model)throw Error('Missing thermal model');
  const included=new Set(parts.filter(p=>p.name==='rounded_body_surface'||p.name.startsWith('face_')||model.hair_parts.includes(p.name)||model.thermal_extra_hair_parts.includes(p.name)).map(p=>p.name));
  const [low,high]=recipe.thermal.sole_y;
  const dry=recipe.thermal.dry_shape;
  const strength=spec.scope==='whole_body'?dry.width_loss[spec.severity-1]:0;
  const factor=y=>1-strength*(dry.minimum_inset+(1-dry.minimum_inset)*Math.exp(-(((y-dry.cheek_height)/dry.cheek_falloff)**2)));
  const offsets=new Map();
  // Ornaments keep their own dimensions and follow the attachment region inward.
  for(const part of parts){
    if(included.has(part.name)||!vertices)continue;
    let x=0,y=0;
    for(let j=part.start;j<part.start+part.count;j++){x+=vertices[j*8];y+=vertices[j*8+1];}
    x/=part.count;y/=part.count;offsets.set(part.name,x*(factor(y)-1));
  }
  return {
    // Height and depth are invariant. Width loss varies with height: cheeks and
    // lower face hollow inward, not a uniform scale of the model or its eyes.
    deform(point,part){return [included.has(part)?point[0]*factor(point[1]):point[0]+(offsets.get(part)||0),point[1],point[2]];},
    normal(point,normal,part){
      if(!included.has(part))return normal;
      const f=factor(point[1]),df=(factor(point[1]+.001)-factor(point[1]-.001))/.002;
      const n=[normal[0]/f,normal[1]-point[0]*df*normal[0]/f,normal[2]],length=Math.hypot(...n);
      return n.map(v=>v/length);
    },
    widthAt:y=>factor(y),
    coverage(point,part){
      if(!included.has(part))return 0;
      if(spec.scope==='whole_body')return part.startsWith('face_')?.35:1;
      if(part!=='rounded_body_surface')return 0;
      const t=Math.max(0,Math.min(1,(point[1]-low)/(high-low)));
      return 1-t*t*(3-2*t);
    },
    // Triplanar coordinates avoid a front-facing decal. The texture tiles across
    // all selected surfaces; the sole mask is continuous around the underside.
    samples(point,normal){
      const weights=normal.map(n=>Math.abs(n)**4),sum=weights.reduce((a,b)=>a+b,0);
      return [[point[2],point[1]],[point[0],point[2]],[point[0],point[1]]].map((pair,i)=>[
        weights[i]/sum,...pair.map(q=>((q/recipe.thermal.texture_world_scale)%1+1)%1)
      ]);
    }
  };
}
module.exports={createThermal};
