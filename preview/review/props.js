import {loadTexture, updateMesh} from './effects.js';

export async function loadProp(scene, spec, pose) {
    const {meshes, fit, outline} = await pose({op: 'prop', ...spec});
    const textures = new Map();
    const group = new THREE.Group();
    group.matrixAutoUpdate = false;
    await Promise.all(meshes.map(async ({texture, tint, translucent, vertices, colors}) => {
        if (!textures.has(texture)) textures.set(texture, loadTexture('/src/main/resources/assets/' + texture));
        const material = new THREE.MeshBasicMaterial({
            map: await textures.get(texture), side: THREE.DoubleSide, vertexColors: true,
            alphaTest: translucent ? 0 : .05, transparent: Boolean(translucent)
        });
        if (tint) material.color.setRGB(...tint.map(channel => channel / 255));
        const mesh = new THREE.Mesh(new THREE.BufferGeometry(), material);
        updateMesh(mesh, vertices);
        if (colors) {
            const color = mesh.geometry.attributes.color;
            colors.forEach((rgb, i) => {
                const shade = color.getX(i) / 255;
                color.setXYZ(i, (rgb >> 16 & 255) * shade, (rgb >> 8 & 255) * shade, (rgb & 255) * shade);
            });
        }
        group.add(mesh);
    }));
    scene.add(group);
    const mirror = new THREE.Matrix4().makeScale(-1, 1, 1);
    return {
        group, fit, outline,
        set(rows) {
            group.matrix.set(...rows.flat(), 0, 0, 0, 1).premultiply(mirror);
            group.matrixWorldNeedsUpdate = true;
        }
    };
}
