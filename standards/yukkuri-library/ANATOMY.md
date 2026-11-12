# 标准伸缩部件

圆润标准角色使用 [body-standard/anatomy.json](body-standard/anatomy.json) 与其生成的合并组件和骨架。两部位均为肤色。佩尼佩尼是一截粗细均匀的小触角：光滑椭圆短柱加圆头（组件 `peni_stub`，由 `body-standard/build.py` 生成网格，五个 `peni_taper_` 方块只作外包框），整体上翘 25°；麻姆麻姆是贴平身体的肤色底片上一道深色竖线（共用组件 `components/common/anatomy.json`）。麻姆麻姆和佩尼佩尼位于同一前下方表面挂点，油菊在后方；胎生使用该前方出口。原作部件与位置依据见[位置与轮廓记录](../yukkuri-v1/references/contact-position.md)。

挂点高度从着地点往上量，各形态取同一绝对高度（圆润标准体 `surface_site` 的 1.281 单位），不随身体拉长按比例放大；豆形（`subspecies/deficient/recipes.json` 的 `organ_site`）同样两部位都在前方这一高度（项目设计，用户 2026-11-13）。家油是独立的小尺寸设计，仍用其自身挂点。素鸡立接触时，生殖层把麻姆麻姆放到身后表皮上、对方佩尼佩尼实际进入的高度；各形态的实测记录（伸出后的位置、突出量、后表皮深度）由 `drafts/sukkiri/organs.py --sites` 从运行模型生成，经 `drafts/rapem-alice/author_cues.py` 写入 `adult_states.json` 的 `organ_sites`，配对侧视图用 `organs.py --render` 出图。

螺壳魔理沙 `marisa_tsumuri` 也使用同一控制接口；两部位随身体缩入壳内。见[螺壳标准模型](TSUMURI.md)。前方根部使用独立皮肤挂点，随身体动作移动。

打开角色预览，选择部位，再点击“伸出”或“缩回”。默认全部缩回。伸出后保持状态，切换部位时先将当前部位完全缩回，再伸出所选部位，任何时刻最多显现一种。伸缩独立于动作播放和表情，暂停动作时也可操作。

所有适配形态均使用同一套控制接口：

```js
standardAnatomy.extend('peni'); // 或 'mamu'
standardAnatomy.retract();
standardAnatomy.selectPart('mamu');
standardAnatomy.state();
```

复用运行时时，实例化 `AnatomyController(config)`，每帧调用 `update(dt)`，将 `overrides()` 合并到动作骨架的 `boneOverrides`。控制器保证切换互斥。伸出耗时 0.24 秒，缩回耗时 0.65 秒，配置集中在行为文件中。

`build.py` 将四个独立的非循环片段导出到两角色的动画文件：`anatomy_mamu_extend`、`anatomy_mamu_retract`、`anatomy_peni_extend`、`anatomy_peni_retract`。每个片段同时将另一部位保持在缩回位置；外部引擎需要保留完成姿态，并按“先缩回当前部位、再伸出新部位”顺序调度。它们与原有 9 个基础动作、6 个复合动作分开登记。

新增圆润标准角色时，在 `body-standard/recipe.json` 登记来源并通过其构建器生成合并几何和分段权重；角色的 `components`、`rig`、`anatomy` 应指向生成文件。旧独立方块组件的接线方式不适用于当前连续网格。
