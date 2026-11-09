# 动作库分级目录

动作库分三级：**基本动作**是最简单的单一身体或附属部位动作，如跑、跳、伸长（`base/`）；**组装动作**按序列或混合复用基本动作，如做操（`composite/`）；**特殊动作**因真实接触、道具、解剖、支撑几何或多角色协作而无法由组装表达，才定制实现。已有与新增动作都按此规则归类；新演出先查基本层与组装层，缺基本动作先补基本层，组装确实做不到才做特殊动作。

[`tiers.json`](tiers.json) 是分级目录的唯一归属，只梳理分类，不移动、重剪或导出片段。`index.json` 继续只承担 `base/`、`composite/` 的路径解析；其读取方 `motion_library.py` 要求组内值都是路径字符串，而且该索引本来也不覆盖 lore、程序包、螺壳变体与解剖动作，因此不把 `tier` 塞进 `index.json`。

## 读取目录

- `clips`：每个制作片段恰好一项。`source` 记录当前制作来源，`tier` 是按新规则得到的目标分类，`family` 供后续动作包按社交、移动、情绪、照护、出生、搬运、帽船和水上归组；来源仍是 lore 不代表目标分级仍是特殊动作。
- `callers`：实际 Java 行为／动作 ID、直接 `playAction`、渲染片段选择或直接轨道采样，以及 `presentation/moments.json` 行 ID。只读取片段元数据（如 `hasClip`、转移字段）不算渲染调用；只有枚举声明也不算调用。`no caller` 表示未找到这些 Java／moments 调用，通用 `/yukkuri action` 命令不计入。该字段不证明完整玩法链已接通。
- lore 片段的 `classification.kind`：`really_base` 是单一基本动作；`really_composite` 用 `base_motions` 里至少两个基本动作按序列或混合组装（可引用 `gaps` 里待补的基本动作）；同一基本动作重复、改节奏、镜像、反向或场景适配的片段归 `near_duplicate_of` 及其目标；`special` 的 `reason` 说明为何组装不能承担。近似关系是结构判断，不声称当前编译器已经复用目标片段。
- `wiki_behaviours`：Wiki 常见行为的合并同义项，只收行为不收静态设定。`source_ids` 可回查来源表，`clips` 是现有或可部分复用的片段，`tier` 是行为所需层级。`exists` 只表示素材覆盖，不表示游戏已接通；`composable_from_existing_base` 表示现有基本动作已够组装；`base_missing` 指向待补基本动作；`needs_special` 的 `missing` 说明仍缺的真实接触或定制段。
- `gaps`：Wiki 与已规划演出的缺口。`existing` 列出可复用的现有片段或本目录中的前置缺口，`missing` 写明新基本动作或组装段要承担什么；计划项不代表已经制作。

目录还覆盖两个多角色程序包、三个螺壳变体和四个由解剖编译器生成且出现在 `YukkuriAction` 的动作。Wiki 条目引用的厕所、家油与捕食种运行片段由检查器从运行登记验证，不重复登记成标准库源片段。

## 检查

在仓库根目录运行（`<py>` 为项目约定的 Python）：

```sh
<py> standards/yukkuri-library/motions/check_tiers.py --check
```

检查器只读核对：所有基本、组装、lore、程序包、螺壳变体及 `YukkuriAction` 动作恰好覆盖一次；JSON 无重复键；层级、来源、lore 分类与调用行有效（`really_composite` 至少列两个基本动作，解剖动作须在 `behaviors/anatomy.json` 的 `exported_actions` 中）；组合及近似引用的片段存在；Wiki 来源 ID 存在于来源表；缺口引用闭合。Java 与 moments 调用做双向核对，目录不得漏掉实际调用，也不得接受不存在的调用或把有调用的片段标成 `no caller`。它同时从运行资源解析 `clips` 与 `behavior_clips`，验证 Wiki 引用但未冒充标准库源的片段确实存在。检查不导出、不启动游戏，也不代替试玩验收。
