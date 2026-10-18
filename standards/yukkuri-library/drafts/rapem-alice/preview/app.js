'use strict';

const $ = id => document.getElementById(id);
const MODELS = {
  alice: {
    id: 'alice',
    label: '爱丽丝',
    model: '/src/main/resources/assets/mcyukkuri/yukkuri/alice.json',
    textureDir: '/standards/yukkuri-library/assembled/alice/textures/',
    variantAvailable: true
  },
  marisa: {
    id: 'marisa',
    label: '魔理沙',
    model: '/src/main/resources/assets/mcyukkuri/yukkuri/marisa.json',
    textureDir: '/standards/yukkuri-library/assembled/marisa/textures/',
    variantAvailable: false
  }
};

const state = {
  model: 'alice',
  status: 'heat',
  mode: 'show',
  expression: 'auto',
  playing: true,
  speed: 1,
  time: 0,
  turntable: true,
  wireframe: false,
  aura: true,
  camera: {az: 0.36, el: 0.12, distance: 28},
  dragging: false,
  dragMoved: false,
  lastX: 0,
  lastY: 0
};

let renderer, scene, camera, characterGroup, mesh, geometry, material;
let pose, modelData, bounds = {centerY: 5.9, height: 11.8, radius: 8};
let loadToken = 0, textureToken = 0;
let visibleIndices = [];
let blendSource = null;
let lastFrame = performance.now();
let ready = false;
let placeholderTexture;
let currentTextureUrl = '';
let auraSprite, auraRing, particles, particleData = [], particlePositions;
let phaseSegments = [];
let phaseTotal = 10.8;

const textureCache = new Map();
const motionClipsCache = new Map();

/* ------------------------------------------------------------------ */
/* Three.js stage                                                      */
/* ------------------------------------------------------------------ */

function initStage() {
  const canvas = $('view');
  renderer = new THREE.WebGLRenderer({canvas, antialias: true, alpha: true});
  if (renderer.outputEncoding !== undefined && THREE.sRGBEncoding !== undefined) {
    renderer.outputEncoding = THREE.sRGBEncoding;
  }
  renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 2));

  scene = new THREE.Scene();
  camera = new THREE.PerspectiveCamera(35, 1, 0.1, 300);

  characterGroup = new THREE.Group();
  scene.add(characterGroup);

  const hemi = new THREE.HemisphereLight(0xdff1e6, 0x1b2b25, 1.05);
  scene.add(hemi);
  const key = new THREE.DirectionalLight(0xffe4c5, 1.15);
  key.position.set(-9, 18, -13);
  scene.add(key);
  const rim = new THREE.DirectionalLight(0x9dc9d8, 0.7);
  rim.position.set(11, 9, 14);
  scene.add(rim);
  const fill = new THREE.DirectionalLight(0xffad76, 0.38);
  fill.position.set(0, 5, 16);
  scene.add(fill);

  placeholderTexture = new THREE.DataTexture(new Uint8Array([255, 255, 255, 255]), 1, 1, THREE.RGBAFormat);
  placeholderTexture.needsUpdate = true;

  buildGround();
  buildAura();
  bindPointer();
  new ResizeObserver(resize).observe($('stage'));
  resize();
  requestAnimationFrame(loop);
}

function buildGround() {
  const floor = new THREE.Mesh(
    new THREE.CircleGeometry(8.8, 64),
    new THREE.MeshLambertMaterial({color: 0x0e1a17, transparent: true, opacity: 0.94})
  );
  floor.rotation.x = -Math.PI / 2;
  floor.position.y = 0;
  scene.add(floor);

  const ring = new THREE.Mesh(
    new THREE.RingGeometry(8.55, 8.78, 96),
    new THREE.MeshBasicMaterial({color: 0x648875, transparent: true, opacity: 0.46, side: THREE.DoubleSide})
  );
  ring.rotation.x = -Math.PI / 2;
  ring.position.y = 0.012;
  scene.add(ring);

  const grid = new THREE.GridHelper(28, 28, 0x2b4a3e, 0x1b3029);
  grid.position.y = -0.03;
  grid.material.transparent = true;
  grid.material.opacity = 0.32;
  scene.add(grid);

  // Soft contact shadow, kept on the floor while the turntable rotates.
  const shadowTexture = makeRadialTexture(256, 128, [
    [0, 'rgba(0,0,0,0.62)'],
    [0.45, 'rgba(0,0,0,0.30)'],
    [1, 'rgba(0,0,0,0)']
  ]);
  const shadow = new THREE.Mesh(
    new THREE.PlaneGeometry(11.5, 7.4),
    new THREE.MeshBasicMaterial({map: shadowTexture, transparent: true, depthWrite: false, opacity: 0.72})
  );
  shadow.rotation.x = -Math.PI / 2;
  shadow.position.y = 0.02;
  scene.add(shadow);

  // A low warm pool of light under the subject.
  const pool = new THREE.Mesh(
    new THREE.CircleGeometry(7.4, 64),
    new THREE.MeshBasicMaterial({
      map: makeRadialTexture(256, 256, [
        [0, 'rgba(224,163,95,0.12)'],
        [0.55, 'rgba(216,129,82,0.06)'],
        [1, 'rgba(0,0,0,0)']
      ]),
      transparent: true,
      depthWrite: false,
      blending: THREE.AdditiveBlending
    })
  );
  pool.rotation.x = -Math.PI / 2;
  pool.position.y = 0.025;
  scene.add(pool);
}

function buildAura() {
  const auraTexture = makeRadialTexture(256, 256, [
    [0, 'rgba(255,212,160,0.36)'],
    [0.35, 'rgba(240,132,92,0.20)'],
    [0.72, 'rgba(200,74,58,0.06)'],
    [1, 'rgba(0,0,0,0)']
  ]);
  auraSprite = new THREE.Sprite(new THREE.SpriteMaterial({
    map: auraTexture,
    color: 0xffa25f,
    transparent: true,
    depthWrite: false,
    depthTest: true,
    blending: THREE.AdditiveBlending,
    opacity: 0
  }));
  auraSprite.position.set(0, 6.4, 8.4);
  auraSprite.scale.set(18, 18, 1);
  scene.add(auraSprite);

  auraRing = new THREE.Mesh(
    new THREE.RingGeometry(8.2, 8.55, 96),
    new THREE.MeshBasicMaterial({
      color: 0xff9a62,
      transparent: true,
      opacity: 0,
      side: THREE.DoubleSide,
      depthWrite: false,
      blending: THREE.AdditiveBlending
    })
  );
  auraRing.rotation.x = -Math.PI / 2;
  auraRing.position.y = 0.035;
  scene.add(auraRing);

  const count = 64;
  particlePositions = new Float32Array(count * 3);
  for (let i = 0; i < count; i++) {
    particleData.push({
      angle: Math.random() * Math.PI * 2,
      radius: 2.2 + Math.random() * 4.8,
      y: Math.random() * 11.5,
      speed: 0.35 + Math.random() * 0.75,
      sway: 0.3 + Math.random() * 1.1
    });
  }
  const geometry = new THREE.BufferGeometry();
  geometry.setAttribute('position', new THREE.BufferAttribute(particlePositions, 3));
  particles = new THREE.Points(geometry, new THREE.PointsMaterial({
    color: 0xffa66a,
    size: 0.26,
    map: makeParticleTexture(),
    transparent: true,
    opacity: 0,
    depthWrite: false,
    blending: THREE.AdditiveBlending,
    sizeAttenuation: true
  }));
  particles.frustumCulled = false;
  scene.add(particles);
}

function makeRadialTexture(w, h, stops) {
  const canvas = document.createElement('canvas');
  canvas.width = w; canvas.height = h;
  const ctx = canvas.getContext('2d');
  const gradient = ctx.createRadialGradient(w / 2, h / 2, 0, w / 2, h / 2, Math.max(w, h) / 2);
  for (const [at, color] of stops) gradient.addColorStop(at, color);
  ctx.fillStyle = gradient;
  ctx.fillRect(0, 0, w, h);
  const texture = new THREE.CanvasTexture(canvas);
  texture.encoding = THREE.sRGBEncoding;
  return texture;
}

function makeParticleTexture() {
  const canvas = document.createElement('canvas');
  canvas.width = 32; canvas.height = 32;
  const ctx = canvas.getContext('2d');
  const gradient = ctx.createRadialGradient(16, 16, 0, 16, 16, 16);
  gradient.addColorStop(0, 'rgba(255,235,205,1)');
  gradient.addColorStop(0.35, 'rgba(255,165,110,0.7)');
  gradient.addColorStop(1, 'rgba(255,100,70,0)');
  ctx.fillStyle = gradient;
  ctx.fillRect(0, 0, 32, 32);
  return new THREE.CanvasTexture(canvas);
}

function resize() {
  if (!renderer) return;
  const stage = $('stage');
  const w = stage.clientWidth, h = stage.clientHeight;
  if (!w || !h) return;
  renderer.setSize(w, h, false);
  camera.aspect = w / h;
  camera.updateProjectionMatrix();
}

function updateCamera() {
  const d = state.camera.distance;
  const az = state.camera.az, el = state.camera.el;
  const targetY = bounds.centerY;
  camera.position.set(
    Math.sin(az) * Math.cos(el) * d,
    targetY + Math.sin(el) * d,
    -Math.cos(az) * Math.cos(el) * d
  );
  camera.lookAt(0, targetY, 0);
}

function bindPointer() {
  const canvas = $('view');
  canvas.addEventListener('pointerdown', event => {
    state.dragging = true;
    state.dragMoved = false;
    state.lastX = event.clientX;
    state.lastY = event.clientY;
    canvas.setPointerCapture(event.pointerId);
  });
  canvas.addEventListener('pointermove', event => {
    if (!state.dragging) return;
    const dx = event.clientX - state.lastX;
    const dy = event.clientY - state.lastY;
    if (Math.abs(dx) + Math.abs(dy) > 2) {
      state.dragMoved = true;
      state.turntable = false;
      $('turntable').checked = false;
    }
    state.lastX = event.clientX;
    state.lastY = event.clientY;
    state.camera.az -= dx * 0.007;
    state.camera.el = clamp(state.camera.el + dy * 0.005, -0.12, 0.78);
  });
  const endDrag = event => {
    state.dragging = false;
    if (canvas.hasPointerCapture(event.pointerId)) canvas.releasePointerCapture(event.pointerId);
  };
  canvas.addEventListener('pointerup', endDrag);
  canvas.addEventListener('pointercancel', endDrag);
  canvas.addEventListener('wheel', event => {
    event.preventDefault();
    state.camera.distance = clamp(state.camera.distance * (1 + event.deltaY * 0.0012), 14, 62);
  }, {passive: false});
}

/* ------------------------------------------------------------------ */
/* Model loading                                                       */
/* ------------------------------------------------------------------ */

async function loadModel(key) {
  const token = ++loadToken;
  const config = MODELS[key];
  const loading = $('loading');
  loading.classList.remove('hidden');
  loading.innerHTML = '<span class="spinner"></span><b>读取模型与动作…</b><small>' + config.label +
    '运行模型约 11 MB，首次载入稍慢</small>';
  $('play').disabled = true;
  $('play-2').disabled = true;
  ready = false;

  try {
    const response = await fetch(config.model);
    if (!response.ok) throw new Error('HTTP ' + response.status);
    const model = await response.json();
    if (token !== loadToken) return;
    modelData = model;

    if (mesh) {
      characterGroup.remove(mesh);
      geometry.dispose();
      material.dispose();
      mesh = null;
    }

    const nextPose = new RuntimePose(model, 1, null, null);
    const clips = motionClipsCache.get(key) || MotionClips.buildHeatClips(model, {variant: key === 'alice' ? 'alice_rapem' : 'marisa'});
    motionClipsCache.set(key, clips);
    Object.assign(nextPose.clips, clips);
    pose = nextPose;

    buildGeometry();
    computeBounds();
    state.time = 0;
    state.playing = true;
    updatePlayButton();
    updateSubject();
    updateStateButtons();
    rebuildPhaseTrack();
    updateTimeline();
    setAuraPalette();

    const firstExpression = pose.expressionFor(currentActionAndTime().action, 0, 'neutral');
    const firstUrl = textureUrlFor(firstExpression);
    const mainFace = state.status === 'rapem' ? 'teary_tongue' : 'flushed_tongue';
    await Promise.all([
      loadTexture(textureUrlFor(mainFace)).catch(() => null),
      loadTexture(textureUrlFor('flushed_smile')).catch(() => null),
      loadTexture(textureUrlFor('surprise')).catch(() => null)
    ]);
    const firstTexture = await loadTexture(firstUrl).catch(() => loadTexture(textureUrlFor('neutral')));
    if (token !== loadToken) return;

    material.map = firstTexture || placeholderTexture;
    currentTextureUrl = firstTexture ? firstUrl : '';
    material.needsUpdate = true;

    characterGroup.rotation.y = 0;
    state.camera.distance = Math.max(22, bounds.height * 2.7);
    updateCamera();
    loading.classList.add('hidden');
    $('play').disabled = false;
    $('play-2').disabled = false;
    ready = true;
  } catch (error) {
    if (token !== loadToken) return;
    loading.innerHTML = '<span class="spinner" style="animation:none;border-color:#d86152"></span><b>模型未能载入</b><small>' +
      escapeHtml(error.message) + '<br>请从仓库根目录启动本地 HTTP 服务后访问本页。</small>';
  }
}

function buildGeometry() {
  const count = pose.model.vertices.length / 8;
  const positions = new Float32Array(count * 3);
  const uvs = new Float32Array(count * 2);
  const normals = new Float32Array(count * 3);
  geometry = new THREE.BufferGeometry();
  geometry.setAttribute('position', new THREE.BufferAttribute(positions, 3));
  geometry.setAttribute('uv', new THREE.BufferAttribute(uvs, 2));
  geometry.setAttribute('normal', new THREE.BufferAttribute(normals, 3));
  const indices = [];
  for (const part of pose.model.parts) {
    if (part.anatomy) continue;
    for (let i = part.start; i < part.start + part.count; i++) indices.push(i);
  }
  geometry.setIndex(indices);
  visibleIndices = indices;
  blendSource = new Float64Array(count * 8);
  material = new THREE.MeshLambertMaterial({
    map: placeholderTexture,
    side: THREE.DoubleSide,
    alphaTest: 0.04
  });
  material.color.setHex(0xffffff);
  material.wireframe = state.wireframe;
  mesh = new THREE.Mesh(geometry, material);
  mesh.frustumCulled = false;
  characterGroup.add(mesh);
}

function computeBounds() {
  const out = pose.frame('idle', 0);
  let minY = Infinity, maxY = -Infinity, maxXZ = 0;
  for (const part of pose.model.parts) {
    if (part.anatomy || /^(peni_taper|mamu_)/.test(part.name)) continue;
    for (let i = part.start; i < part.start + part.count; i++) {
      const k = i * 8;
      const y = out[k + 1];
      minY = Math.min(minY, y);
      maxY = Math.max(maxY, y);
      maxXZ = Math.max(maxXZ, Math.hypot(out[k], out[k + 2]));
    }
  }
  bounds = {
    centerY: (minY + maxY) / 2,
    height: maxY - minY,
    radius: maxXZ
  };
}

function textureUrlFor(name) {
  return MODELS[state.model].textureDir + name + '.png';
}

function loadTexture(url) {
  if (textureCache.has(url)) return Promise.resolve(textureCache.get(url));
  return new Promise((resolve, reject) => {
    new THREE.TextureLoader().load(url, texture => {
      texture.flipY = false;
      texture.magFilter = THREE.NearestFilter;
      texture.minFilter = THREE.NearestFilter;
      texture.generateMipmaps = false;
      if (THREE.sRGBEncoding !== undefined) texture.encoding = THREE.sRGBEncoding;
      textureCache.set(url, texture);
      resolve(texture);
    }, undefined, () => reject(new Error('贴图读取失败：' + url)));
  });
}

async function applyExpression(name) {
  if (!material || name === 'auto') return;
  const url = textureUrlFor(name);
  if (url === currentTextureUrl) return;
  const token = ++textureToken;
  try {
    const texture = await loadTexture(url);
    if (token !== textureToken) return;
    material.map = texture;
    material.needsUpdate = true;
    currentTextureUrl = url;
  } catch {
    // Keep the previous face rather than blanking the model.
  }
}

/* ------------------------------------------------------------------ */
/* Clip timing                                                         */
/* ------------------------------------------------------------------ */

function prefix() {
  return state.status === 'rapem' ? 'rapem' : 'heat';
}

function currentClips() {
  const p = prefix();
  return {
    onset: pose.clips[p + '_onset'],
    loop: pose.clips[p + '_loop'],
    idle: pose.clips.idle
  };
}

function modeTotal() {
  const clips = currentClips();
  if (state.mode === 'idle') return clips.idle ? clips.idle.duration : 1;
  if (state.mode === 'loop') return clips.loop ? clips.loop.duration : 3.2;
  return (clips.onset ? clips.onset.duration : 7.6) + (clips.loop ? clips.loop.duration : 3.2);
}

function currentActionAndTime() {
  const clips = currentClips();
  if (!clips.onset || !clips.loop) {
    return {action: 'idle', t: 0, clip: clips.idle, start: 0, inLoop: false};
  }
  if (state.mode === 'idle') {
    const duration = clips.idle.duration;
    const t = wrap(state.time, duration);
    return {action: 'idle', t, clip: clips.idle, start: 0, inLoop: false};
  }
  if (state.mode === 'loop') {
    const duration = clips.loop.duration;
    const t = wrap(state.time, duration);
    return {action: prefix() + '_loop', t, clip: clips.loop, start: 0, inLoop: true};
  }
  const onsetDuration = clips.onset.duration;
  const loopDuration = clips.loop.duration;
  const total = onsetDuration + loopDuration;
  const t = wrap(state.time, total);
  if (t < onsetDuration) {
    return {action: prefix() + '_onset', t, clip: clips.onset, start: 0, inLoop: false};
  }
  const loopT = wrap(t - onsetDuration, loopDuration);
  const blendAlpha = t < onsetDuration + 0.6 ? smooth01((t - onsetDuration) / 0.6) : 1;
  return {
    action: prefix() + '_loop',
    t: loopT,
    clip: clips.loop,
    start: onsetDuration,
    inLoop: true,
    blend: blendAlpha < 1
      ? {fromAction: prefix() + '_onset', fromT: onsetDuration, alpha: blendAlpha}
      : null
  };
}

function wrap(v, duration) {
  if (!duration || duration <= 0) return 0;
  return ((v % duration) + duration) % duration;
}

/* ------------------------------------------------------------------ */
/* Frame update                                                        */
/* ------------------------------------------------------------------ */

function loop(now) {
  requestAnimationFrame(loop);
  const dt = Math.min(0.05, (now - lastFrame) / 1000);
  lastFrame = now;

  if (ready && pose) {
    if (state.playing) {
      state.time += dt * state.speed;
      const total = modeTotal();
      if (state.time >= total) state.time = wrap(state.time, total);
    }

    const info = currentActionAndTime();
    updatePose(info);
    updateExpression(info);
    updateTimelineUI(info);
    updateAura(dt, info);

    if (state.turntable) characterGroup.rotation.y += dt * 0.26;
    updateCamera();
  } else {
    updateCamera();
  }

  renderer.render(scene, camera);
}

function updatePose(info) {
  if (!pose.clips[info.action]) return;
  let out;
  let blendSourceView = null;
  if (info.blend) {
    const source = pose.frame(info.blend.fromAction, info.blend.fromT);
    blendSource.set(source);
    blendSourceView = blendSource;
    out = pose.frame(info.action, info.t);
  } else {
    out = pose.frame(info.action, info.t);
  }
  const alpha = info.blend ? info.blend.alpha : 1;
  const position = geometry.attributes.position.array;
  const uv = geometry.attributes.uv.array;
  const normal = geometry.attributes.normal.array;
  for (let i = 0, j = 0, k = 0; i < out.length; i += 8, j += 3, k += 2) {
    position[j] = blendSourceView ? blendSourceView[i] * (1 - alpha) + out[i] * alpha : out[i];
    position[j + 1] = blendSourceView ? blendSourceView[i + 1] * (1 - alpha) + out[i + 1] * alpha : out[i + 1];
    position[j + 2] = blendSourceView ? blendSourceView[i + 2] * (1 - alpha) + out[i + 2] * alpha : out[i + 2];
    uv[k] = out[i + 3];
    uv[k + 1] = out[i + 4];
    normal[j] = out[i + 5];
    normal[j + 1] = out[i + 6];
    normal[j + 2] = out[i + 7];
  }
  // Keep the performance grounded: head/body tilts can push the lower rim below y=0.
  let minY = Infinity;
  for (const i of visibleIndices) { const y = position[i * 3 + 1]; if (y < minY) minY = y; }
  const lift = minY < 0.02 ? 0.02 - minY : 0;
  if (lift > 0) {
    for (const i of visibleIndices) position[i * 3 + 1] += lift;
  }
  geometry.attributes.position.needsUpdate = true;
  geometry.attributes.uv.needsUpdate = true;
  geometry.attributes.normal.needsUpdate = true;
}

function updateExpression(info) {
  if (state.expression !== 'auto') {
    applyExpression(state.expression);
    return;
  }
  const name = pose.expressionFor(info.action, info.t, info.action === 'idle' ? 'neutral' : 'flushed_tongue');
  applyExpression(name);
}

function updateTimelineUI(info) {
  const total = modeTotal();
  const timeline = $('timeline');
  timeline.max = total.toFixed(2);
  timeline.value = state.time.toFixed(2);
  $('clock').textContent = state.time.toFixed(2) + ' / ' + total.toFixed(2) + ' 秒';

  const phase = currentPhase(info);
  $('hud-phase').textContent = phase.label;
  $('hud-clock').textContent = state.time.toFixed(2) + ' / ' + total.toFixed(2) + ' 秒';
  $('phase-index').textContent = String(phase.index + 1).padStart(2, '0');
  $('phase-title').textContent = phase.label;
  $('phase-note').textContent = phase.note;

  const segments = $('phase-track').children;
  for (let i = 0; i < segments.length; i++) segments[i].classList.toggle('active', i === phase.index);

  const amount = heatAmount(info);
  $('meter-value').textContent = Math.round(amount * 100) + '%';
  $('meter-fill').style.width = (amount * 100).toFixed(1) + '%';
}

function currentPhase(info) {
  if (state.mode === 'idle') return {index: 0, label: '待机对照', note: '标准待机呼吸；发情演出从这里开始。'};
  if (state.mode === 'loop') return {index: 0, label: state.status === 'rapem' ? '持续失控' : '持续发情', note: state.status === 'rapem' ? '快速摇摆、抽头与跳跃交替；不演出交配动作。' : '站立呼吸、重心摆动与轻度用力；不演出交配动作。'};
  const clips = currentClips();
  const onset = clips.onset;
  const phases = onset.presentation?.phases || [];
  if (info.inLoop) {
    return {index: phases.length, label: state.status === 'rapem' ? '持续失控' : '持续发情', note: state.status === 'rapem' ? '进入失控循环；快速摇摆、抽头与跳跃交替。' : '进入发情循环；保持站立，呼吸与重心摆动维持。'};
  }
  let index = 0;
  for (let i = 0; i < phases.length; i++) if (info.t >= phases[i].at) index = i;
  return {
    index,
    label: phases[index]?.label || '发情期',
    note: phases[index]?.note || ''
  };
}

function heatAmount(info) {
  if (state.mode === 'idle') return 0;
  const t = info.t;
  if (state.status === 'rapem') {
    if (state.mode === 'loop' || info.inLoop) return 0.92;
    if (t < 0.5) return 0.10 + 0.20 * (t / 0.5);
    if (t < 0.9) return 0.30 + 0.25 * ((t - 0.5) / 0.4);
    if (t < 2.1) return 0.55 + 0.20 * ((t - 0.9) / 1.2);
    if (t < 3.55) return 0.75 + 0.15 * ((t - 2.1) / 1.45);
    if (t < 4.3) return 1;
    return 0.90;
  }
  if (state.mode === 'loop' || info.inLoop) return 0.72;
  if (t < 0.62) return 0.10 + 0.15 * (t / 0.62);
  if (t < 2.7) return 0.25 + 0.28 * ((t - 0.62) / 2.08);
  if (t < 4.3) return 0.53 + 0.19 * ((t - 2.7) / 1.6);
  return 0.72 + 0.08 * Math.min(1, (t - 4.3) / 3.3);
}

function updateAura(dt, info) {
  const amount = heatAmount(info);
  const visible = state.aura && state.mode !== 'idle';
  const target = visible ? 0.08 + amount * 0.40 : 0;
  const color = state.status === 'rapem' ? 0xff5b48 : 0xffb37a;

  if (auraSprite) {
    auraSprite.material.opacity += (target - auraSprite.material.opacity) * Math.min(1, dt * 6);
    auraSprite.material.color.setHex(color);
    const pulse = 1 + 0.035 * Math.sin(performance.now() * 0.0024) + amount * 0.08;
    auraSprite.scale.set(18 * pulse, 18 * pulse, 1);
    auraSprite.visible = state.aura;
  }
  if (auraRing) {
    auraRing.material.opacity = visible ? 0.05 + amount * 0.22 : 0;
    auraRing.material.color.setHex(color);
    const s = 1 + 0.018 * Math.sin(performance.now() * 0.003) + amount * 0.03;
    auraRing.scale.set(s, s, s);
  }
  if (particles) {
    particles.material.opacity = visible ? 0.12 + amount * 0.62 : 0;
    particles.material.color.setHex(color);
    const positions = particles.geometry.attributes.position.array;
    const speed = 0.4 + amount * 1.35;
    for (let i = 0; i < particleData.length; i++) {
      const p = particleData[i];
      p.y += dt * p.speed * speed;
      p.angle += dt * (0.18 + amount * 0.45) * (i % 2 ? 1 : -1);
      if (p.y > 11.8) {
        p.y = 0;
        p.angle = Math.random() * Math.PI * 2;
        p.radius = 2.2 + Math.random() * 4.8;
      }
      const wobble = 0.22 * Math.sin(performance.now() * 0.001 * p.sway + i);
      positions[i * 3] = Math.cos(p.angle) * (p.radius + wobble);
      positions[i * 3 + 1] = p.y;
      positions[i * 3 + 2] = Math.sin(p.angle) * (p.radius + wobble);
    }
    particles.geometry.attributes.position.needsUpdate = true;
  }
}

/* ------------------------------------------------------------------ */
/* UI                                                                  */
/* ------------------------------------------------------------------ */

function rebuildPhaseTrack() {
  const track = $('phase-track');
  track.replaceChildren();
  phaseSegments = [];
  const clips = currentClips();
  if (state.mode === 'idle') {
    phaseSegments.push({label: '待机', note: '标准待机呼吸', at: 0, duration: clips.idle.duration});
  } else if (state.mode === 'loop') {
    phaseSegments.push({label: state.status === 'rapem' ? '持续失控' : '持续发情', note: state.status === 'rapem' ? '快速摇摆、抽头与跳跃交替' : '站立呼吸、重心摆动与轻度用力', at: 0, duration: clips.loop.duration});
  } else {
    const onset = clips.onset;
    const phases = onset.presentation?.phases || [{at: 0, label: '发情期', note: ''}];
    for (let i = 0; i < phases.length; i++) {
      const start = phases[i].at;
      const end = phases[i + 1]?.at ?? onset.duration;
      phaseSegments.push({
        label: phases[i].label,
        note: phases[i].note,
        at: start,
        duration: Math.max(0.01, end - start)
      });
    }
    phaseSegments.push({
      label: state.status === 'rapem' ? '持续失控' : '持续发情',
      note: state.status === 'rapem' ? '进入失控循环；快速摇摆、抽头与跳跃交替。' : '进入发情循环；保持站立，呼吸与重心摆动维持。',
      at: onset.duration,
      duration: clips.loop.duration
    });
  }
  phaseTotal = modeTotal();
  for (const segment of phaseSegments) {
    const bar = document.createElement('i');
    bar.style.flexGrow = String(Math.max(0.01, segment.duration / phaseTotal));
    bar.title = segment.label;
    bar.addEventListener('click', () => {
      state.time = segment.at;
      updateTimeline();
    });
    track.append(bar);
  }
}

function updateSubject() {
  const config = MODELS[state.model];
  const isRapem = state.status === 'rapem' && config.variantAvailable;
  $('subject-tag').textContent = (config.id === 'alice' ? 'ALICE' : 'MARISA') + (isRapem ? ' · RAPEM' : ' · HEAT');
  $('subject-name').textContent = isRapem ? '雷普魔爱丽丝' : (config.id === 'alice' ? '素鸡立' : '素鸡立 · 魔理沙');
  $('subject-sub').textContent = isRapem
    ? '失控异常状态 · 八景 U「基本エロ笑顔」teary_tongue'
    : (config.id === 'alice'
      ? '发情期站立演出 · 八景 U「基本発情顔１→２」'
      : '同一套素鸡立站立动作 · 魔理沙通用性演示');
  $('variant-badge').hidden = !isRapem;
  $('state-hint').textContent = isRapem
    ? '雷普魔是爱丽丝的失控异常状态：使用八景 U「基本エロ笑顔１」的 teary_tongue（睁眼、含泪、伸舌），动作加入突然抽头、下蹲、快速踱步和跳跃前冲。它不是素鸡立的加强版；Wiki 明确要求不要把变体推广为每只爱丽丝的常态。'
    : '素鸡立是通用的发情期站立演出：使用八景 U「基本発情顔１→２」的 flushed_smile → flushed_tongue（闭眼、潮红、笑/伸舌），身体保持站立，只做呼吸、重心摆动和轻度用力。普通爱丽丝仍以社交、觅食和结伴为主。';
  $('rapem-button').disabled = !config.variantAvailable;
  $('meter-label').textContent = isRapem ? '失控强度' : '发情强度';
}

function updateStateButtons() {
  document.querySelectorAll('#state-buttons button').forEach(button => {
    button.classList.toggle('active', button.dataset.state === state.status);
  });
  document.querySelectorAll('#model-buttons button').forEach(button => {
    button.classList.toggle('active', button.dataset.model === state.model);
  });
  document.querySelectorAll('#mode-buttons button').forEach(button => {
    button.classList.toggle('active', button.dataset.mode === state.mode);
  });
}

function updatePlayButton() {
  const text = state.playing ? '暂停' : '播放';
  $('play').textContent = text;
  $('play-2').textContent = text;
}

function updateTimeline() {
  const total = modeTotal();
  $('timeline').max = total.toFixed(2);
  $('timeline').value = state.time.toFixed(2);
  $('clock').textContent = state.time.toFixed(2) + ' / ' + total.toFixed(2) + ' 秒';
}

function setAuraPalette() {
  const color = state.status === 'rapem' ? 0xff5b48 : 0xffa25f;
  if (auraSprite) auraSprite.material.color.setHex(color);
  if (auraRing) auraRing.material.color.setHex(color);
  if (particles) particles.material.color.setHex(color);
}

function bindUI() {
  $('model-buttons').addEventListener('click', event => {
    const button = event.target.closest('button[data-model]');
    if (!button || button.dataset.model === state.model) return;
    state.model = button.dataset.model;
    if (!MODELS[state.model].variantAvailable && state.status === 'rapem') state.status = 'heat';
    updateStateButtons();
    loadModel(state.model);
  });

  $('state-buttons').addEventListener('click', event => {
    const button = event.target.closest('button[data-state]');
    if (!button || button.disabled || button.dataset.state === state.status) return;
    state.status = button.dataset.state;
    state.time = 0;
    updateStateButtons();
    updateSubject();
    setAuraPalette();
    if (pose) {
      rebuildPhaseTrack();
      updateTimeline();
    }
  });

  $('mode-buttons').addEventListener('click', event => {
    const button = event.target.closest('button[data-mode]');
    if (!button || button.dataset.mode === state.mode) return;
    state.mode = button.dataset.mode;
    state.time = 0;
    updateStateButtons();
    if (pose) {
      rebuildPhaseTrack();
      updateTimeline();
    }
  });

  const togglePlay = () => {
    state.playing = !state.playing;
    updatePlayButton();
  };
  $('play').addEventListener('click', togglePlay);
  $('play-2').addEventListener('click', togglePlay);

  $('step-back').addEventListener('click', () => {
    if (!ready || !pose) return;
    state.time = clamp(state.time - 0.1, 0, Math.max(0, modeTotal() - 0.01));
    updateTimeline();
  });
  $('step').addEventListener('click', () => {
    if (!ready || !pose) return;
    state.time = clamp(state.time + 0.1, 0, Math.max(0, modeTotal() - 0.01));
    updateTimeline();
  });

  $('timeline').addEventListener('input', event => {
    if (!ready || !pose) return;
    state.time = Number(event.target.value);
    updateTimeline();
  });

  $('speed').addEventListener('change', event => {
    state.speed = Number(event.target.value);
  });

  $('expression').addEventListener('change', event => {
    state.expression = event.target.value;
  });

  $('turntable').addEventListener('change', event => {
    state.turntable = event.target.checked;
  });
  $('wireframe').addEventListener('change', event => {
    state.wireframe = event.target.checked;
    if (material) material.wireframe = state.wireframe;
  });
  $('aura').addEventListener('change', event => {
    state.aura = event.target.checked;
  });

  $('camera-buttons').addEventListener('click', event => {
    const button = event.target.closest('button[data-cam]');
    if (!button) return;
    const cam = button.dataset.cam;
    state.turntable = false;
    $('turntable').checked = false;
    if (cam === 'front') { state.camera.az = 0; state.camera.el = 0.08; }
    else if (cam === 'side') { state.camera.az = Math.PI / 2; state.camera.el = 0.08; }
    else if (cam === 'three') { state.camera.az = 0.42; state.camera.el = 0.12; }
    else { state.camera.az = 0.36; state.camera.el = 0.12; state.camera.distance = Math.max(22, bounds.height * 2.7); }
    updateCamera();
  });

  window.addEventListener('keydown', event => {
    if (event.code !== 'Space') return;
    const tag = event.target.tagName;
    if (tag === 'INPUT' || tag === 'SELECT' || tag === 'TEXTAREA' || tag === 'BUTTON') return;
    event.preventDefault();
    togglePlay();
  });
}

function escapeHtml(text) {
  return String(text).replace(/[&<>"']/g, c => ({'&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'}[c]));
}

function clamp(v, a, b) {
  return Math.max(a, Math.min(b, v));
}

function smooth01(x) {
  x = clamp(x, 0, 1);
  return x * x * (3 - 2 * x);
}

/* ------------------------------------------------------------------ */
/* Boot                                                                */
/* ------------------------------------------------------------------ */

initStage();
bindUI();
updateSubject();
updateStateButtons();
updatePlayButton();
loadModel('alice');
