# 种族注册与创造栏（6.2）

状态：九种既有实体的注册部分已整理，服务端注册身份、公开别名、尺寸及刷怪蛋／饰品对应已由专项受控场景核对；旧档与客户端展示仍待确认。这是 6.2 的有限子单元，不表示全部种族 switch、注册入口或巨型类已经拆分完成。

`YukkuriVariant` 现在集中保存九种已有种族的资源/注册 ID、对白种族、家油与捕食种标志、自然生成资格、实体宽高与眼高、共用饰品键及刷怪蛋颜色。它只包含字符串、基本值和内部饰品枚举；不引用注册句柄、实体工厂或客户端类。

## 兼容表

下列顺序是既有持久化契约，不得排序。`FamilyDetachedStem` 等现有同步/保存仍使用 4 bit ordinal，本次未调整位宽、ordinal 或迁移存档。

| ordinal | 注册/资源 ID | 宽 × 高 / 眼高 | 对白种族 | 分类 | 自然生成 | 饰品 ID | 蛋底色 / 斑点色 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 0 | reimu | .75 × .53 / .28 | reimu | 通常 | 是 | reimu_ribbon | F1D5AA / B52E36 |
| 1 | marisa | .75 × .53 / .28 | marisa | 通常 | 是 | marisa_hat | F1D5AA / 342D38 |
| 2 | house_reimu | .27 × .23875 / .14 | reimu | 家油 | 否 | reimu_ribbon | D9C39D / B52E36 |
| 3 | house_marisa | .27 × .23875 / .14 | marisa | 家油 | 否 | marisa_hat | D9C39D / 342D38 |
| 4 | chen | .75 × .53 / .28 | chen | 通常 | 是 | chen_hat | F1D5AA / 497647 |
| 5 | alice | .75 × .53 / .28 | alice | 通常 | 是 | alice_headband | F1D5AA / C93D4E |
| 6 | patchouli | .75 × .53 / .28 | patchouli | 通常 | 是 | patchouli_bonnet | F1D5AA / AD87BA |
| 7 | remilia | .80 × .60 / .34 | remilia | 捕食种 | 是 | remilia_bonnet | A8C4E8 / C63642 |
| 8 | flandre | .80 × .60 / .34 | flandre | 捕食种 | 是 | flandre_bonnet | EFCA65 / D03848 |

命名空间均为 `mcyukkuri`；九个刷怪蛋 ID 均为表中种族 ID 加 `_spawn_egg`。两个家油继续共享通常种的饰品物品；螺壳魔理沙仍由 `ModItems.accessoryFor(entity)` 的亚种检查优先返回独立 `marisa_shell`，不把螺壳添加为第十个种族。

## 延迟注册和旧入口

- `ModEntities` 在原位置注册九个旧 public holder，填入私有 `EnumMap`。`holderFor` 只返回句柄，`forVariant` 才在原来的调用阶段解析实体类型；属性注册仍遍历九种，生物分类与追踪范围保持原值。
- `ModItems` 在原声明位置注册七个共用饰品与九个刷怪蛋，填入私有 `EnumMap`；所有旧 public 别名、通用类型与 ID 均保留。刷怪蛋工厂只接收延迟实体 holder，没有注册前调用 `.get()`。
- 元数据不依赖 `ModEntities` 或 `ModItems`。顺序为：纯值 enum → 私有 map → 原位置的别名注册 → 注册事件解析工厂；没有从元数据反向初始化注册类的环。
- 自然生成只把旧七种名单改成元数据筛选。开关、亮度大于 8、可生成地表、空流体、32 格范围当地数量上限、ON_GROUND、MOTION_BLOCKING_NO_LEAVES 及 REPLACE 操作都保留，家油仍未加入该自然生成入口。
- 原有刷怪蛋顺序、捕食种窝/帽子插入位置及其余物品顺序不变；没有把 enum 的存档顺序误当作创造栏顺序。

## 创造栏填充集中（6.2b）

创造栏的内容与筛选条件现在集中在 `ModCreativeTabs`。`HumanWorld` 的原监听回调与 `FreezerModule` 的原注解监听保留在原位置，只委托到集中方法；监听身份、相对时序以及各栏已有物品与顺序均保持不变。人类物品在原版刷怪蛋栏中的既有位置也保留，本单元只整理职责。

这一步没有合并启动入口或注册表。`Facilities`、`Playgrounds`、`FreezerModule` 本来就使用 `ModBlocks` / `ModItems` 等公共注册表；`HumanWorld` 的独立注册表、所有模块初始化次序、配置和事件接线保持原样。物品仍在事件触发时解析，没有提前取出尚未注册的句柄。实际客户端页面展示仍待游戏确认。

## 检查与尚未覆盖的范围

6.2a 从修改前源码提取九项旧值，对新表逐项核对，包括 27 个尺寸/眼高 float 的位表示；12 个实体 holder 和 44 个物品 holder 的公开声明、44 个固定物品 ID 的注册顺序、自然生成谓词原文、螺壳重载入口均保持一致，当时未修改创造栏。四个本单元 Java 文件通过 tree-sitter 语法检查，`git diff --check` 通过。6.2b 另核对迁移前后的完整内容、条件及顺序。临时快照和检查结果只存忽略的 `.local/`。

独立 `SpeciesRegistryGameTests`（suite `species_registry`）用修改前的九行常量作为兼容预期，而非读新元数据自证。远端在注册完成后核对真实实体/物品注册表、public holder 别名、尺寸/眼高、属性、七种自然生成 placement、刷怪蛋默认绑定与反向查询、颜色、ItemStack 组件序列化及 ENTITY_DATA 覆盖，实际构造九种服务器实体，并检查普通/家油共用饰品与螺壳特例。它不启动客户端、不修改世界时间或生成野生群体。

本机未编译、未运行 Gradle、游戏或 P0。既有注册生命周期及组件序列化已由服务端专项受控场景核对；自然生成、实际旧档加载与客户端展示仍需可运行环境确认。

尚未完成的 6.2 内容：其余玩法/模型种族 switch、家庭同步位宽扩展、独立注册表与公共启动入口的职责整理，以及 YukkuriEntity/YukkuriModel 按职责拆分。本次不新增种族，不更改亚种规则、美术、生成概率或玩法平衡；增加新种族仍不能只添加一行表就宣称全部接入完成。
