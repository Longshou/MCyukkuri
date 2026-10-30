# 周边素材游戏适配

本包保存当前选用的周边素材快照。完整卵与裂壳卵采用已确认标准，其余素材为游戏适配候选。

`selected/` 固定保存所选可编辑模型、网格和贴图；[选用清单](selection.json)记录原路径与 SHA-256。[配方](recipe.json)列出物品、设施、附属物和 MC 合成规则。重新导出只读取固定快照，避免后续标准库修改无意改变运行造型。模型为静态网格，无独立骨架或动画；伤口位置由当前角色的身体骨架计算。

运行输出位于 `src/main/resources/assets/mcyukkuri/yukkuri_props/`、`textures/props/`、`models/item/` 和 `models/block/`；[导出清单](provenance.json)逐项记录输入输出指纹。`scripts/export_peripheral_assets.py --check` 核对源与导出，生活及家油旧导出器跳过本配方接管的资源。

食盆内的油粮颗粒堆、割草堆和麦秆堆（自绘材质，`food_bowl_regular`／`_forage`／`_wheat` 只含堆体，叠画在任一款式的盆里）显示最多八份，库存仍使用原有九格容器，八份以上外观饱和。漏馅掉落实体按实际堆叠数量分组绘制，避免原版物品掉落渲染重复绘制整个堆叠。地面便便、脱落头饰和土堆保存原个体尺寸；普通物品栏采用便于辨认的展示尺寸。食盆、软垫窝、沙厕及便堆是圆润低多边形加手绘材质；它们与编织大窝、陶厕、带盖厕的非默认染料款式（`<方块 ID>_<款式>`）也作为附属网格在此导出（见[照料道具制作源](../../../standards/yukkuri-library/drafts/care-props/README.md)）。

油粮食物有预算、普通和高级三档，营养值依次为 12、20、30；未指定档位的普通油粮按 20 处理。三档油粮与橙汁的物品外观是 [16×16 图标](../supply-icons/README.md)，本包只把它们的网格作为附属物保留给离线预览；食盆里的油粮仍由 `food_bowl_regular` 显示。

野外储粮堆 `forage_pile` 保留九格真实库存，客户端按食物总份数显示四级堆形：1–8、9–32、33–96、97 份以上；空堆只画底垫。实体交互以朝向记录的边缘触点为准，同一时刻只保留一个嘴部使用者。

厨余食盆 `food_bowl_garbage` 已删除；进食碎屑只作场景显示，不使用鱼骨或果核造型。完整进度见[文档索引](../../../docs/README.md)。

当前快照的制作源在[照料道具统一造型](../../../standards/yukkuri-library/drafts/care-props/README.md)等对应素材包；更新制作源后重新导出并登记新的快照指纹。

橙汁预览网格瓶标、除霉剂瓶标与同伴绳的展示参数由 [shop_goods_v2.py](shop_goods_v2.py) 生成，GUI 旋转与缩放记录在 `recipe.json` 的 `gui_display`。
