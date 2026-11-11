package io.github.longshou.mcyukkuri.client.capture;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.github.longshou.mcyukkuri.badge.BadgeMenu;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.human.HumanMenu;
import io.github.longshou.mcyukkuri.human.HumanNpc;
import io.github.longshou.mcyukkuri.interaction.CareAction;
import io.github.longshou.mcyukkuri.interaction.CareMenu;
import io.github.longshou.mcyukkuri.interaction.InteractionDispatch;
import io.github.longshou.mcyukkuri.item.OperationToolItem.Tool;
import io.github.longshou.mcyukkuri.pet.exam.ExamMenu;
import io.github.longshou.mcyukkuri.registry.ModItems;
import io.github.longshou.mcyukkuri.shop.KeeperMenu;
import io.github.longshou.mcyukkuri.shop.ShopMenu;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.commands.arguments.selector.EntitySelectorParser;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;

/** Opens real menus in the isolated integrated server owned by the recorder. */
final class CaptureScreen {
    private static final int OPEN_TICK = 52;
    private static final int HOVER_TICK = 70;
    private static final int PRESS_TICK = 70, RELEASE_TICK = 92;
    private final String kind;
    private final String selector;
    private final Double magnifierDistance;
    private CompletableFuture<Integer> target;
    private boolean startedUse;
    private boolean hover;
    private MagnifierFrame magnifierFrame;

    record MagnifierFrame(double fov, double distance, boolean useKey, boolean usingItem) {}

    CaptureScreen(String kind, String selector, Double magnifierDistance) {
        this.kind = kind;
        this.selector = selector;
        this.magnifierDistance = magnifierDistance;
    }

    private int open(ServerPlayer player) {
        try {
            var source = player.createCommandSourceStack().withPermission(4);
            var actor = new EntitySelectorParser(new StringReader(selector), true).parse().findSingleEntity(source);
            double offset = kind.equals("magnifier") ? 4.25 : actor instanceof YukkuriEntity ? 1.35 : 0;
            if (magnifierDistance == null) player.teleportTo(actor.getX() + offset, actor.getY(), actor.getZ());
            else {
                // Front-quarter view, away from the other actors lined up to the east.
                player.teleportTo(actor.getX() - 0.6 * magnifierDistance, actor.getY(), actor.getZ() + 0.8 * magnifierDistance);
            }
            player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            player.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY);
            switch (kind) {
                case "care" -> InteractionDispatch.openCare(player, (YukkuriEntity) actor);
                case "care_busy" -> {
                    var yukkuri = (YukkuriEntity) actor;
                    if (!InteractionDispatch.perform(player, yukkuri, CareAction.LAY_DOWN)) {
                        throw new IllegalStateException("Could not start the care capture operation on " + selector);
                    }
                    InteractionDispatch.openCare(player, yukkuri);
                }
                case "cutter" -> {
                    player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Tool.CUTTER.item()));
                    InteractionDispatch.openCutter(player, (YukkuriEntity) actor);
                }
                case "badge" -> BadgeMenu.open(player, (YukkuriEntity) actor);
                case "human" -> HumanMenu.open(player, (HumanNpc) actor);
                case "shop" -> ShopMenu.open(player, (HumanNpc) actor);
                case "exam" -> ExamMenu.open(player, (HumanNpc) actor);
                case "keeper" -> KeeperMenu.open(player, (HumanNpc) actor);
                case "magnifier" -> player.setItemInHand(InteractionHand.MAIN_HAND, ModItems.MAGNIFYING_GLASS.toStack());
                default -> throw new IllegalArgumentException("Unknown capture screen " + kind);
            }
            return actor.getId();
        } catch (CommandSyntaxException error) {
            throw new IllegalArgumentException("Capture selector must find one entity: " + selector, error);
        }
    }

    boolean ready(Minecraft mc, long tick) {
        if (tick < OPEN_TICK) return false;
        if (target == null) {
            mc.getToasts().clear();
            var server = mc.getSingleplayerServer();
            var uuid = mc.player.getUUID();
            target = server.submit(() -> open(server.getPlayerList().getPlayer(uuid)));
            return false;
        }
        if (!target.isDone()) return false;
        int id = target.join();
        if (kind.equals("magnifier")) {
            if (mc.player.getMainHandItem().is(ModItems.MAGNIFYING_GLASS.get()) && mc.level.getEntity(id) != null) {
                int pressTick = magnifierDistance == null ? OPEN_TICK : PRESS_TICK;
                if (!startedUse && tick >= pressTick) {
                    mc.options.keyUse.setDown(true);
                    mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
                    startedUse = true;
                }
                if (magnifierDistance != null && tick >= RELEASE_TICK) mc.options.keyUse.setDown(false);
                if (magnifierDistance != null || mc.player.isUsingItem()) return true;
            }
        } else if (mc.screen instanceof AbstractContainerScreen<?> screen && matches(screen)) {
            hover = kind.equals("care") && tick >= HOVER_TICK;
            return true;
        }
        if (tick > OPEN_TICK + 60) throw new IllegalStateException("Capture did not open " + kind + " on " + selector);
        return false;
    }

    private boolean matches(AbstractContainerScreen<?> screen) {
        var menu = screen.getMenu();
        return switch (kind) {
            case "care", "care_busy", "cutter" -> menu instanceof CareMenu care && care.cutter() == kind.equals("cutter");
            case "badge" -> menu instanceof BadgeMenu;
            case "human" -> menu instanceof HumanMenu;
            case "shop" -> menu instanceof ShopMenu;
            case "exam" -> menu instanceof ExamMenu;
            case "keeper" -> menu instanceof KeeperMenu;
            default -> false;
        };
    }

    void render(ScreenEvent.Render.Pre event) {
        if (!(event.getScreen() instanceof AbstractContainerScreen<?> screen) || !matches(screen)) return;
        int x = -1, y = -1;
        if (hover) {
            for (var child : screen.children()) {
                if (child instanceof AbstractWidget widget && widget.visible && widget.active && widget.getTooltip() != null) {
                    x = widget.getX() + 2;
                    y = widget.getY() + widget.getHeight() / 2;
                    break;
                }
            }
        }
        event.setCanceled(true);
        screen.renderWithTooltip(event.getGuiGraphics(), x, y, event.getPartialTick());
    }

    Vec3 aim(Minecraft mc, Camera camera, float partial) {
        if (!kind.equals("magnifier") || target == null || !target.isDone()) return null;
        var actor = mc.level.getEntity(target.join());
        if (actor == null) return null;
        return actor.getPosition(partial).add(0, actor.getBbHeight() * 0.6, 0).subtract(camera.getPosition());
    }

    void fov(ViewportEvent.ComputeFov event) {
        if (!event.usedConfiguredFov()) return;
        var mc = Minecraft.getInstance();
        Vec3 aim = aim(mc, event.getCamera(), (float) event.getPartialTick());
        if (aim != null) magnifierFrame = new MagnifierFrame(event.getFOV(), aim.length(),
                mc.options.keyUse.isDown(), mc.player.isUsingItem());
    }

    MagnifierFrame magnifierFrame() { return magnifierFrame; }

    void close(Minecraft mc) {
        if (startedUse) mc.options.keyUse.setDown(false);
        if (mc.screen != null) mc.screen.onClose();
        mc.options.hideGui = true;
    }
}
