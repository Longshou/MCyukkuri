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
    ('交流与情绪', [('nuzzle','蹭蹭'),('comfort_lick','担心舔舐'),('sing','唱歌'),('sway','摇摆'),('greet','打招呼'),('look_around','张望'),
        ('puff','鼓腮'),('push','推挤'),('startle','受惊'),('tremble','发抖'),('droop','低落'),('flee','逃跑')]),
    ('家庭与照料', [('brood','安胎与照看胎儿'),('labor','分娩'),('midwife','接生'),('bereaved','失去幼体后的悲伤'),
        ('postpartum','产后休息'),('first_wake','初生醒来'),('hat_cradle','幼油在帽托中休息')]),
    ('帽子与水边', [('hat_off','接生摘帽'),('hat_hold','扶帽接生'),('hat_on','接生后戴回帽子'),
        ('hat_off_self','独自摘帽'),('hat_on_self','独自戴回帽子'),('hat_off_aq','水生接生摘帽'),
        ('hat_hold_aq','水生扶帽接生'),('hat_on_aq','水生接生后戴帽'),('hat_off_self_aq','水生独自摘帽'),
        ('hat_on_self_aq','水生独自戴帽'),('boat_drift','帽船漂流'),('boat_steady','帽船保持平衡'),
        ('boat_call','船上呼喊'),('boat_brood','船上安胎'),('boat_labor','船上分娩')]),
    ('宠物店与表情', [('think','思考'),('nervous','紧张'),('proud','得意'),('joy_jump','欢呼跳'),('sulk','闹别扭'),
        ('hide_face','躲猫猫'),('beg','贴地抬眼'),('bow','鞠躬'),('shy','害羞'),('ignore','不理会'),('restrain','忍住'),
        ('snatch_pull','咬住拽走'),('stand_ground','扎稳不退'),('offer_bite','递出一口')]),
    ('运输与篮子', [('doze_off','迷糊瘫软'),('struggle','挣扎抗拒'),('basket_in','钻进篮子'),('basket_out','出篮张望')]),
    ('水上日常与亲子', [('boat_paddle','划桨'),('boat_gather','收集口粮'),('boat_eat','船上进食'),('boat_drink','船上饮水'),
        ('boat_sleep','船上睡觉'),('boat_wake','船上醒来'),('boat_board','登船'),('boat_feed_young','船上递食'),
        ('boat_nuzzle','船上亲昵'),('boat_lullaby','哼唱摇篮曲'),('aquatic_hat_care','返岸理帽'),('boat_cover_young','俯身护住孩子'),
        ('boat_wedge_berth','楔岸夜泊'),('boat_push_off','辫子推岸离岸'),('boat_cargo_stow','把粮放进帽冠袋底'),('boat_cargo_fetch','从帽冠袋底取粮'),
        ('boat_jettison','推出帽檐'),('boat_rim_peek','前台阶探头看水'),('boat_nest_curl','深窝里蜷着睡')]),
    ('初航与帽檐', [('hat_brim','站帽檐'),('boat_first_balance','试坐平衡'),('hat_stumble','帽檐失衡'),('hat_jostle','帽檐拥挤'),
        ('hat_brim_offer','低下近缘帽檐'),('hat_hop_up','蹿上帽檐'),('hat_slide_over','贴缘滑进帽里')]),
    ('落水与救援', [('water_plunge','落水冲击'),('water_struggle','水中扑腾'),('water_gasp','呼救喘息'),('water_recover','获救喘息'),
        ('water_hat_flip','翻正帽子'),('water_hat_grip','抓住帽缘'),('water_hat_climb','咬檐勾辫爬回'),('boat_reassure','安抚'),
        ('boat_alarm_call','惊呼'),('boat_alarm_wait','警戒等待'),('boat_flee','撤离'),('boat_calm','平复'),
        ('boat_answer_call','回应呼唤'),('braid_rescue_reach','伸辫救援'),('braid_rescue_hold','辫托孩子'),('braid_rescue_settle','放回帽中'),
        ('water_bob_lunge','水中蹿身'),('water_mouth_grip','咬住帽缘'),('water_hat_shake','抖落帽子'),('water_hat_push','身体顶帽'),
        ('boat_nudge_young','亲油贴身一顶'),('braid_cradle_lift','辫梢卷兜托起幼油'),('be_cradled','被辫梢兜着'),('mouth_bite_hold','咬住并拖拽'),
        ('boat_thrown','翻船被甩出'),('water_hat_right','咬檐翻正帽子')]),
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

