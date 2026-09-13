"""Factory workflow UI text; merged without changing other feature namespaces."""
def language(chinese):
    pairs={
        'purpose.0':('两种用途','Both uses'),'purpose.1':('油虐用','Abuse use'),'purpose.2':('饲料用','Predator feed'),
        'stage_progress':('%s · %s%%','%s / %s%%'),
        'purpose_mode':('生产：%s →','Produce: %s >'),'sale_filter':('选购：%s →','Buy: %s >'),
        'action.19':('交入后备亲代','Deliver reserve parent'),'action.20':('修复托盘 · 铁锭 ×4','Repair tray / 4 iron'),
        'help.17':('选择后续分选的商品用途；两种用途交替生产。已经分选的批次保留原用途。','Choose future grading use. Both alternates between uses; already graded occupants keep their labels.'),
        'help.18':('选择要购买的用途，只从匹配的真实库存出货。','Choose which real product stock to purchase.'),
        'help.19':('手持装有成年普通种的运输箱。工厂接收后备亲代，检查亲缘与配对后补入空工位。','Hold your carrier with an adult ordinary species. Compatible unrelated reserve pairs fill vacant parent stations.'),
        'help.20':('托盘损坏后，手持至少 4 个铁锭修复。未加载的托盘不会被复制。','Hold 4 iron ingots to repair a destroyed tray. Unloaded trays are never duplicated.'),
        'pipeline_state':('产线：%s；亲代 %s，育成 %s，待出货 %s；出生检查 %s，观察转入 %s。','Line: %s; parents %s, nursery %s, inputs %s; birth checks %s, observation transfers %s.'),
        'line_status.0':('正常轮转','Operating'),'line_status.1':('原料用尽','No material'),'line_status.2':('成品库满','Storage full'),
        'line_status.3':('托盘待修复','Tray needs repair'),'line_status.4':('缺少工序设备','Missing station'),'line_status.5':('运输受阻','Transport blocked'),
        'line_status.6':('车间未完全加载','Workshop partly unloaded'),'line_status.7':('托盘尚未加载','Tray unloaded'),
        'size.0':('小号','Small'),'size.1':('中号','Medium'),'size.2':('大号','Large'),
        'package_spec':('%s · 第 %s 批 · %s · %s','%s / Batch %s / %s / %s'),
        'package_state':('活体 %s 只；生命 %s%%，营养 %s，馅料份量 %s。','%s live occupant; health %s%%, nutrition %s, filling portions %s.'),
        'package_treatment':('已检视、清洁。年龄、亲缘和身体状态随原个体保留。','Inspected and cleaned. Original age, family and body state are retained.'),
        'package_activity':('精力 %s；足部灼伤记录 %s；%s。','Energy %s; foot burn record %s; %s.'),
        'voice.young':('幼语','Young speech'),'voice.normal':('普通语音','Ordinary speech'),
        'bag_description':('单体活体包装，右键地面拆袋。饲料规格已标示，捕食种投喂待其接入。','Single live occupant. Use on ground to open. Feed grading is labeled; predator feeding awaits predator integration.'),
        'guide.factory':('亲代按组轮转，子油经接收、育成、分选、清洁后出货；周转托盘实际搬运，工人到场作业。可切换油虐用／饲料用及选购库存。原料耗尽、库存满、设备缺失或道路堵塞会暂停。交入后备亲代可补充退役工位，损坏托盘用四个铁锭修复。纸张／馒头／活体袋分别为 1／2／4 绿宝石。','Parent groups rotate. Trays carry individuals through receiving, nursery, grading, cleaning and packing with staff present. Choose product use and matching stock. Missing materials/stations, full storage and blocked routes pause work. Deliver reserve adults for vacant parent stations; repair broken trays with four iron. Paper/manju/live bags cost 1/2/4 emeralds.'),
    }
    result={'human.mcyukkuri.'+key:pair[0 if chinese else 1] for key,pair in pairs.items()}
    for i,(cn,en) in enumerate([('等待转运','Awaiting transfer'),('接收检查','Receiving'),('送往育成','To nursery'),('育成','Nursery'),('出货分选','Grading'),('清洁处理','Cleaning'),('封装','Packing'),('送往观察','To observation'),('观察','Observation'),('亲代入位','Parent transfer')]):
        result['human.mcyukkuri.pipeline_stage.'+str(i)]=cn if chinese else en
    for id,cn,en in [('factory_receiving_table','子油接收检查台','Birth Receiving Table'),('factory_inspection_table','活体分选台','Live Grading Table'),('factory_washing_table','活体清洁台','Live Washing Table'),('factory_observation_station','异常观察台','Observation Station'),('factory_transfer_tray','工厂周转托盘','Factory Transfer Tray')]:
        for prefix in ('item.','block.'):result[prefix+'mcyukkuri.'+id]=cn if chinese else en
    result['entity.mcyukkuri.factory_tray']='自动周转托盘' if chinese else 'Automatic Transfer Tray'
    return result
