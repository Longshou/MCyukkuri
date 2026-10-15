"""Build original human-world assets and merge only this feature's language keys."""
from pathlib import Path
import importlib.util, json, hashlib, argparse
from runtime_model_fingerprint import matches_model_fingerprint

ROOT=Path(__file__).resolve().parents[1]
TEXT_FINGERPRINT_SUFFIXES={'.java','.json','.py'}
def matches_fingerprint(path,data,digest):
    return (hashlib.sha256(data).hexdigest()==digest or
            (path.suffix in TEXT_FINGERPRINT_SUFFIXES and
             matches_model_fingerprint(data,digest)))
def load(path,name):
    spec=importlib.util.spec_from_file_location(name,ROOT/path);m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m);return m
def language(chinese):
    names=['鬼威惨','爱派大哥哥','爱派大姐姐'] if chinese else ['Oniisan','Caretaker Brother','Caretaker Sister']
    keys={}
    for id,name in zip(['oniisan','caretaker_brother','caretaker_sister'],names):
        keys['entity.mcyukkuri.'+id]=name; keys['item.mcyukkuri.'+id+'_spawn_egg']=name+('生成蛋' if chinese else ' Spawn Egg')
    zh={'owned':'这只已有主人或尚未售出。请先向店员购买。','busy':'正在接待另一位客人，请稍等。','success':'已完成。','unavailable':'暂时无法办理：请检查营业时间、额度、物品、绿宝石、背包空位及交付空间。',
        'quest_location':'走失线索：%s, %s, %s；身份尾签 %s。用运输箱带回原来的个体。',
        'buy_supply':'%s ×%s · %s 绿宝石','buy_animal':'%s · %s 绿宝石','empty':'暂无可售个体','animal_state':'生命 %s；饱食 %s；%s；教育 %s/3。购买包含运输箱并保留原个体身份。',
        'stage.0':'赤体','stage.1':'子体','stage.2':'成体','status':'本日交易余量 %s · 照顾用粮 %s','supplies':'生活用品','animals':'本店活体（悬停看状态）',
        'action.8':'交付加工 · +3 绿宝石','action.9':'领取走失线索','action.10':'归还原个体 · +5 绿宝石','action.11':'补给照顾用粮 ×8','action.12':'询问与参观说明',
        'guide.resident':'用普通油粮补给照顾用品。有走失委托时先领取线索，再用运输箱带回指定个体；同种替代不会结算。夜间休息，白天办理交易。',
        'guide.shopkeeper':'展示的是实际个体；价格包含运输箱，保留原有成长与关系。购买后右键地面放出。用品按每日限额供货，活体不会凭空补回。',
        'guide.factory':'玻璃廊道通向育成与加工区。手持装有自己个体的运输箱交货，每笔 3 绿宝石，每日最多 4 笔、同时最多 2 只。交付后职员会加工该个体。'}
    en={'owned':'This yukkuri is owned or has not been purchased. Talk to the shopkeeper.','busy':'Serving another customer. Please wait.','success':'Done.','unavailable':'Unavailable: check opening hours, stock, items, emeralds, a free inventory slot and delivery space.',
        'quest_location':'Last seen: %s, %s, %s; identity tag %s. Return that individual in a carrier.',
        'buy_supply':'%s x%s / %s E','buy_animal':'%s / %s E','empty':'No animal for sale','animal_state':'Health %s; food %s; %s; lessons %s/3. Includes a carrier, preserving identity.',
        'stage.0':'Baby','stage.1':'Juvenile','stage.2':'Adult','status':'Trades left %s / Care food %s','supplies':'Care supplies','animals':'Live stock (hover for details)',
        'action.8':'Deliver for processing / +3 E','action.9':'Missing companion clue','action.10':'Return companion / +5 E','action.11':'Donate 8 regular feed','action.12':'Advice and tour information',
        'guide.resident':'Donate regular feed for care. Accept a missing companion clue, then return that same individual in a carrier. Trade during daylight.',
        'guide.shopkeeper':'These are actual animals. A purchase includes a carrier and preserves growth and relationships. Use on the ground to release. Supply trades have daily limits; animals are not regenerated.',
        'guide.factory':'The gallery overlooks rearing and processing. Hold your filled carrier to deliver its occupant for 3 emeralds. Limit: four per day and two pending animals. Staff will process the delivered individual.'}
    zh.update({'page':'用品 %s/5 →','action.14':'购买成品','buy_product':'购成品 · %s 绿宝石','action.15':'交入原料 ×8','worker_away':'工人不在附近，请到生产线旁找工人办理。',
        'production':'成品库存 %s；生产原料 %s。每次购买队首成品一份。','ingredient.0':'此人没有生产线。','ingredient.1':'手持至少 8 根甘蔗，补充劳力工场原料。',
        'ingredient.2':'手持至少 8 份小麦，补充馒头面皮原料。','ingredient.3':'手持至少 8 张纸，补充包装材料。',
        'medal.0':'','medal.1':'银章·','medal.2':'金章·','bag_description':'检视、清洁后封装的活体。右键地面拆袋，保留原个体身份。捕食种用途尚待该种实装。',
        'guide.factory':'劳力工场：油库里消耗甘蔗劳动制纸，不发工资。馒头厂：小麦与个体的实际馅料制成馒头。袋装厂：检查、清洁后将同一活体封装。后两类设亲代与子油育成区，沿用成长与数量限制；需要油粮补给。成品每份 2 绿宝石，原料耗尽或库存满即停产。旧加工所保留原来的加工方式。',
        'guide.shopkeeper':'四个展舍售卖普通、银章和金章宠物，五页用品共 20 种。金银章是店内教育分级，金章保留识别无饰物个体的能力，不保证性格完美。购买保留同一只个体及徽章。稀有种尚未实装，展示位不售货。'})
    en.update({'page':'Supplies %s/5 >','action.14':'Buy product','buy_product':'Buy product / %s E','action.15':'Deposit 8 materials','worker_away':'No worker nearby. Talk to the worker beside the production line.',
        'production':'Products %s; material %s. Buys the first queued product.','ingredient.0':'No production line.','ingredient.1':'Hold at least 8 sugar cane.',
        'ingredient.2':'Hold at least 8 wheat.','ingredient.3':'Hold at least 8 paper.','medal.0':'','medal.1':'Silver ','medal.2':'Gold ',
        'bag_description':'Inspected and cleaned live occupant. Use on ground to open once, preserving identity. Predator use awaits predator implementation.',
        'guide.factory':'Labor works use sugar cane and unpaid yukkuri labor to make paper. Manju works consume wheat and actual filling. Bag works inspect and clean a live individual, then seal that individual. Both processing works have parent and nursery rooms using normal growth and population limits. Feed donations sustain care. Products cost 2 emeralds. Full storage or exhausted materials pauses production. Legacy works retain their former processing.',
        'guide.shopkeeper':'Four actual pets: ordinary, silver and gold grades. Five pages offer 20 supplies. Badges represent shop education, not guaranteed perfect temperament; gold keeps accessory-independent recognition. Purchases preserve the individual and its badge. Rare species are not implemented or offered for sale.'})
    for k,v in (zh if chinese else en).items():keys['human.mcyukkuri.'+k]=v
    keys['human.mcyukkuri.guide.factory']=keys['human.mcyukkuri.guide.factory'].replace('成品每份 2 绿宝石','纸张／馒头／活体袋分别为 1／2／4 绿宝石').replace('Products cost 2 emeralds.','Paper, manju and live bags cost 1, 2 and 4 emeralds respectively.')
    for id,cn,en_name in [('yukkuri_manju','油库里馒头','Yukkuri Manju'),('live_yukkuri_bag','袋装活体油库里','Packaged Live Yukkuri'),('silver_yukkuri_badge','油库里银章','Silver Yukkuri Badge'),('gold_yukkuri_badge','油库里金章','Gold Yukkuri Badge'),('labor_bench','油库里劳动作业台','Yukkuri Labor Bench'),('manju_steamer','油库里馒头蒸制机','Yukkuri Manju Steamer'),('live_packing_table','油库里活体封装台','Live Yukkuri Packing Table'),('nursery_station','子油育成护理台','Yukkuri Nursery Station')]:
        keys['item.mcyukkuri.'+id]=cn if chinese else en_name;keys['block.mcyukkuri.'+id]=cn if chinese else en_name
    for i in range(13):
        if i<4: v='白天购买用品，需足够绿宝石及一个背包空位。' if chinese else 'Daylight supply trade; requires emeralds and a free inventory slot.'
        elif i<8: v='购买当前列出的原个体，价格包含一个运输箱。' if chinese else 'Buy this individual, including one carrier.'
        elif i==8:v='手持你自己的装有个体的运输箱。该个体将用于加工，不可当作寄养。' if chinese else 'Hold your filled carrier. Its occupant will be processed.'
        elif i==9:v='领取或重新查看指定个体的最后位置。' if chinese else 'Accept or reread the exact companion’s last position.'
        elif i==10:v='先领取委托，再手持装有原个体的运输箱交还。每只只结算一次。' if chinese else 'Accept the quest, then hold that individual’s carrier. Paid once only.'
        elif i==11:v='消耗 8 份普通油粮，补充 NPC 的有限照顾用品。' if chinese else 'Consume 8 regular feed to replenish this NPC’s care food.'
        else:v='了解店铺、工厂或照顾委托的实际规则。' if chinese else 'Ask about this resident’s services.'
        keys['human.mcyukkuri.help.'+str(i)]=v
    keys['human.mcyukkuri.help.13']='翻到下一页生活用品。' if chinese else 'Browse the next supply page.'
    keys['human.mcyukkuri.help.14']='购买生产库存中的实际成品。' if chinese else 'Buy an actual queued product.'
    keys['human.mcyukkuri.help.15']='手持原料补给该工厂。' if chinese else 'Hold matching materials to replenish this factory.'
    keys['human.mcyukkuri.action.21']='活体翻页' if chinese else 'Next animals'
    keys['human.mcyukkuri.help.21']='查看下一页实际可售个体，包括捕食种。' if chinese else 'Browse the next page of actual stock, including predators.'
    keys['human.mcyukkuri.animals_page']='本店活体 · %s/%s 页' if chinese else 'Animals / page %s of %s'
    keys.update(load('art/structures/human-world/pipeline_language.py','factory_pipeline_language').language(chinese))
    return keys
def main(check=False):
    manifest=ROOT/'art/structures/human-world/exports.json'
    if check:
        record=json.loads(manifest.read_text(encoding='utf-8'));errors=[]
        for path,digest in record['files'].items():
            p=ROOT/path
            if not p.is_file() or not matches_fingerprint(p,p.read_bytes(),digest):errors.append(path)
        for lang in ['zh_cn','en_us']:
            actual=json.loads((ROOT/f'src/main/resources/assets/mcyukkuri/lang/{lang}.json').read_text(encoding='utf-8'))
            errors.extend(lang+':'+k for k,v in language(lang=='zh_cn').items() if actual.get(k)!=v)
        if errors:raise SystemExit('Human-world assets differ: '+', '.join(errors))
        print(f"Human-world sources and exports match ({len(record['files'])} files).")
        return
    load('art/structures/human-world/build.py','build_human_structures').export()
    load('standards/yukkuri-library/tools/author_human_npcs.py','build_human_textures').export()
    load('art/structures/human-world/author_products.py','build_human_products').export()
    load('art/structures/human-world/author_pipeline.py','build_factory_pipeline').export()
    for lang in ['zh_cn','en_us']:
        p=ROOT/f'src/main/resources/assets/mcyukkuri/lang/{lang}.json';data=json.loads(p.read_text(encoding='utf-8'));data.update(language(lang=='zh_cn'))
        p.write_text(json.dumps(data,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    for id in ['oniisan','caretaker_brother','caretaker_sister']:
        p=ROOT/f'src/main/resources/assets/mcyukkuri/models/item/{id}_spawn_egg.json';p.write_text('{"parent":"minecraft:item/template_spawn_egg"}\n',encoding='utf-8')
    patterns=['src/main/resources/data/mcyukkuri/structure/yukkuri_*.nbt','src/main/resources/data/mcyukkuri/worldgen/*/yukkuri_*.json',
              'src/main/resources/assets/mcyukkuri/textures/entity/human/*.png','standards/yukkuri-library/textures/human/*.png',
              'standards/yukkuri-library/assembled/humans/textures/*.png','art/source/human-runtime/textures/*.png','art/structures/human-world/products/*']
    paths=[ROOT/'art/structures/human-world/build.py',ROOT/'standards/yukkuri-library/tools/author_human_npcs.py',ROOT/'src/main/java/io/github/longshou/mcyukkuri/human/HumanBuildingStructure.java',
           ROOT/'src/main/java/io/github/longshou/mcyukkuri/client/HumanRenderer.java',ROOT/'scripts/export_human_world.py']
    paths.append(ROOT/'art/structures/human-world/author_products.py')
    paths.append(ROOT/'art/structures/human-world/author_pipeline.py')
    paths.append(ROOT/'art/structures/human-world/pipeline_language.py')
    paths.append(ROOT/'src/main/java/io/github/longshou/mcyukkuri/client/FactoryTrayRenderer.java')
    paths.append(ROOT/'src/main/resources/assets/mcyukkuri/textures/block/workshop_casing.png')
    paths.append(ROOT/'src/main/java/io/github/longshou/mcyukkuri/human/FactoryStationBlock.java')
    for name in ['factory_receiving_table','factory_inspection_table','factory_washing_table','factory_observation_station','factory_transfer_tray','yukkuri_manju','live_yukkuri_bag','silver_yukkuri_badge','gold_yukkuri_badge','labor_bench','manju_steamer','live_packing_table','nursery_station']:
        for folder,extension in [('models/item','json'),('models/block','json'),('blockstates','json'),('textures/item','png'),('textures/block','png')]:
            p=ROOT/'src/main/resources/assets/mcyukkuri'/folder/(name+'.'+extension)
            if p.exists():paths.append(p)
    for pat in patterns:paths.extend(ROOT.glob(pat))
    paths.extend([ROOT/'src/main/resources/data/mcyukkuri/tags/worldgen/biome/human_buildings.json',ROOT/'src/main/resources/data/mcyukkuri/neoforge/biome_modifier/human_spawns.json'])
    previous=json.loads(manifest.read_text(encoding='utf-8')) if manifest.is_file() else {'files':{}}
    files={}
    for p in sorted(set(paths)):
        relative=p.relative_to(ROOT).as_posix();content=p.read_bytes()
        recorded=previous['files'].get(relative)
        files[relative]=recorded if recorded and matches_fingerprint(p,content,recorded) else hashlib.sha256(content).hexdigest()
    data={'files':files}
    manifest.write_text(json.dumps(data,indent=2)+'\n',encoding='utf-8')
    print('Human world: shop, three independent factory types, legacy template, NPC outfits and original products exported.')
if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--check',action='store_true');main(parser.parse_args().check)
