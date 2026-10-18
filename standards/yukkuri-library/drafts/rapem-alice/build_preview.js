'use strict';
const fs = require('fs');
const path = require('path');
const root = path.resolve(__dirname, '../../../..');
const source = path.join(__dirname, 'preview');
const out = path.join(root, '.local/previews/rapem-alice');
fs.mkdirSync(out, {recursive: true});
fs.writeFileSync(path.join(out, 'pose-cues.js'), 'globalThis.PresentationPoseCues = ' + fs.readFileSync(path.join(__dirname, 'pose-cues.json'), 'utf8') + ';\n');
for (const name of ['index.html', 'style.css', 'app.js']) {
  fs.copyFileSync(path.join(source, name), path.join(out, name));
}
fs.copyFileSync(path.join(__dirname, 'variant', 'variant.json'), path.join(out, 'variant.json'));
fs.writeFileSync(path.join(out,'README.md'), '# 八景1.12普通成体外形适配与持续期观察\n\nreference-normal.png可核对原普通成体眼眉。sukkiri近景/GIF/分镜是8秒持续期内中性观察，不展示期结束。alice-agitated文件仅项目示意，原作专属失控形态未完成。标准确认待用户；不是游戏录屏。\n');
console.log('preview written to',out);
