package io.github.longshou.mcyukkuri.settlement;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import java.util.EnumSet;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.EventHooks;

/** Finite wild food: one reachable plant becomes one real meal, subject to mob griefing. */
public final class ForageGoal extends Goal {
    private final YukkuriEntity entity;
    private BlockPos target;
    private int cooldown, elapsed;
    public ForageGoal(YukkuriEntity entity) { this.entity = entity; setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK)); }
    private ServerLevel level() { return (ServerLevel)entity.level(); }
    private boolean edible(BlockPos pos) {
        if (!level().hasChunkAt(pos)) return false;
        var state = level().getBlockState(pos);
        return state.is(Blocks.SHORT_GRASS) && entity.life().acceptsFood(new ItemStack(state.getBlock().asItem()));
    }
    @Override public boolean canUse() {
        if (entity.getVariant().isHouse() || entity.family().awaitingLanding() || !entity.life().canEat() || entity.life().isSleeping() || entity.isInWaterOrBubble()
                || !EventHooks.canEntityGrief(level(), entity)) return false;
        if (cooldown-- > 0) return false;
        cooldown = Seasons.is(entity, Season.AUTUMN) ? 20 : 40; // autumn foragers are keener
        if (Seasons.forageBlocked(entity)) return false;
        double nearest = Double.MAX_VALUE; target = null;
        for (BlockPos pos : BlockPos.betweenClosed(entity.blockPosition().offset(-6,-2,-6), entity.blockPosition().offset(6,2,6))) {
            double distance = entity.distanceToSqr(Vec3.atCenterOf(pos));
            if (distance < nearest && edible(pos) && entity.life().dryStand(pos) && entity.life().reachable(pos, false)) {
                target = pos.immutable(); nearest = distance;
            }
        }
        elapsed = 0; return target != null;
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
            var state = level().getBlockState(target);
            if (level().setBlock(target, Blocks.AIR.defaultBlockState(), 3)
                    && !entity.life().beginMeal(new ItemStack(state.getBlock().asItem()), null, false)) level().setBlock(target, state, 3);
            stop();
        } else if (elapsed % 10 == 1 && !entity.life().navigate(target, 1, false)) stop();
    }
    @Override public void stop() { target = null; cooldown = 60; entity.getNavigation().stop(); }
}
