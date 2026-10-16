"""Read-only action catalogue shared by the museum map generator."""
from pathlib import Path
import json
import re

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / 'src/main/resources/assets/mcyukkuri'
ACTION_SOURCE = ROOT / 'src/main/java/io/github/longshou/mcyukkuri/entity/YukkuriAction.java'
ACTION_META = {a: (float(d), loop == 'true') for a, d, loop in re.findall(
    r'^\s+[A-Z_]+\("([a-z_]+)", ([\d.]+), (true|false)\)', ACTION_SOURCE.read_text(), re.M)}
CATEGORIES = [
    ('身体与移动', [('idle','待机'),('stretch_shape','伸缩身体'),('side_bend','左右侧摆'),
        ('waddle','行走'),('brace','撑起身体'),('turn','转身'),('hop','跳跃'),('stretch','伸展'),
        ('exercise_vertical','纵向体操'),('exercise_sides','横向体操'),('press','挤压'),('taiso','广播体操')]),
    ('日常生活', [('eat','进食'),('shiawase','饭后幸福'),('drink','饮水'),('sleep','睡觉'),('wake','醒来'),('shake','甩水')]),
    ('交流与情绪', [('nuzzle','蹭蹭'),('sing','唱歌'),('sway','摇摆'),('greet','打招呼'),('look_around','张望'),
        ('puff','鼓腮'),('push','推挤'),('startle','受惊'),('tremble','发抖'),('droop','低落'),('flee','逃跑')]),
    ('家庭与照料', [('brood','安胎与照看胎儿'),('labor','分娩'),('midwife','接生'),('bereaved','失去幼体后的悲伤'),
        ('postpartum','产后休息'),('first_wake','初生醒来'),('hat_cradle','幼油在帽托中休息')]),
    ('帽子与水边', [('hat_off','接生摘帽'),('hat_hold','扶帽接生'),('hat_on','接生后戴回帽子'),
        ('hat_off_self','独自摘帽'),('hat_on_self','独自戴回帽子'),('hat_off_aq','水生接生摘帽'),
        ('hat_hold_aq','水生扶帽接生'),('hat_on_aq','水生接生后戴帽'),('hat_off_self_aq','水生独自摘帽'),
        ('hat_on_self_aq','水生独自戴帽'),('boat_drift','帽船漂流'),('boat_steady','帽船保持平衡'),
        ('boat_call','船上呼喊'),('boat_brood','船上安胎'),('boat_labor','船上分娩')]),
    ('特殊身体', [('shell_retract','缩壳'),('anatomy_mamu_extend','产道伸出'),('anatomy_mamu_retract','产道收回'),
        ('anatomy_peni_extend','生殖部伸出'),('anatomy_peni_retract','生殖部收回')]),
]
NAMES = dict(pair for _, entries in CATEGORIES for pair in entries)
assert set(NAMES) == set(ACTION_META), 'Update the catalogue when registered actions change'
MODELS = {
    'reimu':'灵梦','marisa':'魔理沙','chen':'橙','alice':'爱丽丝','patchouli':'帕秋莉',
    'house_reimu':'家油灵梦','house_marisa':'家油魔理沙','remilia':'蕾米莉亚','flandre':'弗兰',
    'marisa_tsumuri':'壳种魔理沙','reimu_wasa':'多毛灵梦','reimu_deibu':'灵胖','reimu_wasa_deibu':'多毛灵胖',
    'reimu_sparse':'稀毛灵梦','marisa_sparse':'稀毛魔理沙','reimu_face':'歪脸灵梦',
    'marisa_face':'歪脸魔理沙','reimu_bean':'豆形灵梦','marisa_bean':'豆形魔理沙',
}

def load_clips():
    revision=json.loads((ASSETS/'yukkuri_presentation/motion_corrections.json').read_text(encoding='utf-8'))
    clips={};internal={}
    for model in MODELS:
        data=json.loads((ASSETS/f'yukkuri/{model}.json').read_text(encoding='utf-8'))
        correction=revision['models'].get(model,{})
        if correction:assert correction['bone_names']==[b['name'] for b in data['bones']],model
        clips[model]=set(data['clips'])|set(correction.get('clips',{}))
        internal[model]=(clips[model]-set(ACTION_META))|set(data.get('behavior_clips',{}))
    return clips,internal

