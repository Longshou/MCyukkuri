# 素鸡立与雷普魔爱丽丝（待确认草稿）

本目录实现两项本机可验证的内容：

1. **通用“素鸡立”发情期演出**：进入发情期时的行为演出，从警觉、热感上升、坐立不安、驱力增强到冲动冲刺，再进入持续循环。
2. **雷普魔爱丽丝状态变体**：爱丽丝的失控发情状态演示，套用同一套动作但提高颤抖、抽动与前冲幅度。

这是**待确认草稿**，不是已保存标准，也未接入游戏运行资源。

## 来源与边界

- [Wiki · 爱丽丝](../../../wiki/hakkei-reference/lore/species/alice/README.md)：记录“雷普魔”失控变体，以及野外群体因此惧怕或排斥爱丽丝的写法；明确要求区分普通种特征与少数变体，不能把变体推广为每只爱丽丝的潜在本性或行为必然性。
- [L36《饅饅來大講堂》本机抄录](../../../../.local/reference/l36-komicolle.txt)：爱丽丝条目写明“所有愛麗絲種都可能變成失去理智，只想交配的雷普魔，失去理智的同時身體能力也會變強”；同段说明雷普魔是许多森林族群灭亡的原因，爱丽丝种因此处于被歧视和杀害的窘境。
- 本项目 [`docs/CONSUMABLES_AND_BIRTH.md`](../../../../docs/CONSUMABLES_AND_BIRTH.md) 与 `FamilyLife.sexDrive` 已有“兴奋药增加有限发情时间”“只增加繁殖驱动力”的运行时状态；本演出是这一状态的展示层，不改动游戏数值。

演出只表现 wiki/L36 记录的三项行为线索：

- **失去理智**：失神白眼、流涎、视线游移、头部抽动；
- **只想交配**：持续扫视、向目标方向倾身、来回踱步、冲动前冲；
- **身体能力变强**：更快的起跳/前冲与更剧烈的颤抖。

不演出交配动作，不展示生殖器或裸露，也不把“雷普魔”写成普通爱丽丝的默认行为。具体动作幅度、镜头、光环和表情拼合属于展示设计，不是 wiki 对固定动作的描述。

## 文件

| 文件 | 用途 |
| --- | --- |
| `build_variant.js` | 从游戏现有 `dazed.png` 的白眼与 `drool.png` 的流涎嘴部拼合 `variant/heat.png`；不新增器官或几何 |
| `variant/variant.json` | 变体元数据、来源引用和展示边界 |
| `heat-clips.js` | 通用发情期动作生成器；只读取各模型共有的身体/头部/手/臀/头发骨骼，缺失骨骼自动跳过 |
| `preview/` | 网页预览源模板（HTML/CSS/JS），由 `build_preview.js` 复制到 `.local/previews/rapem-alice/` |
| `build_preview.js` | 生成本机预览入口与 README |
| `serve.js` | 仅监听 `127.0.0.1:8799` 的只读静态服务 |
| `verify.js` | Node 侧动作采样检查（不启动浏览器） |
| `render_contact.js` | 软件光栅化静帧联系表，用于离线检查姿态 |

## 生成与查看

```sh
node standards/yukkuri-library/drafts/rapem-alice/build_variant.js
node standards/yukkuri-library/drafts/rapem-alice/build_preview.js
node standards/yukkuri-library/drafts/rapem-alice/serve.js
```

打开：

```text
http://127.0.0.1:8799/.local/previews/rapem-alice/index.html
```

集中入口：`.local/previews/index.html`。

## 实现说明

- **通用动作**：`heat-clips.js` 按骨骼名生成 `heat_onset`、`heat_loop`、`rapem_onset`、`rapem_loop` 四段运行时片段，使用与 `preview/review/pose.js` 相同的轨道格式。爱丽丝、魔理沙、灵梦、橙、帕秋莉均通过采样检查。
- **模型状态**：雷普魔不是新模型文件，而是爱丽丝基础模型的异常状态；页面通过换用高强度片段、热感光环与失神表情表现，避免复制或改写已确认的角色几何。
- **表情**：`heat.png` 由项目既有的 `dazed.png`（白眼/失神）和 `drool.png`（张口/流涎）在 256×256 图集上拼合，保留 `neutral.png` 的皮肤与发箍。它属于展示用草稿，尚未登记为标准表情。
- **地面约束**：网页在每帧姿态后把可见网格的最低点抬到 y=0 以上，避免头部/身体倾斜时穿过地面；这是展示层保护，不改骨骼动作数据。
- **生命周期位置**：演出对象是成体的生殖期状态，不是新的生命阶段；页面注明雷普魔是异常分支，可影响群体对爱丽丝的态度与族群存续。

## 检查状态

- `node --check`：`heat-clips.js`、`preview/app.js`、`build_variant.js` 通过。
- `verify.js`：五种运行模型的动作采样无非有限值；见终端输出。
- 本机 HTTP：HTML、JS、爱丽丝运行模型、热感贴图、`pose.js`、`heat-clips.js` 均返回 200。
- **未执行**：浏览器自动化、Minecraft 实机、Gradle 编译。按仓库约定，本机不启动浏览器自动化，也不执行编译；页面交互与最终观感需要手动打开确认。
- 预览产物在 `.local/previews/rapem-alice/`，不提交；本目录为草稿源，未登记为已确认标准。
