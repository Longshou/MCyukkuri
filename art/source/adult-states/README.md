# 成体状态表现选用

本包由 `scripts/export_adult_states.py` 从
`standards/yukkuri-library/drafts/rapem-alice/` 导出。

- `pose-cues.json`：整体姿态关键帧，与近景图和动图使用同一制作数据。
- `breeding_period.png`、`state_recover.png`：按用户提供的八景 ver1.12 普通成体外貌重画的爱丽丝中性观察与恢复脸。其他种类仍使用其现有标准表情。旧 `brief_relief.png` 已移除。
- `alice_open_drool.png` / `alice_flushed_tongue.png`：标准确认表情 N048（睁眼流口水）与 N035（潮红闭眼伸舌），分别为攻击魔搜寻追击与接触动作的攻方演出脸。
- `<species>_squeezed_cry_tears.png`：标准确认表情 N030（挤眼喊叫含泪），被压制受方的演出脸（reimu/marisa/alice/chen/patchouli）；无标准图集的形态回退 `cry`。被否决的 1.12 原帧合成脸 `alice_agitated` / `rapem_victim` 已删除。

当前已接入游戏代码及资源，等待用户视觉反馈和试玩；未登记为认可的标准造型。
已核对的1.12普通成体外形含蓝虹膜、大片高光和细弧眉。专属失控状态脸未获同版证据，当前状态形态仅为项目示意，不能宣称还原通过。
`heat` 是持续发情期中的8秒中性观察循环；实际持续期取服务器 `FamilyLife.sexDrive`，观察循环结束不会清除状态。失控分镜与三维姿态为项目编排。

重建：`python scripts/export_adult_states.py`；非编译一致性检查：加 `--check`。
