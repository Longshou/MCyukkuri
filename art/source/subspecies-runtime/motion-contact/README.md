# 不足油后发接触适配

后续交接：两豆油已改用标准库 `subspecies/deficient/hair-fit-manifest.json` 登记的发帽修订，本包六套冻结输入及原始审查保留为历史证据。当前脚本的 `--publish` 只写四套 `sparse`／`face`，避免覆盖新豆油；最终游戏资源、配套伤口与器具通过情况见[本轮验收](../../../../docs/validation/personality-deibu/current/README.md)。

本包修正旧不足油运行模型后部浅色表面穿过低后发的问题。静态 `rump_round_end_slice_13` 最外侧为 z = 8.375 模型单位，原后发外侧仅为 z = 7.275 至 7.775；行走时后部与发梢采用不同骨骼，露边进一步扩大。

制作入口为 [deficient_motion_contact.py](../../../../scripts/deficient_motion_contact.py)。`input/` 冻结修改前的六套运行模型和适配骨架，作为可重复制作的输入；再次执行不从已修改快照累积偏移。`assembled/` 保存本包当前输出，`manifest.json` 登记输入、输出、脚本指纹与修改部件。

本轮只调整灵梦／魔理沙 `sparse` 和 `face` 的 `back_hair`、`back_hair_lower` 与 `back_lock_*`：发根保留原位置，低段后移最多 1.65 模型单位；低段以最高 45% 权重跟随邻近后部骨骼，避免完全跟随带来的过大剪切。身体、脸部、侧发、帽子、蝴蝶结、UV、骨架和 19 段动作曲线保持原值。两套 `bean` 未复现相同问题，运行模型保留输入原始字节作为回归对照；它们的旧适配骨架残留普通身体的顶点数和权重数组，本轮按实际豆形运行三角面同步这些数组。标准素材库不变，新圆润体模型不在本包范围内。

从项目根目录运行：

```powershell
python scripts/deficient_motion_contact.py
python scripts/deficient_motion_contact.py --publish --no-preview
```

第一条生成配套导出、当前预览及临时逐帧检查；第二条同步六套选用快照、适配骨架、游戏模型与选用记录。若先运行旧 `export_subspecies.py` 重新生成基础选用，应在其后执行本包发布命令，再生成伤口表面、束具配套和总预览。不要用已适配快照覆盖本包 `input/`。

当前证据位于 [previews/current](previews/current/)：六套行走各 13 个相位；每套另有正面、斜面、侧面、背面，侧弯四相位、伸展三相位及侧面行走。两套面部不足的后部浅条穿发在这些行走相位中已消除，发根保持挂接；稀疏毛发仍保留其少毛特征。六套共 19 段动作各采样 13 次，检查坐标有限值。临时完整采样记录位于项目 `.local/deficient-motion-contact/pose-checks.json`。

本轮为离线几何与动作检查，未启动游戏、Gradle 或预览服务；不替代当前实际客户端验收，也不改变不足油造型的用户确认状态。伤口表面和束具配套由协调任务在模型发布后同步。
