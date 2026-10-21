# 世界观动作

本包为生态行为和关系演出提供动作片段。来源分组与设计边界见 [Wiki 总入口](../../../../../wiki/README.md)；`recipe.json` 是动作 ID、时长、循环、变换、表情编排、形态门控与出处的唯一配置源。Wiki 无明确依据的动作标为项目设计。

动作按主题分为日常、社交与情绪，宠物店与考试，生育与接生，以及水生魔理沙帽船行为。服务端行为选择阶段和播放时钟；客户端只采样对应片段，不以动画驱动业务状态。具体触发见玩法专题：[宠物店](../../../../../docs/PET_SHOP.md)、[生育与接生](../../../../../docs/CONSUMABLES_AND_BIRTH.md)、[水边生活](../../../../../docs/AQUATIC_MARISA_IMPLEMENTATION.md)。

无颈圆团子的动作以压扁、拉伸、小位移、转向和表情表达；整体俯仰与侧倾合计应控制在约 12°。表情片段由 `expression_spans` 编排，哭脸等服务端状态可按 `expression_yield` 优先显示。魔理沙辫子随身体动作的规则见 [单辫动作](../contact-safe/README.md#魔理沙辫子跟随)。

## 制作与导出

`author.py` 求值动作，`hat.py` 求解帽子、帽子实体位置和辫子接触。`--export-birth`、`--export-distress`、`--export-comfort`、`--export-carry` 与 `--export-badge` 分别更新对应片段；完整导出使用 `python scripts/export_motion_corrections.py`。运行资源与指纹由 `art/source/motion-corrections/manifest.json` 登记。

```powershell
python standards/yukkuri-library/motions/variants/lore/author.py --export-birth
python standards/yukkuri-library/motions/variants/lore/author.py --export-distress
python standards/yukkuri-library/motions/variants/lore/author.py --export-comfort
python standards/yukkuri-library/motions/variants/lore/author.py --export-carry
python standards/yukkuri-library/motions/variants/lore/author.py --export-badge
```

预览场景由同目录 `births.py` 与 `hat_motion_preview.py` 生成；它们只展示动作与接触编排，不运行游戏碰撞、AI 或成功率判定。
