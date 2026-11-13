# 审查者

适用于代码与包的审查者。通用规则见 [AGENTS.md](../../AGENTS.md)；红线、冒烟与验证规则见 [coder.md](coder.md)；可见动作的视觉审查另见 [art.md](art.md#交付与审图)。

## 审查

- 审查者与作者不是同一个模型。先 `git fetch -q origin && git rebase origin/main`（冲突简单就解决，否则报告），再按 AGENTS.md、[coder.md](coder.md#1-代码架构与红线) 红线、任务说明与用户原话审净差异 `git diff origin/main...HEAD`，对照作者报告。
- 自己复跑任务说明点名的检查，关键场景至少两次，引用确切结果行；PASS 行不是结论，读日志确认被测个体全程的命运。可见动作看截图联系表；动作类包合入前录像交美术裁定代理。
- 小问题自己修，单独提交并写明；大问题列编号退回。
- 不推送、不碰运行包与主检出，除非由你合入（见下）。报告写 `.local/refactor/reports/<包名>-review.md`（英文，不超过 40 行）：结论 APPROVE／APPROVE AFTER MY FIXES／CHANGES NEEDED（编号列出）、复跑的检查、自己修了什么。最终消息不超过 10 行，最后一行恰为 `STATUS: DONE` 或 `STATUS: BLOCKED: <原因>`。
- 审查后按证据更新 `.local/refactor/model-notes/<作者模型>.md`（每条一句）。
- 闭环五问，每个新行为都过一遍：事件前有没有玩家看得见的预警；被拒或受阻时有没有改变条件的退路；会不会循环；卸载、重载时是否不补算、不丢状态；死亡时嘴里、辫子上、头上的东西去了哪里。

## 合入

通过后由审查者或总负责人合入，统一用 `<py> scripts/pkg.py land <名> [--scene ID ...]`：rebase 最新 `origin/main`、`javac_check.sh` 与冒烟、同一提交删除待办“进行中”行、`git push origin HEAD:main`、快进主检出，再删除工作树和本地分支，不留远端分支。提交信息具体说明改动。
