# 标准伸缩部件

螺壳魔理沙 `marisa_tsumuri` 也引用下述共用组件、骨架和行为，使用相同控制接口；两部位随身体缩入壳内。见 [螺壳标准模型](TSUMURI.md)。

标准魔理沙和灵梦共用 `components/common/anatomy.json`、`components/common/anatomy-rig.json` 与 `behaviors/anatomy.json`。几何和肤色沿用本次已确认的造型；麻姆麻姆位于油菊下方，佩尼佩尼位于脸部下方。前方根部使用独立皮肤挂点，随身体动作移动。

打开角色预览或 `compare.html`，选择部位，再点击“伸出”或“缩回”。默认全部缩回。伸出后保持状态，切换部位时先将当前部位完全缩回，再伸出所选部位，任何时刻最多显现一种。伸缩独立于动作播放和表情，暂停动作时也可操作。

两角色均使用同一套控制接口：

```js
standardAnatomy.extend('peni'); // 或 'mamu'
standardAnatomy.retract();
standardAnatomy.selectPart('mamu');
standardAnatomy.state();
```

复用运行时时，实例化 `AnatomyController(config)`，每帧调用 `update(dt)`，将 `overrides()` 合并到动作骨架的 `boneOverrides`。控制器保证切换互斥。伸出耗时 0.24 秒，缩回耗时 0.65 秒，配置集中在行为文件中。

`build.py` 将四个独立的非循环片段导出到两角色的动画文件：`anatomy_mamu_extend`、`anatomy_mamu_retract`、`anatomy_peni_extend`、`anatomy_peni_retract`。每个片段同时将另一部位保持在缩回位置；外部引擎需要保留完成姿态，并按“先缩回当前部位、再伸出新部位”顺序调度。它们与原有 9 个基础动作、6 个复合动作分开登记。

新增标准角色时，在 `components` 中引用共用几何，在 `rig_components` 中引用共用骨架，在 `anatomy` 中引用行为文件。角色骨架需要具有 `head` 和 `rump_round_end_slice_1_bend` 挂点，贴图沿用标准肤色 UV。
