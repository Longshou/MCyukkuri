# 亚种与先天形态的游戏选用

本目录保存八份实际接入模型及其源快照。螺壳魔理沙选用已有确认造型；多毛灵梦、六份先天形态与帽船适配仍待用户确认。技术验收不改变造型确认状态。

- [当前交互预览](preview.html)：使用本轮运行三角面、骨架、贴图，可切换模型和动作。
- [多角度预览](previews/current/)：每种形态的正、斜、侧、背、行走和侧弯视图。
- [新增表情预览](previews/current/ecology-expressions.png)：八套形态各五种生态表情，由当前运行三角面与贴图渲染。
- [选用清单](manifest.json)：源模型、源骨架、运行资源、动作来源和 SHA-256。
- [玩法与验收](../../../docs/SUBSPECIES_IMPLEMENTATION.md)：数据轴、实际帽船、取得方式及检查边界。
- [不足形态制作源](../../../standards/yukkuri-library/subspecies/deficient/README.md)：配方与作品依据。

从项目根目录依次运行 `python scripts/export_subspecies.py`、`node scripts/export_subspecies_wounds.cjs`、`python scripts/preview_subspecies.py`。第一个脚本的 `--stage` 只制作源与快照；默认还写八份运行模型、表情贴图、口部／饰物挂点及帽船／螺壳道具。第二个生成与这些身体相符的受伤表面；第三个更新离线预览，不代替实机验收。

源与导出不可单独修改。螺壳旧登记未存历史确认指纹，本清单只记录此次选用指纹，不把新算的哈希冒充旧确认凭据。其余造型保持待确认，原有基线文件保留。

表情导出覆盖原有11种及生态新增5种，共128张。螺壳使用自身256×256图集，其余七套使用128×128图集；新增表情核对标准确认指纹，并保持原有UV。`python scripts/export_subspecies.py --textures-only` 只更新表情快照、运行贴图和选择记录，`--check-textures` 只读检查完整覆盖、解码、尺寸及源／快照／运行指纹；缺图直接报错。新增表情的游戏复验由联合验收任务负责，原20帧不能证明此次新增表情已在游戏中通过。

联合任务随后在 `runtime-20260912-230707` 完成128张图集真实客户端解码及加载，记录见[客户端结果](../../../docs/validation/subspecies/client-subspecies-atlases.json)。首次分娩录像的遮挡缺口已由同一冻结包的[豆形母体补拍](../../../docs/validation/port-integration/current/bean-birth-supplement/README.md)关闭，联合验收与出生负责人分别核看通过；不代表全部亚种分娩画面均已验收，技术通过不改变造型确认状态。
