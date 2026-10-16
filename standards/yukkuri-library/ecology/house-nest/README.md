# 家油棉絮巢与破壳演出

制作参数在 [recipe.json](recipe.json)，由 [build.py](build.py) 生成可编辑网格、BBModel 和原创色板；选用快照位于 `art/source/house-ecology/`。本包造型为待确认，不改变完整卵和裂壳卵 R2.4 的认可状态。

`nest_lint` 是可搬运的棉絮灰尘团，`house_lint_bedding` 是低矮的絮团、压实底垫和散纤维。睡眠区在左侧，卵区在右侧，前侧留出进出和存粮空间。棉絮不画成纸片，不用食物碎屑充当巢材。

`house_eggshell` 从已认可完整卵的下部网格选面，保留原 UV，补充内壁和不规则开口。它是本项目原创孵化演出的空壳，不覆盖完整卵／裂壳卵。`house_egg` 物品显示直接使用已有完整卵。产卵数量、演出时序和模型尺度是游戏适配，不是作品确证生理数据。

依据为 [资源与生活生态](../../../../wiki/hakkei-reference/world/resources-ecology.md)和[家油、巢穴与卵](../../../../wiki/hakkei-reference/lore/house-nest-sources.md)。微小寄居、夜间觅食、棉絮灰尘巢及卵生有来源；完整自然孵化连续镜头未从本仓库已核漫画画面确证，摇卵、开壳和短时残壳属于原创表现。

```powershell
python standards/yukkuri-library/ecology/house-nest/build.py
python standards/yukkuri-library/ecology/house-nest/build.py --check
python standards/yukkuri-library/ecology/house-nest/render_preview.py
python standards/yukkuri-library/ecology/house-nest/render_demo.py --ffmpeg "<ffmpeg可执行文件路径>"
python scripts/build_preview_hub.py
```

离线预览直接读取当前游戏网格、表情与动作，使用仓库软件光栅器；不启动游戏或浏览器、不编译。预览输出到 `.local/previews/house-ecology/`，仅证明当前美术与场景构图，游戏 AI、路径、多人同步仍需运行验证。

`render_demo.py` 输出24秒、720×480、12帧/秒的连续 MP4，以及便于直接查看的短 GIF 和封面。四段从0、6、12、18秒开始，依次展示白日归巢、夜间搬运、守卵倾听和错峰破壳；移动路线、日夜色调与压缩后的孵化时长属于离线演示安排。`demo.html` 是播放器模板，生成到主题入口后支持跳段与慢放，统一预览入口也提供链接。重新生成时先出静态分镜，再出动画，最后重建总入口。
