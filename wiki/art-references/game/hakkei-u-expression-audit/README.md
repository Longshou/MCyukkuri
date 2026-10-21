# 八景 U 表情资源字段核对

游戏资源：E:/yukkuri/资料/原作参考/eightSight.v8.3/ゆっくり八景U_Data。

本版 Assembly-CSharp.dll 的 y_graphic 类型有 1352 个公开 Sprite[] 字段。按实际字段顺序解析 level0 中 MonoBehaviour 76216：数据头 32 字节，逐数组 int32 个数与 (int32 fileID, int64 pathID) 引用，恰好消费 224788 字节；level0 外部文件表确认 fileID 2 指向 sharedassets0.assets。

旧 graphic-map.json 自第 1176 个字段起与当前 DLL 字段顺序不符，因此其中“寝転びあくび顔”实际指向帽子资源。graphic-map-verified.json 按当前 DLL 顺序登记字段，不使用错位字段作为表情依据。

237 个相关字段含 221 个非空字段与 16 个全空数组。基本驚愕顔和基本困惑顔３复用 Sprite 5640，槽位 0 有 220 个不同 Sprite。contact-1.png 至 contact-8.png 是提取原图联系表，不是游戏截图。

yu_base.cs 34535–34544、35301–35313 展示默认表情族偏移为 0、种族标记 2/3 时偏移 2/4，再加成长标记；menu.cs 11230–11237 的较小成员用成长标记 0，11261–11263 的父辈用标记 1。字段到表情配方的映射见 provenance/expression-coverage.json。

[眼部表现参考](n033-round2-reference.md)记录 Sprite 7788 与相关漫画画格的来源范围。
