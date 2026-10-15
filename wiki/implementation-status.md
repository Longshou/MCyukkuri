# 设定与游戏实现的对应

[总入口](README.md) · [基本设定](hakkei-reference/world/rules.md)

2026-10-14 对照仓库现有实现文档整理。本页是导航，不是本轮重新运行玩法验收的证明。“采用”说明设计选择，“接入”说明功能存在，“验收”仍以对应记录的范围为准。

| 主题 | 当前口径 | 实现、计划与验收入口 |
| --- | --- | --- |
| 性格 | 利己性、攻击性、依恋性、自制力四轴已接入；六轴与十六型属于旧版说明 | [当前实装](../docs/PERSONALITY_DESIGN.md#当前实装) |
| 胎生与茎生 | 普通胎生已接入，不能再写成仅有演示；家油保留卵生 | [消耗品与普通胎生](../docs/CONSUMABLES_AND_BIRTH.md) |
| 家油与巢卵 | 实体、筑巢、卵与孵化已接入；制作源网页原型不是当前实现的全部 | [核心玩法](../docs/CORE_GAMEPLAY.md)、[后续接入](../docs/PORT_REMAINDER.md) |
| 生育资格 | 多毛可育；其他不足形态与 DOS 按项目规则不可育；DOS 本体仍是候选 | [生育资格说明](../docs/CONSUMABLES_AND_BIRTH.md)、[后续开发](../docs/DEVELOPMENT_PLAN.md) |
| 茎尺寸与母体 | 母体、胎数与茎尺寸相关已采用，线性配置方向是原创方案；不要称已全部实装 | [开发计划 A6](../docs/DEVELOPMENT_PLAN.md#附录-a设定冲突的采用决定) |
| 灵胖 | 成熟、营养与环境共同作用的后天转变已经接入 | [成长规则](reimu-deibu-development.md)、[性格实装](../docs/PERSONALITY_DESIGN.md) |
| 徽章 | 训练、考核、佩戴已接入；金章观察者识别失饰者是项目规则 | [金银章](../docs/BADGES.md)、[作品参考](badges.md) |
| 失饰的社会后果 | 识别已有；完整聚落降职、驱逐与恢复仍是计划 | [步骤 1.1](../docs/DEVELOPMENT_PLAN.md) |
| 商店、工厂与 NPC | 已接入；Wiki 中 2026-09-13 的原创建议属于历史设计 | [人类世界](../docs/HUMAN_WORLD.md)、[生产线](../docs/validation/factory-pipeline/README.md) |
| 捕食种 | 蕾米莉亚、弗兰的实体、飞行捕食、枝巢与销售已经接入 | [捕食种实现](../docs/PREDATOR_IMPLEMENTATION.md) |
| 群落与天然住所 | 有当前实现；不等于季节迁徙和群落职位已完整 | [群落实现](../docs/SETTLEMENTS.md) |
| 季节、越冬、被炉与 DOS | 已有采用方向与资料条目，仍按开发计划逐步实现 | [步骤 3、4 与附录 A](../docs/DEVELOPMENT_PLAN.md) |

本轮仅维护资料、图片和辅助脚本，未改游戏逻辑或模型。没有启动游戏、P0 GameTest 或进行界面自动化。
