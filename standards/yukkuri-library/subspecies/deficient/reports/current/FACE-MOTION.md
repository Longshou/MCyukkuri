# 豆油移动表情遮挡修复

2026-09-13，用户追问移动时是否遮住表情。针对性检查复现：两种豆油在 `waddle` 约 0.25 秒、0.75 秒时，左右眼交替被身体覆盖，嘴巴也缺失一半。此前头发检查、有限数值采样和静态四向实机截图未覆盖该问题，不能作为动态表情已通过的依据。

原豆油身体使用分段转动，但旧 `face_*_skin_anchor` 挂点没有对应的动作曲线，导致脸保持原方向。修订源 [bean_face.py](../../bean_face.py) 根据每个五官的实际高度，插值身体相邻两段的位置、旋转和缩放，并同步挂点支点。五官子骨自身控制保留；正面增加 0.045 模型单位的绘制余量，补偿平面表情与分段表面的细小差异。该次修复未改变身体、头发、帽子、蝴蝶结和贴图；后续辫子与帽子几何修订见 [BRAID-HAT.md](BRAID-HAT.md)。

源 BBModel、骨架、选用快照、运行模型及来源登记一并同步。最新指纹见 [hair-fit-manifest.json](../../hair-fit-manifest.json)。当前导出和骨架数据一致；旧固定开发 Jar 的验收结果不自动用于本修订。

可重跑检查命令为 `python standards/yukkuri-library/subspecies/deficient/verify_face_motion.py`。对两种模型、16 张现用表情、走路／跳跃／逃跑三个动作，各采样 25 个时刻及正面／斜侧两个角度，共检查 14,400 个眼睛或嘴部样本。使用同一姿态与镜头，比较五官实际正面像素在完整模型和完整脸部组合中的可见性；保留正常泪花叠加，排除表情薄片的肤色侧边。结果为意外遮挡像素 0，见 [face-motion.json](face-motion.json)。

Chrome 实际展示页另检查两模型的 `waddle` 0、0.1、0.25、0.5、0.75、0.9、1.1 秒，眼睛和嘴巴意外遮挡像素均为 0，见 [face-motion-browser.json](face-motion-browser.json)。当前导出图见[灵梦行走表情](../../previews/current/reimu_bean-face-browser.png)、[魔理沙行走表情](../../previews/current/marisa_bean-face-browser.png)和[侧面行走](../../previews/current/braid-hat-motion.png)。技术检查不代替用户对造型的确认，造型状态仍为待确认。

此前表情修复版本完成过 185 帧 Minecraft 行走采集、42 相位抽查，没有观察到吞眼或半嘴。当前辫子和帽子已再次修改；本轮 14,400 个样本及浏览器检查已用新模型重跑通过，实机结果以 [face-motion.json](face-motion.json) 的当前指纹和实机字段为准，不沿用此前版本的验收结论。

本轮辫子与帽子修订的 Minecraft 复验也已完成：两豆油正面、侧面、斜后方行走及正面哭泣表情未观察到意外遮眼或半嘴。所用四模型和贴图与固定产物指纹一致，相关记录见[本轮实机报告](../../../../../../docs/validation/bean-braid-fangs/current/README.md)。实机拼图核看和 56 张精选原图的指纹核对覆盖本轮整体素材采集，不能视为全功能或多人测试。
