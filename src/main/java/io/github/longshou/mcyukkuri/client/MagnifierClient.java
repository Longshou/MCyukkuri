package io.github.longshou.mcyukkuri.client;

import io.github.longshou.mcyukkuri.MCYukkuri;
import io.github.longshou.mcyukkuri.entity.YukkuriView;
import io.github.longshou.mcyukkuri.registry.ModItems;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.CalculatePlayerTurnEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RenderHandEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;

/** Client half of the magnifying glass: a smooth close zoom, scaled mouse and a crosshair status strip. */
public final class MagnifierClient {
    private static final float ZOOM_FOV = 0.28f;
    private static final double MOUSE_SCALE = 0.3;
    private static final double REACH = 12.0;
    private static final double AIM_ASSIST = 0.12; // Allow a narrow margin around a small body.
    private static float previousZoom;
    private static float zoom;

    public static void registerHud(RegisterGuiLayersEvent event) {
        event.registerAbove(VanillaGuiLayers.CROSSHAIR, MCYukkuri.id("magnifier_status"), MagnifierClient::renderStrip);
    }

    private static LocalPlayer observer(Minecraft minecraft) {
        var player = minecraft.player;
        if (player == null || !player.isAlive() || minecraft.screen != null || minecraft.getOverlay() != null
                || !minecraft.options.getCameraType().isFirstPerson() || minecraft.getCameraEntity() != player) return null;
        var glass = ModItems.MAGNIFYING_GLASS.get();
        return player.isHolding(glass) && (!player.isUsingItem() || player.getUseItem().is(glass)) ? player : null;
    }

    private static boolean scoping(Minecraft minecraft, LocalPlayer player) {
        return minecraft.options.keyUse.isDown() && player.isUsingItem()
                && player.getItemInHand(player.getUsedItemHand()).is(ModItems.MAGNIFYING_GLASS.get());
    }

    static void tick(ClientTickEvent.Post event) {
        var minecraft = Minecraft.getInstance();
        var player = observer(minecraft);
        if (player == null) {
            previousZoom = zoom = 0f;
            return;
        }
        previousZoom = zoom;
        zoom = Mth.approach(zoom, scoping(minecraft, player) ? 1f : 0f, 0.12f);
    }

    static void fov(ViewportEvent.ComputeFov event) {
        if (!event.usedConfiguredFov() || observer(Minecraft.getInstance()) == null) return;
        float amount = Mth.lerp((float) event.getPartialTick(), previousZoom, zoom);
        event.setFOV(event.getFOV() * Mth.lerp(amount, 1f, ZOOM_FOV));
    }

    static void mouse(CalculatePlayerTurnEvent event) {
        if (zoom == 0f || observer(Minecraft.getInstance()) == null) return;
        // MouseHandler cubes (0.6 * sensitivity + 0.2) to get the actual turn rate.
        double base = event.getMouseSensitivity() * 0.6f + 0.2f;
        double scaled = base * Math.cbrt(Mth.lerp(zoom, 1.0, MOUSE_SCALE));
        event.setMouseSensitivity((scaled - 0.2f) / 0.6f);
    }

    static void renderHand(RenderHandEvent event) {
        var minecraft = Minecraft.getInstance();
        var player = observer(minecraft);
        if (player != null && scoping(minecraft, player)) event.setCanceled(true);
    }

    private static void renderStrip(GuiGraphics g, DeltaTracker delta) {
        var minecraft = Minecraft.getInstance();
        var player = observer(minecraft);
        if (minecraft.options.hideGui || player == null || !scoping(minecraft, player)) return;
        var aim = pick(player, minecraft.gameRenderer.getMainCamera());
        if (aim == null) return;
        StatusStrip.of(aim).renderHud(g, minecraft.font);
    }

    private static YukkuriView pick(LocalPlayer player, Camera camera) {
        Vec3 eye = camera.getPosition();
        Vec3 end = eye.add(new Vec3(camera.getLookVector()).scale(REACH));
        var candidates = player.level().getEntities(player, new AABB(eye, end).inflate(AIM_ASSIST),
                e -> e instanceof YukkuriView && e.isPickable() && !e.isInvisibleTo(player));
        if (candidates.isEmpty()) return null;
        end = player.level().clip(outlineRay(player, eye, end)).getLocation();

        YukkuriView direct = null, assisted = null;
        double directDistance = eye.distanceToSqr(end), assistedDistance = directDistance;
        for (var entity : candidates) {
            AABB body = entity.getBoundingBox();
            Vec3 hit = firstHit(body, eye, end);
            if (hit != null) {
                double distance = eye.distanceToSqr(hit);
                if (distance < directDistance) {
                    directDistance = distance;
                    direct = (YukkuriView) entity;
                }
                continue;
            }
            if (direct != null) continue;
            Vec3 near = firstHit(body.inflate(AIM_ASSIST), eye, end);
            if (near == null) continue;
            Vec3 surface = new Vec3(Mth.clamp(near.x, body.minX, body.maxX),
                    Mth.clamp(near.y, body.minY, body.maxY), Mth.clamp(near.z, body.minZ, body.maxZ));
            double distance = eye.distanceToSqr(surface);
            if (distance < assistedDistance && player.level().clip(outlineRay(player, eye, surface)).getType() == HitResult.Type.MISS) {
                assistedDistance = distance;
                assisted = (YukkuriView) entity;
            }
        }
        return direct != null ? direct : assisted;
    }

    private static Vec3 firstHit(AABB bounds, Vec3 eye, Vec3 end) {
        return bounds.contains(eye) ? eye : bounds.clip(eye, end).orElse(null);
    }

    private static ClipContext outlineRay(LocalPlayer player, Vec3 start, Vec3 end) {
        return new ClipContext(start, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player);
    }

    private MagnifierClient() {}
}
