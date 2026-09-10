/* Deterministic, seekable art simulation. Host game must own biological time and birth events. */
(function (root) {
  'use strict';
  const clamp = x => Math.max(0, Math.min(1, x));
  const mix = (a, b, t) => a.map((v, i) => v + (b[i] - v) * t);
  const smooth = x => { x = clamp(x); return x * x * (3 - 2 * x); };
  function bezier(points, t) {
    if (points.length === 1) return points[0];
    return bezier(points.slice(1).map((p, i) => mix(points[i], p, t)), t);
  }
  function validateCount(data, count) {
    const range=data.birth.offspring_count;
    if(!Number.isInteger(count)||count<range.min||count>range.max)throw new RangeError('Offspring count must be 1–5');
    return count;
  }
  function randomCount(data, random=Math.random) {
    const q=random(),range=data.birth.offspring_count;
    if(!Number.isFinite(q)||q<0||q>=1)throw new RangeError('Random value must be in [0, 1)');
    return range.min+Math.floor(q*(range.max-range.min+1));
  }
  function detachTime(data, i, count) {
    const t=data.birth.preview_seconds;
    return t.detach_first+(count===1?0:i/(count-1)*(t.detach_last-t.detach_first));
  }
  function pathPoint(shape, t) {
    const q=clamp(t)*(shape.centerline.length-1),i=Math.min(shape.centerline.length-2,Math.floor(q));
    return mix(shape.centerline[i],shape.centerline[i+1],q-i);
  }
  function sample(data, seconds, motherScale = 1, count = data.birth.offspring_count.preview_default) {
    validateCount(data,count);
    const cfg = data.birth, timing = cfg.preview_seconds, shape = data.stem, growth = root.YukkuriGrowth;
    const adult = growth.stage(data.growth, 'adult');
    if (!Number.isFinite(seconds) || !Number.isFinite(motherScale) || motherScale < adult.min_scale || motherScale > adult.max_scale) throw new RangeError('Invalid time or adult scale');
    const time = Math.max(0, Math.min(timing.end, seconds));
    // Root detachment and its Minecraft item are recorded only. No invented retraction.
    const stemGrowth = smooth(time / timing.sprout_end);
    const world = p => p.map(v => v * motherScale);
    const capacity=shape.capacity_scale[count],units=shape.reference_units_to_model;
    const sourcePoint=p=>world([shape.anchor[0],
      shape.anchor[1]+(p[1]-shape.reference_origin[1])*units*capacity,
      shape.anchor[2]+(p[0]-shape.reference_origin[0])*units*capacity-smooth((p[1]-shape.reference_origin[1])/.5)*shape.depth_clearance]);
    const attachment=i=>sourcePoint(shape.socket_layouts[count][i]);
    const pendantLength=shape.pendant_length_reference*units*motherScale;
    const fruitProgress = clamp((time - timing.fruit_start) / (timing.mature - timing.fruit_start));
    const children = Array.from({length:count}, (_, i) => {
      const detach = detachTime(data,i,count), falling = time >= detach;
      const scale = growth.scaleAt(data.growth, 'baby', fruitProgress, (i+.5)/count);
      const birthScale = cfg.birth_scale;
      const origin=attachment(i),at=Math.min(time,detach);
      const roll=.025*Math.sin(at*1.4+i*1.7)+(i-(count-1)/2)*.20*fruitProgress;
      // Keep the source attachment motion; the connector is rendered as a
      // solid, thinner stem according to the user's revised art direction.
      const tip=[origin[0],origin[1]-Math.cos(roll)*pendantLength,origin[2]+Math.sin(roll)*pendantLength];
      const hanging=[tip[0],tip[1]-Math.cos(roll)*data.crown_y*scale,tip[2]+Math.sin(roll)*data.crown_y*scale];
      let position = hanging, landed = false, rotation=roll;
      if (falling) {
        const u = clamp((time - detach) / timing.fall_duration);
        const target = [hanging[0],0,hanging[2]-4.2];
        position = mix(hanging, target, smooth(u));
        // Gravity starts gently, accelerates downward and ends on the ground plane.
        position[1] = hanging[1] * (1 - u * u);
        landed = u >= 1;
        rotation=roll*(1-smooth(u));
      }
      return {id: 'reimu-child-' + (i + 1), motherId: 'reimu-mother', species: 'reimu', visible: time >= timing.fruit_start,
        stage: falling ? cfg.birth_stage : 'baby', scale: falling ? birthScale : scale,
        attached: !falling, landed, sleeping:!landed, rotation, position, origin, tip, growth: fruitProgress};
    });
    const born = children.filter(c => !c.attached).length;
    let phase = time < timing.sprout_end ? '弯茎生长' : time < timing.mature ? '实子沉睡成长' : born === 0 ? '闭眼待生' : born < count ? '依次脱落' : time < timing.end ? '幼体落地' : '完成';
    return {time, phase, motherScale, count, stemGrowth, children, born, complete: time === timing.end, attachment, sourcePoint, world};
  }
  function create(data, mother = {}, options = {}) {
    const identity = {id: 'reimu-mother', species: 'reimu', stage: 'adult', ecology: 'ordinary', reproductionMode: 'stem', scale: 1, ...mother};
    if (identity.species !== data.birth.character || identity.stage !== 'adult' || identity.ecology !== 'ordinary' || identity.reproductionMode !== 'stem') throw new Error('This preview requires an ordinary adult Reimu using stem reproduction');
    const count=validateCount(data,options.count ?? randomCount(data,options.random));
    sample(data, 0, identity.scale,count);
    let seconds = 0, emitted = new Set();
    return {
      get time() { return seconds; },
      state() { return sample(data, seconds, identity.scale,count); },
      advance(dt) {
        if (!Number.isFinite(dt) || dt < 0) throw new RangeError('Elapsed time must be nonnegative');
        seconds = Math.min(data.birth.preview_seconds.end, seconds + dt);
        const state = this.state(), events = [];
        for (const child of state.children) if (!child.attached && !emitted.has(child.id)) {
          emitted.add(child.id); events.push({type: 'birth', childId: child.id, motherId: identity.id, stage: child.stage, scale: child.scale});
        }
        return {state, events};
      },
      save() { return {version: 2, behavior: data.birth.id, mother: {...identity}, count, seconds, emitted: [...emitted]}; },
      restore(saved) {
        if (saved.version !== 2 || saved.behavior !== data.birth.id || saved.count!==count || JSON.stringify(saved.mother) !== JSON.stringify(identity) || !Number.isFinite(saved.seconds) || saved.seconds < 0 || saved.seconds > data.birth.preview_seconds.end || !Array.isArray(saved.emitted)) throw new Error('Invalid stem preview snapshot');
        const born = sample(data, saved.seconds, identity.scale,count).children.filter(c => !c.attached).map(c => c.id);
        if (new Set(saved.emitted).size !== saved.emitted.length || saved.emitted.length !== born.length || saved.emitted.some(id => !born.includes(id))) throw new Error('Snapshot birth events do not match its time');
        seconds = saved.seconds; emitted = new Set(saved.emitted);
      }
    };
  }
  function transform(vertices, scale, offset, roll=0, axis='z') {
    const out = vertices.slice();
    const c=Math.cos(roll),s=Math.sin(roll);
    for (let i = 0; i < out.length; i += 8) {
      if(axis==='x') {
        out[i]=vertices[i]*scale+offset[0];
        out[i+1]=(vertices[i+1]*c+vertices[i+2]*s)*scale+offset[1];
        out[i+2]=(-vertices[i+1]*s+vertices[i+2]*c)*scale+offset[2];
        out[i+6]=vertices[i+6]*c+vertices[i+7]*s;out[i+7]=-vertices[i+6]*s+vertices[i+7]*c;
      } else {
        out[i]=(vertices[i]*c-vertices[i+1]*s)*scale+offset[0];
        out[i+1]=(vertices[i]*s+vertices[i+1]*c)*scale+offset[1];out[i+2]=vertices[i+2]*scale+offset[2];
        out[i+5]=vertices[i+5]*c-vertices[i+6]*s;out[i+6]=vertices[i+5]*s+vertices[i+6]*c;
      }
    }
    return out;
  }
  // Closed rectangular rod with true face normals; negative U selects a solid palette color.
  function rod(out, a, b, radius, color = 0) {
    const d = b.map((v, i) => v - a[i]), length = Math.hypot(...d);
    if (length < 1e-5 || radius < 1e-5) return;
    const n = d.map(v => v / length), right = Math.abs(n[1]) < 0.98 ? [n[2], 0, -n[0]] : [1, 0, 0];
    const size = Math.hypot(...right); for (let i=0;i<3;i++) right[i] /= size;
    const up = [n[1]*right[2]-n[2]*right[1], n[2]*right[0]-n[0]*right[2], n[0]*right[1]-n[1]*right[0]];
    const corners = [];
    for (const p of [a,b]) for (const [x,y] of [[-1,-1],[1,-1],[1,1],[-1,1]]) corners.push(p.map((v,i)=>v+radius*(right[i]*x+up[i]*y)));
    for (const ids of [[0,3,2,1],[4,5,6,7],[0,1,5,4],[1,2,6,5],[2,3,7,6],[3,0,4,7]]) {
      const ps = ids.map(i=>corners[i]), u=ps[1].map((v,i)=>v-ps[0][i]), v=ps[2].map((v,i)=>v-ps[0][i]);
      let norm=[u[1]*v[2]-u[2]*v[1],u[2]*v[0]-u[0]*v[2],u[0]*v[1]-u[1]*v[0]], l=Math.hypot(...norm); norm=norm.map(v=>v/l);
      for (const k of [0,1,2,0,2,3]) out.push(...ps[k], -1-color, 0, ...norm);
    }
  }
  function pendantRadius(shape, socket, scale=1) {
    let nearest=Infinity,parameter=0;
    for(let i=0;i<shape.centerline.length-1;i++) {
      const a=shape.centerline[i],b=shape.centerline[i+1],d=b.map((v,k)=>v-a[k]),length=d[0]*d[0]+d[1]*d[1];
      const u=length?clamp(((socket[0]-a[0])*d[0]+(socket[1]-a[1])*d[1])/length):0;
      const distance=Math.hypot(socket[0]-a[0]-u*d[0],socket[1]-a[1]-u*d[1]);
      if(distance<nearest){nearest=distance;parameter=(i+u)/(shape.centerline.length-1);}
    }
    const mainRadius=shape.radius[0]+(shape.radius[1]-shape.radius[0])*Math.pow(parameter,4);
    return Math.min(shape.pendant_width_reference*shape.reference_units_to_model/2,mainRadius*shape.pendant_main_radius_ratio)*scale;
  }
  // A capped, shaded octagonal pedicel, thicker at the main stem attachment.
  function pedicel(out,a,b,baseRadius,tipRadius,color,sides=8) {
    const delta=b.map((v,i)=>v-a[i]),length=Math.hypot(...delta);if(length<1e-6)return;
    const axis=delta.map(v=>v/length),right=Math.abs(axis[1])<.98?[axis[2],0,-axis[0]]:[1,0,0];
    const rlen=Math.hypot(...right);for(let i=0;i<3;i++)right[i]/=rlen;
    const up=[axis[1]*right[2]-axis[2]*right[1],axis[2]*right[0]-axis[0]*right[2],axis[0]*right[1]-axis[1]*right[0]];
    const ring=(p,r)=>Array.from({length:sides},(_,i)=>p.map((v,k)=>v+r*(Math.cos(i/sides*Math.PI*2)*right[k]+Math.sin(i/sides*Math.PI*2)*up[k])));
    const base=ring(a,baseRadius),tip=ring(b,tipRadius);
    const triangle=(a,b,c)=>{const u=b.map((v,k)=>v-a[k]),v=c.map((v,k)=>v-a[k]);let n=[u[1]*v[2]-u[2]*v[1],u[2]*v[0]-u[0]*v[2],u[0]*v[1]-u[1]*v[0]],len=Math.hypot(...n);if(len<1e-10)return;n=n.map(x=>x/len);for(const p of [a,b,c])out.push(...p,-1-color,0,...n);};
    for(let i=0;i<sides;i++){const j=(i+1)%sides;triangle(base[i],base[j],tip[j]);triangle(base[i],tip[j],tip[i]);triangle(a,base[j],base[i]);triangle(b,tip[i],tip[j]);}
  }
  function geometry(data, state, restingVertices) {
    let out = transform(restingVertices, state.motherScale, [0,0,0]);
    const shape=data.stem, color=0, n=shape.segments;
    if (state.stemGrowth>0) {
      for(let i=0;i<n;i++) {
        const t=i/n*state.stemGrowth, next=(i+1)/n*state.stemGrowth;
        rod(out,state.sourcePoint(pathPoint(shape,t)),state.sourcePoint(pathPoint(shape,next)),(shape.radius[0]+(shape.radius[1]-shape.radius[0])*Math.pow(t,4))*state.motherScale,color);
      }
    }
    for(const [index,child] of state.children.entries()) if(child.visible) {
      if(child.attached){const radius=pendantRadius(shape,shape.socket_layouts[state.count][index],state.motherScale);pedicel(out,child.origin,child.tip,radius,radius*shape.pendant_tip_radius_ratio,color,shape.pendant_radial_segments);}
      const childVertices=child.sleeping?data.sleep_vertices:restingVertices;
      out=out.concat(transform(childVertices,child.scale,child.position,child.rotation,'x'));
      if(child.sleeping)out=out.concat(transform(sleepFace(data.sleep),child.scale,child.position,child.rotation,'x'));
      if(child.attached) {
        // Small calyx at the crown, as in U's がく attachment.
        const calyx=[];
        for(let k=0;k<5;k++) {const a=k*Math.PI*2/5;rod(calyx,[0,data.crown_y+.1,0],[Math.cos(a)*.85,data.crown_y-.12,Math.sin(a)*.85],.15,0);}
        out=out.concat(transform(calyx,child.scale,child.position,child.rotation,'x'));
      }
    }
    // Ground grid is part of the same depth buffer, so it occludes correctly from every view.
    for(let x=-12;x<=12;x+=3)rod(out,[x,-.12,-33],[x,-.12,10],.015,3);
    for(let z=-33;z<=10;z+=3)rod(out,[-12,-.12,z],[12,-.12,z],.015,3);
    return out;
  }
  function sleepFace(face) {
    const out=[];
    for(const line of face.eyelids)for(let i=0;i<line.length-1;i++)rod(out,line[i],line[i+1],face.eyelid_radius,4);
    const p=face.mouth_center,r=face.mouth_radii;
    for(let i=0;i<16;i++){
      const a=i*Math.PI/8,b=(i+1)*Math.PI/8;
      for(const v of [p,[p[0]+Math.cos(b)*r[0],p[1]+Math.sin(b)*r[1],p[2]],[p[0]+Math.cos(a)*r[0],p[1]+Math.sin(a)*r[1],p[2]]])out.push(...v,-6,0,0,0,-1);
    }
    rod(out,[.33,2.6,-4.7],[.42,2.0,-4.7],.09,6);
    return out;
  }
  const api={sample,create,geometry,transform,bezier,rod,pedicel,pendantRadius,sleepFace,randomCount,detachTime,validateCount,pathPoint}; root.YukkuriStem=api;
  if(typeof module!=='undefined')module.exports=api;
})(globalThis);
