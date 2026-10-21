# 《油库里日记》表情与模型参考

[建模调研素材](art-references/game/README.md) · [来源与记录边界](README.md#来源与记录边界)

## 作品与材料范围

[Yukkuri Diary 发布页](https://apkpure.com/yukkuri-diary/jp.gr.java_conf.sakatasnow.YukkuriDiary)标注开发者 BlueSnow、版本 2023.v2（2023-06-07）和包名 jp.gr.java_conf.sakatasnow.YukkuriDiary。Miurabo《Yukkuri My Friends》包名不同，不作同一作品处理。

提取材料位于 art-references/game/diary/：source-evidence.json 记录 APK 与程序集资源哈希，asset-index.json 记录资产索引，rig-materials.json 和 animation-bindings.json 记录骨骼、材质及绑定。材料只用于结构核读；未反编译原生逻辑、恢复运行状态机或逐帧测量动画，也未作为 MC 运行资源。

## 网格、骨骼与贴图

以下资产均来自 sharedassets0.assets：

| 资产 | 已确认事实 |
| --- | --- |
| Body，Mesh 202 / 224 | 各 951 顶点；所读网格没有 BlendShape 通道。 |
| Body，SkinnedMeshRenderer 1272 / 1279 / 1288 | 有 17 个骨骼，包括左右眼、上下眼皮、眉毛、Jaw、JawUp、脸颊和舌头。 |
| Hair04Marisa，Mesh 257 / 258 | 各 380 顶点；独立蒙皮，绑定 Spine、Hip。 |
| Momi 与侧发组件 | 有 MomiageBone01R…06L 等侧发骨骼。 |
| yukkuri01，Texture2D 152 | 256×256 图集，含皮肤、眼睛、口内、头发和配饰；采用图集与网格 UV。 |
| yukkuri01，Material 59；Hoppe，Material 35 | 前者主贴图为 152、Shader 名为 Toon/Lit；后者为独立粉色脸颊材质、Shader 名为 Toon/Basic。 |

这些记录说明眼球、眼皮、眉毛、嘴、脸颊和头发是可分离部件；不把它们误写成整张二维表情帧或形态键系统。

## 动画绑定

按场景 Transform 路径的 CRC32 与动画资源路径哈希对应：

- LookDown / LookForWard / LookLeft / LookRight / LookUp 绑定 Root/Onaka/Spine/LeftEye 与 RightEye。
- Yukkuri_Talk 绑定 Jaw 与 JawUp。
- YukkuriIdle 绑定眼皮、眉毛、眼球、上下颚、身体和侧发。
- YukkuriEat03、nemui01 还绑定舌头与脸颊。

绑定存在不表示每一帧都有可见位移，也不能单独推出运行时状态转换。

## 可回查图像

- 默认绑定姿态局部重建：[reconstructed-front.png](art-references/game/diary/reconstructed-front.png)。
- 身体与眼口拓扑线框：[body-wireframe.png](art-references/game/diary/body-wireframe.png)。

两图只展示默认绑定姿态与简化材质，未包含游戏表情、骨骼变形、完整配饰或原 Shader，不是原作实机截图。
