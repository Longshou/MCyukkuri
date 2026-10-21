# 排便与后侧照护动作

制作源为 `recipe.json`，选用快照为 `art/source/toilet-contact/recipe.json`。依据 EH08-008 的亲油舔舐促进幼体首便；身体收缩、节拍与表情切换属于本项目演出。

独立输出 `yukkuri_presentation/toilet_contact.json`，格式 1，包含 19 种当前骨架的七段片段与 `tongue` 参数；不重写基础模型或共享 `motion_corrections.json`。客户端在其他演出修订后通过现有 `applyRevision` 接入 `models[id]`。

- `ground_toilet`、`toilet`、`boat_toilet`、`toilet_accident`：40 tick 单次，两次明显挤压收缩后舒缓；失禁使用惊慌和含泪结尾。位置和真实排泄物由服务器决定。
- `first_toilet_wait`：安静低伏等待，不播放受刺激反应。
- `first_toilet_stimulated`：10 tick 循环，惊讶、收缩、含泪；仅真实后侧接触时进入。
- `first_toilet_assist`：10 tick 循环，稳定低伏（俯 −9..−10°、压低约 10%）和小幅点头、张闭口；客户端接触求解的合计俯仰上限为 12°（原 28°），靠前移与压低补足；40 tick 接触完成后服务端切换 `care_finish`，即停止舌头。

每 tick 在已适配的实际几何上求最低点，修正根部垂直偏移以保持支撑，不增加前后或左右位移。复用已有五官几何与贴图；缺少细分类表情的形态按配方回退，哭泣状态保留优先级。

舌头按用户修正采用与嘴部同一 XY 平面的平面表情，随面部根骨骼运动。轮廓为简洁的粉色片状图形，宽 `.045`、最大向下长度 `.055` block（乘角色体型）；不做圆截面、厚度、弯曲体积或朝幼体伸出的外部网格。所有顶点在同一面部平面，统一 `.003 block` 偏移仅避免与嘴部重叠闪烁。纹理为纯色 `textures/entity/care_tongue.png`。10 tick 一轮，2 至 7 tick 按 `.15+.85*sin(pi*(phase-2)/5)^2` 在脸平面内改变长度，窗口外缩至 15%。仅真实后侧接触时显示；服务端接触与排便计数仍独立于表情。`max_reach` 仅保留为嘴部接近距离的离线核对阈值，不再决定舌头几何长度。

离线预览包含身体/表情片段、成年亲油对 0.2 尺寸幼体的照护场景，以及面部舌头近景；不模拟碰撞或 AI。

```powershell
python standards/yukkuri-library/interactions/toilet-contact/build.py
python standards/yukkuri-library/interactions/toilet-contact/build.py --check
python standards/yukkuri-library/interactions/toilet-contact/preview.py
```

预览输出到 `.local/previews/toilet-contact/`，并重建本地当前主题入口 `.local/previews/index.html`。`render.cjs` 使用仓库离线骨骼求值与软件栅格器，无浏览器、游戏或编译。预览不代表游戏中的接触、排泄物或帽沿碰撞验收。

## 照护形态边界与静态可达性

豆形不足油先天不育，且服务端 `parentAvailable`/`rearContact` 明确排除其作为首便照护者。它们仍保留排便和幼体受刺激动作，可由普通亲油照护；不为不可用亲代增加深蹲或加长舌头。

`node standards/yukkuri-library/interactions/toilet-contact/check_reach.cjs` 对 19 种形态的成年/.2 尺寸幼体及普通灵梦、魔理沙亲代对各自豆形幼体采样完整照护周期。豆形成年亲代保留实测距离并标为 `ineligible_caregiver`，不作为适用照护组合的失败项；其他可用亲代只在全周期嘴点到后侧目标的间距均不超过 `.22 block` 时通过。输出在 `.local/reports/toilet-contact-reach.json`，只说明离线几何可达性，不证明游戏中碰撞、寻路或 AI 已通过。
