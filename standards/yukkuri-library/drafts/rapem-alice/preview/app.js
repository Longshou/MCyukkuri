'use strict';

const $ = id => document.getElementById(id);
const MODELS = {
  alice: {
    id: 'alice',
    label: '爱丽丝',
    model: '/src/main/resources/assets/mcyukkuri/yukkuri/alice.json',
    textureDir: '/standards/yukkuri-library/assembled/alice/textures/',
    rapemTexture: '/standards/yukkuri-library/drafts/rapem-alice/variant/alice_agitated.png',
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
  turntable: false,
  wireframe: false,
  aura: false,
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
    const mainFace = state.status === 'rapem' ? 'alice_agitated' : state.status === 'relief' ? 'satisfied' : state.status === 'act' ? 'drool' : 'closed_smile';
    await Promise.all([
      loadTexture(textureUrlFor(mainFace)).catch(() => null),
      loadTexture(textureUrlFor('confused')).catch(() => null),
      loadTexture(textureUrlFor('sleepy')).catch(() => null),
      loadTexture(textureUrlFor('satisfied')).catch(() => null),
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
    if (part.anatomy || /^(peni_taper|mamu_)/.test(part.name)) continue;
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
  const config = MODELS[state.model];
  if (config.variantAvailable && ['alice_agitated','breeding_period','state_recover'].includes(name)) return '/standards/yukkuri-library/drafts/rapem-alice/variant/' + name + '.png';
  if (name === 'alice_agitated') name = 'angry';
  if (name === 'breeding_period') name = 'neutral';
  if (name === 'state_recover') name = 'sleepy';
  return config.textureDir + name + '.png';
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
  if (state.status === 'relief') return {onset: pose.clips.heat_relief, idle: pose.clips.idle};
  if (state.status === 'act') return {onset: pose.clips.rapem_act, idle: pose.clips.idle};
  return {onset: pose.clips[prefix() + '_onset'], idle: pose.clips.idle};
}

function modeTotal() {
  const clips = currentClips();
  return state.mode === 'idle' ? (clips.idle?.duration || 1) : (clips.onset?.duration || 10);
}

function currentActionAndTime() {
  const clips = currentClips();
  if (state.mode === 'idle' || !clips.onset) {
    return {action: 'idle', t: wrap(state.time, clips.idle.duration), clip: clips.idle};
  }
  const action = state.status === 'relief' ? 'heat_relief' : state.status === 'act' ? 'rapem_act' : prefix() + '_onset';
  return {action, t: clamp(state.time, 0, clips.onset.duration), clip: clips.onset};
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
      if (state.time >= total) {
        if (state.mode === 'idle' || state.status === 'heat' || state.status === 'act') state.time = wrap(state.time, total);
        else { state.time = total; state.playing = false; updatePlayButton(); }
      }
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
  const name = pose.expressionFor(info.action, info.t, 'neutral');
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
  if (state.mode === 'idle') return {index: 0, label: '待机对照', note: '标准待机呼吸；不触发任何状态。'};
  const phases = info.clip.presentation?.phases || [];
  let index = 0;
  for (let i = 0; i < phases.length; i++) if (info.t >= phases[i].at) index = i;
  return {index, label: phases[index]?.label || '正常', note: phases[index]?.note || ''};
}

function heatAmount(info) {
  if (state.mode === 'idle') return 0;
  if (state.status === 'heat') return 1;
  if (state.status === 'act') return .85;
  if (state.status === 'relief') {
    // Two soft pulses aligned with the staged beats (0s / 2.3s / 4.55s), then a calm tail.
    const t = info.t;
    return Math.max(smooth01(t / 1.2) * (1 - smooth01((t - 2.3) / 0.8)),
      smooth01((t - 4.0) / 0.9) * (1 - smooth01((t - 5.5) / 0.5))) * 0.9;
  }
  const t = info.t;
  const start = 0.2;
  const peak = 3.0;
  const decline = 5.4;
  return smooth01((t - start) / (peak - start)) * (1 - smooth01((t - decline) / (9.2 - decline)));
}

function updateAura(dt, info) {
  const amount = heatAmount(info);
  const visible = state.aura && state.mode !== 'idle';
  const target = visible ? amount * 0.40 : 0;
  const color = state.status === 'rapem' || state.status === 'act' ? 0xff5b48 : state.status === 'relief' ? 0xffd76b : 0xffb37a;

  if (auraSprite) {
    auraSprite.material.opacity += (target - auraSprite.material.opacity) * Math.min(1, dt * 6);
    auraSprite.material.color.setHex(color);
    const pulse = 1 + 0.035 * Math.sin(performance.now() * 0.0024) + amount * 0.08;
    auraSprite.scale.set(18 * pulse, 18 * pulse, 1);
    auraSprite.visible = state.aura;
  }
  if (auraRing) {
    auraRing.material.opacity = visible ? amount * 0.22 : 0;
    auraRing.material.color.setHex(color);
    const s = 1 + 0.018 * Math.sin(performance.now() * 0.003) + amount * 0.03;
    auraRing.scale.set(s, s, s);
  }
  if (particles) {
    particles.material.opacity = visible ? amount * 0.62 : 0;
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
  const clips = currentClips();
  const phases = state.mode === 'idle'
    ? [{at: 0, label: '待机', note: '标准待机呼吸'}]
    : (clips.onset.presentation?.phases || [{at: 0, label: '正常', note: ''}]);
  phaseTotal = modeTotal();
  phaseSegments = phases.map((phase, i) => ({...phase,
    duration: Math.max(0.01, (phases[i + 1]?.at ?? phaseTotal) - phase.at)}));
  for (const segment of phaseSegments) {
    const bar = document.createElement('i');
    bar.style.flexGrow = String(segment.duration / phaseTotal);
    bar.title = segment.label;
    bar.addEventListener('click', () => { state.time = segment.at; updateTimeline(); });
    track.append(bar);
  }
}

function updateSubject() {
  const config = MODELS[state.model];
  const isRapem = (state.status === 'rapem' || state.status === 'act') && config.variantAvailable;
  const isRelief = state.status === 'relief';
  $('subject-tag').textContent = (config.id === 'alice' ? 'ALICE' : 'MARISA') + ' · ADULT CANDIDATE';
  $('subject-name').textContent = state.status === 'act' ? '雷普魔 · 素鸡立动作' : isRapem ? '雷普魔爱丽丝' : isRelief ? '素鸡立 · 完成' : '素鸡立 · 持续繁殖期';
  $('subject-sub').textContent = state.status === 'act' ? '接触后前倾贴身 · 2秒摩擦循环 · 三段完成'
    : isRapem ? '搜寻宣言 → 追击 → 中断 → 恢复'
    : isRelief ? '慢！ → 素——！ → 素鸡立～！ · 一次性弹动后回归待机'
    : '期内中性观察 · 8秒节律重复 · 不演示时期结束';
  $('variant-badge').hidden = !isRapem;
  $('state-hint').textContent = state.status === 'act'
    ? '接触后双方贴身锁位：攻方保持前倾并按2秒周期摩擦（对应1.12 attack_3/4台词节拍），器官在游戏内保持伸出；受方另体挣扎，不在此单体片段。'
    : isRapem
    ? '搜寻宣言（ゆふぅ……）→ 锁定与追击（受方喊れいぱーだー！？并逃跑）→ 中断 → 恢复；序列按1.12 label_2616..2624 还原。'
    : isRelief
    ? '受孕成功或结算成功后的一次性完成演出；三拍分别对应heat_relief_1/2/3原句，未受孕期不触发，期自然耗尽改播heat_unmet台词。'
    : '素鸡立按用户指代的发情期展示。这里只作持续期内的中性观察：轻微原地左右看，不抬身、不卸力、不演示期结束。';
  $('rapem-button').disabled = !config.variantAvailable;
  $('meter-label').textContent = '展示张力';
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
  const color = state.status === 'rapem' || state.status === 'act' ? 0xff5b48 : state.status === 'relief' ? 0xffd76b : 0xffa25f;
  if (auraSprite) auraSprite.material.color.setHex(color);
  if (auraRing) auraRing.material.color.setHex(color);
  if (particles) particles.material.color.setHex(color);
}

function bindUI() {
  $('model-buttons').addEventListener('click', event => {
    const button = event.target.closest('button[data-model]');
    if (!button || button.dataset.model === state.model) return;
    state.model = button.dataset.model;
    if (!MODELS[state.model].variantAvailable && (state.status === 'rapem' || state.status === 'act')) state.status = 'heat';
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
    if (!state.playing && state.time >= modeTotal()) state.time = 0;
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
