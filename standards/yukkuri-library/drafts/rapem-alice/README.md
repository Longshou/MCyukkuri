# 八景U参考：短时缓解与爱丽丝失控状态

本轮按用户要求重做；旧白眼笑脸与旧吐舌草稿已移除。新制作源为 `face-design.json`、`build_faces.js` 和 `pose-cues.json`，确认状态仍为**待用户视觉反馈**。

## 可直接观看的当前产物

输出均在 `.local/previews/rapem-alice/`：

- `alice-agitated-closeup.png`、`alice-agitated.gif`：普通／失控形象／恢复的近景与完整动作。
- `sukkiri-closeup.png`、`sukkiri.gif`：短时紧张、一次抬身、缓慢卸力和恢复。
- `alice-agitated-storyboard.png`、`sukkiri-storyboard.png`：固定地面和镜位的六阶段分镜，含起点和终点。
- `contact.png`：两张当前近景的汇总；`index.html` 保留可手动拖动的预览。

GIF在10秒示例结束后停顿并重播；完整网页演出结束暂停。动画播放方式不表示游戏状态会无限循环。

## 依据和改编边界

已核对的八景U参考是 `wiki/art-references/game/standard-species/hakkei/sprite-4654.png`（成体普通脸）及 `hakkei-slot-2.png`（爱丽丝整头）。取其大黑瞳、双高光、细弧眼缘、小口和浅粉椭圆颊的外形特点，使用参数化绘制重新适配项目模型，不复制原游戏像素。

当前没有可验证的八景U专属“雷普魔”脸调用依据。新稿的集中凝视、内压斜眉与短扁紧口、持续前倾和失稳属于项目状态改编。L36与Wiki提供异常状态及群落风险的背景，不规定本稿唯一眉眼和动作秒数。本包不再使用1.12程序帧号作依据，也没有采用疑似幼体的色情表情参考。

## 新面部与姿态

| 输出 | 特点 |
| --- | --- |
| `variant/alice_agitated.png` | 保留大黑瞳和双高光，集中眼神、内压斜眉与短扁紧口；替代旧白眼笑脸 |
| `variant/brief_relief.png` | 平缓闭眼、小口呼气；无吐舌、潮红高潮演出 |
| `variant/state_recover.png` | 低眼睑、闭口和疲倦神态 |
| `pose-cues.json` | 六通道姿态关键帧；失控稳定前倾约20度、压低体型并两次非周期偏重；缓解一次抬身后缓慢沉降；0.68后共用恢复 |

三张新脸（仅爱丽丝）和同源姿态幅度已选入游戏，选用登记由 `art/runtime-sources.json` 维护；这不等于用户已认可为标准。其他物种继续使用各自标准表情，不能直接套用爱丽丝完整图集。

网页的10秒顺序与阶段时长仅用于展示；游戏持续时间、目标和中断由服务器决定。软件渲染与客户端均绕原点按 `Rz · Ry · Rx · Scale` 变换，并依最低可见顶点接地；本页不是游戏录屏。

## 重建

```sh
node standards/yukkuri-library/drafts/rapem-alice/build_faces.js
node standards/yukkuri-library/drafts/rapem-alice/build_preview.js
node standards/yukkuri-library/drafts/rapem-alice/render_visuals.js
python standards/yukkuri-library/drafts/rapem-alice/render_captions.py
node standards/yukkuri-library/drafts/rapem-alice/render_contact.js
node standards/yukkuri-library/drafts/rapem-alice/verify.js
```

Python入口仅为软件渲染帧添加中文标签并编码GIF，需要Pillow；Windows中文字体使用微软雅黑。模型渲染由仓库软件光栅器完成，不启动浏览器、不编译模组。`build_rapem.js` 保留为旧命令的转发入口，实际调用新 `build_faces.js`。

手动网页预览服务仍为 `node standards/yukkuri-library/drafts/rapem-alice/serve.js`，地址 `http://127.0.0.1:8799/.local/previews/rapem-alice/index.html`。试玩仍使用仓库唯一油库里试玩场。
