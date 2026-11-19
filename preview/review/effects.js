const assets = '/src/main/resources/assets/mcyukkuri/';

export function loadTexture(url) {
    return new THREE.TextureLoader().loadAsync(url).then(texture => {
        texture.flipY = false;
        texture.magFilter = texture.minFilter = THREE.NearestFilter;
        texture.generateMipmaps = false;
        texture.needsUpdate = true;
        return texture;
    });
}

export async function loadEmote(scene, height, size) {
    const path = assets + 'textures/emote/';
    const [atlas, {columns, rows, emotes}] = await Promise.all([
        loadTexture(path + 'atlas.png'), fetch(path + 'emotes.json').then(response => response.json())
    ]);
    const cells = new Map(emotes.map(({id, cell}) => [id, cell]));
    const geometry = new THREE.PlaneGeometry(size, size).translate(0, size / 2, 0);
    const material = new THREE.MeshBasicMaterial({map: atlas, transparent: true,
        depthWrite: false, alphaTest: 1 / 255, side: THREE.DoubleSide});
    const faint = material.clone();
    faint.depthTest = false;
    faint.opacity = 64 / 255;
    const symbol = new THREE.Group();
    symbol.position.y = height;
    const occluded = new THREE.Mesh(geometry, faint);
    const visible = new THREE.Mesh(geometry, material);
    occluded.renderOrder = 2;
    visible.renderOrder = 3;
    symbol.add(occluded, visible);
    scene.add(symbol);
    return {
        set(id) {
            symbol.visible = Boolean(id);
            if (!id) return;
            const [x, y] = cells.get(id);
            const uv = geometry.attributes.uv;
            uv.setXY(0, x / columns, y / rows);
            uv.setXY(1, (x + 1) / columns, y / rows);
            uv.setXY(2, x / columns, (y + 1) / rows);
            uv.setXY(3, (x + 1) / columns, (y + 1) / rows);
            uv.needsUpdate = true;
        },
        face(camera) { symbol.quaternion.copy(camera.quaternion); }
    };
}

export function updateMesh(mesh, vertices, lit = true) {
    const {geometry} = mesh;
    const count = vertices.length / 8;
    for (const [name, size] of [['position', 3], ['uv', 2], ['color', 3]]) {
        if (geometry.attributes[name]?.count !== count) {
            geometry.setAttribute(name, new THREE.BufferAttribute(new Float32Array(count * size), size)
                .setUsage(THREE.DynamicDrawUsage));
        }
    }
    const {position, uv, color} = geometry.attributes;
    for (let i = 0, v = 0; i < vertices.length; i += 8, v++) {
        position.setXYZ(v, vertices[i], vertices[i + 1], vertices[i + 2]);
        uv.setXY(v, vertices[i + 3], vertices[i + 4]);
        const shade = lit ? .78 + .22 * Math.max(0,
            -.35 * vertices[i + 5] + .8 * vertices[i + 6] - .48 * vertices[i + 7]) : 1;
        color.setXYZ(v, shade, shade, shade);
    }
    position.needsUpdate = uv.needsUpdate = color.needsUpdate = true;
}

export function actorMesh(scene, material) {
    const mesh = new THREE.Mesh(new THREE.BufferGeometry(), material);
    mesh.scale.x = -1;
    mesh.frustumCulled = false;
    scene.add(mesh);
    return mesh;
}

function floorMesh(rim, texture, trace, yaw) {
    const points = [], uvs = [];
    for (let i = 0; i < rim.length - 1; i++) {
        for (const [x, z] of [[0, 0], rim[i], rim[i + 1]]) {
            points.push(x * trace.halfX * 16, 0, -z * trace.halfZ * 16);
            uvs.push(.5 + .5 * x, .5 + .5 * z);
        }
    }
    const geometry = new THREE.BufferGeometry();
    geometry.setAttribute('position', new THREE.Float32BufferAttribute(points, 3));
    geometry.setAttribute('uv', new THREE.Float32BufferAttribute(uvs, 2));
    const mesh = new THREE.Mesh(geometry, new THREE.MeshBasicMaterial({
        map: texture, side: THREE.DoubleSide, transparent: true, depthWrite: false
    }));
    const {x, y, z} = trace.position;
    mesh.position.set(16 * x, 16 * y, -16 * z);
    mesh.rotation.y = -yaw;
    mesh.renderOrder = 1;
    return mesh;
}

export async function loadFloor(scene, effect) {
    const texture = await loadTexture(assets + effect.texture);
    const traces = effect.traces.map(trace => {
        const mesh = floorMesh(effect.rim, texture, trace, effect.yaw);
        scene.add(mesh);
        return {trace, mesh};
    });
    const flights = [];
    if (effect.flights.length) {
        const response = await fetch(assets + 'yukkuri_props/leaked_filling.json');
        if (!response.ok) throw new Error('HTTP ' + response.status);
        const {parts, vertices} = await response.json();
        const lump = parts.find(part => part.unit === 1);
        const material = new THREE.MeshBasicMaterial({
            map: texture, side: THREE.DoubleSide, vertexColors: true, alphaTest: .05
        });
        for (const {path, size} of effect.flights) {
            const mesh = new THREE.Mesh(new THREE.BufferGeometry(), material);
            updateMesh(mesh, vertices.slice(lump.start * 8, (lump.start + lump.count) * 8));
            mesh.scale.set(size, size, -size);
            scene.add(mesh);
            flights.push({path, mesh});
        }
    }
    return seconds => {
        const tick = Math.floor(seconds * 20);
        for (const {trace, mesh} of traces) {
            mesh.visible = tick >= trace.tick && tick < trace.until;
            mesh.material.opacity = Math.min(1, (trace.tick + effect.lifetime - tick) / effect.fadeTicks);
        }
        const age = tick - effect.tick;
        for (const {path, mesh} of flights) {
            mesh.visible = age >= 0 && age < path.length - 1;
            if (mesh.visible) {
                const {x, y, z} = path[age];
                mesh.position.set(16 * x, 16 * y, -16 * z);
            }
        }
    };
}

export async function loadPaper(scene) {
    const path = assets + 'textures/block/newspaper_stack_';
    return Promise.all([['top', 0xffffff], ['fold', 0xd8d4ca]].map(async ([name, color]) => {
        const material = new THREE.MeshBasicMaterial({map: await loadTexture(path + name + '.png'),
            vertexColors: true, alphaTest: .05, side: THREE.DoubleSide, color});
        const mesh = actorMesh(scene, material);
        mesh.visible = false;
        return mesh;
    }));
}
