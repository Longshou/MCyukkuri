import {categories, entryURL, loadEntries} from './entries.js';
import {buildTimeline, leakSteps, sampleTimeline, selectEmote, textureFace} from './timeline.mjs';
import {actorMesh, loadEmote, loadFloor, loadPaper, loadTexture, updateMesh} from './effects.js';
import {loadSpeech} from './speech.js';
import {mountVerdicts} from './verdicts.js';

const $ = id => document.getElementById(id);
const params = new URLSearchParams(location.search);

async function pose(payload, packed = false) {
    const response = await fetch('/pose', {
        method: 'POST',
        headers: {'Content-Type': 'application/json',
                  Accept: packed ? 'application/octet-stream' : 'application/json'},
        body: JSON.stringify(payload)
    });
    if (!response.ok) throw new Error((await response.json()).error ?? 'HTTP ' + response.status);
    return packed ? new Float32Array(await response.arrayBuffer()) : response.json();
}

function floatVertices(base64) {
    const raw = atob(base64);
    const bytes = new Uint8Array(raw.length);
    for (let i = 0; i < raw.length; i++) bytes[i] = raw.charCodeAt(i);
    return new Float32Array(bytes.buffer);
}

function media(entry) {
    const target = $('media');
    if (entry.audio) {
        const audio = document.createElement('audio');
        audio.controls = true;
        audio.preload = 'metadata';
        audio.src = entry.audio;
        target.append(audio);
    } else if (entry.image) {
        const image = document.createElement('img');
        image.src = entry.image;
        image.alt = entry.title;
        target.append(image);
    }
    const text = entry.summary ?? entry.japanese;
    if (text) {
        const paragraph = document.createElement('p');
        paragraph.textContent = text;
        target.append(paragraph);
    }
    target.hidden = false;
}

function showVariants(data, entry, ident) {
    const choices = entry.steps
        ? (entry.variants ?? []).map(model => [model, entry.id])
        : data.entries.filter(row => row.category === entry.category && row.clip === entry.clip)
            .map(row => [row.model, row.id]);
    if (choices.length < 2) return;
    const select = $('variant');
    for (const [model] of choices) select.add(new Option(data.models[model].name, model));
    select.value = ident;
    select.onchange = () => {
        const [model, id] = choices.find(([candidate]) => candidate === select.value);
        const url = new URL(entryURL(id, entry.steps ? model : undefined), location.origin);
        if ($('paper').checked) url.searchParams.set('paper', '1');
        location.assign(url);
    };
    $('variant-control').hidden = false;
}

async function playEntry(data, entry) {
    const requested = params.get('model');
    const ident = entry.model ?? (entry.variants.includes(requested) ? requested : entry.variants[0]);
    const actor = data.models[ident];
    showVariants(data, entry, ident);
    $('preview').hidden = false;
    $('status').textContent = '正在载入三维姿态…';

    const canvas = $('view');
    const renderer = new THREE.WebGLRenderer({canvas, antialias: true});
    renderer.setPixelRatio(Math.min(devicePixelRatio, 2));
    renderer.setClearColor(0xe7eee8);
    const scene = new THREE.Scene();
    const camera = new THREE.OrthographicCamera(-1, 1, 1, -1, .1, 1000);
    const grid = new THREE.GridHelper(96, 12, 0x93a99a, 0xc5d5c9);
    grid.position.y = -.05;
    scene.add(grid);
    const ground = new THREE.Mesh(new THREE.PlaneGeometry(96, 96),
        new THREE.MeshBasicMaterial({color: 0xe7eee8}));
    ground.rotation.x = -Math.PI / 2;
    ground.position.y = -.06;
    scene.add(ground);
    const material = new THREE.MeshBasicMaterial({side: THREE.DoubleSide, vertexColors: true, alphaTest: .05});
    const mesh = actorMesh(scene, material);

    const {id, clips, actor: proportions} = await pose({op: 'register', modelId: ident, scale: actor.scale});
    addEventListener('pagehide', event => {
        if (!event.persisted) fetch('/pose', {method: 'POST', keepalive: true,
            body: JSON.stringify({op: 'release', id})});
    });
    const leak = entry.leak && await pose({op: 'leak', ...entry.leak, bodyWidth: proportions.width * actor.scale});
    let drawFloor, stream;
    if (leak) {
        const {puddleAt, width} = leak.beat;
        const after = entry.steps[2];
        const {outlet} = await pose({op: 'render', id, action: after.clip, time: 0, intensity: after.intensity,
            expression: textureFace(data, actor, leak.faces[2], puddleAt / 20), scale: actor.scale,
            outlet: proportions.outlet, vertexFormat: 'float32le'});
        const floor = await pose({op: 'floorEffect', reason: 'excited_leak', amount: width, sourceId: 1,
            scale: actor.scale * proportions.modelScale, tick: puddleAt,
            position: {x: -outlet[0] / 16, y: outlet[1] / 16, z: -outlet[2] / 16 + leak.puddleReach},
            direction: {x: 0, y: 0, z: 1}});
        entry = {...entry, steps: leakSteps(entry.steps, leak, puddleAt + floor.lifetime)};
        drawFloor = await loadFloor(scene, floor);
        stream = actorMesh(scene, new THREE.MeshBasicMaterial({map: await loadTexture(actor.textures.leak_flow),
            side: THREE.DoubleSide, transparent: true, depthWrite: false}));
    } else if (entry.filling) {
        const [x, y, z] = proportions.mouth.map(value => value * actor.scale / 16);
        const floor = await pose({op: 'floorEffect', reason: 'vomit', amount: 1, sourceId: 1,
            scale: actor.scale * proportions.modelScale, tick: 0, position: {x: -x, y, z: -z},
            direction: {x: 0, y: 0, z: 1}});
        const actionEnd = buildTimeline(entry, clips, data.momentClips).duration;
        const tracesEnd = Math.max(...floor.traces.map(trace => trace.until)) / 20;
        if (tracesEnd > actionEnd) {
            const idle = {clip: 'idle', faces: [], tempo: 1, duration: tracesEnd - actionEnd};
            entry = {...entry, steps: [...entry.steps, idle]};
        }
        drawFloor = await loadFloor(scene, floor);
    }
    const {track, duration, loop} = buildTimeline(entry, clips, data.momentClips);
    const cover = $('paper');
    cover.checked = params.get('paper') === '1';
    let paper;
    const emoteHeight = proportions.emoteHeight * actor.scale * 16;
    const emoteSize = proportions.emoteSize * actor.scale * 16;
    const emote = entry.steps && await loadEmote(scene, emoteHeight, emoteSize);
    const mute = $('mute');
    const cue = entry.speech?.[proportions.speaker];
    const speech = cue && await loadSpeech(cue, proportions.voicePitch, mute);
    $('mute-control').hidden = !speech;
    const timeline = $('time');
    timeline.max = duration;
    const textures = new Map();

    function texture(face) {
        if (!textures.has(face)) {
            textures.set(face, loadTexture(actor.textures[face]));
        }
        return textures.get(face);
    }

    let azimuth = .45, elevation = .15;
    let expression = 'auto', time = 0, elapsed = 0, lastTime, previous = performance.now();
    let playing = false, pending = false, dirty = true, revision = 0;
    async function drawFrame(seconds, elapsed, version) {
        const sample = sampleTimeline(track, clips, seconds, expression);
        const face = textureFace(data, actor, sample.face, seconds);
        const tick = Math.floor(seconds * 20);
        const alpha = leak && (leak.traceOpacity[tick - leak.beat.puddleAt] ?? 0);
        const payload = {op: 'render', id, action: sample.clip, time: sample.time,
            intensity: sample.intensity, expression: face, scale: actor.scale};
        const streamVisible = alpha > 0 && face !== 'leak_flow';
        const covered = cover.checked;
        if (covered) {
            payload.paperCover = proportions.paperAccessories;
            paper ??= loadPaper(scene);
        }
        if (streamVisible) payload.leakStream = true;
        const rich = streamVisible || covered;
        if (rich) payload.vertexFormat = 'float32le';
        const [frame, map, sheets] = await Promise.all([pose(payload, !rich), texture(face), paper]);
        if (version !== revision) return;
        updateMesh(mesh, rich ? floatVertices(frame.vertices) : frame);
        if (sheets) {
            for (const [index, field] of ['paper_top', 'paper_under'].entries()) {
                const sheet = sheets[index];
                sheet.visible = covered && Boolean(frame[field]);
                if (sheet.visible) updateMesh(sheet, floatVertices(frame[field]));
            }
        }
        if (stream) {
            stream.visible = streamVisible;
            if (streamVisible) {
                updateMesh(stream, floatVertices(frame.leak_stream), false);
                stream.material.opacity = alpha / 255;
            }
        }
        if (drawFloor) drawFloor(seconds);
        if (!material.map) material.needsUpdate = true;
        material.map = map;
        const sweat = leak && tick >= leak.beat.puddleAt && tick < leak.sweatUntil;
        if (emote) emote.set(selectEmote(sample, elapsed, duration, proportions.emoteSeconds, sweat));
        const caption = speech?.caption(elapsed) || sample.caption;
        $('caption').textContent = caption ?? '';
        $('caption').hidden = !caption;
        $('clock').textContent = seconds.toFixed(2) + ' / ' + duration.toFixed(2) + ' 秒';
        timeline.value = seconds;
        lastTime = seconds;
        dirty = true;
    }

    await drawFrame(0, 0, revision);
    const bounds = new THREE.Box3().setFromBufferAttribute(mesh.geometry.attributes.position);
    const top = emote ? Math.max(bounds.max.y, emoteHeight + emoteSize) : bounds.max.y;
    const centerY = (bounds.min.y + top) / 2;
    const span = Math.max((top - bounds.min.y) * .77, 4);
    const radius = Math.hypot(Math.max(-bounds.min.x, bounds.max.x), Math.max(-bounds.min.z, bounds.max.z));
    const zoom = $('zoom');
    function resize() {
        const width = canvas.clientWidth, height = canvas.clientHeight;
        renderer.setSize(width, height, false);
        const halfHeight = Math.max(span, radius * height / width) / Number(zoom.value);
        camera.top = halfHeight;
        camera.bottom = -halfHeight;
        camera.right = halfHeight * width / height;
        camera.left = -camera.right;
        camera.updateProjectionMatrix();
        dirty = true;
    }
    new ResizeObserver(resize).observe($('stage'));
    zoom.oninput = resize;
    canvas.addEventListener('wheel', event => {
        event.preventDefault();
        zoom.value = Number(zoom.value) * Math.exp(-event.deltaY * .001);
        resize();
    }, {passive: false});
    for (const button of document.querySelectorAll('[data-angle]')) {
        button.onclick = () => {
            azimuth = Number(button.dataset.angle);
            elevation = .1;
            dirty = true;
        };
    }
    let drag;
    canvas.onpointerdown = event => {
        drag = [event.clientX, event.clientY];
        canvas.setPointerCapture(event.pointerId);
    };
    canvas.onpointermove = event => {
        if (!drag) return;
        azimuth += (event.clientX - drag[0]) * .009;
        elevation = Math.max(-.3, Math.min(1.4, elevation + (event.clientY - drag[1]) * .009));
        drag = [event.clientX, event.clientY];
        dirty = true;
    };
    canvas.onlostpointercapture = () => { drag = undefined; };

    function setPlaying(value) {
        playing = value;
        if (!value) speech?.sync(elapsed, false, 1);
        $('play').textContent = value ? '暂停' : '播放';
    }
    function seek(seconds, sinceStart = seconds) {
        revision++;
        lastTime = undefined;
        time = seconds;
        elapsed = sinceStart;
    }
    $('play').onclick = () => {
        if (time >= duration && !playing) seek(0);
        if (!playing && !mute.checked) speech?.enable();
        setPlaying(!playing);
    };
    mute.onchange = () => {
        if (!mute.checked) speech.enable();
    };
    timeline.oninput = () => {
        setPlaying(false);
        seek(Number(timeline.value));
    };
    cover.onchange = () => seek(time, elapsed);
    if (entry.category === 'models') {
        const select = $('face');
        select.add(new Option('随动作', 'auto'));
        for (const face of actor.expressions) select.add(new Option(data.faceLabels[face] ?? face, face));
        select.onchange = () => {
            expression = select.value;
            seek(time);
        };
        $('face-control').hidden = false;
    }

    const speed = $('speed');
    function animate(now) {
        const dt = (now - previous) / 1000;
        previous = now;
        if (playing) {
            const delta = dt * Number(speed.value);
            time += delta;
            elapsed += delta;
            if (time >= duration) {
                time = loop ? time % duration : duration;
                if (!loop) setPlaying(false);
            }
        }
        speech?.sync(elapsed, playing, Number(speed.value));
        if (!pending && time !== lastTime) {
            pending = true;
            drawFrame(time, elapsed, revision).then(() => { pending = false; }).catch(error => {
                setPlaying(false);
                $('play').disabled = timeline.disabled = true;
                $('status').textContent = '姿态读取失败。' + error.message;
            });
        }
        if (dirty) {
            camera.position.set(-150 * Math.sin(azimuth) * Math.cos(elevation),
                centerY + 150 * Math.sin(elevation), -150 * Math.cos(azimuth) * Math.cos(elevation));
            camera.lookAt(0, centerY, 0);
            if (emote) emote.face(camera);
            renderer.render(scene, camera);
            dirty = false;
        }
        requestAnimationFrame(animate);
    }
    document.addEventListener('visibilitychange', () => {
        if (document.hidden) setPlaying(false);
    });
    $('status').textContent = '';
    $('play').disabled = timeline.disabled = false;
    setPlaying(params.get('autoplay') === '1');
    resize();
    previous = performance.now();
    requestAnimationFrame(animate);
}

loadEntries().then(async data => {
    const moment = params.get('moment');
    let entry = data.entries.find(item => item.id === params.get('entry'));
    if (moment) {
        const response = await fetch('/src/main/resources/assets/mcyukkuri/yukkuri_presentation/moments.json');
        if (!response.ok) throw new Error('HTTP ' + response.status);
        const row = (await response.json()).moments[moment];
        const model = params.get('model');
        if (row && data.models[model]) entry = {...row, moment, model, title: moment, category: 'performances'};
    }
    if (!entry) throw new Error('未找到这个条目。');
    $('title').textContent = entry.title;
    document.title = entry.title + ' · 演出放映室';
    $('home').href = './index.html?category=' + entry.category;
    const detail = entry.speaker ? entry.speaker + ' · ' + entry.detail
        : entry.detail ?? (entry.model && data.models[entry.model].name) ?? categories[entry.category];
    $('detail').textContent = detail;
    $('detail').hidden = false;
    if (entry.clip || entry.steps) await playEntry(data, entry);
    else media(entry);
    if (entry.tag === 'look-only') {
        const verdict = $('verdict');
        verdict.dataset.verdictId = entry.id;
        verdict.hidden = false;
        await mountVerdicts([verdict]);
    }
}).catch(error => {
    $('status').textContent = error.message;
});
