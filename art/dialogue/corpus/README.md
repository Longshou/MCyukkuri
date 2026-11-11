# 分轴语料库

本目录是对白制作的机器可读输入。标记 `shared_runtime` 的情境句由导出器直接打包，其余条目供人工选句和写作参考。

## 文件与记录

- `common.json`：跨种族固定词形、称呼、意图和口吻母题。
- `species/<id>.json`：八个现有对白种族的自称、哭声、语尾、称呼、词汇偏好与幼语覆盖。
- `stage.json`：赤油、子油、亚成油、成油的编辑规则；`young` 是现有兼容声线。阶段差异仍由现有数据中的手写句对承担，本文件只供以后构建使用，不是运行时改写器。
- `special.json`：特殊形态、个体声线和即时状态的叠加规则。
- `situation.json`：`speech-tiers.json` 中每个现有事件各一行，另列后续包计划新增的事件。现有事件通过 `events[]` 精确映射到已有行；`s_<event>` 是本项目生成行的 ID 后缀。

每条语料或规则至少有 `id`、`zh`、`ja`、`en`、`basis`、`status`。ID 在同一记录集合内唯一；跨文件引用用 `axis.id` 或 `species.id` 指明作用域。`basis` 是来源 ID 数组；`P` 表示项目原创。来源词形配原创翻译或整句时同时标来源与 `P`，不把原创句冒充作品引文。`status`：`ready` 为已写语料，`rule` 为已由规则覆盖，`candidate` 为待逐句审校的编辑候选，`planned` 为后续事件，`todo` 为尚无语料。

`forms` 保存同一概念的阶段词形；`data` 保存替换表、称呼表、槽位等结构化规则。`zh`、`ja`、`en` 在规则记录中是对规则的三语简述，在语料记录中是台词文本。空文本只允许出现在 `todo` 事件记录中。

## 情境映射与组合

`situation.json.events[]` 的 `event` 必须存在于 `speech-tiers.json`；已有行通过同一 `event` 直接查找。顶层 `situation.json.lines[]` 是可选的项目原创补充句，不改写已有行。`axes` 指明该情境需要组合的语料轴；`axis_refs` 可进一步指向具体规则 ID。

顶层 `planned[]` 使用 `proposed_event`，并提供已有的 `fallback_event` 与待接入的 `future_line_id`。入住宣言和仅在确认呼唤者是母油时使用的隔玻璃回应登记在这里。

组合顺序沿用 Wiki：真实对象与意图、可辨认的关系、个体明确声线、基础种族与阶段、特殊形态覆盖、即时情绪与场景。不能仅凭口音推断智力，不能凭体型推断年龄，不能对未认出的对象使用亲属称呼。

## 检查

```powershell
<py> scripts/check_speech_corpus.py --check
```

检查 JSON、必填字段、全库 ID 唯一、情境事件与现有对白事件一致、物种文件名、已有/后备事件及现有行 ID 后缀映射。`--check` 只读，不生成对白。
