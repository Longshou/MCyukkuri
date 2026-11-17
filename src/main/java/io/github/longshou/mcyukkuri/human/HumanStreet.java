package io.github.longshou.mcyukkuri.human;

import io.github.longshou.mcyukkuri.entity.YukkuriAction;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.entity.YukkuriComponents;
import io.github.longshou.mcyukkuri.family.FamilyData;
import io.github.longshou.mcyukkuri.family.FamilyNest;
import io.github.longshou.mcyukkuri.life.Expulsion;
import io.github.longshou.mcyukkuri.life.Facilities;
import io.github.longshou.mcyukkuri.life.PetNestBlock;
import io.github.longshou.mcyukkuri.life.StallTheft;
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
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.util.DefaultRandomPos;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** One local encounter, driven by the resident's existing routine goal. */
final class HumanStreet {
    static final int DAY = 24000, ROUTE_TICKS = 200;
    static final byte KICK_EVENT = 91;
    static final byte FROWN_EVENT = 92;
    static final int KICK_POSE_TICKS = 10;
    private static final int FEED_INTERVAL = 2400;
    /**
     * Ticks a resident keeps an animal it drove off out of its care: longer than a street routing beat, far shorter
     * than the driven-off claim's cooldown. A caring resident only scolds when truly pushed, so it holds the distance
     * longest; a cruel one scolds routinely and forgets soonest.
     */
    private static final int SCOLD_MEMORY_CARING = 2400, SCOLD_MEMORY_NEUTRAL = 1800, SCOLD_MEMORY_CRUEL = 1200;
    /** How long a resident keeps answering its own pet's plea for help after the pet reaches it. */
    private static final int HELP_TICKS = 400;
    private static final String FED_AFTER = "ResidentWildFeedAfter";
    private static final String KICK_DAY = "StreetKickDay";
    private static final double CONTACT_SQ = 4, CLOSE_SQ = 1.44, PARTED_SQ = 9;
    private final HumanNpc npc;
    private final Map<UUID, Warning> warnings = new HashMap<>();
    private final Map<UUID, Long> scolded = new HashMap<>();
    private long actionDay = Long.MIN_VALUE, nextPlayerFrown;
    private int actions;
    private long beatUntil;
    private boolean chasing;
    private Vec3 chaseStand;
    private YukkuriEntity activeTarget;
    private YukkuriEntity theftTarget;
    private BlockPos witnessedSite, activeSite;
    private long theftWitnessUntil;
    private YukkuriEntity helpTarget;
    private BlockPos helpSite;
    private long helpUntil;
    private YukkuriEntity lastPetted;
    private long nextDirtLook;
    int kickPose, frownPose;

    private static final class Warning {
        final long at;
        final BlockPos site;
        boolean seen, parted;
        long blockedUntil;
        Warning(long at, BlockPos site) { this.at = at; this.site = site; }
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
        if (actionDay != day()) { actionDay = day(); actions = 0; }
        warnings.values().removeIf(warning -> now() >= Math.max(warning.at + DAY, warning.blockedUntil));
        if (actions < 2 && theftTarget != null && now() < theftWitnessUntil && canTheftWarn(theftTarget)) return theftTarget;
        if (actions < 2 && HumanWorld.CRUELTY.get() && npc.attitude() != HumanNpc.Attitude.CARING) {
            var returning = nearest(2, y -> siteReturn(y) != null && canStart(y));
            if (returning != null) return returning;
        }
        return switch (npc.attitude()) {
            case CARING -> nearest(4, this::canCare);
            case NEUTRAL -> null;
            case CRUEL -> {
                if (npc.level().isRaining()) yield null;
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
                && (lastPetted != y || eligibleMeal(y))
                && !remembersScold(y);
    }

    /** A resident that just scolded an animal keeps it out of its care; the refusal is logged with that real reason. */
    private boolean remembersScold(YukkuriEntity y) {
        long until = scolded.getOrDefault(y.getUUID(), 0L);
        if (now() >= until) return false;
        if (now() >= nextPlayerFrown && npc.distanceToSqr(y) <= CONTACT_SQ && y.social().foodRequestTo(npc)) {
            nextPlayerFrown = now() + ROUTE_TICKS;
            npc.level().broadcastEntityEvent(npc, FROWN_EVENT);
            cue(npc, y, "warning");
        }
        GameplayLog.limited(npc, "npc-care", "street_scold." + y.getUUID(), 200,
                "action=street_care_skip target={} reason=scolded until={}", y.getUUID(), until);
        return true;
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
        y.showReaction(YukkuriAction.MOOD_FLUTTER, "happy", "", 60);
        npc.swing(InteractionHand.MAIN_HAND);
        lastPetted = y;
        GameplayLog.event(npc, "npc-care", "action=street_pet target={} food={}", y.getUUID(), npc.careFood());
        return true;
    }

    private boolean canTarget(YukkuriEntity y, BlockPos exposure, boolean keepBite) {
        if (!npc.streetActive() || npc.level().isRaining()
                || !available(y) || y.profile().aquatic() || !y.onGround() || !npc.hasLineOfSight(y)
                || !npc.isWithinRestriction(y.blockPosition()) || !npc.isWithinRestriction(exposure)) return false;
        var identity = y.status().identity();
        if (identity != Identity.STRAY && identity != Identity.WILD_FAMILY
                || !y.family().adult() && !y.family().subadult()
                || y.settlement().groupId() != null || y.settlement().society().slave()
                || y.family().brood() != null || y.family().partner() != null || !lone(y)) return false;
        if (!keepBite && (y.life().mealTicks() > 0 || !y.component(YukkuriComponents.MOUTH).freeForExternalWork())
                || y.getHealth() < y.getMaxHealth() || y.body().toxin() > 0 || y.body().mold() > 0
                || !y.body().wounds().isEmpty() || y.body().footBurned() || y.status().marks().dirt() > 0
                || FamilyNest.atAssignedNest(y) || FamilyNest.rimNest(y) != null || Facilities.floor(y).getBlock() instanceof PetNestBlock
                || ShelterBlock.shelter(y.level(), BlockPos.containing(y.position().add(0, .125, 0))) != null
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
        if (npc.distanceToSqr(y) > CONTACT_SQ || !canTarget(y, y.blockPosition(), false)) return false;
        var warning = warnings.get(y.getUUID());
        if (warning != null && (!warning.seen || !warning.parted || now() < warning.blockedUntil || !HumanWorld.CRUELTY.get())) return false;
        if (npc.attitude() != HumanNpc.Attitude.CRUEL && siteReturn(y) == null) return false;
        return siteReturn(y) != null || crowding(y) || y.social().demandingFoodFrom(npc);
    }

    private BlockPos siteReturn(YukkuriEntity y) {
        var warning = warnings.get(y.getUUID());
        return warning == null || warning.site == null || y.blockPosition().distSqr(warning.site) > 16
                || y.life().avoidsField(warning.site) ? null : warning.site;
    }

    private boolean canTheftWarn(YukkuriEntity y) {
        return !npc.walking() && now() >= nextPlayerFrown && npc.distanceToSqr(y) <= 144
                && !warnings.containsKey(y.getUUID()) && !y.life().avoidsField(witnessedSite)
                && canTarget(y, witnessedSite, true);
    }

    static void witnessedCropTheft(YukkuriEntity y, BlockPos crop) {
        var witness = y.level().getEntitiesOfClass(HumanNpc.class, y.getBoundingBox().inflate(12, 12, 12), npc ->
                npc.street.observable() && npc.street.canTarget(y, crop, true) && npc.distanceToSqr(y) <= 144
                        && npc.street.seesCrop(crop) && !npc.walking() && !npc.street.warnings.containsKey(y.getUUID())
                        && npc.street.now() >= npc.street.nextPlayerFrown
                        && (npc.street.actionDay != npc.street.day() || npc.street.actions < 2))
                .stream().min(Comparator.comparingDouble(y::distanceToSqr)).orElse(null);
        if (witness == null) return;
        witness.street.witnessTheft(y, crop);
    }

    /** The site a resident saw taken from; the routine then warns there in its own manner. */
    private void witnessTheft(YukkuriEntity y, BlockPos site) {
        theftTarget = y;
        witnessedSite = site.immutable();
        theftWitnessUntil = now() + 60;
    }

    private boolean seesCrop(BlockPos crop) {
        var sight = npc.level().clip(new ClipContext(npc.getEyePosition(), crop.getCenter(),
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, npc));
        return sight.getType() == HitResult.Type.MISS || sight.getBlockPos().equals(crop);
    }

    static HumanNpc witnessedStallTake(YukkuriEntity y, BlockPos crate, Vec3 contact) {
        if (y.level().isRaining() || !StallTheft.maySteal(y)) return null;
        var witness = y.level().getEntitiesOfClass(HumanNpc.class, y.getBoundingBox().inflate(12),
                npc -> npc.streetActive() && !npc.resting() && !npc.walking() && npc.street.observable()
                        && npc.distanceToSqr(y) <= 144 && npc.isWithinRestriction(crate)
                        && y.life().personality().trust(npc.getUUID()) < 10
                        && npc.street.now() >= npc.street.nextPlayerFrown
                        && (npc.street.actionDay != npc.street.day() || npc.street.actions < 2)
                        && aheadOf(npc, y) && npc.street.seesStallContact(crate, contact))
                .stream().min(Comparator.comparingDouble(y::distanceToSqr)).orElse(null);
        if (witness == null) return null;
        var street = witness.street;
        if (street.actionDay != street.day()) { street.actionDay = street.day(); street.actions = 0; }
        street.actions++;
        street.nextPlayerFrown = street.now() + ROUTE_TICKS;
        street.rememberScold(y);
        witness.level().broadcastEntityEvent(witness, FROWN_EVENT);
        if (witness.attitude() != HumanNpc.Attitude.CARING) witness.getNavigation().stop();
        cue(witness, y, "warning");
        Expulsion.driveOff(y, witness, crate);
        GameplayLog.event(witness, "npc-care", "action=stall_scold target={} crate={} attitude={} actions={} drove_off=true",
                y.getUUID(), crate.toShortString(), witness.attitude(), street.actions);
        return witness;
    }

    private boolean seesStallContact(BlockPos crate, Vec3 contact) {
        var sight = npc.level().clip(new ClipContext(npc.getEyePosition(), contact, ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE, npc));
        // The authored box is open, but its collision shape is a solid cuboid. Only the rim at the tug may touch the ray.
        return sight.getType() == HitResult.Type.MISS
                || sight.getBlockPos().equals(crate) && sight.getLocation().distanceToSqr(contact) < .04;
    }

    private boolean crowding(YukkuriEntity y) {
        double distance = npc.distanceToSqr(y);
        return distance <= .85 * .85 || distance <= CLOSE_SQ && aheadOf(npc, y);
    }

    void begin(YukkuriEntity y) {
        var warning = warnings.get(y.getUUID());
        activeSite = y == theftTarget && now() < theftWitnessUntil ? witnessedSite
                : warning != null && warning.seen ? siteReturn(y) : null;
        if (activeSite != null || npc.attitude() == HumanNpc.Attitude.CRUEL) activeTarget = y;
    }

    boolean reacting() { return activeTarget != null; }

    /** Its own pet ran to its feet about a beggar: the resident answers in its own manner. */
    void helpPet(YukkuriEntity pet, YukkuriEntity stray) {
        if (!npc.isAlive() || stray == null || !stray.isAlive() || stray.level() != npc.level()) return;
        helpTarget = stray;
        helpSite = stray.blockPosition().immutable();
        helpUntil = now() + HELP_TICKS;
        npc.getNavigation().stop();
        if (pet != null && pet.isAlive() && pet.level() == npc.level()) {
            npc.getLookControl().setLookAt(pet, 30, 30);
            npc.swing(InteractionHand.MAIN_HAND);
            pet.showReaction(YukkuriAction.MOOD_FLUTTER, "happy", "", 60);
            pet.life().personality().talk(npc.getUUID(), true);
        }
        cue(npc, stray, npc.attitude() == HumanNpc.Attitude.CRUEL ? "chase" : "warning");
        Expulsion.driveOff(stray, npc, helpSite);
        GameplayLog.event(npc, "npc-care", "action=pet_help pet={} target={} attitude={} distance={}",
                pet == null ? null : pet.getUUID(), stray.getUUID(), npc.attitude(), Math.round(npc.distanceTo(stray)));
    }

    boolean helping() { return helpTarget != null; }

    /** The routine drives this while the answer lasts: the resident steps up to where the beggar stood. */
    void helpStep() {
        if (!helpTarget.isAlive() || helpTarget.level() != npc.level()
                || now() >= helpUntil || npc.distanceToSqr(helpTarget) > 1024) { stopHelp(); return; }
        npc.getLookControl().setLookAt(helpTarget, 30, 30);
        double reach = npc.attitude() == HumanNpc.Attitude.CRUEL ? 1.5
                : npc.attitude() == HumanNpc.Attitude.CARING ? 3.5 : 2.5;
        if (npc.distanceToSqr(helpSite.getCenter()) > reach * reach) {
            if (npc.tickCount % 20 == 1)
                npc.getNavigation().moveTo(helpSite.getX() + .5, helpSite.getY(), helpSite.getZ() + .5, .85);
        } else npc.getNavigation().stop();
    }

    private void stopHelp() { helpTarget = null; helpSite = null; }

    boolean step(YukkuriEntity y) {
        var warning = warnings.get(y.getUUID());
        boolean siteWarning = activeSite != null && !chasing && (beatUntil != 0 || warning == null);
        if (activeTarget != y || !observable() || !canTarget(y, y.blockPosition(), siteWarning)) return false;
        npc.getLookControl().setLookAt(y, 30, 30);
        if (beatUntil != 0) {
            if (chasing) return chaseContinues(y);
            if (siteWarning && !warning.seen && y.hasLineOfSight(npc)) y.getLookControl().setLookAt(npc, 30, 30);
            if (!warning.seen && now() < beatUntil && npc.distanceToSqr(y) <= (activeSite == null ? CONTACT_SQ : 144)
                    && y.hasLineOfSight(npc) && aheadOf(y, npc)) {
                warning.seen = true;
                if (activeSite != null) {
                    Expulsion.driveOff(y, npc, activeSite);
                } else {
                    var contact = SocialDisposition.contact(y, npc);
                    double confidence = SocialDisposition.confidence(contact.esteem());
                    String face = contact.wariness() > confidence ? "teary" : "surprise";
                    if (y.showReaction(face, "", 40)) {
                        GameplayLog.event(y, "npc-care", "action=street_warning_seen resident={} face={} wariness={} confidence={}",
                                npc.getUUID(), face, contact.wariness(), confidence);
                    }
                }
            }
            return now() < beatUntil;
        }
        if (!(siteWarning ? canTheftWarn(y) : canStart(y))) return false;
        npc.getNavigation().stop();
        if (warning == null) {
            warning = new Warning(now(), activeSite);
            warnings.put(y.getUUID(), warning);
            if (activeSite == null && interrupted(y, warning)) return false;
            beatUntil = now() + 30;
            actions++;
            cue(npc, y, activeSite == null ? "warning" : "crop");
            y.getLookControl().setLookAt(npc, 30, 30);
            if (activeSite != null) y.showReaction("surprise", "", 30);
            GameplayLog.event(npc, "npc-care", "action=street_warn target={} health={} actions={} site={}", y.getUUID(), y.getHealth(), actions, activeSite);
            return true;
        }
        if (activeSite == null && !crowding(y)) return false;
        warning.parted = false;
        if (activeSite == null && interrupted(y, warning)) return false;
        var away = away(y);
        if (!dryStreet(y, y.position(), away)) return false;
        var data = y.getPersistentData();
        if (npc.attitude() == HumanNpc.Attitude.CRUEL && npc.distanceToSqr(y) <= .85 * .85
                && (!data.contains(KICK_DAY) || data.getLong(KICK_DAY) != day())
                && dryStreet(y, y.position().add(away.scale(.5)), away)
                && npc.level().noCollision(y, y.getBoundingBox().move(away.scale(.5)).deflate(.01))) {
            kick(y, away);
            return false;
        }
        Vec3 destination = npc.position().add(away);
        var nav = npc.getNavigation();
        var stand = BlockPos.containing(destination);
        if (stand.equals(npc.blockPosition()) || !dryStreet(y, Vec3.atBottomCenterOf(stand), away)) return false;
        var path = nav.createPath(stand, 0);
        if (path == null || !path.canReach() || path.getNodeCount() > 2) return false;
        if (activeSite != null) {
            for (int i = 0; i < path.getNodeCount(); i++)
                if (npc.level().getBlockState(path.getNodePos(i).below()).is(Blocks.FARMLAND)) return false;
        }
        if (!nav.moveTo(path, .85)) return false;
        beatUntil = now() + ROUTE_TICKS;
        chasing = true;
        chaseStand = Vec3.atBottomCenterOf(stand);
        if (activeSite != null) y.life().avoidField(activeSite);
        if (activeSite != null) warning.blockedUntil = now() + DAY;
        actions++;
        cue(npc, y, "chase");
        GameplayLog.event(npc, "npc-care", "action=street_chase target={} health={} actions={}", y.getUUID(), y.getHealth(), actions);
        return true;
    }

    private boolean chaseContinues(YukkuriEntity y) {
        return HumanWorld.CRUELTY.get() && now() < beatUntil && !npc.getNavigation().isDone() && !npc.horizontalCollision
                && npc.distanceToSqr(y) <= CONTACT_SQ && aheadOf(npc, y) && dryStreet(y, y.position(), away(y))
                && dryStreet(y, chaseStand, away(y))
                && (activeSite != null || !interrupted(y, warnings.get(y.getUUID())));
    }

    private boolean interrupted(YukkuriEntity y, Warning warning) {
        var by = npc.level().getEntitiesOfClass(HumanNpc.class, npc.getBoundingBox().inflate(6, 2, 6),
                resident -> resident != npc && resident.streetActive() && resident.attitude() == HumanNpc.Attitude.CARING
                        && resident.distanceToSqr(npc) <= 36 && resident.hasLineOfSight(npc)
                        && resident.hasLineOfSight(y) && npc.hasLineOfSight(resident)).stream().findFirst().orElse(null);
        if (by == null) return false;
        warning.blockedUntil = now() + DAY;
        if (!chasing) actions++;
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
            if (!npc.level().hasChunkAt(at) || activeSite == null && !road(npc.level().getBlockState(at.below()))
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
        if (activeSite != null) {
            y.life().avoidField(activeSite);
            warnings.get(y.getUUID()).blockedUntil = now() + DAY;
        }
        npc.level().broadcastEntityEvent(npc, KICK_EVENT);
        actions++;
        GameplayLog.event(npc, "npc-care", "action=street_kick target={} health={}->{} actions={}",
                y.getUUID(), health, y.getHealth(), actions);
    }

    void tick() {
        if (now() >= nextDirtLook && npc.streetActive() && observable()) {
            nextDirtLook = now() + 60;
            var dirty = nearest(4, y -> y.status().identity() == Identity.STRAY && y.status().marks().dirt() >= 2
                    && y.isAlive() && aheadOf(npc, y) && npc.hasLineOfSight(y)
                    && SocialDisposition.dirtAversion(y, y.life().personality().relation(npc.getUUID()))
                    >= (npc.attitude() == HumanNpc.Attitude.CARING ? 1 : .5));
            if (dirty != null) {
                nextDirtLook = now() + 600;
                npc.getLookControl().setLookAt(dirty, 30, 30);
                npc.level().broadcastEntityEvent(npc, FROWN_EVENT);
                GameplayLog.event(npc, "npc-care", "action=street_dirt_frown target={} dirt={}", dirty.getUUID(), dirty.status().marks().dirt());
            }
        }
        if (theftTarget != null && (!theftTarget.isAlive() || theftTarget.isRemoved() || now() >= theftWitnessUntil)) {
            theftTarget = null; witnessedSite = null;
        }
        if (lastPetted != null && (!lastPetted.isAlive() || lastPetted.level() != npc.level()
                || npc.distanceToSqr(lastPetted) > PARTED_SQ)) lastPetted = null;
        scolded.values().removeIf(until -> now() >= until);
        if (warnings.isEmpty()) return;
        var level = (ServerLevel) npc.level();
        for (var entry : warnings.entrySet()) {
            var warning = entry.getValue();
            if (warning.seen && !warning.parted && level.getEntity(entry.getKey()) instanceof YukkuriEntity y
                    && (warning.site == null ? npc.distanceToSqr(y) > PARTED_SQ
                            : y.blockPosition().distSqr(warning.site) > 16)) warning.parted = true;
        }
    }

    void save(CompoundTag tag) {
        if (actions > 0) {
            tag.putLong("StreetActionDay", actionDay);
            tag.putInt("StreetActions", actions);
        }
        var interventions = new ListTag();
        warnings.forEach((target, warning) -> {
            if (warning.site != null || warning.blockedUntil <= now()) return;
            var saved = new CompoundTag();
            saved.putUUID("Target", target);
            saved.putLong("Until", warning.blockedUntil);
            interventions.add(saved);
        });
        if (!interventions.isEmpty()) tag.put("StreetInterventions", interventions);
    }

    void load(CompoundTag tag) {
        actionDay = tag.getLong("StreetActionDay");
        actions = tag.getInt("StreetActions");
        warnings.clear();
        for (var item : tag.getList("StreetInterventions", Tag.TAG_COMPOUND)) {
            var saved = (CompoundTag) item;
            if (!saved.hasUUID("Target") || saved.getLong("Until") <= now()) continue;
            var warning = new Warning(saved.getLong("Until") - DAY, null);
            warning.blockedUntil = saved.getLong("Until");
            warnings.put(saved.getUUID("Target"), warning);
        }
    }

    void cancel() { activeTarget = null; activeSite = null; beatUntil = 0; chasing = false; chaseStand = null; }

    /** Pick up an available real portion within reach. The same stock pays for feeding and bin transfers. */
    void pickUpGrain() {
        if (!npc.streetActive() || !observable() || npc.attitude() != HumanNpc.Attitude.CARING || npc.careFood() != 0
                || npc.walking() || npc.level().isRaining() || npc.tickCount % 40 != 13) return;
        var grain = npc.level().getEntitiesOfClass(ItemEntity.class, npc.getBoundingBox().inflate(1.25, .25, 1.25),
                item -> item.isAlive() && !item.hasPickUpDelay() && item.onGround()
                        && item.getItem().is(ModItems.GRAIN_REGULAR.get()) && npc.hasLineOfSight(item))
                .stream().min(Comparator.comparingDouble(npc::distanceToSqr)).orElse(null);
        if (grain == null) return;
        npc.takeStreetGrain();
        grain.getItem().shrink(1);
        if (grain.getItem().isEmpty()) grain.discard();
        npc.swing(InteractionHand.OFF_HAND);
        GameplayLog.event(npc, "npc-care", "action=street_pickup item={} count=1 food=0->1", grain.getUUID());
    }

    boolean threatTo(YukkuriEntity witness) {
        return activeTarget != null && beatUntil > now() && witness.distanceToSqr(npc) <= 64 && !witness.isLeashed()
                && (activeSite == null || chasing)
                && witness.hasLineOfSight(npc) && (witness == activeTarget
                ? chasing || beatUntil - now() <= 10 && warnings.get(activeTarget.getUUID()).seen
                : witness.hasLineOfSight(activeTarget))
                && canTarget(activeTarget, activeTarget.blockPosition(), activeSite != null && !chasing);
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

    void scoldSquatter(YukkuriEntity squatter) {
        rememberScold(squatter);
        npc.level().broadcastEntityEvent(npc, FROWN_EVENT);
        cue(npc, squatter, "warning");
        GameplayLog.event(npc, "npc-care", "action=yard_scold target={} distance={}",
                squatter.getUUID(), npc.distanceTo(squatter));
    }

    private void rememberScold(YukkuriEntity target) {
        int memory = switch (npc.attitude()) {
            case CARING -> SCOLD_MEMORY_CARING;
            case NEUTRAL -> SCOLD_MEMORY_NEUTRAL;
            case CRUEL -> SCOLD_MEMORY_CRUEL;
        };
        scolded.put(target.getUUID(), now() + memory);
    }

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
        private boolean finished;
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
            finished = false;
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

        @Override public boolean canContinueToUse() { return npc.streetActive() && !npc.walking() && !finished; }
        @Override public boolean requiresUpdateEveryTick() { return true; }

        @Override public void tick() {
            // Vanilla still ticks running goals before its next continuation check.
            if (finished) return;
            if (npc.distanceToSqr(destination) < .5) { finished = true; return; }
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
            else finished = true;
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
