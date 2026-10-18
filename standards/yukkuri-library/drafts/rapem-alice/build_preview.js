'use strict';
const fs = require('fs');
const path = require('path');
const root = path.resolve(__dirname, '../../../..');
const source = path.join(__dirname, 'preview');
const out = path.join(root, '.local/previews/rapem-alice');
fs.mkdirSync(out, {recursive: true});
fs.writeFileSync(path.join(out, 'pose-cues.js'), 'globalThis.PresentationPoseCues = ' + fs.readFileSync(path.join(__dirname, 'pose-cues.json'), 'utf8') + ';\n');
for (const name of ['index.html', 'style.css', 'app.js', 'sequence.html']) {
  fs.copyFileSync(path.join(source, name), path.join(out, name));
}
fs.copyFileSync(path.join(__dirname, 'variant', 'variant.json'), path.join(out, 'variant.json'));
fs.writeFileSync(path.join(out,'README.md'), '# 成体正常配对、事后茎生与营养消耗\n\n主预览为normal-flow.gif、abnormal-flow.gif及各自的-flow-storyboard.png；state-faces.png是同姿态状态表情对照；stem-development.png展示营养仍足够时从零进度到出生后茎离身的另一条分支。生成源为制作包内render_flows.js与render_flow_captions.py，先运行Node渲染，再用带Pillow的Python加字与导出GIF。本脚本只重建页面，不替代渲染。\n\n正常演出复用完整greet 1.4秒、nuzzle 1.6秒与成功shiawase 2秒；异常流程先对照事前无茎与事后进度0无茎，再逐渐长茎。后段选择茎生过度消耗分支：母体营养下降、停育仍继续负担、营养耗竭、濒死耗馅，严重时才死亡；营养仍足够时可继续发育。时序经过压缩，不代表固定死期。\n\nreference-normal.png可核对1.12原普通成体眼眉。sukkiri单体文件仅为8秒持续期中性观察，不展示期结束。alice-agitated单体文件仅项目示意，原作专属失控形态未完成。标准确认待用户；所有图片均不是游戏录屏。\n');
console.log('preview written to',out);
