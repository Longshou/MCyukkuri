# 项目导航

当前分为模组开发与美术制作两部分：游戏代码已有工程骨架；角色动作、表情和生活场景已有制作资产，等待逐项接入实体。

| 类别 | 主入口 | 内容与维护方式 |
| --- | --- | --- |
| 当前复用标准 | [素材登记](standards/yukkuri-library/index.html) · [动作／表情对照](standards/yukkuri-library/compare.html) | 共用组件、91 个表情、9 个基础动作与 6 个复合配方；新制作的唯一编辑入口 |
| 游戏代码 | [src](src) · [开发指南](docs/DEVELOPMENT.md) | 实体、注册、服务器行为和客户端渲染放这里；实际运行资源放 `src/main/resources` |
| 基础角色 | [art/source](art/README.md) | 当前选定的魔理沙与灵梦基础体型，作为造型基准 |
| 动作资料 | [动作分类](art/library/README.md#角色动作) | 甩臀、伸长、半速体操、鼓腮、排嗯嗯、灵梦整套体操；保存采用中及未淘汰版本的骨骼与关键帧 |
| 表情方案 | [表情分类](art/library/README.md#表情与脸部试验) | 分层表情、噗咕 A 与完整眼线、刘海及组合试验 |
| 互动场景 | [场景分类](art/library/README.md#互动与生活场景) | 进食状态机、噗咕较量与逃跑；同时保留行为脚本和动画数据 |
| 道具模型 | [art/props](art/props/README.md) | 独立馅料团、食盆与油粮；记录来源与校验值 |
| 历史方案 | [历史分类](art/library/README.md#历史造型与动作方案) | 仍有依赖或未淘汰的早期体型、移动与伸长方案；明确弃用者排除 |
| 设定与出处 | [wiki](wiki/README.md) | 作品与游戏资料、核读边界、背景设定 |
| 保存与恢复 | [资产流程](docs/ASSET_WORKFLOW.md) · [本地备份](archives/README.md) | 分类源文件包、逐文件 SHA-256、保留方案的本地快照与恢复工具 |

## 目录约定

```text
art/
  source/                         选定的角色基础模型
  props/                          可复用道具模型
  library/
    models/                       基础角色制作包
    motions/                      单体动作制作包
    expressions/                  表情与候选制作包
    scenes/                       互动行为制作包
    history/                      历史方案制作包
    shared/                       共享工具和早期资产制作包
    snapshots/                    历次分类目录快照
    catalog.json                  全部包、动作时长、版本与依赖索引
variants/                         原始美术工作目录（路径保持稳定）
archives/art-preservation/         本地完整历史，包括参考与渲染帧
tools/project-library/            建库、校验、恢复工具
src/                              模组源码及实际打入 Jar 的资源
docs/                             开发路线与维护规范
wiki/                             设定与来源资料
```

## 后续接入顺序

1. **角色基础进入游戏：** 保持选定外观，接入实体、碰撞、待机与移动；统一现有多套骨骼的运行版本。
2. **动作和表情调用：** 按动作 ID 接入控制器，保留半速体操等既定节奏。表情和动作优先级由运行层协调，源文件候选继续留档。
3. **生活行为与道具：** 先接进食与食盆，再接排嗯嗯、馅料生成和保存；食量和生成事件由服务器管理。
4. **互动与生态：** 接噗咕较量、逃跑和群体行为，再推进成长、家庭与巢穴。

以上是接入规划，不能把浏览器演示视为已经实现的游戏功能。具体里程碑见 [开发路线](docs/ROADMAP.md)。

## 弃用处理

以 [分类配置](art/library/collections.json) 中的 `retired` 为准。强烈收腰、后身纵深和噗咕眼形 B/C/D 不进入新的保存与恢复。旧文件的实际删除被执行策略拦截，残留范围见 [清理清单](art/library/retirement-plan.json)。
