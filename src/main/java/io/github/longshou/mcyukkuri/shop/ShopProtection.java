package io.github.longshou.mcyukkuri.shop;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.human.Custody;
import io.github.longshou.mcyukkuri.interaction.InteractionDispatch;
import io.github.longshou.mcyukkuri.registry.ModItems;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
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
import net.neoforged.neoforge.event.level.PistonEvent;

/**
 * Inside a pet shop (its authored footprint, docs/FEATURES.md 宠物店) nothing is destroyed and the stock is not handled before it is bought:
 * players cannot break or place blocks there, cannot take, turn or break the goods' item frames,
 * and cannot interact with or hurt stock animals; explosions leave the shop and its stock standing. An empty-hand click on a stock animal
 * still opens the staff's menu on its row (ShopMenu.clickStock), and the magnifying glass may look at it.
 */
public final class ShopProtection {
    private ShopProtection() {}

    private static boolean guarded(Level level, BlockPos pos) {
        return level instanceof ServerLevel server && ShopRegistry.get(server).protectedAt(server, pos);
    }
    /** A stock animal standing in its own shop. */
    private static ShopRegistry.Entry stockShop(Entity target) {
        if (!(target instanceof YukkuriEntity y) || !(y.level() instanceof ServerLevel level)) return null;
        var shop = ShopRegistry.get(level).shopAt(level, y.position());
        return shop != null && ShopTrade.stockOf(y, shop.id) ? shop : null;
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
        if (!(event.getEntity() instanceof Player player)) return;
        boolean inside = guarded(player.level(), event.getPos());
        if (event instanceof BlockEvent.EntityMultiPlaceEvent multiple)
            inside |= multiple.getReplacedBlockSnapshots().stream().anyMatch(s -> guarded(player.level(), s.getPos()));
        if (!inside) return;
        event.setCanceled(true);
        refuse(player, "block");
    }
    public static void fluid(BlockEvent.FluidPlaceBlockEvent event) {
        if (event.getLevel() instanceof ServerLevel level && guarded(level, event.getPos())) event.setCanceled(true);
    }
    public static void piston(PistonEvent.Pre event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (guarded(level, event.getPos()) || guarded(level, event.getFaceOffsetPos())) {
            event.setCanceled(true);
            return;
        }
        var resolver = event.getStructureHelper();
        if (resolver != null && resolver.resolve() && (resolver.getToDestroy().stream().anyMatch(pos -> guarded(level, pos))
                || resolver.getToPush().stream().anyMatch(pos -> guarded(level, pos) || guarded(level, pos.relative(resolver.getPushDirection())))))
            event.setCanceled(true);
    }
    public static void used(PlayerInteractEvent.RightClickBlock event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        var level = player.serverLevel();
        var pos = event.getPos();
        var shop = ShopRegistry.get(level).protectedShop(level, pos);
        if (shop == null) return;
        var state = level.getBlockState(pos);
        if (state.getBlock() instanceof DoorBlock door) {
            boolean wooden = door.type().canOpenByHand();
            if (wooden && !ShopAccess.legacyServiceDoor(shop, pos)) return;
            var required = ShopAccess.doorTier(shop, pos);
            if (required == null) return;
            boolean allowed = shop.membership(player.getUUID()).allows(required);
            boolean egress = !allowed && ShopAccess.egress(shop, player, pos);
            if (wooden && (allowed || egress)) return;
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.SUCCESS);
            if (event.getHand() != InteractionHand.MAIN_HAND || player.isSpectator()) return;
            if (allowed || egress) {
                ShopDoors.toggle(player, shop, pos, egress);
            } else {
                int missing = required.threshold() - shop.spend(player.getUUID());
                ShopTrade.say(player, "membership.door", required.nameText(), missing);
                GameplayLog.limited(player, "shop", "membership_door." + required, 100,
                        "action=door result=refused shop={} door={} required={} missing={}", shop.id, pos, required, missing);
            }
            return;
        }
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.FAIL);
        if (event.getHand() == InteractionHand.MAIN_HAND) refuse(event.getEntity(), "block");
    }
    public static void touched(PlayerInteractEvent.EntityInteract event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        var target = event.getTarget();
        if (target instanceof ItemFrame frame && guarded(event.getLevel(), frame.getPos())) {
            event.setCanceled(true); event.setCancellationResult(InteractionResult.FAIL);
            if (event.getHand() == InteractionHand.MAIN_HAND) refuse(player, "frame");
            return;
        }
        var shop = stockShop(target);
        if (shop == null) return;
        if (event.getHand() == InteractionHand.MAIN_HAND && ShopAccess.touchStock(player.serverLevel(), shop, player)) {
            event.setCanceled(true); event.setCancellationResult(InteractionResult.SUCCESS);
            return;
        }
        var held = event.getItemStack();
        // The empty hand asks the staff (the menu on its row); the magnifying glass only looks.
        if (held.isEmpty() && event.getHand() == InteractionHand.MAIN_HAND && !player.isSecondaryUseActive() || held.is(ModItems.MAGNIFYING_GLASS.get())) return;
        event.setCanceled(true); event.setCancellationResult(InteractionResult.FAIL);
        if (event.getHand() == InteractionHand.MAIN_HAND) refuse(player, "stock");
    }
    public static void touchedAt(PlayerInteractEvent.EntityInteractSpecific event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        var target = event.getTarget();
        var shop = stockShop(target);
        if (shop != null && event.getHand() == InteractionHand.MAIN_HAND && ShopAccess.touchStock(player.serverLevel(), shop, player)) {
            event.setCanceled(true); event.setCancellationResult(InteractionResult.SUCCESS);
            return;
        }
        if (target instanceof ItemFrame frame && guarded(event.getLevel(), frame.getPos()) || shop != null && !event.getItemStack().isEmpty()
                && !event.getItemStack().is(ModItems.MAGNIFYING_GLASS.get())) {
            event.setCanceled(true); event.setCancellationResult(InteractionResult.FAIL);
        }
    }
    public static void struck(AttackEntityEvent event) {
        var target = event.getTarget();
        var player = event.getEntity();
        if (!(player instanceof ServerPlayer server)) return;
        if (target instanceof ItemFrame frame && guarded(frame.level(), frame.getPos())) {
            event.setCanceled(true);
            refuse(player, "frame");
        } else if (target instanceof YukkuriEntity animal) {
            var reason = Custody.harmRefusal(player, animal, animal.blockPosition());
            if (reason == null) return;
            event.setCanceled(true);
            var shop = stockShop(animal);
            if (shop != null && ShopAccess.touchStock(server.serverLevel(), shop, server)) return;
            InteractionDispatch.refuse(player, animal, "attack", reason);
        }
    }
    /** Arrows, thrown things and any other harm a player causes to stock. */
    public static void harmed(LivingIncomingDamageEvent event) {
        if (event.getSource().getEntity() instanceof ServerPlayer player && event.getEntity() instanceof YukkuriEntity animal
                && Custody.harmRefusal(player, animal, animal.blockPosition()) != null) {
            event.setCanceled(true);
            var shop = stockShop(animal);
            if (shop != null) ShopAccess.touchStock(player.serverLevel(), shop, player);
        }
    }
    public static void exploded(ExplosionEvent.Detonate event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        event.getAffectedBlocks().removeIf(pos -> guarded(level, pos));
        event.getAffectedEntities().removeIf(e -> e instanceof ItemFrame frame && guarded(level, frame.getPos()) || stockShop(e) != null);
    }
}
