package io.github.longshou.mcyukkuri.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import net.minecraft.util.Mth;

/** Small, non-contact adult state cues. The server owns phases, movement and consequences. */
final class AgitationPresentation {
    private AgitationPresentation() {}

    static boolean selected(YukkuriEntity entity) {
        if (!entity.family().adult() || entity.isDeadOrDying() || entity.isPassenger()
                || !entity.contactPose().equals("NONE")) return false;
        String behavior = switch (entity.agitationState().getString("Phase")) {
            case "ONSET" -> "agitation_onset";
            case "RELIEF" -> "agitation_relief";
            case "RECOVER" -> "agitation_recover";
            case "ALICE_WATCH" -> "alice_watch";
            case "ALICE_APPROACH" -> "alice_approach";
            default -> "";
        };
        return !behavior.isEmpty() && behavior.equals(entity.getBehaviorId());
    }

    /** Keep the same face on the texture, facial geometry and wound surface. */
    static String expression(YukkuriEntity entity, String otherwise) {
        if (selected(entity)) return entity.getExpressionId();
        // Aftermath is visible during quiet rest and brooding. Urgent reactions and ordinary
        // eating, sleep, handling and family interactions keep their own expressions.
        String behavior = entity.getBehaviorId(), action = entity.getAnimationId();
        if (entity.aftermathBurden() && (behavior.equals("idle") || behavior.equals("tend_brood"))
                && (action.equals("idle") || action.equals("brood"))
                && !java.util.Set.of("cry", "blank_cry", "surprise").contains(entity.getExpressionId()))
            return entity.aftermathWeak() ? "sleepy" : "teary";
        return otherwise;
    }

    static void pose(YukkuriEntity entity, float partialTick, PoseStack pose) {
        if (!selected(entity)) return;
        float ticks = Math.max(0, entity.level().getGameTime() - entity.agitationState().getLong("Start") + partialTick);
        float settle = Mth.clamp(ticks / 12, 0, 1);
        // Whole-body changes also carry the worn badge and held objects. No root translation:
        // navigation is the only source of approach and retreat in the world.
        switch (entity.getBehaviorId()) {
            case "agitation_onset" -> pose.mulPose(Axis.ZP.rotationDegrees(2 * settle));
            case "agitation_relief" -> {
                float release = (float)Math.sin(Math.PI * Mth.clamp(ticks / 80, 0, 1));
                pose.scale(1 + .015f * release, 1 - .025f * release, 1 + .015f * release);
            }
            case "agitation_recover" -> pose.mulPose(Axis.ZP.rotationDegrees(-2 * (1 - Mth.clamp(ticks / 100, 0, 1))));
            case "alice_watch" -> pose.scale(1 + .02f * settle, 1 - .035f * settle, 1 + .02f * settle);
            default -> { } // Approach uses real locomotion, without an extra cosmetic lunge.
        }
    }
}
