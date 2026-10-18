# 素鸡立与雷普魔爱丽丝（程序参考重做）

本目录按 **油库里八景 ver1.12（馒吧汉化组）** 的程序逻辑重做，把两件事明确分开：

| | 素鸡立 | 雷普魔爱丽丝 |
| --- | --- | --- |
| 程序状态 | `すっきり`（自我缓解/站立发情演出） | `れいぽう`（强侵入性失控状态） |
| 程序面部帧 | 成人 Alice：eye 68/70、brow 8、mouth 256/280、blush 30/34 | `*label_0643`：eye 0、brow 8、mouth 30、blush 0；op2 24/28 |
| 本页表情 | `flushed_smile` → 重绘 `sukkiri_tongue`（闭眼、潮红、开口短舌） | 重绘 `rapem`：白眼、怒眉、开口笑、无腮红 |
| 动作 | 程序 `すっきり`；`startle` / `brace` / `waddle` 分层，保持站立 | 程序 `れいぽう`；`startle` / `turn` / `brace` / `waddle` / `hop` 分层，锁定目标、接近、前冲 |
| 范围 | 爱丽丝、魔理沙等运行模型通用 | 仅爱丽丝失控变体；不是新种族，也不是每只爱丽丝的常态 |

## 程序依据

反编译源：`.local/reference/eight-sight/start.utf8.hsp`（本机文件，不入库）。游戏本体从 `八景1.12馒吧汉化组汉化版/1/油库里八景ver1.12.rar` 解出，密码为汉化组名。

### れいぽう（雷普魔）

- `れいぽうゲージ` 累积；`種族フラグ == 2`（爱丽丝）在首次增加时 `+100`，之后按徽章/状态持续获得更高增长。
- 达到阈值（2000 / 8000 / 400，视学习与种族）后 `れいぽう開始フラグ = 1`，进入 `*label_2616`：
  - `*label_0643` 设置面部：`目表示枚数 = 0`、`眉表示枚数 = 8`、`口表示枚数 = 30`、`頬表示枚数 = 0`。
  - 成人 `op2表示枚数 = 28`，儿童 `= 24`。
  - `*label_3432` / `*label_3433` 载入 `rapo_attack` 台词。
- `*label_2617` 结束状态；`*label_2623` / `*label_2624` 处理 `相互れいぽう` 的目标锁定、`相互れいぽうキャッチ` 和接近。
- 本页只演出 **锁定 → 下蹲 → 快速接近 → 跳跃前冲 → 持续失控**，不演出交配过程；佩妮佩妮仅作为程序 op2 的解剖状态显示，马姆马姆隐藏。

### すっきり（素鸡立）

- `すっきり学習フラグ` 与 `れいぽうゲージ` 共同决定动作；`*label_1398` 设置成人面部：`目 68/70`、`眉 8`、`口 256/280`、`頬 30/34`，并设置 `op2` / `op3`。
- `*label_1401` 进入 `ゆっくりＯＰ` 动画：`9531` → `9537`，`表情タイム` 在 0–24 循环。
- 本页把这段压缩成 `flushed_smile` → 重绘 `sukkiri_tongue`，身体保持站立，只做呼吸、重心摆动和轻度用力。
- `sukkiri_tongue` 保留 `flushed_tongue` 的闭眼/眉/腮红，嘴部改用 `open_smile` 的短舌并清空 effect-mouth 覆盖层，修掉原长舌的黑色重叠线。
- 佩妮佩妮（程序 `op2`/`ぺにぺに`）默认显现；马姆马姆始终隐藏。该显示只对应程序解剖状态，不演出交配或插入过程。

## 重绘

`build_rapem.js` 生成 `variant/rapem.png`：

- 底图：标准 `neutral.png`。
- 眼：`blank_happy.png` 白眼区。
- 眉：`angry.png` 怒眉区。
- 嘴：`open_smile.png` 开口笑。
- 腮红/眼泪：保留 neutral（无腮红、无泪）。
- 依据：`れいぽう` 的 frame 选择为 eye 0 / brow 8 / mouth 30 / blush 0；原帧在 3D 模型上偏中性，重绘保留“睁眼、抬眉、开口、无腮红”的结构，强化“失去理智”的可读性。
- 重绘只组合项目标准五官部件，不复制游戏像素。

## 文件

| 文件 | 用途 |
| --- | --- |
| `build_rapem.js` | 从标准表情图集重绘 `variant/rapem.png` 与 `variant/sukkiri_tongue.png` |
| `motion-clips.js` | 用已有 `startle / turn / brace / waddle / hop` 运行时片段分层生成 `heat_*`、`rapem_*` |
| `variant/variant.json` | 两种状态、程序帧、重绘配方与边界 |
| `preview/` | 网页预览源模板 |
| `build_preview.js` | 复制模板与登记到 `.local/previews/rapem-alice/` |
| `render_contact.js` | 软件光栅化八格联系表 |
| `verify.js` | 动作采样、表情文件、heat/rapem 姿态差异检查 |
| `serve.js` | 仅监听 `127.0.0.1:8799` 的只读静态服务 |

## 生成与查看

```sh
node standards/yukkuri-library/drafts/rapem-alice/build_rapem.js
node standards/yukkuri-library/drafts/rapem-alice/build_preview.js
node standards/yukkuri-library/drafts/rapem-alice/render_contact.js
node standards/yukkuri-library/drafts/rapem-alice/serve.js
```

打开：

```text
http://127.0.0.1:8799/.local/previews/rapem-alice/index.html
```

集中入口：`.local/previews/index.html`。

## 检查状态

- `node --check`：`build_rapem.js`、`motion-clips.js`、`preview/app.js` 通过。
- `verify.js`：五种运行模型无非有限值；`flushed_smile` / `flushed_tongue` 图集存在；`variant/rapem.png`、`variant/sukkiri_tongue.png` 与佩妮佩妮变体存在；heat/rapem 的最大姿态差已记录。
- 本机 HTTP：页面、`motion-clips.js`、标准表情图集、`variant/rapem.png`、运行模型均返回 200。
- **未执行**：浏览器自动化、Minecraft 实机、Gradle 编译。按仓库约定，本机不启动浏览器自动化，也不执行编译；页面交互与最终观感需要手动打开确认。
