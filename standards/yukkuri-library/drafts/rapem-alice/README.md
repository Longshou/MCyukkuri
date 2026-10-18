# 素鸡立与雷普魔爱丽丝 · 状态表现候选

本包是**待确认的成体表现草案**，没有自动选为标准或游戏运行素材。登记在 `variant/variant.json`。

| 分支 | 表情顺序 | 身体表现 |
| --- | --- | --- |
| 素鸡立 · 短时缓解 | `neutral → confused → closed_smile → sleepy → neutral` | 正常、短暂侧看停步、放松、疲惫、恢复 |
| 雷普魔爱丽丝 · 中断示例 | `surprise → angry → confused → sleepy → neutral` | 警觉、定向接近、停步退让、疲惫恢复 |

两个分支分别播放，均在 10 秒结束后暂停。短时缓解不等同于整段发情期，也不自动转为异常状态。异常分支仅适用爱丽丝；普通爱丽丝并不必然进入这一分支。

## 证据与制作选择

- [Wiki 爱丽丝条目](../../../../wiki/hakkei-reference/lore/species/alice/README.md)区分普通爱丽丝与失控变体，不应把变体特征推广为每只爱丽丝的常态。
- [L36《饅饅來大講堂》](https://komicolle.org/c/40720)记述异常变体失去理智时的身体能力变化及群落风险；未规定白眼、怒眉配笑脸、固定动作次序或恢复时长。
- 表情选用、10 秒分段、停步退让和疲惫恢复属于本项目制作选择。预览只演示一只成体的状态提示，无目标接触或侵害过程，不显示生殖器。
- 旧稿中的八景 1.12 程序帧号与反编译笔记保留在登记的 `retained_inputs`，本轮未独立复核，不把它们作为当前表现或机制的证明。

## 与游戏的对应

当前预览直接复用标准五官：躁动 `confused`、放松 `closed_smile`、失控 `angry`、恢复 `sleepy`。游戏当前也使用这些标准脸；本包的转向、位移、分段时长和展示光环**没有自动接入游戏**。游戏状态由服务器规则决定，预览不代表实际状态持续时间或玩法结果。

旧 `rapem.png` 与 `sukkiri_tongue.png` 制作输入及原配方继续保留，当前页面不选用。原白眼怒眉笑脸没有可验证的固定形象依据，故改用标准紧张表情；恢复段替代无止境循环，便于辨认状态结束。

## 文件与重建

| 文件 | 职责 |
| --- | --- |
| `motion-clips.js` | 从运行模型的 `idle / turn / brace / waddle / startle` 组合有限片段；缺少来源动作时明确报错 |
| `variant/variant.json` | 待确认状态、证据边界、标准表情映射和保留输入 |
| `build_rapem.js` | 沿用旧入口名；只生成标准表情图集检查图，不改写旧表情输入 |
| `preview/` | 页面源模板；结束暂停，默认关闭转台与光环 |
| `build_preview.js` | 将当前页面与登记复制到本机预览目录 |
| `render_contact.js` | 软件光栅化两行五阶段联系图，不启动浏览器 |
| `verify.js` | 现有非编译资源检查入口：动作数据、标准表情及模型采样 |
| `serve.js` | 仅监听 `127.0.0.1:8799` 的只读静态服务 |

```sh
node standards/yukkuri-library/drafts/rapem-alice/build_rapem.js
node standards/yukkuri-library/drafts/rapem-alice/build_preview.js
node standards/yukkuri-library/drafts/rapem-alice/render_contact.js
node standards/yukkuri-library/drafts/rapem-alice/verify.js
```

当前产物：`.local/previews/rapem-alice/index.html`、`contact.png`、`expression-atlas.png`。联系图仅是离线模型采样，不代表 Minecraft 实机结果。总入口由仓库 `scripts/build_preview_hub.py` 重建。

手动查看时运行 `node standards/yukkuri-library/drafts/rapem-alice/serve.js`，访问 `http://127.0.0.1:8799/.local/previews/rapem-alice/index.html`。完整演出结束后再按播放可重播；浏览器交互与游戏内感受仍需用户查看。
