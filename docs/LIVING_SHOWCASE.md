# 连续生活展示

独立入口为根目录 `启动连续生活展示.cmd`。它使用已下载的 Java/NeoForge 运行依赖和指定 CI Jar，不运行 Gradle、不编译，也不修改动作博物馆。生成存档、制品、日志和运行配置全部放在 `.local/living-showcase/`。

地图分为陆上家庭、台地高差家庭、水上家庭，以及秋、冬、春、夏四个聚落。各区相距96格，以玻璃围栏和外侧木栈道隔开；入口支持开始、本区重置和近距离旁观。开始前没有活体；开始后才初始化双亲并用正常受孕入口建立真实胎次。重置按展示身份、亲缘后代与帽船所有权清理，不以区域选择器删除正常世界家庭。

陆上连续过程为出生、近身嚼糜递喂、首便辅助、实际爬行与起落、自主进食、采下两份植物并铺床、亚成告别及另筑新居。远处保留天然空洞穴。水上使用同一对真实水生亲油、自己的帽子和真实水面，继续帽中出生、护理、帽沿排泄、初航往返与独立驶离。初始化的老巢有24份旧粮；水上开始时各转移8份到亲油帽内，并消耗岸边各一株实际枯枝作桨。之后缺粮时划向紧邻水面的真实成熟莓丛，停船并用实际口部持续接触采果，果实进入有限船舱、莓丛退回已采状态；可沿原版随机生长再结果。后续没有自动补粮、刷树叶物品、写入护理成功或补发初航奖励。

短草、矮叶和莓丛是现场有限方块来源；取材要走近并消耗原方块。子代不会被预先设置成不同年龄。演示配置把成熟基准改为12000 tick（10分钟）、妊娠改为1200 tick（1分钟）；仍保留年龄、有效营养、运动与独立资格各自门槛。`-NormalTiming` 生成默认48000/6000 tick的独立存档。入口可切换20TPS或80TPS；80TPS会同步加速需求、成长、寻路和动画，细看照料时恢复20TPS。没有按现实时间偷偷完成的章节。

四季由群系决定，四个聚落分别放在针叶林、雪林、平原和丛林。各自使用现有 `settlement spawn forest_led` 生成真实聚落，演示配置固定3户、每户最多2个已有子代，另有真实族长；这是聚落初始住户，不冒充连续家庭的出生过程。现场留出多余天然洞穴，观察实际秋储、冬季本窝吃粮与闭巢、春季共享/派工和夏午休。日夜、雨雪和tick速度是全世界共享条件，入口明确标示。`/function mcyliving:chapter/rain|clear|morning` 改变环境；`chapter/threat|migration` 调用春林聚落的现有威胁/迁徙入口，仍需实际选址和行走，场地不合适时正常报告失败。这些入口不直接写结局。

观察笔记可查看个体、住所、帽船真实状态。旁观按钮切换到旁观模式，`/function mcyliving:home` 返回冒险模式。`/function mcyliving:land/reset`（其他区为 `heights`、`water`、`autumn`、`winter`、`spring`、`summer`）可单独重播。暂停按钮使用全世界 `tick freeze`，冻结期间实体和按钮都暂停；点击聊天里的「继续世界」或输入 `/tick unfreeze` 恢复，「单步20 tick」可逐段观察。

## 制品清单与安全启动

`.local/living-showcase/runtime.json` 应记录当前提交对应的成功 CI 制品。示例结构（路径均相对仓库）：

```json
{
  "commit": "完整40位CI提交SHA",
  "runtime": ".local/setup/runtime-1d6bc8b6",
  "java": ".local/tools/java/jdk-21.0.12.1+1/bin/java.exe",
  "templateLevel": ".local/experimental-map/ecology-20261016-check/saves/MCyukkuri-Ecology/level.dat",
  "artifact": {
    "path": ".local/living-showcase/artifacts/mcyukkuri.jar",
    "sha256": "实际Jar的64位SHA256",
    "runId": "成功CI运行编号",
    "name": "该运行的实际制品名称"
  }
}
```

也可提供 `artifact.url`（HTTPS，直接Jar地址）。Jar不存在时下载；文件存在时验证哈希。启动器核对提交、Jar哈希及专用命令类；旧制品和缺少运行依赖直接报错。只复用引擎依赖，旧模组目录从参数文件中替换为当前CI Jar解包目录。不会以陈旧 `showcase-runtime.json` 启动，也不会失败后回退编译。所有生成目录均新建，不覆盖旧存档。

```powershell
# 只下载、检查制品与生成存档；不启动Java或游戏
./scripts/launch_living_showcase.ps1 -PrepareOnly
# 同提交继续存档；-Fresh另建新档，保留已有档
./scripts/launch_living_showcase.ps1 -Fresh
# 原始成长参数另建存档
./scripts/launch_living_showcase.ps1 -NormalTiming
```

生成器可单独运行，依赖 `.local/setup/python` 中的 `nbtlib`。只读取模板的 `level.dat`，不复制区块、实体和世界账本。生成器核对函数引用与原版fill体积上限；这是非编译数据检查，不代表游戏内过程已经实测。
