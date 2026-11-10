package io.github.longshou.mcyukkuri.client;

import io.github.longshou.mcyukkuri.body.BodySigns;
import io.github.longshou.mcyukkuri.entity.YukkuriView;
import io.github.longshou.mcyukkuri.family.FamilyLife;
import java.util.List;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/** Short crosshair status strip built from already-synced values. */
public final class StatusStrip {
    public static List<Component> lines(YukkuriView view) {
        Component header = Component.translatable("magnifier.mcyukkuri.header", view.getDisplayName(), stage(view));

        Component health = Component.translatable("magnifier.mcyukkuri.health",
                Math.round(view.getHealth()), Math.round(view.getMaxHealth()));
        Component identity = Component.translatable(view.identityTranslationKey());
        Component badge = Component.translatable("magnifier.mcyukkuri.badge",
                Component.translatable("badge.mcyukkuri.profile.grade." + view.visibleMedal()));
        int mold = BodySigns.moldTier(view.visibleSigns() & BodySigns.MOLD_MASK);
        Component dirt = mold > 0
                ? Component.translatable("magnifier.mcyukkuri.moldy", mold)
                : Component.translatable("magnifier.mcyukkuri.no_mold");

        MutableComponent readouts = Component.empty()
                .append(health).append(" · ")
                .append(identity).append(" · ")
                .append(badge).append(" · ")
                .append(dirt);
        if (view.liveChildCount() > Integer.bitCount(view.liveBornMask())) {
            readouts.append(" · ").append(Component.translatable("magnifier.mcyukkuri.pregnant"));
        }
        return List.of(header, readouts);
    }

    private static Component stage(YukkuriView view) {
        String id = switch (FamilyLife.Stage.values()[view.lifeStage()]) {
            case BABY -> "baby";
            case JUVENILE -> "juvenile";
            case ADULT -> "adult";
        };
        return Component.translatable("stage.mcyukkuri." + id);
    }

    private StatusStrip() {}
}
