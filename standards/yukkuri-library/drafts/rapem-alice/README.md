# 素鸡立与雷普魔爱丽丝（待确认草稿）

本目录按项目现有表情与动作实现重做，目标是把两件事明确分开：

| | 素鸡立 | 雷普魔爱丽丝 |
| --- | --- | --- |
| 含义 | 通用发情期站立演出 | 爱丽丝失控异常状态 |
| 八景 U 表情 | 「基本発情顔１→２」 | 「基本エロ笑顔１→２」 |
| 标准表情库 | N034 `flushed_smile` → N035 `flushed_tongue` | N036 `teary_tongue` / N037 `teary_tongue_b` |
| 面部特征 | 闭眼、潮红、笑/伸舌 | 睁眼、含泪、伸舌 |
| 动作 | `startle` → `brace` / `waddle` 分层；保持站立 | `startle` / `turn` / `brace` / `waddle` / `hop` 分层；加入抽头、下蹲、快速踱步、跳跃前冲 |
| 适用范围 | 爱丽丝、魔理沙等运行模型通用 | 仅爱丽丝状态变体；不是新种族 |

这是**待确认草稿**，未登记为标准，也未接入游戏运行资源。

## 来源

- [Wiki · 爱丽丝](../../../wiki/hakkei-reference/lore/species/alice/README.md)：记录“雷普魔”失控变体，以及野外群体因此惧怕或排斥爱丽丝的写法；明确要求区分普通种特征与少数变体。
- [L36《饅饅來大講堂》本机抄录](../../../../.local/reference/l36-komicolle.txt)：爱丽丝条目写明“所有愛麗絲種都可能變成失去理智，只想交配的雷普魔，失去理智的同時身體能力也會變強”；同段说明雷普魔是许多森林族群灭亡的原因。
- 标准表情库：
  - `expressions/presets/flushed_smile.json`：N034，来源「基本発情顔１グラベース」。
  - `expressions/presets/flushed_tongue.json`：N035，来源「基本発情顔２グラベース」。
  - `expressions/presets/teary_tongue.json`：N036，来源「基本エロ笑顔１グラベース」。
  - `expressions/presets/teary_tongue_b.json`：N037，来源「基本エロ笑顔２グラベース」。
- 动作源：运行资源已有的 `startle`、`turn`、`brace`、`waddle`、`hop`、`idle` 片段；`motion-clips.js` 只做分层与时间混合，不再手写正弦骨骼曲线。

## 文件

| 文件 | 用途 |
| --- | --- |
| `motion-clips.js` | 从已有运行时片段分层生成 `heat_onset`、`heat_loop`、`rapem_onset`、`rapem_loop`；输出运行时轨道格式 |
| `variant/variant.json` | 变体元数据、两种表现的区别、标准表情来源与边界 |
| `preview/` | 网页预览源模板（HTML/CSS/JS） |
| `build_preview.js` | 复制网页模板与变体登记到 `.local/previews/rapem-alice/` |
| `serve.js` | 仅监听 `127.0.0.1:8799` 的只读静态服务 |
| `verify.js` | Node 侧动作采样、表情图集存在性、heat/rapem 姿态差异检查 |
| `render_contact.js` | 软件光栅化联系表，使用标准表情图集 |

## 生成与查看

```sh
node standards/yukkuri-library/drafts/rapem-alice/build_preview.js
node standards/yukkuri-library/drafts/rapem-alice/render_contact.js
node standards/yukkuri-library/drafts/rapem-alice/serve.js
```

打开：

```text
http://127.0.0.1:8799/.local/previews/rapem-alice/index.html
```

集中入口：`.local/previews/index.html`。

## 实现要点

- **表情来自标准素材库**：网页读取 `standards/yukkuri-library/assembled/<species>/textures/` 下的标准图集，不再使用上一版的 `dazed`＋`drool` 自拼贴图。
- **素鸡立**：`heat_onset` 的表情为 `surprise → flushed_smile → flushed_tongue`；动作只叠加 `startle`、`brace`、`waddle`，世界根骨骼没有前后位移，保持站立。
- **雷普魔**：`rapem_onset` 的表情为 `surprise → teary_tongue`；动作叠加 `startle`、`turn`、`brace`、`waddle`、`hop`，并在 3.55–4.30 秒加入向前的根位移，形成跳跃前冲。
- **首尾衔接**：网页在 onset 结束进入 loop 的前 0.6 秒做姿态插值，避免两段拼接跳变。
- **地面约束**：网页把可见网格最低点抬到 y=0 以上；这是展示层保护，不改动作数据。
- **边界**：不演出交配动作，不展示生殖器或裸露。具体动作幅度、镜头和光环是展示设计，不是 Wiki 的固定动作记录。雷普魔是异常状态，不是每只爱丽丝的常态。

## 检查状态

- `node --check`：`motion-clips.js`、`preview/app.js`、`build_preview.js` 通过。
- `verify.js`：爱丽丝、魔理沙、灵梦、橙、帕秋莉均无非有限值；四种标准表情图集均存在；heat 与 rapem 的最大姿态差为 12.19 / 14.79 / 14.17 / 20.10 / 16.14 单位，确认不是同一套动作的强度档位。
- 本机 HTTP：页面、JS、模型、标准表情图集、`pose.js`、`motion-clips.js` 均返回 200。
- **未执行**：浏览器自动化、Minecraft 实机、Gradle 编译。按仓库约定，本机不启动浏览器自动化，也不执行编译；页面交互与最终观感需要手动打开确认。
