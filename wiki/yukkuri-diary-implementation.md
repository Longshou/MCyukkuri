# 《油库里日记》表情与模型实现核读

日期：2026-09-09。目的：为当前 MC 魔理沙的眼睛、高光、刘海和神态寻找实现参考。**本页为核读结果与 MC 实施建议；未把日记资源接入 MC，也未运行日记 APK。**

本页中的机器路径、`reference/`、`variants/` 及其检查结论均为历史记录，对应文件未纳入当前项目。它们不构成当前可访问入口或新版验收证据；后续工作遵循项目内目录约定与本机执行限制。

## 结论

日记的神态控制具有可借鉴的结构：**身体网格内独立的眼球、上下眼皮、眉毛、上下颚、脸颊和舌头骨骼，配合单独的头发／侧发模型。** 它不是仅将整张静态表情贴在馒头表面。本机 APK 还保存了与这些骨骼对应的看向、说话、待机、进食等动画绑定。

对当前 MC 版本，值得借鉴的是部件分工与配合。静态图只改高光大小，无法同时解决眼皮开合、瞳孔方向、嘴形、刘海遮挡这些问题。日记自身的眼球贴图也有渐变与光泽，不应把它误读为“完全没有高光”的例子。

## 网络核对

- [Yukkuri Diary 发布镜像页](https://apkpure.com/yukkuri-diary/jp.gr.java_conf.sakatasnow.YukkuriDiary)：开发者标为 BlueSnow，版本 2023.v2，更新日期 2023-06-07，包名与本地 APK 相同。该页用于确认作品和版本，不用于推断代码实现。
- 发布页附有[更新说明中的视频链接](https://youtu.be/hK-NYV3o_aM)；本轮没有观看视频，不据此推断实现。
- 对应 [Google Play 地址](https://play.google.com/store/apps/details?id=jp.gr.java_conf.sakatasnow.YukkuriDiary)本轮返回 404；不能由此确定所有地区的上架状态。
- 通用搜索工具两次连接失败，改用直接 HTTP 访问搜索页面及发布页。Google 搜索要求脚本，DuckDuckGo 返回验证页，Bing 结果相关性不足。最终通过 APK 包名核对上述发布镜像，未找到可核实的作者源代码说明。
- 搜索出现的 Miurabo《Yukkuri My Friends》包名不同，**不是本次研究的日记**。

网页本地快照位于 `reference/diary/`，仅供核读，不进入模型资源包。

## APK 证据

原文件：`E:/yukkuri/资料/安装包/Yukkuri+Diary_2023.v2_APKPure.apk`。另有汉化测试版，但本轮使用原始 APK。复用既有 `E:/yukkuri/YukkuriDiary-zhCN/original/` 的已提取文件，并实际比较其 `sharedassets0.assets` 与原 APK 对应 split 条目合并结果：**字节完全相同**。APK 和程序集资源 SHA256 见 `reference/diary/source-evidence.json`。

包名：`jp.gr.java_conf.sakatasnow.YukkuriDiary`。资源记录 Unity `2021.3.26f1`，APK 包含 ARM64／ARMv7 的 `libunity.so`、`libil2cpp.so` 与 `global-metadata.dat`。本轮没有反汇编原生执行逻辑。

以下 ID 均属于 `sharedassets0.assets`：

| 资产 | 已确认事实 | 对当前项目的意义 |
| --- | --- | --- |
| Body，Mesh 202 / 224 | 各 951 顶点；所读网格没有 BlendShape 通道 | 此处主要可见的是骨骼蒙皮，不能误称为形态键系统 |
| Body，SkinnedMeshRenderer 1272 / 1279 / 1288 | 17 个骨骼，包括 `LeftEye`、`RightEye`、上下眼皮、左右眉毛、`Jaw`、`JawUp`、左右脸颊和舌头 | 眼球、眼皮、眉毛与嘴的姿态可分别改变 |
| Hair04Marisa，Mesh 257 / 258 | 各 380 顶点；独立蒙皮模型，绑定 Spine、Hip | 头发轮廓独立于面部，不依靠脸贴图模拟 |
| Momi 及侧发组件 | `MomiageBone01R`…`06L` 等侧发骨骼 | 侧发还有独立姿态控制，不应把主发壳的两根骨骼当作整套头发骨架 |
| yukkuri01，Texture2D 152 | 256×256 图集，包含皮肤、眼睛、口内、头发／配饰等区域 | 采用图集配合网格 UV；未发现这是整张二维表情帧表 |
| yukkuri01，Material 59 | 主贴图引用 152；Shader 292 的名称为 `Toon/Lit` | 有卡通材质；不将简化重建中的 Lambert 外观冒充原作画面 |
| Hoppe，Material 35 | Shader 302 为 `Toon/Basic`，独立粉色材质 | 脸颊表现也是可分离部分 |

完整索引：`reference/diary/asset-index.json`；骨骼／材质：`rig-materials.json`。

## 动画与骨骼的实际对应

通过场景 Transform 路径的 CRC32 对照动画资源的路径哈希，而非只凭动画名称猜测：

- `LookDown` / `LookForWard` / `LookLeft` / `LookRight` / `LookUp`：匹配 `Root/Onaka/Spine/LeftEye` 与 `RightEye`。
- `Yukkuri_Talk`：匹配 `Jaw` 与 `JawUp`。
- `YukkuriIdle`：含眼皮、眉毛、眼球、上下颚、身体和侧发绑定。
- `YukkuriEat03`、`nemui01`：含眼皮、眼球、眉毛、上下颚、舌头、脸颊及侧发绑定。

绑定映射见 `reference/diary/animation-bindings.json`。这是资源结构证据；未恢复运行状态机，也未逐帧验证各通道实际位移幅度，不把存在绑定等同于每一帧都有运动。

## 结构预览

历史结构预览 `reference/diary/mesh-study.html` 支持切换头发和线框，文件未纳入当前项目。它组合导出的 Body 224 与 Hair04Marisa 257，应用对象旋转后使用简化材质显示。**仅默认绑定姿态，未应用游戏表情、骨骼变形、完整配饰和原 Shader，不是原作完整角色／实机截图。**

- 默认姿态局部重建：`reference/diary/reconstructed-front.png`，未归档。
- 身体／眼口拓扑线框：`reference/diary/body-wireframe.png`，未归档。

头发观察：中央尖束和两侧有方向的斜分，比当前 MC 版本等宽直垂的刘海更容易露出眉眼；不需要增加密集发丝条纹也能读出发束。此项是本轮视觉判断。

## MC 可采用的方案（建议，尚未实现）

1. 保留用户认可的方块轮廓和已冻结表情版本。日记的 3D 网格不直接作为当前 MC 模型替换件。
2. 将面部绘制拆为眼底、瞳孔／单块高光、上／下眼皮、眉毛和嘴。MC 可以先用分层像素贴图／表情帧实现，不一定照搬17骨骼。
3. 先定三个受控参数：眼皮开合、双眼共同视线、嘴巴开合。开心状态把眼皮、视线、眉毛和上宽下圆的笑嘴一起调；不只放大眼睛或增加高光。
4. 高光留在瞳孔内部，避免眼白闭环与多个亮点叠成“玻璃眼”；让眼皮在需要时遮住部分瞳孔。
5. 刘海用少量有方向的收尖发束，露出眉眼。保持 A–D 的 B/C 这种同表情对比方式，以便区分头发与表情的贡献。
6. 最后再做眨眼、左右看和说话试验，并在正常游戏视距核对；眼睛特写好看不代表实际角色可爱。

原记录将提取资源保存在研究环境的 `reference/diary/`，本项目不包含这些文件。现有 MC 模型、贴图和资源包未引用这些原作像素或网格。

## 2026-09-10：原创预览试验已实现

历史记录记载，建议曾做成 12 组单项试验与三组合并候选，保存于 `variants/diary-combinations/`，该目录及其交付说明未纳入当前项目。预览使用原创分层像素绘制器，分别调整眼皮、共同视线、嘴形与刘海，并提供眨眼开关。记录称三个默认状态已导出为独立静态模型，浏览器交互和静态导出一致性检查通过；这些结果未在当前项目复核，不能作为本次或后续改动的验收证据。当时尚未接入 MC 动态实体，也未使用 APK 原作资产。
