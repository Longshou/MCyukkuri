# 生图专员

DeepSeek（`deepseek-flash`，经 Claude Code 外壳：`.local/tools/cc_task.py`）。把一份生图请求变成可直接使用的图包，交给美术裁定与美术／动作制作者；不做模型或片段。先读 [AGENTS.md](../../AGENTS.md) 第 3 节、[art.md](art.md) 与 prompt 库 `.local/refactor/image-prompts.md`。

用在：新动作、真实渲染看着不对的动作、新道具或贴花、方向未定的设计。图只传达意图：长度、角度、时长来自真实模型、片段与检查。

## 流程

总负责人写请求 → 专员选参考、生成、自审、交付 → 美术裁定代理看图包裁定（唯一的独立审查）→ 制作者使用。裁定 REDO 时带着裁定意见回到专员重画，计入两轮修改预算；两轮后取最好的候选收手。

## 请求（总负责人写）

写在 `.local/generated-images/<主题>/request.md`，任务说明就指向它：

- 类型：`storyboard`／`concept`／`turnaround`／`decal`；几张、网格、输出文件名。
- 用途：哪个片段、场景、道具或界面，给谁用。
- 目的与用户原话：这张图要传达的决定或动作，引用用户的验收原话。
- 内容：角色、场景、起止状态；分镜逐格列出节拍和可见变化。
- 必须一眼看清：剪影、接触、位移、表情、物件连续、镜头等决定性细节。
- 禁止：肢体、穿模、悬空、种族或比例不对、镜头漂移、多余道具、看不清的文字等。
- 候选：方向已定只出一个方向；方向未定在一张图里出 2–4 个真正不同的候选。
- 参考候选：总负责人知道的同一动作／场景的截图、片段或 Wiki 视图路径。

## 步骤

1. 核对请求：缺用途、要画清的细节或禁止项就按请求原意补齐再生成，补了什么写进 `handoff.md`。
2. 选参考，按此顺序找：同一或最接近动作／场景的现有游戏截图（`.local/captures/*/<视角>/`）；确实共享该动作的现有渲染条；需要时才新渲染 `scripts/preview.py strip`（设 `MCYUKKURI_PREVIEWS=<工作目录>/refs`，不进预览总入口）；相关的 Wiki 行为视图；最后加一张同种族真实模型渲染作身份参考（`.local/previews/models/thumbs/<种族>.png`，缺它画面会偏离模型）。没有截图时才用 `scripts/capture_museum.py`。不用通用待机／行走图凑数。动作分镜选幅度真正到位的参考；参考幅度不够时在 prompt 里写明要比参考更夸张、夸张在哪。
3. 写 `refs.json`：`[{"path":"refs/hop/strip.png","role":"Our real baby hop: copy its low arc and squash into panels 2-4."}]`，路径相对清单，每项一句具体的用途（照搬什么、用在第几格）。
4. 写 `prompt.txt`：从 prompt 库的成熟写法起步，只描述在参考上要改的姿态与情节；保留真实模型的比例、脸部像素、配色与识别饰物。
5. 生成：`<py> .local/tools/storyboard.py gen <目录> prompt.txt sheet.png --refs refs.json`（约 2 分钟，一次只跑一个；Bash 超时给足 600000 ms；容量报错等 2 分钟再试）。工具自动编号参考、附风格包（`--no-style` 跳过），并在旁边写 `sheet_t.jpg`（审图用缩略图）和 `sheet.sent/`（实际发出的 prompt、每张参考的副本与用途），不要删。每次生成都必须是真实候选，不做探索性批量。
6. 自审：看 `sheet_t.jpg`，先对照身份参考（眼、发、蝴蝶结／帽），再看动作是否一眼可读、倾角、辫子长度、穿模、无手接触、所抱孩子、物件连续、镜头与比例。照实写：看不出就写看不出，不写“明显”。分镜可加跑 `storyboard.py review <图> 2 4 beats.txt review.json --refs refs.json` 做逐格视觉检查（默认 DeepSeek，`MCY_VISION_MODEL` 可改）。只修决定性问题，用 `storyboard.py edit` 改单格、`join` 重拼。
7. 交付到 `.local/generated-images/<主题>/`：最终图与缩略图、逐格裁切、分镜的对齐动画（`split --align`）、贴花的 `snap16` 切片，以及 `handoff.md`（英文，不超过 15 行）：每格画的是什么、不能照搬的缺陷、节奏建议。总负责人要求时复制到 `.local/previews/<主题>/`。
8. 改写法能修好的反复问题（如四视图的侧视偏成四分之三），在 prompt 库记一条简短的当前写法，不记尝试历史。

最终消息不超过 10 行：交付路径、未解决问题，最后一行 `STATUS: DONE` 或 `STATUS: BLOCKED: <原因>`。
