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
fs.writeFileSync(path.join(out,'README.md'), '# 八景U参考重做预览\n\n独立可观看产物：alice-agitated-closeup.png、alice-agitated.gif、sukkiri-closeup.png、sukkiri.gif。两项各有storyboard.png供阶段核对。三张新脸及姿态幅度已选入游戏，标准确认仍待视觉反馈。10秒时间轴为展示编排，本页不是游戏录屏。\n');
console.log('preview written to',out);
