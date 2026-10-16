# 展馆动作清单

当前 `YukkuriAction` 注册 110 个动作。此表从注册表、19 套基础模型和动作修订联合生成；“资源适配”指确实存在动画片段，不等于每个年龄、亚种都适合该情景。水生魔理沙与普通魔理沙共用模型，展台按生态身份分开。

展馆（五厅，27 个展台）按情景演示其中 95 个动作（含待机），其余收录在目录，不强行放入参观路线。展台里的演出是 `/yukkuri action` 与 `/yukkuri say` 的指令演出；真实行为只在“动手角”和“聚落”两处。

缩壳虽然有专属资源，但当前命令将所有 `_retract` 当作成对收回动作，导致 `shell_retract` 回到待机。展馆暂不放置无效缩壳按钮；自然受惊时的缩壳由另一条游戏逻辑触发。

身体结构四段、失去幼体后的悲伤（`bereaved`）和家油内部片段不纳入参观路线，但仍在下表列全。

## 身体与移动

| 动作 | ID | 资源适配 | 展台 |
| --- | --- | --- | --- |
| 待机 | `idle` | 全部 19 套模型 | 03 表达与陪伴 |
| 伸缩身体 | `stretch_shape` | 全部 19 套模型 | 01 身体如何活动 |
| 左右侧摆 | `side_bend` | 全部 19 套模型 | 01 身体如何活动 |
| 行走 | `waddle` | 全部 19 套模型 | 01 身体如何活动、04 排泄与照料、06 嚼糜与首便、08 长大与独立、09 家油的巢与卵、16 考场、18 相亲、20 野良应对、24 秋：储粮、25 冬：居巢 |
| 撑起身体 | `brace` | 全部 19 套模型 | 04 排泄与照料、06 嚼糜与首便 |
| 转身 | `turn` | 全部 19 套模型 | 01 身体如何活动 |
| 跳跃 | `hop` | 全部 19 套模型 | 01 身体如何活动、07 学起跳、08 长大与独立、24 秋：储粮 |
| 伸展 | `stretch` | 全部 19 套模型 | 01 身体如何活动、08 长大与独立 |
| 纵向体操 | `exercise_vertical` | 全部 19 套模型 | 01 身体如何活动、07 学起跳、08 长大与独立 |
| 横向体操 | `exercise_sides` | 全部 19 套模型 | 01 身体如何活动 |
| 挤压 | `press` | 全部 19 套模型 | 01 身体如何活动 |
| 广播体操 | `taiso` | 全部 19 套模型 | 01 身体如何活动 |

## 日常生活

| 动作 | ID | 资源适配 | 展台 |
| --- | --- | --- | --- |
| 进食 | `eat` | 全部 19 套模型 | 02 悠闲的一天、06 嚼糜与首便、13 船上育儿、22 商店与人类、24 秋：储粮、25 冬：居巢 |
| 饭后幸福 | `shiawase` | 全部 19 套模型 | 02 悠闲的一天、04 排泄与照料、06 嚼糜与首便、08 长大与独立、09 家油的巢与卵、12 帽船上的一天、13 船上育儿、14 第一次出航、18 相亲、22 商店与人类、24 秋：储粮 |
| 饮水 | `drink` | 全部 19 套模型 | 02 悠闲的一天 |
| 睡觉 | `sleep` | 全部 19 套模型 | 02 悠闲的一天、08 长大与独立、13 船上育儿、19 运输、25 冬：居巢 |
| 醒来 | `wake` | 全部 19 套模型 | 02 悠闲的一天 |
| 甩水 | `shake` | 全部 19 套模型 | 02 悠闲的一天、12 帽船上的一天 |

## 交流与情绪

| 动作 | ID | 资源适配 | 展台 |
| --- | --- | --- | --- |
| 蹭蹭 | `nuzzle` | 全部 19 套模型 | 03 表达与陪伴、06 嚼糜与首便、09 家油的巢与卵、18 相亲 |
| 唱歌 | `sing` | 全部 19 套模型 | 03 表达与陪伴 |
| 摇摆 | `sway` | 全部 19 套模型 | 03 表达与陪伴、07 学起跳、13 船上育儿 |
| 打招呼 | `greet` | 全部 19 套模型 | 03 表达与陪伴、08 长大与独立、14 第一次出航 |
| 张望 | `look_around` | 全部 19 套模型 | 03 表达与陪伴、05 迎接新生命、06 嚼糜与首便、07 学起跳、08 长大与独立、09 家油的巢与卵、14 第一次出航、22 商店与人类、24 秋：储粮 |
| 鼓腮 | `puff` | 全部 19 套模型 | 03 表达与陪伴、17 徽章与旁人、20 野良应对 |
| 推挤 | `push` | 全部 19 套模型 | 20 野良应对 |
| 受惊 | `startle` | 全部 19 套模型 | 03 表达与陪伴、21 捕食种 |
| 发抖 | `tremble` | 全部 19 套模型 | 03 表达与陪伴、04 排泄与照料、06 嚼糜与首便、16 考场、25 冬：居巢 |
| 低落 | `droop` | 全部 19 套模型 | 03 表达与陪伴、04 排泄与照料、17 徽章与旁人 |
| 逃跑 | `flee` | 全部 19 套模型 | 21 捕食种 |

## 家庭与照料

| 动作 | ID | 资源适配 | 展台 |
| --- | --- | --- | --- |
| 安胎与照看胎儿 | `brood` | 灵梦、魔理沙、橙、爱丽丝、帕秋莉、蕾米莉亚、弗兰、壳种魔理沙、多毛灵梦、灵胖、多毛灵胖 | 05 迎接新生命 |
| 分娩 | `labor` | 灵梦、魔理沙、橙、爱丽丝、帕秋莉、壳种魔理沙、多毛灵梦、灵胖、多毛灵胖 | 05 迎接新生命 |
| 接生 | `midwife` | 魔理沙、壳种魔理沙 | 05 迎接新生命 |
| 失去幼体后的悲伤 | `bereaved` | 灵梦、魔理沙、橙、爱丽丝、帕秋莉、蕾米莉亚、弗兰、壳种魔理沙、多毛灵梦、灵胖、多毛灵胖 | 仅目录 |
| 产后休息 | `postpartum` | 灵梦、魔理沙、橙、爱丽丝、帕秋莉、蕾米莉亚、弗兰、壳种魔理沙、多毛灵梦、灵胖、多毛灵胖 | 05 迎接新生命 |
| 初生醒来 | `first_wake` | 全部 19 套模型；只用于初生幼体 | 05 迎接新生命、09 家油的巢与卵 |
| 幼油在帽托中休息 | `hat_cradle` | 灵梦、魔理沙、橙、爱丽丝、帕秋莉、蕾米莉亚、弗兰、壳种魔理沙、多毛灵梦、灵胖、多毛灵胖、稀毛灵梦、稀毛魔理沙、歪脸灵梦、歪脸魔理沙、豆形灵梦、豆形魔理沙；只用于帽托幼体 | 13 船上育儿 |

## 帽子与水边

| 动作 | ID | 资源适配 | 展台 |
| --- | --- | --- | --- |
| 接生摘帽 | `hat_off` | 魔理沙 | 10 魔理沙的帽子 |
| 扶帽接生 | `hat_hold` | 魔理沙 | 10 魔理沙的帽子 |
| 接生后戴回帽子 | `hat_on` | 魔理沙 | 10 魔理沙的帽子 |
| 独自摘帽 | `hat_off_self` | 魔理沙 | 10 魔理沙的帽子 |
| 独自戴回帽子 | `hat_on_self` | 魔理沙 | 10 魔理沙的帽子 |
| 水生接生摘帽 | `hat_off_aq` | 魔理沙；水生情景 | 11 水边的适应 |
| 水生扶帽接生 | `hat_hold_aq` | 魔理沙；水生情景 | 11 水边的适应 |
| 水生接生后戴帽 | `hat_on_aq` | 魔理沙；水生情景 | 11 水边的适应 |
| 水生独自摘帽 | `hat_off_self_aq` | 魔理沙；水生情景 | 11 水边的适应、14 第一次出航 |
| 水生独自戴帽 | `hat_on_self_aq` | 魔理沙；水生情景 | 11 水边的适应、14 第一次出航 |
| 帽船漂流 | `boat_drift` | 魔理沙；水生情景 | 11 水边的适应 |
| 帽船保持平衡 | `boat_steady` | 魔理沙；水生情景 | 11 水边的适应、14 第一次出航、15 落水与救援 |
| 船上呼喊 | `boat_call` | 魔理沙；水生情景 | 11 水边的适应 |
| 船上安胎 | `boat_brood` | 魔理沙；水生情景 | 13 船上育儿 |
| 船上分娩 | `boat_labor` | 魔理沙；水生情景 | 13 船上育儿 |

## 宠物店与表情

| 动作 | ID | 资源适配 | 展台 |
| --- | --- | --- | --- |
| 思考 | `think` | 全部 19 套模型 | 16 考场 |
| 紧张 | `nervous` | 全部 19 套模型 | 03 表达与陪伴、16 考场、17 徽章与旁人 |
| 得意 | `proud` | 全部 19 套模型 | 03 表达与陪伴、16 考场、17 徽章与旁人 |
| 欢呼跳 | `joy_jump` | 全部 19 套模型 | 03 表达与陪伴、07 学起跳、16 考场、17 徽章与旁人、22 商店与人类 |
| 闹别扭 | `sulk` | 全部 19 套模型 | 03 表达与陪伴、16 考场 |
| 躲猫猫 | `hide_face` | 全部 19 套模型 | 16 考场 |
| 贴地抬眼 | `beg` | 全部 19 套模型 | 17 徽章与旁人、20 野良应对、22 商店与人类、25 冬：居巢 |
| 鞠躬 | `bow` | 全部 19 套模型 | 08 长大与独立、19 运输、20 野良应对、21 捕食种 |
| 害羞 | `shy` | 全部 19 套模型 | 03 表达与陪伴、18 相亲 |
| 不理会 | `ignore` | 全部 19 套模型 | 20 野良应对、21 捕食种 |
| 忍住 | `restrain` | 全部 19 套模型 | 21 捕食种 |

## 运输与篮子

| 动作 | ID | 资源适配 | 展台 |
| --- | --- | --- | --- |
| 迷糊瘫软 | `doze_off` | 全部 19 套模型 | 19 运输 |
| 挣扎抗拒 | `struggle` | 全部 19 套模型 | 19 运输 |
| 钻进篮子 | `basket_in` | 全部 19 套模型 | 19 运输、22 商店与人类 |
| 出篮张望 | `basket_out` | 全部 19 套模型 | 18 相亲、19 运输、22 商店与人类 |

## 水上日常与亲子

| 动作 | ID | 资源适配 | 展台 |
| --- | --- | --- | --- |
| 划桨 | `boat_paddle` | 魔理沙；水生情景 | 12 帽船上的一天、14 第一次出航、15 落水与救援 |
| 收集口粮 | `boat_gather` | 魔理沙；水生情景 | 12 帽船上的一天 |
| 船上进食 | `boat_eat` | 魔理沙；水生情景 | 12 帽船上的一天、13 船上育儿 |
| 船上饮水 | `boat_drink` | 魔理沙；水生情景 | 12 帽船上的一天 |
| 船上睡觉 | `boat_sleep` | 魔理沙；水生情景 | 12 帽船上的一天 |
| 船上醒来 | `boat_wake` | 魔理沙；水生情景 | 12 帽船上的一天 |
| 登船 | `boat_board` | 魔理沙；水生情景 | 12 帽船上的一天、14 第一次出航 |
| 船上递食 | `boat_feed_young` | 魔理沙；水生情景 | 13 船上育儿 |
| 船上亲昵 | `boat_nuzzle` | 魔理沙；水生情景 | 13 船上育儿、15 落水与救援 |
| 哼唱摇篮曲 | `boat_lullaby` | 魔理沙；水生情景 | 13 船上育儿 |
| 返岸理帽 | `aquatic_hat_care` | 魔理沙 | 12 帽船上的一天 |

## 初航与帽檐

| 动作 | ID | 资源适配 | 展台 |
| --- | --- | --- | --- |
| 站帽檐 | `hat_brim` | 灵梦、魔理沙、橙、爱丽丝、帕秋莉、蕾米莉亚、弗兰、壳种魔理沙、多毛灵梦、灵胖、多毛灵胖、稀毛灵梦、稀毛魔理沙、歪脸灵梦、歪脸魔理沙、豆形灵梦、豆形魔理沙 | 15 落水与救援 |
| 试坐平衡 | `boat_first_balance` | 魔理沙；水生情景 | 14 第一次出航 |
| 帽檐失衡 | `hat_stumble` | 灵梦、魔理沙、橙、爱丽丝、帕秋莉、蕾米莉亚、弗兰、壳种魔理沙、多毛灵梦、灵胖、多毛灵胖、稀毛灵梦、稀毛魔理沙、歪脸灵梦、歪脸魔理沙、豆形灵梦、豆形魔理沙 | 15 落水与救援 |
| 帽檐拥挤 | `hat_jostle` | 灵梦、魔理沙、橙、爱丽丝、帕秋莉、蕾米莉亚、弗兰、壳种魔理沙、多毛灵梦、灵胖、多毛灵胖、稀毛灵梦、稀毛魔理沙、歪脸灵梦、歪脸魔理沙、豆形灵梦、豆形魔理沙 | 仅目录 |
| 低下近缘帽檐 | `hat_brim_offer` | 魔理沙 | 15 落水与救援 |
| 蹿上帽檐 | `hat_hop_up` | 全部 19 套模型 | 15 落水与救援 |
| 贴缘滑进帽里 | `hat_slide_over` | 全部 19 套模型 | 15 落水与救援 |

## 落水与救援

| 动作 | ID | 资源适配 | 展台 |
| --- | --- | --- | --- |
| 落水冲击 | `water_plunge` | 全部 19 套模型 | 15 落水与救援 |
| 水中扑腾 | `water_struggle` | 全部 19 套模型 | 15 落水与救援 |
| 呼救喘息 | `water_gasp` | 全部 19 套模型 | 仅目录 |
| 获救喘息 | `water_recover` | 全部 19 套模型 | 15 落水与救援 |
| 翻正帽子 | `water_hat_flip` | 魔理沙 | 仅目录 |
| 抓住帽缘 | `water_hat_grip` | 魔理沙 | 仅目录 |
| 攀回帽上 | `water_hat_climb` | 魔理沙 | 仅目录 |
| 安抚 | `boat_reassure` | 灵梦、魔理沙、橙、爱丽丝、帕秋莉、蕾米莉亚、弗兰、壳种魔理沙、多毛灵梦、灵胖、多毛灵胖、稀毛灵梦、稀毛魔理沙、歪脸灵梦、歪脸魔理沙、豆形灵梦、豆形魔理沙；水生情景 | 14 第一次出航 |
| 惊呼 | `boat_alarm_call` | 灵梦、魔理沙、橙、爱丽丝、帕秋莉、蕾米莉亚、弗兰、壳种魔理沙、多毛灵梦、灵胖、多毛灵胖、稀毛灵梦、稀毛魔理沙、歪脸灵梦、歪脸魔理沙、豆形灵梦、豆形魔理沙；水生情景 | 15 落水与救援 |
| 警戒等待 | `boat_alarm_wait` | 灵梦、魔理沙、橙、爱丽丝、帕秋莉、蕾米莉亚、弗兰、壳种魔理沙、多毛灵梦、灵胖、多毛灵胖、稀毛灵梦、稀毛魔理沙、歪脸灵梦、歪脸魔理沙、豆形灵梦、豆形魔理沙；水生情景 | 仅目录 |
| 撤离 | `boat_flee` | 灵梦、魔理沙、橙、爱丽丝、帕秋莉、蕾米莉亚、弗兰、壳种魔理沙、多毛灵梦、灵胖、多毛灵胖、稀毛灵梦、稀毛魔理沙、歪脸灵梦、歪脸魔理沙、豆形灵梦、豆形魔理沙；水生情景 | 15 落水与救援 |
| 平复 | `boat_calm` | 灵梦、魔理沙、橙、爱丽丝、帕秋莉、蕾米莉亚、弗兰、壳种魔理沙、多毛灵梦、灵胖、多毛灵胖、稀毛灵梦、稀毛魔理沙、歪脸灵梦、歪脸魔理沙、豆形灵梦、豆形魔理沙；水生情景 | 15 落水与救援 |
| 回应呼唤 | `boat_answer_call` | 灵梦、魔理沙、橙、爱丽丝、帕秋莉、蕾米莉亚、弗兰、壳种魔理沙、多毛灵梦、灵胖、多毛灵胖、稀毛灵梦、稀毛魔理沙、歪脸灵梦、歪脸魔理沙、豆形灵梦、豆形魔理沙；水生情景 | 15 落水与救援 |
| 伸辫救援 | `braid_rescue_reach` | 魔理沙 | 仅目录 |
| 辫托孩子 | `braid_rescue_hold` | 魔理沙 | 仅目录 |
| 放回帽中 | `braid_rescue_settle` | 魔理沙 | 仅目录 |
| 水中蹿身 | `water_bob_lunge` | 全部 19 套模型 | 15 落水与救援 |
| 咬住帽缘 | `water_mouth_grip` | 全部 19 套模型 | 15 落水与救援 |
| 抖落帽子 | `water_hat_shake` | 魔理沙 | 15 落水与救援 |
| 身体顶帽 | `water_hat_push` | 魔理沙 | 15 落水与救援 |
| 亲油贴身一顶 | `boat_nudge_young` | 魔理沙；水生情景 | 15 落水与救援 |

## 特殊身体

| 动作 | ID | 资源适配 | 展台 |
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
| 灵梦 | `boat_be_carried`、`boat_carry_young`、`care_finish`、`feed_child`、`first_toilet_assist`、`hat_walk`、`learn_jump`、`learn_watch`、`nest_harvest`、`nest_place`、`nest_press`、`nest_spread`、`newborn_crawl`、`water_hat_over`、`water_hat_pull`、`water_held_breath` |
| 魔理沙 | `boat_be_carried`、`boat_carry_young`、`boat_toilet`、`care_finish`、`feed_child`、`first_toilet_assist`、`hat_walk`、`learn_jump`、`learn_watch`、`nest_harvest`、`nest_place`、`nest_press`、`nest_spread`、`newborn_crawl`、`water_hat_over`、`water_hat_pull`、`water_held_breath` |
| 橙 | `boat_be_carried`、`boat_carry_young`、`care_finish`、`feed_child`、`first_toilet_assist`、`hat_walk`、`learn_jump`、`learn_watch`、`nest_harvest`、`nest_place`、`nest_press`、`nest_spread`、`newborn_crawl`、`water_hat_over`、`water_hat_pull`、`water_held_breath` |
| 爱丽丝 | `boat_be_carried`、`boat_carry_young`、`care_finish`、`feed_child`、`first_toilet_assist`、`hat_walk`、`learn_jump`、`learn_watch`、`nest_harvest`、`nest_place`、`nest_press`、`nest_spread`、`newborn_crawl`、`water_hat_over`、`water_hat_pull`、`water_held_breath` |
| 帕秋莉 | `boat_be_carried`、`boat_carry_young`、`care_finish`、`feed_child`、`first_toilet_assist`、`hat_walk`、`learn_jump`、`learn_watch`、`nest_harvest`、`nest_place`、`nest_press`、`nest_spread`、`newborn_crawl`、`water_hat_over`、`water_hat_pull`、`water_held_breath` |
| 家油灵梦 | `boat_be_carried`、`boat_carry_young`、`care_finish`、`feed_child`、`first_toilet_assist`、`hat_walk`、`house_carry`、`house_crawl`、`house_deposit`、`house_pickup`、`learn_jump`、`learn_watch`、`nest_harvest`、`nest_place`、`nest_press`、`nest_spread`、`newborn_crawl`、`water_hat_over`、`water_hat_pull`、`water_held_breath` |
| 家油魔理沙 | `boat_be_carried`、`boat_carry_young`、`care_finish`、`feed_child`、`first_toilet_assist`、`hat_walk`、`house_carry`、`house_crawl`、`house_deposit`、`house_pickup`、`learn_jump`、`learn_watch`、`nest_harvest`、`nest_place`、`nest_press`、`nest_spread`、`newborn_crawl`、`water_hat_over`、`water_hat_pull`、`water_held_breath` |
| 蕾米莉亚 | `boat_be_carried`、`boat_carry_young`、`care_finish`、`feed_child`、`first_toilet_assist`、`hat_walk`、`learn_jump`、`learn_watch`、`nest_harvest`、`nest_place`、`nest_press`、`nest_spread`、`newborn_crawl`、`predator_bite`、`predator_dive`、`predator_fly`、`predator_land`、`water_hat_over`、`water_hat_pull`、`water_held_breath` |
| 弗兰 | `boat_be_carried`、`boat_carry_young`、`care_finish`、`feed_child`、`first_toilet_assist`、`hat_walk`、`learn_jump`、`learn_watch`、`nest_harvest`、`nest_place`、`nest_press`、`nest_spread`、`newborn_crawl`、`predator_bite`、`predator_dive`、`predator_fly`、`predator_land`、`water_hat_over`、`water_hat_pull`、`water_held_breath` |
| 壳种魔理沙 | `boat_be_carried`、`boat_carry_young`、`care_finish`、`feed_child`、`first_toilet_assist`、`hat_walk`、`learn_jump`、`learn_watch`、`nest_harvest`、`nest_place`、`nest_press`、`nest_spread`、`newborn_crawl`、`shell_sway`、`still`、`water_hat_over`、`water_hat_pull`、`water_held_breath` |
| 多毛灵梦 | `boat_be_carried`、`boat_carry_young`、`care_finish`、`feed_child`、`first_toilet_assist`、`fur_sway`、`hat_walk`、`learn_jump`、`learn_watch`、`nest_harvest`、`nest_place`、`nest_press`、`nest_spread`、`newborn_crawl`、`water_hat_over`、`water_hat_pull`、`water_held_breath` |
| 灵胖 | `boat_be_carried`、`boat_carry_young`、`care_finish`、`feed_child`、`first_toilet_assist`、`hat_walk`、`learn_jump`、`learn_watch`、`nest_harvest`、`nest_place`、`nest_press`、`nest_spread`、`newborn_crawl`、`water_hat_over`、`water_hat_pull`、`water_held_breath` |
| 多毛灵胖 | `boat_be_carried`、`boat_carry_young`、`care_finish`、`feed_child`、`first_toilet_assist`、`fur_sway`、`hat_walk`、`learn_jump`、`learn_watch`、`nest_harvest`、`nest_place`、`nest_press`、`nest_spread`、`newborn_crawl`、`water_hat_over`、`water_hat_pull`、`water_held_breath` |
| 稀毛灵梦 | `boat_be_carried`、`boat_carry_young`、`care_finish`、`feed_child`、`first_toilet_assist`、`hat_walk`、`learn_jump`、`learn_watch`、`nest_harvest`、`nest_place`、`nest_press`、`nest_spread`、`newborn_crawl`、`water_hat_over`、`water_hat_pull`、`water_held_breath` |
| 稀毛魔理沙 | `boat_be_carried`、`boat_carry_young`、`care_finish`、`feed_child`、`first_toilet_assist`、`hat_walk`、`learn_jump`、`learn_watch`、`nest_harvest`、`nest_place`、`nest_press`、`nest_spread`、`newborn_crawl`、`water_hat_over`、`water_hat_pull`、`water_held_breath` |
| 歪脸灵梦 | `boat_be_carried`、`boat_carry_young`、`care_finish`、`feed_child`、`first_toilet_assist`、`hat_walk`、`learn_jump`、`learn_watch`、`nest_harvest`、`nest_place`、`nest_press`、`nest_spread`、`newborn_crawl`、`water_hat_over`、`water_hat_pull`、`water_held_breath` |
| 歪脸魔理沙 | `boat_be_carried`、`boat_carry_young`、`care_finish`、`feed_child`、`first_toilet_assist`、`hat_walk`、`learn_jump`、`learn_watch`、`nest_harvest`、`nest_place`、`nest_press`、`nest_spread`、`newborn_crawl`、`water_hat_over`、`water_hat_pull`、`water_held_breath` |
| 豆形灵梦 | `boat_be_carried`、`boat_carry_young`、`care_finish`、`feed_child`、`first_toilet_assist`、`hat_walk`、`learn_jump`、`learn_watch`、`nest_harvest`、`nest_place`、`nest_press`、`nest_spread`、`newborn_crawl`、`water_hat_over`、`water_hat_pull`、`water_held_breath` |
| 豆形魔理沙 | `boat_be_carried`、`boat_carry_young`、`care_finish`、`feed_child`、`first_toilet_assist`、`hat_walk`、`learn_jump`、`learn_watch`、`nest_harvest`、`nest_place`、`nest_press`、`nest_spread`、`newborn_crawl`、`water_hat_over`、`water_hat_pull`、`water_held_breath` |

家油夹取、搬运、放下、蠕动由相应行为状态调用；展馆的家油展台用标准动作与台词事件演示，不播放这些内部片段。

维护入口：`scripts/build_museum_full.py --catalog docs/MOTION_MUSEUM_ACTIONS.md`（同时需提供种子 level.dat 与新的输出目录）。
