'use strict';
/* Adult-state presentation candidates. The existing runtime movements
 * and authored expressions provide a sustained observation cycle or an interruption sample.
 * Timings and choreography are project choices, not fixed Wiki/L36 facts.
 * Gameplay state is owned by the server. */
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

  // Pure posture recipe. Progress is 0..1 within the selected clip; the heat cycle lasts 8 seconds.
  // Rotation is degrees in model axes; scale is relative to the ordinary adult.
  // These authored silhouettes are shared independently of any game movement.
  const POSE_KEYS = poseCues;
  function samplePresentationPose(branch, progress) {
    const keys = POSE_KEYS[branch === 'rapem' ? 'rapem' : branch === 'relief' ? 'relief' : branch === 'act' ? 'act' : 'heat'];
    const p = clamp(progress, 0, 1);
    let i = 0;
    while (i + 1 < keys.length - 1 && p > keys[i + 1][0]) i++;
    const a = keys[i], b = keys[i + 1];
    const q = smooth((p - a[0]) / (b[0] - a[0]));
    const v = a.slice(1).map((value, j) => value + (b[j + 1] - value) * q);
    return {rotation: v.slice(0, 3), scale: v.slice(3, 6), offset: [0,0,0]};
  }

  function addPostureTracks(model, clip, branch, period = 0) {
    const bones = boneMap(model);
    const sample = (boneName, channel, values) => {
      const bone = bones.get(boneName);
      if (bone === undefined) throw new Error('Presentation bone missing: ' + boneName);
      const keys = [];
      for (let i = 0; i <= clip.duration * FPS; i++) {
        const t = i / FPS;
        const progress = period > 0 ? (t / period) % 1 : t / clip.duration;
        keys.push([t, ...values(samplePresentationPose(branch, progress))]);
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
    const periodFace = options.variant === 'alice_rapem' ? 'breeding_period' : 'neutral';
    const satisfiedFace = 'satisfied';
    const recoveryFace = options.variant === 'alice_rapem' ? 'state_recover' : 'sleepy';
    const common = {approval: 'pending', adult_only: true, gameplay_authority: false};
    const layers = [{source:'idle',start:0,duration:10,weight:1,fadeIn:0,fadeOut:0}];
    const clips = {
      heat_onset: addPostureTracks(model, bake(model, 'heat_onset', 8, true,
        [{source:'idle',start:0,duration:8,weight:1,fadeIn:0,fadeOut:0}],
        [[0,8,periodFace]],
        {...common, label:'素鸡立 · 持续繁殖期观察示意',kind:'heat',
          description:'期内原地轻微左右观察，无伸展、卸力或结束演出；服务端决定时期何时结束。',
          phases:[
            {at:0,label:'期内 · 中性观察',note:'蓝虹膜与细弧眉按1.12普通成体外形适配。'},
            {at:1.6,label:'期内 · 向一侧看',note:'原地轻转4度，无高度与体型变化。'},
            {at:3.2,label:'期内 · 视线回正',note:'仍处于持续期，不表示缓解。'},
            {at:5.2,label:'期内 · 看另一侧',note:'8秒观察节律可重复；不是病程。'},
            {at:6.8,label:'期内 · 继续观察',note:'周期末尾仍处于时期中，未演示时期结束。'}
          ]},null), 'heat')
    };
    // Staged 120-tick completion (ゆっ→すっ→すっきり～) shared by every species' resolved period.
    clips.heat_relief = addPostureTracks(model, bake(model, 'heat_relief', 6, false,
      [{source:'idle',start:0,duration:6,weight:1,fadeIn:0,fadeOut:0}],
      [[0,2.3,satisfiedFace],[2.3,4.55,satisfiedFace],[4.55,6,satisfiedFace]],
      {...common, label:'素鸡立 · 完成三段示意', kind:'relief',
        description:'受孕或离屏结算后的一次性短暂弹动；三次节拍对应“慢！”“素——！”“素鸡立～！”台词，随后回到普通待机。',
        phases:[
          {at:0,label:'第一拍 · 慢！',note:'微微下压后回弹，与heat_relief_1台词同时。'},
          {at:2.3,label:'第二拍 · 素——！',note:'再次轻压，与heat_relief_2台词同时。'},
          {at:4.55,label:'第三拍 · 素鸡立～！',note:'最后一次弹动后站定，满足脸由服务端阶段驱动。'},
          {at:6,label:'结束',note:'一次性演出，不循环；时期已由受孕或结算结束。'}
        ]},null), 'relief');
    if (options.variant !== 'alice_rapem') return clips;
    // The contact act: two-second rub cycles through the lean curve (game ALICE_ACT, 2s period).
    clips.rapem_act = addPostureTracks(model, bake(model, 'rapem_act', 6, true,
      [{source:'idle',start:0,duration:6,weight:1,fadeIn:0,fadeOut:0}],
      [[0,6,'drool']],
      {...common, label:'雷普魔 · 素鸡立动作示意', kind:'rapem',
        description:'接触后攻方保持前倾并按1秒周期耸腰顶胯（act关键帧通道，对应1.12每30帧一次すりすり），器官保持伸出（op2显示）；受方另体挣扎，不在此单体片段。',
        phases:[
          {at:0,label:'回拉',note:'身体间距贴到体宽和之半加0.45格，胯部略收。'},
          {at:0.5,label:'耸腰顶点',note:'1秒周期内两次下压顶胯，配scale脉冲抬高重心；台词按t=60/120/160/200/210/240接入。'},
          {at:3,label:'三段完成',note:'慢！→素——！→素鸡立～！与heat_relief台词对齐。'},
          {at:5,label:'结算与结束',note:'t=210作1/4抽取，t=240释放受方并可能立即再猎。'}
        ]},null), 'act', 1);
    clips.rapem_onset = addPostureTracks(model, bake(model, 'rapem_onset',10,false,layers,
      [[0,0.8,periodFace],[0.8,1.5,periodFace],[1.5,5.4,'alice_agitated'],[5.4,6.8,periodFace],
       [6.8,9.2,recoveryFace],[9.2,10,periodFace]],
      {...common,label:'爱丽丝异常状态 · 项目姿态示意（原作专属形态未完成）',kind:'rapem',
        description:'整体持续前倾、低重心与凝视，非周期失稳，随后中断收势恢复。',
        phases:[
          {at:0,label:'正常对照',note:'同一模型、同一镜位，建立普通轮廓。'},
          {at:0.8,label:'警觉',note:'抬眼凝视，向目标方向倾身。'},
          {at:1.5,label:'项目失控姿态',note:'稳定前倾约20°、压低体型并凝视；两次不均匀重心调整。'},
          {at:5.4,label:'中断收势',note:'前倾停止、缓慢回正。'},
          {at:6.8,label:'疲惫恢复',note:'短暂低头后回到普通站姿。'},
          {at:9.2,label:'恢复',note:'眉眼、身体与重心归位。'}
        ]},null), 'rapem');
    return clips;
  }

  return {buildHeatClips, sampleClip, samplePresentationPose};
});
