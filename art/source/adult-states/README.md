# 成体状态表现选用

本包由 `scripts/export_adult_states.py` 从
`standards/yukkuri-library/drafts/rapem-alice/` 导出。

- `pose-cues.json`：整体姿态关键帧，与近景图和动图使用同一制作数据。
- `alice_agitated.png`：本轮重新制作的 `variant/alice_agitated.png` 选用快照；专用于爱丽丝失控阶段，不沿用旧稿。
- `breeding_period.png`、`state_recover.png`：按用户提供的八景 ver1.12 普通成体外貌重画的爱丽丝中性观察与恢复脸。其他种类仍使用其现有标准表情。旧 `brief_relief.png` 已移除。

当前已接入游戏代码及资源，等待用户视觉反馈和试玩；未登记为认可的标准造型。
已核对的1.12普通成体外形含蓝虹膜、大片高光和细弧眉。专属失控状态脸未获同版证据，当前状态形态仅为项目示意，不能宣称还原通过。
`heat` 是持续发情期中的8秒中性观察循环；实际持续期取服务器 `FamilyLife.sexDrive`，观察循环结束不会清除状态。失控分镜与三维姿态为项目编排。

重建：`python scripts/export_adult_states.py`；非编译一致性检查：加 `--check`。
