package io.github.longshou.mcyukkuri.client;

import io.github.longshou.mcyukkuri.body.BodySigns;
import io.github.longshou.mcyukkuri.entity.YukkuriView;
import io.github.longshou.mcyukkuri.pet.BadgeGrade;
import java.util.List;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/** Short crosshair status strip built from already-synced values; the coming interaction UI shares it. */
public final class StatusStrip {
    public static List<Component> lines(YukkuriView view) {
        Component name = view.hasCustomName()
                ? view.getDisplayName()
                : Component.translatable("entity.mcyukkuri." + view.getVariant().assetId());
        Component header = Component.translatable("magnifier.mcyukkuri.header", name, stage(view));

        Component health = Component.translatable("magnifier.mcyukkuri.health",
                Math.round(view.getHealth()), Math.round(view.getMaxHealth()));
        int medal = view.visibleMedal();
        Component identity = medal == BadgeGrade.NONE.id()
                ? Component.translatable("magnifier.mcyukkuri.wild")
                : Component.translatable("magnifier.mcyukkuri.pet",
                        Component.translatable("badge.mcyukkuri.profile.grade." + medal));
        int mold = view.visibleSigns() & BodySigns.MOLD_MASK;
        Component dirt = mold > 0
                ? Component.translatable("magnifier.mcyukkuri.moldy", mold)
                : Component.translatable("magnifier.mcyukkuri.clean");

        MutableComponent readouts = Component.empty()
                .append(health).append(" · ")
                .append(identity).append(" · ")
                .append(dirt);
        if (view.livePregnancyProgress() > 0 || view.broodCount() > 0) {
            readouts.append(" · ").append(Component.translatable("magnifier.mcyukkuri.pregnant"));
        }
        return List.of(header, readouts);
    }

    private static Component stage(YukkuriView view) {
        String id = switch (view.lifeStage()) {
            case 0 -> "baby";
            case 1 -> "juvenile";
            default -> "adult";
        };
        return Component.translatable("stage.mcyukkuri." + id);
    }

    private StatusStrip() {}
}
