# 魔理沙单侧粗辫

当前已确认造型及各形态模型、动作指纹见 [approved.json](approved.json)；制作参数见 [candidate.json](candidate.json)。辫子位于魔理沙自身左侧（`+X`、`hand_right`），由暗色内芯、六节交错编织段、白色发结和散开发尾组成；另一侧为不编织的短侧发。只复用既有贴图格，不修改贴图或表情图集。头发部件名含 `hair` 或 `braid`，秃发时按侧隐藏。

圆润魔理沙各形态使用同一辫型。稀毛体只保留一束辫、发结、发尾及短侧发；豆形魔理沙按头部比例缩小并沿轮廓外移，参数分别见 [稀毛配方](../../../subspecies/deficient/rounded-marisa/recipe.json) 和 [豆形配方](../../../subspecies/deficient/recipes.json)。家油魔理沙夹取、搬运和放下时由两处辫梢夹点带动物品，短侧发留在头侧；家油灵梦仍使用两束短发。

摇摆、撑住、压低和侧弯动作按 [动作修正配方](../../../motions/variants/contact-safe/README.md) 生成辫子跟随曲线；螺壳缩入时头发向后并略向外张开，避免进入压扁的身体。改动来源或动作时，由 [sync_inputs.py](sync_inputs.py) 更新冻结输入；建模入口为 [author.py](author.py)，家油模型接入入口为 [export_house_runtime.py](export_house_runtime.py)。
