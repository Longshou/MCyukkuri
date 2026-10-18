'use strict';
/* Generic estrus-onset ("素鸡立") presentation for any runtime yukkuri model.
 *
 * The performance is behavioural, not anatomical: it uses only the documented
 * L36/wiki cues — loss of reason, a strong reproductive drive, and increased
 * physical ability — plus the existing idle/fidget vocabulary. No mating action,
 * genital geometry or new organ is animated.
 *
 * Usage:
 *   const clips = HeatClips.buildHeatClips(model, {variant: 'alice_rapem'});
 *   Object.assign(pose.clips, clips);
 */
(function (root, factory) {
  const api = factory();
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  root.HeatClips = api;
})(typeof globalThis !== 'undefined' ? globalThis : this, function () {
  'use strict';

  const clamp = (v, a, b) => Math.max(a, Math.min(b, v));
  const smooth = x => { x = clamp(x, 0, 1); return x * x * (3 - 2 * x); };
  const TAU = Math.PI * 2;
  const wave = (t, hz) => Math.sin(TAU * hz * t);
  const bump = (t, center, width) => Math.exp(-Math.pow((t - center) / width, 2));
  const pulse = (t, a, b) => {
    if (t <= a || t >= b) return 0;
    const u = (t - a) / (b - a);
    return Math.sin(Math.PI * u);
  };

  function boneMap(model) {
    const map = new Map();
    (model.bones || []).forEach((b, i) => map.set(b.name, i));
    return map;
  }

  function add(tracks, map, name, channel, values, fps, duration) {
    const index = map.get(name);
    if (index === undefined) return;
    const keys = values.map((v, i) => [Math.min(i / fps, duration), v[0], v[1], v[2]]);
    tracks.push({bone: String(index), channel, keys});
  }

  function build(model, options = {}) {
    const fps = options.fps || 24;
    const feral = options.variant === 'alice_rapem';
    const intensity = feral ? 1.55 : 1;
    const map = boneMap(model);
    const clips = {};
    const accessories = ['alice_headband', 'hat', 'crown', 'bow_left', 'bow_right', 'bow_knot', 'ribbon'];
    const has = name => map.has(name);

    function makeOnset(id, duration, loop) {
      const frames = Math.ceil(duration * fps);
      const tracks = [];
      const sample = fn => {
        const out = [];
        for (let i = 0; i <= frames; i++) out.push(fn(Math.min(i / fps, duration)));
        return out;
      };
      const env = t => ({
        alert: 1 - smooth(t / 0.7),
        heat: smooth((t - 0.7) / 1.5),
        escalation: smooth((t - 2.2) / 2.0),
        drive: smooth((t - 4.2) / 1.2),
        burst: pulse(t, 5.25, 6.05),
        settle: smooth((t - 6.05) / 1.2)
      });
      const breath = t => 0.5 - 0.5 * Math.cos(TAU * (feral ? 2.15 : 1.75) * t);
      const scan = t => wave(t, feral ? 0.22 : 0.17);
      const sway = t => wave(t, 0.55);
      const tremble = t => wave(t, feral ? 10.5 : 8.2);
      const shiver = t => wave(t, feral ? 13 : 11);
      const feralSnap = t => feral
        ? 28 * bump(t, 1.15, 0.055) - 34 * bump(t, 3.55, 0.06) + 30 * bump(t, 5.05, 0.05) - 20 * bump(t, 6.65, 0.07)
        : 0;

      add(tracks, map, 'root', 'position', sample(t => {
        const e = env(t);
        return [0.45 * scan(t) * (0.35 + e.drive), e.burst * (feral ? 0.60 : 0.45), e.burst * (feral ? -1.20 : -0.90)];
      }), fps, duration);
      add(tracks, map, 'body_motion', 'position', sample(t => {
        const e = env(t);
        return [
          (0.10 * scan(t) + 0.03 * tremble(t) * e.escalation) * intensity,
          0.055 * (breath(t) - 0.5) * e.heat * intensity - 0.06 * e.burst * intensity,
          0.02 * Math.sin(TAU * 0.7 * t) * e.heat
        ];
      }), fps, duration);
      add(tracks, map, 'body_motion', 'rotation', sample(t => {
        const e = env(t);
        return [
          (-1.2 * e.heat - 2.4 * e.drive - (feral ? 7.0 : 5.0) * e.burst) * intensity,
          2.8 * scan(t) * intensity,
          (1.6 * sway(t) + 0.9 * tremble(t) * e.escalation) * intensity
        ];
      }), fps, duration);
      add(tracks, map, 'body_stretch', 'rotation', sample(t => {
        const e = env(t);
        return [0, 0, (2.0 * sway(t) + 0.8 * shiver(t) * e.escalation) * intensity];
      }), fps, duration);
      add(tracks, map, 'upper_body', 'rotation', sample(t => {
        const e = env(t);
        return [
          (-0.8 * e.heat - 1.4 * e.drive - (feral ? 3.2 : 2.4) * e.burst) * intensity,
          -1.5 * scan(t) * intensity,
          (-1.4 * sway(t) - 0.6 * tremble(t) * e.escalation) * intensity
        ];
      }), fps, duration);
      add(tracks, map, 'head', 'rotation', sample(t => {
        const e = env(t);
        return [
          ((-2.6 * e.heat + 1.2 * breath(t) + 1.2 * tremble(t) * e.escalation - (feral ? 4.0 : 3.0) * e.burst) * intensity)
            + (feral ? 4 * bump(t, 1.15, 0.08) : 0),
          (14 * scan(t) + 9 * e.drive * Math.sin(TAU * 0.65 * t) + 12 * e.burst * Math.sin(TAU * 1.8 * t)) * intensity + feralSnap(t),
          (1.5 * sway(t) + 1.2 * tremble(t) * e.escalation + 0.8 * e.alert) * intensity
            + (feral ? 8 * bump(t, 3.55, 0.07) : 0)
        ];
      }), fps, duration);
      for (const [name, sign] of [['hair', 1], ['bangs', 1]]) {
        if (!has(name)) continue;
        add(tracks, map, name, 'rotation', sample(t => {
          const e = env(t);
          return [
            (1.2 * breath(t) + 1.0 * tremble(t) * e.escalation) * sign * intensity,
            0,
            (1.6 * sway(t) + 1.3 * shiver(t) * e.escalation) * sign * intensity
          ];
        }), fps, duration);
      }
      for (const [name, sign] of [['hand_left', 1], ['hand_right', -1]]) {
        if (!has(name)) continue;
        add(tracks, map, name, 'rotation', sample(t => {
          const e = env(t);
          return [
            (1.6 * breath(t) + 1.2 * tremble(t) * e.escalation) * intensity,
            (2.0 * e.drive * Math.sin(TAU * 0.9 * t)) * sign * intensity,
            (3.2 * sway(t) + 2.0 * shiver(t) * e.escalation + 4.0 * e.drive * Math.sin(TAU * 0.8 * t)) * sign * intensity
          ];
        }), fps, duration);
      }
      for (const name of ['rump_sway', 'rump_roll']) {
        if (!has(name)) continue;
        add(tracks, map, name, 'rotation', sample(t => {
          const e = env(t);
          return [
            (0.8 * breath(t) + 0.5 * tremble(t) * e.escalation) * intensity,
            1.2 * scan(t) * intensity,
            (2.4 * sway(t) + (name === 'rump_roll' ? -1.2 : 1.0) * tremble(t) * e.escalation) * intensity
          ];
        }), fps, duration);
      }
      for (const name of accessories) {
        if (!has(name)) continue;
        add(tracks, map, name, 'rotation', sample(t => {
          const e = env(t);
          return [
            0.8 * tremble(t) * e.escalation * intensity,
            0.6 * sway(t) * intensity,
            (1.0 * sway(t) + (feral ? 2.8 : 1.6) * shiver(t) * e.escalation) * intensity
          ];
        }), fps, duration);
      }
      return {
        duration,
        loop,
        tracks,
        expression_spans: loop
          ? [[0, duration, 'heat']]
          : [[0, 0.7, 'surprise'], [0.7, duration, 'heat']],
        presentation: {
          id,
          label: feral ? '雷普魔 · 失控发情演出' : '素鸡立 · 发情期演出',
          phases: [
            {at: 0, label: '警觉', note: '身体突然停住，抬头观察'},
            {at: 0.7, label: '热感上升', note: '面颊泛红、呼吸加快'},
            {at: 2.2, label: '坐立不安', note: '视线游移、四肢小幅抓挠'},
            {at: 4.2, label: '驱力增强', note: '来回踱步、向目标方向倾身'},
            {at: 5.25, label: '冲动冲刺', note: '身体能力短暂增强'},
            {at: 6.05, label: '持续状态', note: '呼吸与颤抖维持，进入循环'}
          ]
        }
      };
    }

    function makeLoop(id, duration) {
      const frames = Math.ceil(duration * fps);
      const tracks = [];
      const sample = fn => {
        const out = [];
        for (let i = 0; i <= frames; i++) out.push(fn(Math.min(i / frames, 1)));
        return out;
      };
      const breath = u => 0.5 - 0.5 * Math.cos(TAU * 6 * u);
      const scan = u => Math.sin(TAU * u);
      const sway = u => Math.sin(TAU * 2 * u);
      const tremble = u => Math.sin(TAU * (feral ? 19 : 15) * u);
      const shiver = u => Math.sin(TAU * (feral ? 29 : 23) * u);
      const periodicBump = (u, center, width) => Math.exp(-Math.pow((u - center) / width, 2));

      add(tracks, map, 'root', 'position', sample(u => [0.35 * scan(u), 0.02 * breath(u), 0]), fps, duration);
      add(tracks, map, 'body_motion', 'position', sample(u => [
        0.08 * scan(u) * intensity,
        0.05 * (breath(u) - 0.5) * intensity,
        0
      ]), fps, duration);
      add(tracks, map, 'body_motion', 'rotation', sample(u => [
        (-1.0 + 1.0 * breath(u)) * intensity,
        2.4 * scan(u) * intensity,
        (1.5 * sway(u) + 0.8 * tremble(u)) * intensity
      ]), fps, duration);
      add(tracks, map, 'body_stretch', 'rotation', sample(u => [
        0, 0, (1.8 * sway(u) + 0.7 * shiver(u)) * intensity
      ]), fps, duration);
      add(tracks, map, 'upper_body', 'rotation', sample(u => [
        (-0.7 + 0.7 * breath(u)) * intensity,
        -1.2 * scan(u) * intensity,
        (-1.2 * sway(u) - 0.5 * tremble(u)) * intensity
      ]), fps, duration);
      add(tracks, map, 'head', 'rotation', sample(u => [
        (-1.0 + 1.0 * breath(u) + 0.8 * tremble(u)) * intensity
          + (feral ? 3 * periodicBump(u, 0.55, 0.035) : 0),
        (12 * scan(u) + 3 * Math.sin(TAU * 3 * u)) * intensity
          + (feral ? 18 * periodicBump(u, 0.22, 0.025) - 16 * periodicBump(u, 0.72, 0.025) : 0),
        (2.0 * sway(u) + 1.2 * tremble(u)) * intensity
          + (feral ? 8 * periodicBump(u, 0.86, 0.04) : 0)
      ]), fps, duration);
      for (const [name, sign] of [['hand_left', 1], ['hand_right', -1]]) {
        if (!has(name)) continue;
        add(tracks, map, name, 'rotation', sample(u => [
          (1.2 * breath(u) + 0.8 * tremble(u)) * intensity,
          (2.0 * Math.sin(TAU * 2 * u)) * sign * intensity,
          (2.6 * sway(u) + 1.6 * shiver(u)) * sign * intensity
        ]), fps, duration);
      }
      if (has('hair')) add(tracks, map, 'hair', 'rotation', sample(u => [
        (0.9 * breath(u) + 0.8 * tremble(u)) * intensity,
        0,
        (1.4 * sway(u) + 1.1 * shiver(u)) * intensity
      ]), fps, duration);
      if (has('bangs')) add(tracks, map, 'bangs', 'rotation', sample(u => [
        (0.7 * breath(u) + 0.6 * tremble(u)) * intensity,
        0,
        (1.0 * sway(u) + 0.8 * shiver(u)) * intensity
      ]), fps, duration);
      if (has('rump_sway')) add(tracks, map, 'rump_sway', 'rotation', sample(u => [
        0.6 * breath(u), 0.8 * scan(u), (2.0 * sway(u) + 0.8 * tremble(u)) * intensity
      ]), fps, duration);
      if (has('rump_roll')) add(tracks, map, 'rump_roll', 'rotation', sample(u => [
        0.4 * breath(u), 0.6 * scan(u), (-1.2 * sway(u) - 0.6 * tremble(u)) * intensity
      ]), fps, duration);
      for (const name of accessories) {
        if (!has(name)) continue;
        add(tracks, map, name, 'rotation', sample(u => [
          0.6 * tremble(u) * intensity,
          0.5 * scan(u) * intensity,
          (1.0 * sway(u) + (feral ? 2.2 : 1.2) * shiver(u)) * intensity
        ]), fps, duration);
      }
      return {
        duration,
        loop: true,
        tracks,
        expression_spans: [[0, duration, 'heat']],
        presentation: {
          id,
          label: feral ? '雷普魔 · 失控循环' : '素鸡立 · 发情循环',
          phases: [{at: 0, label: '持续发情', note: '呼吸、颤抖与扫视维持'}]
        }
      };
    }

    clips.heat_onset = makeOnset('heat_onset', 7.6, false);
    clips.heat_loop = makeLoop('heat_loop', 3.2);
    clips.rapem_onset = makeOnset('rapem_onset', 7.6, false);
    clips.rapem_loop = makeLoop('rapem_loop', 2.4);
    return clips;
  }

  return {buildHeatClips: build, build};
});
