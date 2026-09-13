# 消耗品与普通胎生

2026-09-13。本文记录 P5 消耗品与 P7 普通种 LIVE 胎生的接线与验收；交接时的半成品状态保留在 [交接记录](CONSUMABLES_HANDOFF.md)。造型适配待用户确认。

## 玩家实际操作

| 内容 | 入口与结算 | 限制 |
| --- | --- | --- |
| 清水、酸奶 | 手持物品右击个体，含一口份量，吞咽时才结算；生存消耗后归还一个空瓶 | 缓解干燥与辛辣；不凭空回血或增加营养；无需求时拒绝重复使用 |
| 自然饮水 | 干燥个体寻找可达的真实清水边缘，抵达后逐步饮水 | 无需手持容器；不远程取水 |
| 兴奋药 | 口服吞咽后增加有限发情时间 | 不绕过成年、去势、年龄、关系、住所、营养和容量条件 |
| 幼语药（シタタラン） | 口服吞咽后保存幼儿口吻标志 | 不修复舌部或其他组织；不改变实际年龄 |
| 助眠喷雾 | 90 tick 延迟后进入有限药物睡眠，状态可保存 | 不要求已有住所；外部危险中断睡眠；重复喷洒不能重置时间 |
| 超辣油粮 | 吞咽后施加有限辛辣不适，内部伤害并吐出身体已有馅料 | 含入口中不立即受伤；吐出量扣除真实有限馅量，不新增皮肤伤口 |
| 生殖用馅 | 持一份右击合格普通成年个体，保持近距离静止操作；第 40 tick 提交并结束 | 生存预留一份，中断或重载后原物归还；已成功提交不再返还；既有孕期不能重复投药 |

口服由 `Consumables`、`YukkuriLife` 负责，投药由 `OperationLife.Pose.ASSIST` 与 `FamilyData.assist` 共同提交。投药资格还检查健康、卫生、能量、真实脚底支撑、日常活动互斥和可用住所；排除家油、捕食种、NoAI、伤口、感染及离线工厂中的繁殖用途。静止物理步可能短暂清除 `onGround`，因此使用脚底实际块碰撞补判，并保留竖向重力。

## 固定胎次与安全出生

生殖用馅建立 `BirthKind.LIVE` 胎次。母体 UUID、胎次 UUID、孩子 UUID、种类、禀赋、亚种及营养和区域人口预留在提交时确定，父方保持空值；不指定附近个体为父方。自然普通配对的胎生概率由 `ordinaryLiveBirthChance` 配置，默认 0.20，按配对身份与胎次稳定选择。此概率与制造配方属于 Minecraft 适配；家油仍卵生，捕食种仍走其茎生规则。

孕育仅随加载后的服务端 tick 前进。母体需要可用住所、营养和安全空间，分娩时还需静止、前出口通畅和完整落地支撑。墙体、液体、越界及占据出口的幼体会暂停当前胎次；不穿墙产出或将兄弟姐妹叠在同一点。每个幼体以 36 tick 显露，下一只起点间隔 48 tick；成功加入世界后才登记出生。逐个出生后腹部回缩，最后保留 70 tick 产后恢复。

LIVE 新生儿为 BABY，比例 0.18，出生朝向继承显露阶段，先落地再启用新生反应。脱离时给出轻微前向速度，由真实物理碰撞处理，让首幼体自然离开出口；前方仍被占用时继续暂停下一只。重载和收纳恢复保留固定胎次，卸载不偷跑；母体死亡取消未出生部分，已出生孩子不重抽或重复生成。实际母父信息供亚种出生处理使用，制造投药不伪造父方；工厂所属、聚落和栖息地的继承沿用已有接口。

孩子的 `subspeciesFlags` 随胎次保存，显露模型与出生后的实体使用同一结果，不在出生时根据变化后的父母状态重抽。豆油的高度同时用于出生前空间检查、实体加入世界时的碰撞箱和后续成长检查；旧存档缺失该字段时按普通形态加载。成功加入世界并登记后才调用新生认知，加入失败不通知母体。

## 造型与资源关系

六件道具及四种份量的源、导出和指纹见 [消耗品素材](../art/props/consumables/README.md)。普通角色沿用 [当前运行素材来源](../art/runtime-sources.json) 选定的网格、骨架和表情贴图；本任务不覆盖已确认角色的静态模型。

孕体下部变宽/变深、上部抬升、口下前出口、后部投药点及 0.18 新生尺寸参考已确认的 [胎生行为原型](../standards/yukkuri-v1/behaviors/viviparous-birth.json) 和 `standards/yukkuri-v1/runtime/viviparous-birth.js`。当前接入由通用 `LiveBirthGeometry` 与客户端 `LiveBirthRenderer` 实现：服务端同步孕期、已出生位、孩子种类和亚种；客户端对当前角色网格变形，裁开出口、绘制封闭边缘与显露幼体，不在客户端创建实体。口中物和伤口渲染跟随同一孕体变形。表情面按身体纵向切片细分，排除贴片背面，避免弯曲后相交产生黑块。新版圆润角色上的具体适配不能沿用旧方体原型的确认指纹。

八景 U 原生只读核读入口为 `E:/yukkuri/Eight景U-ModLab/analysis/decompiled/yu_base.cs`：清水 545/酸奶 546 对应便秘缓解；生殖馅 550 在约 11394–11455 行还有既有孕期异常分支，不等于无条件重建胎次。Minecraft 实现拒绝对既有孕期重复投药，将制造投药映射为普通 LIVE；不会把这种适配称为逐行复刻。

## 验证状态

可复验入口（PowerShell，使用本机已有 JDK 21）：

```powershell
$env:JAVA_HOME='E:/yukkuri/.devtools/mcyukkuri/java/jdk-21.0.12.1+1'
./gradlew.bat -I scripts/consumable_checks.init.gradle --project-cache-dir .local/consumables-birth/gradle-cache --no-configuration-cache compileP0TestJava createP0GameTestLaunchScript createP0PreviewLaunchScript
python scripts/run_consumable_checks.py
python standards/yukkuri-library/drafts/consumables/build.py --check --skip-language
```

脚本冻结所有 class/resource 字节和启动参数，保存源码与运行产物指纹；五份报告全部存在且断言、进程退出码均通过才返回成功。每轮新世界和日志均保留在 `.local/consumables-birth/gametest-*`，`latest-test.json` 记录最新一轮；不覆盖旧快照，不在验证后删除唯一证据。

独立构建使用 JDK 21、NeoForge 21.1.250，所有临时产物和世界均位于 `.local/consumables-birth/`，并使用独立 Gradle project cache。`gametest-7i41y064` 的冻结 classes/resources 快照通过 5 项 GameTest、101 条断言：36 条消耗品、4 条自然饮水、42 条投药与胎生边界、5 条真实 tick 投药至两只幼体落地、14 条亚种身份和碰撞空间。实际 tick 测试使用外层 `Player.interactOn` 和服务端实体，投药提交后将孕育时长缩为 200 tick 加快测试，不通过测试代码移走第一只幼体。报告、日志及源码/运行指纹见 [本轮证据](validation/consumables-birth/current/README.md)。

受控检查覆盖资格拒绝、一次预留、中断/重载原物归还、重复投药、未知父方、固定孩子身份、营养扣除、收纳与重复释放、出生前方障碍、无地面、母体移动、两只依次出生、母体死亡及新生认知提交时机。亚种检查覆盖 `reimu_bean`、`reimu_wasa`、`marisa_tsumuri` 的固定身份，及豆油出生和成长时的低顶碰撞。

最新客户端 `client-1789249837` 采集了六件道具、口中份量、孕期与出生多角度，以及实际右击投药和双胎出生的 109 张原始帧；见 [实机画廊](../art/props/consumables/previews/current/index.html) 与同目录记录/指纹。观察角色保持存活，出生录像全程无菜单遮挡。客户端与规则测试是分别冻结的快照；最终客户端构建包含预览观察模式修正。专服双客户端投药/出生同步尚未运行，不用单机 GameTest 代替多人验收。中文文档人工核对，未声称执行未安装的文风工具。
