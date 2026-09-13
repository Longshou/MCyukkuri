# 本机目录清理

2026-09-13。按用户要求整理 `E:/yukkuri/`，保留当前正在运行的实验地图、玩家存档、源代码、正式素材和未完成工作。顶层启动入口为 `E:/yukkuri/启动MC实验地图.lnk`，原有 `MCyukkuri/启动实验地图.cmd` 保持可用；目录分类见上级 `从这里开始.md`。

## 已完成

- 删除362个过期生成目录，约33.96 GiB：已结束任务的隔离 Gradle 构建、项目缓存、冻结类文件、生成资源和重复 Minecraft/NeoForge 依赖。没有删除全局 Gradle 缓存。各轮运行目录的日志、诊断、截图和世界仍保留；删除的构建树内诊断已另存并校验ZIP。
- 删除9个已合并到 `Eight景U-ModLab` 主分支且没有未提交改动的旧工作树，共约9.08 GiB。删除前保留独有诊断、图像、交付附件和存档，压缩后约2.00 GiB；Git分支历史未删除。`aquatic-capture` 有13项未提交改动，`bullying`、`smoke-dialogue`、`stinky-filling` 尚未合并，这4个工作树保留。
- 将顶层15项散落目录和文件归入 `游戏/`、`资料/`、`工具/`，同步41个执行脚本或一般文档中的路径。搬移保留原文件、游戏目录和存档；当前开发使用的八景 U v9.8、Java环境及3个项目保持固定位置。
- 根目录汉化APK与 `YukkuriDiary-zhCN/release/` 中的文件SHA256完全相同，已删除重复副本，释放127,883,912字节。原始APK移入 `资料/安装包/`，没有删除；汉化构建脚本的APK及字体路径同步更新。

## 保留与限制

实验地图正在运行，以下依赖未移动或替换：`.local/play-readiness/runtime-20260913-115036/`、其交付Jar、`.local/experimental-map/play/`、`run/saves/` 和现有Java环境。其他仍在运行的旧服务器、预览服务和Gradle进程未关闭。

正式 `docs/validation/` 及美术确认登记保留。历史运行记录现在是诊断材料，已清理类文件的快照不能直接重启；要复验应从对应源码重新隔离构建。保留的历史指纹描述原先被验证的文件，不宣称这些已删除产物仍在磁盘上。

旧美术原型仍有可编辑源与独有候选，未因年代旧而删除。`collections.json` 标为弃用的6类美术原型及旧包曾列入删除步骤，但自动审批仅返回 `blocked by policy`，没有执行该步骤，也没有改写原确认记录。根目录旧网络日志因此也未在该步删除。混合历史包及未确认唯一文件继续保留。

## 记录

详细清单在 `.local/directory-cleanup/`：`generated-plan.json`、`deleted-generated.json`、`worktrees-before.json`、`worktree-plan.json`、`deleted-worktrees.json`、`root-moves.json`、`path-edits.json`、`retained-build-diagnostics.zip` 和 `retained-worktree-evidence.zip`。保留的归档经过 ZIP 解压一致性检查，删除前再次比对归档SHA256，并逐项检查目标在指定目录内、没有符号链接或运行进程占用。

本轮未重新启动游戏或重跑全机制测试；检查范围是目录、文件引用、归档完整性和现有启动依赖。没有自动提交、推送或丢弃任何未提交源码修改。
