# 野外事件本轮验收

2026-09-13。实现与逐卡边界见 [41 卡实施记录](../../WILD_EVENTS_IMPLEMENTATION.md)，画面见 [16 组实机预览](index.html)。

`runtime-17` 固定副本中的事件专项共 141 项断言全部通过，测试进程正常退出。覆盖真实亲缘、区域互斥、持久化、实际菜单操作、有限食物、真实动作、帽船与探险队等路径；具体断言为 [checks.json](checks.json)，编译与引擎日志为 [compile.log](compile.log)、[gametest.log](gametest.log)。本结果只适用于登记的运行副本，不代表后续共享源码或全项目回归通过。

预览保留 16 组、111 张精选客户端帧。主采集为 `runtime-14`，姐妹、单亲、帽船、探险补采为 `runtime-17`；每组版本和图片 SHA-256 见 [preview.json](preview.json)。[主采集状态](capture-results.json)、[补采状态](capture-supplement-results.json)与 `frames/` 原采集记录保留事件 UUID、阶段和真实实体状态。原记录中的完整帧路径指向本机 `.local/wild-events/`；可随项目查看的精选图位于 `images/`。没有修改截图像素。

本轮核看姐妹等待与逐个获食、单亲等待与全体获食、真帽船岸边跟随和实际救援后续、探险往返的画面；此前主采集还核看认知页、歌唱、收养请求、家庭分离、弃养、口癖、教学和森林辨食。认知页由认知任务独立验收，15 页字体高度证据见认知专题。生存自然游玩、多人晚加入和双玩家并发尚未执行；标准造型确认状态不因截图或规则测试而改变。

[运行与来源指纹](fingerprints.json)登记最新固定副本；`fingerprints-runtime-14.json` 与 `fingerprints-runtime-16.json` 保留此前采集与修正过程的版本证据。页面在桌面和手机宽度下检查图片加载、滑块与脚本错误，结果见 [browser-check.json](browser-check.json)。

事件源码与测试已释放，临时客户端已停止。完整临时日志、测试世界和帧序列保留在 `.local/wild-events/`；未清理其他任务进程或输出。中文事实与文风为人工审校。
