/* Check the offline camera against a right-handed camera and the shipped asymmetric mesh. */
'use strict';
const assert = require('node:assert/strict');
const {OrthographicCamera, Vector3} = require('../preview/vendor/three.min.js');
const {renderModel} = require('../standards/yukkuri-library/runtime/render.js');
const marisa = require('../src/main/resources/assets/mcyukkuri/yukkuri/marisa.json');

const width = 128, height = 96, span = 16, centerY = 9.5;
const braid = marisa.parts.filter(part => part.name.startsWith('braid_lock_'))
  .flatMap(part => marisa.vertices.slice(part.start * 8, (part.start + part.count) * 8));
assert(braid.length > 0, 'The shipped Marisa mesh must contain the single braid');
const points = [];
for (let i = 0; i < braid.length; i += 8) {
  points.push(new Vector3(...braid.slice(i, i + 3)));
}
assert(points.every(point => point.x > 0), 'The single braid is on Marisa’s +X side');

const texture = new Uint8Array([255, 255, 255, 255]);
for (const [az, el] of [[0, 0], [.65, .35], [-.65, .35], [Math.PI / 2, 0], [Math.PI, 0]]) {
  const horizontal = span * width / height;
  const camera = new OrthographicCamera(-horizontal, horizontal, span, -span, .1, 200);
  camera.position.set(50 * Math.sin(az) * Math.cos(el), centerY + 50 * Math.sin(el),
                      -50 * Math.cos(az) * Math.cos(el));
  camera.lookAt(0, centerY, 0);
  camera.updateMatrixWorld();
  const projected = points.map(point => point.clone().project(camera));
  const x = projected.map(point => (1 + point.x) * width / 2);
  const y = projected.map(point => (1 - point.y) * height / 2);
  const [minX, maxX] = [Math.min(...x), Math.max(...x)];
  const [minY, maxY] = [Math.min(...y), Math.max(...y)];
  const pixels = renderModel(braid, texture, width, height, az, el, span, {centerY, textureSize: 1});
  const visible = [];
  for (let i = 0; i < width * height; i++) {
    if (pixels[4 * i + 3]) visible.push([i % width, Math.floor(i / width)]);
  }
  assert(visible.length > 0, `Braid must render at azimuth ${az}`);
  assert(visible.every(([px, py]) => px + .5 >= minX - 1e-7 && px + .5 <= maxX + 1e-7
                                  && py + .5 >= minY - 1e-7 && py + .5 <= maxY + 1e-7),
         `Braid is outside the camera projection at azimuth ${az}, elevation ${el}`);
  const visibleX = visible.map(([px]) => px);
  console.log(`Marisa braid az=${az.toFixed(3)} el=${el.toFixed(3)}: x=${Math.min(...visibleX)}–${Math.max(...visibleX)}, ${visible.length} pixels`);
}

function panel(z, solid, backFace = false) {
  const corners = [[-4, -4, 1, 1], [-4, 4, 1, 0], [4, 4, 0, 0], [4, -4, 0, 1]];
  const order = backFace ? [0, 2, 1, 0, 3, 2] : [0, 1, 2, 0, 2, 3];
  return order.flatMap(i => {
    const [x, y, u, v] = corners[i];
    return [x, y, z, solid ?? u, v, 0, 0, backFace ? 1 : -1];
  });
}

const swatches = new Uint8Array([255, 0, 0, 255, 0, 0, 255, 255,
                                255, 255, 0, 255, 0, 0, 0, 0]);
const colors = [[255, 255, 255], [0, 255, 0]];
const pixels = renderModel([...panel(-1), ...panel(1, -1), ...panel(-2, -2, true)],
                           swatches, width, height, 0, 0, span, {centerY: 0, textureSize: 2, colors});
const pixel = (x, y) => Array.from(pixels.slice(4 * (y * width + x), 4 * (y * width + x) + 4));
assert.deepEqual(pixel(58, 42), [225, 0, 0, 255], 'Top-left texture and near face');
assert.deepEqual(pixel(70, 42), [0, 0, 225, 255], 'Top-right texture');
assert.deepEqual(pixel(58, 54), [225, 225, 0, 255], 'Bottom-left texture');
assert.deepEqual(pixel(70, 54), [225, 225, 225, 255], 'Transparent texel reveals the far face');
assert.deepEqual(pixel(0, 0), [0, 0, 0, 0], 'Background stays transparent');
console.log('Texture orientation, depth, alpha and back-face culling passed');
