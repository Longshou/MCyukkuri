# 消耗品与普通胎生本轮证据

`summary.json` 指向本轮全新 GameTest 世界 `gametest-7i41y064`。五份 `*checks.json` 分别记录口服/喷雾/辛辣 36 项、自然饮水 4 项、辅助繁育/保存/出生边界及认知提交 42 项、外层玩家右击后实际 tick 至双胎出生落地 5 项、固定亚种和出生/成长碰撞 14 项，共 101 条断言全部通过。两只幼体依次自然离开出口，真实 tick 场景没有通过测试代码挪走首幼体。

`runtime-manifest.json` 保存实际运行 class/resource 字节的 SHA-256，`source-manifest.json` 保存该轮源码和 JSON 指纹。它们证明相应冻结快照，不能证明之后仍被多个任务修改的共享工作副本。`gametest.log` 保留引擎执行日志。夹具孕育时长缩短为 200 tick，未改写正式配置默认值。

模型导出检查为 `build.py --check --skip-language`：10 个模型无差异。[最新客户端图像与状态](../../../../art/props/consumables/previews/current/README.md) 单独记录，包含 109 张实机原始帧、逐帧实体状态和另一份冻结客户端指纹，不能用规则断言代替模型视觉检查。专服双客户端同步尚未运行。

入口、玩法与来源关系见 [实现记录](../../../CONSUMABLES_AND_BIRTH.md)。
