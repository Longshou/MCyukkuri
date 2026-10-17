package io.github.longshou.mcyukkuri.settlement;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.EventHooks;

/**
 * Finite wild food: one reachable plant becomes one real meal, subject to mob griefing. Searches are staggered per
 * animal, share the server-wide {@link SearchBudget}, test at most three paths, and reserve their tuft through
 * {@link ForageClaims}, so a whole colony never rushes the same plant in the same tick.
 */
public final class ForageGoal extends Goal {
    private static final int MAX_PATH_TESTS = 3;
    private final YukkuriEntity entity;
    private BlockPos target;
    private int cooldown, elapsed;
    public ForageGoal(YukkuriEntity entity) {
        this.entity = entity; setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
        cooldown = 20 + entity.getRandom().nextInt(100); // never every animal on the same tick after a spawn or reload
    }
    private ServerLevel level() { return (ServerLevel)entity.level(); }
    private boolean grass(BlockPos pos) { return level().hasChunkAt(pos) && Grazing.edible(level().getBlockState(pos)); }
    private boolean edible(BlockPos pos) { return grass(pos) && entity.life().acceptsFood(new ItemStack(Items.SHORT_GRASS)); }
    @Override public boolean canUse() {
        if (entity.getVariant().isHouse() || entity.family().awaitingLanding() || !entity.life().canEat() || entity.life().isSleeping() || entity.isInWaterOrBubble()
                || !EventHooks.canEntityGrief(level(), entity)) return false;
        if (cooldown-- > 0) return false;
        if (!SearchBudget.take(level())) { cooldown = 2 + entity.getRandom().nextInt(8); return false; }
        cooldown = (Seasons.is(entity, Season.AUTUMN) ? 20 : 40) + entity.getRandom().nextInt(10); // autumn foragers are keener
        if (Seasons.forageBlocked(entity) || !entity.life().acceptsFood(new ItemStack(Items.SHORT_GRASS))) return false;
        var found = new ArrayList<BlockPos>();
        for (BlockPos pos : BlockPos.betweenClosed(entity.blockPosition().offset(-6,-2,-6), entity.blockPosition().offset(6,2,6)))
            if (grass(pos) && !ForageClaims.taken(level(), pos, entity.getUUID())) found.add(pos.immutable());
        found.sort(Comparator.comparingDouble(pos -> entity.distanceToSqr(Vec3.atCenterOf(pos))));
        // A claim from an earlier offer that the goal selector never started would otherwise linger.
        if (target != null) ForageClaims.release(level(), target, entity.getUUID());
        target = null; int tests = 0;
        for (BlockPos pos : found) {
            if (!entity.life().dryStand(pos)) continue;
            if (++tests > MAX_PATH_TESTS) break;
            if (entity.life().reachable(pos, false)) { target = pos; break; }
        }
        elapsed = 0;
        if (target == null) return false;
        ForageClaims.claim(level(), target, entity.getUUID());
        return true;
    }
    @Override public boolean canContinueToUse() {
        return target != null && elapsed < 200 && edible(target) && !entity.family().awaitingLanding() && entity.life().canEat()
                && !entity.isInWaterOrBubble() && EventHooks.canEntityGrief(level(), entity);
    }
    @Override public boolean requiresUpdateEveryTick() { return true; }
    @Override public void tick() {
        elapsed++;
        if (!canContinueToUse()) { stop(); return; }
        if (entity.distanceToSqr(Vec3.atBottomCenterOf(target)) < 1) {
            // One bite takes the plant back one growth stage (Grazing); it grows again on game time, so the meadow lasts.
            if (Grazing.edible(level().getBlockState(target)) && entity.life().beginMeal(new ItemStack(Items.SHORT_GRASS), null, false)) Grazing.graze(level(), target);
            stop();
        } else if (elapsed % 10 == 1 && !entity.life().navigate(target, 1, false)) stop();
    }
    @Override public void stop() {
        if (target != null) ForageClaims.release(level(), target, entity.getUUID());
        target = null; cooldown = 60; entity.getNavigation().stop();
    }
}
