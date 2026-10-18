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
fs.writeFileSync(path.join(out, 'README.md'), `# 素鸡立与雷普魔爱丽丝 · 待确认制作候选

两个独立的成体片段：正常→躁动→短时缓解→恢复；警觉→定向接近→中断→疲惫恢复。
均为 10 秒有限演出，结束后暂停。标准脸与游戏复用；动作编排没有自动接入游戏。

生成入口：node standards/yukkuri-library/drafts/rapem-alice/build_preview.js
联系图：node standards/yukkuri-library/drafts/rapem-alice/render_contact.js
页面：.local/previews/rapem-alice/index.html
服务：node standards/yukkuri-library/drafts/rapem-alice/serve.js
地址：http://127.0.0.1:8799/.local/previews/rapem-alice/index.html

默认关闭转台和光环；无生殖器显示、接触动作或无限状态循环。
分段、幅度和恢复节奏均为制作选择，不是 Wiki/L36 固定动作记录，也不是 Minecraft 实机验收。
`);
console.log('preview written to', out);
