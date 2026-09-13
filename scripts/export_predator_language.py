"""Merge only predator-owned language keys and natural-spawn data."""
import json
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
ASSETS=ROOT/'src/main/resources/assets/mcyukkuri'
for lang in ('zh_cn','en_us','ja_jp'):
    zh=lang=='zh_cn';ja=lang=='ja_jp'
    keys={}
    for ident,cn,en,jp in [('remilia','蕾米莉亚','Remilia','れみりゃ'),('flandre','弗兰','Flandre','ふらん')]:
        name=cn if zh else jp if ja else en
        keys['entity.mcyukkuri.'+ident]=name
        keys['item.mcyukkuri.'+ident+'_spawn_egg']=name+('生成蛋' if zh else 'のスポーンエッグ' if ja else ' Spawn Egg')
        keys['item.mcyukkuri.'+ident+'_bonnet']=name+('软帽' if zh else 'の帽子' if ja else ' Bonnet')
        (ASSETS/f'models/item/{ident}_spawn_egg.json').write_text('{"parent":"minecraft:item/template_spawn_egg"}\n')
    keys['block.mcyukkuri.predator_nest']=keys['item.mcyukkuri.predator_nest']='捕食种枝巢' if zh else '捕食種の枝巣' if ja else 'Predator Twig Nest'
    for ident,cn,en,jp in [('predator_takeoff','起飞追猎','Taking off','離陸'),('predator_chase','追猎','Pursuing prey','追跡'),('predator_dive','俯冲咬取','Diving','急降下'),('predator_feed','食用馅料','Eating filling','餡を食べる'),('predator_return','降落归巢','Returning to nest','帰巣'),('predator_fly','短途飞行','Flying','飛行')]:
        keys['behavior.mcyukkuri.'+ident]=cn if zh else jp if ja else en
    for i,cn,en,jp in [(11,'停止捕猎','Stop hunting','狩りを止める'),(12,'允许捕猎','Allow hunting','狩りを許す'),(13,'返回巢穴','Return to nest','巣に戻る')]:
        keys['badge.mcyukkuri.action.'+str(i)]=cn if zh else jp if ja else en
        keys['badge.mcyukkuri.help.'+str(i)]='仅主人可指挥有章、教育达标的捕食种；归巢需要已登记巢穴。' if zh else 'バッジと教育が必要。帰巣には登録した巣が必要。' if ja else 'Owner command; requires a badge and sufficient education. Returning needs a registered nest.'
    keys['predator.mcyukkuri.control_state']='捕食指令：%s · %s' if zh else '捕食指示：%s · %s' if ja else 'Hunting order: %s / %s'
    for i,cn,en,jp in [(0,'遵循个性','Natural','性格に従う'),(1,'停止捕猎','Peaceful','狩り停止'),(2,'允许捕猎','Hunting allowed','狩り許可'),(3,'返回巢穴','Return to nest','帰巣')]:keys['predator.mcyukkuri.order.'+str(i)]=cn if zh else jp if ja else en
    keys['predator.mcyukkuri.obedient.0']='教育不足，保留野性' if zh else '教育不足' if ja else 'Insufficient education'
    keys['predator.mcyukkuri.obedient.1']='可听从主人' if zh else '飼い主の指示に従う' if ja else 'Accepts owner commands'
    p=ASSETS/f'lang/{lang}.json';d=json.loads(p.read_text(encoding='utf-8'));d.update(keys);p.write_text(json.dumps(d,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
DATA=ROOT/'src/main/resources/data/mcyukkuri'
p=DATA/'tags/worldgen/biome/predator_habitat.json';p.parent.mkdir(parents=True,exist_ok=True);p.write_text(json.dumps({'replace':False,'values':['minecraft:forest','minecraft:dark_forest','minecraft:taiga']})+'\n')
p=DATA/'neoforge/biome_modifier/predator_spawns.json';p.write_text(json.dumps({'type':'neoforge:add_spawns','biomes':'#mcyukkuri:predator_habitat','spawners':[{'type':'mcyukkuri:'+i,'weight':1,'minCount':1,'maxCount':1} for i in ('remilia','flandre')]})+'\n')
print('Predator language, spawn eggs and rare woodland spawns exported.')
