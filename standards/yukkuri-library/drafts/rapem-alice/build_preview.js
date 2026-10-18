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

- **素鸡立（程序 すっきり）**：通用发情期站立演出。程序的面部帧为 eye 68/70、brow 8、mouth 256/280、blush 30/34；预览使用 flushed_smile → 重绘 sukkiri_tongue（闭眼、潮红、开口短舌；去掉原长舌重叠）。动作只用 startle / brace / waddle 分层，保持站立。
- **雷普魔爱丽丝（程序 れいぽう）**：爱丽丝失控异常状态。程序在 *label_0643 选 eye 0 / brow 8 / mouth 30 / blush 0，并叠加目标锁定与接近；原帧偏中性，预览重绘为 rapem（白眼、怒眉、开口笑、无腮红）。动作加入 turn / hop、目标接近和向前的根位移。佩妮佩妮默认显现，马姆马姆始终隐藏；不演出交配或插入过程。

两者不是强度档位，而是不同表情、不同动作构成、不同状态含义。雷普魔不是新种族，也不是每只爱丽丝的常态。程序反编译源见 .local/reference/eight-sight/start.utf8.hsp。

## 边界

不包含交配动作、裸露或生殖器展示；具体动作幅度、镜头和光环属于展示设计，不是 Wiki 的固定动作记录。
`;
fs.writeFileSync(path.join(out, 'README.md'), readme);
console.log('preview written to', out);
