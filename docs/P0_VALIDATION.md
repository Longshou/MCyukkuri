# P0 基础验证记录

> 当前入口（2026-10-16）：下文是历史轮次记录，不代表当前代码已通过全套验收。当前可通过 GitHub Actions 的 `Optional P0 GameTest` 手动选择测试组，或在远端运行 `./gradlew runP0GameTest -Pp0Suite=core`；默认不传参数仍为 `integration`。本机不编译、不启动游戏。运行结果以对应提交的 Actions 日志为准，详见[开发指南](DEVELOPMENT.md)。

当前对白夹具允许五类聚落原创事件使用尚未配音的字幕；既有语音事件仍检查配音存在，声音注册检查不变。这项断言修正不代表新增字幕已经配音。

> 后续更新（2026-09-12）：本页保留对应轮次的验证边界。用户随后接受 EULA；专服、多人、成长家庭、家油巢卵及基础伤病的现行实现和证据见 [核心玩法记录](CORE_GAMEPLAY.md)。

> 本页记录加入 P1–P2 前的基础验收。后续代码和现行断言数量见 [P1–P2 实现记录](P1_P2_IMPLEMENTATION.md)。

2026-09-12，本轮已实现独立验收夹具、修复两处觅食错误，并完成构建、真实引擎行为检查和部分客户端验证。**P0 尚未整体通过**：专用服务器、双客户端晚加入、真实区块卸载和专服重启检查尚未执行。相关入口已经准备好，待开发者确认接受 Minecraft EULA 后继续。

## 环境与证据

本机 Windows、JDK 21.0.12.1、Minecraft 1.21.1、NeoForge 21.1.250、ModDevGradle 2.0.146、Gradle 9.2.1。验证对象为当前未提交工作副本。本轮没有提交、推送或远程 CI 运行结果。

测试源位于 `src/p0Test/`，是独立开发模组 `mcyukkuri_p0`。普通运行配置和发行 Jar 只加载主模组；当前 `build` 不编译夹具，`runP0GameTest` 单独编译并执行检查。测试世界和运行日志保存在执行环境的 `.local/p0/`，不入库，也不进入游戏资源目录；远端手动工作流只上传诊断日志与 Gradle 报告。

可复用测试区：平地位于 y=64；半砖在 z=12、整格台阶在 z=14、窄道在 z=18、屋顶在 z=22、水池在 x=11–15/z=12–16。行为测试在 x/z≈24.5 的平地反复生成单件或堆叠食物。区域已经生成并查看，但台阶、窄道、水域和屋顶的完整导航验收尚未进行。

## 已执行

| 检查 | 本轮结果与范围 |
| --- | --- |
| 资源契约 | `validate_entity_assets.py` 通过：四模型、每模型 19 动作、44 张表情贴图及来源指纹 |
| 构建与打包 | Gradle `build` 通过；核对 Jar 的模型、贴图、四生成蛋、语言、食物标签和模组声明，夹具未打包 |
| Minecraft GameTest | 1 个顺序场景中的 185 项断言全部通过 |
| 注册与生成 | 两个创造分类包含四生成蛋；实际调用生成蛋方块交互，生成对应类型并扣除一件 |
| 食物与导航 | 生存喂食扣一件／恢复生命、饱腹拒食、非食物拒绝、创造模式不扣、显式动作阻止手喂；地面单件与堆叠、拾取延迟、`mobGriefing` 和三格寻食通过 |
| 休息与受伤 | 受控缩短休息等待后，实际 AI 进入睡眠；受伤立即受惊、随后移动逃跑，超时恢复 |
| 动作状态 | 四实体接受全部 19 动作；单次动作结束、循环起始 tick 稳定、伸出保持、半途收回、无效动作拒绝、受伤中断通过 |
| NBT | 原生 `save`／`loadEntityRecursive` 恢复 UUID、种族和两个冷却值；手动姿态重置。此项是内存序列化往返 |
| 实际客户端 | 单人集成世界同时加载四实体，渲染器均为 `YukkuriRenderer`；已查看正、背、侧、家油近景与测试区截图 |
| 渲染数据 | 实际客户端调用四模型共 76 个动作片段，每片段 6 个时点，共 456 次非空／有限顶点检查；跨动作恢复常态通过，20 张行为必需贴图由 Minecraft 解码通过 |
| 动作帧序列 | 按动作时长加一秒录制全部 19 动作，另录家油侧弯、鼓腮、体操近景，共 22 段、964 帧；录制原始 PNG 和逐帧动作状态，已核对各实体出现请求动作。视觉核看为静态视角及重点关键帧抽查，未逐帧人工批准 |
| 资源重载 | 在加载世界后调用客户端资源重载；模型对象更新，四实体继续显示。此项覆盖有效资源的成功重载 |

GameTest 按真实引擎 tick 运行，但使用加速的内置测试服务器；休息等边界通过夹具设置状态后观察真实行为，手喂与生成蛋使用 `FakePlayer` 调用原版交互入口。这些结果不能代表真人完整操作、专服网络行为或长时间生存游玩。

## 本轮修复

两处改动均位于 `YukkuriEntity.FindFoodGoal`，未改变模型或标准造型。

1. 原来的实体寻路默认允许距目标一格结束，但进食距离更短，导致四种实体接近三格外食物后停住。改为使用到目标格的精确路径；对应断言现在实际观察到食物消失并产生饱腹冷却。
2. GoalSelector 不保证每 tick 重查继续条件，导致已经吃饱的实体在下一次停止目标前额外消耗同一堆食物。现在每次取食 tick 都重查饥饿、受惊、显式动作和 `mobGriefing`；三件食物应剩两件的回归断言通过。

## 复现已通过的检查

先让本次终端的 `JAVA_HOME` 与 `PATH` 使用已有 JDK 21，不必修改全局设置。

```powershell
python scripts/validate_entity_assets.py
.\gradlew.bat build runP0GameTest --no-daemon
.\gradlew.bat createP0PreviewLaunchScript createP0ServerLaunchScript createP0ClientALaunchScript createP0ClientBLaunchScript
python scripts/run_p0.py prepare
python scripts/run_p0.py prepare-preview
python scripts/run_p0.py launch preview
```

`prepare-preview` 只复制已通过 GameTest 生成的世界，目标已存在时拒绝覆盖；后续复用时跳过该命令。等待 `.local/p0/preview/p0-control/joined.json` 表明进入世界，然后按顺序执行：

```powershell
python scripts/run_p0.py request preview assets
python scripts/capture_p0.py
python scripts/run_p0.py request preview reload
python scripts/run_p0.py request preview stop
```

客户端隐藏窗口，截图读取真实渲染缓冲区。录制时暂不处理后续控制请求；同一目标一次只启动一个实例。停止请求只发往本轮对应目标，不应结束其他 Minecraft 进程。macOS／Linux 的 Gradle 命令改用 `./gradlew`；这两个平台的客户端流程本轮未测。

## 专服与多人待验收流程

先由开发者阅读并接受 [Minecraft EULA](https://aka.ms/MinecraftEULA)，再将隔离配置 `.local/p0/server/eula.txt` 中的 `eula=false` 改为 `eula=true`。准备脚本只创建默认拒绝配置，不代为接受。服务器绑定 `127.0.0.1:25586`，离线身份只供两个本机测试客户端使用。

```powershell
python scripts/run_p0.py launch server
python scripts/run_p0.py request server setup
python scripts/run_p0.py request server checks --timeout 180
python scripts/run_p0.py launch client-a
```

启动后分别等待 `server/p0-control/ready.json` 与客户端 `p0-control/joined.json`。控制请求为 JSON 文件协议；Python API 可免除命令行转义，例如在项目根目录执行下列脚本段：

```python
import sys
sys.path.insert(0, 'scripts')
import run_p0 as p0

p0.request('server', 'action', action='taiso')
p0.launch('client-b')  # B 在动作开始后加入；等待其 joined.json，再读取快照
```

依次完成以下检查并保存结果，全部通过后才关闭 P0：

1. 读取服务端及 A/B 的 `snapshot`，按 UUID 比较四实体的 `action`、`start` 和 `expression`。分别录制 `record` 帧序列，检查晚加入观察到相同相位。
2. 服务端 `network-feed` 为两玩家各发两苹果，并返回目标网络 ID；两客户端分别发送 `interact`（参数 `target`）。用 `players` 确认两人合计只扣一件，服务端冷却生效。
3. `unload` 移走观察者并取消强制加载；重复观察 `unloaded`，直到目标区块和四实体确实离开内存。然后 `load`，等待加载，比较 UUID、种族、状态和瞬时姿态重置。
4. `prepare-restart` 写入指定生命值、冷却和伸出姿态，保存并正常停止。先停止两个客户端，确认服务端退出，再启动同一服务器；检查 `restart-checks.json` 全通过。该文件记录实际磁盘恢复，不能用 NBT 往返代替。
5. 用生成蛋和 `/summon` 人工检查方向与碰撞；`/yukkuri action` 的权限与补全、持食吸引、台阶／窄道／屋顶／水边、受火伤、死亡中断，以及资源损坏后的重载处理仍需验收。
6. 记录无截图写盘干扰的帧时间及服务端 tick 开销；本轮录制时间含 PNG 写盘，不能据此宣布性能达标。最后给所有本轮测试实例发送 `stop`。

## 结论边界

代码和测试入口已经交付，基础行为回归及单人客户端证据可复现。专服与多人检查未运行，P0 保持“部分通过、待完成”。两套中文技术文档技能在当前会话中不可用，本页和相关入口已人工核对事实与表述。
