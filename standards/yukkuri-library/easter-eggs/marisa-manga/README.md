# 彩蛋：漫画版魔理沙

照漫画 M01 的幼体魔理沙做的 Blender 立体模型，含 8 个表情与 3 段演示动作。用户采用为彩蛋保留：不是标准造型，不进运行资源，游戏未接入。

## 造型

以体宽 W、体高 H、眼宽 E 计，比例量自 M01 画格：

- 身体：近正圆，宽高接近 1:1，头顶饱满。
- 眼：约 0.18 W，中心约 0.66 H，内侧间距约 1 E。整只眼为黑色圆形，上半部有一块白色高光窗，下沿有小亮点。
- 嘴：位于两眼之间，上沿在眼中心下方约 0.25 E，为“ω”上沿的猫嘴。
- 腮红：眼外下方的斜线。
- 头发：成簇发片，两眼之间垂一缕；左侧（魔理沙自己的左）为细三股辫，约在 0.5 H 处系蝴蝶结。
- 帽子：帽筒短，向后歪折；帽檐下有白荷叶边。

配色、尺寸与发片布置全部写在 [variants.json](variants.json)，改造型只改这里。

## 产物与接口

| 文件 | 作用 |
| --- | --- |
| [faces.py](faces.py) | 画脸部贴图 `textures/marisa_<表情>.png`（neutral、blink、yu、push、chew_open、chew_closed、happy、content） |
| [build.py](build.py) | 在 Blender 场景里建模，生成集合 `COL_Marisa_marisa` 与层级 `EMPTY_marisa_body / stretch / hat / braid` |
| [animate.py](animate.py) | 三段动作：hop（ゆっ♪ 两跳）、crawl（ずりずり）、eat（むーしゃむーしゃ…しあわせー）。表情由脸部物体的 `expr` 属性逐帧切换 |
| [stage.py](stage.py) | 预览台：天光、地面、相机，以及五视角加脸部特写静帧 |
| [make.py](make.py) | 无界面一键构建：模型、动作、`.blend`、静帧与动作帧 |
| [render_clips.py](render_clips.py)、[clip_sheets.py](clip_sheets.py) | 逐帧渲染动作；输出动图 WebP 与 8 拍分拍条 |

## 使用

```
<py> faces.py
D:/Blender/blender.exe -b --factory-startup --python make.py -- <输出目录> --stills --clips
<py> clip_sheets.py <输出目录>
```

输出放 `.local/`，不入库。贴图是生成物，改 `faces.py` 或 `variants.json` 的脸部参数后要重跑 `faces.py`。

## 来源

- 主参考：[M01 第 3 页](../../../../wiki/art-references/images/M01/003-33d8597a.png)。用户另给的同作者画格只用来量眼、嘴、帽的比例。
- 立体化方式、表情组合与演示动作为项目设计，不是原作设定。
