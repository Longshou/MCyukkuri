package io.github.longshou.mcyukkuri.social;

import io.github.longshou.mcyukkuri.entity.YukkuriAction;
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
        return YukkuriAction.byId(Moments.clip(Moments.select("reactions", event, "fallback_idle"))).orElseThrow();
    }
    private static String condition(String key) {
        return Moments.face(Moments.select("conditions", key, ""), 0, "");
    }
    public static String expression(YukkuriEntity e, String fallback) {
        if (!e.isPassenger() && e.isInWaterOrBubble()) return condition("immersed");
        if (e.life().isSleeping() || e.family().awaitingLanding()) return condition("sleeping");
        if (e.isPanicking() || e.hurtTime > 0) return condition("startled");
        if ((e.woundComfort().receiving() || e.woundComfort().soothed()) && e.body().condition().hasSkinOrBurnDamage())
            return condition(e.woundComfort().inContact() ? "soothed" : "discomfort");
        if (e.body().condition().needsPainExpression() || e.life().spicyTicks() > 0) return condition("hurt");
        if (e.life().constipated() && e.life().bladder() >= 70) return condition("discomfort");
        String id = moment(e.getBehaviorId());
        var row = Moments.row(id);
        String expression = Moments.face(id, (e.level().getGameTime()-e.getBehaviorStartTick())/20f, "");
        if (row.has("face_condition")) {
            boolean active = switch (row.get("face_condition").getAsString()) {
                case "moving" -> e.getDeltaMovement().horizontalDistanceSqr() > .0001;
                case "urgent_toilet" -> e.life().bladder() >= 90;
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
