# 种族注册与行为

油库里种族的注册元数据集中在 `YukkuriVariant`，实体类型、刷怪蛋、饰品物品和创造栏都由它派生；本篇记录注册事实、各种族的行为差异，以及各自的接入边界。

代码：`entity/YukkuriVariant`、`entity/SpeciesTraits`、`life/SpeciesLife`、`registry/ModEntities`、`registry/ModItems`、`registry/ModCreativeTabs`、`dialogue/SpeechAddress`、`dialogue/SpeechCount`；资源登记：`art/runtime-sources.json`。

## 种族元数据

`YukkuriVariant` 保存每个种族的资源/注册 ID、对白种族、家油与捕食种标志、自然生成资格、实体宽高与眼高、共用饰品键及刷怪蛋颜色。它只含字符串、基本值和内部饰品枚举 `Accessory`，不引用注册句柄、实体工厂或客户端类。

下列顺序是持久化契约：家庭与离身茎的同步数据用 4 bit ordinal（每个孩子 `ordinal << (i*4)`，最多 16 种），**不得重排、只能在末尾追加**。

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
| 9 | myon | .75 × .53 / .28 | myon | 通常 | 是 | myon_hairband | DDE2E6 / 2C2A30 |

- 命名空间均为 `mcyukkuri`；刷怪蛋 ID 为 `<种族 ID>_spawn_egg`（`spawnEggId()`）。
- 普通种共用 0.75 × 0.53 格身体碰撞箱；帽饰、蝴蝶结、双尾等属显示几何，不计入碰撞箱。捕食种 0.80 × 0.60，家油 0.27 × 0.23875。尺寸与眼高一律以 `YukkuriVariant` 为准。
- 家油与对应的普通种共享饰品物品；螺壳魔理沙由 `ModItems.accessoryFor(entity)` 的亚种检查优先返回独立的 `marisa_shell`，不是单独的种族。
- 实体属性：最大生命 10、移动速度 0.18、跨步高度 1 格（`YukkuriEntity` 属性构建；妖梦的属性经 `rare/MyonLife.attributes(variant)` 统一构建）。生物分类 `CREATURE`，客户端追踪范围 8。
- 十个种族之外，`hat_boat`、`material_drop`、`yukkuri_remains` 是非油实体，不进 `YukkuriVariant`。

## 注册结构

- `ModEntities` 以 `register(YukkuriVariant)` 为每个种族注册实体类型，句柄放入私有 `EnumMap`；`holderFor` 只返回句柄，`forVariant` 才解析实体类型。属性注册遍历全部种族。
- `ModItems` 为每个 `Accessory` 注册饰品物品、为每个种族注册 `DeferredSpawnEggItem`，同样放入私有 `EnumMap`；刷怪蛋工厂只接收延迟实体 holder，注册前不调用 `.get()`。
- 元数据不依赖 `ModEntities` / `ModItems`：纯值 enum → 私有 map → 别名注册 → 注册事件解析工厂，不存在从元数据反向初始化注册类的环。
- 自然生成：`ModEntities.registerSpawnPlacements` 对 `naturalSpawn()` 为真的种族注册放置规则（`NATURAL_SPAWNS` 开关、亮度 > 8、脚下为 `ANIMALS_SPAWNABLE_ON`、空流体、32 格内同类数量低于 `LOCAL_SPAWN_CAP`；`ON_GROUND`、`MOTION_BLOCKING_NO_LEAVES`、`REPLACE`）。家油不走该入口。群系与权重等规则见各种族篇（妖梦见 [RARE_FORMS.md](RARE_FORMS.md)，捕食种见 [PREDATOR_IMPLEMENTATION.md](PREDATOR_IMPLEMENTATION.md)）。
- ordinal 只表示存档顺序，不等于创造栏顺序。新增种族时除加枚举行外，还要接入 `ModEntities` / `ModItems` 的公开别名、`ModCreativeTabs`、对白、模型与 `art/runtime-sources.json`。

## 创造栏

创造栏内容与筛选集中在 `ModCreativeTabs`：

- 自有分类 `yukkuri_world`（`itemGroup.mcyukkuri`，图标为观察手册）：手册、各种族刷怪蛋、捕食种巢与帽饰、道具与设施、油粮和消耗品等。
- 原版“刷怪蛋”分类（`CreativeModeTabs.SPAWN_EGGS`）：十个种族的刷怪蛋，经 `addVanillaSpawnEggs` 加入。
- 人类 NPC 蛋、馒头、活体袋、徽章与手帖、工厂设备由 `HumanWorld` 的原监听回调委托给 `ModCreativeTabs.addHumanContents`，同时加入上述两个分类；冷冻柜由 `FreezerModule` 的监听委托给 `addFreezerContents`，只加入 `yukkuri_world`。
- `Facilities`、`Playgrounds`、`Consumables` 的物品表由 `yukkuri_world` 遍历加入。物品在事件触发时解析，不提前取出尚未注册的句柄。

## 动作入口

`/yukkuri action <目标> <动作>`（权限等级 2，动作名可补全）可让任意油播放指定动作。完整动作表、各动作适用形态与试玩场按钮见 [MOTION_MUSEUM_ACTIONS.md](MOTION_MUSEUM_ACTIONS.md)。命令要点：

- 动作时长与循环标记取标准库原值。循环动作持续到显式 `idle`、切换或受伤；`stretch_shape`、`startle`、`turn` 为单次动作，结束后恢复 AI。
- 伸出类部件动作保持末帧，收回结束回到常态；两个部件互斥、默认不显示，已收回时再次收回不会闪现伸出首帧，伸出途中收回会按当前进度回拨。
- 命令触发的演示暂停寻路，`waddle`、`flee` 原地播放；自动行为触发时由服务器寻路移动实体。动画不驱动碰撞箱或伤害判定。
- 世界观动作随动作修正资源下发，设计与触发见 [世界观动作](../standards/yukkuri-library/motions/variants/lore/README.md)。

## 模型与动作资源

- `YukkuriRenderer` 只在客户端注册；`YukkuriModel` 读取 `assets/mcyukkuri/yukkuri/<实体 ID>.json` 的三角面、骨架、双骨骼顶点绑定及关键帧，使用原版实体材质提交几何，不依赖 GeckoLib。
- 标准模型保留原始骨架与动作曲线。家油按宽、高、深分别适配支点与位移，身体共享顶点与新头发按高度在相邻分段骨骼间混合；家油没有标准鼓腮的独立膨胀面，`puff` 映射为身体宽深膨胀并配合生气表情。
- 每帧先恢复常态再采样当前动作，防止稀疏曲线遗漏默认隐藏状态；三角面按原始外法线统一绕序，混合变形后重算面法线。
- 选定源、原样 `.bbmodel` 快照（`art/source/standard-runtime/<种族>/`）、适配骨架、纹理、动作与目标路径、逐文件 SHA-256 均登记于 [runtime-sources.json](../art/runtime-sources.json)；快照与适配配方见 [接入素材说明](../art/source/standard-runtime/README.md)，[动作预览](../art/source/standard-runtime/preview.html)可直接打开。

## 橙、爱丽丝、帕秋莉

三者共用标准身体动作，各自的挂点适配如下：

| 种族 | 适配方式 |
| --- | --- |
| 橙 | 绿帽随头部、耳朵随头发、双尾随臀部骨骼；没有独立的情绪摆尾 |
| 爱丽丝 | 短侧发与完整发箍各有挂点；发箍花边随饰物整体摘下 |
| 帕秋莉 | 增加三个长发接触骨骼（`runtime_contact_hair`、`runtime_contact_hand_left`、`runtime_contact_hand_right`）及逐顶点双骨混合 |

帕秋莉长发直接套用原动作时，侧弯和下压会穿入地面。运行适配在 `scripts/runtime_animation.py`（`fit_grounded_hair`）：上端 Y ≥ 8.48 的顶点保持原绑定，低处逐渐增加接触权重；每 0.02 秒求取所需竖直补偿并变换回父骨空间。需要补偿的动作为 `side_bend`、`puff`、`brace`、`exercise_sides`、`press`、`taiso`，最大约 1.235 模型单位；静态形状不变。

三种饰物 `chen_hat`、`alice_headband`、`patchouli_bonnet` 由运行模型的对应部件导出：爱丽丝花边、帕秋莉新月和帽褶包含在同一件饰物里。导出配方在 `art/props/runtime-peripheral/recipe.json`（种族到饰物的映射），显隐选择在 `art/props/care-facilities/recipe.json`。摘取、归还、掉落与持物缩放按所属角色处理。妖梦的 `myon_hairband` 同属 `Accessory` 枚举。

生活、睡眠、清洁、受伤、搬运、成长与亲子逻辑与其他普通种共用实现；嘴部持物位置、咀嚼表现、饰物显隐、茎生母体与幼体挂点各有独立配置。专属对白见 [DIALOGUE_IMPLEMENTATION.md](DIALOGUE_IMPLEMENTATION.md)。

## 种族行为差异

各种族的行为差异全部走一张系数查表，共享 AI 读系数，不按种族另写 AI。

依据 komicolle「饅饅來大講堂」11518905（物种档案）。`SpeciesTraits` 按 `YukkuriVariant` 取系数；家油与捕食种取中性行，保留现有生态。每只个体的计数与说话钩子在 `SpeciesLife`，已登记到状态中枢（`species.*`）。系数是项目设计，按讲堂形容词定量。妖梦见 [妖梦与稀有变体](RARE_FORMS.md)。

| 种族 | 系数（相对中性 1） | 说明 |
| --- | --- | --- |
| 灵梦 | 颊囊 2 份；独唱权重 ×1.5（且常对着空气唱）；亲昵需求增长 ×1.3；觅食间隔 ×1.25；家务频率 ×0.8；喂养孩子的饥饿差 8（其余 15） | 溺爱孩子、自私懒惰，让配偶多干活。**颊囊是灵梦独有**（C5）。 |
| 魔理沙 | 相扑挑战 ×2；挑衅/欺负阈值 ×1.5；对战强度 ×1.1；家务 ×0.9 | 大胆自大。探险队长本来就只由魔理沙担任（`Expedition`）。 |
| 爱丽丝 | 家务频率 ×1.5 | 持家。 |
| 帕秋莉 | 易吐；体力消耗 ×1.25（等效体力上限约 80%）；对战强度 ×0.8；挑衅 ×0.8、相扑 ×0.5；可数到 9 | 吐馅见下。 |
| 橙 | 移速 ×1.2（属性修饰）；被欺负权重 ×1.5；对战强度 ×0.75 | 敏捷、打不赢。体型（娇小）属美术，未改碰撞箱。 |
| 妖梦 | 觅食间隔 ×0.7；家务 ×1.2；挑衅 ×1.2；相扑 ×1.8；被欺负权重 ×0.8；对战强度 ×1.4 | 移速另由 `rare/MyonLife` 的属性适配处理；体力消耗系数 0.9 尚未生效，见[待办](TODO.md#源码核查发现的问题)。 |

### 吐馅（仅帕秋莉）

`SpeciesLife.vomit` 调用 `BodyLife.vomit`，冷却 3600 tick；呕吐不扣血，最多消耗 2 精力，吐后压力 +3 并流泪。触发：连吃（两餐间隔不足 600 tick，或饿得低于 15 就吞）40%；受惊起步 25%；压力 ≥60 时每 400 tick 20%。

### 颊囊（仅灵梦）

- 家务拾取：捡起食物后，在 2.5 格内继续捡同种食物，直到 2 份；放储粮点时逐份由嘴接触放入（`HabitatLife.depositCarried`）。
- 觅食出行：灵梦每趟的装载上限 +2；其余种族沿用 `ForageTrip` 的 4～6 份上限。

### 接入的台词事件

- 称呼（`SpeechAddress`）：对 6 格内可见的玩家和居民（按信任/恐惧/饲主/态度选“饲主先生/大哥哥/大姐姐/人类先生/臭老太婆/糞老頭”；人类的性别与年长由 UUID 稳定派生，因为游戏没有这个数据）、对家人（母/父/伴侣/孩子/兄姊弟妹/族长）、对看到的捕食种（蕾米莉亚/芙兰）。同一对象 2400 tick 内不重复。
- 数数（`speakCount`）：放储粮后（40%，数当前储量）；偶尔数身边孩子、同伴、地上的食物（饿时）。
- 自述（`narrate_*`）：见 [原版台词接入](DIALOGUE_IMPLEMENTATION.md)。

### 状态读数

状态键 `species.cheek_capacity / cheek_load / vomits / chores / songs / nuzzles / move_speed / speech_hooks`，用 `/data get entity <e> YukkuriStatusView.species` 读取。
