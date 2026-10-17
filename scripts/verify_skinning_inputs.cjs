/* Offline geometry check for SkinningCache. No compilation, browser or game startup.
 * Samples the existing independent preview poser with the current fitted meshes and motions.
 * Checks that corners grouped by the Java cache inputs have identical posed positions, while
 * leaving every original triangle, UV and normal in place. This is not an FPS benchmark or
 * execution of the Java renderer; remote verifySkinningCache covers the Java cache lifecycle.
 * Output: .local/reports/skinning-cache/inputs.json (untracked).
 */
'use strict';
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const {RuntimePose, puffAmount} = require('../preview/review/pose.js');
const root = path.resolve(__dirname, '..');
const assets = path.join(root, 'src/main/resources/assets/mcyukkuri');
const read = name => JSON.parse(fs.readFileSync(path.join(assets, name), 'utf8'));
const fits = read('yukkuri_presentation/placement_fit.json').models;
const revisions = read('yukkuri_presentation/motion_corrections.json').models;
const house = read('yukkuri_presentation/house_ecology.json').models;
const report = {kind: 'offline input-equivalence, not measured frame time', models: {}};
const bodySurface = name => name.startsWith('soft_head_slice_') ||
    ['house_body_surface', 'predator_body_surface', 'deficient_body_surface', 'rounded_body_surface'].includes(name);
const hairHidden = (part, loss, vertices) => {
    const name = part.name;
    if (!loss || !(name.includes('hair') || name.includes('bang') || name.includes('braid') || name.startsWith('wasa_'))) return false;
    if (loss & 4) return true;
    if (name.includes('bang') || name.includes('crown') || name.includes('wrap') || name.startsWith('wasa_scalp')) return false;
    let x = 0;
    for (let i = part.start; i < part.start + part.count; i++) x = Math.fround(x + vertices[i * 8]);
    return (loss & (x < 0 ? 1 : 2)) !== 0;
};
for (const file of fs.readdirSync(path.join(assets, 'yukkuri')).filter(n => n.endsWith('.json') && n !== 'badge_mounts.json')) {
    const raw = read('yukkuri/' + file), id = raw.id;
    const pose = new RuntimePose(raw, 1, revisions[id], fits[id]);
    if (house[id]) Object.assign(pose.clips, house[id].clips);
    const model = pose.model, count = model.vertices.length / 8;
    // The Java loader stores vertex/weight/morph inputs as floats.
    model.vertices = model.vertices.map(Math.fround);
    model.weights = model.weights.map(w => w.map(Math.fround));
    pose.puffOffsets = Float64Array.from(pose.puffOffsets, Math.fround);
    const changing = new Uint8Array(count), features = new Int32Array(count).fill(-1);
    for (const patches of Object.values(model.expression_patches || {})) for (const patch of patches) {
        const part = model.parts[patch.index];
        changing.fill(1, part.start, part.start + part.count);
    }
    for (const part of model.parts) if (part.feature_bone !== undefined)
        features.fill(part.feature_bone, part.start, part.start + part.count);
    const allParts = [...model.parts, ...pose.puffAttachments.values(), ...(pose.puffSurface ? [pose.puffSurface] : [])];
    const hairMasks = new Map();
    for (const part of allParts) {
        if (changing.subarray(part.start, part.start + part.count).some(Boolean)) continue;
        hairMasks.set(part, [1, 2, 4].reduce((mask, loss) => mask | (hairHidden(part, loss, model.vertices) ? loss : 0), 0));
    }
    const slots = new Int32Array(count), keys = new Map();
    let slotCount = 0;
    const number = n => Object.is(n, -0) ? '-0' : String(n);
    for (let i = 0; i < count; i++) {
        if (changing[i]) { slots[i] = slotCount++; continue; }
        const key = [...model.vertices.slice(i * 8, i * 8 + 3), ...model.weights[i], features[i],
            ...pose.puffOffsets.slice(i * 3, i * 3 + 3)].map(number).join(',');
        if (!keys.has(key)) keys.set(key, slotCount++);
        slots[i] = keys.get(key);
    }
    const bodyCorners = [], bodyUnique = [], selectedBodySlots = new Set();
    for (const part of model.parts.filter(p => bodySurface(p.name))) {
        for (let i = part.start; i < part.start + part.count; i++) {
            bodyCorners.push(i);
            if (!selectedBodySlots.has(slots[i])) {
                selectedBodySlots.add(slots[i]);
                bodyUnique.push(i);
            }
        }
    }
    let bodyFloorSamples = 0;
    const verifyFloor = (output, label) => {
        const minimum = indices => indices.reduce((low, i) => Math.min(low, output[i * 8 + 1]), Infinity);
        assert.equal(minimum(bodyUnique), minimum(bodyCorners), `${id}/${label}: deduplication changed body floor`);
        bodyFloorSamples++;
    };
    let frames = 0, corners = 0, reused = 0;
    const workloads = {};
    const actions = ['idle', 'waddle', 'hop', 'puff', 'labor', 'midwife', 'boat_labor', 'hat_remove', 'feed_child'];
    for (const action of actions.filter(a => pose.clips[a])) for (const phase of [.17, .57]) {
        const time = pose.clips[action].duration * phase;
        pose.expression(pose.expressionFor(action, time));
        pose.src = Float64Array.from(pose.src, Math.fround);
        for (const part of allParts) for (let loss = 0; loss < 8; loss++) {
            const hidden = hairMasks.has(part) ? (hairMasks.get(part) & loss) !== 0 : hairHidden(part, loss, pose.src);
            assert.equal(hidden, hairHidden(part, loss, pose.src), `${id}/${action}/${part.name}: changed hair visibility for ${loss}`);
        }
        const output = pose.frame(action, time), drawn = new Map();
        const puff = puffAmount(pose.clips[action], time);
        // The preview poser replaces the original body during puff; the Java contact solver
        // intentionally samples the original body. Compare only poses with the same surface.
        if (puff === 0) verifyFloor(output, action);
        let frameCorners = 0, frameReused = 0;
        for (let pi = 0; pi < model.parts.length; pi++) {
            const part = puff > 0 ? (pose.puffSurface?.part_index === pi ? pose.puffSurface : pose.puffAttachments.get(pi) || model.parts[pi]) : model.parts[pi];
            if (part.anatomy && !action.startsWith('anatomy_' + part.anatomy + '_')) continue;
            for (let first = part.start; first < part.start + part.count; first += 3) {
                if (model.house && part.name.startsWith('face_') && pose.src[first * 8 + 7] >= -.15) continue;
                for (let i = first; i < first + 3; i++) {
                    const point = Array.from(output.slice(i * 8, i * 8 + 3));
                    if (drawn.has(slots[i])) {
                        const previous = drawn.get(slots[i]);
                        for (let c = 0; c < 3; c++) assert.ok(Math.abs(point[c] - previous[c]) < 1e-10,
                            `${id}/${action}: cache aliases different posed points at ${i}`);
                        frameReused++;
                    } else drawn.set(slots[i], point);
                    frameCorners++;
                }
            }
        }
        frames++; corners += frameCorners; reused += frameReused;
        if (phase === .57) workloads[action] = {corners: frameCorners, uniqueTransforms: drawn.size,
            reusedPercent: +(frameReused * 100 / frameCorners).toFixed(1)};
    }
    for (const action of ['feed_child', 'first_toilet_assist', 'boat_feed_young'].filter(a => pose.clips[a])) {
        const time = pose.clips[action].duration * .57;
        if (puffAmount(pose.clips[action], time) !== 0) continue;
        pose.expression(pose.expressionFor(action, time));
        pose.src = Float64Array.from(pose.src, Math.fround);
        // Candidate poses must each recompute the minimum; no result survives a bone change.
        for (const pitch of [0, -5, -10, -28, 0])
            verifyFloor(pose.frame(action, time, {pitch}), `${action}/pitch=${pitch}`);
    }
    const bodyFloor = {cornersPerPass: bodyCorners.length, uniqueTransformsPerPass: bodyUnique.length,
        removedPercent: bodyCorners.length ? +((1 - bodyUnique.length / bodyCorners.length) * 100).toFixed(1) : 0,
        samplesChecked: bodyFloorSamples};
    report.models[id] = {frames, corners, reused, workloads, bodyFloor,
        hairVisibility: {cachedParts: hairMasks.size, expressionPartsUsingLiveScan: allParts.length - hairMasks.size, masksChecked: 8},
        sourceBytes: fs.statSync(path.join(assets, 'yukkuri', file)).size};
    console.log(`${id}: ${frames} frames, ${corners} corners checked, idle reuse ${workloads.idle.reusedPercent}%, body floor ${bodyCorners.length} -> ${bodyUnique.length}`);
}
const destination = path.join(root, '.local/reports/skinning-cache');
fs.mkdirSync(destination, {recursive: true});
fs.writeFileSync(path.join(destination, 'inputs.json'), JSON.stringify(report, null, 2) + '\n');
