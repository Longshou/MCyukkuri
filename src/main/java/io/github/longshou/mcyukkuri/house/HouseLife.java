package io.github.longshou.mcyukkuri.house;

import io.github.longshou.mcyukkuri.life.EcologyRoutine;
import io.github.longshou.mcyukkuri.life.EcologyRoutines;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import io.github.longshou.mcyukkuri.body.MouthPlacement;
import io.github.longshou.mcyukkuri.entity.Behaviors;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.life.TravelRange;
import io.github.longshou.mcyukkuri.family.FamilyData;
import io.github.longshou.mcyukkuri.interaction.ItemContact;
import io.github.longshou.mcyukkuri.registry.ModItems;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.EventHooks;
import java.util.Locale;
import net.minecraft.world.entity.Entity;

/**
 * Loaded-time life of one house yukkuri. Residents shelter in a nest of lint by day, come out when no person is near,
 * and by night (or when rested and unobserved) gather the traces people leave on the floor: lint to line the nest and
 * crumbs to stock it. A newcomer without a nest first carries lint to its hidden corner and founds the nest there.
 * Further jobs are eating, tending eggs, moving a clutch out of a spoiled nest, hiding from people and patrolling
 * walls when no debris is in sight. Everything carried is a real stack on the entity; every step is a visible phase.
 */
public final class HouseLife implements EcologyRoutine {
    public static final int MAX_SOURCE_PATH_CHECKS = 6;
    /** A resident that carries something for this long without delivering it puts it down. */
    private static final int CARRY_LIMIT = 2400;
    /** People closer than this (a crouching person counts as farther) send a resident indoors; farther than QUIET lets it roam by day. */
    private static final double SHY = 3.5, QUIET = 8;
    private static final int LOCATE_RADIUS = 8, SCOUT_RADIUS = 14, MAX_SCOUTS = 3;
    private enum Phase { NONE, APPROACH, ENTER, REST, LEAVE, PEEK, TEND, LISTEN, LIFT, STARTLED, SEARCH, SCOUT, SNIFF, HIDE, SHAPE }
    private enum Stat { PICKED, DELIVERED, FOUNDED, BUILT, STASHED, ATE, TENDED, MOVED, HIDDEN, SCOUTED }

    private final YukkuriEntity entity;
    private final HouseNavigation movement, exitMovement;
    private GlobalPos nest;
    private UUID nestIdentity;
    private HouseholdEcology.Den den;
    private ItemStack carrying = ItemStack.EMPTY;
    private SupplyPileBlockEntity supplyTarget;
    private int wait, ticks, careWait, sleepTicks, startledTicks, lostTicks;
    private boolean active, tending, rested, careContact, feeding, lifting, hurry, denRest;
    private Phase phase = Phase.NONE;
    private int interaction, interactionTicks;
    private int sourceCursor;
    private Vec3 contactTarget, restPosition;
    private BlockPos refuge, scoutTarget;
    private boolean crossedEntrance, exiting;
    private int exitRetryAt, carryAge, restCheckAt, scoutAt, scouts, hideUntil, peekAt, denCheckAt;
    private double humanDistance = 99;
    private boolean lastDeliveredLint;
    private GlobalPos eggTarget;
    private UUID eggTargetIdentity;
    private final int[] stats = new int[Stat.values().length];

    public HouseLife(YukkuriEntity entity) {
        this.entity = entity; movement = new HouseNavigation(entity); exitMovement = new HouseNavigation(entity);
    }

    // ------------------------------------------------------------------------------------------------ home
    public ItemStack carrying() { return carrying; }
    public BlockPos nestPosition() { return nest != null && nest.dimension().equals(entity.level().dimension()) ? nest.pos() : null; }
    public HouseNestBlockEntity nest() {
        BlockPos pos = nestPosition();
        if (pos == null || !entity.level().hasChunkAt(pos)
                || !(entity.level().getBlockEntity(pos) instanceof HouseNestBlockEntity value)) return null;
        return nestIdentity == null || nestIdentity.equals(value.identity()) ? value : null;
    }
    public boolean choose(HouseNestBlockEntity value) {
        if (!EcologyRoutines.house(entity) || !value.usable(entity) || !entity.life().reachable(value.entrance(), false)
                || entity.level() instanceof ServerLevel level && HouseholdProtection.protectedAt(level,value.getBlockPos())) return false;
        var previous=nest;
        nest = GlobalPos.of(entity.level().dimension(), value.getBlockPos()); nestIdentity = value.identity(); den = null;
        if(!nest.equals(previous))GameplayLog.event(entity,"house","home chosen old={} home={} identity={}",previous,nest,nestIdentity);
        lostTicks = 0; refuge = null; entity.setPersistenceRequired(); return true;
    }
    /** A newcomer knows the hidden corner it came to, but has no nest until it brings its first lint. */
    public void foundAt(HouseholdEcology.Den corner) {
        nest = null; nestIdentity = null; den = corner; lostTicks = 0; entity.setPersistenceRequired(); stop();
    }
    public HouseholdEcology.Den den() { return den; }
    /** Neither a nest nor a corner to settle in (for example outdoors): the ordinary idle wandering applies. */
    public boolean homeless() { return nest == null && den == null; }
    private BlockPos entranceCell() {
        var value = nest();
        if (value != null) return value.entrance();
        return den != null ? den.site().relative(den.entrance()) : null;
    }
    private boolean founding() { return nest() == null && den != null; }
    /** Where this resident rests: its nest entrance, or the entrance of the corner it has settled in. */
    public BlockPos homeStand() {
        var value = nest();
        if (value != null) return value.usable(entity) ? value.entrance() : null;
        return den != null ? den.site().relative(den.entrance()) : null;
    }
    public boolean active() { return active; }
    public boolean sleeping() { return active && sleepTicks >= 20 && (phase == Phase.REST && physicallyInside() || phase == Phase.HIDE && denRest); }
    public boolean wantsCrawl() {
        return active && interaction == 0 && (phase == Phase.ENTER || phase == Phase.LEAVE || phase == Phase.LIFT
                || phase == Phase.APPROACH && nest() != null
                && entity.distanceToSqr(Vec3.atBottomCenterOf(nest().entrance())) < 2.25
                || carrying.isEmpty() && sourceAvailable() && entity.distanceToSqr(sourcePosition()) < .36);
    }
    private boolean physicallyInside() {
        var value = nest();
        if (value == null) return false;
        var local = value.toLocal(entity.position());
        return entity.onGround() && entity.blockPosition().equals(value.getBlockPos())
                && local.y >= 0 && local.y < .3 && Math.abs(local.x - .5) < .3 && local.z > .02 && local.z < .82;
    }
    public boolean mustExit() {
        if (entity.tickCount < exitRetryAt || !entity.isAlive() || entity.isNoAi() || entity.isPassenger()
                || entity.isCommandPlaying() || entity.operations().active()) return false;
        var value = nest(); if (value == null) return false;
        if (exiting) return entity.distanceToSqr(Vec3.atBottomCenterOf(value.entrance())) >= .04;
        return entity.blockPosition().equals(value.getBlockPos())
                && (entity.isPanicking() || entity.isOnFire() || entity.isInWaterOrBubble()
                || !active && startledTicks == 0 && !entity.life().isSleeping() && entity.life().mealTicks() == 0);
    }
    public void tickExit() {
        var value = nest(); if (value == null) return;
        if (!exiting) { movement.stop(); exiting = true; }
        var exit = value.toLocal(entity.position()).z > .16
                ? value.localPosition(.5, 0, .12) : Vec3.atBottomCenterOf(value.entrance());
        if (!exitMovement.moveTo(exit, 1.15)) exitRetryAt = entity.tickCount + 60;
    }
    public void stopExit() { exitMovement.stop(); exiting = false; }

    // ------------------------------------------------------------------------------------------------ observable state
    public String activity() {
        if (interaction > 0) return interaction == 1 ? Behaviors.HOUSE_PICKUP : Behaviors.HOUSE_DEPOSIT;
        return switch (phase) {
            case APPROACH -> tending ? Behaviors.HOUSE_TEND : lifting ? Behaviors.HOUSE_CARRY : Behaviors.HOUSE_HIDE;
            case ENTER -> Behaviors.HOUSE_ENTER;
            case REST -> sleeping() ? Behaviors.HOUSE_SLEEP : Behaviors.HOUSE_HIDE;
            case LEAVE -> Behaviors.HOUSE_EXIT;
            case PEEK -> Behaviors.HOUSE_PEEK;
            case TEND, LIFT -> Behaviors.HOUSE_TEND;
            case LISTEN -> Behaviors.HOUSE_LISTEN;
            case STARTLED -> Behaviors.HOUSE_STARTLED;
            case SEARCH -> Behaviors.HOUSE_SEARCH_HOME;
            case SCOUT -> Behaviors.HOUSE_SCAVENGE;
            case SNIFF -> Behaviors.HOUSE_SNIFF;
            case HIDE -> Behaviors.HOUSE_HIDE;
            case SHAPE -> Behaviors.HOUSE_SHAPE;
            default -> carrying.isEmpty() ? Behaviors.HOUSE_SCAVENGE : Behaviors.HOUSE_CARRY;
        };
    }
    /** Short machine-readable stage, for the operator tools and the saved entity data. */
    public String stage() {
        if (!active) return sleeping() ? "sleep" : "idle";
        if (interaction > 0) return switch (interaction) { case 1 -> "pickup"; case 2 -> founding() ? "found_nest" : "deposit"; case 4 -> "place_egg"; default -> "contact"; };
        return switch (phase) {
            case NONE -> eggTarget != null ? carrying.isEmpty() ? "to_clutch" : "carry_egg"
                    : feeding ? "feed" : !carrying.isEmpty() ? "carry" : sourceAvailable() ? "to_source" : "outing";
            case APPROACH -> hurry ? "hide_home" : "to_nest";
            case HIDE -> denRest ? "den_rest" : "hide";
            default -> phase.name().toLowerCase(Locale.ROOT);
        };
    }
    public String describe() {
        return "stage=" + stage() + " nest=" + (nest == null ? "-" : nest.pos().toShortString()) + " den=" + (den == null ? "-" : den.site().toShortString())
                + " carry=" + (carrying.isEmpty() ? "-" : carrying.getHoverName().getString()) + " human=" + Math.round(humanDistance * 10) / 10.0
                + " stats=" + statsText();
    }
    private String statsText() {
        var text = new StringBuilder();
        for (var stat : Stat.values()) if (stats[stat.ordinal()] > 0) text.append(stat.name().toLowerCase(Locale.ROOT)).append('=').append(stats[stat.ordinal()]).append(',');
        return text.isEmpty() ? "-" : text.substring(0, text.length() - 1);
    }
    private void count(Stat stat) { stats[stat.ordinal()]++; }

    // ------------------------------------------------------------------------------------------------ passive tick
    public void tick() { EcologyRoutines.tick(this); }
    @Override public boolean supportsPassiveTick() {
        return !entity.level().isClientSide && EcologyRoutines.house(entity);
    }
    @Override public void passiveTick() {
        if (entity.isNoAi()) return;
        if (wait > 0) wait--;
        if (careWait > 0) careWait--;
        if (startledTicks > 0) startledTicks--;
        if (lostTicks > 0) lostTicks--;
        carryAge = carrying.isEmpty() ? 0 : carryAge + 1;
        // An abandoned carry (interrupted trips, nest lost or full) ends on the floor, never in limbo inside the mouth.
        if (carryAge > CARRY_LIMIT && !active && entity.isAlive()) { carryAge = 0; dropCarrying(); }
        if (entity.isPanicking() || entity.isOnFire() || entity.isInWaterOrBubble()) { stop(); return; }
        if (entity.tickCount % 10 == 0) senseHumans();
        if (entity.tickCount % 100 != 0) return;
        // Unloaded and other-dimension homes are memories, not evidence of destruction.
        if (nest != null && (!nest.dimension().equals(entity.level().dimension()) || !entity.level().hasChunkAt(nest.pos()))) return;
        var value = nest();
        if (value != null) {
            if (nestIdentity == null) nestIdentity = value.identity(); // Upgrade old coordinate-only saves.
            // A blocked entrance is not a destroyed home. Keep its identity and retry later.
            if (entity.life().covered(value.entrance())) return;
            // A roofless home still owns its inventory, but residents may find another shelter.
            lostTicks = Math.max(lostTicks, 200);
        }
        if (nest != null && value == null) disturb(true);
        if (den != null && !denValid()) den = null;
        if (nest == null) {
            HouseNestBlockEntity best = null; double score = Double.MAX_VALUE;
            for (BlockPos pos : BlockPos.betweenClosed(entity.blockPosition().offset(-8, -1, -8), entity.blockPosition().offset(8, 1, 8))) {
                if (!entity.level().hasChunkAt(pos) || !(entity.level().getBlockEntity(pos) instanceof HouseNestBlockEntity candidate)
                        || !candidate.usable(entity) || candidate.disturbedTicks() > 0
                        || HouseholdProtection.protectedAt((ServerLevel)entity.level(),pos)) continue;
                double candidateScore = entity.distanceToSqr(Vec3.atBottomCenterOf(candidate.entrance())) - (candidate.built() ? 4 : 0);
                if (candidateScore < score && entity.life().reachable(candidate.entrance(), false)) { best = candidate; score = candidateScore; }
            }
            if (best != null && choose(best)) { stop(); wait = 0; entity.showReaction("surprise", "house_arrival", 80); }
            else if (den == null && available()) seekDen();
        }
    }
    /** A homeless resident picks a hidden corner of its own room (walls on at least two sides) as the place to found a nest. */
    private void seekDen() {
        if (!(entity.level() instanceof ServerLevel level) || entity.tickCount < denCheckAt) return;
        denCheckAt = entity.tickCount + 400;
        for (var candidate : HouseholdEcology.dens(level, entity.blockPosition(), 10))
            if (entity.life().reachable(candidate.site().relative(candidate.entrance()), false)) {
                den = candidate; GameplayLog.event(entity,"house","corner chosen site={}",candidate.site());
                return;
            }
    }
    private boolean denValid() {
        return den != null && entity.level().hasChunkAt(den.site()) && entity.level().isEmptyBlock(den.site())
                && SupplyPiles.supported(entity.level(), den.site());
    }
    /** People: nearest player or villager that can see this resident (a crouching person counts as farther away). */
    private void senseHumans() {
        double best = 99;
        for (var player : entity.level().players()) {
            if (player.isSpectator() || player instanceof FakePlayer) continue;
            double distance = Math.sqrt(entity.distanceToSqr(player));
            if (distance > 12) continue;
            if (distance > 1.5 && !seenBy(player)) distance += 6;
            if (player.isCrouching()) distance *= 1.9;
            best = Math.min(best, distance);
        }
        for (var villager : entity.level().getEntitiesOfClass(Villager.class, entity.getBoundingBox().inflate(8), Villager::isAlive)) {
            double distance = Math.sqrt(entity.distanceToSqr(villager));
            if (distance > 1.5 && !seenBy(villager)) distance += 6;
            best = Math.min(best, distance);
        }
        humanDistance = best;
    }
    /** A tiny resident sees (and is seen by) a person if any of feet, waist or eyes is in the clear from its own height. */
    private boolean seenBy(Entity human) {
        var from = entity.position().add(0, .12, 0);
        for (double height : new double[]{.2, .9, human.getEyeHeight()}) {
            var to = human.position().add(0, height, 0);
            if (entity.level().clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, entity)).getType() == HitResult.Type.MISS) return true;
        }
        return false;
    }
    public double humanDistance() { return humanDistance; }
    private boolean humanTooClose() { return humanDistance < SHY; }

    private boolean available() {
        return EcologyRoutines.house(entity) && entity.isAlive() && !entity.isNoAi() && !entity.isPassenger()
                && !entity.isPanicking() && !entity.isCommandPlaying() && !entity.operations().active()
                && !entity.isInWaterOrBubble() && !entity.isOnFire() && entity.life().mealTicks() == 0
                && entity.life().drugSleep() == 0 && entity.life().sprayDelay() == 0
                && entity.life().wetness() <= 40 && !entity.family().awaitingLanding()
                && (entity.family().adult() || entity.tickCount >= 40);
    }
    private boolean mayTransfer() { return EventHooks.canEntityGrief((ServerLevel) entity.level(), entity); }
    private boolean daytime() { return !entity.level().isNight(); }
    /**
     * Working hours: all night, and by day only for a rested adult when nobody is around. A person close by always keeps
     * a resident indoors, so they appear when people do not notice them.
     */
    private boolean outingAllowed() {
        if (!entity.family().adult() || humanDistance < SHY + .5) return false;
        // Hunger sends a resident out whatever the hour, as long as it has the strength.
        if (entity.life().nutrition() < 45 && entity.life().energy() >= 25) return true;
        return entity.life().energy() >= 35 && (!daytime() || entity.life().energy() >= 70 && humanDistance >= QUIET);
    }
    private boolean needsTending(HouseNestBlockEntity value) {
        return entity.family().adult() && value.built() && careWait == 0 && (value.eggs() > 0 && value.careTicks() < 120 && broods(value) || value.dirt() >= 3 && value.canClean())
                && entity.life().nutrition() >= 40 && entity.life().energy() >= 25;
    }
    /** Eggs only warm while a parent sits with them: one parent broods (the one with the lowest id that is fit), the other forages. */
    private boolean broods(HouseNestBlockEntity value) {
        if (!entity.family().adult() || value.food() <= 0 || value.dirt() >= 6 || value.disturbedTicks() > 0) return false;
        for (var other : entity.level().getEntitiesOfClass(YukkuriEntity.class, new AABB(value.getBlockPos()).inflate(6),
                e -> e != entity && e.isAlive() && EcologyRoutines.house(e) && e.family().adult() && value.getBlockPos().equals(e.house().nestPosition())
                        && e.life().nutrition() >= 40 && e.life().energy() >= 25))
            if (other.getUUID().compareTo(entity.getUUID()) < 0) return false;
        return true;
    }
    /** The brooding parent stays with the eggs while the clutch is fed and clean and it is itself fed and rested. */
    private boolean keepsBrooding(HouseNestBlockEntity value) {
        return value.eggs() > 0 && broods(value) && entity.life().nutrition() >= 40 && entity.life().energy() >= 25;
    }
    /** The nest still wants lint or crumbs (the founder's missing nest wants lint first). */
    private boolean wantsMaterials(HouseNestBlockEntity value) {
        return value == null ? den != null : !value.built() || value.food() < HouseNestBlockEntity.FOOD_GOAL;
    }
    private boolean scoutAllowed() { return entity.tickCount >= scoutAt && entity.family().adult(); }

    // ------------------------------------------------------------------------------------------------ planning
    /** Called by the higher-priority life goal after executable thirst, toilet and thermal needs. */
    public boolean preferRoutine() {
        if (!available() || entity.family().active()) return false;
        return canUse();
    }
    public boolean canUse() {
        if (!available()) return false;
        if (active) return true;
        if (wait > 0) return false;
        wait = 20;
        if (startledTicks > 0) return start(Phase.STARTLED);
        var value = nest();
        boolean grief = mayTransfer();
        // Hunger overrides the schedule, including before a stray has found its first nest.
        if (entity.life().nutrition() < 30 && entity.life().canEat() && grief) {
            if (!carrying.isEmpty() && !SupplyPiles.crumb(carrying)) dropCarrying();
            if (carrying.isEmpty() && value != null && value.usable(entity) && value.food() > 0) return start(Phase.NONE);
            feeding = true;
            if (SupplyPiles.crumb(carrying)) return start(Phase.NONE);
            findSource(value);
            if (sourceAvailable()) return start(Phase.NONE);
            feeding = false;
        }
        if (value == null || !value.usable(entity)) return planWithoutNest(grief);
        if (value.disturbedTicks() > 0) return start(physicallyInside() ? Phase.LEAVE : Phase.SEARCH);
        if (carrying.is(ModItems.HOUSE_EGG.get()) && eggTarget == null) returnEgg();
        // A clutch in a spoiled nest is carried to a fit one.
        if (grief && relocationPlan(value)) return start(Phase.NONE);
        if (!carrying.isEmpty() && grief) {
            if (wantedResource(value, carrying)) return start(Phase.NONE);
            dropCarrying(); // Retry only when planning again; a full store must not prevent rest.
        }
        if (entity.life().canEat() && entity.life().nutrition() < 65 && value.food() > 0 && grief) return start(Phase.NONE);
        if (entity.life().nutrition() < 30) return false;
        tending = needsTending(value);
        if (tending) return enterHome();
        // Somebody is right there: go in and keep still.
        if (!physicallyInside() && humanTooClose()) { count(Stat.HIDDEN); hideUntil = entity.tickCount + 120; hurry = true; return enterHome(); }
        if (outingAllowed()) {
            if (carrying.isEmpty() && grief) {
                findSource(value);
                if (sourceAvailable() || wantsMaterials(value) && scoutAllowed()) {
                    // Everything starts at the door: leave the nest first, then look about.
                    if (physicallyInside()) { clearSource(); return start(Phase.LEAVE); }
                    if (sourceAvailable()) return start(Phase.NONE);
                    scoutTarget = null; return start(Phase.SCOUT);
                }
            }
            if (!physicallyInside() && entity.distanceToSqr(Vec3.atBottomCenterOf(value.entrance())) > 4) return enterHome();
        }
        return enterHome();
    }
    /** A resident without a usable nest: found one at its corner, or wait where it rests. */
    private boolean planWithoutNest(boolean grief) {
        if (den != null && denValid()) {
            if (outingAllowed() && grief) {
                if (!carrying.isEmpty()) {
                    if (SupplyPiles.lint(carrying)) return start(Phase.NONE);
                    dropCarrying();
                }
                findSource(null);
                if (sourceAvailable()) return start(Phase.NONE);
                if (scoutAllowed()) { scoutTarget = null; return start(Phase.SCOUT); }
            }
            return holdAtDen(); // nothing to fetch: sleep in its corner until the next round
        }
        if (lostTicks > 0 && entity.life().nutrition() >= 30 && entity.life().energy() >= 25) return start(Phase.SEARCH);
        // A carried item must remain real even when no reachable replacement home exists.
        if (nest == null && !carrying.isEmpty()) dropCarrying();
        return false;
    }
    /** A newcomer that has nothing to do rests in its corner, where it will found its nest. */
    private boolean holdAtDen() {
        denRest = true; hideUntil = 0; hurry = false; sleepTicks = 0;
        return start(Phase.HIDE);
    }
    private boolean wantedResource(HouseNestBlockEntity value, ItemStack item) {
        if (feeding) return SupplyPiles.crumb(item);
        if (item.is(ModItems.HOUSE_EGG.get())) return eggTarget != null;
        if (SupplyPiles.crumb(item)) return value != null && value.food() < HouseNestBlockEntity.FOOD_GOAL;
        if (SupplyPiles.lint(item)) return value == null ? den != null : !value.built();
        return false;
    }
    private boolean wantedResource(HouseNestBlockEntity value, SupplyPileBlockEntity pile) {
        if (feeding) return pile.crumb();
        if (pile.crumb()) return value != null && value.food() < HouseNestBlockEntity.FOOD_GOAL;
        if (pile.lint()) return value == null ? den != null : !value.built();
        return false;
    }
    private record Source(SupplyPileBlockEntity pile, BlockPos pos, double distance) {}
    private void findSource(HouseNestBlockEntity value) { locate(value, LOCATE_RADIUS); }
    private void locate(HouseNestBlockEntity value, int radius) {
        radius = TravelRange.search(entity, radius);
        clearSource(); var candidates = new ArrayList<Source>();
        // Inspect real pile entries only in loaded chunks within the travel radius.
        for (var pile : new SupplyPileView((ServerLevel)entity.level()).nearby(entity.blockPosition(), radius, 1))
            if (wantedResource(value, pile))
                candidates.add(new Source(pile, pile.getBlockPos(), entity.distanceToSqr(pile.pickupPosition())));
        candidates.sort(Comparator.comparingDouble(Source::distance).thenComparingLong(source -> source.pos().asLong()));
        if (candidates.isEmpty()) { sourceCursor = 0; return; }
        int start = Math.floorMod(sourceCursor, candidates.size()), attempts = Math.min(MAX_SOURCE_PATH_CHECKS, candidates.size());
        for (int i = 0; i < attempts; i++) {
            var candidate = candidates.get((start + i) % candidates.size());
            if (!entity.life().reachable(candidate.pos(), false)) continue;
            supplyTarget = candidate.pile(); sourceCursor = 0; return;
        }
        // Unreachable nearer piles cannot hide later candidates forever, or trigger unlimited path searches.
        sourceCursor = (start + attempts) % candidates.size();
    }
    private boolean sourceAvailable() {
        // Keep the actual block entity reference: removal or replacement must cancel an in-flight pickup.
        return supplyTarget != null && !supplyTarget.isRemoved() && entity.level().hasChunkAt(supplyTarget.getBlockPos())
                && entity.level().getBlockEntity(supplyTarget.getBlockPos()) == supplyTarget && supplyTarget.count() > 0;
    }
    private ItemStack sourceContents() { return supplyTarget.contents(); }
    private boolean wantedSource(HouseNestBlockEntity value) { return wantedResource(value, supplyTarget); }
    private Vec3 sourcePosition() { return supplyTarget.pickupPosition(); }
    private boolean canSeeSource() {
        var hit = entity.level().clip(new ClipContext(entity.getEyePosition(), sourcePosition(),
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, entity));
        return hit.getType() == HitResult.Type.MISS || hit.getBlockPos().equals(supplyTarget.getBlockPos());
    }
    private ItemStack takeSource() { return supplyTarget.takeOne(); }
    private void clearSource() { supplyTarget = null; }
    private boolean enterHome() {
        restPosition = null; sleepTicks = 0; rested = false;
        return start(physicallyInside() ? tending ? Phase.TEND : Phase.REST : Phase.APPROACH);
    }
    private boolean start(Phase next) { active = true; phase(next); return true; }
    private void phase(Phase next) { movement.stop(); phase = next; ticks = 0; crossedEntrance = false; if (next == Phase.TEND) careContact = false; }
    public boolean canContinue() {
        if (!active || !available()) return false;
        if (phase == Phase.STARTLED || phase == Phase.SEARCH) return ticks < 160;
        if (phase == Phase.SCOUT) return ticks < 600 && entranceCell() != null;
        if (phase == Phase.SNIFF || phase == Phase.SHAPE) return ticks < 80;
        if (phase == Phase.HIDE) return ticks < (denRest ? 900 : 600);
        if (feeding) return ticks < travelLimit() && (sourceAvailable() || SupplyPiles.crumb(carrying));
        if (homeStand() == null) return false;
        if (phase == Phase.REST) return true;
        return ticks < travelLimit();
    }
    private int travelLimit() { return Math.max(400, (int)Math.ceil(1200 / Math.max(.25, entity.getScale() * entity.growthScale()))); }

    // ------------------------------------------------------------------------------------------------ the goal
    public void tickGoal() {
        if (!canContinue()) { stop(); return; }
        ticks++; var value = nest();
        if (phase == Phase.STARTLED) {
            entity.getNavigation().stop();
            if (startledTicks == 0) phase(value != null && physicallyInside() ? Phase.LEAVE : Phase.SEARCH);
            return;
        }
        if (phase == Phase.SEARCH) { seekRefuge(); return; }
        if (value == null && phase != Phase.NONE && phase != Phase.SCOUT && phase != Phase.SNIFF && phase != Phase.HIDE && phase != Phase.SHAPE) { stop(); return; }
        // Outside the nest, a person walking up sends the resident home at once.
        if (entity.tickCount % 5 == 0 && interaction == 0 && humanTooClose()
                && (phase == Phase.NONE || phase == Phase.SCOUT || phase == Phase.SNIFF || phase == Phase.PEEK || phase == Phase.SHAPE)) {
            hide(value); return;
        }
        if (interaction > 0) { tickInteraction(value); return; }
        if (phase != Phase.NONE) { tickPhase(value); return; }
        tickOuting(value);
    }
    private void hide(HouseNestBlockEntity value) {
        count(Stat.HIDDEN); movement.stop(); entity.getNavigation().stop();
        hideUntil = entity.tickCount + 120; hurry = true; scoutTarget = null; clearSource();
        entity.showReaction("surprise", "house_startled", 30);
        if (value != null && value.usable(entity)) { tending = false; restPosition = null; sleepTicks = 0; phase(Phase.APPROACH); }
        else { denRest = false; phase(Phase.HIDE); }
    }
    private void tickOuting(HouseNestBlockEntity value) {
        if (eggTarget != null) { tickRelocate(value); return; }
        if (feeding && SupplyPiles.crumb(carrying)) {
            if (entity.life().beginMeal(carrying, null, false)) { carrying = ItemStack.EMPTY; count(Stat.ATE); }
            stop(); return;
        }
        // Every outing crosses the remembered entrance before choosing an external path.
        if (value != null && entity.blockPosition().equals(value.getBlockPos())) {
            phase(Phase.LEAVE); tickPhase(value); return;
        }
        var entrance = entranceCell();
        if (!feeding && !carrying.isEmpty() && entrance != null && wantedResource(value, carrying)) {
            Vec3 spot = Vec3.atBottomCenterOf(entrance);
            if (inReach(spot) && canSeeContact(spot)) beginInteraction(2, spot);
            else approach(spot);
        } else if (value != null && entity.life().canEat() && entity.life().nutrition() < 65 && value.food() > 0 && mayTransfer()) {
            Vec3 spot = Vec3.atBottomCenterOf(value.entrance());
            if (inReach(spot) && canSeeContact(spot)) {
                ItemStack food = value.takeFood();
                if (!food.isEmpty() && !entity.life().beginMeal(food, null, false)) value.deposit(food);
                else if (!food.isEmpty()) count(Stat.ATE);
                stop();
            } else approach(spot);
        } else if (sourceAvailable()) {
            if (!wantedSource(value) || !mayTransfer()) { stop(); return; }
            if (inSourceStand(sourcePosition()) && canSeeSource()) beginInteraction(1, sourcePosition());
            else approachSource(sourcePosition());
        } else stop();
    }
    private Vec3 restingPlace(HouseNestBlockEntity value) {
        if (restPosition == null) {
            var residents = entity.level().getEntitiesOfClass(YukkuriEntity.class, new AABB(value.getBlockPos()).inflate(2),
                    other -> other != entity && other.isAlive() && other.house().nest() == value);
            // Reserve the back bed first so a sleeper at the entrance cannot block the next arrival.
            // Keep maximum-size (.324 wide) adults clear of both each other and the egg bed.
            Vec3 back = value.localPosition(.32, .025, .69);
            boolean backReserved = residents.stream().anyMatch(other -> other.house().restPosition != null
                    && other.house().restPosition.distanceToSqr(back) < .01);
            restPosition = backReserved ? value.localPosition(.32, .025, .31) : back;
        }
        return restPosition;
    }
    private void tickPhase(HouseNestBlockEntity value) {
        Vec3 outside = value == null ? null : Vec3.atBottomCenterOf(value.entrance());
        switch (phase) {
            case APPROACH -> {
                double distance = entity.distanceToSqr(outside);
                // Reach the accessible side of the corner before moving diagonally to the bedding.
                if (distance < .03 && entity.onGround()) phase(Phase.ENTER);
                else route(outside, hurry ? 1.25 : .8);
            }
            case ENTER -> {
                Vec3 threshold = value.localPosition(.5, 0, .12);
                if (!crossedEntrance) {
                    if (entity.distanceToSqr(threshold) < .0025) { crossedEntrance = true; movement.stop(); }
                    else { route(threshold, hurry ? .9 : .65); return; }
                }
                Vec3 inside = restingPlace(value);
                if (entity.distanceToSqr(inside) < .0025 && entity.onGround()) {
                    entity.getNavigation().stop(); hurry = false; phase(tending ? Phase.TEND : lifting ? Phase.LIFT : Phase.REST);
                } else route(inside, hurry ? .9 : .65);
            }
            case REST -> {
                entity.getNavigation().stop();
                if (!physicallyInside() || value.disturbedTicks() > 0) { finishRest(); phase(Phase.LEAVE); return; }
                if (needsTending(value)) { finishRest(); tending = true; phase(Phase.TEND); return; }
                if (sleepTicks >= entity.life().sleepDuration() && entity.tickCount >= restCheckAt) {
                    restCheckAt = entity.tickCount + 100;
                    if (entity.tickCount >= hideUntil) {
                        if (mayTransfer() && relocationPlan(value)) { finishRest(); lifting = true; phase(Phase.LIFT); return; }
                        // Executable thirst, toilet and thermal plans preempt this goal in YukkuriLife.
                        // An unavailable resource must not cause an endless leave/re-enter loop.
                        if (outingAllowed() && (entity.life().energy() >= 70 || entity.life().nutrition() < 45) && outingWork(value)) { finishRest(); phase(Phase.LEAVE); return; }
                        // Young ones look out for a moment now and then.
                        if (!entity.family().adult() && entity.life().energy() >= 60 && humanDistance >= QUIET && entity.tickCount >= peekAt) {
                            peekAt = entity.tickCount + 1200; finishRest(); phase(Phase.LEAVE); return;
                        }
                    }
                }
                sleepTicks++;
                if (sleepTicks == 20) {
                    entity.speak("sleep");
                    GameplayLog.event(entity,"sleep","begin source=house home={} energy={} nutrition={}",nest,entity.life().energy(),entity.life().nutrition());
                }
                if (sleepTicks >= 20) entity.life().restInHouse();
                if (sleepTicks >= entity.life().sleepDuration()) {
                    rested = true;
                    if(entity.life().energy() >= 95) { stop(); wait=120; return; }
                }
            }
            case TEND, LISTEN -> {
                if (!physicallyInside() || value.eggs() == 0 && value.dirt() == 0 && !careContact || value.disturbedTicks() > 0) {
                    careWait = 100; tending = false; phase(Phase.LEAVE); return;
                }
                entity.getNavigation().stop(); contactTarget = value.eggPosition(0); faceContact();
                if (phase == Phase.TEND && ticks == 26) {
                    careContact = value.tend(entity); careWait = careContact ? 120 : 100;
                    if (careContact) count(Stat.TENDED);
                }
                if (phase == Phase.TEND && ticks >= 40) {
                    if (careContact && value.eggs() > 0) { phase(Phase.LISTEN); if (value.progress() >= .75f) entity.showReaction("happy", "house_listen", 80); }
                    else { tending = false; phase(daytime() || !outingAllowed() ? Phase.REST : Phase.LEAVE); }
                } else if (phase == Phase.LISTEN && ticks >= 80) {
                    if (keepsBrooding(value)) { phase(Phase.TEND); return; } // stays with the eggs, tending again
                    tending = false; phase(daytime() || !outingAllowed() ? Phase.REST : Phase.LEAVE);
                }
            }
            case LIFT -> {
                // Lifts one egg out of a spoiled nest in the mouth; then carries it out.
                entity.getNavigation().stop(); contactTarget = value.eggPosition(0); faceContact();
                if (ticks == 26) {
                    var egg = value.lift(entity);
                    if (egg.isEmpty()) { lifting = false; eggTarget = null; careWait = 200; phase(Phase.LEAVE); return; }
                    carrying = egg; entity.showReaction("neutral", "house_pickup", 40);
                    GameplayLog.event(entity,"house","egg lifted source={} destination={} count={}",value.getBlockPos(),eggTarget,egg.getCount());
                }
                if (ticks >= 40) { lifting = false; phase(Phase.LEAVE); }
            }
            case LEAVE -> {
                finishRest();
                Vec3 threshold = value.localPosition(.5, 0, .12);
                if (!crossedEntrance) {
                    if (value.toLocal(entity.position()).z <= .16 || entity.distanceToSqr(threshold) < .0025) { crossedEntrance = true; movement.stop(); }
                    else { route(threshold, .65); return; }
                }
                if (entity.distanceToSqr(outside) < .04) {
                    if (!carrying.isEmpty() || eggTarget != null) phase(Phase.NONE);
                    else { phase(Phase.PEEK); entity.showReaction("neutral", "house_peek", 60); }
                } else route(outside, .65);
            }
            case PEEK -> {
                if (entity.distanceToSqr(outside) > .09) { route(outside, .8); return; }
                movement.stop();
                entity.getNavigation().stop();
                double side = Math.sin(ticks * .06) * 1.4;
                var look = value.localPosition(.5 + side, .1, -2.5);
                entity.getLookControl().setLookAt(look.x, look.y, look.z, 20, 20);
                if (ticks >= 60) { stop(); wait = 20; }
            }
            case SCOUT -> tickScout(value);
            case SNIFF -> tickSniff(value);
            case HIDE -> tickHide();
            case SHAPE -> {
                // Presses the new lint into the bedding with its body.
                movement.stop(); entity.getNavigation().stop();
                contactTarget = nestCentre(); faceContact();
                if (ticks >= 36) { stop(); wait = 20; }
            }
            default -> { }
        }
    }
    private Vec3 nestCentre() {
        var value = nest();
        return value != null ? Vec3.atBottomCenterOf(value.getBlockPos()) : den != null ? Vec3.atBottomCenterOf(den.site()) : entity.position().add(0, 0, 1);
    }
    private boolean outingWork(HouseNestBlockEntity value) {
        if (!carrying.isEmpty()) return true;
        if (value.usable(entity) && entity.life().canEat() && entity.life().nutrition() < 65 && value.food() > 0) return true;
        if (!wantsMaterials(value)) return false;
        findSource(value); boolean found = sourceAvailable(); clearSource();
        return found || scoutAllowed();
    }

    // ------------------------------------------------------------------------------------------------ patrol and hiding
    private void tickScout(HouseNestBlockEntity value) {
        if (scoutTarget == null) {
            scoutTarget = pickScoutCell();
            if (scoutTarget == null) { scouts = 0; scoutAt = entity.tickCount + 1200; stop(); wait = 60; return; }
        }
        Vec3 point = Vec3.atBottomCenterOf(scoutTarget);
        if (entity.distanceToSqr(point) < .09) { movement.stop(); phase(Phase.SNIFF); return; }
        if (!movement.moveTo(point, .7)) { scoutTarget = null; scouts++; if (scouts >= MAX_SCOUTS) { scouts = 0; scoutAt = entity.tickCount + 1200; stop(); wait = 60; } }
    }
    private BlockPos pickScoutCell() {
        var origin = nestPosition() != null ? nestPosition() : den != null ? den.site() : entity.blockPosition();
        if (!(entity.level() instanceof ServerLevel level)) return null;
        int tries = 0;
        for (var cell : HouseholdEcology.patrolCells(level, origin, SCOUT_RADIUS)) {
            if (tries++ >= 4) break;
            if (entity.life().reachable(cell, false)) return cell;
        }
        return null;
    }
    private void tickSniff(HouseNestBlockEntity value) {
        movement.stop(); entity.getNavigation().stop();
        double heading = Math.toRadians(entity.getYRot()) + Math.sin(ticks * .12) * .9;
        entity.getLookControl().setLookAt(entity.getX() - Math.sin(heading) * 2, entity.getEyeY() - .05, entity.getZ() + Math.cos(heading) * 2, 25, 25);
        if (ticks == 24 && mayTransfer()) {
            locate(value, LOCATE_RADIUS);
            if (sourceAvailable()) { scouts = 0; count(Stat.SCOUTED); entity.showReaction("happy", "house_pickup", 40); phase(Phase.NONE); return; }
        }
        if (ticks >= 50) {
            count(Stat.SCOUTED); scoutTarget = null; scouts++;
            if (scouts >= MAX_SCOUTS) { scouts = 0; scoutAt = entity.tickCount + 1800; stop(); wait = 60; }
            else phase(Phase.SCOUT);
        }
    }
    /** Without a nest to run to, a startled resident freezes in its corner. */
    private void tickHide() {
        var spot = entranceCell();
        if (spot != null && entity.distanceToSqr(Vec3.atBottomCenterOf(spot)) > .09) { route(Vec3.atBottomCenterOf(spot), 1.25); return; }
        movement.stop(); entity.getNavigation().stop(); hurry = false;
        if (denRest) {
            sleepTicks++;
            if (sleepTicks == 20) {
                entity.speak("sleep");
                GameplayLog.event(entity,"sleep","begin source=corner site={} energy={} nutrition={}",den==null?null:den.site(),entity.life().energy(),entity.life().nutrition());
            }
            if (sleepTicks >= 20) entity.life().restInHouse();
            if (sleepTicks >= entity.life().sleepDuration()) { stop(); wait = 60; }
            return;
        }
        if (entity.tickCount >= hideUntil && humanDistance >= SHY + 1) { stop(); wait = 60; }
    }

    // ------------------------------------------------------------------------------------------------ moving a clutch
    private HouseNestBlockEntity eggNest() {
        if (eggTarget == null || !eggTarget.dimension().equals(entity.level().dimension()) || !entity.level().hasChunkAt(eggTarget.pos())
                || !(entity.level().getBlockEntity(eggTarget.pos()) instanceof HouseNestBlockEntity other)
                || eggTargetIdentity != null && !eggTargetIdentity.equals(other.identity())) return null;
        return other;
    }
    private boolean relocationPlan(HouseNestBlockEntity source) {
        if (eggTarget != null || !carrying.isEmpty() || !entity.family().adult() || source.eggs() == 0 || !source.unfit()
                || entity.life().nutrition() < 40 || entity.life().energy() < 25 || !(entity.level() instanceof ServerLevel level)) return false;
        HouseNestBlockEntity best = null; double score = Double.MAX_VALUE;
        for (var other : new SupplyPileView(level).nearbyNests(source.getBlockPos(), 16)) {
            if (other == source || other.isRemoved() || !other.hasEggRoom() || other.unfit() || !other.usable(entity)
                    || !entity.life().reachable(other.entrance(), false)) continue;
            double distance = other.getBlockPos().distSqr(source.getBlockPos());
            if (distance < score) { best = other; score = distance; }
        }
        if (best == null) return false;
        eggTarget = GlobalPos.of(level.dimension(), best.getBlockPos()); eggTargetIdentity = best.identity();
        GameplayLog.event(entity,"house","egg relocation begin source={} destination={} eggs={}",source.getBlockPos(),eggTarget,source.eggs());
        lifting = true; return true;
    }
    private void tickRelocate(HouseNestBlockEntity source) {
        var target = eggNest();
        if (target == null || target.unfit() || !target.hasEggRoom()) {
            GameplayLog.limited(entity,"house","egg_destination",200,"egg relocation stopped reason=destination_unavailable destination={} carried={}",eggTarget,carrying.getCount());
            eggTarget = null; lifting = false; returnEgg(); stop(); return;
        }
        if (carrying.isEmpty()) {
            if (source == null || source.eggs() == 0) { eggTarget = null; lifting = false; stop(); return; }
            lifting = true; restPosition = null;
            phase(physicallyInside() ? Phase.LIFT : Phase.APPROACH); return;
        }
        Vec3 spot = Vec3.atBottomCenterOf(target.entrance());
        if (inReach(spot) && canSeeContact(spot)) beginInteraction(4, spot);
        else approach(spot);
    }
    private void returnEgg() {
        if (carrying.isEmpty()) return;
        var home = nest();
        if (home != null && home.insertEgg(carrying)) {
            GameplayLog.event(entity,"house","egg returned home={} count={}",home.getBlockPos(),carrying.getCount());
            carrying = ItemStack.EMPTY; return;
        }
        // The ledger still holds the egg: leave the real item where it can be found.
        entity.spawnAtLocation(carrying.copy()); carrying = ItemStack.EMPTY;
    }

    // ------------------------------------------------------------------------------------------------ refuge, disturbance
    private void finishRest() {
        if(sleepTicks>=20)GameplayLog.event(entity,"sleep","end source={} home={} corner={} ticks={} energy={} nutrition={} rested={}",
                denRest?"corner":"house",nest,den==null?null:den.site(),sleepTicks,entity.life().energy(),entity.life().nutrition(),rested);
        if (rested) { rested = false; entity.family().woke(); }
        sleepTicks = 0;
    }
    private void seekRefuge() {
        if (refuge == null) {
            double best = Double.MAX_VALUE;
            for (var pos : BlockPos.betweenClosed(entity.blockPosition().offset(-3, 0, -3), entity.blockPosition().offset(3, 0, 3))) {
                double distance = entity.distanceToSqr(Vec3.atBottomCenterOf(pos));
                if (distance < .36 || distance >= best || !entity.life().dryStand(pos) || !entity.life().covered(pos)
                        || !entity.life().reachable(pos, false)) continue;
                var value = nest();
                if (value != null && pos.distSqr(value.getBlockPos()) < 2) continue;
                refuge = pos.immutable(); best = distance;
            }
        }
        if (refuge != null && entity.distanceToSqr(Vec3.atBottomCenterOf(refuge)) > .16) {
            route(Vec3.atBottomCenterOf(refuge), .85);
        } else {
            entity.getNavigation().stop();
            if (ticks % 30 == 1) entity.getLookControl().setLookAt(entity.getX() + Math.sin(ticks * .1), entity.getEyeY(), entity.getZ() - 1, 20, 20);
        }
        if (ticks >= 100) { stop(); wait = 100; }
    }
    /** Cleaning surprises the real residents. Demolition forgets only the home, never the family. */
    public void disturb(boolean destroyed) {
        if (entity.level().isClientSide || !EcologyRoutines.house(entity)) return;
        GameplayLog.event(entity,"house","disturbed home={} destroyed={} phase={} carried={}",nest,destroyed,phase,carrying.getCount());
        stopExit();
        stop(); entity.life().interrupt(); entity.family().stop(); entity.dailyLife().stop(); entity.social().stop();
        if (destroyed) { nest = null; nestIdentity = null; lostTicks = 1200; }
        refuge = null; startledTicks = 24; wait = 0;
        entity.showReaction("surprise", destroyed ? "house_search_home" : "house_startled", 100);
    }
    private void route(Vec3 point, double speed) {
        if (!movement.moveTo(point, speed)) {
            GameplayLog.limited(entity,"house","route_failed",200,"route failed target={} phase={} carrying={}",point,phase,carrying.getCount());
            stop(); wait = 100;
        }
    }
    private void approach(Vec3 point) {
        var delta = point.subtract(entity.position()).multiply(1, 0, 1);
        if (delta.lengthSqr() < .0001) delta = new Vec3(0, 0, 1);
        // Keep the exact contact offset local. Far away, route to the source's actual block.
        route(entity.distanceToSqr(point) < 2.25 ? point.subtract(delta.normalize().scale(reach())) : point, .8);
    }
    private boolean canSeeContact(Vec3 point) {
        return entity.level().clip(new ClipContext(entity.getEyePosition(), point.add(0, .04, 0),
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, entity)).getType() == HitResult.Type.MISS;
    }
    private double reach() { return .28 * entity.getScale() * entity.growthScale(); }
    private boolean inReach(Vec3 point) {
        var delta = point.subtract(entity.position());
        return Math.abs(delta.y) < .12 && Math.abs(delta.horizontalDistance() - reach()) < .045;
    }
    private double mouthOffset() { return MouthPlacement.position(entity).subtract(entity.position()).horizontalDistance(); }
    /** The body is at the stand point from which its mouth (not its centre) can meet the item. */
    private boolean inSourceStand(Vec3 point) {
        var delta = point.subtract(entity.position());
        return Math.abs(delta.y) < .12 && Math.abs(delta.horizontalDistance() - mouthOffset()) < .05;
    }
    private void approachSource(Vec3 point) {
        // Far away route to the actual block; close in, aim the mouth at the item with the shared contact rule.
        route(entity.distanceToSqr(point) < 2.25 ? ItemContact.approach(entity, point) : point, .8);
    }
    /** Pickup requires physical mouth contact, evaluated after the resident has turned towards the pile. */
    private boolean mouthTouchesSource() { return ItemContact.reaches(entity, sourcePosition().add(0, .05, 0), .10); }
    private void beginInteraction(int kind, Vec3 point) { movement.stop(); interaction = kind; interactionTicks = 0; contactTarget = point; entity.getNavigation().stop(); faceContact(); }
    private void faceContact() {
        if (contactTarget == null) return;
        var delta = contactTarget.subtract(entity.position()); float yaw = (float) (Math.atan2(-delta.x, delta.z) * 180 / Math.PI);
        entity.setYRot(yaw); entity.yBodyRot = yaw; entity.yHeadRot = yaw;
        entity.getLookControl().setLookAt(contactTarget.x, entity.getEyeY(), contactTarget.z, 180, 180);
    }
    private void tickInteraction(HouseNestBlockEntity value) {
        entity.getNavigation().stop(); entity.setDeltaMovement(0, entity.getDeltaMovement().y, 0); faceContact(); interactionTicks++;
        if (interactionTicks == 13) {
            if (!mayTransfer()) { stop(); return; }
            if (interaction == 1) {
                if (!sourceAvailable() || !mouthTouchesSource() || !canSeeSource()
                        || !wantedSource(value)) { stop(); return; }
                if (feeding && (!entity.life().canEat() || !entity.life().acceptsFood(sourceContents()))) { stop(); return; }
                var at = sourcePosition(); boolean lint = supplyTarget.lint();
                var taken = takeSource(); clearSource();
                if (taken.isEmpty()) { stop(); return; }
                GameplayLog.event(entity,"house","pickup source={} item={} count={} feeding={}",at,taken.getItem(),taken.getCount(),feeding);
                count(Stat.PICKED);
                entity.showReaction("happy", "house_pickup", 60);
                if (entity.level() instanceof ServerLevel level) HouseholdEcology.puff(level, BlockPos.containing(at), lint);
                if (feeding) {
                    // All beginMeal preconditions were checked before this synchronous one-item debit.
                    if (entity.life().beginMeal(taken, null, false)) count(Stat.ATE);
                    else carrying = taken;
                    stop(); return;
                }
                carrying = taken;
            } else if (interaction == 2 && !carrying.isEmpty()) {
                var entrance = entranceCell();
                if (entrance == null || !inReach(Vec3.atBottomCenterOf(entrance)) || !canSeeContact(Vec3.atBottomCenterOf(entrance))) { stop(); return; }
                var target = value != null ? value : foundNest();
                if (target == null || !deliver(target)) { dropCarrying(); stop(); return; }
            } else if (interaction == 4 && !carrying.isEmpty()) {
                var target = eggNest();
                if (target != null && target.insertEgg(carrying)) {
                    carrying = ItemStack.EMPTY; count(Stat.MOVED); eggTarget = null; lifting = false;
                    var from = nest();
                    if (choose(target) && from != null && entity.level() instanceof ServerLevel)
                        for (var partner : entity.level().getEntitiesOfClass(YukkuriEntity.class, new AABB(from.getBlockPos()).inflate(8),
                                other -> other != entity && other.isAlive() && EcologyRoutines.house(other) && from.getBlockPos().equals(other.house().nestPosition())))
                            partner.house().choose(target);
                    entity.showReaction("happy", "house_deposit", 80);
                    GameplayLog.event(entity,"house","egg deposited home={} eggs={}",target.getBlockPos(),target.eggs());
                } else { eggTarget = null; lifting = false; returnEgg(); }
                stop(); return;
            }
        }
        if (interactionTicks >= 26) {
            if (interaction == 2) {
                interaction = interactionTicks = 0;
                if (lastDeliveredLint && nest() != null) { lastDeliveredLint = false; phase(Phase.SHAPE); contactTarget = nestCentre(); }
                else stop();
                return;
            }
            if (interaction == 4) { stop(); return; }
            interaction = interactionTicks = 0; contactTarget = null; ticks = 0;
        }
    }
    /** Hands the carried unit over: founding the nest first when this is a newcomer's first lint. */
    private boolean deliver(HouseNestBlockEntity target) {
        boolean lint = SupplyPiles.lint(carrying), builtBefore = target.built();
        if (!target.deposit(carrying)) return false;
        GameplayLog.event(entity,"house","deposit home={} item={} count={} food={} lint={}",
                target.getBlockPos(),carrying.getItem(),carrying.getCount(),target.food(),target.materials());
        carrying = ItemStack.EMPTY; count(Stat.DELIVERED);
        if (!lint) count(Stat.STASHED);
        lastDeliveredLint = lint;
        if (entity.level() instanceof ServerLevel level) HouseholdEcology.puff(level, target.getBlockPos(), lint);
        entity.showReaction("happy", "house_deposit", 60);
        if (!builtBefore && target.built()) {
            count(Stat.BUILT);
            GameplayLog.event(entity,"house","nest built home={} lint={}",target.getBlockPos(),target.materials());
        }
        return true;
    }
    /** The newcomer's first delivery: the nest appears at its corner and becomes this resident's home. */
    private HouseNestBlockEntity foundNest() {
        if (den == null || !(entity.level() instanceof ServerLevel level)) return null;
        var made = HouseholdEcology.found(level, den, entity.getVariant().assetId().endsWith("reimu"));
        if (made == null) {
            GameplayLog.limited(entity,"house","found_failed",200,"nest founding failed site={}",den.site());
            den = null; return null;
        }
        nest = GlobalPos.of(level.dimension(), made.getBlockPos()); nestIdentity = made.identity(); den = null;
        count(Stat.FOUNDED);
        GameplayLog.event(entity,"house","nest founded home={} identity={}",nest,nestIdentity);
        return made;
    }

    public void stop() {
        movement.stop();
        finishRest(); active = false; clearSource(); contactTarget = restPosition = null;
        interaction = interactionTicks = ticks = 0; phase = Phase.NONE; tending = careContact = feeding = lifting = hurry = denRest = false; scoutTarget = null;
        wait = Math.max(wait, 40);
    }
    /**
     * Puts the carried unit back on the floor. Lint and crumbs only exist as ground debris, so whatever cannot be set down is
     * simply scattered; an egg is a real item and is returned to the nest or left on the floor with its ledger entry intact.
     */
    public void dropCarrying() {
        if (carrying.isEmpty()) return;
        if (carrying.is(ModItems.HOUSE_EGG.get())) { returnEgg(); return; }
        if (entity.level() instanceof ServerLevel level && entity.isAlive())
            carrying.shrink(HouseholdEcology.residual(level, entity.blockPosition(), carrying));
        carrying = ItemStack.EMPTY;
    }

    // ------------------------------------------------------------------------------------------------ persistence
    public CompoundTag save() {
        var tag = new CompoundTag(); tag.putInt("Version", 3); FamilyData.putPosition(tag, "Nest", nest);
        if (nestIdentity != null) tag.putUUID("NestIdentity", nestIdentity);
        if (den != null) { tag.putLong("Den", den.site().asLong()); tag.putString("DenEntrance", den.entrance().getName()); }
        tag.putInt("CareWait", careWait); tag.putInt("LostTicks", lostTicks); tag.putInt("StartledTicks", startledTicks);
        if (!carrying.isEmpty()) tag.put("Carrying", carrying.save(entity.registryAccess()));
        // Read-only observation for operators and automated checks; never loaded back.
        tag.putString("Stage", stage()); tag.putString("Stats", statsText());
        tag.putInt("HumanDistanceTenths", (int)Math.round(Math.min(99, humanDistance) * 10));
        var counts = new CompoundTag();
        for (var stat : Stat.values()) counts.putInt(stat.name().toLowerCase(Locale.ROOT), stats[stat.ordinal()]);
        tag.put("Counts", counts);
        return tag;
    }
    public void load(CompoundTag tag) {
        stopExit(); exitRetryAt = 0;
        stop(); nest = FamilyData.position(tag, "Nest"); nestIdentity = tag.hasUUID("NestIdentity") ? tag.getUUID("NestIdentity") : null;
        den = null;
        if (tag.contains("Den")) {
            var direction = Direction.byName(tag.getString("DenEntrance"));
            if (direction != null && direction.getAxis().isHorizontal()) den = new HouseholdEcology.Den(BlockPos.of(tag.getLong("Den")), direction);
        }
        carrying = tag.contains("Carrying") ? ItemStack.parseOptional(entity.registryAccess(), tag.getCompound("Carrying")) : ItemStack.EMPTY;
        careWait = Math.clamp(tag.getInt("CareWait"), 0, 120); lostTicks = Math.clamp(tag.getInt("LostTicks"), 0, 1200);
        startledTicks = Math.clamp(tag.getInt("StartledTicks"), 0, 24); refuge = null;
        var counts = tag.getCompound("Counts");
        for (var stat : Stat.values()) stats[stat.ordinal()] = Math.max(0, counts.getInt(stat.name().toLowerCase(Locale.ROOT)));
    }
}
