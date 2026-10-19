package io.github.longshou.mcyukkuri.body;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.network.BodyEffectNetworking;
import io.github.longshou.mcyukkuri.network.BodyEffectPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

/** Server-side notification after body accounting has completed. It never creates a world item. */
public final class BodyEffects {
    private BodyEffects() {}

    public static void emit(YukkuriEntity source, String reason, float amount, Vec3 outlet) {
        if (source == null || source.level().isClientSide || !(source.level() instanceof ServerLevel level)
                || reason == null || reason.isBlank() || !Float.isFinite(amount) || amount <= 0
                || outlet == null || !Double.isFinite(outlet.x) || !Double.isFinite(outlet.y) || !Double.isFinite(outlet.z)) return;
        Vec3 direction = source.getLookAngle();
        if (!Double.isFinite(direction.x) || !Double.isFinite(direction.y) || !Double.isFinite(direction.z)
                || direction.lengthSqr() < 1e-6) direction = new Vec3(0, 0, 1);
        direction = direction.normalize();
        try {
            BodyEffectNetworking.send(level, new BodyEffectPayload(source.getId(), reason, amount,
                    outlet.x, outlet.y, outlet.z, (float) direction.x, (float) direction.y, (float) direction.z,
                    level.getGameTime(), source.growthScale() * source.getScale() * source.profile().modelScale()), outlet);
        } catch (IllegalArgumentException ignored) {
            // Invalid presentation input cannot alter the already-completed body transaction.
        }
    }
}
