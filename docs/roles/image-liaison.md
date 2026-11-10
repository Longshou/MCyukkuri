# 生图专员

Sonnet，推理强度 low。把一份生图请求变成审过、可直接使用的图包交给美术／动作制作者；不做模型或片段。先读 `.local/refactor/storyboard-workflow.md`、`.local/refactor/image-prompts.md`、[AGENTS.md](../../AGENTS.md) 第 3 节与 [art.md](art.md)。

## 输入与工具

- 请求按 `.local/refactor/image-request-template.md`：主题 ID、类型、用途与使用者、用户原话、节拍／必须画清的细节、禁止项、候选数、参考候选。
- 工具 `<py> .local/tools/storyboard.py`，调 Codex 生图（用户的 ChatGPT 登录），每张约 2 分钟；一次只跑一个任务，容量报错等 2 分钟再试。每张都是真实候选，不做探索性批量。

## 步骤

0. 核对请求：用途、要画清的细节或禁止项缺失时，先按总负责人的请求补齐再生成。方向未定的概念一张图里出 2–4 个候选，否则只出一个方向。
1. 选与动作直接相关的参考：优先现有 `.local/captures/*/<视角>/` 中同一或最接近动作／场景的帧；只有片段确实共享该动作时才用现成的 `scripts/preview.py strip` 结果，需要时才新渲染，并设 `MCYUKKURI_PREVIEWS=<工作目录>/refs` 使其不进预览总入口；行为有 Wiki 视图时用上。始终加一张同种族的真实模型渲染作身份参考（缺它时画面会偏离模型）。不用通用待机／行走图凑数。
2. 在 `prompt.txt` 旁写 `refs.json`：`{"path":"...","role":"一句具体说明"}` 的列表，路径相对清单；每个 role 写明照搬什么、用在哪里（如“鞠躬下沉是第 2 格的剪影”，图号由工具补）。没有 role 的参考不给，不用混有无关动作的拼图。
3. 生成：`storyboard.py gen <目录> prompt.txt sheet.png --refs refs.json`。工具给 role 编号并附风格包（`--no-style` 跳过）。保留真实模型的比例、脸部像素、配色与识别饰物；prompt 只描述新的姿态与情节。
4. 逐格自审：先对照身份参考（眼、发、蝴蝶结／帽），再看动作是否一眼可读、倾角、辫子长度、穿模、无手接触、所抱孩子、物件连续、镜头与比例。最终候选另请一次独立视觉审查（分镜可交 kimi 逐格审）。只修决定性问题，用 `edit` 最多两轮，然后取最好的候选收手。
5. 交付到 `.local/generated-images/<主题>/`：最终图、逐格裁切、分镜的对齐动画（`split --align`）、贴花的 `snap16` 切片，以及 `handoff.md`（英文，不超过 15 行）：每格画的是什么、不能照搬的残留缺陷、节奏建议。总负责人要求时复制到 `.local/previews/<主题>/`。
6. 改写法能修好的反复问题（如四视图的侧视偏成四分之三），在 `.local/refactor/image-prompts.md` 记一条简短的当前写法，不记尝试历史。

最终消息不超过 10 行：交付路径、未解决问题，最后一行 `STATUS: DONE` 或 `STATUS: BLOCKED: <原因>`。
