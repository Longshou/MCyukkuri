# 头发贴合与移动检查

后续修订说明：本报告及原候选指纹保留上一轮背发修复的检查范围。用户随后指出的豆油贴合问题另行修复，当前 20 形态展示也已增加捕食种日常尖牙；当前展示的核对记录见 [bean-fang-gallery-browser.json](bean-fang-gallery-browser.json)，豆油制作与检查见[修订说明](../../../subspecies/deficient/reports/current/HAIR-FIT.md)。不要用本报告的旧展示指纹宣称后续修订已经通过当时的检查。

2026-09-13，本机 Windows、Python 3.13／Pillow、Node.js 与现有 Playwright／Chrome。检查针对当前标准库导出和本轮展示页；游戏运行资源同步由活动接入任务负责。

八种圆润模型的 `hair` 与 `bangs` 改为按高度细分的纹理网格，使用身体同一组连续分段权重。后发按身体截面增加 0.22 模型单位的目标遮盖余量；灵胖只加深上部刘海后侧发根，保留正面轮廓和下部发梢。源配方在 [recipe.json](../../features/hair-contact/recipe.json) 和 [author.py](../../features/hair-contact/author.py)，没有修改冻结输入或原批准指纹。

| 检查 | 当前结果 |
| --- | --- |
| 源与导出 | [3,110 项通过](sources.json)，含组件／模型一致、骨架与权重数量、未修改部件、730 份冻结输入和 95 份排除基线 |
| 动作数据 | [131 段动作、3,275 个姿态通过](structure.json)，骨骼索引有效、坐标有限，活动片段发生变化 |
| 后发遮挡 | 七种带走路动作的标准模型，各 121 时间点、39 个后部内部采样点，共 33,033 点；[旧版 1,678 点穿透](hair-contact-before.json)，[新版 0 点](hair-contact.json)；这是限定后部区域的几何检查，不是全身碰撞证明 |
| 螺壳种 | 标准导出没有 `waddle`，未虚构普通走路结论；现有螺壳动作纳入数据采样和浏览器展示 |
| 视觉复核 | 自行查看本轮正面、斜侧、侧面、背面，以及走路两侧摆动、伸展、侧弯和转身图；[修复前](hair-contact-before.png)／[修复后](hair-contact-after.png)，[灵胖四视图](../../previews/current/reimu_deibu-views.png) |
| 用户展示入口 | [Chrome 检查通过](hair-gallery-browser.json)，遍历全部展示角色的四个动作时间点，检查背面／复位按钮和三种受影响的独立预览，无脚本错误 |

[全部模型交互页](../../previews/current/all-models/index.html)读取实际导出，包含四视图及每种 12 帧背面动图。DOS 的原预览顶点在展示适配中按法线统一三角面方向，保留模型本身与专属表情。各角色独立取景，图片中大小不代表游戏尺寸。

[灵胖刘海修复前后](deibu-hair-comparison.png)使用同一侧面镜头分别渲染本轮修复前的数据和当前导出。上部发根已连接，刘海前缘和发梢保持原位。

八种标准导出和多毛灵胖组合已由接入任务重新选用，运行九模型的头发权重保持本轮分段绑定。另发现的旧不足／脸油后发下缘浅色条已由接入任务修复：四个 sparse／face 模型低后发按后部包络外移，并加入 45% 后部运动跟随；六目标已同步，两种 bean 的运行模型字节保持不变。独立来源和审查见[亚种后发适配](../../../../../art/source/subspecies-runtime/motion-contact/manifest.json)及[检查记录](../../../../../art/source/subspecies-runtime/motion-contact/reports/current/review.json)。本页 20 形态展示已刷新读取这批运行资源。

本任务未单独启动 Minecraft；游戏接入与实机结果不由上述离线和浏览器检查替代，实机复验仍由接入任务执行。标准身体的原确认仍保留，本轮头发造型为待确认，当前候选的模型、骨架、配方和证据指纹见 [candidate.json](../../features/hair-contact/candidate.json)。

复现入口（项目根目录）：

```powershell
python standards/yukkuri-library/body-standard/build.py
python standards/yukkuri-library/body-standard/render_preview.py --actions
python standards/yukkuri-library/body-standard/verify_sources.py
node standards/yukkuri-library/body-standard/verify.cjs
node standards/yukkuri-library/body-standard/features/hair-contact/verify.cjs
python standards/yukkuri-library/body-standard/features/hair-contact/gallery.py
node standards/yukkuri-library/body-standard/features/hair-contact/verify_gallery.cjs
```
