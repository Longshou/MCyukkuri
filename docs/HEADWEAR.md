# 头饰第二生命与制裁（世界观包 6、包 7 的“无头饰误认”）

依据：komicolle「饅饅來大講堂」No.11518829（头饰随身体成长并能自我修复；饅会用舔把碎掉的头饰聚在一起保存等待修补；失去头饰会被社会性抹杀，本人也承受极大压力；“制裁”就是杀不能慢慢来的人或饅，能否慢慢来取决于有无头饰、是否完整）、回帖“头饰以自我感觉为养分成长”，No.11518847（没有头饰就认不出是同类）。裁决见 C7、C11、Q3。具体数值都是项目设计。

代码：`headwear/Headwear.java`（状态、成长、损坏、修复、碎片收集目标）、`headwear/Sanction.java`（制裁执行目标与族规接口）、`item/AccessoryShardItem.java`、`client/HatDamageMask.java`。状态只存在主人身上，物品和帽船只带主人令牌，所以损坏与尺寸在被摘走、被偷、当船时都保持不变。

## 状态

| 状态 | 含义 | 对 `unease` 的持续压力（每分钟） |
| --- | --- | --- |
| `worn` | 戴着；损坏 0 完好、1 轻损（毛边缺口）、2 撕裂（缺一大块） | 0／0.5／1（`accessory_torn`） |
| `off` | 被摘走、被偷，或水生魔理沙当帽船用（坐在自己帽船里不算失饰） | 3（`no_accessory`，帽船中为 0） |
| `shattered` | 碎了，碎片散落还没收齐 | 4（`accessory_shattered`） |
| `reassembling` | 碎片已收齐，放在头上慢慢长合（仍不算完整，同族仍认不出） | 1.5（`accessory_mending`） |
| `lost` | 烧掉、碎片被毁或三天没找到任何碎片 | 5（`accessory_gone`） |

压力由 `MindLife.sample` 读取 `Headwear.uneasePerMinute()`；碎裂瞬间另加 12（烧毁 20），并沿用 `lost_accessory` 事件（+15、台词“帽子不见了”）。

## 损坏、修复、碎裂

- 来源（`Headwear.struck`，在 `YukkuriEntity.hurt` 中调用）：打到头顶 70%、捕食种咬 45%、其他 ≥3 的打击 15% 损坏 1 级（≥5 为 2 级）；火每次 35% 损坏 1 级，烧到第 3 级直接烧没（无碎片）；爆炸 2–3 级；落石压 2 级；制裁时每口 50%。
- 自我修复：戴着时按心情累积，安心（`unease<35`）每 tick +2，不安 +1，甜化或非油症时停止。轻损 6000、撕裂 12000（安心约 2.5 分钟／5 分钟一级）。橙汁不能修头饰（C4）。
- 碎裂：超过撕裂即碎成 2–3 块 `accessory_shard`，带主人与碎片令牌，地上可存在 3 天。主人会走过去舔起来（`shard_seek`／`shard_lick`）；玩家也可以捡走，对主人右键交还（凑齐时增加对玩家的信任）。碎片被火、岩浆等毁掉，或三天没有进展，头饰就算永久失去。
- 拼回：碎片凑齐后放在头上，以“拼回”外观显示，安心时每 tick +2、其余（非油症除外）+1，到 12000 长合，恢复为撕裂级头饰，再按上面的修复速度变好，并触发 `returned_accessory`（-20 不安）。

## 随身体成长

`Headwear.fit` 记录头饰自身的大小。戴着且主人不甜化时，头饰会长到和身体一致（安心时约 50 秒从 0.6 长回 1.0）；摘下、当船、甜化或非油症时不长。所以被摘走很久后还回来的头饰、长期不安的个体，头上的饰物看起来偏小（最小 0.6 倍，以饰物底部为中心缩放）。头饰不会大于身体。

## 失饰的社会后果与无头饰误认

- 认不出：`YukkuriLife.canRecognize`（金章观察者例外是**项目原创**规则，讲堂帖没有依据；帽船主人在自己帽船里仍可辨认）。亲子喂食、伴侣求偶、问候、贴贴、照料都因此中断，这是原有逻辑。
- 新增：亲友只保护认得出的受害者（`GroupInteractionGoal.witnessedAttack`），所以失饰个体挨打时亲属不会出手。
- 新增：野外的凶悍成体（攻击性 ≥65，或自私 ≥65 且自制 <65）会欺负认不出的无主失饰个体（相扑／欺负回合，被欺负者 `unease` +6、哭）。
- 聚落内：原有的冷落 → 驱逐／嗯嗯奴隶链不变（`SettlementSociety`），被驱逐后仍在聚落成员视野里出现的失饰者会被制裁（见下）。
- 判定接口（给“同类相食”）：`Headwear.isUnrecognizedHatless(observer, target)`：目标没戴饰物且观察者认不出（亲属同样认不出）；`Headwear.fairGame(observer, target)`：同上且目标是自由个体（野生、流浪、聚落成员）且不是捕食种，宠物和有主个体受保护（C11）。

## 制裁（杀）

- 执行者：自由成体、健康、非捕食种／家油、凶悍（攻击性 ≥55，或自私 ≥65 且自制 <65）；目标在聚落中时只由同一聚落的正式成员执行；还认得出目标的亲属不参与。每案最多两名执行者。
- 自动理由：同聚落（或无聚落）的非油症狂乱个体（每次扫描 1/3）；同聚落被驱逐的失饰者再次出现（1/4）。扫描每 10–15 秒一次，范围 8 格，需要视线。
- 过程：走到 3 格内宣告（鼓气、生气，`sanction_declare`；目标哭求 `sanction_plea`，不安 +10），然后追咬，每 25 tick 一口 3 点伤害，咬伤是撕裂伤（`InjuryModel.kind`），馅会流出；直到目标死亡、逃出 24 格、执行者受重伤或惊慌，或 2400 tick。
- 族规接口：`Sanction.request(target, reason, accuser)`，由聚落规约（包 12）判定偷窃、杀同族、冒犯族长等之后调用；`Sanction.judged(target)` 读当前案件。宠物、有主、家油和捕食种不会被制裁。配置 `life.sanction`（默认开）。

## 美术

制作源 `art/props/hat-damage/recipe.json`，导出 `scripts/export_hat_damage.py` → `yukkuri_presentation/hat_damage.json`、`models/item/accessory_shard.json`；预览 `art/props/hat-damage/preview.py`（输出 `.local/previews/hat-damage/`）。规则作用于各物种现有饰物几何：轻损为前侧下缘缺口加暗色毛边，撕裂再缺去上部一大块，拼回为完整外形加三道暗色裂缝，碎片物品是主人饰物对应的那一块。被摘下的饰物物品按 `HatDamage` 显示同样的损坏。帽船船体仍显示完好（未改水生代码）。

## 状态键

`accessory.state`（写 `shatter|burn|lose|gather|knit|restore`）、`accessory.damage`（写 0–3，3 为碎裂）、`accessory.fit`（写 0.6–1）、`accessory.shards`、`accessory.detail`、`accessory.sanction`（写理由即对该个体发起制裁，测试用）。日志前缀 `[headwear]`、`[sanction]`。
