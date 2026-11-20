import {categories, entryURL, loadEntries} from './entries.js';
import {buildTimeline, emoteFrame, leakSteps, sampleTimeline, textureFace} from './timeline.mjs';
import {actorMesh, loadEmote, loadFloor, loadPaper, loadTexture, surfaceMarks, updateMesh} from './effects.js';
import {loadProp} from './props.js';
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
    if (!packed) return response.json();
    const bytes = await response.arrayBuffer();
    const mark = JSON.parse(response.headers.get('X-Pose-Surface-Mark'));
    const actorsEnd = mark?.offset ?? bytes.byteLength;
    const mainLength = payload.partner ? Number(response.headers.get('X-Pose-Partner-Offset')) : actorsEnd;
    const attachments = payload.held || payload.hat ? JSON.parse(response.headers.get('X-Pose-Attachments')) : {};
    return {vertices: new Float32Array(bytes, 0, mainLength / 4),
            partnerVertices: payload.partner ? new Float32Array(bytes, mainLength, (actorsEnd - mainLength) / 4) : undefined,
            partnerSpacing: payload.partner ? Number(response.headers.get('X-Pose-Partner-Spacing')) : undefined,
            surfaceMark: mark && {...mark, vertices: new Float32Array(bytes, mark.offset)},
            emote: decodeURIComponent(response.headers.get('X-Pose-Emote')), ...attachments};
}

function floatVertices(base64) {
    const raw = atob(base64);
    const bytes = new Uint8Array(raw.length);
    for (let i = 0; i < raw.length; i++) bytes[i] = raw.charCodeAt(i);
    return new Float32Array(bytes.buffer);
}

async function registerActor(modelId, scale) {
    const actor = await pose({op: 'register', modelId, scale});
    addEventListener('pagehide', event => {
        if (!event.persisted) fetch('/pose', {method: 'POST', keepalive: true,
            body: JSON.stringify({op: 'release', id: actor.id})});
    });
    return actor;
}

function actorMaterial() {
    return new THREE.MeshBasicMaterial({side: THREE.DoubleSide, vertexColors: true, alphaTest: .05});
}

function actorTexture(actor) {
    const textures = new Map();
    return face => {
        if (!textures.has(face)) textures.set(face, loadTexture(actor.textures[face]));
        return textures.get(face);
    };
}

function setMap(material, map) {
    if (!material.map) material.needsUpdate = true;
    material.map = map;
}

async function createPartner(scene, data, spec) {
    const model = data.models[spec.model];
    const scale = model.scale * spec.scale;
    const {id, clips} = await registerActor(spec.model, scale);
    const {track} = buildTimeline(spec, clips, data.momentClips);
    const material = actorMaterial();
    const placed = new THREE.Group();
    scene.add(placed);
    const mesh = actorMesh(placed, material);
    const texture = actorTexture(model);
    return {
        group: placed,
        expandBounds(bounds) {
            mesh.geometry.computeBoundingBox();
            return bounds.expandByObject(placed);
        },
        sample(seconds) {
            const sample = sampleTimeline(track, clips, seconds, 'auto');
            const face = textureFace(data, model, sample.face, seconds);
            const payload = {id, action: sample.clip, time: sample.time, intensity: sample.intensity, expression: face, scale};
            return {payload, map: texture(face)};
        },
        draw(vertices, spacing, map) {
            placed.position.x = spacing;
            updateMesh(mesh, vertices);
            setMap(material, map);
        }
    };
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
    const material = actorMaterial();
    const mesh = actorMesh(scene, material);

    const {id, clips, actor: proportions} = await registerActor(ident, actor.scale);
    const [held, hat, partner] = await Promise.all([entry.held && loadProp(scene, entry.held, pose),
        entry.hat && loadProp(scene, entry.hat, pose), entry.partner && createPartner(scene, data, entry.partner)]);
    const leak = entry.leak && await pose({op: 'leak', ...entry.leak, bodyWidth: proportions.width * actor.scale});
    const scratchStep = entry.steps?.find(step => step.scratch);
    const marks = (leak || scratchStep) && surfaceMarks(scene);
    let drawFloor, stream;
    if (leak) {
        const {puddleAt, width} = leak.beat;
        const after = entry.steps[2];
        const {outlet, surfaceMark} = await pose({op: 'render', id, action: after.clip, time: 0, intensity: after.intensity,
            expression: textureFace(data, actor, leak.faces[2], puddleAt / 20), scale: actor.scale,
            outlet: proportions.outlet, leakAge: 0, vertexFormat: 'float32le'});
        const floor = await pose({op: 'floorEffect', reason: 'excited_leak', amount: width, sourceId: 1,
            scale: actor.scale * proportions.modelScale, tick: puddleAt,
            position: {x: -outlet[0] / 16, y: outlet[1] / 16, z: -outlet[2] / 16 + leak.puddleReach},
            direction: {x: 0, y: 0, z: 1}});
        entry = {...entry, steps: leakSteps(entry.steps, leak, puddleAt + floor.lifetime)};
        drawFloor = await loadFloor(scene, floor);
        await marks.texture(surfaceMark.texture);
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
    if (scratchStep) {
        const impact = track.find(step => step.scratch);
        const sample = sampleTimeline(track, clips, impact.start, 'auto');
        const {surfaceMark} = await pose({op: 'render', id, action: sample.clip, time: sample.time,
            expression: textureFace(data, actor, sample.face, impact.start), scale: actor.scale,
            scratch: sample.scratch}, true);
        await marks.texture(surfaceMark.texture);
    }
    const cover = $('paper');
    const staged = entry.steps?.some(step => step.stance);
    $('paper-control').hidden = Boolean(staged);
    cover.checked = !staged && params.get('paper') === '1';
    let paper;
    const emoteHeight = proportions.emoteHeight * actor.scale * 16;
    const emoteSize = proportions.emoteSize * actor.scale * 16;
    const emote = await loadEmote(scene, emoteHeight, emoteSize);
    const mute = $('mute');
    const cue = entry.speech?.[proportions.speaker];
    const speech = cue && await loadSpeech(cue, proportions.voicePitch, mute);
    $('mute-control').hidden = !speech;
    const timeline = $('time');
    timeline.max = duration;
    const texture = actorTexture(actor);
    const focus = new THREE.Vector3();

    let azimuth = .45, elevation = .15;
    let expression = 'auto', time = 0, elapsed = 0, lastTime, previous = performance.now();
    let playing = false, pending = false, dirty = true, revision = 0;
    async function drawFrame(seconds, elapsed, version) {
        const sample = sampleTimeline(track, clips, seconds, expression);
        const face = textureFace(data, actor, sample.face, seconds);
        const tick = Math.floor(seconds * 20);
        const alpha = leak && (leak.traceOpacity[tick - leak.beat.puddleAt] ?? 0);
        const payload = {op: 'render', id, action: sample.clip, time: sample.time,
            intensity: sample.intensity, expression: face, scale: actor.scale,
            emote: emoteFrame(sample, elapsed, duration, leak)};
        const streamVisible = alpha > 0 && face !== 'leak_flow';
        const covered = cover.checked;
        if (covered) {
            payload.paperCover = proportions.paperAccessories;
            paper ??= loadPaper(scene);
        }
        if (streamVisible) payload.leakStream = true;
        if (leak) payload.leakAge = tick - leak.beat.puddleAt;
        if (sample.scratch) payload.scratch = sample.scratch;
        if (held) payload.held = {...entry.held, mouthBone: proportions.mouthBone};
        if (hat) payload.hat = {...hat.fit, accessories: proportions.paperAccessories,
            outline: covered ? hat.outline : undefined};
        const peer = partner && partner.sample(seconds);
        if (peer) payload.partner = peer.payload;
        if (sample.pairStage) payload.pairStage = sample.pairStage;
        const rich = streamVisible || covered;
        if (rich) payload.vertexFormat = 'float32le';
        const [frame, map, sheets, peerMap] = await Promise.all([pose(payload, !rich), texture(face), paper, peer?.map]);
        const mark = frame.surfaceMark;
        const markMap = mark && await marks.texture(mark.texture);
        if (version !== revision) return;
        updateMesh(mesh, rich ? floatVertices(frame.vertices) : frame.vertices);
        if (held) held.set(frame.held);
        if (hat) hat.set(frame.hat);
        if (partner) partner.draw(rich ? floatVertices(frame.partnerVertices) : frame.partnerVertices,
            frame.partnerSpacing, peerMap);
        if (staged) {
            mesh.geometry.computeBoundingBox();
            partner.expandBounds(new THREE.Box3().setFromObject(mesh)).getCenter(focus);
        }
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
        if (marks) marks.draw(mark, mark && (rich ? floatVertices(mark.vertices) : mark.vertices), markMap);
        if (drawFloor) drawFloor(seconds);
        setMap(material, map);
        emote.set(frame.emote);
        const caption = speech?.caption(elapsed) || sample.caption;
        $('caption').textContent = caption ?? '';
        $('caption').hidden = !caption;
        $('clock').textContent = seconds.toFixed(2) + ' / ' + duration.toFixed(2) + ' 秒';
        timeline.value = seconds;
        lastTime = seconds;
        dirty = true;
    }

    await drawFrame(0, 0, revision);
    const bounds = new THREE.Box3().setFromObject(mesh);
    for (const prop of [held, hat]) if (prop) bounds.expandByObject(prop.group);
    if (partner) bounds.expandByObject(partner.group);
    const top = Math.max(bounds.max.y, emoteHeight + emoteSize);
    const centerY = (bounds.min.y + top) / 2;
    bounds.getCenter(focus);
    const span = Math.max((top - bounds.min.y) * .77, 4);
    const radius = Math.hypot(bounds.max.x - focus.x, bounds.max.z - focus.z);
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
        const sampleTime = partner ? Math.floor(time * 20) / 20 : time;
        if (!pending && sampleTime !== lastTime) {
            pending = true;
            drawFrame(sampleTime, elapsed, revision).then(() => { pending = false; }).catch(error => {
                setPlaying(false);
                $('play').disabled = timeline.disabled = true;
                $('status').textContent = '姿态读取失败。' + error.message;
            });
        }
        if (dirty) {
            camera.position.set(focus.x - 150 * Math.sin(azimuth) * Math.cos(elevation),
                centerY + 150 * Math.sin(elevation), focus.z - 150 * Math.cos(azimuth) * Math.cos(elevation));
            camera.lookAt(focus.x, centerY, focus.z);
            emote.face(camera);
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
    const entry = data.entries.find(item => item.id === params.get('entry'));
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
