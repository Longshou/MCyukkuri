# 试玩场动作清单

当前 `YukkuriAction` 注册 114 个动作。此表从注册表、19 套基础模型和动作修订联合生成；“资源适配”指确实存在动画片段，不等于每个年龄、亚种都适配。水生魔理沙与普通魔理沙共用模型，场景按生态身份分开。

试玩场的“动作片段”场地用 9 位固定演员播放 47 个纯动作片段（含待机），分五类：身体与移动、日常生活、交流与情绪、宠物店与表情、运输与篮子，另有全体合唱和全体体操。家庭、照护、帽子与水边、落水与救援类动作由真实行为触发，在对应场景里自然出现，不放假按钮。

缩壳虽然有专属资源，但当前命令将所有 `_retract` 当作成对收回动作，导致 `shell_retract` 回到待机，因此不放置缩壳按钮；自然受惊时的缩壳由另一条行为路径触发。身体结构四段、失去幼体后的悲伤（`bereaved`）和家油内部片段不放按钮，但仍在下表列全。

## 身体与移动

| 动作 | ID | 资源适配 | 试玩位置 |
| --- | --- | --- | --- |
| 待机 | `idle` | 全部 19 套模型 | 动作片段场地 |
| 伸缩身体 | `stretch_shape` | 全部 19 套模型 | 动作片段场地 |
| 左右侧摆 | `side_bend` | 全部 19 套模型 | 动作片段场地 |
| 行走 | `waddle` | 全部 19 套模型 | 动作片段场地 |
| 撑起身体 | `brace` | 全部 19 套模型 | 动作片段场地 |
| 转身 | `turn` | 全部 19 套模型 | 动作片段场地 |
| 跳跃 | `hop` | 全部 19 套模型 | 动作片段场地 |
| 伸展 | `stretch` | 全部 19 套模型 | 动作片段场地 |
| 纵向体操 | `exercise_vertical` | 全部 19 套模型 | 动作片段场地 |
| 横向体操 | `exercise_sides` | 全部 19 套模型 | 动作片段场地 |
| 挤压 | `press` | 全部 19 套模型 | 动作片段场地 |
| 广播体操 | `taiso` | 全部 19 套模型 | 动作片段场地 |

## 日常生活

| 动作 | ID | 资源适配 | 试玩位置 |
| --- | --- | --- | --- |
| 进食 | `eat` | 全部 19 套模型 | 动作片段场地 |
| 饭后幸福 | `shiawase` | 全部 19 套模型 | 动作片段场地 |
| 饮水 | `drink` | 全部 19 套模型 | 动作片段场地 |
| 睡觉 | `sleep` | 全部 19 套模型 | 仅目录 |
| 醒来 | `wake` | 全部 19 套模型 | 动作片段场地 |
| 甩水 | `shake` | 全部 19 套模型 | 动作片段场地 |

## 交流与情绪

| 动作 | ID | 资源适配 | 试玩位置 |
| --- | --- | --- | --- |
| 蹭蹭 | `nuzzle` | 全部 19 套模型 | 动作片段场地 |
| 担心舔舐 | `comfort_lick` | 全部 19 套模型 | 动作片段场地 |
| 唱歌 | `sing` | 全部 19 套模型 | 动作片段场地 |
| 摇摆 | `sway` | 全部 19 套模型 | 动作片段场地 |
| 打招呼 | `greet` | 全部 19 套模型 | 动作片段场地 |
| 张望 | `look_around` | 全部 19 套模型 | 动作片段场地 |
| 鼓腮 | `puff` | 全部 19 套模型 | 动作片段场地 |
| 推挤 | `push` | 全部 19 套模型 | 动作片段场地 |
| 受惊 | `startle` | 全部 19 套模型 | 动作片段场地 |
| 发抖 | `tremble` | 全部 19 套模型 | 动作片段场地 |
| 低落 | `droop` | 全部 19 套模型 | 动作片段场地 |
| 逃跑 | `flee` | 全部 19 套模型 | 动作片段场地 |

## 家庭与照料

| 动作 | ID | 资源适配 | 试玩位置 |
| --- | --- | --- | --- |
| 安胎与照看胎儿 | `brood` | 灵梦、魔理沙、橙、爱丽丝、帕秋莉、蕾米莉亚、弗兰、壳种魔理沙、多毛灵梦、灵胖、多毛灵胖 | 陆地与船上的接生场景、嚼糜与首便、学起跳 |
| 分娩 | `labor` | 灵梦、魔理沙、橙、爱丽丝、帕秋莉、壳种魔理沙、多毛灵梦、灵胖、多毛灵胖 | 陆地与船上的接生场景、嚼糜与首便、学起跳 |
| 接生 | `midwife` | 魔理沙、壳种魔理沙 | 陆地与船上的接生场景、嚼糜与首便、学起跳 |
| 失去幼体后的悲伤 | `bereaved` | 灵梦、魔理沙、橙、爱丽丝、帕秋莉、蕾米莉亚、弗兰、壳种魔理沙、多毛灵梦、灵胖、多毛灵胖 | 陆地与船上的接生场景、嚼糜与首便、学起跳 |
| 产后休息 | `postpartum` | 灵梦、魔理沙、橙、爱丽丝、帕秋莉、蕾米莉亚、弗兰、壳种魔理沙、多毛灵梦、灵胖、多毛灵胖 | 陆地与船上的接生场景、嚼糜与首便、学起跳 |
| 初生醒来 | `first_wake` | 全部 19 套模型；只用于初生幼体 | 陆地与船上的接生场景、嚼糜与首便、学起跳 |
| 幼油在帽托中休息 | `hat_cradle` | 灵梦、魔理沙、橙、爱丽丝、帕秋莉、蕾米莉亚、弗兰、壳种魔理沙、多毛灵梦、灵胖、多毛灵胖、稀毛灵梦、稀毛魔理沙、歪脸灵梦、歪脸魔理沙、豆形灵梦、豆形魔理沙；只用于帽托幼体 | 陆地与船上的接生场景、嚼糜与首便、学起跳 |

## 帽子与水边

| 动作 | ID | 资源适配 | 试玩位置 |
| --- | --- | --- | --- |
| 接生摘帽 | `hat_off` | 魔理沙 | 池塘场地的帽船与接生场景 |
| 扶帽接生 | `hat_hold` | 魔理沙 | 池塘场地的帽船与接生场景 |
| 接生后戴回帽子 | `hat_on` | 魔理沙 | 池塘场地的帽船与接生场景 |
| 独自摘帽 | `hat_off_self` | 魔理沙 | 池塘场地的帽船与接生场景 |
| 独自戴回帽子 | `hat_on_self` | 魔理沙 | 池塘场地的帽船与接生场景 |
| 水生接生摘帽 | `hat_off_aq` | 魔理沙；水生情景 | 池塘场地的帽船与接生场景 |
| 水生扶帽接生 | `hat_hold_aq` | 魔理沙；水生情景 | 池塘场地的帽船与接生场景 |
| 水生接生后戴帽 | `hat_on_aq` | 魔理沙；水生情景 | 池塘场地的帽船与接生场景 |
| 水生独自摘帽 | `hat_off_self_aq` | 魔理沙；水生情景 | 池塘场地的帽船与接生场景 |
| 水生独自戴帽 | `hat_on_self_aq` | 魔理沙；水生情景 | 池塘场地的帽船与接生场景 |
| 帽船漂流 | `boat_drift` | 魔理沙；水生情景 | 池塘场地的帽船与接生场景 |
| 帽船保持平衡 | `boat_steady` | 魔理沙；水生情景 | 池塘场地的帽船与接生场景 |
| 船上呼喊 | `boat_call` | 魔理沙；水生情景 | 池塘场地的帽船与接生场景 |
| 船上安胎 | `boat_brood` | 魔理沙；水生情景 | 池塘场地的帽船与接生场景 |
| 船上分娩 | `boat_labor` | 魔理沙；水生情景 | 池塘场地的帽船与接生场景 |

## 宠物店与表情

| 动作 | ID | 资源适配 | 试玩位置 |
| --- | --- | --- | --- |
| 思考 | `think` | 全部 19 套模型 | 动作片段场地 |
| 紧张 | `nervous` | 全部 19 套模型 | 动作片段场地 |
| 得意 | `proud` | 全部 19 套模型 | 动作片段场地 |
| 欢呼跳 | `joy_jump` | 全部 19 套模型 | 动作片段场地 |
| 闹别扭 | `sulk` | 全部 19 套模型 | 动作片段场地 |
| 躲猫猫 | `hide_face` | 全部 19 套模型 | 动作片段场地 |
| 贴地抬眼 | `beg` | 全部 19 套模型 | 动作片段场地 |
| 鞠躬 | `bow` | 全部 19 套模型 | 动作片段场地 |
| 害羞 | `shy` | 全部 19 套模型 | 动作片段场地 |
| 不理会 | `ignore` | 全部 19 套模型 | 动作片段场地 |
| 忍住 | `restrain` | 全部 19 套模型 | 动作片段场地 |
| 咬住拽走 | `snatch_pull` | 全部 19 套模型 | 动作片段场地 |
| 扎稳不退 | `stand_ground` | 全部 19 套模型 | 动作片段场地 |
| 递出一口 | `offer_bite` | 全部 19 套模型 | 动作片段场地 |

## 运输与篮子

| 动作 | ID | 资源适配 | 试玩位置 |
| --- | --- | --- | --- |
| 迷糊瘫软 | `doze_off` | 全部 19 套模型 | 动作片段场地 |
| 挣扎抗拒 | `struggle` | 全部 19 套模型 | 动作片段场地 |
| 钻进篮子 | `basket_in` | 全部 19 套模型 | 动作片段场地 |
| 出篮张望 | `basket_out` | 全部 19 套模型 | 动作片段场地 |

## 水上日常与亲子

| 动作 | ID | 资源适配 | 试玩位置 |
| --- | --- | --- | --- |
| 划桨 | `boat_paddle` | 魔理沙；水生情景 | 池塘场地的帽船场景 |
| 收集口粮 | `boat_gather` | 魔理沙；水生情景 | 池塘场地的帽船场景 |
| 船上进食 | `boat_eat` | 魔理沙；水生情景 | 池塘场地的帽船场景 |
| 船上饮水 | `boat_drink` | 魔理沙；水生情景 | 池塘场地的帽船场景 |
| 船上睡觉 | `boat_sleep` | 魔理沙；水生情景 | 池塘场地的帽船场景 |
| 船上醒来 | `boat_wake` | 魔理沙；水生情景 | 池塘场地的帽船场景 |
| 登船 | `boat_board` | 魔理沙；水生情景 | 池塘场地的帽船场景 |
| 船上递食 | `boat_feed_young` | 魔理沙；水生情景 | 池塘场地的帽船场景 |
| 船上亲昵 | `boat_nuzzle` | 魔理沙；水生情景 | 池塘场地的帽船场景 |
| 哼唱摇篮曲 | `boat_lullaby` | 魔理沙；水生情景 | 池塘场地的帽船场景 |
| 返岸理帽 | `aquatic_hat_care` | 魔理沙 | 池塘场地的帽船场景 |

## 初航与帽檐

| 动作 | ID | 资源适配 | 试玩位置 |
| --- | --- | --- | --- |
| 站帽檐 | `hat_brim` | 灵梦、魔理沙、橙、爱丽丝、帕秋莉、蕾米莉亚、弗兰、壳种魔理沙、多毛灵梦、灵胖、多毛灵胖、稀毛灵梦、稀毛魔理沙、歪脸灵梦、歪脸魔理沙、豆形灵梦、豆形魔理沙 | 池塘场地：第一次出航 |
| 试坐平衡 | `boat_first_balance` | 魔理沙；水生情景 | 池塘场地：第一次出航 |
| 帽檐失衡 | `hat_stumble` | 灵梦、魔理沙、橙、爱丽丝、帕秋莉、蕾米莉亚、弗兰、壳种魔理沙、多毛灵梦、灵胖、多毛灵胖、稀毛灵梦、稀毛魔理沙、歪脸灵梦、歪脸魔理沙、豆形灵梦、豆形魔理沙 | 池塘场地：第一次出航 |
| 帽檐拥挤 | `hat_jostle` | 灵梦、魔理沙、橙、爱丽丝、帕秋莉、蕾米莉亚、弗兰、壳种魔理沙、多毛灵梦、灵胖、多毛灵胖、稀毛灵梦、稀毛魔理沙、歪脸灵梦、歪脸魔理沙、豆形灵梦、豆形魔理沙 | 池塘场地：第一次出航 |
| 低下近缘帽檐 | `hat_brim_offer` | 魔理沙 | 池塘场地：第一次出航 |
| 蹿上帽檐 | `hat_hop_up` | 全部 19 套模型 | 池塘场地：第一次出航 |
| 贴缘滑进帽里 | `hat_slide_over` | 全部 19 套模型 | 池塘场地：第一次出航 |

## 落水与救援

| 动作 | ID | 资源适配 | 试玩位置 |
| --- | --- | --- | --- |
| 落水冲击 | `water_plunge` | 全部 19 套模型 | 池塘场地：落水救援 |
| 水中扑腾 | `water_struggle` | 全部 19 套模型 | 池塘场地：落水救援 |
| 呼救喘息 | `water_gasp` | 全部 19 套模型 | 池塘场地：落水救援 |
| 获救喘息 | `water_recover` | 全部 19 套模型 | 池塘场地：落水救援 |
| 翻正帽子 | `water_hat_flip` | 魔理沙 | 池塘场地：落水救援 |
| 抓住帽缘 | `water_hat_grip` | 魔理沙 | 池塘场地：落水救援 |
| 攀回帽上 | `water_hat_climb` | 魔理沙 | 池塘场地：落水救援 |
| 安抚 | `boat_reassure` | 灵梦、魔理沙、橙、爱丽丝、帕秋莉、蕾米莉亚、弗兰、壳种魔理沙、多毛灵梦、灵胖、多毛灵胖、稀毛灵梦、稀毛魔理沙、歪脸灵梦、歪脸魔理沙、豆形灵梦、豆形魔理沙；水生情景 | 池塘场地：落水救援 |
| 惊呼 | `boat_alarm_call` | 灵梦、魔理沙、橙、爱丽丝、帕秋莉、蕾米莉亚、弗兰、壳种魔理沙、多毛灵梦、灵胖、多毛灵胖、稀毛灵梦、稀毛魔理沙、歪脸灵梦、歪脸魔理沙、豆形灵梦、豆形魔理沙；水生情景 | 池塘场地：落水救援 |
| 警戒等待 | `boat_alarm_wait` | 灵梦、魔理沙、橙、爱丽丝、帕秋莉、蕾米莉亚、弗兰、壳种魔理沙、多毛灵梦、灵胖、多毛灵胖、稀毛灵梦、稀毛魔理沙、歪脸灵梦、歪脸魔理沙、豆形灵梦、豆形魔理沙；水生情景 | 池塘场地：落水救援 |
| 撤离 | `boat_flee` | 灵梦、魔理沙、橙、爱丽丝、帕秋莉、蕾米莉亚、弗兰、壳种魔理沙、多毛灵梦、灵胖、多毛灵胖、稀毛灵梦、稀毛魔理沙、歪脸灵梦、歪脸魔理沙、豆形灵梦、豆形魔理沙；水生情景 | 池塘场地：落水救援 |
| 平复 | `boat_calm` | 灵梦、魔理沙、橙、爱丽丝、帕秋莉、蕾米莉亚、弗兰、壳种魔理沙、多毛灵梦、灵胖、多毛灵胖、稀毛灵梦、稀毛魔理沙、歪脸灵梦、歪脸魔理沙、豆形灵梦、豆形魔理沙；水生情景 | 池塘场地：落水救援 |
| 回应呼唤 | `boat_answer_call` | 灵梦、魔理沙、橙、爱丽丝、帕秋莉、蕾米莉亚、弗兰、壳种魔理沙、多毛灵梦、灵胖、多毛灵胖、稀毛灵梦、稀毛魔理沙、歪脸灵梦、歪脸魔理沙、豆形灵梦、豆形魔理沙；水生情景 | 池塘场地：落水救援 |
| 伸辫救援 | `braid_rescue_reach` | 魔理沙 | 池塘场地：落水救援 |
| 辫托孩子 | `braid_rescue_hold` | 魔理沙 | 池塘场地：落水救援 |
| 放回帽中 | `braid_rescue_settle` | 魔理沙 | 池塘场地：落水救援 |
| 水中蹿身 | `water_bob_lunge` | 全部 19 套模型 | 池塘场地：落水救援 |
| 咬住帽缘 | `water_mouth_grip` | 全部 19 套模型 | 池塘场地：落水救援 |
| 抖落帽子 | `water_hat_shake` | 魔理沙 | 池塘场地：落水救援 |
| 身体顶帽 | `water_hat_push` | 魔理沙 | 池塘场地：落水救援 |
| 亲油贴身一顶 | `boat_nudge_young` | 魔理沙；水生情景 | 池塘场地：落水救援 |

## 特殊身体

| 动作 | ID | 资源适配 | 试玩位置 |
| --- | --- | --- | --- |
| 缩壳 | `shell_retract` | 壳种魔理沙 | 仅目录 |
| 产道伸出 | `anatomy_mamu_extend` | 全部 19 套模型 | 仅目录 |
| 产道收回 | `anatomy_mamu_retract` | 全部 19 套模型 | 仅目录 |
| 生殖部伸出 | `anatomy_peni_extend` | 全部 19 套模型 | 仅目录 |
| 生殖部收回 | `anatomy_peni_retract` | 全部 19 套模型 | 仅目录 |

## 注册表之外的内部片段

这些名称不属于 `/yukkuri action` 的可选动作，不放置假按钮。

| 模型 | 内部片段 |
| --- | --- |
| 灵梦 | `boat_be_carried`、`boat_carry_young`、`boat_prepare_carry`、`boat_release_carry`、`care_finish`、`feed_child`、`first_toilet_assist`、`hat_walk`、`learn_jump`、`learn_watch`、`nest_harvest`、`nest_place`、`nest_press`、`nest_spread`、`newborn_crawl`、`water_hat_over`、`water_hat_pull`、`water_held_breath` |
| 魔理沙 | `boat_be_carried`、`boat_carry_young`、`boat_prepare_carry`、`boat_release_carry`、`boat_toilet`、`care_finish`、`feed_child`、`first_toilet_assist`、`hat_walk`、`learn_jump`、`learn_watch`、`nest_harvest`、`nest_place`、`nest_press`、`nest_spread`、`newborn_crawl`、`water_hat_over`、`water_hat_pull`、`water_held_breath` |
| 橙 | `boat_be_carried`、`boat_carry_young`、`boat_prepare_carry`、`boat_release_carry`、`care_finish`、`feed_child`、`first_toilet_assist`、`hat_walk`、`learn_jump`、`learn_watch`、`nest_harvest`、`nest_place`、`nest_press`、`nest_spread`、`newborn_crawl`、`water_hat_over`、`water_hat_pull`、`water_held_breath` |
| 爱丽丝 | `boat_be_carried`、`boat_carry_young`、`boat_prepare_carry`、`boat_release_carry`、`care_finish`、`feed_child`、`first_toilet_assist`、`hat_walk`、`learn_jump`、`learn_watch`、`nest_harvest`、`nest_place`、`nest_press`、`nest_spread`、`newborn_crawl`、`water_hat_over`、`water_hat_pull`、`water_held_breath` |
| 帕秋莉 | `boat_be_carried`、`boat_carry_young`、`boat_prepare_carry`、`boat_release_carry`、`care_finish`、`feed_child`、`first_toilet_assist`、`hat_walk`、`learn_jump`、`learn_watch`、`nest_harvest`、`nest_place`、`nest_press`、`nest_spread`、`newborn_crawl`、`water_hat_over`、`water_hat_pull`、`water_held_breath` |
| 家油灵梦 | `boat_be_carried`、`boat_carry_young`、`boat_prepare_carry`、`boat_release_carry`、`care_finish`、`feed_child`、`first_toilet_assist`、`hat_walk`、`house_carry`、`house_crawl`、`house_deposit`、`house_pickup`、`learn_jump`、`learn_watch`、`nest_harvest`、`nest_place`、`nest_press`、`nest_spread`、`newborn_crawl`、`water_hat_over`、`water_hat_pull`、`water_held_breath` |
| 家油魔理沙 | `boat_be_carried`、`boat_carry_young`、`boat_prepare_carry`、`boat_release_carry`、`care_finish`、`feed_child`、`first_toilet_assist`、`hat_walk`、`house_carry`、`house_crawl`、`house_deposit`、`house_pickup`、`learn_jump`、`learn_watch`、`nest_harvest`、`nest_place`、`nest_press`、`nest_spread`、`newborn_crawl`、`water_hat_over`、`water_hat_pull`、`water_held_breath` |
| 蕾米莉亚 | `boat_be_carried`、`boat_carry_young`、`boat_prepare_carry`、`boat_release_carry`、`care_finish`、`feed_child`、`first_toilet_assist`、`hat_walk`、`learn_jump`、`learn_watch`、`nest_harvest`、`nest_place`、`nest_press`、`nest_spread`、`newborn_crawl`、`predator_bite`、`predator_dive`、`predator_fly`、`predator_land`、`water_hat_over`、`water_hat_pull`、`water_held_breath` |
| 弗兰 | `boat_be_carried`、`boat_carry_young`、`boat_prepare_carry`、`boat_release_carry`、`care_finish`、`feed_child`、`first_toilet_assist`、`hat_walk`、`learn_jump`、`learn_watch`、`nest_harvest`、`nest_place`、`nest_press`、`nest_spread`、`newborn_crawl`、`predator_bite`、`predator_dive`、`predator_fly`、`predator_land`、`water_hat_over`、`water_hat_pull`、`water_held_breath` |
| 壳种魔理沙 | `boat_be_carried`、`boat_carry_young`、`boat_prepare_carry`、`boat_release_carry`、`care_finish`、`feed_child`、`first_toilet_assist`、`hat_walk`、`learn_jump`、`learn_watch`、`nest_harvest`、`nest_place`、`nest_press`、`nest_spread`、`newborn_crawl`、`shell_sway`、`still`、`water_hat_over`、`water_hat_pull`、`water_held_breath` |
| 多毛灵梦 | `boat_be_carried`、`boat_carry_young`、`boat_prepare_carry`、`boat_release_carry`、`care_finish`、`feed_child`、`first_toilet_assist`、`fur_sway`、`hat_walk`、`learn_jump`、`learn_watch`、`nest_harvest`、`nest_place`、`nest_press`、`nest_spread`、`newborn_crawl`、`water_hat_over`、`water_hat_pull`、`water_held_breath` |
| 灵胖 | `boat_be_carried`、`boat_carry_young`、`boat_prepare_carry`、`boat_release_carry`、`care_finish`、`feed_child`、`first_toilet_assist`、`hat_walk`、`learn_jump`、`learn_watch`、`nest_harvest`、`nest_place`、`nest_press`、`nest_spread`、`newborn_crawl`、`water_hat_over`、`water_hat_pull`、`water_held_breath` |
| 多毛灵胖 | `boat_be_carried`、`boat_carry_young`、`boat_prepare_carry`、`boat_release_carry`、`care_finish`、`feed_child`、`first_toilet_assist`、`fur_sway`、`hat_walk`、`learn_jump`、`learn_watch`、`nest_harvest`、`nest_place`、`nest_press`、`nest_spread`、`newborn_crawl`、`water_hat_over`、`water_hat_pull`、`water_held_breath` |
| 稀毛灵梦 | `boat_be_carried`、`boat_carry_young`、`boat_prepare_carry`、`boat_release_carry`、`care_finish`、`feed_child`、`first_toilet_assist`、`hat_walk`、`learn_jump`、`learn_watch`、`nest_harvest`、`nest_place`、`nest_press`、`nest_spread`、`newborn_crawl`、`water_hat_over`、`water_hat_pull`、`water_held_breath` |
| 稀毛魔理沙 | `boat_be_carried`、`boat_carry_young`、`boat_prepare_carry`、`boat_release_carry`、`care_finish`、`feed_child`、`first_toilet_assist`、`hat_walk`、`learn_jump`、`learn_watch`、`nest_harvest`、`nest_place`、`nest_press`、`nest_spread`、`newborn_crawl`、`water_hat_over`、`water_hat_pull`、`water_held_breath` |
| 歪脸灵梦 | `boat_be_carried`、`boat_carry_young`、`boat_prepare_carry`、`boat_release_carry`、`care_finish`、`feed_child`、`first_toilet_assist`、`hat_walk`、`learn_jump`、`learn_watch`、`nest_harvest`、`nest_place`、`nest_press`、`nest_spread`、`newborn_crawl`、`water_hat_over`、`water_hat_pull`、`water_held_breath` |
| 歪脸魔理沙 | `boat_be_carried`、`boat_carry_young`、`boat_prepare_carry`、`boat_release_carry`、`care_finish`、`feed_child`、`first_toilet_assist`、`hat_walk`、`learn_jump`、`learn_watch`、`nest_harvest`、`nest_place`、`nest_press`、`nest_spread`、`newborn_crawl`、`water_hat_over`、`water_hat_pull`、`water_held_breath` |
| 豆形灵梦 | `boat_be_carried`、`boat_carry_young`、`boat_prepare_carry`、`boat_release_carry`、`care_finish`、`feed_child`、`first_toilet_assist`、`hat_walk`、`learn_jump`、`learn_watch`、`nest_harvest`、`nest_place`、`nest_press`、`nest_spread`、`newborn_crawl`、`water_hat_over`、`water_hat_pull`、`water_held_breath` |
| 豆形魔理沙 | `boat_be_carried`、`boat_carry_young`、`boat_prepare_carry`、`boat_release_carry`、`care_finish`、`feed_child`、`first_toilet_assist`、`hat_walk`、`learn_jump`、`learn_watch`、`nest_harvest`、`nest_place`、`nest_press`、`nest_spread`、`newborn_crawl`、`water_hat_over`、`water_hat_pull`、`water_held_breath` |

家油夹取、搬运、放下、蠕动由相应行为状态调用；试玩场的家油场景让一对家油真实拾絮、筑巢、产卵，不播放这些内部片段。

维护入口：`scripts/build_museum_full.py --install`（生成新世界）；本清单用 `--catalog docs/MOTION_MUSEUM_ACTIONS.md --pack-only --output <新目录>` 重新生成。
