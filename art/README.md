# 美术资产入口

当前复用标准：[去重素材登记](../standards/yukkuri-library/index.html) · [基础／复合动作与全部表情对照](../standards/yukkuri-library/compare.html)。新素材统一登记在此库；下方旧基础角色为兼容快照。


新增：[标准魔理沙三种形态预览](../standards/yukkuri-v1/subspecies/index.html) · [螺壳、水生、被炉模型](../standards/yukkuri-v1/subspecies/README.md)。

[基础角色](#当前基础角色) · [动作/表情/场景资料库](library/README.md) · [道具](props/README.md) · [保存与恢复](../docs/ASSET_WORKFLOW.md)

## 当前基础角色

这里保存已选定的灵梦与魔理沙基础模型，供下一阶段实体接入使用。不自动打入 Mod Jar。

| 种族 | 当前来源 | 文件 |
| --- | --- | --- |
| 魔理沙 | 本地 `variants/marisa-body`，用户选定的去底层版本 | `source/marisa/marisa.bbmodel`、`.geo.json`、`.png` |
| 灵梦 | 本地 `variants/reimu-hakkei`，与基础魔理沙同尺度的八景 U 参考版本 | `source/reimu/reimu.bbmodel`、`.geo.json`、`.png` |

![魔理沙基础模型](source/marisa/preview-sheet.png)

![灵梦基础模型](source/reimu/preview-sheet.png)

`.bbmodel` 是可编辑源文件，贴图随项目文件保留；`.geo.json` 为此前导出的骨骼几何。后续接入 GeckoLib 时需要检查骨骼、UV、动画与坐标，不能仅复制文件就宣称已完成实体接入。

造型参考本机《ゆっくり八景U》相关资源，几何与像素贴图为此前项目制作。原作品作者署名与授权尚未完整核实，不把这些文件标为已取得第三方开源授权。具体角色与设定来源见 [Wiki](../wiki/README.md)。

动作、表情候选和互动场景已归类到 `library/`，以无损源文件包保存完整制作数据和版本清单。原 `variants/` 工作目录继续保留，避免改变跨目录生成依赖。原作提取素材和逐帧输出另外保存到本地完整快照。运行版本的骨骼适配与实体接入仍需单独完成。
