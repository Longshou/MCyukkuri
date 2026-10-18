# 八景1.12普通成体外形适配与成体状态流程

本轮依据用户新提供的 `油库里八景ver1.12/油库里八景ver1.12/` 原资源纠正外形。确认状态仍为待用户视觉反馈。**原作专属失控形态未完成**；不能把普通脸或项目斜眉当作专属还原。

## 参考资料

`更新履歴.txt` 写明 2020/05/18 ver1.12。实际核对 `pict/arisu/sei_new/6.png` 第0格普通眼、`7.png` 第0格普通眉，以及 `4.png`、`10.png` 第2格普通头发/发带分层候选。普通成体眼是蓝虹膜、黑细轮廓、大片白高光及下侧小白点，普通眉为细弧。文件路径与SHA256在 `face-design.json`。

缓存 `.local/reference/eight-sight/start.utf8.hsp` 尚未与本安装包建立提取指纹绑定，只能用于候选分层定位，不能证明1.12专属状态调用。内部版本数字不能独自证明对外发行版本。参考拼图不是运行截图，运行贴图由参数化脚本重画。

## 当前输出

| 面部源 | 用途 |
| --- | --- |
| `variant/breeding_period.png` | 1.12普通成体蓝眼配色适配，中性闭口；只提示期内观察，不宣称原作专属发情脸 |
| `variant/alice_agitated.png` | 同蓝眼配色下的项目紧张眉口示意；原作专属失控形态尚未完成 |
| `variant/state_recover.png` | 项目恢复示意，仅供失控分支使用 |
| `pose-cues.json` | heat为8秒左右观察：yaw 0/-4/0/+4/0/0，pitch/roll为0、scale恒1；rapem保留项目姿态与恢复尾部 |

素鸡立按用户指代的发情期展示。已删除旧 `brief_relief.png` 闭眼呼气脸及一次抬身、卸力、疲惫恢复的短片剧情。HEAT使用持续期内中性观察，8秒循环不是期结束；时期由服务端负责。其他物种保留其自己的普通脸，不直接套Alice图集。

预览和游戏使用同源姿态，绕原点按 `Rz · Ry · Rx · Scale` 变换后接地。失控的10秒中断/恢复只为项目离线示意，不是已核对原作行为。

## 可观看产物

全部位于 `.local/previews/rapem-alice/`：

- `normal-flow.gif`、`normal-flow-storyboard.png`：多角色正常配对；双方回应1.4秒、亲近1.6秒、成功回应2秒，随后选取普通茎生结果举例。动作取现有 `greet`、`nuzzle`、`shiawase`，不是新发现的原作繁殖动画。
- `abnormal-flow.gif`、`abnormal-flow-storyboard.png`：异常接近与对方回避，以及独立的事后茎生、疲惫与食物陪伴示例。陪护摆位为离线编排，自动送食AI尚未实现。
- `state-faces.png`：相同正面、相同待机姿态下的普通期、异常和恢复脸对照，避免仅靠前倾制造差别。
- `reference-normal.png`：实际1.12普通成体分层参考及原眼眉放大。
- `sukkiri-closeup.png`、`sukkiri.gif`、`sukkiri-storyboard.png`：期内观察三近景、8秒循环和六点采样；没有期结束演出。
- `alice-agitated-closeup.png`、`alice-agitated.gif`、`alice-agitated-storyboard.png`：均明确标为项目示意，原作专属失控形态未完成。
- `contact.png` 汇总当前近景；`index.html` 可手动拖动查看。全部不是游戏录屏。

多角色行进路线、画面切换与照护摆位是离线编排，不替代游戏寻路和机制验收。游戏自动离屏结算遵循 [行为说明](../../../../docs/ADULT_AGITATION.md) 的玩家追踪、接近距离、目标资格和一次性守卫；现场观察只会看到警觉与回避，事后场景通过已有入口单独展示。

## 重建

```sh
python standards/yukkuri-library/drafts/rapem-alice/build_reference.py
node standards/yukkuri-library/drafts/rapem-alice/build_faces.js
node standards/yukkuri-library/drafts/rapem-alice/build_preview.js
node standards/yukkuri-library/drafts/rapem-alice/render_visuals.js
python standards/yukkuri-library/drafts/rapem-alice/render_captions.py
node standards/yukkuri-library/drafts/rapem-alice/render_contact.js
node standards/yukkuri-library/drafts/rapem-alice/render_flows.js
python standards/yukkuri-library/drafts/rapem-alice/render_flow_captions.py
node standards/yukkuri-library/drafts/rapem-alice/verify.js
```

参数化面部源为 `face-design.json` 与 `build_faces.js`。参考图生成器为 `build_reference.py`，只读取明确列出的普通成体切片，依赖用户提供的本机1.12目录；不浏览混合状态图集。参考图留在本机，运行选用由 `art/runtime-sources.json` 登记；选用不等于标准认可。Python仅添加中文标签和编码GIF，需要Pillow和微软雅黑。渲染使用仓库软件光栅器，不运行浏览器、不编译游戏；试玩仍由用户使用唯一试玩场入口。
