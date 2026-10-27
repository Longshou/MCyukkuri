package io.github.longshou.mcyukkuri.client;

import io.github.longshou.mcyukkuri.MCYukkuri;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import io.github.longshou.mcyukkuri.subspecies.HatBoatEntity;
import java.util.Comparator;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.InputEvent;

/** Client-only readout of a real primary-hand use input.  It never changes the hit result or input event. */
public final class HatInteractionTrace {
    private static final int TRACE_INTERVAL_TICKS = 20;
    private static long lastTraceTick = Long.MIN_VALUE;

    private HatInteractionTrace() {}

    public static void trace(InputEvent.InteractionKeyMappingTriggered event) {
        if (!GameplayLog.detailEnabled() || !event.isUseItem() || event.getHand() != InteractionHand.MAIN_HAND) return;
        var minecraft = Minecraft.getInstance();
        var player = minecraft.player;
        if (player == null || minecraft.level == null) return;

        long tick = minecraft.level.getGameTime();
        if (lastTraceTick != Long.MIN_VALUE && tick >= lastTraceTick && tick - lastTraceTick < TRACE_INTERVAL_TICKS) return;
        lastTraceTick = tick;

        var camera = minecraft.getCameraEntity();
        Vec3 cameraPosition = minecraft.gameRenderer.getMainCamera().getPosition();
        Vec3 eye = player.getEyePosition();
        Vec3 rayOrigin = camera == null ? eye : camera.getEyePosition();
        Vec3 view = camera == null ? player.getViewVector(1.0F) : camera.getViewVector(1.0F);
        double reach = player.entityInteractionRange();
        HitResult hit = minecraft.hitResult;
        double hitDistance = hit == null ? Double.NaN : rayOrigin.distanceTo(hit.getLocation());
        String hitSummary = describeHit(hit, eye);
        String nearbyHats = nearbyHats(player, rayOrigin, view, reach);

        MCYukkuri.LOGGER.info("[hat-input] t={} cancelled={} player=#{} uuid={} pos={} eye={} camera={} cameraPos={} rayOriginNow={} mode={} spectator={} using={} useItem={} main={} reach={} hitDistance={} hit={} nearbyHats={}",
                tick, event.isCanceled(),
                player.getId(), player.getUUID(), player.position(), eye,
                camera == null ? "none" : entityId(camera), cameraPosition, rayOrigin,
                minecraft.gameMode == null ? "none" : minecraft.gameMode.getPlayerMode(), player.isSpectator(), player.isUsingItem(),
                itemSummary(player.getUseItem()), itemSummary(player.getMainHandItem()), reach, hitDistance, hitSummary, nearbyHats);
    }

    private static String describeHit(HitResult hit, Vec3 eye) {
        if (hit == null) return "none";
        double playerDistance = eye.distanceTo(hit.getLocation());
        if (hit.getType() == HitResult.Type.MISS)
            return "MISS(point=" + hit.getLocation() + ",playerDistance=" + playerDistance + ")";
        if (hit instanceof EntityHitResult entityHit) {
            Entity entity = entityHit.getEntity();
            return "ENTITY(point=" + hit.getLocation() + ",playerDistance=" + playerDistance + ",target=" + entityId(entity) + ")";
        }
        if (hit instanceof BlockHitResult blockHit) {
            return "BLOCK(point=" + hit.getLocation() + ",playerDistance=" + playerDistance + ",pos=" + blockHit.getBlockPos() + ")";
        }
        return hit.getType() + "(point=" + hit.getLocation() + ",playerDistance=" + playerDistance + ")";
    }

    /** These are only nearby, loaded hulls in the viewing corridor; {@link Minecraft#hitResult} remains the actual target. */
    private static String nearbyHats(Entity player, Vec3 cameraPosition, Vec3 view, double reach) {
        Vec3 end = cameraPosition.add(view.scale(reach));
        AABB corridor = new AABB(cameraPosition, end).inflate(1.25);
        List<HatBoatEntity> hats = player.level().getEntitiesOfClass(HatBoatEntity.class, corridor,
                hat -> !hat.isRemoved()).stream()
                .sorted(Comparator.comparingDouble(hat -> rayDistance(cameraPosition, view, reach, hat.position())))
                .limit(3)
                .toList();
        if (hats.isEmpty()) return "[]";
        return hats.stream().map(hat -> "{id=" + hat.getId() + ",uuid=" + hat.getUUID() + ",state=" + hat.state()
                + ",pos=" + hat.position() + ",bb=" + hat.getBoundingBox() + ",pickRadius=" + hat.getPickRadius()
                + ",rayDistance=" + rayDistance(cameraPosition, view, reach, hat.position()) + "}").toList().toString();
    }

    private static double rayDistance(Vec3 eye, Vec3 view, double reach, Vec3 point) {
        double along = Math.clamp(point.subtract(eye).dot(view), 0, reach);
        return point.distanceTo(eye.add(view.scale(along)));
    }

    private static String entityId(Entity entity) {
        return entity.getClass().getSimpleName() + "#" + entity.getId() + " uuid=" + entity.getUUID();
    }

    private static String itemSummary(ItemStack stack) {
        return stack.isEmpty() ? "empty" : stack.getDescriptionId() + "x" + stack.getCount();
    }
}
