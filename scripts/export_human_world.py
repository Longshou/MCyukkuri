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
    return json.loads((ROOT/'art/structures/human-world/language.json').read_text(encoding='utf-8'))['zh_cn' if chinese else 'en_us']
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
        actual=json.loads((ROOT/'src/main/resources/assets/mcyukkuri/lang/ja_jp.json').read_text(encoding='utf-8'))
        errors.extend('ja_jp:'+k for k,v in load('art/structures/human-world/author_exam_props.py','exam_props_language').language('ja_jp').items() if actual.get(k)!=v)
        checks=load('art/structures/human-world/check_structure.py','shop_structure_check')
        errors.extend('shop structure: '+e for e in checks.check_shop()+checks.check_street())
        if errors:raise SystemExit('Human-world assets differ: '+', '.join(errors))
        print(f"Human-world sources and exports match ({len(record['files'])} files).")
        return
    load('art/structures/human-world/build.py','build_human_structures').export()
    load('standards/yukkuri-library/tools/author_human_npcs.py','build_human_textures').export()
    load('art/structures/human-world/author_products.py','build_human_products').export()
    load('art/structures/human-world/author_pipeline.py','build_factory_pipeline').export()
    exam=load('art/structures/human-world/author_exam_props.py','build_exam_props'); exam.export()
    checks=load('art/structures/human-world/check_structure.py','shop_structure_check')
    problems=checks.check_shop()+checks.check_street()
    if problems: raise SystemExit('Shop structure checks failed: '+'; '.join(problems))
    p=ROOT/'src/main/resources/assets/mcyukkuri/lang/ja_jp.json';data=json.loads(p.read_text(encoding='utf-8'));data.update(exam.language('ja_jp'))
    p.write_text(json.dumps(data,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    for lang in ['zh_cn','en_us']:
        p=ROOT/f'src/main/resources/assets/mcyukkuri/lang/{lang}.json';data=json.loads(p.read_text(encoding='utf-8'));data.update(language(lang=='zh_cn'))
        p.write_text(json.dumps(data,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    for id in ['oniisan','caretaker_brother','caretaker_sister']:
        p=ROOT/f'src/main/resources/assets/mcyukkuri/models/item/{id}_spawn_egg.json';p.write_text('{"parent":"minecraft:item/template_spawn_egg"}\n',encoding='utf-8')
    patterns=['src/main/resources/data/mcyukkuri/structure/yukkuri_*.nbt','src/main/resources/data/mcyukkuri/worldgen/*/yukkuri_*.json',
              'src/main/resources/data/mcyukkuri/structure/village/*.nbt','src/main/resources/data/mcyukkuri/worldgen/template_pool/village/**/*.json',
              'src/main/resources/assets/mcyukkuri/textures/entity/human/*.png','standards/yukkuri-library/textures/human/*.png',
              'standards/yukkuri-library/assembled/humans/textures/*.png','art/source/human-runtime/textures/*.png','art/structures/human-world/products/*',
              'src/main/resources/assets/mcyukkuri/textures/block/exam_*.png','src/main/resources/assets/mcyukkuri/models/block/exam_*.json','src/main/resources/assets/mcyukkuri/models/item/exam_*.json',
              'src/main/resources/assets/mcyukkuri/blockstates/exam_*.json','src/main/resources/data/mcyukkuri/loot_table/blocks/exam_*.json']
    paths=[ROOT/'art/structures/human-world/build.py',ROOT/'standards/yukkuri-library/tools/author_human_npcs.py',ROOT/'standards/yukkuri-library/tools/author_staff_skins.py',ROOT/'src/main/java/io/github/longshou/mcyukkuri/human/HumanBuildingStructure.java',
           ROOT/'src/main/java/io/github/longshou/mcyukkuri/client/HumanRenderer.java',ROOT/'scripts/export_human_world.py']
    paths.append(ROOT/'art/structures/human-world/author_products.py')
    paths.append(ROOT/'art/props/badges/author_badges.py')
    paths.append(ROOT/'art/structures/human-world/author_pipeline.py')
    paths.append(ROOT/'art/structures/human-world/language.json')
    paths.append(ROOT/'art/structures/human-world/pipeline_language.py')
    paths.extend([ROOT/'art/structures/human-world/author_exam_props.py',ROOT/'art/structures/human-world/check_structure.py',ROOT/'art/structures/human-world/author_street.py'])
    paths.extend(ROOT.glob('src/main/java/io/github/longshou/mcyukkuri/human/Exam*Block.java'))
    paths.append(ROOT/'src/main/java/io/github/longshou/mcyukkuri/client/FactoryTrayRenderer.java')
    paths.append(ROOT/'src/main/resources/assets/mcyukkuri/textures/block/workshop_casing.png')
    paths.append(ROOT/'src/main/java/io/github/longshou/mcyukkuri/human/FactoryStationBlock.java')
    for name in ['factory_receiving_table','factory_inspection_table','factory_washing_table','factory_observation_station','factory_transfer_tray','yukkuri_manju','live_yukkuri_bag','silver_yukkuri_badge','gold_yukkuri_badge','platinum_yukkuri_badge','labor_bench','manju_steamer','live_packing_table','nursery_station']:
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
    print('Human world: village shop, factory templates, NPC outfits and original products exported.')
if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--check',action='store_true');main(parser.parse_args().check)
