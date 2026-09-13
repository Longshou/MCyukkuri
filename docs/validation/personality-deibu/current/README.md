# 圆润体型、人格与灵胖实装验收

2026-09-13。对应用户要求“实装更新，并且检查所有机制是否适配恰当”。这是移植联合版本之后的新构建；历史双客户端验收不计入本轮结果。

豆油动态表情后续修正：两豆油的五官挂点现按所在身体高度跟随分段动作，配套伤口与器具已重新导出。实际客户端录制185帧，并人工核对两模型×三表情×七阶段的42个样本，未见此前的吞眼或半嘴，见[动态表情图](../../../../art/source/rounded-runtime/previews/current/minecraft/bean-face-motion.png)与[采样记录](bean-face-motion.json)。原始时间轴按20Hz记录，渲染插值与邻近采样误差已在记录中说明。此补修版的固定构建已完成回归；此前固定产物及指纹保留于 `../archive/before-bean-face/`。

本页最新结果仅对应已冻结的豆油表情修正版。另一模型任务随后开始的辫子体积、帽口和尖牙返工不包含在此Jar中；共享工作副本可能继续变化，不能把本页结果用于未验收的新资源。

辫子、帽檐与尖牙的后续固定产物已另行完成定向复验，最新四模型实机与Jar见[后续验收](../../bean-braid-fangs/current/README.md)。

## 实装结果

- 四项性格倾向参与分食、争食等待、冲突、保护、亲昵和教学；保留独立认知与玩家关系。旧六项性格迁移到四项，旧大胆值不充当攻击性。后代继承先天倾向，后天经历按冷却与累计次数缓慢改变。
- 环境友善度独立记录实际遭遇，默认50。有效照料每分钟最多增加4点；受攻击每两秒最多记一次，之后五分钟内照料不抵消敌意；平静时每分钟向50恢复1点。非玩家攻击不会占用玩家关系记录，正常相扑和无来源环境伤害不算敌意。
- 成年灵梦在营养≥75、环境友善度<30时累计20分钟加载时间；默认固定30%易感个体可以转为灵胖，条件不足时累计进度消退。转换等待空闲、落地和足够空间，保持身份、人格、关系和伤口。多毛灵梦转换后保留毛发；不足油不走这条路径，后天形态不进入出生抽取。
- 九种圆润运行形态已接入。灵胖及多毛灵胖的实际身体为普通形态1.5倍；导出先归一化，碰撞、成长、出生出口、饰物、茎生、伤口、遗留物和操作器具共同使用形态比例。成长仍为默认40分钟成熟、成年后约30小时寿命，并保留个体差异。
- 旧伤口通过冻结的旧表面迁移，后续保存实际表面指纹与锚点。怀孕表情投射到实际鼓起的身体表面，修复嘴部和面颊被遮住的问题。豆油后发和捕食种尖牙使用另一模型任务释放的最新资源。

详细规则见[人格与环境](../../../PERSONALITY_DESIGN.md)、[灵胖转变](../../../../wiki/reimu-deibu-development.md)和[成长时钟](../../../GROWTH_BALANCE.md)。源文件与导出关系见[圆润接入包](../../../../art/source/rounded-runtime/README.md)。

## 检查与证据

最新冻结构建完整回归 **40项 GameTest、2106条报告检查全部通过，进程退出码0**，见[汇总](summary.json)。其中人格24条、灵胖转变16条、圆润接入及伤口迁移18条、成长28条；其余覆盖家庭、消耗品、伤口、社交、野外事件、家油、亚种、徽章、冷冻、商店、工厂和操作等既有机制。每个专题的原始报告保存在本目录 `checks/`。聚落移动记录的采样条数会随运行变化，前一轮2114与本轮2106的差额来自这一项。

[资源检查](assets.json)核对九套圆润源/快照/贴图，以及全部19种当前运行形态的网格、骨架、动作、配套伤口和徽章指纹，共28条记录通过。[浏览器检查](browser.json)涵盖九种形态、三种表情，以及每组五动作的两个相位，27组通过，无页面或资源请求错误。

网页显示补验：用户反馈圆润预览大面积透明，查明该独立页面沿用128×128采样尺寸，而实际图集为256×256。原检查只判断PNG非空，漏掉了缺失模型。现已按模型图集尺寸采样，并新增正面、侧面、背面实际画布与完整网格轮廓的像素覆盖比较；修前27组检查失败，修后全部通过，九种角色截图已人工核对。记录见[网页修正](browser-fix.json)。此项只修改网页及生成脚本，不改变上述固定Jar。

实际客户端检查包括九形态四方向、九形态怀孕，以及灵胖伤口、徽章、茎生、脱饰、固定架和箱体等状态；最后补验两豆油与两捕食种的四方向和尖牙表情，共归档72张有效原图。图片见[四向图](../../../../art/source/rounded-runtime/previews/current/minecraft/four-views.png)、[孕态](../../../../art/source/rounded-runtime/previews/current/minecraft/pregnancy.png)、[挂接与状态](../../../../art/source/rounded-runtime/previews/current/minecraft/states.png)、[豆油与尖牙](../../../../art/source/rounded-runtime/previews/current/minecraft/bean-fangs.png)。截图为真实 Minecraft 帧；操作姿态由夹具设定，交互结果另由 GameTest 检查。各客户端运行快照、原图和检查关联见 [visual.json](visual.json)。本轮启动的客户端、测试进程与浏览器均已退出。

独立审查发现两处歌唱夹具在等待期间被自主赛跑预约，已调整夹具发现事件的时序；未为通过测试关闭生产环境的活动互斥。旧成长阈值、实体宽度和六项性格夹具也按新规则更新。

豆油后续完整回归曾暴露旧语音夹具的加载时序问题：截止时刻角色仍为 `entityTicks=0`，尚未开始更新。诊断和失败记录保存在 `bean-face-regression/`。夹具现先等待实体开始tick，再按实际游戏时钟检查到期；不改生产语音逻辑。修正后的完整运行记录显示角色已更新59tick，台词在截止后正常清除。

## 复现与产物

在项目根目录使用 JDK 21：

```powershell
$env:JAVA_HOME='E:/yukkuri/.devtools/mcyukkuri/java/jdk-21.0.12.1+1'
./gradlew.bat -I scripts/personality_deibu_checks.init.gradle --project-cache-dir .local/personality-deibu/gradle-cache --no-configuration-cache build createP0GameTestLaunchScript createP0PreviewLaunchScript verifyPersonalityOutputs
python scripts/run_personality_deibu_checks.py --suite integration
python scripts/verify_rounded_runtime.py
node scripts/verify_rounded_browser.cjs
```

实际主程序和测试 source set 的类、资源均确认位于 `.local/personality-deibu/build/`；执行历史与配置缓存独立。GameTest 先复制类与资源快照，再运行隐藏的 Java 进程。已验收开发 Jar 保存于 `.local/personality-deibu/release/mcyukkuri-0.1.0-dev.jar`，包内1470个资源文件（含生成的模组声明）和全部主程序类与测试快照一致，指纹和核对范围见[产物记录](release.json)。本次归档使用 `archive_personality_deibu.py --frozen-only`，没有把其他任务仍在改动的共享资源宣称为通过。没有提交、推送或发布。

## 尚未覆盖

本轮未重新执行双客户端、专服晚加入、长时间多人运行、默认速度多代生存或用户真实旧世界升级。自动检查和实际截图覆盖本次受影响路径及完整既有 GameTest 套件，不能保证所有输入和模组组合都没有问题。圆润身体基线已经确认；后发、灵胖面容、小蝴蝶结及新增组合的美术确认状态仍按各来源登记，实装不替代用户确认。
