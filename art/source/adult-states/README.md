# 成体状态表现选用

本包由 `scripts/export_adult_states.py` 从 `standards/yukkuri-library/drafts/rapem-alice/` 导出。

- `pose-cues.json`：整体姿态关键帧，与近景图和动图使用同一制作数据；`pair_peni`／`pair_mamu` 是普通自愿配对的共享接触循环。
- `breeding_period.png`、`state_recover.png`：按用户提供的八景 ver1.12 普通成体外貌重画的爱丽丝中性观察与恢复脸。其他种类仍使用其现有标准表情。
- `alice_open_drool.png` / `alice_flushed_tongue.png`：标准确认表情 N048（睁眼流口水）与 N035（潮红闭眼伸舌），分别为攻击魔搜寻追击与接触动作的攻方演出脸。
- `<species>_squeezed_cry_tears.png`：标准确认表情 N030（挤眼喊叫含泪），被压制受方的演出脸（reimu/marisa/alice/chen/patchouli）；无标准图集的形态回退 `cry`。
- `<species>_flushed_tongue.png`：标准确认表情 N035，普通配对的父方接触脸；覆盖 reimu/marisa/chen/patchouli/marisa_tsumuri，爱丽丝同图同时用于攻击魔接触。

八景 ver1.12 普通成体外形含蓝虹膜、大片高光和细弧眉。专属失控状态脸没有同版来源证据，当前状态形态仅为项目适配示意。
`heat` 是持续发情期中的8秒中性观察循环；实际持续期取服务器 `FamilyLife.sexDrive`，观察循环结束不会清除状态。失控分镜与三维姿态为项目编排。

重建：`python scripts/export_adult_states.py`。
