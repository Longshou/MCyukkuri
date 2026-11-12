# 美术与动作制作者

适用于模型、贴图、道具、动作片段与表情的制作者。通用规则见 [AGENTS.md](../../AGENTS.md)；造型与动作约束（无脖子圆球、俯仰与横滚合计约 12°）见[制作约定](../../standards/yukkuri-library/AGENTS.md)；动作接进玩法代码时同样遵守 [coder.md](coder.md) 的红线。

## 复用与资源

- 新做或替换美术前先在[素材目录](../../art/CATALOG.md)按类查现有素材，并查 `.local/previews/`（含 Codex 试画页 `codex-image-trial/`）与 `.local/refactor/reports/` 里已做过的探索；能复用就复用（在代码里变化尺寸、覆盖或配色），新增素材在 `art/catalog.json` 归类后重建目录。
- 打包资源只在 `src/main/resources/`，模组声明模板在 `src/main/templates/`；参考图、预览和标准素材库不复制进运行资源。
- `standards/yukkuri-library/` 是制作源，`art/source/` 是游戏选用快照，`art/runtime-sources.json` 登记对应关系。改模型先改制作源再导出，换用素材同步登记。
- 专用道具使用符合含义的美术，需替换的占位登记在[待办](../TODO.md#玩法待办)。

## 方向先行

- 先定结构、数值和素材清单，再制作；最后只给用户看一张当前预览，不给用户看量尺表。
- 分镜与概念先行：新动作先出 8 格分镜（以本项目模型为参考，只作意图：节拍、弧线、节奏；长度、角度、接触来自真实模型与检查），新道具、贴图、物件先出概念图或四视图定方向。分镜与概念图由生图专员交付到 `.local/generated-images/<主题>/`，经美术裁定后才用（规则见 [image-liaison.md](image-liaison.md)）；制作者只用交付物，按 `handoff.md` 与裁定意见避开不能照搬的地方，`<图>.sent/` 里是实际发出的参考；缺交付物就在报告里向总负责人要，不自己跑生图。
- 先粗后细：新动作或新行为先做能跑的粗版，尽早截图（或录像）对照用户原话审过方向，再细化；不要做完全部细节才第一次看画面。中间轮次用离线渲染条与网页预览迭代；游戏内截图（`capture_museum.py`／`capture_review.py`）只在最后一轮做，确认真实场景里的接触、遮挡与观察点。造型、表情、外观等审美类改动：概念图认可后先在一个代表形态的真实模型上出粗样，美术裁定代理认可再铺到全部形态与片段。
- 替换已在用的美术：先在同一场景、同一视角出新旧对比预览（静图或录像），美术裁定代理看过决定换才替换。

## 交付与审图

- 美术裁定：概念图、分镜、新旧对比与美术取舍由美术裁定代理（[art-arbiter.md](art-arbiter.md)）裁定，不再等用户；用户试玩认可仍是完成标准。动作类包合入前，被测场景的录像交美术裁定代理看，它点头才合入。
- 可见动作的交付：真实片段的离线渲染条与分镜节拍并排；`scripts/capture_museum.py` 截图联系表按实际大小看接触、穿模和观察点可读性，并由另一个代理对照用户原话审图后才能合入。每轮出图由另一个代理独立审图，作者自评不能代替。一条命令出审图：`<py> .local/tools/capture_review.py --worktree <工作树> <场景> --criteria <用户原话或文件> [--beats 节拍文件]`（截图后由视觉模型按全分辨率帧对照原话与固定检查，默认 DeepSeek，`MCY_VISION_MODEL` 可改；写 `.local/reports/capture-review/`；身体／动作问题退出 1，取景问题单列，审核错误退出 2）。
- 新预览用 `<py> scripts/preview.py`（用法见[脚本说明](../../scripts/README.md)）输出 `.local/previews/<主题>/` 并自动登记总入口；新动作以 `viewer <主题> --model <形态> --clip <片段> --decision <要用户决定什么>` 生成一个打开即播放的真实运行模型链接交给用户，并登记待查看。其他需用户决定的预览也带 `--decision`。同主题只留当前预览，不删制作输入、当前模型或原始参考图。改模型后出一张当前预览并贴在回复中。

## 命令与文件

- 动作包只用仓库命令（`export.py motion`、`preview.py`、`pose_check.py`、`capture_review.py`、`safe_edit.py`）；`export.py motion` 导出后执行身体硬错误门槛，`--check` 对现有导出执行同样门槛，检查范围与缺证据处理见[脚本说明](../../scripts/README.md)。命令做不到的步骤补成命令选项，不写一次性脚本。生成脚本和模板留在原制作目录。认可集合和文件指纹由制作源登记保存。
- 生成文件冲突先合并制作源（动作源 motions／variants／lore／recipe.json、author.py、motion_corrections.json；语言按键、台词按行 ID 合并）再重建受影响输出，不手工合并生成文件。
- 目录与文件用稳定英文 ID 命名，不用“最终版／新版2”；当前导出放 `assembled/`，待确认方案放 `drafts/<主题>/`，被替代方案直接删除、不设 `archive/`；同包脚本留包内，跨目录脚本放 `scripts/` 并登记其 README；`standards/yukkuri-v1/` 仍被读取。
- 报告、提交与范围同 [coder.md](coder.md#5-工作守则) 的工作守则。
