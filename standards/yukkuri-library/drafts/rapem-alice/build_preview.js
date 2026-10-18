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
fs.copyFileSync(path.join(__dirname, 'variant', 'variant.json'), path.join(out, 'variant.json'));
const readme = `# 素鸡立与雷普魔爱丽丝 · 本机预览

生成入口：\`node standards/yukkuri-library/drafts/rapem-alice/build_preview.js\`
页面：\`.local/previews/rapem-alice/index.html\`
服务：\`node standards/yukkuri-library/drafts/rapem-alice/serve.js\`，访问 \`http://127.0.0.1:8799/.local/previews/rapem-alice/index.html\`

## 两种表现

- **素鸡立**：通用发情期站立演出。表情取自标准库 N034/N035，即八景 U「基本発情顔１→２」的 flushed_smile → flushed_tongue（闭眼、潮红、笑/伸舌）。动作只用 startle / brace / waddle 分层，保持站立，做呼吸、重心摆动和轻度用力。
- **雷普魔爱丽丝**：爱丽丝失控异常状态。表情取自标准库 N036/N037，即八景 U「基本エロ笑顔１→２」的 teary_tongue / teary_tongue_b（睁眼、含泪、伸舌）。动作加入 turn / hop 和向前的根位移，表现为突然抽头、下蹲、快速踱步与跳跃前冲。

两者不是强度档位，而是不同表情、不同动作构成、不同状态含义。雷普魔不是新种族，也不是每只爱丽丝的常态。

## 边界

不包含交配动作、裸露或生殖器展示；具体动作幅度、镜头和光环属于展示设计，不是 Wiki 的固定动作记录。
`;
fs.writeFileSync(path.join(out, 'README.md'), readme);
console.log('preview written to', out);
