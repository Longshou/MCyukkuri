# 项目脚本

| 脚本 | 输入与用途 | 执行边界 |
| --- | --- | --- |
| `validate_entity_assets.py` | 对照 `art/source/` 与运行资源，检查两种实体的几何、贴图、动画标识和骨骼引用 | 在指定验证环境使用；本机不执行 |

标准素材包的制作和局部导出脚本保留在 `standards/yukkuri-library/` 内，见其 [目录约定](../standards/yukkuri-library/AGENTS.md)。不要把有相对路径依赖的包内脚本移到这里。

新增脚本须说明输入、输出、是否写文件及适用环境。临时脚本放项目内 `.local/`；可复用后再整理为正式入口。所有脚本遵守根目录 [AGENTS.md](../AGENTS.md) 的本机执行限制。
