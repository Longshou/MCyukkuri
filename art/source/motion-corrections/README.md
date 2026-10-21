# 当前动作适配

动作配方和生成器见 [contact-safe](../../../standards/yukkuri-library/motions/variants/contact-safe/README.md)；世界观动作与情景触发见 [lore](../../../standards/yukkuri-library/motions/variants/lore/README.md)。当前游戏适配由 `scripts/export_motion_corrections.py` 生成，资源输出到 `src/main/resources/assets/mcyukkuri/yukkuri_presentation/motion_corrections.json` 和对应实体的 `cheek_puff.png`；输入、输出指纹见 [manifest.json](manifest.json)。

鼓腮 `puff` 使用整身根骨骼挤压拉伸，标准形态一轮 2.8 秒：

| 阶段 | 时间比例 | 身体与表情 |
| --- | --- | --- |
| 吸气 | 0–0.12 | 缩至宽 1.03、高 0.94、深 1.03；前倾 3° |
| 鼓起 | 0.12–0.30 | 带 35% 过冲至宽／深 1.14、高 1.12；前倾回正，切换 `puff` |
| 保持 | 0.30–0.80 | 约 ±2.5% 呼吸与 1.2% 轻颤；侧倾不超过 1.2° |
| 泄气 | 0.80–0.90 | 压至宽／深 1.05、高 0.93；切换 `cheek_puff` |
| 回落 | 0.90–1.0 | 压扁回到基准姿态 |

豆形只横向鼓起（约 1.10、1.03、1.10），歪脸形态为等比 1.07，避免占地过大。哭脸的服务端表情优先于动作编排；吸气阶段不覆盖调用方已设置的表情。没有独立鼓腮贴图的形态使用生成的 `cheek_puff`，从其愤怒眼眉、加深腮红与标准鼓腮嘴组合。

其他适配动作使用同一根骨骼变换与落地补偿，包括侧弯、横向与纵向体操、跳动、伸展和完整体操；魔理沙的粗辫在对应形态中由 `hand_right` 跟随。素材检查室与游戏读取相同资源。运行端先核对骨架名称与顺序；不匹配时保留基础模型。
