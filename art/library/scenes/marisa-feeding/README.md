# 魔理沙 · 进食、食盆与油粮

状态：已制作，待实体接入。原目录：`variants/marisa-feeding`。

[当前源文件包](564ff16dd045e4e11f66.zip) · [逐文件校验清单](564ff16dd045e4e11f66.manifest.json)

ZIP 内保存原项目相对路径。与其它源文件包一同恢复，保留跨目录生成依赖。
已记录的主要依赖：marisa-body；验证工具可能依赖共享运行库。

| 动作名 | 秒 | 循环 |
| --- | ---: | --- |
| `animation.marisa.idle` | 1 | True |
| `animation.marisa.walk` | 0.8 | True |
| `animation.marisa.bite` | 2.7 | False |
| `animation.marisa.satisfied` | 1.5 | False |

## 可编辑模型内的动画

| 模型 | 动作 | 秒 |
| --- | --- | ---: |
| `variants/marisa-feeding/marisa.bbmodel` | `animation.marisa.idle` | 1 |
| `variants/marisa-feeding/marisa.bbmodel` | `animation.marisa.walk` | 0.8 |
| `variants/marisa-feeding/marisa.bbmodel` | `animation.marisa.bite` | 2.7 |
| `variants/marisa-feeding/marisa.bbmodel` | `animation.marisa.satisfied` | 1.5 |
