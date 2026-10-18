'use strict';
/* 素鸡立 / 雷普魔 presentation clips, composed from the project's authored runtime motions.
 *
 * This replaces the earlier hand-written sine tracks. The heat presentation uses the
 * standard estrus faces adapted from 八景 U:
 *   基本発情顔１ -> flushed_smile      (closed eyes, flushed smile)
 *   基本発情顔２ -> flushed_tongue     (closed eyes, flushed tongue)
 * The game's れいぽう face selection (*label_0643) is eye 0 / brow 8 /
 * mouth 30 / blush 0. The preview redraws that structure as blank eyes +
 * angry brows + open smile + no blush; it does not use the standard
 * teary_tongue face for 雷普魔.
 * Body motion is layered from the already-authored runtime clips startle / turn / brace /
 * waddle / hop / idle. No mating action or new organ is authored. The optional
 * peni display reuses the model's existing anatomy_peni parts; mamu is skipped.
 */
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
    const clips = {};
    const heatOnsetLayers = [
      {source: 'startle', start: 0, duration: 0.62, weight: 1, fadeIn: 0.03, fadeOut: 0.16},
      {source: 'waddle', start: 0.55, duration: 2.45, weight: 0.42, rate: 0.55, fadeIn: 0.35, fadeOut: 0.35},
      {source: 'brace', start: 2.7, duration: 2.0, weight: 0.30, rate: 0.50, fadeIn: 0.30, fadeOut: 0.35},
      {source: 'waddle', start: 4.3, duration: 2.35, weight: 0.50, rate: 0.65, fadeIn: 0.35, fadeOut: 0.50}
    ];
    const heatLoopLayers = [
      {source: 'waddle', start: 0, duration: 3.2, weight: 0.45, rate: 0.55, fadeIn: 0, fadeOut: 0},
      {source: 'brace', start: 0, duration: 3.2, weight: 0.15, rate: 0.45, fadeIn: 0, fadeOut: 0}
    ];
    const rapemOnsetLayers = [
      {source: 'startle', start: 0, duration: 0.52, weight: 1, fadeIn: 0.02, fadeOut: 0.14},
      {source: 'turn', start: 0.42, duration: 0.50, weight: 0.90, rate: 1.2, fadeIn: 0.12, fadeOut: 0.14},
      {source: 'brace', start: 0.90, duration: 1.45, weight: 0.70, rate: 1.4, fadeIn: 0.20, fadeOut: 0.20},
      {source: 'waddle', start: 2.10, duration: 1.65, weight: 0.95, rate: 1.8, fadeIn: 0.20, fadeOut: 0.20},
      {source: 'hop', start: 3.55, duration: 0.70, weight: 0.80, rate: 1.4, fadeIn: 0.08, fadeOut: 0.14},
      {source: 'turn', start: 4.15, duration: 0.60, weight: 0.90, rate: 2.0, fadeIn: 0.08, fadeOut: 0.14},
      {source: 'waddle', start: 4.70, duration: 1.60, weight: 1.00, rate: 2.0, fadeIn: 0.18, fadeOut: 0.20},
      {source: 'brace', start: 6.20, duration: 0.85, weight: 0.85, rate: 1.6, fadeIn: 0.12, fadeOut: 0.14},
      {source: 'startle', start: 6.95, duration: 0.55, weight: 0.90, rate: 1.2, fadeIn: 0.03, fadeOut: 0.18}
    ];
    const rapemLoopLayers = [
      {source: 'waddle', start: 0, duration: 2.4, weight: 0.90, rate: 2.0, fadeIn: 0, fadeOut: 0},
      {source: 'brace', start: 0, duration: 2.4, weight: 0.45, rate: 1.5, fadeIn: 0, fadeOut: 0},
      {source: 'hop', start: 0.62, duration: 0.42, weight: 0.60, rate: 1.5, fadeIn: 0.08, fadeOut: 0.10},
      {source: 'turn', start: 1.28, duration: 0.42, weight: 0.70, rate: 2.0, fadeIn: 0.08, fadeOut: 0.10},
      {source: 'hop', start: 1.82, duration: 0.42, weight: 0.60, rate: 1.6, fadeIn: 0.08, fadeOut: 0.10}
    ];
    const rapemRoot = t => [0, 0, -1.15 * pulse(t, 3.55, 4.30)];
    Object.assign(clips, {
      heat_onset: bake(model, 'heat_onset', 7.6, false, heatOnsetLayers,
        [[0, 0.62, 'surprise'], [0.62, 2.7, 'flushed_smile'], [2.7, 7.6, 'sukkiri_tongue']],
        {
          label: '素鸡立 · 发情期站立演出',
          kind: 'heat',
          description: '通用发情期表现：程序 すっきり 的闭眼/开口；使用重绘 sukkiri_tongue，站立、呼吸、重心摆动，不离开原地。',
          phases: [
            {at: 0, label: '警觉', note: '受惊片段；身体短暂弹起'},
            {at: 0.62, label: '热感 · 闭眼笑', note: 'flushed_smile；身体开始慢慢摆动'},
            {at: 2.70, label: '发情 · 伸舌', note: 'sukkiri_tongue；轻度用力与持续颤抖'},
            {at: 4.30, label: '持续发情', note: '保持站立，重心左右移动'}
          ]
        }, null),
      heat_loop: bake(model, 'heat_loop', 3.2, true, heatLoopLayers,
        [[0, 3.2, 'sukkiri_tongue']],
        {
          label: '素鸡立 · 发情循环',
          kind: 'heat',
          description: '站立发情循环；只有呼吸、重心摆动和轻度用力。',
          phases: [{at: 0, label: '持续发情', note: 'sukkiri_tongue；站立循环'}]
        }, null),
      rapem_onset: bake(model, 'rapem_onset', 7.6, false, rapemOnsetLayers,
        [[0, 0.5, 'surprise'], [0.5, 7.6, 'rapem']],
        {
          label: '雷普魔 · 失控演出',
          kind: 'rapem',
          description: '爱丽丝异常状态：八景 U 重绘失神笑；突然停住、抽头、下蹲、快速踱步与跳跃前冲。',
          phases: [
            {at: 0, label: '突然停住', note: 'startle；短暂弹起后僵住'},
            {at: 0.50, label: '失控笑', note: 'rapem 重绘表情；白眼、怒眉、开口笑'},
            {at: 0.90, label: '下蹲', note: 'brace；压低身体，持续颤抖'},
            {at: 2.10, label: '快速踱步', note: 'waddle 加速；来回扫视'},
            {at: 3.55, label: '跳跃前冲', note: 'hop + 向前冲的根位移'},
            {at: 4.70, label: '持续失控', note: 'waddle 高速；抽头与跳跃交替'}
          ]
        }, rapemRoot),
      rapem_loop: bake(model, 'rapem_loop', 2.4, true, rapemLoopLayers,
        [[0, 2.4, 'rapem']],
        {
          label: '雷普魔 · 失控循环',
          kind: 'rapem',
          description: '失控循环；快速摇摆、抽头与跳跃，不是普通发情的加强版。',
          phases: [{at: 0, label: '持续失控', note: 'rapem 重绘表情；抽头与跳跃交替'}]
        }, null)
    });

    // 佩妮佩妮显现：把运行时已有的 anatomy_peni_extend 轨道并入动作。
    // 动作名以 anatomy_peni_ 开头，RuntimePose 会显示 peni 部件并跳过 mamu 部件。
    const peniExt = model.clips['anatomy_peni_extend'];
    const peniVariant = (base, id) => {
      if (!peniExt) return null;
      const tracks = base.tracks.slice();
      const seen = new Set(tracks.map(track => track.bone + '|' + track.channel));
      for (const track of peniExt.tracks) {
        const key = track.bone + '|' + track.channel;
        if (seen.has(key)) continue;
        const keys = track.keys.filter(k => k[0] <= peniExt.duration + 1e-6).map(k => [k[0], k[1], k[2], k[3]]);
        if (!keys.length) continue;
        const last = keys[keys.length - 1];
        if (id.endsWith('_loop')) {
          keys.length = 0;
          keys.push([0, last[1], last[2], last[3]]);
        }
        if (last[0] < base.duration) keys.push([base.duration, last[1], last[2], last[3]]);
        tracks.push({bone: track.bone, channel: track.channel, keys});
        seen.add(key);
      }
      return {...base, id, tracks, presentation: {...base.presentation, id}};
    };
    clips.anatomy_peni_heat_onset = peniVariant(clips.heat_onset, 'anatomy_peni_heat_onset');
    clips.anatomy_peni_heat_loop = peniVariant(clips.heat_loop, 'anatomy_peni_heat_loop');
    clips.anatomy_peni_rapem_onset = peniVariant(clips.rapem_onset, 'anatomy_peni_rapem_onset');
    clips.anatomy_peni_rapem_loop = peniVariant(clips.rapem_loop, 'anatomy_peni_rapem_loop');
    for (const key of Object.keys(clips)) if (clips[key] == null) delete clips[key];
    return clips;
  }

  return {buildHeatClips, sampleClip};
});
