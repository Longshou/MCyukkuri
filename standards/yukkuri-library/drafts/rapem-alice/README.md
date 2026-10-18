# 八景1.12成体外形适配与素鸡立流程

依据用户提供的 `油库里八景ver1.12/油库里八景ver1.12/` 原资源制作：普通成体外形按 `pict/arisu/sei_new` 普通眼眉适配，行为骨架按 `start.utf8.hsp` label_2616..2624 还原为完整雷普魔素鸡立序列（宣言→追击→接触→摩擦动作→三段完成→点评/自助餐→すっき死），台词直引 `text/<角色>/sei/seima_serifu.txt`。这些是幻想生物的生殖行为，作为游戏机制客观还原。确认状态待用户视觉反馈。

## 参考资料

`更新履歴.txt` 写明 2020/05/18 ver1.12。实际核对 `pict/arisu/sei_new/6.png` 第0格普通眼、`7.png` 第0格普通眉，以及 `4.png`、`10.png` 第2格普通头发/发带分层候选。普通成体眼是蓝虹膜、黑细轮廓、大片白高光及下侧小白点，普通眉为细弧。行为逻辑读 `start.utf8.hsp`：れいぽうゲージ（爱丽丝阈值400）、発情→相互ゆっくす/雷普分支、attack_1..6、rapo_1..3 台词时序与体力−200/すっき死分支。文件路径与SHA256在 `face-design.json`。

## 当前输出

| 面部源 | 用途 |
| --- | --- |
| `variant/breeding_period.png` | 1.12普通成体蓝眼配色适配，中性闭口；期内观察提示 |
| `variant/alice_agitated.png` | 同蓝眼配色下的雷普魔紧张眉口；搜寻找与追击阶段使用 |
| `variant/state_recover.png` | 序列结束后的恢复示意 |
| `pose-cues.json` | heat为8秒左右观察：yaw 0/-4/0/+4/0/0，pitch/roll为0、scale恒1；relief为120tick单次弹动（两轻一重三次下压回弹，末尾归位）；rapem为搜寻找/追击前倾与动作阶段2秒循环摩擦采样 |

素鸡立按用户指代的发情期展示；雷普魔序列按1.12骨架完整接入：动作阶段攻方前倾循环摩擦并保持 `anatomy_peni_extend`（对应1.12 op2器官显示），受方 tremble 挣扎与哭脸；三段完成台词（ゆっ！→すっ！→すっきり～！）、点评（まむまむ评价）、自助餐再猎与体力耗竭死亡均已接通。旧 `brief_relief.png` 闭眼呼气脸及一次抬身、卸力、疲惫恢复的短片剧情已删除。

## 可观看产物

全部位于 `.local/previews/rapem-alice/`：

- `normal-flow.gif`、`normal-flow-storyboard.png`：多角色正常配对；双方回应1.4秒、亲近1.6秒、成功回应2秒，随后选取普通茎生结果举例。动作取现有 `greet`、`nuzzle`、`shiawase`，不是新发现的原作繁殖动画。
- 交互页 `index.html` 含“素鸡立完成”（6秒三拍弹动）与“雷普魔素鸡立”（2秒摩擦循环）两个状态，与运行端 `heat_relief` / `alice_act` 行为及关键帧通道同源；三拍分别对应 heat_relief_1/2/3 台词。
- `abnormal-flow.gif`、`abnormal-flow-storyboard.png`：事前无茎、事后进度0仍无茎、逐渐萌茎；随后选择茎生过度消耗分支，展示母体营养下降、停育仍继续负担、衰弱、营养耗竭、濒死耗馅，以及严重时死亡。九格分镜直接对照事前与事后的零进度。时间经过压缩，不代表固定死亡时长或所有个体必死。
- `state-faces.png`：相同正面、相同待机姿态下的普通期、异常和恢复脸对照，避免仅靠前倾制造差别。
- `stem-development.png`：营养仍足够时的另一条分支，按0、0.06、0.15、0.55、0.95以及出生完成后取样。茎果复用当前 `FamilyGeometry` / `FamilyRenderer` 的挂点、`GrowthSize.fruit` 比例、角色 `sleep` 贴图和 `idle(0)`；母体保持事后 `teary` 表情。`FamilyLife.finishStem` 在出生完成后清除头顶挂接，并由 `ShedStemDrops` 处理茎离身。
- `reference-normal.png`：实际1.12普通成体分层参考及原眼眉放大。
- `sukkiri-closeup.png`、`sukkiri.gif`、`sukkiri-storyboard.png`：期内观察三近景、8秒循环和六点采样；没有期结束演出。
- `rapem-act.gif`、`rapem-act-storyboard.png`：接触后的素鸡立动作单体片段（前倾贴身、2秒摩擦循环、三段完成节拍标注）；器官伸出仅见于游戏内，离线渲染不含解剖部件。
- `alice-agitated-closeup.png`、`alice-agitated.gif`、`alice-agitated-storyboard.png`：搜寻与追击的前倾姿态示意。
- `contact.png` 汇总当前近景；`index.html` 可手动拖动查看。全部不是游戏录屏。

多角色行进路线、画面切换和消耗时序为离线编排。事后茎长读取每个阶段的 `stemProgress`，按 `smoothstep(progress / .15)` 从0连续增长；正常配对后的绿茎也从0逐渐长到示例进度0.08。营养低于30时停育，但 `FamilyLife.tickAftermathBurden` 仍持续消耗母体营养与体力。营养归零后复用 `YukkuriLife` 现有损伤，`BodyLife.guardCore` 先保留1点生命并耗馅，馅量耗尽后再次受伤才可能死亡。遗体复用 `RemainsRenderer` 的 `sleep`、`idle(0)` 和纵向0.65缩放，不再附着茎。营养仍足够时继续发育，与预览后段的过度消耗分支分开。游戏事后结算条件见 [行为说明](../../../../docs/ADULT_AGITATION.md)。

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
