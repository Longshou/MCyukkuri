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
fs.writeFileSync(path.join(out,'README.md'), '# 成体正常配对、异常回避与事后照护\n\n主预览为normal-flow.gif、abnormal-flow.gif及各自的-flow-storyboard.png；state-faces.png是同姿态生产脸对照。生成源为制作包内render_flows.js与render_flow_captions.py，先运行Node渲染，再用带Pillow的Python加字与导出GIF。本脚本只重建页面，不替代渲染。\n\n正常演出复用完整greet 1.4秒、nuzzle 1.6秒与成功shiawase 2秒；异常流程展示接近与回避，并以独立场景示意早期绿茎、疲惫、现有食物与成年同伴。路线与护理摆放是离线编排，不是寻路或新增送食AI证明。\n\nreference-normal.png可核对1.12原普通成体眼眉。sukkiri单体文件仅为8秒持续期中性观察，不展示期结束。alice-agitated单体文件仅项目示意，原作专属失控形态未完成。标准确认待用户；所有图片均不是游戏录屏。\n');
console.log('preview written to',out);
