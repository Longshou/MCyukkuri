# 当前基础美术资产

这里保存已选定的灵梦与魔理沙基础模型，供下一阶段实体接入使用。不自动打入 Mod Jar。

| 种族 | 当前来源 | 文件 |
| --- | --- | --- |
| 魔理沙 | 本地 `variants/marisa-body`，用户选定的去底层版本 | `source/marisa/marisa.bbmodel`、`.geo.json`、`.png` |
| 灵梦 | 本地 `variants/reimu-hakkei`，与基础魔理沙同尺度的八景 U 参考版本 | `source/reimu/reimu.bbmodel`、`.geo.json`、`.png` |

![魔理沙基础模型](source/marisa/preview-sheet.png)

![灵梦基础模型](source/reimu/preview-sheet.png)

`.bbmodel` 是可编辑源文件，贴图随项目文件保留；`.geo.json` 为此前导出的骨骼几何。后续接入 GeckoLib 时需要检查骨骼、UV、动画与坐标，不能仅复制文件就宣称已完成实体接入。

造型参考本机《ゆっくり八景U》相关资源，几何与像素贴图为此前项目制作。原作品作者署名与授权尚未完整核实，不把这些文件标为已取得第三方开源授权。具体角色与设定来源见 [Wiki](../wiki/README.md)。

历史表情、动作试验仍在本地 `variants/`，首批上传不含这些大型中间文件，也不含八景 U 原始游戏资源。需要将某个动作正式纳入开发时，再整理成独立、可复现的源文件与运行资源。
