import {categories, entryURL, loadEntries} from './entries.js';
import {buildTimeline, sampleTimeline, textureFace} from './timeline.mjs';
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
        ? entry.variants.map(model => [model, entry.id])
        : data.entries.filter(row => row.category === entry.category && row.clip === entry.clip)
            .map(row => [row.model, row.id]);
    if (choices.length < 2) return;
    const select = $('variant');
    for (const [model] of choices) select.add(new Option(data.models[model].name, model));
    select.value = ident;
    select.onchange = () => {
        const [model, id] = choices.find(([candidate]) => candidate === select.value);
        location.assign(entryURL(id, entry.steps ? model : undefined));
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
    const material = new THREE.MeshBasicMaterial({side: THREE.DoubleSide, vertexColors: true, alphaTest: .05});

    const {id, clips} = await pose({op: 'register', modelId: ident});
    addEventListener('pagehide', event => {
        if (!event.persisted) fetch('/pose', {method: 'POST', keepalive: true,
            body: JSON.stringify({op: 'release', id})});
    });
    const {track, duration, loop} = buildTimeline(entry, clips);
    const timeline = $('time');
    timeline.max = duration;
    $('caption').hidden = !entry.steps;
    const textures = new Map();

    function texture(face) {
        if (!textures.has(face)) {
            textures.set(face, new THREE.TextureLoader().loadAsync(actor.textures[face]).then(image => {
                image.flipY = false;
                image.magFilter = THREE.NearestFilter;
                image.minFilter = THREE.NearestFilter;
                image.generateMipmaps = false;
                image.needsUpdate = true;
                return image;
            }));
        }
        return textures.get(face);
    }

    let azimuth = .45, elevation = .15;
    let expression = 'auto', time = 0, lastTime, previous = performance.now();
    let playing = false, pending = false, dirty = true, revision = 0;
    const geometry = new THREE.BufferGeometry();

    async function drawFrame(seconds, version) {
        const sample = sampleTimeline(track, clips, seconds, expression);
        const face = textureFace(data, actor, sample.face, seconds);
        const [vertices, map] = await Promise.all([
            pose({op: 'render', id, action: sample.clip, time: sample.time,
                  intensity: sample.intensity, expression: face, scale: actor.scale}, true),
            texture(face)
        ]);
        if (version !== revision) return;
        if (!geometry.hasAttribute('position')) {
            const count = vertices.length / 8;
            for (const [name, size] of [['position', 3], ['uv', 2], ['color', 3]]) {
                geometry.setAttribute(name, new THREE.BufferAttribute(new Float32Array(count * size), size)
                    .setUsage(THREE.DynamicDrawUsage));
            }
        }
        const {position, uv, color} = geometry.attributes;
        for (let i = 0, v = 0; i < vertices.length; i += 8, v++) {
            position.setXYZ(v, vertices[i], vertices[i + 1], vertices[i + 2]);
            uv.setXY(v, vertices[i + 3], vertices[i + 4]);
            const shade = .78 + .22 * Math.max(0, -.35 * vertices[i + 5] + .8 * vertices[i + 6] - .48 * vertices[i + 7]);
            color.setXYZ(v, shade, shade, shade);
        }
        position.needsUpdate = uv.needsUpdate = color.needsUpdate = true;
        if (!material.map) material.needsUpdate = true;
        material.map = map;
        if (sample.caption) $('caption').textContent = sample.caption;
        $('clock').textContent = seconds.toFixed(2) + ' / ' + duration.toFixed(2) + ' 秒';
        timeline.value = seconds;
        lastTime = seconds;
        dirty = true;
    }

    await drawFrame(0, revision);
    const mesh = new THREE.Mesh(geometry, material);
    mesh.scale.x = -1;
    mesh.frustumCulled = false;
    scene.add(mesh);
    const bounds = new THREE.Box3().setFromBufferAttribute(geometry.attributes.position);
    const centerY = (bounds.min.y + bounds.max.y) / 2;
    const span = Math.max((bounds.max.y - bounds.min.y) * .77, 4);
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
        $('play').textContent = value ? '暂停' : '播放';
    }
    function seek(seconds) {
        revision++;
        lastTime = undefined;
        time = seconds;
    }
    $('play').onclick = () => {
        if (time >= duration && !playing) seek(0);
        setPlaying(!playing);
    };
    timeline.oninput = () => {
        setPlaying(false);
        seek(Number(timeline.value));
    };
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
            time += dt * Number(speed.value);
            if (time >= duration) {
                time = loop ? time % duration : duration;
                if (!loop) setPlaying(false);
            }
        }
        if (!pending && time !== lastTime) {
            pending = true;
            drawFrame(time, revision).then(() => { pending = false; }).catch(error => {
                setPlaying(false);
                $('play').disabled = timeline.disabled = true;
                $('status').textContent = '姿态读取失败。' + error.message;
            });
        }
        if (dirty) {
            camera.position.set(-150 * Math.sin(azimuth) * Math.cos(elevation),
                centerY + 150 * Math.sin(elevation), -150 * Math.cos(azimuth) * Math.cos(elevation));
            camera.lookAt(0, centerY, 0);
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
