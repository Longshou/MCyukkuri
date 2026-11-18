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
import io.github.longshou.mcyukkuri.shop.ShopRegistry;
import io.github.longshou.mcyukkuri.life.SurfaceApproach;
import io.github.longshou.mcyukkuri.pet.BadgeGrade;
import io.github.longshou.mcyukkuri.pet.social.BadgeSocial;
import io.github.longshou.mcyukkuri.pet.stage.Staging;
import io.github.longshou.mcyukkuri.play.PlayBlockEntity;
import io.github.longshou.mcyukkuri.registry.ModBlocks;
import io.github.longshou.mcyukkuri.registry.ModItems;
import io.github.longshou.mcyukkuri.settlement.ShelterBlock;
import io.github.longshou.mcyukkuri.social.SocialDisposition;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import io.github.longshou.mcyukkuri.status.Identity;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
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
import net.minecraft.world.entity.Marker;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.util.DefaultRandomPos;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.Path;
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
    private record Help(YukkuriEntity pet, YukkuriEntity stray, BlockPos site, long until) {}
    private Help help;
    private Path helpPath;
    private YukkuriEntity lastPetted;
    private long nextDirtLook;
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
        if (actionDay != day()) { actionDay = day(); actions = 0; }
        warnings.values().removeIf(warning -> now() >= Math.max(warning.at + DAY, warning.blockedUntil));
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
        if (npc.strollingWith(y) && y.outing().fit() && !eligibleMeal(y)) return false;
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

    private boolean canTarget(YukkuriEntity y) {
        if (!npc.streetActive() || npc.level().isRaining()
                || !available(y) || y.profile().aquatic() || !y.onGround() || !npc.hasLineOfSight(y)
                || !npc.isWithinRestriction(y.blockPosition())) return false;
        var identity = y.status().identity();
        if (identity != Identity.STRAY && identity != Identity.WILD_FAMILY
                || !y.family().adult() && !y.family().subadult()
                || y.settlement().groupId() != null || y.settlement().society().slave()
                || y.family().brood() != null || y.family().partner() != null || !lone(y)) return false;
        if (y.life().mealTicks() > 0 || !y.component(YukkuriComponents.MOUTH).freeForExternalWork()
                || y.getHealth() < y.getMaxHealth() || y.body().toxin() > 0 || y.body().mold() > 0
                || !y.body().wounds().isEmpty() || y.body().footBurned() || y.status().marks().dirt() > 0
                || FamilyNest.atAssignedNest(y) || FamilyNest.rimNest(y) != null || Facilities.floor(y).getBlock() instanceof PetNestBlock
                || ShelterBlock.shelter(y.level(), BlockPos.containing(y.position().add(0, .125, 0))) != null
                || !SurfaceApproach.supportedGround(y, y.position())) return false;
        return !Custody.protectedAnimal(y) && Custody.harmRefusal(null, y, y.blockPosition()) == null
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
        if (npc.distanceToSqr(y) > CONTACT_SQ || !canTarget(y)) return false;
        var warning = warnings.get(y.getUUID());
        if (warning != null && (!warning.seen || !warning.parted || now() < warning.blockedUntil || !HumanWorld.CRUELTY.get())) return false;
        return npc.attitude() == HumanNpc.Attitude.CRUEL && (crowding(y) || y.social().demandingFoodFrom(npc));
    }

    static void witnessedCropTheft(YukkuriEntity y, BlockPos crop) {
        var witness = y.level().getEntitiesOfClass(HumanNpc.class, y.getBoundingBox().inflate(12, 12, 12), npc ->
                npc.streetActive() && !npc.level().isRaining() && npc.street.observable() && !npc.walking()
                        && npc.distanceToSqr(y) <= 144 && npc.isWithinRestriction(crop)
                        && npc.hasLineOfSight(y) && npc.street.seesSite(crop))
                .stream().min(Comparator.comparingDouble(y::distanceToSqr)).orElse(null);
        if (witness == null) return;
        witness.street.expulsionCue(y, crop, "crop");
        GameplayLog.event(witness, "npc-care", "action=street_warn target={} health={} site={} attitude={}",
                y.getUUID(), y.getHealth(), crop, witness.attitude());
    }

    private boolean seesSite(BlockPos site) {
        var sight = npc.level().clip(new ClipContext(npc.getEyePosition(), site.getCenter(),
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, npc));
        return sight.getType() == HitResult.Type.MISS || sight.getBlockPos().equals(site);
    }

    static HumanNpc witnessedStallTake(YukkuriEntity y, BlockPos crate, Vec3 contact) {
        if (y.level().isRaining() || !StallTheft.maySteal(y)) return null;
        var witness = y.level().getEntitiesOfClass(HumanNpc.class, y.getBoundingBox().inflate(12),
                npc -> npc.streetActive() && !npc.resting() && !npc.walking() && npc.street.observable()
                        && npc.distanceToSqr(y) <= 144 && npc.isWithinRestriction(crate)
                        && aheadOf(npc, y) && npc.street.seesStallContact(crate, contact))
                .stream().min(Comparator.comparingDouble(y::distanceToSqr)).orElse(null);
        if (witness == null) return null;
        witness.street.expulsionCue(y, crate, "warning");
        GameplayLog.event(witness, "npc-care", "action=stall_scold target={} crate={} attitude={} drove_off=true",
                y.getUUID(), crate.toShortString(), witness.attitude());
        return witness;
    }

    static void witnessedBowlTake(YukkuriEntity eater, BlockPos bowl) {
        var level = (ServerLevel) eater.level();
        var shop = ShopRegistry.get(level).shopAt(level, bowl.getCenter());
        var owner = Custody.view(eater).owner();
        if (shop != null && (shop.id.equals(owner) || shop.staff(owner))) return;
        var witness = level.getEntitiesOfClass(HumanNpc.class, eater.getBoundingBox().inflate(12), npc ->
                !npc.isNoAi() && npc.isAlive() && npc.distanceToSqr(eater) <= 144
                        && !npc.getUUID().equals(owner)
                        && (npc.ownsYardBowl(bowl) || shop != null && shop.staff(npc.getUUID()))
                        && npc.hasLineOfSight(eater) && npc.street.seesSite(bowl))
                .stream().min(Comparator.comparingDouble(eater::distanceToSqr)).orElse(null);
        if (witness == null) return;
        witness.street.expulsionCue(eater, bowl, "warning");
        GameplayLog.event(witness, "npc-care", "action=bowl_scold target={} site={} attitude={}",
                eater.getUUID(), bowl, witness.attitude());
    }

    private void expulsionCue(YukkuriEntity thief, BlockPos site, String line) {
        rememberScold(thief);
        npc.level().broadcastEntityEvent(npc, FROWN_EVENT);
        cue(npc, thief, npc.attitude() == HumanNpc.Attitude.CRUEL ? "chase" : line);
        Expulsion.driveOff(thief, npc, site);
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
        if (npc.attitude() == HumanNpc.Attitude.CRUEL) activeTarget = y;
    }

    boolean reacting() { return activeTarget != null; }

    /** Its own pet ran to its feet about a beggar: the resident answers in its own manner. */
    void helpPet(YukkuriEntity pet, YukkuriEntity stray) {
        var site = stray.blockPosition();
        if (npc.job() == HumanNpc.Job.RESIDENT && npc.level().isDay() && !npc.isTrading()) {
            help = new Help(pet, stray, site, now() + HELP_TICKS);
            helpPath = null;
        }
        gesture(npc, npc.attitude() == HumanNpc.Attitude.CRUEL ? "chase" : "warning");
        Expulsion.driveOff(stray, npc, site);
        GameplayLog.event(npc, "npc-care", "action=pet_help pet={} target={} attitude={} distance={}",
                pet.getUUID(), stray.getUUID(), npc.attitude(), Math.round(npc.distanceTo(stray)));
    }

    boolean helping() { return help != null; }

    /** The routine drives this while the answer lasts: the resident steps up to where the beggar stood. */
    void helpStep() {
        var nav = npc.getNavigation();
        double reach = switch (npc.attitude()) { case CARING -> 3.5; case NEUTRAL -> 2.5; case CRUEL -> 1.5; };
        if (npc.distanceToSqr(Vec3.atBottomCenterOf(help.site())) > reach * reach && now() < help.until()) {
            if (helpPath == null) {
                npc.getLookControl().setLookAt(help.stray(), 30, 30);
                helpPath = nav.createPath(help.site(), 0);
                if (helpPath != null && helpPath.canReach() && nav.moveTo(helpPath, .85)) return;
            } else if (!nav.isDone()) return;
        }
        nav.stop();
        var pet = help.pet();
        if (pet.isAlive() && pet.level() == npc.level() && npc.distanceToSqr(pet) <= 36 && npc.hasLineOfSight(pet)) {
            npc.getLookControl().setLookAt(pet, 30, 30);
            npc.swing(InteractionHand.MAIN_HAND);
            pet.showReaction(YukkuriAction.MOOD_FLUTTER, "happy", "", 60);
            pet.life().personality().talk(npc.getUUID(), true);
            GameplayLog.event(npc, "npc-care", "action=pet_comfort pet={} distance={}", pet.getUUID(), npc.distanceTo(pet));
        }
        stopHelp();
    }

    void stopHelp() { help = null; helpPath = null; }

    boolean step(YukkuriEntity y) {
        var warning = warnings.get(y.getUUID());
        if (activeTarget != y || !observable() || !canTarget(y)) return false;
        npc.getLookControl().setLookAt(y, 30, 30);
        if (beatUntil != 0) {
            if (chasing) return chaseContinues(y);
            if (!warning.seen && now() < beatUntil && npc.distanceToSqr(y) <= CONTACT_SQ
                    && y.hasLineOfSight(npc) && aheadOf(y, npc)) {
                warning.seen = true;
                var contact = SocialDisposition.contact(y, npc);
                double confidence = SocialDisposition.confidence(contact.esteem());
                String face = contact.wariness() > confidence ? "teary" : "surprise";
                if (y.showReaction(face, "", 40)) {
                    GameplayLog.event(y, "npc-care", "action=street_warning_seen resident={} face={} wariness={} confidence={}",
                            npc.getUUID(), face, contact.wariness(), confidence);
                }
            }
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
            GameplayLog.event(npc, "npc-care", "action=street_warn target={} health={} actions={} site=null", y.getUUID(), y.getHealth(), actions);
            return true;
        }
        if (!crowding(y)) return false;
        warning.parted = false;
        if (interrupted(y, warning)) return false;
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
        if (!nav.moveTo(path, .85)) return false;
        beatUntil = now() + ROUTE_TICKS;
        chasing = true;
        chaseStand = Vec3.atBottomCenterOf(stand);
        actions++;
        cue(npc, y, "chase");
        GameplayLog.event(npc, "npc-care", "action=street_chase target={} health={} actions={}", y.getUUID(), y.getHealth(), actions);
        return true;
    }

    private boolean chaseContinues(YukkuriEntity y) {
        return HumanWorld.CRUELTY.get() && now() < beatUntil && !npc.getNavigation().isDone() && !npc.horizontalCollision
                && npc.distanceToSqr(y) <= CONTACT_SQ && aheadOf(npc, y) && dryStreet(y, y.position(), away(y))
                && dryStreet(y, chaseStand, away(y))
                && !interrupted(y, warnings.get(y.getUUID()));
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
        if (help != null && helpPath == null && now() >= help.until()) stopHelp();
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
        if (lastPetted != null && (!lastPetted.isAlive() || lastPetted.level() != npc.level()
                || npc.distanceToSqr(lastPetted) > PARTED_SQ)) lastPetted = null;
        scolded.values().removeIf(until -> now() >= until);
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
        var interventions = new ListTag();
        warnings.forEach((target, warning) -> {
            if (warning.blockedUntil <= now()) return;
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
            var warning = new Warning(saved.getLong("Until") - DAY);
            warning.blockedUntil = saved.getLong("Until");
            warnings.put(saved.getUUID("Target"), warning);
        }
    }

    void cancel() { activeTarget = null; beatUntil = 0; chasing = false; chaseStand = null; }

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
                && witness.hasLineOfSight(npc) && (witness == activeTarget
                ? chasing || beatUntil - now() <= 10 && warnings.get(activeTarget.getUUID()).seen
                : !witness.status().identity().companion() && witness.hasLineOfSight(activeTarget))
                && canTarget(activeTarget);
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
        gesture(actor, line);
    }

    private static void gesture(HumanNpc actor, String line) {
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
        private List<YukkuriEntity> companions = List.of();
        private final Predicate<YukkuriEntity> obstacle = y -> y.isAlive() && !companions.contains(y);
        private boolean homeward;
        private boolean sheltering;
        private int lagTicks;
        private long nextOuting;
        private Vec3 resume;
        private boolean toPark;
        private PlayBlockEntity facility;
        private int lingerTicks, tiredTicks;

        Stroll(HumanNpc npc) {
            this.npc = npc;
            setFlags(EnumSet.of(Flag.MOVE));
        }

        boolean outing() { return homeward || !companions.isEmpty(); }
        boolean escorts(YukkuriEntity pet) { return !homeward && companions.contains(pet); }
        boolean parkLingering() { return facility != null && resume == null && atDestination(); }
        PlayBlockEntity parkFacility() { return parkLingering() && !facility.isRemoved() ? facility : null; }

        void save(CompoundTag tag) {
            if (outing()) tag.putBoolean("OutingReturn", true);
            if (nextOuting > 0) tag.putLong("NextOuting", nextOuting);
        }
        void load(CompoundTag tag) {
            companions = List.of();
            homeward = tag.getBoolean("OutingReturn");
            nextOuting = tag.getLong("NextOuting");
        }

        @Override public boolean canUse() {
            return npc.tickCount >= nextAt && !npc.leadingWalk() && (homeward ? !npc.isTrading() : npc.streetActive());
        }

        @Override public void start() {
            detours = 0;
            finished = false;
            resume = null;
            if (homeward) { returnHome("resume"); return; }
            if (npc.level().isRaining()) {
                if (npc.level().canSeeSky(npc.blockPosition()) && shelter()) return;
                route(Vec3.atBottomCenterOf(npc.home()));
                return;
            }
            if (startOuting()) return;
            Vec3 stand = DefaultRandomPos.getPos(npc, 8, 2);
            route(stand == null ? Vec3.atBottomCenterOf(npc.home()) : stand);
        }

        private boolean startOuting() {
            var yard = npc.yardBowl();
            if (yard == null || npc.attitude() != HumanNpc.Attitude.CARING || npc.level().getGameTime() < nextOuting
                    || npc.blockPosition().distSqr(npc.home()) > 100) return false;
            var ready = npc.animals(Custody.Role.COMPANION).stream()
                    .filter(pet -> pet.blockPosition().distSqr(yard) <= 100 && pet.outing().wants(npc)).limit(2).toList();
            if (ready.isEmpty()) return false;
            var landmarks = npc.level().getEntitiesOfClass(Marker.class, new AABB(npc.home()).inflate(18),
                    m -> npc.isWithinRestriction(m.blockPosition()) && (m.getTags().contains("mcy_park_walk")
                            || m.getTags().contains("mcy_yard_street")));
            landmarks.sort(Comparator.comparing((Marker m) -> !m.getTags().contains("mcy_park_walk"))
                    .thenComparingDouble(npc::distanceToSqr));
            // As with the companion cover search, bound route probes when several lots overlap.
            boolean routed = false;
            for (var marker : landmarks.stream().limit(8).toList()) {
                if (!route(marker.position())) continue;
                routed = true;
                toPark = marker.getTags().contains("mcy_park_walk");
                break;
            }
            if (!routed) {
                var stand = DefaultRandomPos.getPos(npc, 12, 2);
                if (stand == null || !npc.level().canSeeSky(BlockPos.containing(stand)) || !route(stand)) return false;
            }
            companions = ready;
            nextOuting = npc.level().getGameTime() + 3000; // the resident's existing rest between walks
            for (var pet : companions) pet.outing().leave(npc, yard);
            lagTicks = 0;
            GameplayLog.event(npc, "npc-care", "action=outing_depart pets={} destination={}", companions.size(), GameplayLog.at(destination));
            return true;
        }

        private void returnHome(String reason) {
            if (!homeward) GameplayLog.event(npc, "npc-care", "action=outing_turn reason={}", reason);
            homeward = true;
            clearPark();
            sheltering = false;
            resume = null;
            detours = 0;
            var home = Vec3.atBottomCenterOf(npc.home());
            if (npc.distanceToSqr(home) < .5) finishReturn("home");
            else if (!route(home)) findReturnShelter();
        }

        private boolean beginLinger() {
            toPark = false;
            var center = BlockPos.containing(destination);
            var level = npc.level();
            var found = BlockPos.betweenClosedStream(center.offset(-12, -2, -12), center.offset(12, 2, 12))
                    .filter(level::hasChunkAt).map(level::getBlockEntity)
                    .filter(PlayBlockEntity.class::isInstance).map(PlayBlockEntity.class::cast)
                    .min(Comparator.comparingDouble(play -> play.getBlockPos().distSqr(center))).orElse(null);
            if (found == null) return false;
            var facing = found.facing();
            for (var side : List.of(facing.getClockWise(), facing.getCounterClockWise(), facing)) {
                var pos = found.getBlockPos().relative(side, found.kind().depth + (side == facing ? 3 : 2));
                var stand = Vec3.atBottomCenterOf(pos);
                if (!npc.isWithinRestriction(pos) || !level.hasChunkAt(pos) || !level.getFluidState(pos).isEmpty()
                        || !level.getBlockState(pos.below()).isFaceSturdy(level, pos.below(), Direction.UP)
                        || !level.noCollision(npc, npc.getBoundingBox().move(stand.subtract(npc.position())))
                        || !route(stand)) continue;
                facility = found;
                detours = 0;
                GameplayLog.event(npc, "npc-care", "action=outing_park facility={} kind={} stand={}",
                        facility.getBlockPos(), facility.kind(), GameplayLog.at(stand));
                return true;
            }
            return false;
        }

        private void linger() {
            npc.getNavigation().stop();
            npc.getLookControl().setLookAt(facility.getBlockPos().getCenter());
            boolean playing = false, waitingForRest = false, done = true;
            for (var pet : companions) {
                boolean atPlay = pet.playLife().active();
                playing |= atPlay;
                waitingForRest |= pet.outing().tired() && !pet.life().isSleeping() && !atPlay;
                done &= pet.outing().doneAtPark();
            }
            tiredTicks = waitingForRest ? tiredTicks + 1 : 0;
            lingerTicks++;
            if (done) returnHome("park_done");
            else if (tiredTicks > 300) returnHome("tired");
            else if (lingerTicks >= 3000 || lingerTicks >= 2400 && !playing) returnHome("park_time");
        }

        private void clearPark() {
            toPark = false;
            facility = null;
            lingerTicks = tiredTicks = 0;
        }

        private void finishReturn(String result) {
            GameplayLog.event(npc, "npc-care", "action=outing_{}", result);
            homeward = false;
            companions = List.of();
            finished = true;
        }

        private void findReturnShelter() {
            if (npc.onGround() && !npc.isInWaterOrBubble() && !npc.level().canSeeSky(npc.blockPosition())) finishReturn("shelter");
            else if (shelter()) sheltering = true;
            else finishReturn("return_blocked");
        }

        private boolean shelter() {
            int probes = 0;
            for (var pos : BlockPos.withinManhattan(npc.blockPosition(), 5, 1, 5)) {
                if (!npc.isWithinRestriction(pos) || !npc.level().hasChunkAt(pos) || npc.level().canSeeSky(pos)
                        || !npc.level().getFluidState(pos).isEmpty()
                        || !npc.level().getBlockState(pos.below()).isFaceSturdy(npc.level(), pos.below(), Direction.UP)) continue;
                var stand = Vec3.atBottomCenterOf(pos);
                if (!npc.level().noCollision(npc, npc.getBoundingBox().move(stand.subtract(npc.position())))) continue;
                if (route(stand)) return true;
                if (++probes == 8) break;
            }
            return false;
        }

        @Override public boolean canContinueToUse() {
            return !finished && !npc.isTrading() && !npc.leadingWalk() && (outing() || npc.streetActive());
        }
        @Override public boolean requiresUpdateEveryTick() { return true; }

        @Override public void tick() {
            // Vanilla still ticks running goals before its next continuation check.
            if (finished) return;
            if (!homeward && !companions.isEmpty()) {
                if (!npc.level().isDay()) { returnHome("night"); return; }
                if (npc.level().isRaining()) { returnHome("rain"); return; }
                if (npc.avoidingStreet()) { returnHome("danger"); return; }
                YukkuriEntity lagging = null;
                double lag = 0;
                // Give physiology a half-second sample interval. Death, unload and custody end the escort at once.
                boolean checkNeeds = npc.tickCount % 10 == 0;
                for (int i = 0; i < companions.size(); i++) {
                    var pet = companions.get(i);
                    if (!pet.isAlive() || !pet.outing().escorted()
                            || checkNeeds && !(facility == null ? pet.outing().fit() : pet.outing().sound())) {
                        returnHome("companion");
                        return;
                    }
                    if (facility != null) continue;
                    double behind = npc.distanceTo(pet) - pet.outing().outerDistance();
                    if (behind > lag) {
                        lagging = pet;
                        lag = behind;
                    }
                }
                if (facility != null) {
                    if (facility.isRemoved()) { returnHome("park_done"); return; }
                    if (parkLingering()) { linger(); return; }
                } else if (lagging != null) {
                    npc.getLookControl().setLookAt(lagging, 30, 30);
                    npc.getNavigation().setSpeedModifier(.30);
                    if (++lagTicks >= ROUTE_TICKS) { returnHome("lagging"); return; }
                } else {
                    lagTicks = 0;
                    npc.getNavigation().setSpeedModifier(.55);
                }
            }
            if (atDestination()) {
                if (resume != null) {
                    var next = resume;
                    resume = null;
                    if (route(next)) return;
                    blockedOutingLeg();
                } else if (!homeward && !companions.isEmpty()) {
                    if (!toPark || !beginLinger()) returnHome("destination");
                } else if (homeward) {
                    finishReturn(sheltering ? "shelter" : "home");
                } else {
                    finished = true;
                }
                return;
            }
            if (npc.position().distanceToSqr(last) > .25) { last = npc.position(); stalled = 0; }
            if (!npc.getNavigation().isDone() && ++stalled < ROUTE_TICKS) {
                var toward = destination.subtract(npc.position()).multiply(1, 0, 1).normalize();
                AABB ahead = npc.getBoundingBox().move(toward.scale(.65)).deflate(.04);
                if (npc.level().getEntitiesOfClass(YukkuriEntity.class, ahead, obstacle).isEmpty()
                        && npc.level().getEntitiesOfClass(Player.class, ahead, player -> !player.isSpectator()).isEmpty()) return;
            }
            if (++detours == 1 && !npc.level().isRaining()) {
                Vec3 side = destination.subtract(npc.position()).multiply(1, 0, 1).normalize().yRot((float) (Math.PI / 2));
                var next = destination;
                if (trySide(side) || trySide(side.scale(-1))) {
                    if (outing()) resume = next;
                    return;
                }
            }
            if (outing()) blockedOutingLeg();
            else if (detours <= 2) route(Vec3.atBottomCenterOf(npc.home()));
            else finished = true;
        }

        private boolean atDestination() { return npc.distanceToSqr(destination) < .5; }

        private void blockedOutingLeg() {
            if (!homeward) returnHome("blocked");
            else if (!sheltering) findReturnShelter();
            else finishReturn("return_blocked");
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
            clearPark();
            if (!companions.isEmpty()) {
                if (!homeward) GameplayLog.event(npc, "npc-care", "action=outing_turn reason=interrupted");
                homeward = true;
                companions = List.of();
            }
            npc.getNavigation().stop();
            nextAt = npc.tickCount + 40 + npc.getRandom().nextInt(80);
            destination = null;
        }
    }
}
