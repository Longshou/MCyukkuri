'use strict';
/* Adult-only, non-graphic presentation candidates. The existing runtime movements
 * and approved expressions are composed into finite episodes with a visible end.
 * Timings and choreography are project choices, not fixed Wiki/L36 facts.
 * This draft does not control gameplay and never enables anatomy geometry. */
(function (root, factory) {
  const api = factory(typeof module !== 'undefined' && module.exports ? require('./pose-cues.json') : root.PresentationPoseCues);
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  root.MotionClips = api;
})(typeof globalThis !== 'undefined' ? globalThis : this, function (poseCues) {
  'use strict';
  if (!poseCues || poseCues.format !== 1) throw new Error('Missing presentation pose-cues.json');

  const FPS = 24;
  const clamp = (v, a, b) => Math.max(a, Math.min(b, v));
  const smooth = x => { x = clamp(x, 0, 1); return x * x * (3 - 2 * x); };
  const wrap = (v, d) => d > 0 ? ((v % d) + d) % d : 0;
  const pulse = (t, a, b) => t <= a || t >= b ? 0 : Math.sin(Math.PI * (t - a) / (b - a));
  const CHANNEL_OFFSET = {position: 0, rotation: 3, scale: 6};

  function boneMap(model) {
    const map = new Map();
    (model.bones || []).forEach((b, i) => map.set(b.name, i));
    return map;
  }

  function defaultPose(count) {
    const pose = new Float64Array(count * 9);
    for (let i = 0; i < count; i++) {
      pose[i * 9 + 6] = 1;
      pose[i * 9 + 7] = 1;
      pose[i * 9 + 8] = 1;
    }
    return pose;
  }

  function sampleTrack(track, t, count) {
    const keys = track.keys;
    if (!keys || !keys.length) return;
    let lo = 0, hi = keys.length - 1;
    while (hi - lo > 1) {
      const mid = (lo + hi) >> 1;
      if (keys[mid][0] <= t) lo = mid; else hi = mid;
    }
    const a = keys[lo], b = keys[Math.min(lo + 1, keys.length - 1)];
    const q = a[0] === b[0] ? 0 : clamp((t - a[0]) / (b[0] - a[0]), 0, 1);
    const bone = Number(track.bone);
    if (!Number.isInteger(bone) || bone < 0 || bone >= count) return;
    const offset = CHANNEL_OFFSET[track.channel];
    const base = bone * 9 + offset;
    track._out = track._out || new Float64Array(3);
    track._out[0] = a[1] + (b[1] - a[1]) * q;
    track._out[1] = a[2] + (b[2] - a[2]) * q;
    track._out[2] = a[3] + (b[3] - a[3]) * q;
    return {base, values: track._out};
  }

  function sampleClip(model, clip, t) {
    const count = model.bones.length;
    const pose = defaultPose(count);
    if (!clip) return pose;
    const time = clip.loop ? wrap(t, clip.duration) : clamp(t, 0, clip.duration);
    for (const track of clip.tracks) {
      const sample = sampleTrack(track, time, count);
      if (!sample) continue;
      pose[sample.base] = sample.values[0];
      pose[sample.base + 1] = sample.values[1];
      pose[sample.base + 2] = sample.values[2];
    }
    return pose;
  }

  function weightAt(t, layer) {
    const start = layer.start || 0;
    const end = start + (layer.duration ?? 0);
    if (t < start || t > end) return 0;
    const fadeIn = layer.fadeIn ?? 0.15;
    const fadeOut = layer.fadeOut ?? 0.15;
    let w = 1;
    if (fadeIn > 0) w = Math.min(w, smooth((t - start) / fadeIn));
    if (fadeOut > 0) w = Math.min(w, smooth((end - t) / fadeOut));
    return w * (layer.weight ?? 1);
  }

  function blendFrame(model, layers, t, rootOffset) {
    const count = model.bones.length;
    const accum = new Float64Array(count * 9);
    let total = 0;
    for (const layer of layers) {
      const weight = weightAt(t, layer);
      if (weight <= 0) continue;
      const clip = model.clips[layer.source] || model.behavior_clips?.[layer.source];
      if (!clip) continue;
      const local = (t - (layer.start || 0)) * (layer.rate ?? 1) + (layer.offset ?? 0);
      const pose = sampleClip(model, clip, local);
      for (let i = 0; i < pose.length; i++) accum[i] += pose[i] * weight;
      total += weight;
    }
    const identity = Math.max(0, 1 - total);
    const denominator = Math.max(1, total);
    for (let i = 0; i < count; i++) {
      const base = i * 9;
      for (let c = 0; c < 3; c++) accum[base + c] /= denominator;
      for (let c = 3; c < 6; c++) accum[base + c] /= denominator;
      accum[base + 6] = (accum[base + 6] + identity) / denominator;
      accum[base + 7] = (accum[base + 7] + identity) / denominator;
      accum[base + 8] = (accum[base + 8] + identity) / denominator;
    }
    if (rootOffset) {
      const root = model.bones.findIndex(b => b.name === 'world_motion');
      if (root >= 0) {
        const offset = rootOffset(t);
        accum[root * 9] += offset[0] || 0;
        accum[root * 9 + 1] += offset[1] || 0;
        accum[root * 9 + 2] += offset[2] || 0;
      }
    }
    return accum;
  }

  function bake(model, id, duration, loop, layers, expressionSpans, presentation, rootOffset) {
    const count = model.bones.length;
    const frames = Math.ceil(duration * FPS);
    const animated = new Set();
    for (const layer of layers) {
      const clip = model.clips[layer.source] || model.behavior_clips?.[layer.source];
      if (!clip) continue;
      for (const track of clip.tracks) animated.add(track.bone + '|' + track.channel);
    }
    if (rootOffset) {
      const root = model.bones.findIndex(b => b.name === 'world_motion');
      if (root >= 0) animated.add(String(root) + '|position');
    }
    const tracks = [];
    for (const key of animated) {
      const [bone, channel] = key.split('|');
      const offset = CHANNEL_OFFSET[channel];
      const base = Number(bone) * 9 + offset;
      const keys = [];
      for (let i = 0; i <= frames; i++) {
        const t = Math.min(i / FPS, duration);
        const pose = blendFrame(model, layers, t, rootOffset);
        keys.push([t, pose[base], pose[base + 1], pose[base + 2]]);
      }
      tracks.push({bone, channel, keys});
    }
    return {
      duration,
      loop,
      tracks,
      expression_spans: expressionSpans,
      presentation: {
        id,
        ...presentation,
        sources: [...new Set(layers.map(layer => layer.source))]
      }
    };
  }

  // Pure posture recipe. Progress is 0..1 across the 10-second authoring sample.
  // Rotation is degrees in model axes; scale is relative to the ordinary adult.
  // These authored silhouettes are shared independently of any game movement.
  const POSE_KEYS = poseCues;
  function samplePresentationPose(branch, progress) {
    const keys = POSE_KEYS[branch === 'rapem' ? 'rapem' : 'heat'];
    const p = clamp(progress, 0, 1);
    let i = 0;
    while (i + 1 < keys.length - 1 && p > keys[i + 1][0]) i++;
    const a = keys[i], b = keys[i + 1];
    const q = smooth((p - a[0]) / (b[0] - a[0]));
    const v = a.slice(1).map((value, j) => value + (b[j + 1] - value) * q);
    return {rotation: v.slice(0, 3), scale: v.slice(3, 6), offset: [0,0,0]};
  }

  function addPostureTracks(model, clip, branch) {
    const bones = boneMap(model);
    const sample = (boneName, channel, values) => {
      const bone = bones.get(boneName);
      if (bone === undefined) throw new Error('Presentation bone missing: ' + boneName);
      const keys = [];
      for (let i = 0; i <= 10 * FPS; i++) {
        keys.push([i / FPS, ...values(samplePresentationPose(branch, i / (10 * FPS)))]);
      }
      clip.tracks = clip.tracks.filter(t => Number(t.bone) !== bone || t.channel !== channel);
      clip.tracks.push({bone, channel, keys});
    };
    sample('world_motion', 'rotation', p => p.rotation);
    sample('world_motion', 'scale', p => p.scale);
    clip.presentation.posture_recipe = 'samplePresentationPose';
    return clip;
  }

  function buildHeatClips(model, options = {}) {
    if (!model.clips?.idle) throw new Error('Presentation source clip missing: idle');
    const reliefFace = options.variant === 'alice_rapem' ? 'brief_relief' : 'closed_smile';
    const recoveryFace = options.variant === 'alice_rapem' ? 'state_recover' : 'sleepy';
    const common = {approval: 'pending', adult_only: true, gameplay_authority: false};
    const layers = [{source:'idle',start:0,duration:10,weight:1,fadeIn:0,fadeOut:0}];
    const clips = {
      heat_onset: addPostureTracks(model, bake(model, 'heat_onset', 10, false, layers,
        [[0, 1.2, 'neutral'], [1.2, 3.5, 'confused'], [3.5, 6.8, reliefFace],
         [6.8, 9.2, recoveryFace], [9.2, 10, 'neutral']],
        {...common, label:'素鸡立 · 短时缓解姿态候选',kind:'heat',
          description:'明确压低蓄势、一次抬身、慢慢卸力沉降，再恢复站姿。',
          phases:[
            {at:0,label:'正常',note:'普通轮廓与平稳站姿。'},
            {at:1.2,label:'短暂紧张',note:'身体压低，略向侧方偏重；只做一次蓄势。'},
            {at:3.5,label:'缓解与卸力',note:'一次抬身后慢慢沉降，不做往复摆动。'},
            {at:6.8,label:'疲惫恢复',note:'轻微低头，逐渐回到普通轮廓。'},
            {at:9.2,label:'回到正常',note:'重心、体型与眉眼归位，片段结束。'}
          ]},null), 'heat')
    };
    if (options.variant !== 'alice_rapem') return clips;
    clips.rapem_onset = addPostureTracks(model, bake(model, 'rapem_onset',10,false,layers,
      [[0,0.8,'neutral'],[0.8,1.5,'surprise'],[1.5,5.4,'alice_agitated'],[5.4,6.8,'confused'],
       [6.8,9.2,recoveryFace],[9.2,10,'neutral']],
      {...common,label:'雷普魔爱丽丝 · 独立状态姿态候选',kind:'rapem',
        description:'整体持续前倾、低重心与凝视，非周期失稳，随后中断收势恢复。',
        phases:[
          {at:0,label:'正常对照',note:'同一模型、同一镜位，建立普通轮廓。'},
          {at:0.8,label:'警觉',note:'抬眼凝视，向目标方向倾身。'},
          {at:1.5,label:'失控姿态',note:'稳定前倾约20°、压低体型并凝视；两次不均匀重心调整。'},
          {at:5.4,label:'中断收势',note:'前倾停止、缓慢回正；不展示目标接触。'},
          {at:6.8,label:'疲惫恢复',note:'短暂低头后回到普通站姿。'},
          {at:9.2,label:'恢复',note:'眉眼、身体与重心归位。'}
        ]},null), 'rapem');
    return clips;
  }

  return {buildHeatClips, sampleClip, samplePresentationPose};
});
