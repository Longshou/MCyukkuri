# 项目脚本

资源统一入口：`<py> scripts/export.py --family weather --family life` 按家族导出；`--changed [REV]` 读取从 REV（默认 HEAD）到工作区的 Git 差异（含暂存、未暂存、未跟踪文件），选择声明的源和下游导出器。`--list` 列出脚本、只读检查参数、依赖与边界；表在 `scripts/export.py`，执行按依赖排序，导出器仍留原目录。已选制作器先于其运行导出；单独导出家族不会自动重做已选模型制作源。来源清单中显式登记的 `inputs` 也参与改动选择；旧七模型入口 `runtime-models` 只允许显式指定。无选择时只接受 `--check`，避免误跑全部历史制作器。每项只打印一行耗时／结果，详细输出在 `.local/reports/export/<报告名>-logs/`，汇总在 `results.json`（`--report` 可改位置）；失败返回非零，独立检查继续，无检查的脚本明确标 `NO CHECK`，不会执行写入。`subspecies` 的检查只覆盖贴图；尚未合入的导出器标 `MISSING`，不略过。表新增项应同时登记制作源路径与依赖。音频检查使用主检出 `.local/python-audio/` 的现有依赖；对白的本地八景原文也从主检出读取，运行导出仍写当前工作树。

动作源合并后用 `export.py --rebuild-motion` 重建完整动作、天气接触轮廓和生活片段。家族在 lore `recipe.json` 的 `export_families` 登记片段及必要派生输出，不再新增命令行开关。`export.py motion call_child wake --beats beats.txt --model reimu` 导出所属家族，调用 `preview.py strip` 输出各片段的节拍渲染条，再通过 `pose_check.py --serve` 把同一组任意秒时刻逐帧送检，输出在 `.local/reports/export/motion/<片段>/<模型>/`。`--model reimu marisa` 可一次检查多个形态，家族只导出一次，各形态的预览与指标分别保存；单个形态的姿态证据错误会显示具体原因并继续其余形态，整批仍返回非零。默认只查 `tilt`；例如 `--model marisa --metrics tilt,pass` 加辫身穿透。其他单帧检查用 `--metrics wet,gap,held`，必要证据由 `--water`／`--context` 提供；时序检查直接用 G2 的 `--frames`，不把稀疏节拍伪装成连续采样。几何 fail／疑似均如实保留在报告，缺证据、未知输入或未合入 G2 则非零退出。节拍格式沿用下文预览命令。

登记源拆为 `art/registry/runtime/<登记键>.json`（模型再拆为 `runtime/models/<模型>.json`）、`art/registry/catalog/<类别>/category.json` 与 `families/<家族>.json`。现有 `art/runtime-sources.json`、`art/catalog.json` 是生成的兼容汇总；原有读取方不变。登记文件中的 `_order` 只保留原分类优先顺序，不进入汇总，新项按 ID 稳定排序。独立包只改各自登记源，删除分片即删除登记，合并后 `export.py --registries` 重建汇总，`--registries --check` 只读比较。统一入口在每个旧导出器运行前先从分片重建汇总，运行后仅接收该导出器实际产生的登记变化，再重建汇总；动作节拍入口遵守同一规则。旧导出器须经统一入口运行。`--import-registries` 只供尚无分片的仓库一次性迁移，已有分片时拒绝导入，不能用汇总恢复已删除的登记。

“保存 xx”使用 `export.py --register entry.json`，一条命令登记素材与认可文件的 SHA-256，不改其他包的文件，也不立即改兼容汇总；后续导出／合并重建汇总。输入示例（`approval` 仅在用户已认可时提供，路径相对仓库根）：

```json
{"runtime":{"example":{"source":"standards/yukkuri-library/motions/variants/lore/recipe.json"}},"catalog":[{"category":"motions","family":{"id":"example","prefixes":["art/source/example/"]}}],"approval":{"id":"example","decision":"用户认可的具体内容","files":["standards/yukkuri-library/motions/variants/lore/recipe.json"]}}
```

认可登记写 `standards/yukkuri-library/provenance/selections/<ID>.json`；输入全数校验后才落盘，JSON 写入用同目录临时文件原子替换。工具自查：`<py> scripts/test_export.py`（含真实双分支登记合并、失败传播、只读边界和指纹反例）。

`library-validation` 在全量导出检查中只读验证标准库；圆润角色按各自 `export` 路径检查连续网格、组件导出一致性、骨骼、解剖动作和逐像素图集，旧方块角色仍按共享组件与 UV 绑定检查。`<py> scripts/test_library_validation.py` 注入非有限顶点、错误动作轨道和同尺寸错误贴图作为反例。单独运行 `standards/yukkuri-library/validate.py` 时报告写 `.local/reports/library-validation/`，加 `--check` 只打印结果。

全量只读检查可加 `--jobs 3` 并发执行独立检查，报告仍按声明顺序排列，任一失败仍返回非零；默认顺序执行。并发仅供 `--check`，写入导出保持依赖顺序执行。

lore 通用宠物联系表 `pet_motions_preview.py` 已由 `preview.py strip <主题> --model <模型> --clip <片段> --times <秒列表>` 接替；出生兼容入口 `birth_performance_preview.py` 移除，专用剖面直接用 `mamu_birth_preview.py`。保留的专门组合：`aquatic_family_preview.py`／`first_voyage_preview.py`（船上乘员与投喂交接）、`aquatic_paddle_preview.py`／`aquatic_strokes_preview.py`（船体、水线、岸边与辫节弯曲带）、`carry_hold_preview.py`（口中放幼、双尾承托、持物，侧面轮廓导出亦复用其蒙皮）、`birth_catching_preview.py`／`mamu_birth_preview.py`（出生出口剖切与接幼）、`weather_preview.py`（按真实侧面轮廓排列双体；雷雨与晒太阳的单体渲染已委托统一 strip）。这些依赖的 lore `preview.py` 是场景蒙皮辅助库，不是命令入口；`page.py` 的完整设定动作目录也保留。`aquatic_preview.py` 的旧帽船与木棍组合由上述水上专门预览覆盖。

试玩场默认使用 `/yukkuri status trace light`；`off` 关闭，`dense` 每 tick 输出完整状态及耗时。结构化 `[trace]` 行写入服务端原日志，`st` 中缺失的键表示不变，`null` 表示未知。light 每 tick 观察依附关系、持物、动作身份和危险状态，位置、速度、帽船坐标系及其他连续读数每 40 tick 比较一次，仅写变化；异常跳变保留前后两帧。详细状态表的 `ch` 在 light 每 200 tick 观察一次（dense 为 20 tick），不保留其间的短暂诊断变化，也不重复输出同一次状态变化的文本和 `ev`；普通玩法事件照常记录。场景窗口重置后首帧完整，未加载区块的窒息几何记为未知，不为录制加载区块。

演出数据：`export_moments.py [--check]` 导出并检查片段、表情及符号引用；`test_moments.py` 用错误引用与非法时间检查拒绝行为。`preview.py moment <ID>` 输出渲染条与 MP4，`preview.py kit` 包含演出行与头顶符号目录。格式见[演出数据](../standards/yukkuri-library/presentation/README.md)。
`<py> scripts/timeline.py <日志或 .log.gz> [pose.jsonl ...] --output <目录>` 合并结构化 `[trace]` 记录，按运行、维度、场景窗口与个体输出 JSONL、世界流和 `index.json`；重复拷贝去重，同名场景再次开启分开登记，缺号、冲突与坏记录写进索引。`<py> scripts/smoke_checks.py <日志...> [--scene ID] [--dense] [--seed N] [--evidence H|C|F|R] [--rerun 命令] [--output checks.json]` 独立报告 FATE、LOOP、PROGRESS、JUMP、LEDGER、ORPHAN、END、LAG、SPAWN、VANTAGE，附种子、证据等级、重跑命令和异常前后 100 tick 的记录；`--dense` 仅用于逐 tick 全量记录，启用个体无离场记录却消失的检查。缺数据记 `no-evidence`，报告退出码不因异常改变；既有命运门槛及 `allow_fate` 仍由兼容入口 `smoke_fate.py` 执行。`<py> scripts/fixtures/timeline/test_timeline.py` 运行十项检查各自的故障、干净与缺数据样例，以及移动、库存、日志边界与命运门槛对照；这些离线样例不替代真实日志校准。冒烟每场与截图每个视角显式设为 dense，按场景观察点在第 1、20 tick 与后续每 20 tick 读取几何；原始窗口逐行复制，轮询使用增量游标，既有断言只读非 trace 文本。冒烟可传 --seed N 作会话种子（默认生成）；每轮由会话种子与轮次散列出该轮种子，在场景开始前用 `/yukkuri status seed <n>` 交给服务器，覆盖此后构造／首次加入世界的个体随机数、模组世界随机、模组身份与出生 UUID、台词和考试随机；未设种子时世界级抽样使用原版世界随机源。原版自身的随机、设种子前已生成的世界、tick 与世界生成执行顺序仍不受控，不能完整重放；每场的 checks.json、timelines/index.json、jfr.json 与 JFR 文件记录该轮种子、重跑命令、tick／录制器耗时及服务端热点方法（JFR 按场景开启、结束并保留原文件，热点只取一层栈；checks.json 只含本场窗口，上一场窗口的收尾记录在该场自己的结果里）。`<py> scripts/replay_compare.py <左冒烟结果.json或日志> <右冒烟结果.json或日志>` 对比去掉 tick、坐标、原版实体数字 ID（`#N`）与耗时诊断（`elapsedMs=`）后的有序 INFO 玩法行，报告相同前缀长度、首处分歧；结果 JSON 还校验场景顺序与种子一致，并比较场景通过／失败结果。UUID 和业务数值仍参与比较，空日志不能证明可回放。反馈入档自动输出 observations/ 下的时间线、各 trace 窗口的检查结果和摘要；无记录或无场景 END 声明时明确记 no-evidence。

场景观察契约在 `museum_scenarios.py`：`smoke.end` 的 `final` 保存 `(主体, 谓词)`，按整窗末尾 100 tick 至少 80% 且最后时刻成立判定；`paths`（同 `smoke.milestones`）是互不强加顺序的路径，每条 `steps` 保存有序的 `(事件发出者, seen:类别:正则)`，`each` 要求选中的每个个体走完整条链。正则命名捕获绑定身份，后续 `$变量` 必须匹配同一身份；`$标签名` 引用唯一场景个体，`$subject` 引用 each 当前个体；顺序使用 tick 和记录顺序，支持同 tick 事件。`post` 在链尾事件后的一个物理 tick 检查真实完成状态，允许随后恢复日常行为；`forbid` 禁止窗口内的指定事件。主体支持标签、`actors`、`world`、`child_of:标签`、`newborn_of:标签`（仅本窗 life/birth）、`hat_of:标签`。谓词支持状态字段、`carry_empty=true`、`near.主体<=距离`、`aboard.帽主=true`（含帽主承托）、`home_of.主体=true`（相同 homeStand 坐标）、`nest_of.亲油=true`（该亲油对同一孩子真实放下日志的窝内占用，且孩子已解除承托）；homeStand 相等不能代替窝内几何。未标记聚落成员的事件只从同维度、同窗口、`bounds` 场景范围内的原始 GameplayLog 补读，不补造状态。旧式 END 列表仍供已有调用方使用。`capture.views` 各视角的 `criteria` 保存画面要求。`<py> scripts/fixtures/timeline/test_end.py` 验证顺序、错配、离场、世界事件、初生范围和完成后继续活动；`<py> scripts/fixtures/timeline/replay_end.py 日志目录 --output 目录` 对真实日志和移除阶段事件的故障输入逐场报告 END。检查只报告，不代替用户试玩。

`<py> scripts/fixtures/timeline/test_calibration.py` 用已标注日志的最小复现验证正常行为、故意破坏和缺失证据；`<py> -m unittest discover -s scripts/fixtures/timeline -p 'test_*.py'` 运行全部离线检查。样例不代替真实日志校准，十项观察检查默认只报告，其中 ORPHAN 与 SPAWN 已按校准升为冒烟门槛（见下）。

校准资格：FATE、ORPHAN、LAG、SPAWN、VANTAGE 在主线 `0c830e89b` 的 31 场景各 20 次同输入重放中无已标注误报，并保留故障样例；此资格限于各检查已声明的观察范围。其中 ORPHAN、SPAWN 的 `fail` 判为场景问题：冒烟逐条打印 `FAIL <场景>: <检查> <详情> (actor=… t=…)`，该场记为未通过，失败原因以检查名为键进入 `--repeat` 的失败原因表与 `--compare`；`suspect` 与 `no-evidence` 不参与判定，没有按场景的豁免。门槛集合只有 `smoke_museum.py` 读报告处的 `GATING_CHECKS` 一处。其余检查仍只报告：FATE 已由 `smoke_fate.py` 入门槛，同一死亡不重复计一次；LAG 测的是带观测负载的本机运行（主线 29 次真实告警来自并发的其他服务器），不是改动的性质；VANTAGE 只报固定观察点的近似取景，属场景设计问题。LOOP、PROGRESS 的重复动作与停走仍需区分实际等待、接触和受推；JUMP 的入抱、离抱缺少客户端连续目标轨迹；LEDGER 的旧同步携带物只显示优先项，不能证明库存守恒；END 的路径与瞬时后置条件仍有未解决项。这五项尚未取得门槛资格，不以缺证据或未解决项当作零误报。`test_catalog.py` 为 28 条服务端历史问题提供故障、正常和缺数据样例，并用 SPAWN／VANTAGE 几何样例覆盖摆放行；注入样例不能替代缺失的历史日志或旧版通过率。

PROGRESS 只统计实际停住后的重新移动；持续移动中跨过 0.01 格/tick 不算停止。原地转向只统计超过 1° 的反向，范围按实际体型缩放，半格停滞界限不变。实际阶段、持物、恢复、接触和有期限的等待进度结束对应统计窗口；计数停住、到期、失败或重置仍检查。共享等待计时不能掩盖同一个体正在执行的其他动作。幼体静止时跟随照护者真实方位转头不算独立摆动，但位移与停滞仍检查。LOOP 不把诊断快照、候选过滤、实际前进或已完成的不同交接累计成同一次失败；400 次真实拒绝界限不变。

JUMP 以真实位移、前后 tick 速度、跨步能力和承载者身体朝向检查残差；碰撞前 travel、帽内已执行的局部飞行增量及同个体同 tick 的明确初态放置只解释对应输入。实际碰撞且速度归零的轴允许位移在零到输入速度之间截短；额外或反向位移仍按 0.25／1 格分界报告。LEDGER 分别记录实际持有者，离开观察范围只扣除该持有者的库存，死亡后的展示缓存不重复计入物资；交接解释限定前后 2 tick，明确物品 ID 必须匹配，自然过期须有实际 age/lifespan。嘴部记录从各组件读取真实持物，并带 `hold.mouthAuthority=components`；缺此读数的旧日志库存总量记 no-evidence，仍检查已观察到的嘴部重复占用和实体出入。`test_inventory_authority.py` 验证采叶遮住草的旧显示、真实丢草和缺权威读数。双方记录确认的亲油乘员由实时抱持动作定位，缺少动画目标轨迹时 JUMP 记 no-evidence；亲油自身、入抱与离抱仍检查。无采样个体的掉落／拾取日志须明确指向已采样物品 UUID。报告附实际持有者和物品增减量；事件解释不等于逐笔数量配平。

SPAWN 保留 0.0625 格支撑容差、碰撞与初始卡墙检查；初始化内落稳的个体（首个样本尚无支撑、+20 tick 已真实支撑）不算坏起点，两个样本都无支撑或任一时刻穿模仍失败；开场拒绝后在原有 40 tick 内确实开始行动的不算阻塞，缺后续样本记 no-evidence。VANTAGE 在近似主体高度低于画面 3.5%、可见射线少于 5/9 或中心出画时提示；射线依据最近碰撞体，不能证明每个演员、接触点和演出细节可读，仍需画面审查。

`python scripts/check_architecture.py --check` 是 Python 3.10+ 标准库的只读架构建议工具；默认不带 `--check` 也只读。它只扫描本仓库 `src/main/java/**/*.java`，不扫描 `.worktrees`、`.local`、资源或文档，不调用 Git、Java、Gradle、游戏、网络，也不修改文件。不接入 CI、pre-commit 或合并门槛，不替代远端编译或用户试玩。

`bash scripts/javac_check.sh [工作树]` 用本机 JDK 直接 javac 编译主源码，并运行 `motor.geom`、`intent`、`aquatic`、`family`、`pose` 包的纯 Java 单元测试，约 20 秒；不经 Gradle、不启动游戏。依赖 `.local/tools/` 下的 JDK 与 JUnit 控制台 jar，以及主检出里已有的 `build/moddev`、`.local/gradle-home` 依赖 jar。只作提交前自查，不替代 CI，也不算试玩验收。

`python scripts/update_museum_runtime.py --run <run-id>` 为已有试玩配置安装同一提交的成功 CI 运行包，不编译、不启动游戏，在实际游玩的机器执行；安装并读回配置后删除旧运行包，仍被运行中的冒烟或截图引用的目录保留到下次更新（规则见[AGENTS.md](../AGENTS.md#6-环境与命令)的“运行包更新”）。

`<py> scripts/safe_edit.py replace <文件> <旧文本> <新文本>`（多行文本用 `--old-from-file`／`--new-from-file`）按精确匹配改文件的一处：旧文本必须恰好出现一次，匹配 0 次或多次都不写入并返回非零；写入时保留原文件的换行风格（CRLF／LF，混合换行与孤立 `\r` 直接拒绝）、UTF-8 编码（原文件有 BOM 才写 BOM）与结尾换行。用于代理改源码与文档，代替 `sed` 和临时脚本：Windows 下它们会丢掉 `\r`、把 UTF-8 写成 ANSI 代码页、吃掉文件结尾换行（曾把待办截成 11 行）。

`<py> scripts/pkg.py <命令>`（`PYTHONUTF8=1`）是总负责人的包生命周期入口，出错即停并给出原因，不静默兜底：`start <名> --title <条目> --files <主要文件> [--art]` 要求主检出干净，`pull --ff-only`，在待办“进行中”表加行并以 `TODO: register <名>` 推送，基于 `origin/main` 建 `.worktrees/<名>`，缺省时写 `.local/refactor/briefs/<名>.md` 骨架（`--art` 加 `_build-common.md` 指引）；`land <名> [--scene ID ...] [--dry-run]` 要求工作树干净，rebase `origin/main`，改动含 Java 时跑 `javac_check.sh`，列出场景则调用本工作树的 `smoke_museum.py --worktree`（非零退出码原样返回并中止），`--dry-run` 把上述全部检查移到 `.local/tmp/pkg-dry-<名>` 的临时 detached 副本上（不动该包分支，检查完删除副本），通过后不推送、不改行、不删工作树，以 `TODO: <名> landed` 删除进行中行，`git push origin HEAD:main`，快进主检出，移除工作树（占用时 `git worktree prune` 重试，仍失败则报告残留路径）并删除本地分支，打印推送提交；`runtime [--commit <sha>] [--dry-run]` 等该提交（默认 `origin/main`，等待期间 `origin/main` 前进、或该 Build 被更新的推送取消时，就改等新提交）的 Build 完成，失败打印日志尾部，成功后等试玩客户端关闭（运行中的冒烟与截图不妨碍更新，它们继续用旧包），再快进主检出并运行 `update_museum_runtime.py --run <run-id>`（`--dry-run` 在此之前停止），适合合入后后台运行；`status` 每包一行（工作树、分支、领先 `origin/main` 的提交数、未提交文件数、报告 STATUS 行、待办行是否存在），并列出行无工作树、工作树无行、报告 DONE 但未合入。

`scripts/smoke_museum.py`：用法与自查边界见[环境与命令](../AGENTS.md#6-环境与命令)。与截图共用 `headless_server`：只把游戏目录在 `.local/experimental-map/museum/` 内的客户端、或 `play-museum/process.json` 记录且启动时间吻合的 PID 当作用户客户端；它运行时冒烟与截图排队等待（提示一次后静默轮询），`--no-wait` 改为直接拒绝。每个冒烟服务器与截图客户端启动前在主检出 `.local/smoke/slots/` 取一个全机名额（默认名额见[开发约定](../AGENTS.md#6-环境与命令)，`--slots` 或环境变量 `MCY_SMOKE_SLOTS`；记录 pid 与进程启动时间，进程退出的记录自动回收），名额满时先来先得地等待；`python scripts/smoke_slots.py` 列出占用与排队。取名额前若 F: 空闲低于 50 GB，先删除 24 小时前且未被引用的冒烟实例、pack 与截图运行的大文件（窗口日志、JFR、stdout、mod／run），仍不足就等待并提示一次；`pkg.py land` 合入后同样清理一次。`<py> scripts/smoke_prune.py [--keep-hours N] [--apply]` 完整删除这些目录（默认只列出）。“被引用”指目录名（`instance-<12 位十六进制>` 或截图的 `<日期>-<时间>-<6 位>`）出现在 docs/、AGENTS.md、本文件、`.local/HANDOFF.md`、`.local/refactor/` 或 `.local/reports/` 的文本里；要长期保留的证据须在待办或报告中写明目录名，结果与基线索引不算引用。重复场景的日志与最终读数按运行顺序编号保存，保留每轮结果。一个场景失败时逐条列出全部问题（每个命中过的 `forbid`、每个缺失的 `expect`、`verify`、个体命运与门槛检查的每个失败发现，后者附个体与 tick），不只报第一条；运行前还用 `git archive` 导出 `origin/main` 的场景源码，对照同一场景的 `expect`／`forbid`／`verify` 与时长，不同就打印 `WARNING` 行点名差异（只警告，不改判定）——曾有工人在自己的树里放宽期望，自测因此通过。

每轮冒烟另做个体命运检查（`smoke_fate.py`）：窗口内任何油库里死亡或进入濒死、受溺水伤、受 `inWall` 窒息伤、受饥饿伤，或同一个体以同一原因重复同一拒绝 400 次以上（含限频的 `suppressed=N`），都按 FAIL 打印对应日志行。场景真实路径包含的结局在 `smoke['allow_fate']` 声明：`kind`（`death`／`drown`／`in_wall`／`starve`／`loop`）、`who`（该个体自己打印的某行的模式，如 `uuid=…` 或 `pit slave `；省略为任何个体）、`match`（命运行须匹配的模式，如死因）与 `why`。`python scripts/smoke_fate.py [--scene 场景ID] <日志> ...` 复查已存的场景日志。

`--repeat N` 在一个服务器里把每个场景各跑 N 次（每轮各用由会话种子与轮次派生的独立种子，重跑命令带该轮种子），打印“场景 × 通过数 × 各失败原因次数”，并写入主检出 `.local/smoke/results/<commit>-<时间>-<随机后缀>.json`。`--baseline` 改跑本树与 `origin/main` 的合并基点（`--base-commit` 可指定）：用 `git archive` 导出该提交的 `scripts/`、`src/` 与 `gradle.properties` 到 `.local/smoke/baseline/`（用完即删）编译运行，按提交与命运检查版本把各场景的通过数与失败原因累计在 `.local/smoke/baseline/<commit>.json`，只补跑不足 `--repeat` 的次数；同一提交的基线同一时间只有一个在跑。`--compare` 先跑本分支，再补齐基线，逐场景对照并列出只在本分支出现的失败原因。`python scripts/smoke_baseline.py [--repeat N，默认 2] [--seed N] [场景ID ...]` 拉取 `origin/main` 后，仅在机器空闲（无用户客户端、无人占用或等待名额）时补它的基线，一旦有人要用就在下一个场景前停下。

`--layout` 读取生成初态、40 tick 初始化及场景延迟召唤后的实体包围盒、方块碰撞形状和水面。初始无支撑者须在初始化内落稳；已落稳者随后正常跳跃不算坏起点。覆盖命令生成的聚落、人员、帽船、场景道具及玩家，排除地形施工掉落物；同场地互斥按钮共用地块，群系各用所属地块。生成失败、延迟演员缺失、函数加载失败、穿模或越界均返回非零；每场 `*.layout.json` 保留读数。

`python scripts/capture_museum.py [--worktree <工作树>] [--smoke-commands] [--vantage] <场景ID> [...]` 复用已装 CI 运行包，在主检出 `.local/captures/<run>/` 的独立世界启动隐藏客户端，按按钮入口和观察点录制 PNG、带 tick 标签的联系表与 `frames.json`（请求／实际 tick、耗时、窗口状态）；需 Windows、Pillow、nbtlib，`--worktree` 仅 javac 组装，用户客户端运行时排队等待（`--no-wait` 拒绝）。`--smoke-commands` 按场景 `smoke.commands` 的 tick 执行真实交互（如摇帽），不改行为结果；捕获窗口仍由 `capture` 决定，须覆盖待测事件。`--vantage` 固定使用按钮入口的观察点，关闭跟拍和 observe，用于对照服务器 VANTAGE 的取景读数。时序在场景的 `capture=`（`shots` 与含首尾的 `sequences=(起点,终点,步长)`）；捕获开关下另有客户端 `/museumcapture shot <延迟tick>`、`/museumcapture sequence <秒数> <fps>`。只作视觉自查，不算试玩验收，不操作桌面或用户世界。

场景 `vantage=(横向偏移,纵向偏移,朝向[,俯角])` 落在该列地面；五项形式在俯角前指定相对场地基准脚底高度的偏移，以避开屋顶与柱子。俯角默认 20 度。`capture` 的 `observe=False` 仅在截图时关闭头顶状态字，不影响普通场景读数。

捕获目录中的 `pose.jsonl` 记录已绘制主体的片段、实际片段时刻、根矩阵、跨越标记、混合后的持物矩阵和共享 `PoseChecks` 结果；截图帧另存骨骼与皮肤输入，完整几何证据以 `evidence` 指向同目录的 `.json.gz`，`pose_check.py --frames` 可读取。`frames.json` 的 `gameTime`、`poseFrame` 对应绝对游戏 tick 与姿态帧号。片段／阶段变化、标记跨越与新指标异常会补截图；`capture_readout.py` 按标记、指标和服务端里程碑选择约十帧，写 `selected-frames.json`、`geometry.json` 并生成联系表。实体朝向（制作片段根骨转动之前的 `body_yaw`）与密集服务端样本按主体、维度、绝对 tick 精确对齐；含片段根骨转动的 `yaw` 仍用于跟拍；骨骼角速度在实体绘制坐标系 `pose_basis` 内按相邻渲染帧的实际时间差计算，排除整体转向及片段／阶段切换；旧输入没有该坐标系时仍按原始矩阵计算。原始检查中的缺输入仍保留为 `error`；汇总报告按主体、帧号和指标列为 `no-evidence` 并给出原因，不视作通过，也不使整个视角报 `complete=false`。`complete` 只表示报告处理完整，数值非法等输入错误仍使其为 false；覆盖范围须同时看 `no_evidence`。身体、伤口替换片、破帽与替代帽使用绘制时发出的三角面取证；产出出口与尚未出生的幼体分别归入 body／prop，缺失的道具尖端仍报明确的缺证据；只声明 `power_start..power_end`、未声明入水深度与桨尖的划水片段，在动力段内报缺证据，不再算作无划水动作。时间节拍须用 `0.5s 触碰` 或 `20t 落地`，不接受格序号。

`capture.follow` 可指定跟随的实体选择器；多个观察角度用 `capture.views={名字: {follow: 选择器, offset: (左右, 高度, 前后), criteria: 本机位观察标准}}`，摄影机偏移按上一渲染帧实际画出的身体朝向计算。方块使用 `look_at: (x,y,z)`，目标相对场景 anchor 与脚底高度，保留原观察点的摄影机位置。每个角度重新运行真实场景，跟随只移动旁观者与摄影机；清场会取消跟随。跟随截图关闭观察标签与聊天覆盖，PNG 与联系表按角度名字分目录。

`<py> scripts/capture_review.py --worktree <工作树> <场景> [--criteria <原话或文件>] [--beats <时间节拍文件>]` 一条命令捕获并审图，捕获排队与启动输出即时显示；`--reuse <捕获目录>` 复用已有结果。审图和捕获命令均可用 `--view <完整机位ID>` 复查指定机位（可重复，如 `carry_home_chen`），不改变场景时长与断言。节拍默认按场景时间对齐；审指定片段时加 `--beat-clip wake`，按同一截图帧里各主体实际片段的 `ct` 秒数对齐，不借用邻帧；选中帧没有该片段时明确报错。`test_capture_review.py` 离线核对时钟、主体和即时输出。AI 只判断各机位下的动作是否看得懂，几何问题只来自共享指标，均保留为报告项；缺证据或审图服务错误退出 2。`--compare-old <旧审图脚本>` 对同一组图额外运行旧提示词，保存两份判断；旧提示词的报警不自动等于误报，须结合原尺寸画面与指标证据复核。报告在主检出 `.local/reports/capture-review/`，不自动登记用户待查看页。

截图运行独占主检出 `.local/captures/capture.lock` 的文件锁；后来的截图命令等待前一次结束后自动运行，冒烟不取此锁。截图同样占一个全机名额，并支持 `--no-wait`、`--slots`。

持物交接的 `held` 检查使用同帧两端握点与平滑插值进度，静止道具使用单点锚点；接口见[姿态检查接口](../src/main/java/io/github/longshou/mcyukkuri/pose/check/README.md)。`<py> scripts/fixtures/capture_readout/replay_held.py <20261108-165057-f8d575 捕获目录> --output <本地结果.json>` 从存档嘴部网格、骨骼、制作轨道与交接日志重建两帧缺失的路径证据，复算旧告警，并检查故意偏离路径的输入仍失败；原始证据不改写。

没有姿态记录的机位仍按游戏时间选帧并完成可读性审图，几何报告注明 `no-evidence` 原因；已记录主体在截图帧缺席也单列缺证据。跟拍目标缺失或未绘制时照常结束捕获窗口，帧记录 `cameraReady=false`，报告注明机位缺证据。任一机位缺证据时整次审图退出 2，其他机位继续处理。未写机位标准时不套用整场“看点”，避免近景机位因画外主体被误报；需要可读性判断时在 `capture.views` 的 `criteria` 写明该机位可见内容。妖梦口中楼观剑的绘制矩阵、原点握点和嘴部锚点接入 `held` 检查；收起木棍时不声明口中持物。



## 架构建议检查

检查客户端读取服务端组件、未许可的 `getPersistentData`、原始 `isHouse/isPredator` 和内联全限定类型名；覆盖调用、方法引用、跨空白与显式泛型调用，排除注释、字符串、字符、text block 和 package/import。同步视图与快照不因组件词根而禁止。另有三条重构守卫：`BODY_WRITE`（`motor/` 以外对可确定为 `YukkuriEntity` 的接收者调用 `setPos`、`moveTo`、`teleportTo`、`setDeltaMovement`、`addDeltaMovement`、`move(MoverType…)`、`startRiding`、`stopRiding`、`setYRot`／`setYBodyRot`／`setYHeadRot`，仅转调 `BodyDriver` 的覆写除外）、`BASE_IMPORTS_DLC`（`aquatic/`、`client/aquatic/`、`registry/` 以外 import `aquatic` 包）、`GEOM_PURE`（`motor/geom/` import `net.minecraft`）。现有 `BODY_WRITE` 迁移基线已清零，许可条目为空；新的写入只能出现在 `motor/` 内，或经 `BodyDriver`／`Motor` 门面。

`architecture_exceptions.json` 按精确文件／方法签名、归一化 token SHA-256、出现次数和源码顺序记录许可；每个入口列 receiver、键读写方向与理由。方法代码、字面量、签名或次数变化须复核；注释和字面量外排版不改变 hash。两个方法外键常量 `GroupCards.BAR`、`Cannibalism.HUNT_COOLDOWN` 也按 token 锁定。没有目录／类级豁免或自动刷新基线命令，不能只换 hash 消除诊断。

这是有限词法检查：仅解析确定声明、简单 `var` 别名、`instanceof` 绑定及可确定的字段；复杂工厂、继承、跨方法数据流、动态 NBT 子键和别名逃逸仍需人工复核，尤其是 `Custody.store` 入参。未知 receiver、方法归属不明、Unicode escape、词法／括号错误、包路径不符及读取失败会报告覆盖缺口。全名检查从标准包根和源码 package/import 收集包根，按大写类型段识别；全小写长链报人工核对，非常规外部包根和复杂嵌套／泛型类型不保证完整覆盖。组件 getter 返回类型与 `ComponentKey` 登记交叉核对，但不证明全部 Java 语义成立。

诊断按路径／行／列排序；退出码 `0` 为扫描范围内无发现，`1` 为违规或许可漂移，`2` 为覆盖／读取／配置错误（优先）。`--json` 输出同样诊断；`--root <目录>` 只改扫描根，仍使用本工具旁许可。没有修复模式；报告需由调用者重定向至 `.local/reports/`。

## 试玩清单与反馈入档

`<py> scripts/playtest_list.py [--since <commit>] [--until <commit>] [--no-html]`：给用户的试玩清单。上次试玩提交取最近一场会话（`play-museum/*.session.json` 的 `runtimeConfig.commit`，`--since` 覆盖），已装运行包取 `museum-runtime.json`（`--until` 覆盖）；两个提交的 `docs/TODO.md` 差异只决定哪些状态仍开放的条目算新增或变化，措辞一律取当前检出的 `docs/TODO.md`（跨版本靠标题／引导短语／重叠度对上；在当前待办里找不到对应条目的跳过，列在标准错误）。条目里反引号包住的场景 ID 经 `museum_scenarios.py` 换成按钮：一场一行，按场地分组（场地与场内场景都按博物馆顺序），场内复测失败在前、已接入待试玩次之；每条目取最短看点（看点／完成子句，约 40 字）；顶部有场地、场景、条目计数，未指名场景的条目与已不存在的场景 ID 放在末尾“其他”块。Markdown 打到标准输出，页面写到 `.local/previews/playtest/index.html`；总入口 `index.html` 由 `build_preview_hub.py` 按分类生成，并链接此页。场景 ID 以当前检出为准，TODO 条目只认反引号写法。

`<py> scripts/feedback_intake.py [--session <id或前缀>]`：把最近（或指定）会话的 session JSON、该场 stdout／stderr 日志（以及最近一场的 `latest.log`、`debug.log`；更早场次的这两份已被覆盖）复制到 `.local/reports/playtest-<日期>-<时间>/`，原文件不动；按聊天标题 `【<场景标题>】看点：` 识别用户打开过的场景，窗口取到下一次场景启动为止（并排场地的场景会共享窗口），逐场汇总死亡、溺水、`inWall`、饥饿伤害、拒绝（含原因计数）、异常，以及场景 `smoke.expect` 的断言行是否出现（无断言的场景给服务端前缀计数），写成同目录 `summary.md` 并打印。标题已在场景源码里改名的场景标为找不到；日志只含时分秒，日期由会话开始时间逐日推算。

## 预览与导出

`<py> scripts/check_pose_consumers.py <基线提交> [--lore] [--stow]` 与 `node scripts/check_pose_consumers.cjs <基线提交>` 对照同一批输入的 Python 制作门槛、矩阵、辫链及软件渲染；`--lore` 执行全部 lore 家族的真实门槛，`--stow` 直接复核戴帽／脱帽分别从岸上与船上持棍姿态进入的门槛。`--inject-arc`／`--inject-vertex` 是应非零退出的破坏对照。报告放 `.local/reports/pose-consumers*`；不登记用户收件箱，不代替美术或玩法验收。制作桥接与交互页的核心服务接口见下文的姿态检查接口。

`<py> scripts/pose_check.py --model marisa --clip bank_beckon --t 0:1.6:0.05 --metric tilt,wet,pass [--water 0.5] [--partner child=marisa:baby@0,8,-2] [--care x,y,z]` 用共享 Java 姿态核心输出含单位、判定带和证据位置的 JSON；`--frames <capture>/pose.jsonl` 重算捕获证据，`--serve` 每行接收一个 JSON 请求，`--vertices out.f32` 输出最后一帧皮肤点，`--pose-out pose.jsonl` 保存可回放姿态。只编译并缓存纯核心及其纯函数依赖，不跑 Gradle、不启动游戏；需要本机 JDK 21 和已有 Gson/JOML jar。七项检查只报告，缺水面、道具尖端、伙伴或时序证据时报错，不把缺证据算通过；输入/证据错误退出 2，有效报告（含 fail）退出 0。坐标、配对、允许区、假设带、服务协议与覆盖边界见[姿态检查接口](../src/main/java/io/github/longshou/mcyukkuri/pose/check/README.md)。

`<py> scripts/preview.py <子命令>`（`PYTHONUTF8=1`；Pillow、Node.js）统一生成预览并登记总入口，工作树默认共用主检出 `.local/previews/<主题>/`，可用 `MCYUKKURI_PREVIEWS` 指定其他输出。`strip <主题> --model reimu --clip idle --times 0s,5t,10t` 出渲染条和帧图集；`--prop <ID>` 改看道具，`--beats beats.txt` 每行用秒／tick 时刻（如 `0.5s 抬头`、`20t 回落`，不能用格序号），`--view 0 4 --view 35 8 --view 90 4` 在同一渲染条逐行排多个机位，`--partner marisa --partner-offset 20 0 0` 同场放伴侣。`--face-preset <预设JSON>` 用运行表情库的同一合成代码按草稿预览预设贴图与几何（预设须在某份 `standards/yukkuri-library` 内，部件贴图按该库解析），不改运行库；`--before <旧检出或Git ref>` 出同机位、同时刻新旧对比，同时给 `--face` 与 `--face-preset` 时前者是旧侧运行表情、后者是当前侧草稿。`capture <主题> --source <截图视角目录>` 从 `frames.json` 出带帧号／tick 联系表和 1 倍速 MP4（相邻 tick 差 / 20 秒，20 fps 复用静帧保留长间隔，末帧保留 1 tick；使用本机 `.local/tools/video-decode/` 的 ffmpeg），`--ticks 起点 终点 --crop X Y W H --zoom 倍数` 保留逐帧裁图，`--compare <另一视角目录>` 按相同 tick 并排。`motion <主题>` 按分镜、动态分镜、真实片段、截图／录像、审图 JSON 固定排版，自动读取主题已有材料，也可用 `--storyboard/--animatic/--strip/--capture/--review` 提供文件；缺项标注待提供。`collection <主题> --source <录像目录>` 整理已有 MP4；`hub` 只刷新本地页面登记（已有无页面主题自动出媒体页，留存预览不声明为当前运行效果）。生成命令可带 `--title` 与 `--decision <要用户决定什么>`，后者同时登记待查看；也可 `inbox add <ID> --title <标题> --url ../<主题>/index.html --decision <决定>` 或 `inbox remove <ID>`，首次接管保留原条目。缺帧、无效时刻、未知片段等错误非零退出，输入／制作失败保留当前页面；详细参数见各子命令 `--help`。查看服务用 `scripts/serve_review.py --port 8765`，页面效果由用户手工查看。

`python scripts/build_art_catalog.py` 从 Git 跟踪文件、`art/runtime-sources.json`、标准库目录及包内登记生成[素材目录](../art/CATALOG.md)；分类树、路径前缀、运行资源 ID 模式及登记键维护在 `art/catalog.json`。运行 ID 优先，路径前缀取最长匹配，登记输出补齐未指定文件，共用资源另列；伤口目录按路径归类，不随种族 ID 分散。不扫描 Wiki。`--check` 只读检查未归类文件／登记族及目录过期，任一存在返回 1。新增文件先 `git add` 再生成；游戏使用只统计 Java／运行 JSON 的静态资源引用，动态加载不作否定结论，预览只链接工作树内已有文件。

所有新预览统一写到 `.local/previews/<主题>/`，总入口为 `.local/previews/index.html`。同主题只保留当前版本；截图和检查报告分别写到 `.local/screenshots/`、`.local/reports/`，不入库。

`python scripts/build_preview_hub.py` 生成纯预览入口、当前模型／物品缩略图、声音索引与连续场景 GIF／可拖动帧序列；`--render` 另行生成专项图（部分依赖现有原版资源 jar）。需要 Pillow、numpy 和 Node.js。`build_preview_props.py` 复用制作源的软件渲染器，只读当前打包网格和贴图，不导出游戏资源。`python scripts/serve_review.py --port 8765` 只启动本机 HTTP 服务，再手动打开它输出的地址。页面模板在 `preview/hub/`，模型查看器源码在 `preview/review/`；收录与专项图片更新边界见[预览说明](../preview/README.md#内容与边界)。生成流程不启动浏览器、游戏或 Java 编译。

总入口调用 `build_preview_library.py`，按 `preview/hub/categories.json` 生成游戏分类目录、物品缩略图及试玩入口；`preview_block_model.py` 适配原生方块元素供离线渲染；`preview_inventory.py` 从 Java 注册与运行引用过滤残留资源。它们只读资源与场景源码，输出在 `.local/previews/`，分类缺口报告在 `.local/reports/preview-library.json`；不会生成试玩世界或运行场景。

`asset_export_checks.py` 是各导出器 `--check` 共用的只读比较库（生活、家庭、家油照料与周边）。普通 JSON 仅容忍 LF／CRLF 差异，仍核对字段顺序、空格与结尾换行；PNG 检查按字节比较，生成器仅在格式、模式、尺寸和像素完全一致时复用现有编码。三个道具包的网格生成器仅复用符号零（`-0.0`／`0.0`）等值的既有表示；其 Blockbench 模型按 JSON 内容比较，仅容忍顶点坐标不超过 `1e-15` 的计算尾差和内嵌 PNG 的等价编码。草麦食盆先核对预览图集裁切像素，再复用所选原始调色板字节。生活口部数据从圆润选用网格重新推导并对照选用记录；遗体物品模型从 `art/props/house-care/yukkuri_remains.item.json` 复制当前渲染配置。

跨平台模型指纹通过 `runtime_model_fingerprint.py` 比较原始、LF 和 CRLF 三种字节表示；Java 客户端使用相同的 `ModelFingerprint` 规则。只容忍换行转换，不忽略模型数值、空格、字段顺序或结尾换行的变化。保留已有导出指纹，避免仅因跨平台检出而更换存档中的表面标识。

`verify_puff_motion.cjs` 检查整身鼓腮：每种形态鼓腮为只驱动根骨骼的2.8秒循环且首尾回到静止、不再带脸颊形变与包络、鼓起幅度在预算内（豆形不拉高）、俯仰≤4°侧倾≤2°无转向、最低点不离地、表情时间线以 `cheek_puff` 收尾并让位于哭脸、完整体操段脸时间线一致。只读运行资源，离线运行。

整身鼓腮、侧弯、跳跃与体操由 `export_motion_corrections.py` 从动作配方和当前骨架生成动作覆盖资源，并生成各形态的 `cheek_puff.png`。默认按片段缓存编译、辫子跟随与辫节传播；缓存键包含配方项及其 base／镜像依赖、共享参数、所属模型与位置适配、表情文件名和编译代码／外部几何指纹，持物交接校验另含持物尺寸与两种起始持物姿态。缓存位于当前工作树 `.local/cache/motion-compile/`，可直接删除；输入或缓存内容指纹不符即重编。`--check` 复用未变片段、重编变化输入，并核对完整输出文件 SHA-256 与贴图，不写运行资源或来源清单（可填充本地缓存）。`--full` 忽略缓存重编全部，也可由 `export.py --full` 传入。`--only id,... --trial 目录` 把试验结果写到指定目录而不碰运行资源。替换基础模型后重新执行；`--update-only id,...` 只重建这些形态并保留其他形态的已选片段，可与 `--check` 合用。工具自查：`<py> scripts/test_motion_compile_cache.py`；加 `--integration` 在 `.local/tmp/` 隔离副本中运行全量基线、单片段／位置适配改动的字节对比、耗时与损坏缓存反例（耗时较长）。`verify_motion_corrections.cjs` 检查地面接触、挂接一致性、跳跃高度及表情时段（鼓腮按2.8秒），并离线生成前后对比与动图，不操作浏览器。见[当前动作适配](../art/source/motion-corrections/README.md)。

素材集中检查使用 `build_review_catalog.py` 从当前运行资源生成模型／声音索引，`start_review.ps1` 启动仅监听本机的只读服务；`-NoOpen` 只启动服务并输出地址，不打开浏览器。`stop_review.ps1` 按进程记录停止该服务。`node scripts/verify_review.cjs` 通过命令行验证动作数据并生成模型缩略图，不操作浏览器或游戏；Python 需有 Pillow，可用 `REVIEW_PYTHON` 指定解释器。检查数据写入 `.local/reports/model-review/`，联系表写入 `.local/previews/model-review/`，缩略图写入 `.local/previews/models/thumbs/`。入口见[素材检查室](../preview/README.md)。

灵梦稀毛／歪脸的定向导出为 `export_reimu_deficient_rounded.py`，复用现有不足形态圆润生成逻辑。当前运行模型统一用 `build_preview_hub.py --render` 离线预览。`preview_reimu_deficient.py`／`preview_marisa_deficient.py` 保留源、快照、运行资源指纹与动作采样检查，使用 Python、Pillow、numpy 与 Node.js，预览写入 `.local/previews/<reimu|marisa>-deficient-rounded/`，报告写入 `.local/reports/` 下同名主题目录；运行后重建预览总入口。通用预览不替代这些专项检查。来源见[制作包](../standards/yukkuri-library/subspecies/deficient/rounded-reimu/README.md)。

`verify_marisa_deficient_browser.cjs`、`verify_rounded_browser.cjs`、`verify_hat_interactions.cjs` 是历史浏览器自动化工具，不是当前 Agent 的检查入口；按根 AGENTS.md，不执行这些脚本。交互页面效果由用户手工查看。

人工试玩当前使用[油库里试玩场](../docs/FEATURES.md#试玩场与动作博物馆)：`build_museum_full.py --install` 现场生成全新世界（数据包加种子 `level.dat` 的元数据）到 `.local/experimental-map/museum`，整体替换，不做原地修补；各开放场地的地形在 `museum_fields.py`，场景在 `museum_scenarios.py`，生成真实个体的词汇在 `museum_dsl.py`，按钮与观察台在 `museum_sites.py`，动作目录在 `museum_actions.py`；需要 Python 3.11+、nbtlib。`--catalog` 另外生成完整动作适配清单。根目录 `启动动作博物馆.cmd`（唯一的试玩启动入口，文件名沿用）调用 `launch_museum.ps1`：世界缺失或生成器改动时先生成，再用已有 CI 运行产物启动，不编译；运行模组比数据包旧时，进图会大字提示“运行模组过旧”。`check_museum_pack.py` 离线核对数据包里的每条命令与函数引用、场地布局、按钮与场景的对应，`render_museum_map.py` 画俯视布局图。其他输出目录约束见[开发命令](../AGENTS.md#开发命令)。

圆润体型与灵胖发育的资源导出使用 `export_rounded_runtime.py`、`rounded_wound_surface.cjs`、`export_rounded_attachments.py`、`export_badges.py --mounts-only` 和 `export_family_assets.py`；按受影响的资源选择，不要求逐个执行。束具仍由标准库 `drafts/operation-tools/build.py` 生成。`build_rounded_preview.py` 将专项预览模板写入 `.local/previews/rounded-runtime/`，只服务专项预览依赖，不作为当前通用预览入口。来源见[圆润运行包](../art/source/rounded-runtime/README.md)。

接触器具的制作与导出入口为 `standards/yukkuri-library/drafts/operation-tools/build.py`。它从参数配方及所选角色表面制作器具、固定带、铁箱和培养瓶，同步可编辑模型、接入快照、运行资源、配方与指纹；`--check` 只核对导出。确认状态见制作源登记。

| 脚本 | 输入与用途 | 执行边界 |
| --- | --- | --- |
| `export_predator_assets.py` | 从捕食种配方导出的模型同步两种实体快照、256 图集、19+4 动作与来源登记 | 只导蕾米莉亚、弗兰，不替换现有七种；`--check` 只读核对同一批输出，不写文件 |
| `preview_predators.py` | 生成两种运行数据交互预览和八视图拼图 | 使用实际导出网格离线渲染，不启动游戏 |
| `settlement_site_preview.py` | 用本机 JDK 编译模组源码与 `settlement_site_preview/SiteDump.java`，由真实的 `SitePlanner`／`SiteBuilder` 在内存里的合成坡地／平地上建出各适居群系的小群与村，镜像 `NaturalShelter.survey` 逐洞勘测并检查空地到洞口、嗯嗯坑的可达（`--seeds N`），出等轴预览 | 不用 Gradle、不启动游戏；需要主检出的 `.local/tools/java`、`build/moddev/artifacts` 合并 jar 与 `.local/gradle-home`；预览写 `.local/previews/wild-settlements/`，`build_preview_hub.py` 只收录输入指纹一致的现有输出，不调用此编译脚本 |
| `export_predator_language.py` | 增量合并捕食种专属语言、生成蛋模型和低权重森林生成规则 | 保留其他任务语言键 |
| `export_wound_surfaces.cjs` | 读取七种当前运行角色与选定 R3.1 源，导出外皮细分、五官挂接、骨架、动作及材质；记录来源指纹；`--check` 只读核对每份伤口的来源指纹与当前运行模型一致，`--ids=` 限定角色，圆润体型由 `rounded_wound_surface.cjs` 管理 | 写 `yukkuri_wounds/` 与伤口适配包，不修改标准角色源，不启动游戏 |
| `verify_wound_runtime.py` | 核对全部伤口与动作骨架；核对伤口源与输出指纹、有限数值及共点权重一致性，可传角色 ID 限定后者范围 | 只读，不启动游戏 |
| `export_human_world.py` | 从制作源同步商店、三类工厂、生成规则、五套服装、原创机台与商品、生成蛋及中英语言；`--check` 核对指纹 | 默认写本模块资源与登记；检查只读，不启动游戏 |
| `catalogue_hakkei_dialogue.py` | 只读原版八景 U 角色 CSV 与 ModLab 翻译／新增 TSV，生成分离的对白检索与来源指纹 | 写 `art/dialogue/native-u/`，不写来源游戏和运行资源 |
| `build_aquestalk_dialogue.py` | 读取选句与配方，调用本机 AquesTalkPlayer，输出 288 条 WAV／OGG、发音符号及试听页；204 原版与 84 原创分别标注 | Windows 本机制作；`--work-directory` 隔离工具副本，匹配文本、参数和指纹时复用录音；不打包程序、字典或 DLL |
| `export_dialogue.py` | 核对原句／原创来源与录音文本，导出台词、语言资源、OGG、时长及声音索引，保留其他任务条目 | 默认写运行资源；`--stage` 先导出到指定待核对目录；`--check` 只读检查，不启动游戏 |
| `export_runtime_models.py` | 旧七模型批量接入配方；当前圆润选用存在时拒绝执行，避免覆盖模型 | 当前圆润形态用 `export_rounded_runtime.py`；家油定向导出仍复用其函数，见对应制作包 |
| `runtime_house_interactions.py` | 供模型导出器调用；制作家油俯身夹取、短辫持物、放下与蠕动曲线，登记辫梢挂点 | 无独立启动入口，不改标准造型 |
| `runtime_waddle.py` | 供模型导出器调用；将家油走动适配至实际连续身体截面，增强五种普通角色的油菊摆动 | 不写文件，不改标准造型，保留 1.2 秒周期 |
| `runtime_animation.py` | 供模型导出器调用；按游戏矩阵采样并生成帕秋莉长发接地骨骼与混合绑定 | 无独立启动入口，不改标准造型 |
| `runtime_preview.py` | 供模型导出器调用，也可单独运行；用 `art/source/standard-runtime/` 的快照与贴图生成浏览器预览数据 | 只读快照，只写 `.local/previews/standard-runtime/`，不入库 |
| `export_badges.py` | 从徽章挂点配方及当前饰物几何导出挂接数据、手册配方和语言；稀有形态沿用其基础形态的挂点（被炉魔理沙用自身帽檐），`--check` 核对一致性 | 默认写游戏资源；检查模式只读 |
| `export_living_assets.py` | 从生活设施配方及圆润选用网格导出当前归属的方块状态、材质与嘴部表现数据，更新来源指纹；`--check` 检查现有导出 | 周边模型交给周边导出器；默认写资源与来源登记，检查模式只读，不启动游戏 |
| `export_family_assets.py` | 从生命周期和家庭配方导出共用茎形、挂接与成长参数；`--check` 核对指纹 | 只写游戏适配资源，不覆盖角色标准 |
| `export_house_assets.py` | 从家油照料配方与遗体物品渲染配置选用源导出当前归属的方块状态、物品、合成和来源；`--check` 核对导出 | 默认写资源，检查模式只读 |
| `export_peripheral_assets.py` | 从周边固定快照导出静态网格、贴图、物品／方块调度和合成，记录输入输出指纹；`--check` 核对导出 | 只读取所选快照，不改变标准确认；草麦引用原版贴图资源位置 |
| `export_pet_goods_icons.py` | 把 `art/props/pet-goods/` 的四张 16×16 宠物照料图标（糖粒、布丁、牵引胴带、襁褓布）导出到 `textures/item/`，写 `art/props/pet-goods/provenance.json`；`--check` 核对 | 只读快照；默认写资源与来源登记，检查模式只读 |

标准素材包的制作和局部导出脚本保留在 `standards/yukkuri-library/` 内，见其 [目录约定](../standards/yukkuri-library/AGENTS.md)。不要把有相对路径依赖的包内脚本移到这里。

新增脚本须说明输入、输出、是否写文件及适用环境。临时脚本放项目内 `.local/`；可复用后再整理为正式入口。所有脚本遵守根目录 [AGENTS.md](../AGENTS.md) 的本机执行限制。

Wiki 漫画参考使用 `wiki_reference_library.py`（Python 3.10+、Pillow）：`fetch` 读取 `wiki/art-references/sources.json`，从登记的公开作品正文保存原图与来源清单；缓存、失败记录放 `.local/reference/wiki-manga/`。`check` 离线只读核对解码、尺寸、哈希和文件登记；`catalog` 从已核读 manifest 生成逐作品 Markdown 目录；`sheets` 只在 `.local/` 生成联系表。它不操作浏览器、不登录站点、不绕过证书验证，不写游戏资源。详见[参考库维护](../wiki/art-references/README.md#出处索引)。

E-Hentai 补充库使用 `wiki_ehentai_reference.py`（Python 3.10+、Pillow）：`fetch` 读取 `wiki/art-references/ehentai/sources.json`，按登记上限与实际公开图页链接增量保存图片及 manifest；网页缓存和总览放 `.local/reference/ehentai/`。`check` 离线只读核对登记、解码、尺寸和 SHA-256；`catalog` 生成该子库作品目录；`sheets` 生成临时总览。只保存站点图页提供的版本，不承诺作者母版；不使用登录凭据、浏览器自动化、付费下载或访问限制绕过。选页须在图集首页已经观察到，新页默认未核读。详见[补充库维护](../wiki/art-references/ehentai/README.md#来源登记)。

亚种与先天形态入口，详见[实现说明](../docs/FEATURES.md#种族与亚种)：

| 脚本 | 用途与写入边界 |
| --- | --- |
| `export_subspecies.py` | 读取已选螺壳／多毛源与不足形态配方，生成八套模型、快照、口部数据、帽船和螺壳道具；`--stage` 只写源与快照，默认同步自身运行资源；`--textures-only` 只补表情导出，`--check-textures` 只读核对128图覆盖、解码、尺寸和来源指纹；需 Python/Pillow |
| `export_subspecies_wounds.cjs` | 读取八套实际运行几何与既有受伤表面算法，仅重建对应受伤表面和指纹；需 Node.js |
| `preview_subspecies.py` | 从当前运行三角面和贴图生成离线交互页、六角度拼图与40幅生态表情图；需 Python/Pillow 和现有 Node.js，不启动游戏 |

日常生态入口：`export_ecology_expressions.py` 按标准库 `expressions/runtime.json` 导出全部认可表情（共享辅助模块 `runtime_face_library.py`），保留已选贴图来源，补齐其他贴图与几何补丁；已烘焙预设按制作源记录的几何输入指纹复用权威补丁，输入变化的草稿从共享源网格按同一烘焙规则重算，新预设与身体构建器、库导出同步工具共用 `expression_geometry.py` 的偏移规则；双帧逐帧时长（`frame_ticks` 与 `frames` 一一对应）、模型清单、缺失表情的显式别名和目录动作清单在该数据中维护。`--check` 同时调用 `face_usage.py`／`FaceUsage.java`：使用本机 JDK 的语法树收集表情调用、字符串返回的表情提供者及其局部变量／参数转发，另读全部运行片段的 `expression_spans`，要求每个发出 ID 在全部模型上均可选或显式映射，并有对应贴图；缺失时报 ID、模型及来源。该检查不推断物种可达性，包含被覆盖片段与条件分支，任意外部输入仍由客户端未知 ID 报错处理。`build_ecology_audio.py` 制作原创短哼唱，二者支持只读 `--check`。`preview.py kit` 生成表情与基本／通用动作目录（认可缩略图、Java 与片段表情段引用数、真实运行资源实绘及共享 pose 模块的动画预览），只登记预览总入口。`preview.py strip --face <ID>` 可指定运行表情并按逐帧时长播放 A/B 帧。说明见[日常生态](../docs/FEATURES.md#日常生态)。

头饰与头发位置适配由 `export_placement_fit.cjs` 从[制作源](../standards/yukkuri-library/presentation/placement-fit/README.md)和当前运行模型导出 `yukkuri_presentation/placement_fit.json` 与来源清单；稀有形态沿用其基础形态的适配，部件索引按自身模型重映射。`--check` 只核对，`--only id,... --out 文件` 写试验结果；`--update-only id,...` 只更新这些模型并保留其他适配。基础模型不改写。`placement_fit.py` 是 Python 导出器共用的拼接规则：`export_motion_corrections.py`（鼓腮）与 `export_hat_interactions.py`（脱帽、帽托和幼油尺寸）都读取拼接后的网格。`verify_placement_fit.cjs` 检查头饰透穿、头发进入身体、五官遮挡、蝴蝶结贴合与地面，并输出前后对比、脱帽、动作和未改形态预览；`--checks-only` 不渲染。只做离线计算与渲染，不操作界面或游戏。见[游戏适配](../art/source/placement-fit/README.md)。

社区 Wiki 抓取使用 `wiki_fandom.py`（Python 3.10+、标准库）：`fetch` 通过公开 MediaWiki API（`list=categorymembers`、`action=parse&prop=wikitext`）把 Category:Pet Yukkuri、Places、Meta、Memes、Behavior、Biology 的页面源码、图片文件名与说明文字缓存到 `.local/fandom/`（不入库），每请求间隔一秒、已存在页面直接跳过，`--refresh` 强制重取；`list` 列出缓存状态，`show` 打印页面源码（`--page` 指定页名），`check` 离线只读核对缓存与 `wiki/hakkei-reference/sources-fandom.md` 的登记：每个缓存页面恰好登记一次，每个登记过的收录地点文件存在且引用该 F 编号。站点图片在 Cloudflare 质询后面，脚本只从页面源码登记文件名与说明，不请求图片、不绕过质询。见[fandom 条目来源](../wiki/hakkei-reference/sources-fandom.md)。`python scripts/test_wiki_fandom.py` 离线回归 `show` 的跨分类页面归属。

游戏参考分类使用 `wiki_reference_topics.py build/check`：以 `wiki/reference-library/game-annotations.json` 的逐图实体、情景、情绪、制作目标、画格和用途为主，合并图片 manifest 与小说阅读卡。生成四个交叉索引、兼容旧主题的页面、精选清单与离线筛选器；页面模板为 `wiki_reference_gallery.html`。默认显示已写明用途的游戏图片，精选优先，可组合筛选实体／情景／情绪／目标与画风。检查实体范围、所有存图的用途或队列状态、实际文件哈希和生成一致性。`quality-selection.json` 只提供辅助质量标签。两个抓取器均跳过 `collect: false` 来源；E-Hentai 的 `fetch --source` 若显式请求停采来源会在联网前拒绝。见[游戏参考库](../wiki/reference-library/README.md)。

全 Wiki 图片使用 `wiki_image_tags.py run`（Python 3.10+、Pillow、可调用的 pi Kimi 视觉模型）逐图分类：固定提示词沿用四个人工索引轴，并补行为动作与形象细节；结果校验后写回 `classifications.json`，SHA-256 不变即跳过，每图原子保存、无效回答重试一次，并用进程锁阻止并发写入；可用 `--workers`、`--delay`、`--limit` 和 `--path` 控制批次，中断后原命令续跑。`summary` 输出数量与分类汇总，`check` 离线核对全部文件、哈希和标签。人工确认的删除计划用 `apply-deletions --plan <JSON>` 同步清单、注释与精选记录，再运行两个作品目录生成器及 `wiki_reference_topics.py build`；不要用模型候选直接删图。
