package io.github.longshou.mcyukkuri.client;

import io.github.longshou.mcyukkuri.MCYukkuri;
import io.github.longshou.mcyukkuri.entity.YukkuriView;
import io.github.longshou.mcyukkuri.registry.ModItems;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.client.event.CalculatePlayerTurnEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import net.neoforged.neoforge.common.NeoForge;

/** Client half of the magnifying glass: a smooth close zoom, scaled mouse and a crosshair status strip. */
public final class MagnifierClient {
    private static final float ZOOM_FOV = 0.28f;   // a magnifier, not a telescope
    private static final float MOUSE_SCALE = 0.3f; // sensitivity at full zoom
    private static final double REACH = 12.0;      // extended pick distance
    private static final float INFLATE = 0.4f;     // small bodies are hard to hit
    private static float zoom;                     // 0..1, eased every tick
    private static YukkuriView aim;

    public static void register() {
        NeoForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, ViewportEvent.ComputeFov.class, MagnifierClient::fov);
        NeoForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, CalculatePlayerTurnEvent.class, MagnifierClient::mouse);
        NeoForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, ClientTickEvent.Post.class, MagnifierClient::tick);
    }

    public static void registerHud(RegisterGuiLayersEvent event) {
        event.registerAboveAll(MCYukkuri.id("magnifier_status"), MagnifierClient::renderStrip);
    }

    private static boolean scoping(LocalPlayer player) {
        return player.isUsingItem() && player.getUseItem().is(ModItems.MAGNIFYING_GLASS.get());
    }

    private static void tick(ClientTickEvent.Post event) {
        var minecraft = Minecraft.getInstance();
        var player = minecraft.player;
        float target = player != null && scoping(player) ? 1f : 0f;
        zoom = Mth.approach(zoom, target, 0.12f);
        aim = zoom > 0.01f && player != null ? pick(player) : null;
    }

    private static void fov(ViewportEvent.ComputeFov event) {
        if (zoom > 0.001f) event.setFOV(event.getFOV() * (1f - zoom * (1f - ZOOM_FOV)));
    }

    private static void mouse(CalculatePlayerTurnEvent event) {
        if (zoom > 0.001f) event.setMouseSensitivity(event.getMouseSensitivity() * (1f - zoom * (1f - MOUSE_SCALE)));
    }

    private static void renderStrip(GuiGraphics g, DeltaTracker delta) {
        if (aim == null || aim.isRemoved()) return;
        var minecraft = Minecraft.getInstance();
        if (minecraft.player == null) return;
        var font = minecraft.font;
        int width = minecraft.getWindow().getGuiScaledWidth();
        int height = minecraft.getWindow().getGuiScaledHeight();
        int y = height / 2 + 12;
        for (var line : StatusStrip.lines(aim)) {
            g.drawString(font, line, (width - font.width(line)) / 2, y, 0xFFFFFFFF, true);
            y += 10;
        }
    }

    private static YukkuriView pick(LocalPlayer player) {
        Vec3 eye = player.getEyePosition();
        Vec3 end = eye.add(player.getViewVector(1f).scale(REACH));
        BlockHitResult block = player.level().clip(
                new ClipContext(eye, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
        double limit = block.getType() == HitResult.Type.MISS ? REACH : eye.distanceTo(block.getLocation());
        YukkuriView best = null;
        double bestDist = limit * limit;
        AABB box = new AABB(eye, end).inflate(1.0);
        for (Entity entity : player.level().getEntities(player, box, e -> e instanceof YukkuriView)) {
            var hit = entity.getBoundingBox().inflate(INFLATE).clip(eye, end);
            if (hit.isEmpty()) continue;
            double d = eye.distanceToSqr(hit.get());
            if (d < bestDist) {
                bestDist = d;
                best = (YukkuriView) entity;
            }
        }
        return best;
    }

    private MagnifierClient() {}
}
