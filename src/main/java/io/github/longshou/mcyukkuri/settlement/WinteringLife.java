package io.github.longshou.mcyukkuri.settlement;

import io.github.longshou.mcyukkuri.human.Custody;
import io.github.longshou.mcyukkuri.entity.Behaviors;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.season.Season;
import java.util.EnumSet;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import io.github.longshou.mcyukkuri.rare.RareForms;

/** Optional winter sheltering, not hibernation. Owns no items and grants no physiological rewards. */
public final class WinteringLife extends Goal {
    private final YukkuriEntity entity;
    private BlockPos target;
    private boolean running, staying, mealRoute, yielded;
    private int elapsed, mealTicks, yieldLeft;
    private long nextStay, nextMeal;
    /** Mouth contact with the home's forage pile while taking a stored meal. */
    private final PileContact mealContact;

    public WinteringLife(YukkuriEntity entity) {
        this.entity = entity;
        this.mealContact = new PileContact(entity);
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }
    private ServerLevel level() { return (ServerLevel) entity.level(); }
    public static boolean winter(YukkuriEntity entity) {
        return SettlementSeasons.is(entity, Season.WINTER);
    }
    public boolean active() { return running; }
    public String activity() { return staying ? Behaviors.WINTER_STAY : Behaviors.WINTER_RETURN; }
    public boolean usesWinterStores() {
        return !entity.level().isClientSide && winter(entity) && !entity.profile().houseBody() && !entity.profile().dangerToYukkuri()
                && (Custody.view(entity).owner() == null);
    }
    private boolean migrating() {
        var group = entity.settlement().group();
        return group != null && group.migrateTo != null;
    }
    private boolean ready() {
        return !entity.level().isClientSide && entity.isAlive() && !entity.isNoAi()
                && !entity.profile().houseBody() && !entity.profile().dangerToYukkuri()
                && !entity.isPassenger() && !entity.isLeashed() && !entity.isPanicking() && !entity.isCommandPlaying()
                && !entity.operations().active() && !entity.shellRetraction().active()
                && !entity.family().active() && !entity.family().awaitingLanding()
                && !entity.isInWaterOrBubble() && !entity.isOnFire() && !entity.life().isSleeping()
                && entity.life().mealTicks() == 0 && entity.life().drugSleep() == 0 && entity.life().sprayDelay() == 0
                && !SettlementEvents.homeClaimHeld(entity) && !migrating() && !SettlementEvents.reserved(entity)
                && (Custody.view(entity).owner() == null);
    }
    public ShelterBlockEntity stockedHome() {
        if (!usesWinterStores()) return null;
        var home = entity.habitat().shelter();
        return home != null && home.owns(entity.getUUID()) && home.usable(entity) && home.hasFoodFor(entity) ? home : null;
    }
    private boolean reached(BlockPos bed) {
        return bed != null && entity.distanceToSqr(Vec3.atBottomCenterOf(bed)) < .64
                && entity.life().dryStand(entity.blockPosition());
    }
    private boolean comfortable() { return !entity.life().temperature().unsafeSleep(); }
    private boolean stayReady() {
        return ready() && !entity.life().hasUrgentNeed() && !entity.life().needsFood()
                && entity.life().dryness() < 40 && entity.life().bladder() < 70 && entity.life().spicyTicks() == 0
                && !entity.independence().householdOuting() && entity.habitat().carrying().isEmpty()
                && entity.settlement().duties().carrying().isEmpty() && comfortable();
    }
    private boolean warmHome(ShelterBlockEntity home) {
        return home != null && home.insulated() && entity.life().temperature().comfortableAt(home.bed(entity.getUUID()));
    }
    @Override public boolean canUse() {
        if (level().getGameTime() < nextStay || !stayReady() || RareForms.winterActive(entity)) return false;
        nextStay = level().getGameTime() + 100;
        var home = stockedHome();
        if (!warmHome(home)) return false;
        var bed = home.bed(entity.getUUID());
        if (!reached(bed) && !entity.life().reachable(bed, false)) return false;
        target = bed.immutable(); return true;
    }
    @Override public void start() { running = true; staying = false; elapsed = 0; }
    @Override public boolean canContinueToUse() {
        if (!running || elapsed >= 400 || !stayReady()) return false;
        var home = stockedHome();
        return home != null && target.equals(home.bed(entity.getUUID())) && home.insulated()
                && (elapsed % 20 != 0 || warmHome(home));
    }
    @Override public boolean requiresUpdateEveryTick() { return true; }
    @Override public void tick() {
        if (!canContinueToUse()) { stop(); return; }
        elapsed++;
        staying = reached(target);
        if (staying) entity.getNavigation().stop();
        else if (elapsed % 20 == 1 && !entity.life().navigate(target, 1, false)) stop();
    }
    @Override public void stop() {
        running = staying = false; target = null; elapsed = 0;
        nextStay = level().getGameTime() + 200;
        entity.getNavigation().stop();
    }

    /** Called by the higher-priority life goal, after drink/toilet/thermal and family checks. */
    public boolean planMeal() {
        if (level().getGameTime() < nextMeal || !ready() || !entity.life().canEat()
                || entity.independence().householdOuting() && !entity.life().hasUrgentNeed()) return false;
        var home = stockedHome();
        if (home == null) return false;
        var pile = home.pile();
        if (pile == null || !mealContact.near(pile) && !entity.life().reachable(pile.standCell(), false)) { nextMeal = level().getGameTime() + 100; return false; }
        mealRoute = true; mealTicks = 0; mealContact.reset(); return true;
    }
    public boolean continueMeal() {
        return mealRoute && mealTicks < 240 && ready() && entity.life().canEat() && stockedHome() != null
                && (!entity.independence().householdOuting() || entity.life().hasUrgentNeed());
    }
    public boolean tickMeal() {
        if (!continueMeal()) return false;
        var home = stockedHome(); var pile = home.pile(); mealTicks++;
        if (pile == null) return false;
        // The stock is a pile outside the sleeping place: walk to its feeding side and touch it with the mouth first.
        if (!mealContact.near(pile)) {
            mealContact.reset();
            return mealTicks % 10 != 1 || entity.life().navigate(pile.standCell(), 1.15, false);
        }
        if (!mealContact.tick(pile)) return true;
        entity.getNavigation().stop();
        ItemStack portion = home.foodFor(entity);
        boolean priority = entity.life().nutrition() >= 30 && priorityResident(home, portion);
        if (!yielded && priority) { yielded = true; yieldLeft = 40; }
        if (priority && yieldLeft > 0) { yieldLeft--; return true; }
        if (home.beginStoredMeal(entity)) { yielded = false; yieldLeft = 0; }
        return false;
    }
    private boolean priorityResident(ShelterBlockEntity home, ItemStack portion) {
        return !level().getEntitiesOfClass(YukkuriEntity.class, home.interior().inflate(1), other ->
                other != entity && other.isAlive() && !other.isNoAi() && !other.isPassenger()
                        && home.owns(other.getUUID()) && other.habitat().shelter() == home && home.usable(other)
                        && other.distanceToSqr(Vec3.atBottomCenterOf(home.bed(other.getUUID()))) < .64
                        && entity.life().canRecognize(other) && entity.hasLineOfSight(other)
                        && other.life().canEat() && other.life().acceptsFood(portion) && !other.life().isSleeping()
                        && !other.isOnFire() && !other.isInWaterOrBubble() && !other.operations().active()
                        && !other.isLeashed() && !other.shellRetraction().active() && other.life().drugSleep() == 0 && other.life().sprayDelay() == 0
                        && !SettlementEvents.reserved(other)
                        && !other.family().active() && !other.family().awaitingLanding() && !other.independence().householdOuting()
                        && !other.life().temperature().unsafeSleep() && other.life().dryness() < 40 && other.life().bladder() < 70 && other.life().spicyTicks() == 0
                        && (other.life().nutrition() + 10 < entity.life().nutrition()
                            || entity.family().adult() && !other.family().adult() && other.life().nutrition() <= entity.life().nutrition()))
                .isEmpty();
    }
    public void stopMeal() { if (mealRoute) nextMeal = level().getGameTime() + 40; mealRoute = false; mealTicks = 0; mealContact.reset(); }
    public CompoundTag save() {
        var tag = new CompoundTag(); tag.putBoolean("Yielded", yielded); tag.putInt("YieldLeft", yieldLeft); return tag;
    }
    public void load(CompoundTag tag) {
        yielded = tag.getBoolean("Yielded"); yieldLeft = yielded ? Math.clamp(tag.getInt("YieldLeft"), 0, 40) : 0;
        mealRoute = running = staying = false; target = null; elapsed = mealTicks = 0; nextStay = nextMeal = 0;
    }
    public Component observation() {
        String state = !usesWinterStores() ? "inactive" : mealRoute ? "meal" : running ? staying ? "staying" : "returning" : "available";
        var home = entity.habitat().shelter();
        return Component.translatable("notebook.mcyukkuri.winter", Component.translatable("winter.mcyukkuri." + state), home == null ? 0 : home.food());
    }
}
