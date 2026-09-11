# 本轮表情修订登记

先审高复用部件，不修改正式表情。N0005 按 N005 记录。

- O02：重绘；眼睛参照灵梦旧版小弯月眼，嘴巴参考 N001
- N002：眼睛同 O02 问题；闭口微笑嘴另调
- O03：嘴巴参考八景 U 得意脸
- O06：嘴巴同 N001；小弯月微笑眼同上
- N005：嘴巴同 N001；眼睛重绘（用户写 N0005）
- O08：重绘
- N010：重绘
- O09：嘴巴、眼睛调整
- N011：嘴巴、眼睛调整
- N012：泪光参考含泪笑，贴下眼缘湿润感，不要椭圆
- N013：泪光同 N012
- N014：眼睛、嘴巴重绘
- N015：眼睛、嘴巴重绘

## 低复用部件，稍后单独判断

按部件种类与相同像素统计，左右眼不重复计数；最多 3 个表情引用。

- O02 / face_eye_left：共用于 O02, O06, O08
- O02 / face_eye_right：共用于 O02, O06, O08
- O02 / face_mouth：共用于 O02, O06
- N002 / face_mouth：共用于 N002
- O03 / face_mouth：共用于 O03
- O06 / face_eye_left：共用于 O02, O06, O08
- O06 / face_eye_right：共用于 O02, O06, O08
- O06 / face_mouth：共用于 O02, O06
- O06 / face_effect_mouth：共用于 O06, O09, O13
- N005 / face_effect_mouth：共用于 N005, N011, N049
- O08 / face_eye_left：共用于 O02, O06, O08
- O08 / face_eye_right：共用于 O02, O06, O08
- O08 / face_mouth：共用于 O08
- O08 / face_tear_left：共用于 O05, O08
- O08 / face_tear_right：共用于 O05, O08
- N010 / face_mouth：共用于 N010, N092
- O09 / face_eye_left：共用于 O09, O13
- O09 / face_eye_right：共用于 O09, O13
- O09 / face_mouth：共用于 O09, O13
- O09 / face_effect_mouth：共用于 O06, O09, O13
- N011 / face_effect_mouth：共用于 N005, N011, N049