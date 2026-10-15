# 标准伸缩部件

2026-09-13：当前八种圆润身体使用 [body-standard/anatomy.json](body-standard/anatomy.json) 与配套合并组件、骨架。麻姆麻姆和佩尼佩尼均位于同一前下方表面挂点，油菊在后方；胎生使用该前方出口。旧 `components/common/` 和 `behaviors/anatomy.json` 保留供历史及排除种类使用。下文接口仍有效，旧位置与接线说明仅适用于推广前版本。

螺壳魔理沙 `marisa_tsumuri` 也引用下述共用组件、骨架和行为，使用相同控制接口；两部位随身体缩入壳内。见 [螺壳标准模型](TSUMURI.md)。

标准魔理沙和灵梦共用 `components/common/anatomy.json`、`components/common/anatomy-rig.json` 与 `behaviors/anatomy.json`。几何和肤色沿用本次已确认的造型；推广前麻姆麻姆位于油菊下方，佩尼佩尼位于脸部下方；当前圆润标准统一为前下方挂点。前方根部使用独立皮肤挂点，随身体动作移动。

打开角色预览，选择部位，再点击“伸出”或“缩回”。默认全部缩回。伸出后保持状态，切换部位时先将当前部位完全缩回，再伸出所选部位，任何时刻最多显现一种。伸缩独立于动作播放和表情，暂停动作时也可操作。

两角色均使用同一套控制接口：

```js
standardAnatomy.extend('peni'); // 或 'mamu'
standardAnatomy.retract();
standardAnatomy.selectPart('mamu');
standardAnatomy.state();
```

复用运行时时，实例化 `AnatomyController(config)`，每帧调用 `update(dt)`，将 `overrides()` 合并到动作骨架的 `boneOverrides`。控制器保证切换互斥。伸出耗时 0.24 秒，缩回耗时 0.65 秒，配置集中在行为文件中。

`build.py` 将四个独立的非循环片段导出到两角色的动画文件：`anatomy_mamu_extend`、`anatomy_mamu_retract`、`anatomy_peni_extend`、`anatomy_peni_retract`。每个片段同时将另一部位保持在缩回位置；外部引擎需要保留完成姿态，并按“先缩回当前部位、再伸出新部位”顺序调度。它们与原有 9 个基础动作、6 个复合动作分开登记。

新增圆润标准角色时，在 `body-standard/recipe.json` 登记来源并通过其构建器生成合并几何和分段权重；角色的 `components`、`rig`、`anatomy` 应指向生成文件。旧独立方块组件的接线方式不适用于当前连续网格。
