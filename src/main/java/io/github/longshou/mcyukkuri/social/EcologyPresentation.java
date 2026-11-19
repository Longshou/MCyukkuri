package io.github.longshou.mcyukkuri.social;

import io.github.longshou.mcyukkuri.entity.YukkuriAction;
import io.github.longshou.mcyukkuri.life.NeedWarnings;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.entity.presentation.Moments;

/** Physical conditions keep priority; authored clips and faces live in the moment table. */
public final class EcologyPresentation {
    public static String moment(String behavior) {
        return Moments.select("behaviors", behavior, "fallback_idle");
    }
    public static int duration(String behavior) { return Moments.ticks(moment(behavior)); }
    public static YukkuriAction action(String behavior) {
        return YukkuriAction.byId(Moments.clip(moment(behavior))).orElseThrow();
    }
    public static YukkuriAction group(String activity) {
        return YukkuriAction.byId(Moments.clip(Moments.select("groups", activity, "fallback_group"))).orElseThrow();
    }
    public static YukkuriAction reaction(String event) {
        if (event.equals("puff")) return YukkuriAction.PUFF;
        return YukkuriAction.byId(Moments.clip(Moments.select("reactions", event, "fallback_idle"))).orElseThrow();
    }
    private static String condition(String key) {
        return Moments.face(Moments.select("conditions", key, ""), 0, "");
    }
    public static void tell(YukkuriEntity entity, NeedWarnings needs) {
        var need = needs.leading();
        double intensity = NeedWarnings.insistence(entity.life().personality().temperament());
        YukkuriAction action = switch (need) {
            case URINE -> YukkuriAction.NERVOUS;
            case STOOL -> YukkuriAction.BRACE;
            case THIRST -> YukkuriAction.IDLE;
            case SLEEP -> YukkuriAction.DOZE_OFF;
            case HUNGER -> YukkuriAction.DROOP;
            case COLD -> YukkuriAction.COLD_SHIVER;
            case OVERHEATING -> YukkuriAction.WATER_GASP;
            case BREEDING -> throw new IllegalStateException("Heat has its own courtship presentation");
        };
        String face = switch (need) {
            case URINE -> "pursed";
            case STOOL -> "chewing_closed";
            case THIRST -> "licking";
            case SLEEP -> "yawn";
            case HUNGER -> intensity < Temperament.LOW35 ? "look_down" : "drool";
            case COLD -> "teary";
            case OVERHEATING -> "teary_pant";
            case BREEDING -> throw new IllegalStateException();
        };
        int duration = 12 + (int) Math.round(18 * intensity);
        entity.showNeedTell(action, face, duration);
        if (need == NeedWarnings.Need.HUNGER && entity.family().adult() && intensity >= Temperament.LOW35)
            entity.speakCue("hungry");
        GameplayLog.limited(entity, "needs", "tell_" + need, 600,
                "tell need={} mask={} face={} ticks={}", need, needs.mask(), face, duration);
    }

    public static String expression(YukkuriEntity e, String fallback) {
        if (!e.isPassenger() && e.isInWaterOrBubble()) return condition("immersed");
        if (e.life().isSleeping() || e.family().awaitingLanding()) return condition("sleeping");
        if (e.isPanicking() || e.hurtTime > 0) return condition("startled");
        if ((e.woundComfort().receiving() || e.woundComfort().soothed()) && e.body().condition().hasSkinOrBurnDamage())
            return condition(e.woundComfort().inContact() ? "soothed" : "discomfort");
        if (e.body().condition().needsPainExpression() || e.life().spicyTicks() > 0) return condition("hurt");
        if (e.life().constipated() && e.life().bowel() >= 70) return condition("discomfort");
        String id = moment(e.getBehaviorId());
        var row = Moments.row(id);
        long start = id.equals(e.getMomentId()) ? e.getAnimationStartTick() : e.getBehaviorStartTick();
        String expression = Moments.face(id, (e.level().getGameTime()-start)/20f, "");
        if (row.has("face_condition")) {
            boolean active = switch (row.get("face_condition").getAsString()) {
                case "moving" -> e.getDeltaMovement().horizontalDistanceSqr() > .0001;
                case "urgent_toilet" -> e.life().toiletPressure() >= 90;
                default -> throw new IllegalArgumentException("Unknown face condition");
            };
            if (active) expression = row.get("condition_face").getAsString();
        }
        if (expression.isEmpty()) expression = e.contactPose().equals("RUB") ? condition("rub")
                : e.life().nutrition() < 30 ? condition("hungry") : e.life().energy() < 25 ? condition("tired")
                : e.dailyLife().affectionNeed() >= 80 && fallback.equals("neutral") ? condition("lonely") : fallback;
        return e.profile().dangerToYukkuri() ? Moments.predatorFace(expression) : expression;
    }
    private EcologyPresentation() {}
}
