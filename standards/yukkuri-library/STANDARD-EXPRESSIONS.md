# 标准表情

当前标准集合为 99 项。权威集合是 [`expressions/index.json`](expressions/index.json) 的 `standard_set`；认可状态和源文件指纹见 [`provenance/standard-expressions.json`](provenance/standard-expressions.json)。

正式源位于 `expressions/presets/`，共用像素部件在 `expressions/parts/`。N033 默认采用棕灰 A，B/C 是同编号备选；完整编号索引见[表情核对清单](expressions/review-list.md)。

全部表情（标准、派生、成年状态、遗骸、专属）的唯一登记在 [`expressions/registry.json`](expressions/registry.json)：`kind`、限定形态 `models`、双帧 `frame_ticks`／`frame_of`、渲染器开口脸 `open_mouth`、无发出点仍保留的 `reserved` 原因；`scripts/check_face_registry.py` 与预设、认可记录、各形态贴图、face_library 及全部发出点核对，并入 `export_ecology_expressions.py --check`。没有别名：发出的 ID 就是贴图 ID。各形态运行选用在 [`expressions/runtime.json`](expressions/runtime.json)；含泪双帧的 B 帧（泪溢出、泪珠顺脸颊流下）由 [`standards/yukkuri-library/tools/author_tear_frames.py`](tools/author_tear_frames.py) 从同组 A 帧的泪池生成，改后用 `tools/sync_expression_exports.py --thumbnails-only <ID…>` 刷新缩略图；嘲讽、眼红、撇嘴不理、羞愧四个社交表情由 [`standards/yukkuri-library/tools/author_feeling_faces.py`](tools/author_feeling_faces.py) 制作，素鸡立表情由 [`standards/yukkuri-library/tools/author_sukkiri_faces.py`](tools/author_sukkiri_faces.py) 制作，美食当前的闪亮眼 `sparkle` 由 [`standards/yukkuri-library/tools/author_sparkle_face.py`](tools/author_sparkle_face.py) 制作；导出和目录命令见[脚本说明](../../scripts/README.md)。全部认可 ID 经现有表情同步路径选用，双帧只在客户端轮播，不改变服务端 ID。当前 24 个模型均有脸部区域；若新增无脸模型，须显式登记 `face_fallback`，不可默认回到普通脸。
