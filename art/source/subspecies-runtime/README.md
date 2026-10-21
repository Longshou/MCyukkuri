# 亚种与先天形态的游戏选用

本目录保存游戏实际选用的形态模型、来源快照和当前导出指纹，见 [manifest.json](manifest.json)。制作源按形态维护：[不足形态](../../../standards/yukkuri-library/subspecies/deficient/README.md)、[捕食种](../../../standards/yukkuri-library/subspecies/predator/README.md)、[稀有形态](../../../standards/yukkuri-library/subspecies/rare-forms/README.md)。源模型、选用快照与运行资源属于不同阶段，更新源后需显式重新选用并导出。

从仓库根目录运行 `python scripts/export_subspecies.py` 更新快照和运行模型；`--stage` 只生成源与快照。伤口表面通过 `node scripts/export_subspecies_wounds.cjs` 同步，离线预览通过 `python scripts/preview_subspecies.py` 生成。`--textures-only` 只刷新表情快照与运行贴图，`--check-textures` 只读核对图集覆盖、解码、尺寸及源/快照/运行指纹。

形态玩法规则见[亚种实现](../../../docs/SUBSPECIES_IMPLEMENTATION.md)。
