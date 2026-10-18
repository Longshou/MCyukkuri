'use strict';
/* Adult-only, non-graphic presentation candidates. The existing runtime movements
 * and approved expressions are composed into finite episodes with a visible end.
 * Timings and choreography are project choices, not fixed Wiki/L36 facts.
 * This draft does not control gameplay and never enables anatomy geometry. */
(function (root, factory) {
  const api = factory();
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  root.MotionClips = api;
})(typeof globalThis !== 'undefined' ? globalThis : this, function () {
  'use strict';

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

  function buildHeatClips(model, options = {}) {
    const required = ['idle', 'turn', 'brace', 'waddle', 'startle'];
    for (const name of required) {
      if (!model.clips?.[name] && !model.behavior_clips?.[name]) {
        throw new Error('Presentation source clip missing: ' + name);
      }
    }
    const common = {approval: 'pending', adult_only: true, gameplay_authority: false};
    const heatLayers = [
      {source: 'idle', start: 0, duration: 10, weight: 0.65, fadeIn: 0, fadeOut: 0},
      {source: 'turn', start: 1.2, duration: 1.15, weight: 0.28, rate: 0.70, fadeIn: 0.22, fadeOut: 0.35},
      {source: 'brace', start: 2.3, duration: 1.2, weight: 0.32, rate: 0.60, fadeIn: 0.30, fadeOut: 0.40},
      {source: 'idle', start: 3.5, duration: 3.3, weight: 0.35, rate: 0.65, fadeIn: 0.45, fadeOut: 0.45},
      {source: 'brace', start: 6.8, duration: 2.3, weight: 0.24, rate: 0.32, fadeIn: 0.60, fadeOut: 0.80}
    ];
    const clips = {
      heat_onset: bake(model, 'heat_onset', 10, false, heatLayers,
        [[0, 1.2, 'neutral'], [1.2, 3.5, 'confused'], [3.5, 6.8, 'closed_smile'],
         [6.8, 9.2, 'sleepy'], [9.2, 10, 'neutral']],
        {...common,
          label: '素鸡立 · 短时缓解表现（制作候选）', kind: 'heat',
          description: '正常、躁动、缓解、疲惫、恢复；仅通过标准表情、停步和重心沉降提示状态变化。',
          phases: [
            {at: 0, label: '正常', note: 'neutral；平稳待机，建立前后对照。'},
            {at: 1.2, label: '躁动', note: 'confused；短暂侧看与停步，不持续摇摆。'},
            {at: 3.5, label: '缓解', note: 'closed_smile；停稳、放松，状态开始回落。'},
            {at: 6.8, label: '疲惫恢复', note: 'sleepy；轻微下沉，再逐渐恢复重心。'},
            {at: 9.2, label: '回到正常', note: 'neutral；完整片段结束并暂停，不自动进入另一状态。'}
          ]
        }, null)
    };
    // The variant is Alice-specific. Generic model previews only receive relief.
    if (options.variant !== 'alice_rapem') return clips;
    const rapemLayers = [
      {source: 'idle', start: 0, duration: 10, weight: 0.35, fadeIn: 0, fadeOut: 0},
      {source: 'startle', start: 0.2, duration: 0.75, weight: 0.60, rate: 0.90, fadeIn: 0.10, fadeOut: 0.28},
      {source: 'turn', start: 1.1, duration: 1.2, weight: 0.70, rate: 0.75, fadeIn: 0.22, fadeOut: 0.40},
      {source: 'brace', start: 2.15, duration: 1.0, weight: 0.45, rate: 0.80, fadeIn: 0.25, fadeOut: 0.30},
      {source: 'waddle', start: 3.0, duration: 2.4, weight: 0.80, rate: 1.35, fadeIn: 0.35, fadeOut: 0.40},
      {source: 'brace', start: 5.3, duration: 1.4, weight: 0.80, rate: 0.75, fadeIn: 0.28, fadeOut: 0.40},
      {source: 'brace', start: 6.8, duration: 2.4, weight: 0.40, rate: 0.30, fadeIn: 0.50, fadeOut: 0.80},
      {source: 'idle', start: 8.6, duration: 1.4, weight: 0.65, fadeIn: 0.50, fadeOut: 0}
    ];
    const approach = t => [0, 0,
      -2.4 * smooth((t - 3.0) / 2.4) + 0.6 * smooth((t - 5.8) / 1.0)];
    clips.rapem_onset = bake(model, 'rapem_onset', 10, false, rapemLayers,
      [[0, 1.1, 'surprise'], [1.1, 5.4, 'angry'], [5.4, 6.8, 'confused'],
       [6.8, 9.2, 'sleepy'], [9.2, 10, 'neutral']],
      {...common,
        label: '雷普魔爱丽丝 · 中断与恢复（制作候选）', kind: 'rapem',
        description: '警觉、定向接近、停步退让、疲惫恢复；只有一只成体，未制作接触或侵害过程。',
        phases: [
          {at: 0, label: '警觉', note: 'surprise；短暂停步，抬头观察。'},
          {at: 1.1, label: '定向', note: 'angry；转向后压低重心，眉眼集中。'},
          {at: 3.0, label: '接近', note: 'angry；短段快速行进，保留清楚的前进方向。'},
          {at: 5.4, label: '中断', note: 'confused；停住并退开，接近动作结束。'},
          {at: 6.8, label: '疲惫恢复', note: 'sleepy；下沉后慢慢站稳。'},
          {at: 9.2, label: '回到正常', note: 'neutral；示意中断后的恢复结果，时长为制作安排。'}
        ]
      }, approach);
    return clips;
  }

  return {buildHeatClips, sampleClip};
});
