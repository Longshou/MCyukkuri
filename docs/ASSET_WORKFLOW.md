# 当前复用标准

新动作、表情与共用组件使用 [yukkuri-library](../standards/yukkuri-library/README.md)。在该目录运行 `python build.py` 与 `python validate.py`。基础动作和复合配方分开，生成物只在 assembled；以下旧资料库流程用于历史版本保存与恢复。

# 美术资产保存与恢复

保存采用中和未明确淘汰的动作，包括半速体操、表情、排嗯嗯、进食与噗咕较量。用户明确弃用的方案排除。以原始字节归档，不改写、简化或合并曲线。

## 两层保存

| 位置 | 保存内容 | Git 状态 |
| --- | --- | --- |
| `art/library/<分类>/<项目>/<内容哈希>.zip` | 可编辑模型、几何、动画 JSON、rig、表情数据、项目贴图、行为/生成/验证脚本及离线预览 | 纳入版本管理范围；本次未执行提交或推送 |
| `archives/art-preservation/` | 原工作目录的完整历史：额外包含原作参考图、逐帧输出、旧交付包等 | 仅本地保存，避免把原作提取素材和大量渲染帧打进仓库 |

原工作目录继续留在 `variants/`、`models/`、`textures/`、`preview/` 等位置。保持这些路径是为了保留现有生成器的跨目录依赖。缓存、浏览器用户配置、node_modules 与日志不作为动作数据归档。

压缩是无损保存。每个包都有相邻的 `.manifest.json`，记录原始相对路径、文件字节数和 SHA-256；`catalog.json` 记录包哈希、动作名、时长、循环、每条曲线的内容哈希与主要依赖。同名动作出现在不同版本时分别记录，不能仅凭名称互换骨骼。

互动行为不一定存在独立 `.animation.json`。例如噗咕较量的时序和胜负在 `scene.js` 中，进食的食量与中断处理在 `feeding.js` 中，这些脚本与静态导出一起保存。导出动作条数不等于独立玩法数量。

## 更新资料库

在原工作目录完成编辑和相应验证后，从项目根运行：

```powershell
python tools/project-library/manage.py build
python tools/project-library/manage.py verify --workspace
```

工具自动发现 `variants/` 下的项目。用 `art/library/collections.json` 维护名称、分类、候选状态和主要依赖；未分类的新目录默认按历史候选保存，不会漏掉。工具按内容哈希命名文件包，变更后新增包；未弃用方案的旧包保留。`collections.json` 的 `retired` 清单优先于自动发现；保存与恢复会拒绝其中的资产。历次目录索引保存在 `art/library/snapshots/`。校验失败时不要手动把结果改成通过。

`build` 不执行任何模型生成器，不改变现有动作或覆盖其源文件。源文件在打包过程中发生改变时会停止，避免保存混合版本。不要在同一时间并发运行两个建库进程。

## 恢复

```powershell
python tools/project-library/manage.py restore --out ../MCyukkuri-art-restored
```

目标必须是不存在的新目录。工具先校验所有包和成员路径，再恢复全部分类包的原有相对路径，最后逐文件重新核验。不会覆盖已有工程。生成的 `RESTORE-REPORT.json` 记录恢复文件数与验证结果。

恢复后的 `variants/*/preview.html` 或 `index.html` 可直接打开；源文件包也可按普通 ZIP 手工查看。完整恢复会同时带回共享工具与跨目录依赖。重新生成模型还需要对应的 Python/Pillow/NumPy、Node 和脚本中指定的浏览器环境；外部游戏参考不会自动安装，也不随可移植源文件包提供。

如需某一历史版本，用对应 `snapshots` 中记录的包与清单恢复到新目录，不覆盖当前工作版本。本地完整快照由 `archives/latest-preservation.json` 指向，按快照列出的 ZIP 恢复即可保留参考与渲染帧。旧备份中仍含弃用内容时，列入清理清单；不能继续把它当作可直接恢复的有效快照。

## 进入运行资源的规则

`art/` 保存制作资料，不自动进入 Mod Jar。确认某个动作的运行版本后，将适配过的几何、贴图和动画放入 `src/main/resources/assets/mcyukkuri/`，在控制器中使用稳定动作 ID。原制作包仍作为可编辑依据保留。

新增行为需要记录：使用的模型/动作版本、事件时点（入口扣粮、吞咽、排出物生成等）、循环或结束方式、表情优先级、服务器保存/重载与多人同步。已有网页验证只能证明原型行为，实体接入仍须实机验证。

## 当前清理状态

目录与备份删除被自动审批审查拦截，尚未发生。新目录索引已排除强烈收腰、后身纵深和噗咕眼形 B/C/D（含四版对比页），旧源目录与旧归档仍在磁盘上。完整残留清单见 [retirement-plan.json](../art/library/retirement-plan.json)，使用 `python tools/project-library/retirement_plan.py` 可重新核查。该命令只读扫描、写清单，不删除文件。
