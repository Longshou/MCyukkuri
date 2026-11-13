package io.github.longshou.mcyukkuri.human;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.entity.YukkuriComponents;
import io.github.longshou.mcyukkuri.family.FamilyData;
import io.github.longshou.mcyukkuri.family.FamilyNest;
import io.github.longshou.mcyukkuri.life.Facilities;
import io.github.longshou.mcyukkuri.life.SurfaceApproach;
import io.github.longshou.mcyukkuri.pet.BadgeGrade;
import io.github.longshou.mcyukkuri.pet.social.BadgeSocial;
import io.github.longshou.mcyukkuri.pet.stage.Staging;
import io.github.longshou.mcyukkuri.registry.ModBlocks;
import io.github.longshou.mcyukkuri.registry.ModItems;
import io.github.longshou.mcyukkuri.settlement.ShelterBlock;
import io.github.longshou.mcyukkuri.social.SocialDisposition;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import io.github.longshou.mcyukkuri.status.Identity;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.EnumSet;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.util.DefaultRandomPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** One local encounter, driven by the resident's existing routine goal. */
final class HumanStreet {
    static final int DAY = 24000, ROUTE_TICKS = 200;
    static final byte KICK_EVENT = 91;
    static final byte FROWN_EVENT = 92;
    static final int KICK_POSE_TICKS = 10;
    private static final int FEED_INTERVAL = 2400;
    private static final String FED_AFTER = "ResidentWildFeedAfter";
    private static final String KICK_DAY = "StreetKickDay";
    private static final double CONTACT_SQ = 4, CLOSE_SQ = 1.44, PARTED_SQ = 9;
    private final HumanNpc npc;
    private final Map<UUID, Warning> warnings = new HashMap<>();
    private long actionDay = Long.MIN_VALUE, nextPlayerFrown;
    private int actions;
    private long beatUntil;
    private boolean chasing;
    private YukkuriEntity activeTarget;
    private YukkuriEntity lastPetted;
    int kickPose, frownPose;

    private static final class Warning {
        final long at;
        boolean seen, parted;
        long blockedUntil;
        Warning(long at) { this.at = at; }
    }

    HumanStreet(HumanNpc npc) { this.npc = npc; }

    /** Only one template slot can produce an abuser, and only one roll in six uses it. */
    void assignVillageAttitude() {
        int roll = npc.getRandom().nextInt(6);
        npc.setAttitude(npc.getTags().contains("mcy_street_special")
                ? roll == 0 ? HumanNpc.Attitude.CRUEL : roll <= 3 ? HumanNpc.Attitude.CARING : HumanNpc.Attitude.NEUTRAL
                : HumanNpc.Attitude.NEUTRAL);
    }

    private long now() { return npc.level().getGameTime(); }
    private long day() { return Math.floorDiv(npc.level().getDayTime(), DAY); }

    YukkuriEntity pick() {
        if (!npc.streetActive() || !observable()) return null;
        return switch (npc.attitude()) {
            case CARING -> nearest(4, this::canCare);
            case NEUTRAL -> null;
            case CRUEL -> {
                if (npc.level().isRaining()) yield null;
                if (actionDay != day()) { actionDay = day(); actions = 0; }
                warnings.values().removeIf(warning -> now() >= Math.max(warning.at + DAY, warning.blockedUntil));
                yield actions >= 2 ? null : nearest(2, this::canStart);
            }
        };
    }

    boolean observable() {
        // An empty dedicated server can still tick explicitly loaded areas.
        return npc.level().players().isEmpty() || npc.level().getNearestPlayer(npc, 24) != null;
    }

    private YukkuriEntity nearest(double reach, Predicate<YukkuriEntity> eligible) {
        return npc.level().getEntitiesOfClass(YukkuriEntity.class, npc.getBoundingBox().inflate(reach, 1, reach), eligible)
                .stream().min(Comparator.comparingDouble(npc::distanceToSqr)).orElse(null);
    }

    private boolean available(YukkuriEntity y) {
        return y.isAlive() && !y.isPassenger() && !y.isVehicle() && !y.isLeashed() && !y.isInWaterOrBubble()
                && !y.isCommandPlaying() && !y.operations().active() && !y.pet().busy() && !y.life().isSleeping()
                && !y.family().birthDuty() && !y.isOnFire() && !y.profile().dangerToYukkuri() && !y.body().dying();
    }

    boolean canCare(YukkuriEntity y) {
        if (!npc.streetActive() || !observable() || npc.attitude() != HumanNpc.Attitude.CARING || !available(y) || !npc.mayTend(y)
                || !npc.hasLineOfSight(y) || !y.hasLineOfSight(npc) || y.isPanicking()
                || npc.level().isRaining() && (npc.level().canSeeSky(BlockPos.containing(npc.getEyePosition()))
                || npc.level().canSeeSky(y.blockPosition()) || !SurfaceApproach.supportedGround(y, y.position()))) return false;
        boolean own = Custody.ownedBy(y, npc.getUUID());
        if (!own && (!y.status().identity().free() || Custody.harmRefusal(null, y, y.blockPosition()) != null)) return false;
        if (y.life().mealTicks() > 0 || !y.component(YukkuriComponents.MOUTH).freeForExternalWork()) return false;
        var relation = y.life().personality().relation(npc.getUUID());
        return SocialDisposition.humanWariness(y, npc, relation) <= .50
                && (own || y.social().contactWith(npc) || y.status().identity() == Identity.STRAY && npc.distanceToSqr(y) <= CONTACT_SQ)
                && (lastPetted != y || eligibleMeal(y));
    }

    private boolean eligibleMeal(YukkuriEntity y) {
        return npc.careFood() > 0 && y.life().nutrition() <= 35 && y.body().toxin() == 0 && y.life().canEat()
                && now() >= Custody.data(y).getLong(FED_AFTER);
    }

    boolean care(YukkuriEntity y) {
        if (!canCare(y)) return false;
        if (eligibleMeal(y)) {
            if (!y.life().beginMeal(new ItemStack(ModItems.GRAIN_REGULAR.get()), npc.getUUID(), true)) return false;
            int before = npc.careFood();
            npc.drawCareFood(1);
            var fed = Custody.data(y).copy();
            fed.putLong(FED_AFTER, now() + FEED_INTERVAL);
            Custody.set(y, fed);
            npc.swing(InteractionHand.OFF_HAND);
            GameplayLog.event(npc, "npc-care", "action=begin_meal target={} food={}->{} nutrition={} result=accepted",
                    y.getUUID(), before, npc.careFood(), y.life().nutrition());
            return true;
        }
        y.life().personality().talk(npc.getUUID(), true);
        y.showReaction("happy", "", 60);
        npc.swing(InteractionHand.MAIN_HAND);
        lastPetted = y;
        GameplayLog.event(npc, "npc-care", "action=street_pet target={} food={}", y.getUUID(), npc.careFood());
        return true;
    }

    private boolean canTarget(YukkuriEntity y, BlockPos exposure) {
        if (!npc.streetActive() || npc.attitude() != HumanNpc.Attitude.CRUEL || npc.level().isRaining()
                || !available(y) || y.profile().aquatic() || !y.onGround() || !npc.hasLineOfSight(y)
                || !npc.isWithinRestriction(y.blockPosition()) || !npc.isWithinRestriction(exposure)) return false;
        var identity = y.status().identity();
        if (identity != Identity.STRAY && identity != Identity.WILD_FAMILY
                || !y.family().adult() && !y.family().subadult()
                || y.settlement().groupId() != null || y.settlement().society().slave()
                || y.family().brood() != null || y.family().partner() != null || !lone(y)) return false;
        if (y.life().hasMouthFood() || y.life().mealTicks() > 0 || !y.component(YukkuriComponents.MOUTH).freeForExternalWork()
                || y.getHealth() < y.getMaxHealth() || y.body().toxin() > 0 || y.body().mold() > 0
                || !y.body().wounds().isEmpty() || y.body().footBurned() || y.status().marks().dirt() > 0
                || FamilyNest.atAssignedNest(y) || FamilyNest.rimNest(y) != null || Facilities.floor(y).is(ModBlocks.NEST.get())
                || ShelterBlock.shelter(y.level(), y.blockPosition()) != null
                || !SurfaceApproach.supportedGround(y, y.position())) return false;
        return !Custody.protectedAnimal(y) && Custody.harmRefusal(null, y, exposure) == null
                && BadgeSocial.visibleRank(y).id() < BadgeGrade.BRONZE.id()
                && (!(y.pet().voidBadge() || y.pet().abandoned()) || y.pet().visibleGrade() == BadgeGrade.NONE);
    }

    private boolean lone(YukkuriEntity y) {
        var family = FamilyData.get((ServerLevel) npc.level());
        for (UUID child : family.children(y.getUUID())) {
            var person = family.person(child);
            if (family.acknowledgesChild(y.getUUID(), child) && person != null
                    && (person.status == FamilyData.Status.ALIVE || person.status == FamilyData.Status.UNBORN)) return false;
        }
        return y.level().getEntitiesOfClass(YukkuriEntity.class, y.getBoundingBox().inflate(12, 4, 12),
                peer -> peer != y && peer.isAlive() && family.socialKin(y.getUUID(), peer.getUUID())).isEmpty();
    }

    private boolean canStart(YukkuriEntity y) {
        if (npc.distanceToSqr(y) > CONTACT_SQ || !canTarget(y, y.blockPosition())) return false;
        var warning = warnings.get(y.getUUID());
        if (warning != null && (!warning.seen || !warning.parted || now() < warning.blockedUntil || !HumanWorld.CRUELTY.get())) return false;
        return crowding(y) || y.social().demandingFoodFrom(npc);
    }

    private boolean crowding(YukkuriEntity y) {
        return npc.distanceToSqr(y) <= CLOSE_SQ && aheadOf(npc, y);
    }

    void begin(YukkuriEntity y) {
        if (npc.attitude() == HumanNpc.Attitude.CRUEL) activeTarget = y;
    }

    boolean step(YukkuriEntity y) {
        if (activeTarget != y || !observable() || !canTarget(y, y.blockPosition())) return false;
        npc.getLookControl().setLookAt(y, 30, 30);
        var warning = warnings.get(y.getUUID());
        if (beatUntil != 0) {
            if (chasing) return chaseContinues(y);
            if (now() < beatUntil && y.hasLineOfSight(npc) && aheadOf(y, npc)) warning.seen = true;
            return now() < beatUntil;
        }
        if (!canStart(y)) return false;
        npc.getNavigation().stop();
        if (warning == null) {
            warning = new Warning(now());
            warnings.put(y.getUUID(), warning);
            if (interrupted(y, warning)) return false;
            beatUntil = now() + 30;
            actions++;
            cue(npc, y, "warning");
            y.getLookControl().setLookAt(npc, 30, 30);
            y.showReaction("surprise", "", 30);
            GameplayLog.event(npc, "npc-care", "action=street_warn target={} health={} actions={}", y.getUUID(), y.getHealth(), actions);
            return true;
        }
        if (!crowding(y)) return false;
        if (interrupted(y, warning)) return false;
        var away = away(y);
        if (!dryStreet(y, y.position().add(away.scale(.5)), away)) return false;
        var data = y.getPersistentData();
        if (npc.distanceToSqr(y) <= .85 * .85 && (!data.contains(KICK_DAY) || data.getLong(KICK_DAY) != day())) {
            kick(y, away);
            return false;
        }
        Vec3 destination = npc.position().add(away);
        var nav = npc.getNavigation();
        var stand = BlockPos.containing(destination);
        if (stand.equals(npc.blockPosition()) || !dryStreet(y, Vec3.atBottomCenterOf(stand), away)) return false;
        var path = nav.createPath(stand, 0);
        if (path == null || !path.canReach() || path.getNodeCount() > 2 || !nav.moveTo(path, .85)) return false;
        beatUntil = now() + ROUTE_TICKS;
        chasing = true;
        actions++;
        cue(npc, y, "chase");
        GameplayLog.event(npc, "npc-care", "action=street_chase target={} health={} actions={}", y.getUUID(), y.getHealth(), actions);
        return true;
    }

    private boolean chaseContinues(YukkuriEntity y) {
        return HumanWorld.CRUELTY.get() && now() < beatUntil && !npc.getNavigation().isDone() && !npc.horizontalCollision
                && npc.distanceToSqr(y) <= CONTACT_SQ && dryStreet(y, y.position(), away(y))
                && !interrupted(y, warnings.get(y.getUUID()));
    }

    private boolean interrupted(YukkuriEntity y, Warning warning) {
        var by = npc.level().getEntitiesOfClass(HumanNpc.class, npc.getBoundingBox().inflate(6, 2, 6),
                resident -> resident != npc && resident.streetActive() && resident.attitude() == HumanNpc.Attitude.CARING
                        && resident.distanceToSqr(npc) <= 36 && resident.hasLineOfSight(npc)
                        && resident.hasLineOfSight(y) && npc.hasLineOfSight(resident)).stream().findFirst().orElse(null);
        if (by == null) return false;
        warning.blockedUntil = now() + DAY;
        by.level().broadcastEntityEvent(by, FROWN_EVENT);
        cue(by, npc, "stop");
        GameplayLog.event(by, "npc-care", "action=street_stop target={} abuser={} until={}",
                y.getUUID(), npc.getUUID(), warning.blockedUntil);
        return true;
    }

    private Vec3 away(YukkuriEntity y) { return y.position().subtract(npc.position()).multiply(1, 0, 1).normalize(); }

    /** Preserve two full cells of road beyond the checked stand in the direction of motion. */
    private boolean dryStreet(YukkuriEntity y, Vec3 stand, Vec3 away) {
        if (away.lengthSqr() == 0 || !npc.onGround()) return false;
        for (double distance = 0; distance <= 2; distance += .5) {
            var feet = stand.add(away.scale(distance));
            var at = BlockPos.containing(feet);
            if (!npc.level().hasChunkAt(at) || !road(npc.level().getBlockState(at.below()))
                    || !SurfaceApproach.supportedGround(y, feet) || Custody.harmRefusal(null, y, at) != null) return false;
        }
        return true;
    }

    private static boolean road(BlockState floor) {
        return floor.is(Blocks.DIRT_PATH) || floor.is(Blocks.GRAVEL) || floor.is(Blocks.POLISHED_ANDESITE)
                || floor.is(Blocks.ANDESITE) || floor.is(Blocks.COBBLESTONE);
    }

    private void kick(YukkuriEntity y, Vec3 away) {
        float health = y.getHealth();
        y.nudge(away.x * .12, away.z * .12);
        y.showReaction("surprise", "", 30);
        var personality = y.life().personality();
        int closeness = personality.relation(npc.getUUID()).closeness();
        personality.relationshipChanged(npc.getUUID(), Math.min(-8, -1 - closeness), 18);
        y.getPersistentData().putLong(KICK_DAY, day());
        npc.level().broadcastEntityEvent(npc, KICK_EVENT);
        actions++;
        GameplayLog.event(npc, "npc-care", "action=street_kick target={} health={}->{} actions={}",
                y.getUUID(), health, y.getHealth(), actions);
    }

    void tick() {
        if (lastPetted != null && (!lastPetted.isAlive() || lastPetted.level() != npc.level()
                || npc.distanceToSqr(lastPetted) > PARTED_SQ)) lastPetted = null;
        if (warnings.isEmpty()) return;
        var level = (ServerLevel) npc.level();
        for (var entry : warnings.entrySet()) {
            var warning = entry.getValue();
            if (warning.seen && !warning.parted && level.getEntity(entry.getKey()) instanceof YukkuriEntity y
                    && npc.distanceToSqr(y) > PARTED_SQ) warning.parted = true;
        }
    }

    void save(CompoundTag tag) {
        if (actions > 0) {
            tag.putLong("StreetActionDay", actionDay);
            tag.putInt("StreetActions", actions);
        }
    }

    void load(CompoundTag tag) {
        actionDay = tag.getLong("StreetActionDay");
        actions = tag.getInt("StreetActions");
    }

    void cancel() { activeTarget = null; beatUntil = 0; chasing = false; }

    boolean threatTo(YukkuriEntity witness) {
        return activeTarget != null && beatUntil > now() && witness.distanceToSqr(npc) <= 64 && !witness.isLeashed()
                && witness.hasLineOfSight(npc) && (witness == activeTarget
                ? chasing || beatUntil - now() <= 10 && warnings.get(activeTarget.getUUID()).seen
                : witness.hasLineOfSight(activeTarget))
                && canTarget(activeTarget, activeTarget.blockPosition());
    }

    static void witnessedHurt(YukkuriEntity y, Player player) {
        long now = y.level().getGameTime();
        var witness = y.level().getEntitiesOfClass(HumanNpc.class, y.getBoundingBox().inflate(8, 3, 8),
                resident -> resident.streetActive() && resident.attitude() == HumanNpc.Attitude.CARING
                        && resident.hasLineOfSight(y) && resident.hasLineOfSight(player))
                .stream().min(Comparator.comparingDouble(y::distanceToSqr)).orElse(null);
        if (witness == null || now < witness.streetFrownAfter()) return;
        witness.streetFrown(now + ROUTE_TICKS);
        witness.level().broadcastEntityEvent(witness, FROWN_EVENT);
        cue(witness, player, "player_frown");
        GameplayLog.event(witness, "npc-care", "action=street_player_frown player={} target={}", player.getUUID(), y.getUUID());
    }

    long frownAfter() { return nextPlayerFrown; }
    void frown(long until) { nextPlayerFrown = until; }

    private static void cue(HumanNpc actor, Entity recipient, String line) {
        actor.getLookControl().setLookAt(recipient, 30, 30);
        actor.swing(InteractionHand.MAIN_HAND);
        var level = (ServerLevel) actor.level();
        level.playSound(null, actor.blockPosition(), SoundEvents.VILLAGER_NO, SoundSource.NEUTRAL, .85f, 1);
        Staging.card(level, actor.position().add(0, actor.getBbHeight() + .35, 0),
                Component.translatable("human.mcyukkuri.street." + line), 40, 1);
    }

    private static boolean aheadOf(Entity viewer, Entity target) {
        Vec3 to = target.position().subtract(viewer.position()).multiply(1, 0, 1).normalize();
        return Vec3.directionFromRotation(0, viewer.getYHeadRot()).dot(to) > .5;
    }

    /** Selects ordinary reachable street stands; a blocked leg tries the other side, then heads home. */
    static final class Stroll extends Goal {
        private final HumanNpc npc;
        private Vec3 destination;
        private Vec3 last;
        private int stalled;
        private int nextAt;
        private int detours;

        Stroll(HumanNpc npc) {
            this.npc = npc;
            setFlags(EnumSet.of(Flag.MOVE));
        }

        @Override public boolean canUse() {
            return npc.streetActive() && !npc.walking() && npc.tickCount >= nextAt;
        }

        @Override public void start() {
            detours = 0;
            if (npc.level().isRaining()) {
                if (npc.level().canSeeSky(npc.blockPosition())) {
                    for (var pos : BlockPos.withinManhattan(npc.blockPosition(), 5, 1, 5)) {
                        if (npc.isWithinRestriction(pos) && !npc.level().canSeeSky(pos)
                                && npc.level().getBlockState(pos.below()).isFaceSturdy(npc.level(), pos.below(), Direction.UP)
                                && route(Vec3.atBottomCenterOf(pos))) return;
                    }
                }
                route(Vec3.atBottomCenterOf(npc.home()));
                return;
            }
            Vec3 stand = DefaultRandomPos.getPos(npc, 8, 2);
            route(stand == null ? Vec3.atBottomCenterOf(npc.home()) : stand);
        }

        @Override public boolean canContinueToUse() { return npc.streetActive() && !npc.walking() && destination != null; }
        @Override public boolean requiresUpdateEveryTick() { return true; }

        @Override public void tick() {
            if (npc.distanceToSqr(destination) < .5) { destination = null; return; }
            if (npc.position().distanceToSqr(last) > .25) { last = npc.position(); stalled = 0; }
            if (!npc.getNavigation().isDone() && ++stalled < ROUTE_TICKS) {
                var toward = destination.subtract(npc.position()).multiply(1, 0, 1).normalize();
                AABB ahead = npc.getBoundingBox().move(toward.scale(.65)).deflate(.04);
                if (npc.level().getEntitiesOfClass(YukkuriEntity.class, ahead, Entity::isAlive).isEmpty()
                        && npc.level().getEntitiesOfClass(Player.class, ahead, player -> !player.isSpectator()).isEmpty()) return;
            }
            if (++detours == 1 && !npc.level().isRaining()) {
                Vec3 side = destination.subtract(npc.position()).multiply(1, 0, 1).normalize().yRot((float) (Math.PI / 2));
                if (trySide(side) || trySide(side.scale(-1))) return;
            }
            if (detours <= 2) route(Vec3.atBottomCenterOf(npc.home()));
            else destination = null;
        }

        private boolean trySide(Vec3 side) {
            var stand = BlockPos.containing(npc.position().add(side.scale(1.5)));
            if (!npc.isWithinRestriction(stand) || !npc.level().getBlockState(stand.below()).isFaceSturdy(npc.level(), stand.below(), Direction.UP)) return false;
            return route(Vec3.atBottomCenterOf(stand));
        }

        private boolean route(Vec3 to) {
            var nav = npc.getNavigation();
            var path = nav.createPath(BlockPos.containing(to), 0);
            last = npc.position(); stalled = 0; destination = to;
            if (path == null || !path.canReach() || !nav.moveTo(path, .65)) {
                nav.stop();
                return false;
            }
            return true;
        }

        @Override public void stop() {
            npc.getNavigation().stop();
            nextAt = npc.tickCount + 40 + npc.getRandom().nextInt(80);
            destination = null;
        }
    }
}
