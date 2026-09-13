# 认知能力、教学提交与出生继承

本模块新增独立认知能力，服务端以 `cognition/CognitionData` 的世界存档为权威。`Cognition` 提供当前能力、教学资格、课堂提交和出生接口；个性六维、等待指令、感官和饰品识别继续使用各自原存储。专题验收见[认知检查](validation/cognition/README.md)。

## 来源核读

2026-09-13 只读核对本机 `E:/yukkuri/Eight景U-ModLab/` 下的 Mod 源码和 `analysis/decompiled/` 原生反编译文件。下表区分原生属性、Mod 情景规则与本项目选择，不把它们混称为原游戏完整还原。文件 SHA-256 另存验收目录。

| 来源 | 核读结果 |
| --- | --- |
| `analysis/decompiled/menu.cs:160` | `かしこさ` 是独立的 `int[101]`；另有 `予想かしこさ`，不能将观察估计当成真实能力 |
| `analysis/decompiled/yu_base.cs:621–628` | 原生当前能力限制在 0–1000 |
| `analysis/decompiled/save.cs:201–225,409` | 保存遍历公开整数数组，另见具名 `かしこさ` 保存项；原生能力属于持久数据 |
| `analysis/decompiled/menu.cs:11967–12079` | 野生幼体入口 `野良ゆステ子` 使用 `30 + Random.Range(0,71) + 野良かしこさ補正/4`；野生成体入口 `野良ゆステ成` 使用 `90 + Random.Range(0,71) + 野良かしこさ補正`。这是带原生地点等修正的不同入口，不能概括成一个通用随机分布 |
| `analysis/decompiled/menu.cs:12245,12254` | 商店还使用另外的生成与成长修正规则；本模块没有把商店徽章或售价反推成能力 |
| `analysis/decompiled/menu.cs:12714–12803` | `精子餡ゆっくり誕生` 中父 ID 明确为 0，母 ID 为真实母体；该入口的能力为 `(int)(母体当前能力 / Random.Range(2f,2.5f))/2`。这只证明此入口的单亲计算，不能证明所有茎生、卵生或两亲遗传算法 |
| `src/Mod/WildEcology.cs:148` | Mod 的 `Young` 使用成长标志不等于 1 |
| `src/Mod/WildEvents.cs:135,212–215` | 教师耐心至少 55；学生是幼体、非敌对，教师能力至少高 50。成功条件是 `roll < .3 + 学生能力/500`。原卡这一行没有单独检查教师成年，MC 的成年教师限制是本次接入选择 |
| `src/Mod/WildEcology.cs:311–315` | 只有已完成情景中的 `lesson` 为真才增加 1，且不超过教师能力；随后才记录教育经历 |
| `src/Mod/WildEvents.cs:154,176,275–278` | 森林卡要求饥饿至少 .3 和地点食物机会，辨识成功率为 `Clamp01(.35 + 当前能力/600)`；成功才分配食物，失败是气味误判 |

没有宣称原生完整数值生成表或遗传算法已经还原。本轮明确复用了原生刻度及 Mod 的能力差、成功概率与成功增长；MC 的生成、先天遗传和玩家等待课增长见下节。

## MC 的独立选择与适用范围

- `Innate` 是先天能力，`Learned` 是后天增长，当前能力为两者相加，上限 1000。没有修改 `YukkuriPersonality.Trait` 或其六元素数组。
- 实际 `NATURAL`／`CHUNK_GENERATION` 的 `FinalizeSpawnEvent` 初始化先天能力为 50–200 的整数均匀分布，使用 UUID 固定种子，保存来源 `mc_natural_v1`。这是 MC 选择，不是上表任何原生完整生成表。其他无认知数据的入口、旧存档与旧收纳快照使用固定默认 100，来源 `legacy_default`，后天增长为 0；不会因为第一次观察而随机抽取。
- 所有 `YukkuriEntity` 都能持有认知属性。野外两卡目前受事件模块自己的非家油、非捕食种等适用范围限制；属性本身没有按物种、外形不足、好奇、ORDER 或徽章推断高低。
- `canTeach` 要求成年教师、真实幼体、耐心至少 55、差值至少 50、同世界四格内、双向视线及可识别的非敌对关系，并重查危险、急迫需求和进食。它不消费意愿。多毛种的 `acceptsLesson(teacherUUID)` 由事件在课堂开始时调用一次；不在每 tick 轮询中反复抽意愿。外观不足没有被假定成听视障碍。
- 玩家等待课仍完全由 `YukkuriSocial` 的 `lessons/knowsWait` 管理。只有原 `Task.TEACH` 的真实完成点增加一次认知，已学会后的普通等待命令不增长。此处每次完成加 1 是 MC 接入规则，不冒称还原原生所有玩家教育强度公式。旧存档既有等待课不会补发认知增长。

## 教学事实与事务

事件任务维护课堂和动作状态，认知模块不维护第二份活动课堂。

`Cognition.commitTeaching(teacher, student, classroomUUID)` 要求 `WildEventData` 中存在活动的 `CARD/teach`，成员依次为该教师和学生。事件的 `responses == 3` 表示先观察到教师真实向上速度、再观察到学生模仿向上速度；`sourceCount == 1` 只在动作齐全且原学习概率通过后写入。认知模块在提交时重验资格，返回 `APPLIED`、`DUPLICATE` 或 `INELIGIBLE`。

只有 `APPLIED` 才将后天能力增加 1，并保存该课堂 UUID。只显示对白、仅教师跳起、学生没有模仿、概率失败、危险中断、错误参与者或距离失效均不能增长。已成功的课堂以后即使事件历史被清理，也不能再次增长。每只个体保存的成功课堂数受能力上限自然约束；不按近期历史窗口淘汰防重凭据。

森林辨识只提供 `forestFindSucceeds(entity, roll)`，不生成或复制食物；事件任务负责实际目标和误判情景，现有取食与吞咽负责有限物品扣减。认知模块不保存异种口癖、探险队身份或第二份等待技能。

## 出生与持久化

`FamilyData.birth` 在实际加入实体成功、原胎次子项标记已出生且真实家谱记录为 ALIVE 后调用 `Cognition.born(child, motherUUID, fatherUUID)`。调用核对世界中的同一实体和家谱中的准确母父，拒绝虚构父亲；同一出生再次提交不重抽。

出生先天能力取可用真实亲代**先天值**的整数平均，再加按子代 UUID 固定的 −20 至 +20 整数扰动，并限制到 0–1000。仅一方记录可用时使用这一方，无可用亲代能力记录时使用基线 100。母父 UUID、当时可用的先天值和来源 `mc_birth_v1` 随子代保存；父方未知保存为空，缺少能力记录保存 −1。后天增长从 0 开始，亲代后天学习不遗传。此规则是 MC 的明确适配，不等于上表的原生生殖用馅计算。

`mcyukkuri_cognition` 世界 SavedData 统一跨维度保存每个 UUID 的属性、来源、亲代快照和成功课堂。实体 `persistentData.MCYukkuriCognition` 只是随完整实体 NBT 一起携带的镜像。已有世界记录永远优先，旧镜像、回读或收纳快照不能覆盖较新的学习；世界首次没有该 UUID 时才读取镜像。认知没有计时增长和卸载补偿，冷冻、收纳、卸载、重载都不会自动学习或重抽。客户端观察使用服务器生成的图鉴页，不依赖未同步的客户端实体镜像。

图鉴保留原 14 页并追加独立能力页，只展示先天、学习增长、当前值和等待技能分开学习的说明。服务端观察不改能力，不把来源登记中的实现字段塞进玩家界面。

## 构建与检查入口

使用现有 JDK 21，并执行：

```powershell
./gradlew.bat --offline build createP0GameTestLaunchScript createP0PreviewLaunchScript --project-cache-dir .local/cognition/gradle-cache --no-configuration-cache -I scripts/cognition_checks.init.gradle
python scripts/run_cognition_checks.py gametest
```

初始化脚本逐个修正 `main/p0Test/test` 的实际类、资源输出和运行 classpath；运行器复制成功编译产物及资源到唯一目录，记录逐文件 SHA-256，再启动独立 GameTest 世界。`CognitionGameTests` 使用真实 `mcyukkuri_p0:empty` 模板及 `PrefixGameTestTemplate(false)`，在独立 `cognition` suite 与默认集合中各显式注册一次，没有自动 Holder。

本任务没有修改模型或美术导出。中文事实和文风人工核对；未虚称运行当前不可用的 `tech-doc-style-chinese` 或 `tech-doc-lint`。最新检查数量、固定产物和实际图鉴排版以[验收记录](validation/cognition/README.md)为准。
