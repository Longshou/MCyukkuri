'use strict';
const fs = require('fs');
const path = require('path');
const root = path.resolve(__dirname, '../../../..');
const source = path.join(__dirname, 'preview');
const out = path.join(root, '.local/previews/rapem-alice');
fs.mkdirSync(out, {recursive: true});
for (const name of ['index.html', 'style.css', 'app.js']) {
  fs.copyFileSync(path.join(source, name), path.join(out, name));
}
for (const name of ['heat.png', 'rapem.png', 'heat-atlas-preview.png', 'variant.json']) {
  fs.copyFileSync(path.join(__dirname, 'variant', name), path.join(out, name));
}
const readme = `# 素鸡立与雷普魔爱丽丝 · 本机预览

生成入口：\`node standards/yukkuri-library/drafts/rapem-alice/build_preview.js\`
页面：\`.local/previews/rapem-alice/index.html\`
服务：\`node standards/yukkuri-library/drafts/rapem-alice/serve.js\`，访问 \`http://127.0.0.1:8799/.local/previews/rapem-alice/index.html\`

## 内容

- 通用“素鸡立”发情期演出：警觉 → 热感上升 → 坐立不安 → 驱力增强 → 冲动冲刺 → 持续循环。
- 爱丽丝“雷普魔”异常状态：同一套动作的高强度版本，失神白眼＋流涎、颤抖、头部抽动与前冲。
- 模型与贴图直接读取当前游戏运行资源；新增表情由既有 dazed 白眼与 drool 嘴部拼合，不新增器官或性化几何。

## 边界

“雷普魔”是 wiki/L36 记载的失控变体，不是普通爱丽丝的常态，也不是新种族。演出不包含交配动作、裸露或生殖器展示；它只表现失去理智、繁殖驱力与身体能力增强这三项记录中的行为线索。
`;
fs.writeFileSync(path.join(out, 'README.md'), readme);
console.log('preview written to', out);
