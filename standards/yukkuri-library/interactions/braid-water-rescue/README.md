# 单辫水面救援

制作源为 `recipe.json`，`author.py` 从当前魔理沙模型导出单辫接触几何到 `src/main/resources/assets/mcyukkuri/yukkuri_presentation/braid_rescue.json`。对应身体动作在 `motions/variants/lore/recipe.json`，不更换原模型或增加辫子。

接触点取已有发梢网格的实际末端；`hand_right_tip` 骨骼转轴单独保存，不能当作辫子的完整长度。固定根部俯身与实际支撑抬升一同导出。服务器和客户端共用自然长度、根锚、接触点、抓取高度及阶段时长，抓取期间不伸长辫子。

服务器联合选择亲油的接触站位、放下站位和孩子这一侧的合法近缘空位。选位同时考虑真实帽面路径、身体间距与支撑；抓住后只抬到足面越过实际帽缘所需的净空，在自然辫长球面上低位转入，再短降放稳、松开。`PLACE` 区分沿帽面行走和低位转入，`SETTLE` 才是最后放稳；不再设置朝头顶的统一高举中间点。接触前孩子保持实际水面运动，抓托后由同一辫梢约束连续携带。服务端同步实际 `Duration` 和 `Travelling`，客户端按该时钟映射动作并反解辫骨，不自行移动孩子。

```powershell
python -B standards/yukkuri-library/interactions/braid-water-rescue/author.py
python -B standards/yukkuri-library/motions/variants/lore/author.py --export-distress
```

以上仅导出资源。当前离线预览位于 `.local/previews/hat-motion/index.html`，生成源码为 `motions/variants/lore/hat_motion_preview.py` 与对应 HTML 模板，产物不入库。
