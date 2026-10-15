# 四种实体与标准动作库接入

> 2026-09-13 新增橙、爱丽丝、帕秋莉，当前共七种实体；新种族、长发接地、饰物与幼体同步的本轮证据见 [种族实装记录](SPECIES_IMPLEMENTATION.md)。下文四种实体的数量保留为此前轮次记录。

> 后续更新（2026-09-12）：本页保留对应轮次的验证边界。用户随后接受 EULA；专服、多人、成长家庭、家油巢卵及基础伤病的现行实现和证据见 [核心玩法记录](CORE_GAMEPLAY.md)。

标准灵梦、魔理沙和两种家油的实体、生成蛋、模型及动作已接入。当前生活与互动见 [P1–P2 实现记录](P1_P2_IMPLEMENTATION.md)。下述 P0 数量是此前基础验证结果。2026-09-12 的 P0 验证通过本机构建、资源脚本、185 项引擎断言及真实客户端加载，录制了 19 个动作并验证资源重载。专服、双客户端晚加入、区块卸载与专服重启仍待验收，详见 [P0 验收记录](P0_VALIDATION.md)。CI 已加入资源和 GameTest 检查，本轮未运行远程 CI。

[当前动作预览](../art/source/standard-runtime/preview.html)可由用户直接打开。它使用本次导出的模型数据和独立渲染器，不能代替游戏运行验收。家油沿用 v9.1 导出，造型确认状态仍为待确认；本次没有修改标准造型或批准指纹。

## 生成与尺寸

| 实体 ID | 生成蛋 | 碰撞箱宽 × 高（格） | 眼高（格） |
| --- | --- | --- | --- |
| `mcyukkuri:reimu` | `mcyukkuri:reimu_spawn_egg` | 0.75 × 0.53 | 0.28 |
| `mcyukkuri:marisa` | `mcyukkuri:marisa_spawn_egg` | 0.75 × 0.53 | 0.28 |
| `mcyukkuri:house_reimu` | `mcyukkuri:house_reimu_spawn_egg` | 0.27 × 0.23875 | 0.14 |
| `mcyukkuri:house_marisa` | `mcyukkuri:house_marisa_spawn_egg` | 0.27 × 0.23875 | 0.14 |

四种刷怪蛋同时加入“油库里世界”和原版“刷怪蛋”创造分类，中文搜索“刷怪蛋”或角色名即可查找。它们使用原版蛋形模型，以红／黑斑点区分种族、浅／深底色区分标准种与家油；右键方块生成对应实体。保留原有 `reimu`、`marisa` 注册 ID，种族由实体类型决定；原存档中的这两类实体会采用新模型。P1 已为普通灵梦、魔理沙添加干燥草地自然生成；两种家油仍需刷怪蛋或 `/summon` 创建。

也可以通过命令取得，例如 `/give @s mcyukkuri:house_reimu_spawn_egg 1`；其余物品 ID 见上表。GameTest 已验证两种创造分类包含四种生成蛋，并实际调用右键方块逻辑检查生成类型和数量扣除；物品栏搜索、颜色和手动放置方向仍待人工核对。

模型按 16 单位／格显示，碰撞箱取身体宽高，帽子和蝴蝶结计入显示范围。四种实体最大生命值 10、移动速度属性 0.18、跨步高度 1 格。短小家油在台阶、窄道、水边及方块侧面生成的表现待实机核对。

## 生活与互动

公共实现 `YukkuriEntity` 只使用服务器可用类，优先处理落水逃生、受惊逃跑及显式动作，再协调生活、持食吸引、社交与漫游。`YukkuriLife` 保存持续生活和住所，`YukkuriSocial` 协调玩家与同伴反应；行为阶段、口中食物、饰物和对白同步给客户端。

原来的即时恢复和饱腹冷却已由接近、取食、咀嚼、吞咽和收尾替代。旧 NBT 字段仅保留迁移兼容；新生活数据、人格和记忆可经正常存档及运输保留。食物标签继续为 `mcyukkuri:yukkuri_food`，取食遵守 `mobGriefing`。

具体生存操作、数值、保存规则及验证边界见 [P1–P2 实现记录](P1_P2_IMPLEMENTATION.md)。成长、繁殖、亲属关系和昼夜生态扩展尚未实现。

## 动作入口

拥有权限等级 2 的玩家可使用 `/yukkuri action <目标> <动作>`，动作名支持补全。例如，先生成一只家油再让附近家油做体操：

```mcfunction
/summon mcyukkuri:house_reimu ~ ~ ~
/yukkuri action @e[type=mcyukkuri:house_reimu,distance=..8] taiso
/yukkuri action @e[type=mcyukkuri:house_reimu,distance=..8] idle
```

| 用途 | 动作名 |
| --- | --- |
| 常态与移动 | `idle`、`waddle`、`flee` |
| 伸展、弯曲与体操 | `stretch_shape`、`side_bend`、`stretch`、`exercise_vertical`、`exercise_sides`、`taiso` |
| 其他表现 | `puff`、`brace`、`startle`、`turn`、`hop`、`press` |
| 部件控制 | `anatomy_mamu_extend`、`anatomy_mamu_retract`、`anatomy_peni_extend`、`anatomy_peni_retract` |
| 世界观动作（生活） | `eat`、`shiawase`、`drink`、`sleep`、`wake` |
| 世界观动作（社交与情绪） | `nuzzle`、`sing`、`sway`、`push`、`greet`、`tremble`、`droop`、`look_around`、`shake` |
| 世界观动作（分娩） | `brood`、`labor`、`midwife`（魔理沙）、`postpartum`、`first_wake`、`bereaved` |
| 魔理沙用辫子摆弄帽子 | `hat_off`、`hat_hold`、`hat_on`、`hat_off_self`、`hat_on_self`，水生大帽版本加后缀 `_aq` |
| 水生魔理沙帽船 | `boat_drift`、`boat_steady`、`boat_call`、`boat_brood`、`boat_labor`、`hat_cradle`；新增 `boat_paddle`、`boat_gather`、`boat_eat`、`boat_drink`、`boat_sleep`、`boat_wake`、`boat_board`，触发见[水边生活](AQUATIC_MARISA_IMPLEMENTATION.md) |
| 螺壳魔理沙专用 | `shell_retract`（其他形态显示待机） |

世界观动作随动作修正资源下发，设计、依据与触发方式见 [世界观动作](../standards/yukkuri-library/motions/variants/lore/README.md)。除命令外，服务器会按行为自动选择这些动作：进食、饮水、睡眠、擦脸、唱歌、听歌、相扑、寻找家人等行为各有对应动作；问候、害怕、失饰、被擦洗、玩具等短反应在空闲时播放；没有饰品时空闲即低落。孕期母体安胎，胎生时随收缩用力，魔理沙用辫子摘下自己的帽子翻过来放在出口前接生、扶着帽檐打气，之后再用辫子戴回，产后休息，新生儿初醒。帽船只属于水生魔理沙：漂流、稳船、向岸呼唤，在船上安胎、分娩和睡觉，帽中幼体轻摇。螺壳魔理沙受惊时原地缩壳、不逃跑，缩壳期间受到的伤害减半。片段只编入适用的形态，其他形态播放待机。

动作时长与循环标记取标准库原值。循环演示持续到显式 `idle`、切换或受伤；`stretch_shape`、`startle`、`turn` 为单次动作，结束后恢复 AI。伸出动作保持末帧，收回结束回到常态。两个部件互斥，默认不显示；已经收回时再次执行收回命令不会闪现伸出首帧。伸出途中执行对应收回命令，会按当前进度回拨收回时间。

手动演示暂停寻路，因此命令触发的 `waddle`、`flee` 在原地播放；自动行为触发时由服务器寻路移动实体。动画不驱动碰撞箱或伤害判定。保留跳动的视觉纵向偏移；移除世界骨骼的水平位移，并去掉 `flee` 中预览场景的固定转向，避免与服务器朝向冲突。

## 模型、骨架与资源

`YukkuriRenderer` 只在客户端注册。`YukkuriModel` 读取 `assets/mcyukkuri/yukkuri/<实体 ID>.json` 的三角面、骨架、双骨骼顶点绑定及数字关键帧，使用原版实体材质提交几何。当前实现不依赖 GeckoLib，家油网格不经过只识别方块的旧几何加载器。

标准模型保留原始骨架和动作曲线。家油按宽、高、深分别适配支点和位移，身体共享顶点及新头发按高度在相邻分段骨骼间混合，避免把侧弯简化成整体旋转。现有饰品尽量对应标准骨骼，手部和伸缩部件保留家油挂点。家油没有标准鼓腮的独立膨胀面，因此 `puff` 映射为身体宽度、深度膨胀，配合生气表情。P0 已录制其实际客户端近景并抽查关键帧，未发现明显挂接错误；造型仍待用户确认。

每帧先恢复常态，再采样当前动作，防止稀疏曲线遗漏默认隐藏状态。三角面按原始外法线统一绕序，混合变形后重新计算面法线。P0 已检查动作切换后的常态顶点恢复，实际资源重载后模型对象更新且四实体继续显示。损坏资源导致的重载失败尚未测试。

选定源、原样 `.bbmodel` 快照、适配骨架、纹理、19 个动作和目标路径均登记于 [runtime-sources.json](../art/runtime-sources.json)。快照和适配配方见 [接入素材说明](../art/source/standard-runtime/README.md)。旧 `art/source/reimu/`、`marisa/` 历史基线已删除。

## 指定环境验收

1. 在 CI 或指定环境执行 `python scripts/validate_entity_assets.py` 与 Gradle `build`；核对 Jar 包含四份模型、44 张表情贴图、四种生成蛋、中英文语言和食物标签。
2. 客户端和专用服务器分别加载。使用四种生成蛋与四个 `/summon` ID，检查方向、身体相对尺度、贴图、地面接触、帽饰遮挡和碰撞箱。
3. 测试空闲走动、持食吸引、右键喂食、地面单件及堆叠食物、饱腹拒食、关闭 `mobGriefing`、受伤逃跑及休息中断。
4. 对四种实体逐一播放表中 19 个动作。重点查看家油侧弯／体操中身体接缝与头发饰品，鼓腮表现，跳动触地，以及伸出、半途收回、部件切换、`idle`、受伤、死亡和重载后的隐藏状态。
5. 检查多人同时观察、晚加入、重新加载区块与存档、F3+T 资源重载。记录卡顿与动作同步情况，按实际结果调整网格或采样成本。

记录代码提交或工作副本指纹、依赖版本、环境及截图／视频。当前已执行项和未执行项见 [P1–P2 实现记录](P1_P2_IMPLEMENTATION.md)，此前基线见 [P0 验收记录](P0_VALIDATION.md)，不能把其中受控自动检查写成完整人工验收。任何旧截图、基础工程构建记录或标准造型确认都不能证明新改动通过。
