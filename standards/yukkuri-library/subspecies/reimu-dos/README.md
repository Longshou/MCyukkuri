本轮修订：灵胖保留上窄下宽、头顶略尖的壮硕比例，脸颊和下腹改为柔和弧线，底角略收圆；多毛种改为直接连接头侧的外炸鬓角，并增加头顶、刘海、后脑层次。尺寸采用普通主体宽约 24 cm、DOS 主体高 1.80 m（帽子另算）。

# 多毛种、灵胖与 DOS 魔理沙

[打开三维预览与同尺度对照](index.html) · [参考核读](REFERENCES.md)

灵胖本次增高约 37%，保持宽厚体型并增加成熟沉稳的默认面容；DOS 五官移至上方、缩短刘海、收小压低帽子，使帽沿贴合头顶，并增加威严默认表情。

三份独立设计稿，基于当前素材库的灵梦、魔理沙。DOS 按用户要求采用**较高、较瘦的椭圆形**：身体纵向拉高、上下收拢、侧面减薄，帽子保留独立比例。各方向采用不同参数，不是整体等比放大。

| 模型 | 造型 | 可编辑文件 |
| --- | --- | --- |
| 多毛灵梦 `reimu_wasa` | 普通灵梦身体与蝴蝶结，浓密鬓角直连太阳穴，头顶／刘海／后脑新增毛束 | [bbmodel](assembled/reimu_wasa/reimu_wasa.bbmodel) |
| 灵胖 `reimu_deibu` | 壮硕且圆润的体型，上窄下宽、头顶略尖，成熟面容 | [bbmodel](assembled/reimu_deibu/reimu_deibu.bbmodel) |
| DOS 魔理沙 `marisa_dos` | 高椭圆身体，五官居上，短刘海与贴合头顶的低帽沿，沉稳威严面容 | [bbmodel](assembled/marisa_dos/marisa_dos.bbmodel) |

每个 `assembled/<模型>/` 内提供 `.bbmodel`、`.geo.json`、`.animation.json`、基础 PNG、13 个已保留表情贴图（灵胖、DOS 各增加 1 个亚种专用表情）与独立离线 HTML。可在 Blockbench 中打开并编辑。预览支持拖动旋转、缩放、静止／走动／转身／鼓脸和表情切换；多毛种另有鬓毛轻摆，使用独立 sideburn 左右节点与太阳穴支点；交互按钮为轻摆鬓角。动作沿用标准骨架，经当前体型适配；未将其他体操和繁殖动作声明为本次验收内容。

总览上方卡片各自适配画幅；下方“灵梦三型”和“普通魔理沙与 DOS”使用同一镜头比例与地面基线，可以准确比较导出模型尺寸。

## 编辑与重建

`recipes.json` 保存造型参数，`build.py` 保存毛团建模和椭圆适配逻辑。源模型只读取本素材库的 `assembled/reimu`、`assembled/marisa`，不从历史 variants 取模型。基础库变更时先运行基础库构建，再运行本目录构建：

```powershell
python standards/yukkuri-library/build.py
python standards/yukkuri-library/subspecies/reimu-dos/build.py
node standards/yukkuri-library/subspecies/reimu-dos/verify.cjs
```

本次仅构建了亚种目录，未重新构建或改写原有基础模型。浏览器验证使用 Playwright 与 Chrome；可通过 `PLAYWRIGHT_MODULE` 和 `CHROME_PATH` 指定本机路径。模型构建需要基础库、Python 与 Pillow。

`manifest.json` 记录基础模型哈希和几何数量；`verification.json` 记录实际浏览器验证结果；`checks/` 保存正面、侧面、背面以及鼓脸起始、中间、极限、恢复姿态的截图。

本次未接入 Minecraft 实体或生成物品。原作和网络图片仅用于参考，存放于仓库忽略的 `reference/reimu-dos-variants/`；基础表情沿用项目既有像素；两种专用表情基于既有像素与调色板局部创作，未修改基础库。

## 实际尺寸

`physical-scale.json` 和 `recipes.json` 保存标定。16 模型单位代表 1 米；普通参考主体宽 0.24 米，DOS 主体高 1.80 米，帽子另算。DOS 主体宽约 1.375 米。灵胖主体宽约 0.321 米、高约 0.226 米。普通种为扁圆形，篮球大小按主体宽度表达，不把各轴强制改成球形。

新模型的几何、骨骼支点、动画位移及导出全部按这个尺度生成。总览里的普通种同步校准；原有基础库文件未改写，后续场景复用原基础模型时应应用本目录的 `world_scale`。独立预览按各自体型适配镜头，实际尺寸对照以同尺度区域为准。
