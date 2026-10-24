"""Read the live project's grading constants for deterministic authoring examples."""
import math
import re


def read_rules(root):
    body=root/'src/main/java/io/github/longshou/mcyukkuri/body'
    injury=(body/'InjuryModel.java').read_text(encoding='utf-8')
    life=(body/'BodyLife.java').read_text(encoding='utf-8')
    signs=(body/'BodySigns.java').read_text(encoding='utf-8')
    divisor=float(re.search(r'Math\.ceil\(amount / ([\d.]+)\)',injury)[1])
    threshold=float(re.search(r'WEAK_HEALTH_RATIO\s*=\s*([\d.]+)f',life)[1])
    # Fail if upstream changes the control flow that these examples document.
    compact=re.sub(r'\s+','',life)
    if 'Math.min(3,Math.max(old.severity(),severity)+(loss>=2?1:0))' not in compact:
        raise ValueError('Re-audit repeated-hit grading before exporting examples')
    if 'healthFraction > BodyLife.WEAK_HEALTH_RATIO' not in signs or '!broken && healthFraction' in signs or 'wound.shape().supportsCore()' not in signs:
        raise ValueError('Re-audit current core visibility before exporting examples')
    return dict(grade_divisor=divisor,core_health_ratio=threshold,
        grade_rule='clamp(ceil(accepted health loss / divisor), 1, 3); zero accepted loss creates no wound',
        repeated_hit_rule='same kind and body part: min(3,max(old grade,hit grade)+(accepted loss>=2 ? 1 : 0))',
        capacity_rule='At eight wounds, current insertWound strengthens the nearest existing wound by one; it does not create a ninth.',
        hot_tool_exception='Current hot heated_awl forces at least grade 2; the removed local-burn art path is not exported.',
        core_rule='A supported grade-three mechanical opening, current health/max health <= threshold, and a fitted path to the actual central filling; never an independent random variant.')


def grade_for(loss,rules):
    return 0 if loss<=0 else min(3,max(1,math.ceil(loss/rules['grade_divisor'])))


def sample_damage(grade,core,rules):
    loss={1:2,2:4,3:7 if core else 5}[grade]
    if grade_for(loss,rules)!=grade:raise ValueError('Damage example no longer matches live grade rule')
    return dict(maxHealth=10,healthBefore=10,acceptedLoss=loss,healthAfter=10-loss,
                grade=grade,previousWoundGrade=0,fittedOpening=bool(core),
                coreHealthThreshold=rules['core_health_ratio'],
                label=f'实际扣 {loss} 点，生命 10 → {10-loss}/10；'+('满足露核条件' if core else '不露中枢'))


def examples(rules):
    rows=[]
    for loss in [0,1,2,3,4,5,6,7]:
        grade=grade_for(loss,rules)
        rows.append(dict(acceptedLoss=loss,healthBefore=10,healthAfter=10-loss,grade=grade,
                         exposed=grade==3 and (10-loss)/10<=rules['core_health_ratio']))
    history=[];health=10;old=0
    for loss in [2,2,2,2]:
        grade=grade_for(loss,rules);old=grade if old==0 else min(3,max(old,grade)+(1 if loss>=2 else 0));health-=loss
        history.append(dict(acceptedLoss=loss,healthAfter=health,grade=old,exposed=old==3 and health/10<=rules['core_health_ratio']))
    return dict(rules=rules,basis='10-health example with supported blade/blunt/bite/blast contact, no armor assumptions, no terminal destruction or core damage; actual fitted opening required.',single_hits=rows,repeated_same_site=history)
