/* Extent masks for authored heat damage. No isolated thermal wound or cutout. */
'use strict';
const recipe=require('./recipe.json'),sources=require('./sources.json');
function createThermal(spec,parts){
  const cause=sources.causes[spec.cause];
  if(!cause||cause.kind!=='scorch'||cause.scope!==spec.scope)throw Error('Thermal cause/scope mismatch');
  if(![1,2,3].includes(spec.severity))throw Error('Invalid thermal severity');
  const model=recipe.models[spec.model];if(!model)throw Error('Missing thermal model');
  const included=new Set(parts.filter(p=>p.name==='rounded_body_surface'||p.name.startsWith('face_')||model.hair_parts.includes(p.name)||model.thermal_extra_hair_parts.includes(p.name)).map(p=>p.name));
  const [low,high]=recipe.thermal.sole_y;
  return {
    coverage(point,part){
      if(!included.has(part))return 0;
      if(spec.scope==='whole_body')return 1;
      if(part!=='rounded_body_surface')return 0;
      const t=Math.max(0,Math.min(1,(point[1]-low)/(high-low)));
      return 1-t*t*(3-2*t);
    },
    // Triplanar coordinates avoid a front-facing decal. The texture tiles across
    // all selected surfaces; the sole mask is continuous around the underside.
    uv(point,normal){
      const axis=normal.map(Math.abs).indexOf(Math.max(...normal.map(Math.abs)));
      const pair=axis===1?[point[0],point[2]]:axis===0?[point[2],point[1]]:[point[0],point[1]];
      return pair.map(q=>((q/recipe.thermal.texture_world_scale)%1+1)%1);
    }
  };
}
module.exports={createThermal};
