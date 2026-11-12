package io.github.longshou.mcyukkuri.shop;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.registry.ModItems;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.DoorBlock;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;

/**
 * Inside a pet shop (its authored footprint, docs/FEATURES.md 宠物店) nothing is destroyed and the stock is not handled before it is bought:
 * players cannot break, place or use blocks there (the wooden street doors excepted), cannot take, turn or break the goods' item frames,
 * and cannot interact with or hurt stock animals; explosions leave the shop and its stock standing. An empty-hand click on a stock animal
 * still opens the staff's menu on its row (ShopMenu.clickStock), and the magnifying glass may look at it.
 */
public final class ShopProtection {
    private ShopProtection() {}

    private static boolean guarded(Level level, BlockPos pos) {
        return level instanceof ServerLevel server && ShopRegistry.get(server).protectedAt(server, pos);
    }
    /** A stock animal standing in its own shop. */
    private static boolean stock(Entity target) {
        if (!(target instanceof YukkuriEntity y) || !(y.level() instanceof ServerLevel level)) return false;
        var shop = ShopRegistry.get(level).shopAt(level, y.position());
        return shop != null && ShopTrade.stockOf(y, shop.keeper);
    }
    private static void refuse(Player player, String what) {
        player.displayClientMessage(Component.translatable("shop.mcyukkuri.protect." + what), true);
        GameplayLog.limited(player, "shop", "protect." + what, 100, "action=protect result=refused what={}", what);
    }

    public static void broke(BlockEvent.BreakEvent event) {
        if (!guarded(event.getPlayer().level(), event.getPos())) return;
        event.setCanceled(true);
        refuse(event.getPlayer(), "block");
    }
    public static void placed(BlockEvent.EntityPlaceEvent event) {
        if (!(event.getEntity() instanceof Player player) || !guarded(player.level(), event.getPos())) return;
        event.setCanceled(true);
        refuse(player, "block");
    }
    public static void used(PlayerInteractEvent.RightClickBlock event) {
        if (event.getLevel().isClientSide() || !guarded(event.getLevel(), event.getPos())) return;
        var state = event.getLevel().getBlockState(event.getPos());
        if (state.getBlock() instanceof DoorBlock door && door.type().canOpenByHand()) return;
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.FAIL);
        if (event.getHand() == InteractionHand.MAIN_HAND) refuse(event.getEntity(), "block");
    }
    public static void touched(PlayerInteractEvent.EntityInteract event) {
        if (event.getLevel().isClientSide()) return;
        var player = event.getEntity(); var target = event.getTarget();
        if (target instanceof ItemFrame frame && guarded(event.getLevel(), frame.getPos())) {
            event.setCanceled(true); event.setCancellationResult(InteractionResult.FAIL);
            if (event.getHand() == InteractionHand.MAIN_HAND) refuse(player, "frame");
            return;
        }
        if (!stock(target)) return;
        var held = event.getItemStack();
        // The empty hand asks the staff (the menu on its row); the magnifying glass only looks.
        if (held.isEmpty() && event.getHand() == InteractionHand.MAIN_HAND || held.is(ModItems.MAGNIFYING_GLASS.get())) return;
        event.setCanceled(true); event.setCancellationResult(InteractionResult.FAIL);
        if (event.getHand() == InteractionHand.MAIN_HAND) refuse(player, "stock");
    }
    public static void touchedAt(PlayerInteractEvent.EntityInteractSpecific event) {
        if (event.getLevel().isClientSide()) return;
        var target = event.getTarget();
        if (target instanceof ItemFrame frame && guarded(event.getLevel(), frame.getPos()) || stock(target) && !event.getItemStack().isEmpty()
                && !event.getItemStack().is(ModItems.MAGNIFYING_GLASS.get())) {
            event.setCanceled(true); event.setCancellationResult(InteractionResult.FAIL);
        }
    }
    public static void struck(AttackEntityEvent event) {
        var target = event.getTarget();
        if (event.getEntity().level().isClientSide()) return;
        boolean frame = target instanceof ItemFrame f && guarded(f.level(), f.getPos());
        if (!frame && !stock(target)) return;
        event.setCanceled(true);
        refuse(event.getEntity(), frame ? "frame" : "stock");
    }
    /** Arrows, thrown things and any other harm a player causes to stock. */
    public static void harmed(LivingIncomingDamageEvent event) {
        if (event.getSource().getEntity() instanceof Player && stock(event.getEntity())) event.setCanceled(true);
    }
    public static void exploded(ExplosionEvent.Detonate event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        event.getAffectedBlocks().removeIf(pos -> guarded(level, pos));
        event.getAffectedEntities().removeIf(e -> e instanceof ItemFrame frame && guarded(level, frame.getPos()) || stock(e));
    }
}
