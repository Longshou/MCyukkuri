# 成体状态表现选用

本包由 `scripts/export_adult_states.py` 从
`standards/yukkuri-library/drafts/rapem-alice/` 导出。

- `pose-cues.json`：整体姿态关键帧，与近景图和动图使用同一制作数据。
- `alice_agitated.png`：本轮重新制作的 `variant/alice_agitated.png` 选用快照；专用于爱丽丝失控阶段，不沿用旧稿。
- `brief_relief.png`、`state_recover.png`：同批重画的爱丽丝缓解与恢复脸。其他种类仍使用其现有标准表情。

当前已接入游戏代码及资源，等待用户视觉反馈和试玩；未登记为认可的标准造型。
八景参考用于形象辨识，三维姿态与时间编排是项目美术选择，不声称由 Wiki 或 L36 明定。
游戏按服务端状态时间采样；预览压缩为 10 秒并展示完整恢复，因此不代表寻路、持续时长或接触事件。

重建：`python scripts/export_adult_states.py`；非编译一致性检查：加 `--check`。
