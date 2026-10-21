package io.github.longshou.mcyukkuri.settlement;

import io.github.longshou.mcyukkuri.world.SearchBudget.Purpose;
import javax.annotation.Nullable;
import io.github.longshou.mcyukkuri.entity.Behaviors;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.life.TravelRange;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import net.minecraft.world.entity.ai.util.LandRandomPos;
import io.github.longshou.mcyukkuri.status.Reactions;
import io.github.longshou.mcyukkuri.season.Season;
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
import io.github.longshou.mcyukkuri.rare.MyonLife;

/**
 * Finite wild food: one reachable plant becomes one real meal, subject to mob griefing. Searches are staggered per
 * animal, share the server-wide {@link SearchBudget}, test at most three paths, and reserve their tuft through
 * {@link ForageClaims}, so a whole colony never rushes the same plant in the same tick.
 */
public final class ForageGoal extends Goal {
    private static final int MAX_PATH_TESTS = 3;
    private final YukkuriEntity entity;
    private BlockPos target;
    private BlockPos searchSpot;
    private boolean running;
    private ForageClaims.Claim claim;
    private int elapsed, walkTicks;
    private long scanAfter;
    public ForageGoal(YukkuriEntity entity) {
        this.entity = entity; setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
        // Scan budgeting must not delay ownership of the hunger-driven search.
    }
    /** A proposed target can precede start; running ownership is checked outside this pure query. */
    @Nullable public String activity() {
        if (entity.life().mealTicks() > 0) return entity.life().activity();
        return running ? Behaviors.FORAGE_SEEK : null;
    }

    private ServerLevel level() { return (ServerLevel)entity.level(); }
    private boolean grass(BlockPos pos) { return level().hasChunkAt(pos) && Grazing.edible(level().getBlockState(pos)); }
    private boolean edible(BlockPos pos) { return grass(pos) && entity.life().acceptsFood(new ItemStack(Items.SHORT_GRASS)); }
    @Override public boolean canUse() {
        if (entity.isPassenger() || entity.profile().houseBody() || entity.family().awaitingLanding() || !entity.life().canEat() || entity.life().isSleeping() || entity.isInWaterOrBubble()
                || !EventHooks.canEntityGrief(level(), entity)) return false;
        if (SettlementSeasons.forageBlocked(entity)) return false;
        scanFood();
        if (target == null && entity.life().nutrition() >= 30) return false;
        elapsed = walkTicks = 0; searchSpot = null;
        return true;
    }
    private void scanFood() {
        long now = level().getGameTime();
        if (now < scanAfter || !SearchBudget.take(level(), entity, Purpose.FORAGE)) return;
        scanAfter = now + Math.max(1, Math.round((SettlementSeasons.is(entity, Season.AUTUMN) ? 20 : 40) / MyonLife.forageScale(entity)));
        int reach = (int)Math.round(TravelRange.search(entity, 6) * MyonLife.forageScale(entity)
                * entity.life().personality().exploreMultiplier());
        findFood(reach);
    }
    private double searchSpeed() { return entity.life().nutrition() < 20 ? 1.12 : 1; }
    private void findFood(int reach) {
        var found = new ArrayList<BlockPos>();
        for (BlockPos pos : BlockPos.betweenClosed(entity.blockPosition().offset(-reach,-2,-reach), entity.blockPosition().offset(reach,2,reach)))
            if (edible(pos) && !ForageClaims.taken(level(), pos, entity.getUUID())) found.add(pos.immutable());
        found.sort(Comparator.comparingDouble(pos -> entity.distanceToSqr(Vec3.atCenterOf(pos))));
        // A claim from an earlier offer that the goal selector never started would otherwise linger.
        ForageClaims.release(claim);
        claim = null; target = null; int tests = 0;
        for (BlockPos pos : found) {
            if (!Reactions.Weather.allowsStep(entity,pos) || !entity.life().dryStand(pos) || !entity.family().allowsIdleWalk(Vec3.atBottomCenterOf(pos))) continue;
            if (++tests > MAX_PATH_TESTS) break;
            if (entity.life().reachable(pos, false)) {
                claim = ForageClaims.claim(level(), pos, entity.getUUID());
                if (claim != null) { target = pos; break; }
            }
        }
    }
    @Override public void start() {
        running = true;
        GameplayLog.limited(entity,"forage","start",1200,"food search started target={} nutrition={}",target,entity.life().nutrition());
    }
    @Override public boolean canContinueToUse() {
        return running && !entity.life().isSleeping() && !entity.family().awaitingLanding() && entity.life().canEat()
                && (target != null || entity.life().nutrition() < 30)
                && !SettlementSeasons.forageBlocked(entity)
                && !entity.isInWaterOrBubble() && EventHooks.canEntityGrief(level(), entity);
    }
    @Override public boolean requiresUpdateEveryTick() { return true; }
    @Override public void tick() {
        elapsed++;
        if (!canContinueToUse()) { stop(); return; }
        if (target != null && (!ForageClaims.owns(level(), target, claim) || !edible(target))) {
            ForageClaims.release(claim); claim = null; target = null;
        }
        if (target == null) {
            scanFood();
            if (target != null) {
                GameplayLog.event(entity,"forage","food found target={} elapsed={}",target,elapsed);
                searchSpot = null; return;
            }
            if (++walkTicks >= 120 || searchSpot == null || entity.getNavigation().isDone()) {
                if (elapsed % 10 != 1) return;
                searchSpot = null; walkTicks = 0;
                for (int attempt = 0; attempt < 3; attempt++) {
                    var point = LandRandomPos.getPos(entity,TravelRange.search(entity,12),3);
                    if (point == null || !TravelRange.babyDestination(entity,BlockPos.containing(point))
                            || !Reactions.Weather.allowsStep(entity,BlockPos.containing(point))
                            || !entity.family().allowsIdleWalk(point)) continue;
                    var candidate = BlockPos.containing(point);
                    if (entity.life().navigate(candidate,searchSpeed(),false)) { searchSpot = candidate; break; }
                }
                GameplayLog.limited(entity,"forage","search_path",200,
                        "search sweep destination={} moving={} nutrition={} rain={} elapsed={}",searchSpot,
                        !entity.getNavigation().isDone(),entity.life().nutrition(),level().isRainingAt(entity.blockPosition()),elapsed);
            }
            return;
        }
        if (entity.distanceToSqr(Vec3.atBottomCenterOf(target)) < 1) {
            // One bite takes the plant back one growth stage (Grazing); it grows again on game time, so the meadow lasts.
            if (ForageClaims.owns(level(), target, claim) && Grazing.edible(level().getBlockState(target))
                    && entity.life().beginMeal(new ItemStack(Items.SHORT_GRASS), null, false)) Grazing.graze(level(), target);
            stop();
        } else if (elapsed % 10 == 1 && !entity.life().navigate(target, searchSpeed(), false)) {
            GameplayLog.limited(entity,"forage","blocked_food",200,"food path blocked target={}",target);
            ForageClaims.release(claim); claim = null; target = null; scanAfter = level().getGameTime() + 40;
        }
    }
    @Override public void stop() {
        SearchBudget.cancel(level(),entity,Purpose.FORAGE,0);
        ForageClaims.release(claim); claim = null;
        running = false; searchSpot = null;
        target = null; entity.getNavigation().stop();
    }
}
