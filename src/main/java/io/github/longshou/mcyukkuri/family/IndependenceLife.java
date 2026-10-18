package io.github.longshou.mcyukkuri.family;

import io.github.longshou.mcyukkuri.entity.Behaviors;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.human.HumanCommerce;
import io.github.longshou.mcyukkuri.life.SurfaceApproach;
import io.github.longshou.mcyukkuri.life.YukkuriWorldData;
import io.github.longshou.mcyukkuri.settlement.SettlementData;
import io.github.longshou.mcyukkuri.settlement.SettlementSociety;
import io.github.longshou.mcyukkuri.settlement.ShelterBlockEntity;
import io.github.longshou.mcyukkuri.social.Bonds;
import io.github.longshou.mcyukkuri.social.Temperament;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/**
 * A grown child leaves its parents (project design). When it is old and fit enough, and the day suits it, it walks to a parent,
 * the two exchange a short farewell (the child's "yukkuri shite itte ne" and the parent's glad answer), and the child
 * then simply becomes a vagrant looking for a new nest of its own: its bed in the parents' home is released, its
 * kinship and affinity are kept. Finding or digging the new home belongs to {@code HabitatLife}; nothing here builds.
 * The farewell never takes control of the parent, so a parent can neither be stuck by it nor block it: a child that
 * cannot reach a parent within {@link #FAREWELL_LIMIT} ticks leaves anyway.
 */
public final class IndependenceLife extends Goal {
    private enum Task { NONE, FAREWELL }
    private static final int CHECK_INTERVAL = 1200, FAREWELL_LIMIT = 600, CONTACT_TICKS = 100, PARENT_RANGE = 24, WAITS_FOR_PARENT = 8;
    private final YukkuriEntity entity;
    /** The parents' home that was left; the leaver never moves back into it. */
    private GlobalPos origin;
    private boolean independent, armed, household;
    private UUID visitor;
    private Task task = Task.NONE;
    private int wait = CHECK_INTERVAL, elapsed, contact, loadGrace, parentWaits, visits;
    private boolean running;
    private Vec3 target;
    private int stalled;
    private Vec3 progress;

    public IndependenceLife(YukkuriEntity entity) { this.entity = entity; setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK)); }
    private ServerLevel level() { return (ServerLevel)entity.level(); }
    public boolean active() { return running && task != Task.NONE; }
    /** Kept for callers of the old construction project; leaving no longer involves building. */
    public boolean building() { return false; }
    /** The child has left its parents. It stays true for life, whether it already has a new home or is still looking for one. */
    public boolean independent() { return independent; }
    public ItemStack carrying() { return ItemStack.EMPTY; }
    public int work() { return 0; }
    public int visits() { return visits; }
    public int rainLosses() { return 0; }
    public GlobalPos site() { return null; }
    public GlobalPos origin() { return origin; }
    public String activity() { return task == Task.FAREWELL ? Behaviors.INDEPENDENCE_FAREWELL : Behaviors.IDLE; }
    /** The old household chores no longer yield to a building project. */
    public boolean householdOuting() { return false; }
    /** True for the home this individual left: it does not move back into it. */
    public boolean avoids(BlockPos core) { return independent && origin != null && origin.dimension().equals(level().dimension()) && origin.pos().equals(core); }
    /** Scenario hook: the next daytime opportunity is taken, with no dice. */
    public void arm() { armed = true; wait = 0; }

    private boolean wild() {
        var human = HumanCommerce.data(entity);
        return !entity.getVariant().isHouse() && !entity.getVariant().isPredator()
                && !io.github.longshou.mcyukkuri.subspecies.HatVessel.aquatic(entity)
                && !human.hasUUID("Owner") && !human.contains("PendingRole") && !human.contains("FactoryStage");
    }
    /** Unknown/unloaded living children are conservatively treated as dependent. */
    private boolean hasDependents() {
        var data = FamilyData.get(level());
        if (data.busyParent(entity.getUUID())) return true;
        for (var id : data.children(entity.getUUID())) {
            var person = data.person(id);
            if (person == null || person.status == FamilyData.Status.DEAD || person.status == FamilyData.Status.REMOVED) continue;
            if (!(level().getEntity(id) instanceof YukkuriEntity child) || !child.family().adult()) return true;
        }
        return false;
    }
    /** Why the individual is not leaving yet ("ok" when it would); for the notebook and the playground. */
    public String why() {
        if (independent) return "left";
        if (!wild()) return "not_wild";
        if (!entity.childhood().canLiveIndependently()) return "not_capable";
        if (entity.family().elder()) return "elder";
        if (entity.family().partner() != null) return "partnered";
        if (entity.family().recovery() > 0) return "recovering";
        if (hasDependents()) return "dependents";
        var home = entity.habitat().shelter();
        if (home == null) return "no_home";
        if (!home.usable(entity)) return "bed_unusable";
        if (!(home.owns(entity.family().mother()) || home.owns(entity.family().father()))) return "parents_elsewhere";
        var group = entity.settlement().group(); var role = entity.settlement().society().record();
        if (group != null && (role == null || role.role != SettlementData.Role.MEMBER || role.rejections != 0)) return "disgrace";
        var block = block();
        if (block != null) return "not_ready:" + block;
        if (!member()) return "not_member";
        return "ok";
    }
    /** A capable late juvenile (or an adult still at home) that has a usable bed in a home its parent owns. */
    public boolean eligible() {
        if (independent || !wild() || !entity.childhood().canLiveIndependently() || entity.family().elder() || entity.family().partner() != null
                || entity.family().recovery() > 0 || hasDependents()) return false;
        var home = entity.habitat().shelter();
        var group = entity.settlement().group();
        var role = entity.settlement().society().record();
        // Do not escape lost-accessory consequences by leaving: a community member in disgrace stays where it is.
        if (group != null && (role == null || role.role != SettlementData.Role.MEMBER || role.rejections != 0)) return false;
        return home != null && home.usable(entity) && (home.owns(entity.family().mother()) || home.owns(entity.family().father()));
    }
    /** A family without a community record is fine; a community member must be in good standing. */
    private boolean member() { return entity.settlement().group() == null || SettlementSociety.accepted(entity); }
    private boolean ready() { return block() == null; }
    /** The first thing that keeps the individual from walking off right now, or null. */
    private String block() {
        if (!wild()) return "wild";
        if (!entity.isAlive() || entity.isNoAi()) return "alive";
        if (entity.isPassenger() || entity.isLeashed()) return "carried";
        if (entity.isPanicking() || entity.isCommandPlaying() || entity.operations().active()) return "busy";
        if (entity.family().active() || entity.family().recovery() != 0) return "family";
        if (entity.life().hasUrgentNeed()) return "urgent_need";
        if (entity.life().isSleeping()) return "sleeping";
        if (entity.life().mealTicks() != 0) return "eating";
        if (entity.life().nutrition() < 45) return "hungry";
        if (entity.life().energy() < 40) return "tired";
        if (entity.life().dryness() >= 40) return "thirsty";
        if (entity.isInWaterOrBubble() || entity.isOnFire() || entity.shellRetraction().active()) return "hazard";
        if (io.github.longshou.mcyukkuri.event.WildEvents.reserved(entity)) return "wild_event";
        if (!entity.habitat().carrying().isEmpty() || !entity.settlement().duties().carrying().isEmpty()) return "carrying";
        return null;
    }
    /** Loaded-time clock only; a paused or captured individual never leaves offline. */
    public void clock() {
        if (entity.level().isClientSide || entity.isNoAi()) return;
        if (loadGrace > 0) { loadGrace--; return; }
        if (wait > 0) wait--;
    }
    public int departureChance() {
        var home = entity.habitat().shelter();
        if (home == null) return 0;
        // Original balancing, not a density inferred from EH11's panels: a crowded home pushes its grown young out sooner.
        int chance = home.crowded() ? 30 : 10;
        if (entity.life().personality().attachment() >= Temperament.ATTACHMENT_AT_65) chance -= 5;
        return chance;
    }
    @Override public boolean canUse() {
        if (independent || loadGrace > 0 || wait > 0 || !ready() || !member()) return false;
        wait = armed ? 100 : CHECK_INTERVAL;
        if (!eligible()) return false;
        // Departures are by day. A night-time roll is not spent: look again soon, not a full interval later.
        if (level().isNight()) { wait = 200; return false; }
        if (!armed && entity.getRandom().nextInt(100) >= departureChance()) return false;
        var parent = parentNearby();
        if (parent == null) {
            // Wants to say goodbye: waits a while for a parent to come home, then goes without.
            if (++parentWaits <= WAITS_FOR_PARENT) { wait = 200; return false; }
            leave();
            return false;
        }
        visitor = parent.getUUID(); task = Task.FAREWELL; return true;
    }
    @Override public void start() { running = true; elapsed = contact = 0; target = progress = null; stalled = 0; }
    @Override public boolean requiresUpdateEveryTick() { return true; }
    /** Once on its way the farewell only gives way to real danger or need; picking up a bite of grass or a mild hunger do not call it off. */
    private boolean steady() {
        return wild() && entity.isAlive() && !entity.isNoAi() && !entity.isPassenger() && !entity.isLeashed() && !entity.isPanicking()
                && !entity.isCommandPlaying() && !entity.operations().active() && !entity.life().hasUrgentNeed() && !entity.life().isSleeping()
                && !entity.isInWaterOrBubble() && !entity.isOnFire() && !entity.shellRetraction().active();
    }
    @Override public boolean canContinueToUse() { return active() && steady() && elapsed < FAREWELL_LIMIT + CONTACT_TICKS && !independent; }
    @Override public void tick() {
        if (!canContinueToUse()) { stop(); return; }
        elapsed++;
        if (!(level().getEntity(visitor) instanceof YukkuriEntity parent) || !parent.isAlive() || parent.isPanicking() || parent.isCommandPlaying()) {
            leave(); stop(); return;
        }
        var stand = contactStand(parent);
        if (contact == 0 && (stand == null || elapsed > FAREWELL_LIMIT)) { leave(); stop(); return; }
        if (contact == 0 && !SurfaceApproach.arrived(entity, stand)) { approach(stand); return; }
        SurfaceApproach.halt(entity);
        entity.getLookControl().setLookAt(parent, 25, 25);
        if (!parent.life().isSleeping()) parent.getLookControl().setLookAt(entity, 25, 25);
        // The little show: the child bids its parent yukkuri-shite-itte-ne; the parent answers gladly a moment later.
        if (contact == 0) entity.showReaction("happy", "hello", 90);
        if (contact == 30 && !parent.life().isSleeping()) parent.showReactionDelayed("happy", "gentle", 80, 12);
        if (++contact >= CONTACT_TICKS) { leave(); stop(); }
    }
    private YukkuriEntity parentNearby() {
        var home = entity.habitat().shelter();
        if (home == null) return null;
        return level().getEntitiesOfClass(YukkuriEntity.class, entity.getBoundingBox().inflate(PARENT_RANGE), other -> other != entity && other.isAlive()
                        && (Bonds.parentOf(other, entity))
                        && !other.isPanicking() && !other.isCommandPlaying() && !other.operations().active() && !other.family().active()
                        && !other.life().isSleeping() && !other.life().hasUrgentNeed() && home.owns(other.getUUID())
                        && entity.life().canRecognize(other) && other.life().canRecognize(entity))
                .stream().filter(other -> entity.life().reachable(other.blockPosition(), false))
                .min(Comparator.comparingDouble(entity::distanceToSqr)).orElse(null);
    }
    /** The child lets go of its place in the parents' home and becomes a vagrant. Kinship and affinity stay. */
    private void leave() {
        var home = entity.habitat().shelter();
        if (home != null) origin = GlobalPos.of(level().dimension(), home.getBlockPos());
        independent = true; armed = false; household = false; visits++;
        // Keep a little extra fondness for the parents they just said goodbye to.
        var relations = YukkuriWorldData.get(level());
        for (var parent : new UUID[]{entity.family().mother(), entity.family().father()}) {
            if (parent == null) continue;
            relations.relate(entity.getUUID(), parent, 2); relations.relate(parent, entity.getUUID(), 2);
        }
        entity.habitat().release();
        entity.life().releaseHome();
        entity.setPersistenceRequired();
    }
    /** Called by the habitat when the leaver has taken a home of its own: a loose family group gets a household of its own. */
    public void settled(ShelterBlockEntity home) {
        if (!independent || household || home == null) return;
        var group = entity.settlement().group();
        household = true;
        if (group != null && group.kind != SettlementData.Kind.FAMILY) return;   // a village keeps its member in the village
        if (home.owns(entity.getUUID()) && home.usable(entity) && (group == null || !group.center.equals(home.getBlockPos())))
            entity.settlement().establishHousehold(home);
    }
    private Vec3 contactStand(YukkuriEntity other) {
        var candidates = new ArrayList<Vec3>();
        double reach = (entity.getBbWidth() + other.getBbWidth()) * .5 + .15;
        for (var direction : Direction.Plane.HORIZONTAL)
            candidates.add(other.position().add(direction.getStepX() * reach, 0, direction.getStepZ() * reach));
        candidates.sort(Comparator.comparingDouble(entity::distanceToSqr));
        for (var stand : candidates) if (SurfaceApproach.supported(entity, stand) && entity.life().reachable(BlockPos.containing(stand), false)) return stand;
        return null;
    }
    private void approach(Vec3 destination) {
        if (target == null || target.distanceToSqr(destination) > .0025) { target = destination; progress = entity.position(); stalled = 0; }
        if (progress == null || entity.position().distanceToSqr(progress) > .0036) { progress = entity.position(); stalled = 0; } else stalled++;
        if (stalled >= 120) { elapsed = FAREWELL_LIMIT; return; }
        if (SurfaceApproach.clearLastStep(entity, destination)) { SurfaceApproach.walk(entity, destination, .8); return; }
        if (entity.getNavigation().isDone() || entity.tickCount % 20 == 0) entity.life().navigate(BlockPos.containing(destination), .8, false);
    }
    @Override public void stop() {
        // A farewell that was already heard by the parent is not taken back by an interruption: the leaving goes ahead.
        if (task == Task.FAREWELL && contact >= 30 && !independent) leave();
        task = Task.NONE; running = false; visitor = null; elapsed = contact = 0; wait = 40; target = progress = null; stalled = 0;
        SurfaceApproach.halt(entity);
    }
    public void dropCarrying() {}
    /** Nothing is held across a departure any more; kept for the entity's death and reload hooks. */
    public void abandon() { stop(); wait = CHECK_INTERVAL; }
    public Component observation() {
        String status = independent ? household ? "independent" : "vagrant" : task == Task.FAREWELL ? "farewell" : eligible() ? "eligible" : "family";
        var shelter = entity.habitat().shelter();
        return Component.translatable("notebook.mcyukkuri.independence", Component.translatable("independence.mcyukkuri." + status),
                origin == null ? "—" : origin.pos().toShortString(),
                shelter == null ? "—" : shelter.getBlockPos().toShortString(), entity.habitat().digProgress());
    }
    public CompoundTag save() {
        var tag = new CompoundTag(); io.github.longshou.mcyukkuri.family.FamilyData.putPosition(tag, "Origin", origin);
        tag.putString("Why", why()); tag.putBoolean("Force", armed); tag.putBoolean("Independent", independent); tag.putBoolean("Household", household); tag.putInt("Wait", wait); tag.putInt("Visits", visits);
        return tag;
    }
    public void load(CompoundTag tag) {
        origin = FamilyData.position(tag, "Origin");
        // Saves of the old construction project that had finished a new home count as left; unfinished projects are dropped.
        independent = tag.getBoolean("Independent") || tag.contains("EstablishedHome");
        household = tag.getBoolean("Household") || tag.contains("EstablishedHome");
        armed = tag.getBoolean("Force");
        wait = tag.contains("Wait") ? Math.clamp(tag.getInt("Wait"), 0, CHECK_INTERVAL) : CHECK_INTERVAL;
        if (armed) wait = 0;
        visits = Math.max(0, tag.getInt("Visits"));
        task = Task.NONE; running = false; visitor = null; elapsed = contact = 0; loadGrace = 100; parentWaits = 0;
        target = progress = null; stalled = 0;
    }
}
