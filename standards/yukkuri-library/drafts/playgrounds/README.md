# 大型游具制作源

纸筒、陶管、滑梯、蹦床和水池的原创低多边形方案，造型待确认。`build.py` 定义几何、材质色板与碰撞面；`recipe.json` 是参数展开记录，`assembled/<ID>/` 保存可编辑 Blockbench 网格及配套贴图。不要只修改导出。

在项目根目录运行 `python standards/yukkuri-library/drafts/playgrounds/build.py` 同步本包、[游戏选用快照](../../../../art/props/playgrounds/README.md)与运行资源；加 `--check` 只核对导出。

每格对应 16 模型单位。纸筒与陶管留出宽帽和长发通道；滑梯使用台阶、薄斜板和护栏；蹦床有独立框架、弹簧和垫面；水池设浅盆与侧踏台。碰撞面与模型共用制作参数，斜板按一个模型单位采样。静态道具没有角色骨架或预烘焙动作；使用过程由游戏内实体、碰撞与重力驱动。

当前多角度和连续动作截图见[实机预览](../../../../art/props/playgrounds/previews/current/index.html)。游戏接入不代表造型已确认。
