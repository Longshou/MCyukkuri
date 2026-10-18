package io.github.longshou.mcyukkuri.shop;

import io.github.longshou.mcyukkuri.human.Custody;
import io.github.longshou.mcyukkuri.coordination.DeadlineMap;
import java.util.UUID;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.human.HumanCommerce;
import io.github.longshou.mcyukkuri.human.HumanNpc;
import io.github.longshou.mcyukkuri.life.CareBlock;
import io.github.longshou.mcyukkuri.pet.BadgeGrade;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.phys.AABB;

/** Pen lookup and population count shared by the shopkeeper menu and the keeper's restocking. */
public final class ShopPens {
    public static void reset() { NO_PEN.clear(); }
    private static boolean glassNear(ServerLevel level, BlockPos pos) {
        for (int dx = -2; dx <= 2; dx++) for (int dy = 0; dy <= 2; dy++) for (int dz = -2; dz <= 2; dz++)
            if (level.getBlockState(pos.offset(dx, dy, dz)).is(BlockTags.IMPERMEABLE)) return true;
        return false;
    }
    private static boolean stockOf(YukkuriEntity y, java.util.UUID shop) { return HumanCommerce.ownedBy(y, shop) && (Custody.view(y).role() == Custody.Role.STOCK); }
    /** Sellable stock: animals of Use stock owned by this shopkeeper within 48 blocks; the buyback cap counts only these. */
    public static int stock(ServerLevel level, HumanNpc npc) {
        return level.getEntitiesOfClass(YukkuriEntity.class, npc.getBoundingBox().inflate(48), y -> y.isAlive() && stockOf(y, npc.getUUID())).size();
    }
    /** Animals owned by NPCs within 48 blocks of the NPC. */
    public static int population(ServerLevel level, HumanNpc npc) {
        return level.getEntitiesOfClass(YukkuriEntity.class, npc.getBoundingBox().inflate(48), y -> y.isAlive()
                && (Custody.view(y).owner() != null) && level.getEntity(Custody.view(y).owner()) instanceof HumanNpc).size();
    }
    /** Nearest unoccupied nest near the shopkeeper; display grades prefer a glass-side nest, the rest a glass-free one, but any free nest will do. */
    public static BlockPos freePen(ServerLevel level, HumanNpc shopkeeper, YukkuriEntity animal) {
        return scan(level, shopkeeper, animal, animal.pet().grade().id() >= BadgeGrade.SILVER.id());
    }
    /** Periodic callers (arrival, training): backs off 200 ticks after an empty scan. */
    public static BlockPos freePen(ServerLevel level, HumanNpc shopkeeper, YukkuriEntity animal, boolean display) {
        long now = level.getGameTime();
        if (NO_PEN.active(shopkeeper.getUUID(), now)) return null;
        var pen = scan(level, shopkeeper, animal, display);
        if (pen == null) { if (NO_PEN.size() > 64) NO_PEN.pruneBefore(now); NO_PEN.put(shopkeeper.getUUID(), now + 200); } else NO_PEN.remove(shopkeeper.getUUID());
        return pen;
    }
    /** Shopkeeper to the tick before which the (expensive) pen scan is skipped after it found nothing. */
    private static final DeadlineMap<UUID> NO_PEN = new DeadlineMap<>();
    private static BlockPos scan(ServerLevel level, HumanNpc shopkeeper, YukkuriEntity animal, boolean display) {
        BlockPos origin = shopkeeper.blockPosition(), best = null; double bestScore = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.betweenClosed(origin.offset(-24, -6, -24), origin.offset(24, 6, 24))) {
            if (!level.hasChunkAt(pos) || !(level.getBlockState(pos).getBlock() instanceof CareBlock care) || care.kind() != CareBlock.Kind.NEST
                    || ShopPerformance.reserved(level, pos)) continue;
            if (!level.getEntitiesOfClass(YukkuriEntity.class, new AABB(pos).inflate(1.5), o -> o != animal && o.isAlive() && stockOf(o, shopkeeper.getUUID())).isEmpty()) continue;
            double score = pos.distSqr(origin) + (glassNear(level, pos) == display ? 0 : 1_000_000);
            if (score < bestScore) { bestScore = score; best = pos.immutable(); }
        }
        return best;
    }
    private ShopPens() {}
}
