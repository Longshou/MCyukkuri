package io.github.longshou.mcyukkuri.predator;
import io.github.longshou.mcyukkuri.predator.PredatorFlight.Task;
import io.github.longshou.mcyukkuri.predator.PredatorFlight.Visit;
import static io.github.longshou.mcyukkuri.predator.PredatorFlight.OTHER_TASK_LIMIT;
import java.util.function.Predicate;

import io.github.longshou.mcyukkuri.social.Temperament.Gate;
import io.github.longshou.mcyukkuri.coordination.DeadlineMap;
import io.github.longshou.mcyukkuri.world.SearchBudget;
import io.github.longshou.mcyukkuri.world.SearchBudget.Result;

import io.github.longshou.mcyukkuri.mind.EmotionEvent;

import io.github.longshou.mcyukkuri.life.CombatCourage;
import io.github.longshou.mcyukkuri.life.EcologyRoutine;
import io.github.longshou.mcyukkuri.life.EcologyRoutines;
import io.github.longshou.mcyukkuri.life.TravelRange;
import io.github.longshou.mcyukkuri.life.CareBlock;
import io.github.longshou.mcyukkuri.life.CareBlockEntity;
import io.github.longshou.mcyukkuri.life.SurfaceApproach;
import io.github.longshou.mcyukkuri.family.FamilyNest;
import io.github.longshou.mcyukkuri.family.FamilyData;
import io.github.longshou.mcyukkuri.family.ParentCarry;
import io.github.longshou.mcyukkuri.interaction.ItemContact;
import net.minecraft.world.entity.EntityDimensions;
import io.github.longshou.mcyukkuri.entity.Behaviors;
import io.github.longshou.mcyukkuri.entity.YukkuriComponents;
import io.github.longshou.mcyukkuri.entity.YukkuriAction;
import io.github.longshou.mcyukkuri.body.BodyFood;
import io.github.longshou.mcyukkuri.body.MouthPlacement;
import io.github.longshou.mcyukkuri.body.CorpseFood;
import io.github.longshou.mcyukkuri.body.FillingResource;
import io.github.longshou.mcyukkuri.body.RemainsRot;
import io.github.longshou.mcyukkuri.entity.RemainsEntity;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.headwear.PlacedHatEntity;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import io.github.longshou.mcyukkuri.human.HumanCommerce;
import io.github.longshou.mcyukkuri.pet.PetScenes;
import io.github.longshou.mcyukkuri.registry.ModBlocks;
import io.github.longshou.mcyukkuri.registry.ModItems;
import io.github.longshou.mcyukkuri.settlement.DenBarrier;
import io.github.longshou.mcyukkuri.social.Bonds;
import io.github.longshou.mcyukkuri.social.YukkuriPersonality.Trait;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.UUID;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.navigation.FlyingPathNavigation;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.Shapes;
import io.github.longshou.mcyukkuri.pet.BadgeGrade;
import io.github.longshou.mcyukkuri.motor.BodyAbility;
import io.github.longshou.mcyukkuri.motor.ClipMarkers;
import io.github.longshou.mcyukkuri.motor.action.Hop;
import io.github.longshou.mcyukkuri.motor.Motor;
import io.github.longshou.mcyukkuri.motor.MouthOccupancy.Purpose;
import io.github.longshou.mcyukkuri.motor.Reason;
import io.github.longshou.mcyukkuri.motor.geom.Gait;
import io.github.longshou.mcyukkuri.motor.geom.Ballistic;
import io.github.longshou.mcyukkuri.motor.geom.ShelfApproach;
import io.github.longshou.mcyukkuri.motor.surface.Floor;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.neoforge.event.EventHooks;

/** A bounded, interruptible hunt. Movement uses the engine's flying paths and block collision. */
public final class PredatorLife implements EcologyRoutine {
    /** Acquisition stays within a loaded local view; an active hunt uses bounded path segments to continue past it. */
    private static final double HUNT_RANGE = 60;
    /** Leave margin below the predator-only follow range so a moving target is still reachable by each path search. */
    private static final double FLIGHT_PATH_SEGMENT = 24;
    private static final double PATROL_RADIUS = 60;
    public enum Order { NATURAL, PEACEFUL, HUNT, RETURN }
    /** One attack run: climb above the target, stoop onto it, pull away, circle back (project design, after the Phantom's swoop). */
    private enum Pass { APPROACH, STOOP, WITHDRAW }
    /** A feeding bite and a combat strike hurt less than the former 2.0 so a blocker survives several passes (project design). */
    private static final float BITE_DAMAGE = 1.5f, COMBAT_DAMAGE = 1.5f;
    /** Ticks a predator stays and eats after one feeding bite, and stays settled after a combat kill (project design). */
    private static final int EAT_PAUSE = 160, KILL_PAUSE = 100;
    private static final int SWOOP_GAP = 40, CONTACT_HOLD = 5;
    /** A 6-block pull-away with up to 4.5 blocks of lift needs time to accelerate and brake. */
    private static final int WITHDRAW_LIMIT = 64;
    private static final double SWOOP_HEIGHT = 4.5;
    private final YukkuriEntity animal;
    private final NestBuild nestBuild;
    private final StoopProgress stoop = new StoopProgress();
    private BlockPos lostNest;
    private long lostNestUntil;
    private Task task = Task.NONE;
    private Visit visiting;
    private final DeadlineMap<Visit> failedVisits = new DeadlineMap<>();
    private Order order = Order.NATURAL;
    private UUID commander;
    private YukkuriEntity prey;
    private ItemEntity food;
    private RemainsEntity corpse;
    private Vec3 destination;
    private BlockPos landingStand;
    private BlockPos lastDryStand;
    private long nextLandingSearch;
    private int landingSearchRadius = 8;
    private Long lastPerch, previousPerch;
    private int watchTicks;
    private long nextPerchSearch;
    private int ticks, cooldown, biteTicks, patrolWait = 160, reactWait, restrainWait;
    /** A beaten fighter's landing search keeps this far from the body that beat it. */
    private static final int WITHDRAW_CLEARANCE_SQR = 256;
    private static final double ARMED_PREY_PENALTY = 60;
    private Vec3 landingAvoid;
    /** Combat (true) is a continuous defensive fight that never feeds or depends on hunger; predation (false) is bite, then eat. */
    private boolean retaliation;
    private Pass pass = Pass.APPROACH;
    private int passTicks, lastHit = -SWOOP_GAP, hits, restTicks, pendingRest;
    private String restReason = "";
    private boolean contactHold;
    private Vec3 withdrawGoal;
    /** The current direct flight command, consumed by travel after the engine has updated its controls. */
    private Vec3 flightGoal;
    private Vec3 takeoffExit;
    private double flightSpeed;
    private int flightGoalTick = -1;
    private RemainsEntity eatRemains;
    private LivingEntity territoryTarget, warnedIntruder;
    private BlockPos drivenFrom;
    private long avoidTerritoryUntil;
    private YukkuriEntity bittenPrey;
    private long bittenAt;
    /** Prey with an owner that has already been reported to that owner (game time until which the alert is quiet). */
    private static final DeadlineMap<UUID> ALERTED = new DeadlineMap<>();
    private boolean naturalSpawn;

    public PredatorLife(YukkuriEntity animal) { this.animal = animal; nestBuild = new NestBuild(animal, this); }
    /** Owner alerts never outlive their server. */
    public static void resetAlerts() { ALERTED.clear(); }
    public static void forget(Set<UUID> ids) { for (UUID id : ids) ALERTED.remove(id); }
    public boolean active() { return task != Task.NONE; }
    public Order order() { return obedient() && validCommander() ? order : Order.NATURAL; }
    public String activity() {
        return switch (task) {
            case TAKEOFF -> Behaviors.PREDATOR_TAKEOFF;
            case CHASE -> Behaviors.PREDATOR_CHASE;
            case DIVE -> Behaviors.PREDATOR_DIVE;
            case SCAVENGE -> Behaviors.PREDATOR_FEED;
            case EAT -> Behaviors.PREDATOR_FEED;
            case RETURN, LAND -> Behaviors.PREDATOR_RETURN;
            case PATROL -> Behaviors.PREDATOR_FLY;
            case PERCH -> watchTicks > 0 ? Behaviors.PREDATOR_PERCH : Behaviors.PREDATOR_FLY;
            case FLAP -> Behaviors.PREDATOR_FLAP;
            case WARN, YIELD_TERRITORY -> Behaviors.PREDATOR_SCREECH;
            case THREAT_APPROACH -> Behaviors.PREDATOR_FLY;
            case THREAT_DIVE -> Behaviors.PREDATOR_DIVE;
            case BRANCH_HOP -> Behaviors.PREDATOR_PERCH;
            case FIRST_FLIGHT, BROOD_FLIGHT, DELIVER, VISIT_YOUNG -> Behaviors.PREDATOR_FLY;
            case FOUND -> nestBuild.activity();
            default -> Behaviors.IDLE;
        };
    }
    public String animation() {
        if (task == Task.WARN || task == Task.YIELD_TERRITORY) return Behaviors.PREDATOR_FLAP;
        if (task == Task.FLAP && watchTicks > 0) return Behaviors.PREDATOR_FLAP;
        if (animal.fallDistance > 0 && animal.childhood().nestling() && animal.childhood().wingPractice() > 0
                && !animal.family().awaitingLanding()) return Behaviors.PREDATOR_FLAP;
        if (task == Task.PERCH && watchTicks > 0) return Behaviors.PREDATOR_PERCH;
        if (task == Task.FOUND) return nestBuild.animation();
        if (task == Task.LAND && animal.predatorFlying()) return animal.isInWaterOrBubble() ? "predator_fly" : "predator_land";
        if (biteTicks > 0) return "predator_bite";
        if (!animal.predatorFlying()) return null;
        return task == Task.DIVE || task == Task.THREAT_DIVE ? "predator_dive" : "predator_fly";
    }
    /** The certificate belongs to this animal; putting on someone else's token cannot teach commands. */
    public boolean obedient() {
        return EcologyRoutines.predator(animal) && animal.pet().effectiveGrade().id() >= BadgeGrade.SILVER.id()
                && animal.life().personality().knowsWait() && animal.life().personality().temperament().is(Gate.CLEVER);
    }
    private boolean validCommander() { return commander != null && HumanCommerce.ownedBy(animal, commander); }
    public boolean command(Player player, Order requested) {
        if (!obedient()) {
            GameplayLog.limited(animal,"predator","order_untrained",20,"order refused; player={} order={} reason=untrained",player.getUUID(),requested);
            defy(player); return false;
        }
        if (!animal.pet().near(player) || !HumanCommerce.ownedBy(animal, player.getUUID())
                || animal.pet().busy() || animal.operations().active() || animal.isCommandPlaying() && reactWait == 0) {
            GameplayLog.limited(animal,"predator","order_busy",20,"order refused; player={} order={} near={} owner={} pet_busy={} operation={} command_playing={}",
                    player.getUUID(),requested,animal.pet().near(player),HumanCommerce.ownedBy(animal,player.getUUID()),animal.pet().busy(),animal.operations().active(),animal.isCommandPlaying());
            return false;
        } // its own bow/ignore reaction does not block the next order
        if (requested == Order.RETURN && animal.life().homeStand() == null) {
            GameplayLog.event(animal,"predator","order refused; player={} order={} reason=no_home",player.getUUID(),requested);return false;
        }
        stop(); order = requested; commander = player.getUUID(); cooldown = 0;
        GameplayLog.event(animal,"predator","order accepted; player={} order={}",player.getUUID(),requested);
        animal.setPersistenceRequired();
        if (reactWait == 0) { reactWait = 60; PetScenes.act(animal, "bow", "happy", "predator_obey", 40, player); }
        return true;
    }
    /** An untrained predator ignores its owner's order. */
    private void defy(Player player) {
        if (reactWait > 0 || !EcologyRoutines.predator(animal) || active() || !animal.pet().near(player) || !HumanCommerce.ownedBy(animal, player.getUUID()) || animal.pet().busy()
                || animal.operations().active() || animal.isCommandPlaying() || animal.isPanicking() || animal.life().isSleeping()) return;
        reactWait = 100; PetScenes.act(animal, "ignore", "hmph", "predator_defy", 40, null);
    }
    /** An obedient predator that sees a protected yukkuri it would otherwise go for holds itself back, once a minute at most. */
    private boolean restrain() {
        if (restrainWait > 0 || !obedient() || animal.isCommandPlaying() || animal.life().isSleeping()) return false;
        var tempting = level().getEntitiesOfClass(YukkuriEntity.class, animal.getBoundingBox().inflate(10), y -> huntable(y, false) && HumanCommerce.protectedAnimal(y) && animal.hasLineOfSight(y))
                .stream().min(Comparator.comparingDouble(animal::distanceToSqr)).orElse(null);
        if (tempting == null) return false;
        restrainWait = 1200; PetScenes.act(animal, "restrain", "neutral", "predator_restrain", 60, tempting);
        return true;
    }
    /** The owner of a yukkuri that a predator has started to chase hears of it; a pet near its owner runs to them. */
    private void alertOwner() {
        if (prey == null || !prey.isAlive()) return;
        var id = prey.pet().owner(); if (id == null) return;
        var owner = level().getServer().getPlayerList().getPlayer(id);
        if (owner == null || owner.level() != level() || owner.distanceToSqr(prey) > 4096) return;
        long now = level().getGameTime();
        if (ALERTED.active(prey.getUUID(), now)) return;
        if (ALERTED.size() > 128) { ALERTED.prune(now); if (ALERTED.size() > 1024) ALERTED.clear(); }
        ALERTED.put(prey.getUUID(), now + 600);
        owner.displayClientMessage(Component.translatable("pet.mcyukkuri.predator.alert", prey.getDisplayName()), true);
        if (!prey.life().isSleeping() && owner.distanceToSqr(prey) <= 256) { prey.mind().felt(EmotionEvent.FRIGHTENED); prey.showReaction("surprise", "afraid", 80); }
    }
    private ServerLevel level() { return (ServerLevel)animal.level(); }
    private boolean ready() {
        return readyExceptMeal() && animal.life().mealTicks() == 0;
    }
    /** An eat pause (or the landing that leads into it) is the very meal, so a mouthful does not end it. */
    public boolean needsBroodFlight() {
        if (task == Task.BROOD_FLIGHT) return true;
        var site = animal.family().canopyBirth();
        return site != null && !SurfaceApproach.at(animal, site.stand()) && broodFlightReady()
                && !Task.BROOD_FLIGHT.needsLanding(animal.life().energy()) && freeStand(site.stand());
    }

    private boolean broodFlightReady() {
        return canSustainFlight() && !animal.isNoAi() && !animal.isPanicking() && !animal.isCommandPlaying()
                && !animal.operations().active() && !animal.isOnFire() && !animal.life().isSleeping();
    }

    private boolean runnable() {
        if (task == Task.BROOD_FLIGHT) return broodFlightReady() && animal.family().canopyBirth() != null;
        return task == Task.LAND && animal.predatorFlying() ? readyForLanding()
                : task == Task.EAT ? readyExceptMeal() : ready();
    }
    /** An interrupted airborne hunt still owns its physical return; water and fright are reasons to land, not to stop flapping. */
    public boolean recoveringFlight() { return task == Task.LAND && animal.predatorFlying() && readyForLanding(); }
    private boolean readyForLanding() {
        return EcologyRoutines.predator(animal) && canSustainFlight() && !animal.isNoAi()
                && !animal.isCommandPlaying() && !animal.operations().active() && !animal.pet().busy()
                && !animal.life().isSleeping() && (!animal.family().active() && animal.family().brood() == null
                || animal.family().wildStemBrood());
    }
    private boolean readyExceptMeal() {
        return EcologyRoutines.predator(animal) && animal.isAlive() && !animal.isNoAi() && !animal.isPassenger()
                && !animal.isPanicking() && !animal.isCommandPlaying() && !animal.operations().active()
                && !animal.pet().busy() && !animal.life().isSleeping()
                && !animal.family().active() && animal.family().brood() == null
                && !animal.isInWaterOrBubble() && !animal.isOnFire();
    }
    public boolean wantsHunt() {
        if (!ready() || !animal.family().adult() || animal.life().energy() < 30 || !CombatCourage.willing(animal)) return false;
        if (order() == Order.PEACEFUL || order() == Order.RETURN) return false;
        boolean midday = wildAdult() && PerchScore.midday(level().getDayTime(), !level().isRaining());
        double threshold = order() == Order.HUNT ? 75 : PerchScore.huntThreshold(animal.life().personality().trait(Trait.BOLDNESS), midday);
        if (provisionChild() != null) threshold += 75 + 10 * animal.life().personality().temperament().caring()
                - PerchScore.huntThreshold(animal.life().personality().trait(Trait.BOLDNESS), false);
        return animal.life().nutrition() < threshold;
    }
    /**
     * Starts an ordinary pursuit against the individual that just physically hurt this predator.
     * This is deliberately separate from hunger: a full adult can still drive off an attacker.
     */
    public boolean retaliate(YukkuriEntity attacker) {
        // Repeated hits from the same attacker acknowledge the existing defence without restarting its phase or deadline.
        if (retaliation && prey == attacker && retaliationReady()) return true;
        if (!retaliationReady() || !visibleHuntable(attacker, true)
                || !canFlyTo(attacker.blockPosition()) || !safeHuntDestination(attacker)) {
            GameplayLog.limited(animal,"predator","retaliation_refused",200,
                    "retaliation refused; attacker={} health={}/{} energy={} order={} ready={}",
                    attacker==null?"none":attacker.getUUID(),animal.getHealth(),animal.getMaxHealth(),animal.life().energy(),order(),retaliationReady());
            return false;
        }
        interruptFounding();
        prey = attacker; food = null; corpse = null; destination = null;
        task = Task.TAKEOFF; mode(true, "struck_by " + attacker.getUUID());
        // The Goal may already be running for a previous target; reset its shared pursuit setup now.
        start();
        return true;
    }
    private boolean retaliationReady() {
        return ready() && animal.family().adult() && !animal.childhood().nestling() && CombatCourage.willing(animal)
                && animal.life().energy() >= 20 && order() != Order.PEACEFUL && order() != Order.RETURN;
    }
    /** Read-only status detail; StatusKey owns the command-facing key. */
    public String describe() {
        return "task=" + task.name().toLowerCase() + " mode=" + (retaliation ? "combat" : "predation") + " rest=" + restTicks + " target=" + (prey == null ? "none" : "#" + prey.getId())
                + " retaliation=" + retaliation + " boldness=" + animal.life().personality().boldness() + " retreatRatio=" + CombatCourage.retreatRatio(animal) + " huntRange=" + HUNT_RANGE
                + " preySpeed=" + (prey == null ? 0 : prey.getAttributeValue(Attributes.MOVEMENT_SPEED)) + " blocking=" + blocking();
    }
    private String blocking() {
        if (!EcologyRoutines.predator(animal)) return "not_predator";
        if (!animal.isAlive()) return "dead";
        if (animal.isNoAi()) return "no_ai";
        if (animal.isPassenger()) return "passenger";
        if (animal.isPanicking()) return "panic";
        if (animal.isCommandPlaying()) return "command";
        if (animal.operations().active() || animal.pet().busy()) return "occupied";
        if (animal.life().isSleeping() || animal.life().mealTicks() != 0) return "life";
        if (!animal.family().adult()) return "not_adult";
        if (!CombatCourage.willing(animal)) return "courage_or_health";
        if (animal.life().energy() < 20) return "energy";
        if (order() == Order.PEACEFUL || order() == Order.RETURN) return "order_" + order().name().toLowerCase();
        if (!active() && cooldown > 0) return "cooldown";
        return "none";
    }
    public boolean mayHunt(YukkuriEntity target) { return visibleHuntable(target, true) && safeHuntDestination(target); }
    private boolean safeHuntDestination(YukkuriEntity target) {
        if (target == null) return false;
        if (!target.isInWaterOrBubble()) return true;
        if (biteApproach(target) == null) {
            GameplayLog.limited(animal, "predator", "water_prey_no_contact", 200,
                    "water prey refused target={} reason=mouth_below_clearance preyFeet={} preyHeight={} mouthReach={}",
                    target.getUUID(), target.position(), target.getBbHeight(), mouthReach());
            return false;
        }
        Landing escape = findDryLanding(target.blockPosition(), 24, null);
        if (escape != null) return true;
        GameplayLog.limited(animal, "predator", "water_prey_no_landing", 200,
                "water prey refused target={} reason=no_reachable_dry_landing", target.getUUID());
        return false;
    }
    /** The yukkuri this predator is currently chasing or diving at, or null. */
    public YukkuriEntity prey() { return task == Task.CHASE || task == Task.DIVE ? prey : null; }
    public boolean pursuing(YukkuriEntity target) {
        return prey == target && (task == Task.TAKEOFF || task == Task.CHASE || task == Task.DIVE);
    }
    /** A parent steps in front of its child (grief/GriefLife sacrifice): the chase turns to the parent if the predator would hunt it. */
    public boolean threatens(YukkuriEntity child) {
        return prey() == child || prey() == null && bittenPrey == child && level().getGameTime() - bittenAt < 100;
    }
    public boolean divert(YukkuriEntity shield, YukkuriEntity child) {
        if (!threatens(child) || !readyExceptMeal() || !animal.family().adult() || !CombatCourage.willing(animal)
                || animal.life().energy() < 20 || order() == Order.PEACEFUL || order() == Order.RETURN
                || !mayHunt(shield) || !animal.hasLineOfSight(shield) || animal.distanceToSqr(shield) > 36) return false;
        // An audible taunt redirects the real attack at once, including just after a bite.
        // Interrupting a mouthful uses the same real-food interruption path as other threats.
        boolean chasing = prey() != null;
        animal.life().interrupt();
        prey = shield; mode(true, "blocked_by " + shield.getUUID()); food = null; corpse = null; destination = null;
        if (!chasing) { task = Task.TAKEOFF; start(); }
        GameplayLog.event(animal,"predator","taunt accepted child={} shield={} phase={} retaliation={}",
                child.getUUID(),shield.getUUID(),task,retaliation);
        return true;
    }
    /** Whether the target remains legally huntable; respect=false ignores the protection an educated predator honours. */
    private boolean huntable(YukkuriEntity target, boolean respect) {
        if (target == animal || !target.isAlive() || target.body().dying() || target.isInvulnerable() || EcologyRoutines.predator(target)
                || EcologyRoutines.house(target) || target.isPassenger() || target.operations().active()
                || target.getScale() * target.growthScale() > animal.getScale() * animal.growthScale() * 1.35) return false;
        if (target.mind().foulFilling() && animal.life().nutrition() >= 15) return false;
        // Educated animals respect protected pets; uneducated ones retain the wild risk after purchase.
        if (respect && obedient() && HumanCommerce.protectedAnimal(target)) return false;
        if (animal.family().caresFor(target) || target.getUUID().equals(animal.family().partner())) return false;
        var shelter = target.habitat().shelter();
        if (shelter != null && shelter.owns(target.getUUID()) && shelter.sound() && shelter.contains(target.blockPosition())) return false;
        // A den whose mouth is screened with twigs shelters everyone inside it, residents or not (settlement/DenSeal).
        var screened = DenBarrier.shielding(target);
        if (screened != null) {
            GameplayLog.limited(animal, "predator", "barrier_reject", 200, "target rejected reason=barrier target={} den={} mouth={}",
                    target.getUUID(), screened.getBlockPos().toShortString(), screened.entrance().toShortString());
            return false;
        }
        return true;
    }
    /** New hunts require sight in the local search radius; an active hunt deliberately does not. */
    private boolean visibleHuntable(YukkuriEntity target, boolean respect) {
        return huntable(target, respect) && animal.distanceToSqr(target) <= HUNT_RANGE * HUNT_RANGE
                && animal.hasLineOfSight(target);
    }
    public void naturalSpawn() { naturalSpawn = true; }
    public void tick() { EcologyRoutines.tick(this); }
    @Override public boolean supportsPassiveTick() {
        return !animal.level().isClientSide && EcologyRoutines.predator(animal);
    }
    @Override public void passiveTick() {
        if (naturalSpawn) {
            naturalSpawn = false;
            placeInitialNest();
        }
        if (bittenPrey != null && level().getGameTime() - bittenAt >= 100) bittenPrey = null;
        if (cooldown > 0) cooldown--;
        if (biteTicks > 0) biteTicks--;
        if (patrolWait > 0) patrolWait--;
        if (reactWait > 0) reactWait--;
        if (restrainWait > 0) restrainWait--;
        if (active() && !runnable()) stop();
        if (!animal.blockPosition().equals(lastDryStand) && dryLanded()) lastDryStand = animal.blockPosition();
        if (animal.predatorFlying() && !active()) {
            if (dryLanded() || !canSustainFlight()) animal.setPredatorFlying(false);
            else land();
        }
        if (animal.predatorFlying() && !canSustainFlight()) {
            GameplayLog.limited(animal, "predator", "flight_incapable", 200,
                    "flight lost reason=body_or_energy energy={} health={}", animal.life().energy(), animal.getHealth());
            animal.setPredatorFlying(false);
            stop();
        }
        if (animal.isNoAi()) { cancelNestSearch(); return; }
        if (animal.predatorFlying() && animal.tickCount % 20 == 0)
            animal.life().spendEnergy(Math.min(1, animal.life().energy()));
    }

    private boolean playerNear(Vec3 feet) {
        return level().players().stream().anyMatch(player -> player.distanceToSqr(feet) <= 32 * 32);
    }

    /** Initial placement consumes the request once, including saved chunk-generation spawns. */
    private void placeInitialNest() {
        String refusal = initialNestRefusal();
        if (refusal != null) {
            GameplayLog.event(animal, "predator", "spawn nest refused reason={}", refusal);
            return;
        }
        var sites = PredatorNestSearch.initialSites(level(), animal);
        int siteChecks = 0;
        String reason = "no_site";
        for (var rank : sites) {
            var site = rank.site();
            if (playerNear(site.pad())) { reason = "player_near_site"; continue; }
            // A candidate and the ordinary home assignment can each make one flight check.
            if (++siteChecks > 4) break;
            if (!site.reachable(animal)) { reason = "no_flight_route"; continue; }
            if (!placeInitialNest(site)) { reason = "placement_or_claim_refused"; continue; }
            GameplayLog.event(animal, "predator", "spawn nest placed site={} tree_height={} score={} cleared_leaves={} candidates={}",
                    site.cell(), site.height(), rank.score(), site.clearing().size(), sites.size());
            return;
        }
        GameplayLog.event(animal, "predator", "spawn nest refused reason={} candidates={}", reason, sites.size());
    }

    private String initialNestRefusal() {
        if (animal.isNoAi() || animal.isPassenger()) return "body_unavailable";
        if (playerNear(animal.position())) return "player_near_spawn";
        if (animal.life().home() != null || HumanCommerce.protectedAnimal(animal)) return "protected";
        if (!EventHooks.canEntityGrief(level(), animal)) return "mob_griefing";
        return null;
    }

    private boolean placeInitialNest(CanopySite site) {
        var level = level();
        var cell = site.cell();
        var old = level.getBlockState(cell);
        var above = cell.above();
        var roof = level.getBlockState(above);
        boolean clearAbove = site.clearing().contains(above);
        if (clearAbove && !level.setBlock(above, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL)) return false;
        boolean placed = level.setBlock(cell, ModBlocks.PREDATOR_NEST.get().defaultBlockState(), Block.UPDATE_ALL);
        var actual = placed ? CanopySite.inspect(level, animal, cell) : null;
        if (actual != null && animal.life().assignHome(GlobalPos.of(level.dimension(), cell))) {
            Motor.relocate(animal, actual.pad(), SurfaceApproach.yaw(actual.pad(), actual.stand()), "spawn_nest_perch");
            return true;
        }
        if (placed) level.setBlock(cell, old, Block.UPDATE_ALL);
        if (clearAbove) level.setBlock(above, roof, Block.UPDATE_ALL);
        return false;
    }
    public boolean canFlyTo(BlockPos pos) {
        if (!level().hasChunkAt(pos) || !level().getFluidState(pos).isEmpty()) return false;
        var navigation = new FlyingPathNavigation(animal, level());
        navigation.setCanPassDoors(!TravelRange.staysBehindDoors(animal));
        SearchBudget.path(animal);
        var path = navigation.createPath(pos, 0);
        return path != null && path.canReach();
    }
    boolean canFlyToFeet(Vec3 feet) {
        return CanopySite.clearBody(level(), animal, feet)
                && (shelfApproach(feet) != null || canFlyTo(BlockPos.containing(feet)));
    }

    public boolean supportsCanopyStand(BlockPos support) {
        return CanopySite.leafTop(level(), support, level().getBlockState(support));
    }

    public void lostNest(BlockPos pos) {
        lostNest = pos.immutable();
        lostNestUntil = level().getGameTime() + 3 * 24000;
        GameplayLog.event(animal, "predator", "nest lost site={} avoid_until={}", pos, lostNestUntil);
    }
    double lostNestPenalty(BlockPos site) {
        return lostNest != null && level().getGameTime() < lostNestUntil && lostNest.distSqr(site) <= 16 ? .5 : 0;
    }
    private PredatorNestSearch nestSearch;
    private void cancelNestSearch() {
        if (nestSearch != null) { nestSearch.cancel(animal); nestSearch = null; }
    }
    /** Claims real existing nests first; budget deferral retains the cursor and never reports absence. */
    public Result findNest() {
        if (!(animal.level() instanceof ServerLevel level) || !EcologyRoutines.predator(animal) || !animal.isAlive()) {
            cancelNestSearch(); return Result.NOT_FOUND;
        }
        if (animal.life().homeStand() != null || !animal.family().adult() && !animal.family().subadult()) {
            cancelNestSearch(); return Result.NOT_FOUND;
        }
        if (naturalSpawn || task == Task.FOUND || nestBuild.wantsSurvey()) { cancelNestSearch(); return Result.DEFERRED; }
        if (nestSearch != null && !nestSearch.current(level, animal)) cancelNestSearch();
        if (nestSearch == null) nestSearch = new PredatorNestSearch(level, animal, false);
        var result = nestSearch.advance(animal, pos -> false, pos -> 0);
        if (result != Result.DEFERRED) cancelNestSearch();
        return result;
    }
    private boolean canUse() {
        if (active()) return runnable();
        if (EcologyRoutines.predator(animal) && needsBroodFlight() && hasAirExit()) {
            destination = animal.family().canopyBirth().stand();
            task = Task.BROOD_FLIGHT;
            return true;
        }
        if (naturalSpawn || !ready()) return false;
        if (animal.childhood().nestling()) {
            if (animal.status().identity().wild() && choosePractice()) return true;
            if (cooldown > 0 || !animal.family().canEatIndependently()) return false;
            if (chooseFeeding()) return true;
            cooldown = 20;
            return false;
        }
        if (carryingPrey()) {
            if (!canSustainFlight() || provisionChild() == null) { releasePrey(null); return false; }
            if (cooldown > 0 || Task.DELIVER.needsLanding(animal.life().energy()) || !hasAirExit()) return false;
            task = Task.DELIVER;
            return true;
        }
        if (familyMealDue()) return false;
        var grounded = groundedChildren(broodPad());
        var visit = groundVisit(grounded);
        if (visit != null) {
            visiting = visit.course();
            destination = visit.stand();
            task = Task.VISIT_YOUNG;
            return true;
        }
        if (cooldown == 0 && chooseTerritoryWarning()) return true;
        if (!hasAirExit()) return false;
        var home = animal.life().homeStand();
        boolean wildAdult = wildAdult();
        boolean roost = wildAdult && needsRoost(home);
        if (home != null && !roost && provisionChild() == null && (order() == Order.RETURN || animal.life().energy() < 35 || level().isNight() || level().isRaining())
                && animal.distanceToSqr(Vec3.atBottomCenterOf(home)) > .64) {
            Vec3 feet = landingFeet(home);
            if (feet != null && canDepart(feet)) { landingStand = home; destination = feet; task = Task.RETURN; return true; }
        }
        double pressure = animal.onGround() ? nestBuild.framePressure() : 0;
        if ((cooldown > 0 && pressure == 0) || animal.life().energy() < 20) return false;
        // If even an available meal would lose, skip the food searches. Otherwise let a real target compete.
        boolean seekFood = !FoundingFrame.chooseWork(pressure, animal.life().nutrition(), true);
        if (seekFood && chooseFeeding()) return true;
        if (!grounded.isEmpty() && animal.distanceToSqr(grounded.getFirst()) <= 4) {
            animal.getLookControl().setLookAt(grounded.getFirst(),20,20);
            return false;
        }
        if (seekFood && wantsHunt() && restrain()) { cooldown = 20; return false; }
        if (pressure > 0 || animal.onGround() && nestBuild.canSurvey()) { task = Task.FOUND; return true; }
        if (wildAdult && !nestBuild.wantsSurvey() && !animal.life().hasUrgentNeed()
                && (roost || animal.life().energy() >= 35 && !level().isNight() && !level().isRaining())
                && level().getGameTime() >= nextPerchSearch) {
            destination = PerchSearch.find(animal, home == null ? animal.blockPosition() : home, lastPerch, previousPerch, roost);
            nextPerchSearch = level().getGameTime() + 200;
            if (destination != null) { task = Task.PERCH; return true; }
        }
        if (patrolWait == 0 && order() != Order.RETURN && animal.family().adult() && animal.life().energy() > 45
                && !level().isNight() && !level().isRaining()) {
            var center = home == null ? animal.position() : Vec3.atBottomCenterOf(home);
            double angle = animal.getRandom().nextDouble() * 2 * Math.PI;
            double distance = 12 + animal.getRandom().nextDouble() * (PATROL_RADIUS - 12);
            var pos = BlockPos.containing(center.add(Math.cos(angle) * distance, 2 + animal.getRandom().nextInt(2), Math.sin(angle) * distance));
            if (canFlyTo(pos)) {
                patrolWait = 240 + animal.getRandom().nextInt(200);
                destination = Vec3.atCenterOf(pos); task = Task.PATROL; return true;
            }
            patrolWait = 80;
        }
        cooldown = 20; return false;
    }
    private boolean slowChild(YukkuriEntity target) {
        return !target.family().adult() && !target.family().subadult()
                && target.getAttributeValue(Attributes.MOVEMENT_SPEED) < Gait.GROUND_ATTRIBUTE;
    }
    public boolean practisingHop() { return task == Task.BRANCH_HOP; }

    private BlockPos familyPad() {
        var pad = animal.childhood().padUnderfoot();
        if (pad != null) return pad;
        pad = animal.childhood().adjacentPad(animal.blockPosition());
        if (pad != null) return pad;
        for (var id : animal.family().caregiverIds()) {
            if (id != null && level().getEntity(id) instanceof YukkuriEntity parent) {
                var home = parent.life().homeStand();
                if (home != null && level().getBlockState(home).is(ModBlocks.PREDATOR_NEST.get())) return home;
            }
        }
        return null;
    }

    private boolean choosePractice() {
        var young = animal.childhood();
        if (!young.practiceDue()) return false;
        var pad = familyPad();
        if (pad == null) return false;
        var here = animal.blockPosition();
        boolean onPad = pad.equals(young.padUnderfoot());
        boolean onBranch = !onPad && CanopySite.tree(level().getBlockState(here.below())) && here.distSqr(pad) <= 4;
        var branches = new ArrayList<Vec3>();
        for (var side : Direction.Plane.HORIZONTAL) {
            var at = pad.relative(side);
            if (!CanopySite.tree(level().getBlockState(at.below()))) continue;
            var feet = landingFeet(at);
            if (feet != null && !SurfaceApproach.at(animal, feet)) branches.add(feet);
        }
        young.restWings();
        if (young.readyToFly() && !branches.isEmpty() && hasAirExit() && (onPad || onBranch || firstFlightLift() > Ballistic.GRAVITY)) {
            destination = branches.getFirst();
            task = Task.FIRST_FLIGHT;
            return true;
        }
        if (onBranch || onPad && animal.getRandom().nextBoolean()) {
            for (var centre : onBranch ? List.of(padFeet(pad)) : branches) {
                double inset = .5 - animal.getBbWidth()/2 - SurfaceApproach.ARRIVAL;
                var feet = new Vec3(Mth.clamp(animal.getX(),centre.x-inset,centre.x+inset),centre.y,
                        Mth.clamp(animal.getZ(),centre.z-inset,centre.z+inset));
                if (!SurfaceApproach.supported(animal, feet) || !CanopySite.clearBody(level(), animal, feet)
                        || !new Hop(feet).check(animal, Hop.Search.admission()).ok()) continue;
                destination = feet;
                task = Task.BRANCH_HOP;
                return true;
            }
        }
        if (!young.canFlap()) return false;
        // Face and approach the open edge without putting the sole outside the pad.
        var edge = onPad && !branches.isEmpty() ? branches.getFirst().subtract(Vec3.atBottomCenterOf(pad)).multiply(1, 0, 1).normalize() : Vec3.ZERO;
        destination = onPad ? padFeet(pad).add(edge.scale(.5 - SurfaceApproach.supportRadius(animal) - SurfaceApproach.ARRIVAL)) : animal.position();
        task = Task.FLAP;
        return true;
    }

    private Vec3 padFeet(BlockPos pad) {
        return Vec3.atBottomCenterOf(pad).add(0, level().getBlockState(pad).getCollisionShape(level(), pad).max(Direction.Axis.Y), 0);
    }
    private double firstFlightLift() {
        return flightAcceleration() * Math.sqrt(animal.childhood().wingPractice()) * BodyAbility.fitness(animal);
    }
    private boolean chooseFeeding() {
        if (wantsHunt() && provisionChild() != null) {
            corpse = level().getEntitiesOfClass(RemainsEntity.class, animal.getBoundingBox().inflate(24), this::fetchable).stream()
                    .min(Comparator.comparingDouble(animal::distanceToSqr)).orElse(null);
            if (corpse != null) { food = null; task = Task.SCAVENGE; return true; }
        }
        if (animal.life().canEat()) {
            food = level().getEntitiesOfClass(ItemEntity.class, animal.getBoundingBox().inflate(10), this::edibleDrop).stream()
                    .min(Comparator.comparingDouble(animal::distanceToSqr)).orElse(null);
            corpse = level().getEntitiesOfClass(RemainsEntity.class, animal.getBoundingBox().inflate(10), this::edibleCorpse).stream()
                    .min(Comparator.comparingDouble(animal::distanceToSqr)).orElse(null);
            if (corpse != null && (food == null || animal.distanceToSqr(corpse) < animal.distanceToSqr(food))) {
                food = null; task = Task.SCAVENGE; return true;
            }
            corpse = null;
            if (food != null) { task = Task.SCAVENGE; return true; }
        }
        if (wantsHunt()) {
            prey = level().getEntitiesOfClass(YukkuriEntity.class, animal.getBoundingBox().inflate(HUNT_RANGE, PerchScore.MAX_HEIGHT + 1, HUNT_RANGE), this::mayHunt).stream()
                    .min(Comparator.<YukkuriEntity>comparingInt(y -> slowChild(y) ? 0 : 1)
                            .thenComparingDouble(y -> slowChild(y) ? y.getAttributeValue(Attributes.MOVEMENT_SPEED) : 0)
                            .thenComparingDouble(y -> animal.distanceToSqr(y) + y.getHealth() - y.mind().preyAppeal()
                                    + (fightsBack(y) ? ARMED_PREY_PENALTY : 0))).orElse(null);
            if (prey != null) {
                GameplayLog.event(animal,"predator","prey selected target={} stage={} speed={} slow_child={} range={} distance={}",
                        prey.getUUID(),prey.family().stage(),prey.getAttributeValue(Attributes.MOVEMENT_SPEED),slowChild(prey),HUNT_RANGE,animal.distanceTo(prey));
                task = Task.TAKEOFF; return true;
            }
        }
        return false;
    }
    BlockPos broodPad() {
        if (!wildAdult()) return null;
        var own = animal.life().homeStand();
        return own != null && level().getBlockState(own).is(ModBlocks.PREDATOR_NEST.get()) ? own : null;
    }

    public boolean rearing() { return animal.getVariant().isPredator() && provisionChild()!=null; }

    private YukkuriEntity provisionChild() {
        var home = broodPad();
        if (home == null) return null;
        var fallen = groundChild(home);
        if (fallen != null) return fallen;
        return level().getEntitiesOfClass(YukkuriEntity.class, new AABB(home), child -> !child.family().subadult()
                && !child.family().adult() && child.isAlive() && animal.family().caresFor(child)).stream()
                .min(Comparator.comparingDouble(child -> child.life().nutrition())).orElse(null);
    }

    private YukkuriEntity groundChild() { return groundChild(broodPad()); }
    private YukkuriEntity groundChild(BlockPos pad) {
        return groundedChildren(pad).stream().findFirst().orElse(null);
    }

    private List<YukkuriEntity> groundedChildren(BlockPos pad) {
        if (pad == null) return List.of();
        return level().getEntitiesOfClass(YukkuriEntity.class, new AABB(pad).inflate(7,12,7), child ->
                child.isAlive() && child.childhood().nestling() && child.onGround() && !child.isPassenger()
                && !child.isInWaterOrBubble() && child.getY() < pad.getY()-2 && animal.family().caresFor(child)
                && !CanopySite.tree(level().getBlockState(child.blockPosition().below())) && animal.hasLineOfSight(child)).stream()
                .sorted(Comparator.comparingDouble(animal::distanceToSqr)).toList();
    }

    private record GroundVisit(Visit course, Vec3 stand) {}
    private GroundVisit groundVisit(List<YukkuriEntity> children) {
        if (children.isEmpty() || animal.distanceToSqr(children.getFirst()) <= 4
                || Task.VISIT_YOUNG.needsLanding(animal.life().energy()) || !hasAirExit()) return null;
        for (var child : children) {
            var feet = visitStand(child);
            if (feet != null) return new GroundVisit(visitAt(child, feet), feet);
        }
        return null;
    }

    private Vec3 visitStand(YukkuriEntity child) {
        long now = level().getGameTime();
        return groundStand(child, feet -> !failedVisits.active(visitAt(child, feet), now));
    }
    private Vec3 groundStand(YukkuriEntity child, Predicate<Vec3> allowed) {
        Vec3 closest = null;
        for (var side : Direction.Plane.HORIZONTAL) {
            var at = child.position().relative(side, (animal.getBbWidth()+child.getBbWidth())/2 + .18);
            var feet = SurfaceApproach.restingFloor(animal, at);
            if (feet != null && Math.abs(feet.y-child.getY()) < .5 && allowed.test(feet) && freeStand(feet)
                    && (closest == null || animal.distanceToSqr(feet)<animal.distanceToSqr(closest))) closest=feet;
        }
        return closest;
    }

    private Visit visitAt(YukkuriEntity child, Vec3 feet) {
        return new Visit(child.getUUID(), child.blockPosition().asLong(), BlockPos.containing(feet).asLong());
    }
    private void rememberBlockedVisit(String reason) {
        long now = level().getGameTime();
        long retryAt = PredatorFlight.visitRetryAt(now, ticks);
        failedVisits.prune(now);
        failedVisits.put(visiting, retryAt);
        GameplayLog.event(animal, "predator", "ground visit refused child={} reason={} ground={} stand={} retry_at={}",
                visiting.child(), reason, BlockPos.of(visiting.groundCell()), BlockPos.of(visiting.standCell()), retryAt);
    }
    private void visitGroundChild(YukkuriEntity child) {
        var feet = task == Task.VISIT_YOUNG ? visitStand(child) : groundStand(child, at -> true);
        if (feet == null) {
            if (task == Task.VISIT_YOUNG) rememberBlockedVisit("no_supported_stand");
            stop(); return;
        }
        if (task == Task.VISIT_YOUNG) visiting = visitAt(child, feet);
        if (landingState(true,SurfaceApproach.at(animal,feet)) != ShelfApproach.Landing.TOUCHDOWN) {
            flyToFeet(feet,true); return;
        }
        animal.setPredatorFlying(false);
        Motor.face(animal,SurfaceApproach.yaw(feet,child.position()));
        if (carryingPrey()) {
            if (!releasePrey(null)) return;
            GameplayLog.event(animal,"predator","prey delivered beside grounded young child={} feet={}",child.getUUID(),feet);
        } else {
            animal.showReaction("teary","caregiver",40);
            GameplayLog.event(animal,"predator","landed beside grounded young child={} feet={}",child.getUUID(),feet);
        }
        stop();
    }

    private boolean carryingPrey() { return animal.family().carrying().is(ModItems.REMAINS.get()); }

    private boolean fetchable(RemainsEntity body) {
        var home = broodPad();
        var stage = body.data().getString("Stage");
        var grounded = groundChild();
        return (grounded == null || body.distanceToSqr(grounded) > 1.5) && !body.blockPosition().equals(home) && edibleCorpse(body) && body.stage() == RemainsRot.FRESH
                && !body.data().getBoolean("FoodFoul") && ("baby".equals(stage) || "juvenile".equals(stage))
                && ParentCarry.fitsSingleMouth(animal, body.getBbWidth(), body.getBbHeight() / .65);
    }

    public RemainsEntity careSource(YukkuriEntity child) {
        if (!animal.family().caresFor(child) || !child.family().needsMash()) return null;
        return level().getEntitiesOfClass(RemainsEntity.class, child.getBoundingBox().inflate(1), body ->
                (body.blockPosition().equals(child.blockPosition()) || groundChild()==child && body.distanceToSqr(child)<1.5)
                        && edibleCorpse(body) && body.stage() == RemainsRot.FRESH
                && !body.data().getBoolean("FoodFoul") && animal.distanceToSqr(body) < 2.25).stream()
                .min(Comparator.comparingDouble(animal::distanceToSqr)).orElse(null);
    }

    private boolean familyMealDue() {
        var child = provisionChild();
        return child != null && child.family().needsMash() && child.life().canReceiveCareFood()
                && (!animal.family().carrying().isEmpty() || careSource(child) != null);
    }

    public boolean touchesPrey(RemainsEntity body) {
        var mouth = MouthPlacement.position(animal);
        var box = body.getBoundingBox();
        var closest = new Vec3(Mth.clamp(mouth.x,box.minX,box.maxX), Mth.clamp(mouth.y,box.minY,box.maxY),
                Mth.clamp(mouth.z,box.minZ,box.maxZ));
        return ItemContact.reaches(animal, closest, .025);
    }

    private boolean liftPrey(RemainsEntity body) {
        if (!fetchable(body) || !touchesPrey(body) || !animal.component(YukkuriComponents.MOUTH).freeForExternalWork()) return false;
        if (!animal.family().holdCareFood(body.remains())) return false;
        body.discard();
        task = Task.DELIVER; ticks = 0; corpse = null; eatRemains = null;
        GameplayLog.event(animal,"predator","prey lifted remains={} cargo={}",body.getUUID(),animal.family().carrying());
        beginFlight();
        return true;
    }

    /** The release ray resolves the real floor. A refused placement leaves the mouth record intact. */
    public boolean releasePrey(Vec3 offered) {
        var mouth = MouthPlacement.position(animal);
        var at = ItemContact.releaseSurface(animal, offered == null ? mouth : offered.add(0,.1,0));
        if (at == null) return false;
        var data = FillingResource.data(animal.family().carrying());
        float width = RemainsEntity.dimension(data,"Width",.75f);
        var box = EntityDimensions.scalable(width,RemainsEntity.dimension(data,"Height",.35f)).makeBoundingBox(at);
        if (offered != null && !ItemContact.reaches(animal, at.add(0,.015,0), width / 2)
                || !level().getEntities(animal,box.deflate(.001), e -> e instanceof LivingEntity || e instanceof RemainsEntity).isEmpty()) return false;
        return animal.component(YukkuriComponents.MOUTH).handOne(Purpose.CARE,
                stack -> RemainsEntity.place(level(),at,animal.getYRot(),stack,true));
    }

    private void deliver() {
        var child = provisionChild();
        if (!carryingPrey() || child == null || !canSustainFlight()) {
            if (carryingPrey()) releasePrey(null);
            stop(); return;
        }
        if (child == groundChild()) { visitGroundChild(child); return; }
        var pad = broodPad();
        var entrance = animal.life().homeEntrance(pad);
        if (entrance == null) { stop(); return; }
        var centre = Vec3.atBottomCenterOf(pad);
        var outward = entrance.subtract(centre).multiply(1,0,1).normalize();
        var side = new Vec3(-outward.z,0,outward.x);
        var drop = padFeet(pad).add(outward.scale(.49));
        var stand = centre.add(outward.scale(.5 + animal.getBbWidth()/2 + .005));
        var data = FillingResource.data(animal.family().carrying());
        var dimensions = EntityDimensions.scalable(RemainsEntity.dimension(data,"Width",.75f),RemainsEntity.dimension(data,"Height",.35f));
        for (double offset : new double[]{0,-.27,.27}) {
            var feet = stand.add(side.scale(offset));
            var at = drop.add(side.scale(offset));
            if (!freeStand(feet) || !level().getEntities(animal,dimensions.makeBoundingBox(at),
                    other -> other instanceof LivingEntity || other instanceof RemainsEntity).isEmpty()) continue;
            if (landingState(true,SurfaceApproach.at(animal,feet)) != ShelfApproach.Landing.TOUCHDOWN) {
                animal.setPredatorFlying(true); flyToFeet(feet,true); return;
            }
            animal.setPredatorFlying(false);
            Motor.face(animal,SurfaceApproach.yaw(feet,at));
            if (releasePrey(at)) {
                GameplayLog.event(animal,"predator","prey carried to nest child={} pad={} contact={}",child.getUUID(),pad,at);
                stop();
            }
            return;
        }
        stop();
    }

    private boolean wildAdult() {
        return animal.family().adult() && animal.status().identity().wild();
    }

    private static boolean inTerritory(BlockPos pad, Vec3 position) {
        var offset = position.subtract(Vec3.atBottomCenterOf(pad));
        return ThreatPass.inZone(offset.x, offset.z);
    }

    private boolean territoryIntruder(BlockPos pad, LivingEntity target) {
        if (target == animal || !target.isAlive() || target.isSpectator() || target.getY() < pad.getY() - 3) return false;
        if (target instanceof Player player) return player.distanceToSqr(Vec3.atBottomCenterOf(pad)) <= 25;
        var other = (YukkuriEntity) target;
        return EcologyRoutines.predator(other) && inTerritory(pad, other.position()) && !other.isPassenger()
                && !Bonds.careOrMate(animal, other) && !Bonds.biological(animal, other);
    }

    private boolean chooseTerritoryWarning() {
        var pad = broodPad();
        if (pad == null || !animal.onGround() || animal.getY() < pad.getY() - 3 || !inTerritory(pad, animal.position())) return false;
        if (warnedIntruder != null && !territoryIntruder(pad, warnedIntruder)) warnedIntruder = null;
        var area = new AABB(pad).inflate(ThreatPass.ZONE_RADIUS, 0, ThreatPass.ZONE_RADIUS);
        var canopy = new AABB(area.minX, pad.getY() - 3, area.minZ, area.maxX, level().getMaxBuildHeight(), area.maxZ);
        territoryTarget = level().getEntitiesOfClass(YukkuriEntity.class, canopy, other -> other != warnedIntruder
                && other.onGround() && territoryIntruder(pad, other) && animal.hasLineOfSight(other)).stream()
                .min(Comparator.comparingDouble(animal::distanceToSqr)).orElse(null);
        if (territoryTarget == null) territoryTarget = level().getNearestPlayer(pad.getX() + .5, pad.getY(), pad.getZ() + .5, 5,
                target -> target instanceof Player player && player != warnedIntruder && territoryIntruder(pad, player)
                        && animal.hasLineOfSight(player));
        if (territoryTarget == null) return false;
        task = Task.WARN;
        return true;
    }

    private void screech(LivingEntity target) {
        animal.showReaction("angry", Behaviors.PREDATOR_SCREECH, YukkuriAction.PUFF.durationTicks());
        animal.getLookControl().setLookAt(target, 30, 30);
        GameplayLog.event(animal, "predator", "territory screech target={} boldness={}",
                target.getUUID(), animal.life().personality().boldness());
    }

    private void warnIntruder() {
        var pad = broodPad();
        var target = territoryTarget;
        if (pad == null || !animal.onGround() || !territoryIntruder(pad, target)) { stop(); return; }
        SurfaceApproach.halt(animal);
        animal.getLookControl().setLookAt(target, 30, 30);
        if (ticks < YukkuriAction.PUFF.durationTicks()) return;
        if (Task.THREAT_APPROACH.needsLanding(animal.life().energy()) || !hasAirExit()
                || target instanceof Player && animal.life().personality().boldness() < 50 && provisionChild() == null) {
            stop(); return;
        }
        phase(Task.THREAT_APPROACH);
        destination = target.position();
        beginFlight();
    }

    private void threatPass() {
        var pad = broodPad();
        var target = territoryTarget;
        if (pad == null || !territoryIntruder(pad, target)) { land(); return; }
        var top = target.getBoundingBox().maxY;
        var pullUp = new Vec3(target.getX(), ThreatPass.pullUpY(top), target.getZ());
        animal.getLookControl().setLookAt(target, 30, 30);
        if (task == Task.THREAT_APPROACH) {
            if (ThreatPass.canDescend(animal.getY(), top) && animal.distanceToSqr(pullUp) < 36
                    && flightSweepClear(animal.position(), pullUp)) phase(Task.THREAT_DIVE);
            else { fly(pullUp.add(0, SWOOP_HEIGHT - 1.5, 0), 1.2); return; }
        }
        if (!flightSweepClear(animal.position(), pullUp)) { land(); return; }
        if (animal.distanceToSqr(pullUp) < .09) {
            GameplayLog.event(animal, "predator", "territory pull-up target={} feet={} target_top={}",
                    target.getUUID(), animal.position(), top);
            if (target instanceof YukkuriEntity intruder) intruder.predator().yieldTerritory(animal, pad);
            land();
        } else if (ThreatPass.canDescend(animal.getY(), top)) directFlight(pullUp, 1);
        else land();
    }

    private void yieldTerritory(YukkuriEntity resident, BlockPos pad) {
        if (!ready() || !canSustainFlight() || animal.childhood().nestling() || !hasAirExit()) return;
        boolean alreadyActive = active();
        interruptFounding();
        drivenFrom = pad;
        avoidTerritoryUntil = level().getGameTime() + 24000;
        territoryTarget = resident;
        task = Task.YIELD_TERRITORY;
        if (alreadyActive) start();
    }

    private void leaveTerritory() {
        land();
        beginFlight();
    }

    /** Loaded claimed nests supply their zones. The deadline is the intruder's only lasting avoidance state. */
    List<BlockPos> foreignTerritories(BlockPos centre, int radius) {
        if (level().getGameTime() >= avoidTerritoryUntil) return List.of();
        int reach = radius + ThreatPass.ZONE_RADIUS;
        var ids = FamilyData.get(level());
        var owner = animal.getUUID();
        var mate = animal.family().partner();
        var zones = new ArrayList<BlockPos>();
        for (int z = (centre.getZ() - reach) >> 4; z <= (centre.getZ() + reach) >> 4; z++) {
            for (int x = (centre.getX() - reach) >> 4; x <= (centre.getX() + reach) >> 4; x++) {
                var chunk = level().getChunkSource().getChunkNow(x, z);
                if (chunk == null) continue;
                for (var cell : chunk.getBlockEntitiesPos()) {
                    if (!chunk.getBlockState(cell).is(ModBlocks.PREDATOR_NEST.get())
                            || !(chunk.getBlockEntity(cell) instanceof CareBlockEntity nest) || nest.owns(owner)) continue;
                    if (nest.residentIds().stream().anyMatch(id -> !id.equals(mate)
                            && !ids.related(owner, id) && !ids.socialKin(owner, id))) zones.add(cell);
                }
            }
        }
        return zones;
    }

    static boolean outsideTerritories(List<BlockPos> zones, Vec3 feet) {
        return zones.stream().noneMatch(pad -> inTerritory(pad, feet));
    }

    private boolean needsRoost(BlockPos home) {
        return (level().isNight() || level().isRaining()) && (home == null || animal.life().naturalHome()
                || !level().getEntitiesOfClass(YukkuriEntity.class, new AABB(home), animal.family()::caresFor).isEmpty());
    }

    private void perch() {
        var pos = BlockPos.containing(destination);
        var admission = landingState(CanopySite.tree(level().getBlockState(pos.below())) && destination.equals(landingFeet(pos)),
                SurfaceApproach.at(animal, destination));
        if (admission == ShelfApproach.Landing.REPICK) { stop(); return; }
        if (watchTicks == 0) {
            if (admission != ShelfApproach.Landing.TOUCHDOWN) {
                if (ticks > OTHER_TASK_LIMIT) stop(); else flyToFeet(destination, true);
                return;
            }
            animal.setPredatorFlying(false);
            animal.getNavigation().stop();
            previousPerch = lastPerch;
            lastPerch = pos.asLong();
            watchTicks = 600 + (int)(1200 * animal.life().personality().temperament().patient());
            GameplayLog.event(animal, "predator", "perch begin stand={} shade={} watch_ticks={}",
                    pos, animal.life().covered(pos), watchTicks);
        }
        if (admission != ShelfApproach.Landing.TOUCHDOWN) { stop(); return; }
        if (ticks % 20 == 0) {
            if (familyMealDue() || groundVisit(groundedChildren(broodPad())) != null) { stop(); return; }
            if (chooseTerritoryWarning()) { start(); return; }
            double pressure = nestBuild.framePressure();
            if (!FoundingFrame.chooseWork(pressure, animal.life().nutrition(), true) && chooseFeeding()) {
                watchTicks = 0;
                destination = null;
                start();
                return;
            }
            if (pressure > 0 || nestBuild.wantsSurvey() || animal.life().hasUrgentNeed() || animal.life().energy() < 35) {
                stop(); return;
            }
        }
        if (level().isNight() || level().isRaining()) {
            boolean sleep = needsRoost(animal.life().homeStand()) && animal.life().covered(pos) && animal.life().canFatigueRest();
            stop();
            if (sleep) animal.life().roost();
            return;
        }
        if (ticks % 40 == 1) {
            double angle = animal.getRandom().nextDouble() * 2 * Math.PI;
            animal.getLookControl().setLookAt(animal.getX() + 12 * Math.cos(angle), animal.getY() - 4,
                    animal.getZ() + 12 * Math.sin(angle));
        }
        if (--watchTicks == 0) stop();
    }
    /** A prey that will fight back is harder than one that only runs: the score of one with a stick is worth ~eight blocks. */
    private boolean fightsBack(YukkuriEntity target) {
        return target.myon().hasKanroken() && CombatCourage.willing(target);
    }
    private boolean edibleDrop(ItemEntity item) {
        return item.isAlive() && !item.hasPickUpDelay() && animal.hasLineOfSight(item)
                && (FillingResource.isFilling(item.getItem()) && animal.life().acceptsFood(item.getItem())
                    || item.getItem().is(ModItems.REMAINS.get()) && edibleCorpseData(FillingResource.data(item.getItem())));
    }
    private boolean edibleCorpse(RemainsEntity remains) {
        return remains.isAlive() && !remains.isRemoved() && animal.hasLineOfSight(remains) && edibleCorpseData(remains.data());
    }
    private boolean edibleCorpseData(CompoundTag data) {
        if (CorpseFood.remaining(data) <= 0 || (data.getBoolean("FoodFoul") || RemainsRot.stage(data) > RemainsRot.FRESH)
                && animal.life().nutrition() >= 15) return false;
        if (obedient() && data.getBoolean("Kept")) return false;
        return !data.hasUUID("Identity") || !Bonds.corpseKin(animal,
                data.getUUID("Identity"), data.hasUUID("Partner") ? data.getUUID("Partner") : null);
    }
    private void start() {
        ticks = 0; pass = Pass.APPROACH; passTicks = 0; lastHit = -SWOOP_GAP; hits = 0; restTicks = 0;
        if (task != Task.LAND) pendingRest = 0;
        contactHold = false; withdrawGoal = null; flightGoal = null;
        animal.life().interrupt(); animal.social().stop(); animal.groupInteraction().stop(); animal.playLife().stop();
        if (task == Task.FLAP) { animal.getNavigation().stop(); return; }
        if (task == Task.WARN) {
            watchTicks = 0;
            animal.getNavigation().stop();
            warnedIntruder = territoryTarget;
            screech(territoryTarget);
            return;
        }
        if (task == Task.YIELD_TERRITORY) {
            var resident = (YukkuriEntity) territoryTarget;
            if (animal.onGround() && animal.life().personality().boldness() > resident.life().personality().boldness()) {
                SurfaceApproach.halt(animal);
                screech(resident);
            } else leaveTerritory();
            return;
        }
        if (task == Task.BRANCH_HOP) {
            if (!animal.childhood().hopTo(destination)) stop();
            return;
        }
        if (task == Task.FOUND) { cancelNestSearch(); nestBuild.start(); }
        GameplayLog.event(animal,"predator","activity started; task={} prey={} food={} corpse={} retaliation={} nutrition={} energy={} destination={}",
                task,prey==null?"none":prey.getUUID(),food==null?"none":food.getUUID(),corpse==null?"none":corpse.getUUID(),
                retaliation,animal.life().nutrition(),animal.life().energy(),destination);
        if (task == Task.SCAVENGE && animal.childhood().nestling()) return;
        if (task != Task.FOUND && (task != Task.SCAVENGE || CanopySite.tree(level().getBlockState(animal.blockPosition().below()))
                || (corpse != null ? corpse.getY() : food.getY()) > animal.getY() + 1))
            beginFlight();
    }

    private void beginFlight() {
        var feet = animal.position();
        var pos = BlockPos.containing(feet);
        if (!animal.predatorFlying() && animal.onGround() && (needsSideExit(pos) || task == Task.FIRST_FLIGHT)
                && (destination == null || !SurfaceApproach.at(animal, destination))) {
            takeoffExit = sideExit(pos, feet, prey != null ? prey.position() : destination);
            if (takeoffExit == null) {
                GameplayLog.limited(animal, "predator", "takeoff_enclosed", 200, "takeoff refused reason=no_open_side stand={}", pos);
                if (task == Task.VISIT_YOUNG) rememberBlockedVisit("no_open_side");
                stop(); return;
            }
        }
        animal.setPredatorFlying(true);
    }

    private boolean needsSideExit(BlockPos pos) {
        return animal.life().covered(pos) && (level().getBlockState(pos).is(ModBlocks.PREDATOR_NEST.get())
                || CanopySite.tree(level().getBlockState(pos.below())));
    }

    boolean canDepart(Vec3 feet) {
        var pos = BlockPos.containing(feet);
        return !needsSideExit(pos) || sideExit(pos, feet, null) != null;
    }

    private Vec3 sideExit(BlockPos pos, Vec3 feet, Vec3 toward) {
        var entrance = level().getBlockState(pos).is(ModBlocks.PREDATOR_NEST.get()) ? animal.life().homeEntrance(pos) : null;
        var aim = entrance == null ? toward : entrance;
        var directions = new ArrayList<Vec3>();
        for (var side : Direction.Plane.HORIZONTAL) directions.add(new Vec3(side.getStepX(), 0, side.getStepZ()));
        if (aim != null) directions.sort(Comparator.comparingDouble(dir -> feet.add(dir).distanceToSqr(aim)));
        for (var dir : directions) {
            // Measure the clear leg from the entrance (or the branch edge), beyond the overhanging leaf.
            double edge = .5 + Vec3.atBottomCenterOf(pos).subtract(feet).dot(dir);
            if (entrance != null) edge = Math.max(edge, entrance.subtract(feet).dot(dir));
            var exit = feet.add(dir.scale(edge + 1.5));
            if (flightSweepClear(feet, exit) && flightSweepClear(exit, exit.add(0, 1.2, 0))) return exit;
        }
        return null;
    }
    private void fly(Vec3 goal, double speed) { fly(goal, speed, 6.25); }

    private Vec3 shelfApproach(Vec3 feet) {
        if (animal.distanceToSqr(feet) > 64) return null;
        var leg = ShelfApproach.next(Floor.vec(animal.position()), Floor.vec(feet),
                (a, b) -> flightSweepClear(Floor.point(a), Floor.point(b)));
        return leg == null ? null : Floor.point(leg);
    }

    /** Final descent uses the same route and supported feet the founder checked. */
    boolean landForBuild(Vec3 feet) {
        var admission = landingState(freeStand(feet), SurfaceApproach.at(animal, feet));
        if (admission == ShelfApproach.Landing.REPICK) { nestBuild.failed("stand_obstructed", Reason.NO_ROOM); return false; }
        if (admission == ShelfApproach.Landing.TOUCHDOWN) {
            animal.setPredatorFlying(false);
            return true;
        }
        animal.setPredatorFlying(true);
        flyToFeet(feet, true);
        return false;
    }

    /** Fixed waypoints share their admission route. Only landing presses down on the final clear leg. */
    void flyToFeet(Vec3 feet, boolean landing) {
        var leg = shelfApproach(feet);
        if (leg == null) {
            fly(feet, .85, 0);
        } else {
            boolean descend = landing && leg.equals(feet) && animal.position().subtract(feet).horizontalDistanceSqr() < .16
                    && Math.abs(animal.getY() - feet.y) < .3;
            directFlight(descend ? feet.add(0, -.08, 0) : leg, .85);
        }
    }
    /** A clear line within directRange squared is flown straight instead of by path nodes. */
    private void fly(Vec3 goal, double speed, double directRange) {
        if (!level().hasChunkAt(BlockPos.containing(goal))) { flightBlocked(); return; }
        if (recoveringFlight() && animal.isInWaterOrBubble()) {
            // First lift the actual body clear. A diagonal path to a bank can scrape the water all the way there.
            Vec3 rise = animal.position().add(0, Math.max(.6, flightFloor(animal.position()) - animal.getY() + .35), 0);
            if (blockSweepClear(animal.position(), rise)) directFlight(rise, .85);
            else GameplayLog.limited(animal, "predator", "water_exit_blocked", 100,
                    "water exit blocked reason=ceiling feet={} wanted={} energy={}", animal.position(), rise, animal.life().energy());
            return;
        }
        // Path nodes end at block centers. Finish the last approach at the moving target's
        // exact position, otherwise the animal circles just outside bite or landing range.
        if (animal.distanceToSqr(goal) < directRange && flightSweepClear(animal.position(), goal)) {
            directFlight(goal, speed);
            return;
        }
        if (ticks % 10 == 1 || animal.getNavigation().isDone()) {
            Vec3 pathGoal = flightPathGoal(goal);
            if (!followFlightPath(pathGoal, speed)) {
                GameplayLog.limited(animal,"predator","path_refused",200,"flight path refused; task={} prey={} destination={} elapsed={}",
                        task,prey==null?"none":prey.getUUID(),goal,ticks);
                if (task == Task.FOUND || ticks > 60) flightBlocked();
            }
        }
    }
    private boolean followFlightPath(Vec3 goal, double speed) {
        var navigation = animal.getNavigation();
        if (task != Task.FOUND) return navigation.moveTo(goal.x, goal.y, goal.z, speed);
        // A founder needs the actual survey point or supported stand. A partial path cannot complete that leg.
        var path = navigation.createPath(BlockPos.containing(goal), 0);
        return path != null && path.canReach() && navigation.moveTo(path, speed);
    }
    private void flightBlocked() {
        if (task == Task.FOUND) nestBuild.failed("no_flight_route", Reason.NO_PATH);
        else if (task == Task.FIRST_FLIGHT) stop();
        else {
            if (task == Task.VISIT_YOUNG) rememberBlockedVisit("no_flight_route");
            land();
        }
    }
    private void directFlight(Vec3 goal, double speed) {
        animal.getNavigation().stop();
        flightGoal = goal; flightSpeed = speed; flightGoalTick = animal.tickCount;
        animal.getMoveControl().setWantedPosition(goal.x, goal.y, goal.z, speed);
    }
    /** Null leaves path-node flight to vanilla. A direct command steers inertia instead of adding vanilla's forward thrust twice. */
    public Vec3 flightStep(Vec3 inertia) {
        if (flightGoal == null || flightGoalTick != animal.tickCount || !canSustainFlight()) return null;
        Vec3 delta = flightGoal.subtract(animal.position());
        double distance = delta.length();
        double maximum = BodyAbility.flightPace(animal, flightSpeed);
        double acceleration = flightAcceleration();
        // Include the next tick's displacement in the braking envelope. Weak flight starts braking earlier;
        // the ordinary .91 drag after travel only shortens this conservative no-drag stopping distance.
        double speed = Math.min(maximum, Math.min(distance * .35, brakingSpeed(distance, acceleration)));
        Vec3 desired = distance < 1e-6 ? Vec3.ZERO : delta.scale(speed / distance);
        // The target may move sideways while the predator is descending. Horizontal range must not buy
        // extra downward speed once the sole is close to its contact height. Spend braking effort here first.
        double verticalLimit = brakingSpeed(Math.abs(delta.y), acceleration);
        desired = new Vec3(desired.x, Math.clamp(desired.y, -verticalLimit, verticalLimit), desired.z);
        double verticalChange = Math.clamp(desired.y - inertia.y, -acceleration, acceleration);
        double horizontalBudget = Math.sqrt(Math.max(0, acceleration * acceleration - verticalChange * verticalChange));
        Vec3 horizontalChange = new Vec3(desired.x - inertia.x, 0, desired.z - inertia.z);
        if (horizontalChange.lengthSqr() > horizontalBudget * horizontalBudget)
            horizontalChange = horizontalChange.normalize().scale(horizontalBudget);
        if (speed >= Gait.ordinaryStroke(maximum))
            animal.motor().propulsion(horizontalChange.x, verticalChange, horizontalChange.z, maximum * (1 - Ballistic.AIR_DRAG));
        return inertia.add(horizontalChange).add(0, verticalChange, 0);
    }
    /** Solve v + v²/(2a) <= distance: one full next step plus the remaining finite braking distance. */
    private static double brakingSpeed(double distance, double acceleration) {
        return Math.sqrt(acceleration * acceleration + 2 * acceleration * Math.max(0, distance)) - acceleration;
    }
    private double flightAcceleration() { return .12 * Math.sqrt(Math.max(.1, animal.life().energy() / 100.0)); }
    private AABB bodyAt(Vec3 feet) { return animal.getBoundingBox().move(feet.subtract(animal.position())); }
    /** Required sole height over real water columns under the whole body footprint. */
    private double flightFloor(Vec3 feet) {
        AABB box = bodyAt(feet);
        double floor = Double.NEGATIVE_INFINITY;
        for (int x = Mth.floor(box.minX); x < Mth.ceil(box.maxX); x++)
            for (int z = Mth.floor(box.minZ); z < Mth.ceil(box.maxZ); z++)
                for (int y = Mth.floor(feet.y) - 1; y <= Mth.floor(box.maxY) + 1; y++) {
                    BlockPos p = new BlockPos(x, y, z);
                    if (!level().hasChunkAt(p)) continue;
                    var fluid = level().getFluidState(p);
                    if (fluid.is(FluidTags.WATER)) floor = Math.max(floor, y + fluid.getHeight(level(), p) + .04);
                }
        // A placed catch hat is a real surface too: a low approach clears its cap instead of slicing through it.
        for (var hat : level().getEntitiesOfClass(PlacedHatEntity.class, box.inflate(.1, 2, .1), h -> h.isAlive())) {
            Vec3 local = hat.toLocal(feet);
            double top = hat.profile().footprint(local.x, local.z, animal.getBbWidth() * .5 / hat.unit());
            if (Double.isFinite(top)) floor = Math.max(floor, hat.toWorld(new Vec3(local.x, top, local.z)).y + .04);
        }
        return floor;
    }
    private boolean blockSweepClear(Vec3 from, Vec3 to) {
        int count = Math.max(1, (int)Math.ceil(from.distanceTo(to) / .15));
        Vec3 previous = from;
        for (int i = 1; i <= count; i++) {
            Vec3 feet = from.lerp(to, (double)i / count);
            var pos = BlockPos.containing(feet);
            if (!level().hasChunkAt(pos) || !level().getWorldBorder().isWithinBounds(pos)) return false;
            var step = feet.subtract(previous);
            // Use vanilla's movement clipping, including its vertical-first step at a ceiling or ledge.
            if (Entity.collideBoundingBox(animal, step, bodyAt(previous), level(), List.of())
                    .distanceToSqr(step) > Shapes.EPSILON * Shapes.EPSILON) return false;
            previous = feet;
        }
        return true;
    }
    private boolean flightSweepClear(Vec3 from, Vec3 to) {
        if (from.distanceToSqr(to) > 64) return false; // Longer trips belong to bounded path segments, never a remote body sweep.
        if (!blockSweepClear(from, to)) return false;
        int count = Math.max(1, (int)Math.ceil(from.distanceTo(to) / .15));
        for (int i = 1; i <= count; i++) {
            Vec3 feet = from.lerp(to, (double)i / count);
            if (feet.y < flightFloor(feet)) return false;
        }
        return true;
    }
    /** A chase can cross the acquisition radius without ever asking vanilla's local pathfinder for an overlong route. */
    private Vec3 flightPathGoal(Vec3 goal) {
        Vec3 from = animal.position();
        Vec3 delta = goal.subtract(from);
        double distance = delta.length();
        return distance <= FLIGHT_PATH_SEGMENT ? goal : from.add(delta.scale(FLIGHT_PATH_SEGMENT / distance));
    }
    private void land() { land(null); }
    /** @param avoid the beaten fighter lands well away from the one that beat it (see {@link #withdraw}) */
    private void land(Vec3 avoid) {
        if(task!=Task.LAND)GameplayLog.event(animal,"predator","landing started; previous_task={} prey={} elapsed={} energy={}",
                task,prey==null?"none":prey.getUUID(),ticks,animal.life().energy());
        if (task != Task.LAND || avoid != null && !avoid.equals(landingAvoid)) {
            visiting = null;
            landingStand = null;
            nextLandingSearch = 0; landingSearchRadius = avoid == null ? 8 : 24; landingAvoid = avoid;
            animal.getNavigation().stop();
            if (animal.predatorFlying() && !dryLanded()) directFlight(animal.position().add(0, .8, 0), .85);
        }
        prey = null; food = null; corpse = null; territoryTarget = null; watchTicks = 0; mode(false, "landing"); task = Task.LAND;
        if (level().getGameTime() < nextLandingSearch) return;
        nextLandingSearch = level().getGameTime() + 40;
        int searchedRadius = landingSearchRadius;
        Landing landing = findDryLanding(animal.blockPosition(), searchedRadius, landingStand);
        landingSearchRadius = Math.min(32, landingSearchRadius * 2);
        landingStand = landing == null ? null : landing.stand();
        destination = landing == null ? null : landing.feet();
        if (destination == null) GameplayLog.limited(animal, "predator", "landing.search", 200,
                "landing searching reason=no_dry_stand radius={} energy={} flying={}", searchedRadius, animal.life().energy(), animal.predatorFlying());
        else GameplayLog.limited(animal, "predator", "landing.route", 100,
                "landing route selected stand={} feet={} energy={} flying={}", landingStand, destination, animal.life().energy(), animal.predatorFlying());
    }
    private boolean dryLanded() {
        return animal.onGround() && !animal.isInWaterOrBubble() && freeStand(animal.position());
    }

    private ShelfApproach.Landing landingState(boolean standClear, boolean arrived) {
        boolean touching = arrived && animal.onGround();
        return ShelfApproach.landing(standClear, touching, standClear && touching && dryLanded());
    }
    private boolean canSustainFlight() { return animal.isAlive() && !animal.body().dying() && animal.life().energy() > 0
            && !animal.isPassenger() && animal.childhood().readyToFly(); }
    public boolean canCatchFall() {
        return EcologyRoutines.predator(animal) && !animal.childhood().nestling() && !animal.isNoAi() && canSustainFlight();
    }
    public void catchFall() {
        if (animal.predatorFlying() || !canCatchFall()) return;
        interruptFounding();
        animal.life().wakeForDanger("fall");
        GameplayLog.event(animal, "predator", "fall recovery distance={}", animal.fallDistance);
        animal.setPredatorFlying(true);
        land();
    }
    /** Below its retreat ratio the beaten fighter leaves by air, not by dropping next to the one that beat it. */
    public boolean canWithdrawByAir() {
        return EcologyRoutines.predator(animal) && wildAdult() && !animal.childhood().nestling() && readyForLanding();
    }
    public boolean hasAirExit() {
        return !animal.onGround() || canDepart(animal.position());
    }
    public void withdraw(LivingEntity foe) {
        interruptFounding();
        pendingRest = 0;
        GameplayLog.event(animal, "predator", "withdrawing by air; foe={} health={}/{} distance={}",
                foe.getUUID(), animal.getHealth(), animal.getMaxHealth(), animal.distanceTo(foe));
        land(foe.position());
        if (!animal.predatorFlying()) {
            ticks = 0; // A struck watcher starts its departure now, even after a long stay on the branch.
            beginFlight();
        }
    }
    /** A landing during a fight keeps its search away from the foe: a beaten or spent body does not come down at its feet. */
    private void landAvoidingFoe() {
        if (retaliation && prey != null && animal.distanceToSqr(prey) <= 64) withdraw(prey);
        else land();
    }
    private record Landing(BlockPos stand, Vec3 feet) {}
    private static final List<Heightmap.Types> LANDING_SURFACES = List.of(
            Heightmap.Types.MOTION_BLOCKING, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES);
    /** The candidate and the final flight use the same supported feet, including a nest's pad and assigned rest offset. */
    Vec3 landingFeet(BlockPos stand) {
        if (!level().hasChunkAt(stand) || !animal.life().dryStand(stand)) return null;
        var feet = level().getBlockEntity(stand) instanceof CareBlockEntity care && care.kind() == CareBlock.Kind.NEST
                ? FamilyNest.restPosition(animal, care) : SurfaceApproach.restingFloor(animal, Vec3.atBottomCenterOf(stand));
        return feet != null && freeStand(feet) ? feet : null;
    }
    public boolean freeStand(Vec3 feet) {
        if (!CanopySite.clearBody(level(), animal, feet) || !SurfaceApproach.supported(animal, feet)) return false;
        var cell = BlockPos.containing(feet);
        var box = bodyAt(feet);
        var min = Floor.vec(box.getMinPosition());
        var max = Floor.vec(box.getMaxPosition());
        return level().getEntities(animal, box.minmax(new AABB(cell)), other -> {
            if (other.isSpectator() || !(other instanceof LivingEntity || other instanceof RemainsEntity)) return false;
            var bounds = other.getBoundingBox();
            return ShelfApproach.occupied(cell.equals(other.blockPosition()), min, max,
                    Floor.vec(bounds.getMinPosition()), Floor.vec(bounds.getMaxPosition()));
        }).isEmpty();
    }
    private boolean currentLanding() {
        if (landingStand == null || destination == null) return false;
        Vec3 feet = landingFeet(landingStand);
        return feet != null && feet.distanceToSqr(destination) < .0004;
    }
    /** Loaded dry surfaces only, closest first; bounded path probes retain real collision and reachability. */
    private Landing findDryLanding(BlockPos center, int radius, BlockPos occupiedStand) {
        return findDryLanding(center, radius, occupiedStand, feet -> true);
    }

    public boolean reachableBroodStand(Vec3 feet) {
        return freeStand(feet) && (SurfaceApproach.at(animal,feet) || hasAirExit() && canFlyToFeet(feet));
    }

    public boolean birthGroundAt(double x, double y, double z) {
        var cell = BlockPos.containing(x,y,z);
        if (!level().hasChunkAt(cell) || !level().getFluidState(cell).isEmpty()) return false;
        var floor = level().getBlockState(cell.below());
        return Math.abs(y-cell.getY()) < SurfaceApproach.ARRIVAL && !CanopySite.tree(floor)
                && level().getBlockState(cell).getCollisionShape(level(),cell).isEmpty() && !floor.is(ModBlocks.PREDATOR_NEST.get())
                && floor.isFaceSturdy(level(),cell.below(),Direction.UP);
    }

    public Vec3 broodGroundStand(Predicate<Vec3> fitsBrood) {
        if (!hasAirExit()) return null;
        var here = animal.blockPosition();
        var ground = here.atY(CanopySite.groundY(level(),here));
        var landing = findDryLanding(ground, 8, null, fitsBrood);
        return landing == null ? null : landing.feet();
    }

    private Landing findDryLanding(BlockPos center, int radius, BlockPos occupiedStand, Predicate<Vec3> fits) {
        var zones = foreignTerritories(center, radius);
        var candidates = new HashSet<BlockPos>();
        if (lastDryStand != null && level().hasChunkAt(lastDryStand) && animal.life().dryStand(lastDryStand)) candidates.add(lastDryStand);
        var home = animal.life().homeStand();
        if (home != null && level().hasChunkAt(home) && animal.life().dryStand(home)) candidates.add(home);
        for (int down = 0; down <= 8; down++)
            for (var p : BlockPos.betweenClosed(center.offset(-2,-down,-2), center.offset(2,-down,2)))
                if (level().hasChunkAt(p) && animal.life().dryStand(p)) candidates.add(p.immutable());
        for (int ring = 1; ring <= radius; ring++) {
            int stride = Math.max(1, ring / 8);
            for (int side = -ring; side <= ring; side += stride) {
                for (var p : new BlockPos[]{center.offset(side,0,-ring), center.offset(side,0,ring),
                        center.offset(-ring,0,side), center.offset(ring,0,side)}) {
                    if (!level().hasChunkAt(p) || !level().getWorldBorder().isWithinBounds(p)) continue;
                    for (var map : LANDING_SURFACES) {
                        var surface = level().getHeightmapPos(map, p);
                        if (Math.abs(surface.getY() - animal.getY()) <= 16 && animal.life().dryStand(surface)) candidates.add(surface);
                    }
                }
            }
        }
        var ordered = new ArrayList<>(candidates);
        ordered.remove(occupiedStand);
        // A beaten fighter does not come down at the victor's feet: those stands are not candidates for this landing.
        if (landingAvoid != null) ordered.removeIf(p -> Vec3.atCenterOf(p).distanceToSqr(landingAvoid) <= WITHDRAW_CLEARANCE_SQR);
        ordered.sort(Comparator.comparingDouble(p -> p.distSqr(center) + animal.distanceToSqr(Vec3.atBottomCenterOf(p)) * .1));
        int routeChecks = 0;
        for (BlockPos stand : ordered) {
            Vec3 feet = landingFeet(stand);
            if (feet == null || !outsideTerritories(zones, feet) || !fits.test(feet)) continue;
            if (canDepart(feet) && canFlyToFeet(feet)) return new Landing(stand, feet);
            if (++routeChecks == 8) break;
        }
        return null;
    }
    private void searchWhileFlying() {
        land();
        if (destination != null || !animal.predatorFlying()) return;
        // Continue searching a loaded, unobstructed sector while spending ordinary flight energy. No indefinite free hover.
        double yaw = Math.toRadians(animal.getYRot() + (ticks / 80 % 4) * 90);
        Vec3 next = lastDryStand != null ? Vec3.atBottomCenterOf(lastDryStand).add(0, 1, 0)
                : animal.position().add(-Math.sin(yaw) * 4, .25, Math.cos(yaw) * 4);
        next = flightPathGoal(next);
        if (level().hasChunkAt(BlockPos.containing(next))) fly(next, .65);
    }
    private void tickGoal() {
        ticks++;
        if (drivenFrom != null && !inTerritory(drivenFrom, animal.position())) {
            GameplayLog.event(animal, "predator", "intruder left pad={} feet={} avoid_until={}",
                    drivenFrom, animal.position(), avoidTerritoryUntil);
            drivenFrom = null;
        }
        boolean timedOut = task.timedOut(ticks);
        if (!runnable() || timedOut) {
            if (timedOut && task == Task.VISIT_YOUNG) rememberBlockedVisit("timeout");
            GameplayLog.event(animal,"predator","activity cancelled; reason={} elapsed={}",timedOut?"timeout":"not_ready",ticks);
            stop(); return;
        }
        if (task.needsLanding(animal.life().energy())) {
            takeoffExit = null;
            landAvoidingFoe();
            return;
        }
        if (takeoffExit != null) {
            if (animal.distanceToSqr(takeoffExit) > .015) {
                if (!flightSweepClear(animal.position(), takeoffExit) || ticks > 60) {
                    takeoffExit = null;
                    if (task == Task.VISIT_YOUNG) rememberBlockedVisit("takeoff_blocked");
                    if (task == Task.FIRST_FLIGHT) stop(); else land();
                } else directFlight(takeoffExit, 1);
                return;
            }
            takeoffExit = null;
        }
        if (task == Task.FOUND) {
            if (animal.life().energy() < 15) stop(); else nestBuild.tick(OTHER_TASK_LIMIT);
            return;
        }
        if (task == Task.PERCH) { perch(); return; }
        if (task == Task.WARN) { warnIntruder(); return; }
        if (task == Task.YIELD_TERRITORY) {
            if (!animal.onGround() || ticks >= YukkuriAction.PUFF.durationTicks()) leaveTerritory();
            return;
        }
        if (task == Task.THREAT_APPROACH || task == Task.THREAT_DIVE) { threatPass(); return; }
        if (task == Task.DELIVER) { deliver(); return; }
        if (task == Task.VISIT_YOUNG) {
            var child = groundedChildren(broodPad()).stream().filter(young -> young.getUUID().equals(visiting.child())).findFirst().orElse(null);
            if (child == null) stop(); else visitGroundChild(child);
            return;
        }
        if (task == Task.BROOD_FLIGHT) {
            destination = animal.family().canopyBirth().stand();
            if (!freeStand(destination)) { stop(); return; }
            if (landingState(true, SurfaceApproach.at(animal,destination)) == ShelfApproach.Landing.TOUCHDOWN) {
                animal.setPredatorFlying(false);
                GameplayLog.event(animal,"family","brood landed receiver={} feet={}",animal.family().canopyBirth().pad()==null?"ground":"pad",animal.position());
                stop();
            } else flyToFeet(destination,true);
            return;
        }
        if (task == Task.FLAP) {
            if (!animal.onGround() || !SurfaceApproach.supported(animal, destination)) { stop(); return; }
            var pad = animal.childhood().padUnderfoot();
            var delta = pad == null ? Vec3.ZERO : animal.position().subtract(Vec3.atBottomCenterOf(pad));
            if (pad != null && Math.max(Math.abs(delta.x),Math.abs(delta.z)) < .5 - SurfaceApproach.supportRadius(animal) - .12
                    && !SurfaceApproach.at(animal,destination)) {
                watchTicks = 0;
                Motor.step(animal, destination, .75);
                return;
            }
            SurfaceApproach.halt(animal);
            if (++watchTicks >= ClipMarkers.of(Behaviors.PREDATOR_FLAP).length()) { animal.childhood().practise(false); stop(); }
            return;
        }
        if (task == Task.BRANCH_HOP) { if (!animal.childhood().hopping()) stop(); return; }
        if (task == Task.FIRST_FLIGHT) {
            if (!canSustainFlight() || !freeStand(destination) || !animal.onGround() && firstFlightLift() <= Ballistic.GRAVITY) {
                stop(); return;
            }
            var admission = landingState(true, SurfaceApproach.at(animal, destination));
            if (admission == ShelfApproach.Landing.TOUCHDOWN) {
                animal.childhood().fledged();
                GameplayLog.event(animal, "childhood", "first flight outcome=landed feet={}", animal.position());
                animal.setPredatorFlying(false);
                stop();
            } else flyToFeet(destination, true);
            return;
        }
        if (task == Task.NONE) return;
        if (task == Task.EAT) { eat(); return; }
        if (task.pursuit()) {
            if (!(retaliation ? retaliationReady() : wantsHunt()) || prey == null || !huntable(prey, true)) {
                GameplayLog.event(animal,"predator","pursuit ended; prey={} prey_alive={} retaliation={} energy={} nutrition={} health={}",
                        prey==null?"none":prey.getUUID(),prey!=null&&prey.isAlive(),retaliation,animal.life().energy(),animal.life().nutrition(),animal.getHealth());
                // A fight that ended in a kill leaves the winner settled beside the body, not chasing the next target. A body
                // that could not go on leaves by air instead of dropping at the victor's feet.
                if (retaliation && prey != null && (!prey.isAlive() || prey.body().dying())) rest(KILL_PAUSE, "combat_kill");
                else landAvoidingFoe();
                return;
            }
            if (ticks % 20 == 1) alertPrey();
            if (task == Task.TAKEOFF) {
                YukkuriEntity target = prey;
                fly(animal.position().add(0, 1.2, 0), 1);
                if (task != Task.TAKEOFF || prey != target) return;
                if (ticks >= 15) { phase(Task.CHASE); alertOwner(); }
            } else swoop();
        } else if (task == Task.SCAVENGE) {
            if (corpse != null) {
                if (provisionChild() != null && fetchable(corpse)) {
                    if (liftPrey(corpse)) return;
                    approachCarcass(corpse); return;
                }
                if (!edibleCorpse(corpse) || !animal.life().canAcceptBodyFood()) { stop(); return; }
                var feeding = corpse;
                approachFood(feeding.position());
                if (task != Task.SCAVENGE || corpse != feeding) return;
                double reach = (animal.getBbWidth() + corpse.getBbWidth()) * .5 + .18;
                if (animal.distanceToSqr(corpse) <= reach * reach && animal.hasLineOfSight(corpse)) {
                    takeRemains(corpse, "scavenge");
                    stop();
                }
                return;
            }
            if (food == null || !edibleDrop(food) || !animal.life().canEat()) { stop(); return; }
            var feeding = food;
            approachFood(feeding.position());
            if (task != Task.SCAVENGE || food != feeding) return;
            if (animal.distanceToSqr(feeding) < 1 && animal.hasLineOfSight(feeding)) {
                var stack = feeding.getItem();
                if (stack.is(ModItems.REMAINS.get())) {
                    if (animal.life().canAcceptBodyFood()) {
                        float before = CorpseFood.remaining(FillingResource.data(stack));
                        BodyFood.Bite bite = CorpseFood.take(stack, 1);
                        if (!bite.isEmpty() && animal.life().acceptBodyFood(bite)) {
                            feeding.setItem(stack.copy());
                            GameplayLog.event(animal,"predator","remains feeding carried; remains={} portions={} remaining={} -> {} nutrition={}",
                                    feeding.getUUID(),bite.portions(),before,CorpseFood.remaining(FillingResource.data(stack)),animal.life().nutrition());
                        }
                    }
                } else if (animal.life().beginMeal(stack.copyWithCount(1), null, true)) {
                    stack.shrink(1); if (stack.isEmpty()) feeding.discard(); else feeding.setItem(stack.copy());
                }
                stop();
            }
        } else if (task == Task.LAND || task == Task.RETURN) {
            boolean arrived = destination != null && animal.position().distanceToSqr(destination) < .36
                    && animal.blockPosition().equals(BlockPos.containing(destination));
            var admission = landingState(currentLanding(), arrived);
            if (admission == ShelfApproach.Landing.REPICK) {
                if (destination != null) animal.getNavigation().stop();
                if (task == Task.RETURN) {
                    Landing alternative = findDryLanding(landingStand, 8, landingStand);
                    if (alternative != null) {
                        landingStand = alternative.stand(); destination = alternative.feet();
                        flyToFeet(destination, true); return;
                    }
                }
                destination = null;
                searchWhileFlying(); return;
            }
            if (admission == ShelfApproach.Landing.APPROACH) { flyToFeet(destination, true); return; }
            if (task == Task.LAND && pendingRest > 0) beginRest(pendingRest, restReason);
            else { stop(); if (order() == Order.RETURN) order = Order.PEACEFUL; }
        } else {
            if (destination == null) { stop(); return; }
            fly(destination, .85);
            if (task == Task.PATROL && (animal.distanceToSqr(destination) < .36 || ticks > 200)) land();
        }
    }
    private void mode(boolean combat, String reason) {
        if (retaliation == combat) return;
        retaliation = combat;
        GameplayLog.event(animal,"predator","mode {} -> {}; reason={} task={} prey={} nutrition={} health={}/{}",
                combat?"predation":"combat",combat?"combat":"predation",reason,task,prey==null?"none":prey.getUUID(),
                animal.life().nutrition(),animal.getHealth(),animal.getMaxHealth());
    }
    public void approachCarcass(RemainsEntity body) {
        Vec3 stand = ItemContact.approach(animal, body.position());
        stand = new Vec3(stand.x,body.getY(),stand.z);
        if (animal.position().distanceToSqr(stand) > .0004) {
            if (animal.predatorFlying()) fly(stand,1);
            else SurfaceApproach.walk(animal,stand,.85);
        }
        Motor.face(animal,SurfaceApproach.yaw(animal.position(),body.position()));
    }
    private void approachFood(Vec3 at) {
        if (!animal.childhood().nestling()) fly(at, 1);
        else if (SurfaceApproach.walkResult(animal, at, null, 1) == SurfaceApproach.Result.BLOCKED) stop();
    }
    /** Mouthful of world remains by an eating or scavenging predator. */
    private void takeRemains(RemainsEntity remains, String via) {
        float before = CorpseFood.remaining(remains.data());
        BodyFood.Bite bite = CorpseFood.take(remains, 1);
        boolean taken = !bite.isEmpty() && animal.life().acceptBodyFood(bite);
        GameplayLog.event(animal,"predator","remains feeding via={} remains={} accepted={} portions={} remaining={} -> {} nutrition={}",
                via,remains.getUUID(),taken,bite.portions(),before,CorpseFood.remaining(remains.data()),animal.life().nutrition());
    }
    /** Stay and eat where the bite or the kill happened; land first if still in the air. */
    private void rest(int ticks, String reason) {
        if (dryLanded()) { beginRest(ticks, reason); return; }
        animal.setPredatorFlying(true);
        land();
        if (task == Task.LAND) { pendingRest = ticks; restReason = reason; }
    }
    private void beginRest(int ticks, String reason) {
        mode(false, reason);
        prey = null; food = null; corpse = null; destination = null; pendingRest = 0;
        task = Task.EAT; restTicks = ticks; restReason = reason; eatRemains = null;
        animal.getNavigation().stop(); animal.setPredatorFlying(false);
        GameplayLog.event(animal,"predator","eat pause started; reason={} ticks={} meal_ticks={} nutrition={} energy={}",
                reason,ticks,animal.life().mealTicks(),animal.life().nutrition(),animal.life().energy());
    }
    /** The predator holds still while it chews, then keeps eating the nearby remains bite by bite until it is full or they are empty. */
    private void eat() {
        if (animal.tickCount % 10 == 0)
            eatRemains = level().getEntitiesOfClass(RemainsEntity.class, animal.getBoundingBox().inflate(3), this::edibleCorpse).stream()
                    .min(Comparator.comparingDouble(animal::distanceToSqr)).orElse(null);
        if (eatRemains != null && (!eatRemains.isAlive() || CorpseFood.remaining(eatRemains.data()) <= 0)) eatRemains = null;
        if (eatRemains != null && provisionChild() != null && fetchable(eatRemains) && animal.life().mealTicks() == 0) {
            if (!liftPrey(eatRemains)) approachCarcass(eatRemains);
            return;
        }
        boolean wantsMore = eatRemains != null && animal.life().nutrition() <= 75;
        if (wantsMore) {
            double reach = (animal.getBbWidth() + eatRemains.getBbWidth()) * .5 + .18;
            if (animal.distanceToSqr(eatRemains) > reach * reach) {
                if (animal.tickCount % 10 == 0) animal.getNavigation().moveTo(eatRemains, 1.0);
            } else {
                animal.getNavigation().stop();
                if (animal.life().mealTicks() == 0 && animal.life().canAcceptBodyFood()) takeRemains(eatRemains, "eat_pause");
            }
            restTicks = Math.max(restTicks, 1);
        } else animal.getNavigation().stop();
        if (--restTicks <= 0) {
            GameplayLog.event(animal,"predator","eat pause ended; reason={} meal_ticks={} nutrition={} remains_left={}",restReason,animal.life().mealTicks(),
                    animal.life().nutrition(),eatRemains==null?0:CorpseFood.remaining(eatRemains.data()));
            stop();
        }
    }
    /** Height the attack run may gain above a point before a ceiling. */
    private double clearHeight(Vec3 from) {
        Vec3 start = from.add(0, animal.getBbHeight(), 0);
        var hit = level().clip(new ClipContext(start, start.add(0, SWOOP_HEIGHT, 0), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, animal));
        double free = hit.getType() == HitResult.Type.MISS ? SWOOP_HEIGHT : hit.getLocation().y - start.y;
        return Math.max(1.2, Math.min(SWOOP_HEIGHT, free + animal.getBbHeight() - .5));
    }
    private void setPass(Pass next) { pass = next; passTicks = 0; }
    private double mouthReach() { return .10 * animal.getScale() * animal.growthScale() + .12 * animal.getBbWidth(); }
    private Vec3 nearestBodyPoint(YukkuriEntity target, Vec3 point) {
        AABB body = target.getBoundingBox();
        return new Vec3(Math.clamp(point.x, body.minX, body.maxX), Math.clamp(point.y, body.minY, body.maxY),
                Math.clamp(point.z, body.minZ, body.maxZ));
    }
    /** Aim the mouth at the upper body, then raise the sole only as far as the real water requires. */
    private Vec3 biteApproach(YukkuriEntity target) {
        Vec3 toward = target.position().subtract(animal.position()).multiply(1, 0, 1);
        if (toward.lengthSqr() < 1e-6) toward = MouthPlacement.outward(animal).multiply(1, 0, 1);
        if (toward.lengthSqr() < 1e-6) return null;
        toward = toward.normalize();
        Vec3 mouthOffset = MouthPlacement.position(animal).subtract(animal.position());
        Vec3 contact = new Vec3(target.getX(), target.getBoundingBox().maxY, target.getZ());
        Vec3 goal = contact.subtract(toward.scale(mouthOffset.horizontalDistance())).add(0, -mouthOffset.y, 0);
        double floor = flightFloor(goal);
        // A small baby can be lower than the adult mouth. Keep the adult above the actual ground and
        // let only its measured bite reach bridge that remaining gap; never aim its sole through the floor.
        for (var shape : level().getBlockCollisions(animal, bodyAt(goal)))
            if (!shape.isEmpty()) floor = Math.max(floor, shape.bounds().maxY + .04);
        goal = new Vec3(goal.x, Math.max(goal.y, floor), goal.z);
        Vec3 plannedMouth = goal.add(toward.scale(mouthOffset.horizontalDistance())).add(0, mouthOffset.y, 0);
        return plannedMouth.distanceToSqr(nearestBodyPoint(target, plannedMouth)) <= mouthReach() * mouthReach() ? goal : null;
    }
    private boolean mouthTouches(YukkuriEntity target) {
        Vec3 mouth = MouthPlacement.position(animal);
        Vec3 contact = nearestBodyPoint(target, mouth);
        Vec3 towards = target.getBoundingBox().getCenter().subtract(mouth).multiply(1, 0, 1);
        return mouth.distanceToSqr(contact) <= mouthReach() * mouthReach()
                && (towards.lengthSqr() < .0001 || MouthPlacement.outward(animal).dot(towards.normalize()) > .25)
                && level().clip(new ClipContext(mouth, contact, ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, animal)).getType() == HitResult.Type.MISS;
    }
    private void withdraw(String reason, YukkuriEntity target, Vec3 goal) {
        contactHold = false; withdrawGoal = null; setPass(Pass.WITHDRAW);
        GameplayLog.limited(animal, "predator", "stoop." + reason, 100,
                "stoop ended reason={} prey={} feet={} mouth={} contactGoal={} water={} energy={}", reason, target.getUUID(),
                animal.position(), MouthPlacement.position(animal), goal, animal.isInWaterOrBubble(), animal.life().energy());
    }
    /** Chase and attack as repeated runs: climb above the target, stoop, bite once, pull away, circle back. */
    private void swoop() {
        YukkuriEntity target = prey;
        Vec3 at = target.position();
        double dx = at.x - animal.getX(), dz = at.z - animal.getZ(), horizontal = dx * dx + dz * dz;
        passTicks++;
        animal.getLookControl().setLookAt(target, 30, 30);
        boolean cooled = ticks - lastHit >= SWOOP_GAP;
        switch (pass) {
            case APPROACH -> {
                double height = clearHeight(at);
                phase(Task.CHASE);
                fly(at.add(0, height, 0), 1.2);
                if (task != Task.CHASE || prey != target) return;
                boolean high = animal.getY() >= at.y + Math.min(height, 2.5) - .3;
                if (cooled && animal.hasLineOfSight(target)
                        && (horizontal < 36 && (high || passTicks > 60) || horizontal < 9 && height < 2)) setPass(Pass.STOOP);
            }
            case STOOP -> {
                phase(Task.DIVE);
                Vec3 contact = biteApproach(target);
                if (contact == null || !flightSweepClear(animal.position(), contact)) {
                    withdraw(contact == null ? "mouth_below_clearance" : "body_path_blocked", target, contact);
                    directFlight(animal.position().add(0, .8, 0), .85);
                    return;
                }
                if (mouthTouches(target) && biteTicks == 0 && animal.hasLineOfSight(target)
                        && (retaliation || animal.life().canAcceptBodyFood())) {
                    if (hit(target)) return;
                    contactHold = true; withdrawGoal = null; setPass(Pass.WITHDRAW);
                } else if (horizontal > 100 || stoop.stalled(passTicks, animal.position().distanceTo(contact), mouthReach())) {
                    withdraw("contact_missed", target, contact);
                    directFlight(animal.position().add(0, .8, 0), .85);
                } else directFlight(contact, 1.6);
            }
            case WITHDRAW -> {
                if (contactHold && passTicks <= CONTACT_HOLD) {
                    // The run ends with the body still on the target for a moment: a close foe gets its answering blow here.
                    Vec3 contact = biteApproach(target);
                    if (contact != null && flightSweepClear(animal.position(), contact)) {
                        phase(Task.DIVE); directFlight(contact, .6); return;
                    }
                    contactHold = false;
                }
                phase(Task.CHASE);
                if (withdrawGoal == null) {
                    Vec3 away = new Vec3(-dx, 0, -dz);
                    away = away.lengthSqr() < 1e-4 ? animal.getLookAngle().multiply(-1, 0, -1) : away;
                    away = away.lengthSqr() < 1e-4 ? new Vec3(1, 0, 0) : away.normalize();
                    withdrawGoal = animal.position().add(away.scale(6)).add(0, clearHeight(animal.position()), 0);
                }
                fly(withdrawGoal, 1.5, 64);
                if (task == Task.CHASE && prey == target
                        && (animal.distanceToSqr(withdrawGoal) < 1 || passTicks > CONTACT_HOLD + WITHDRAW_LIMIT)) {
                    contactHold = false; withdrawGoal = null; setPass(Pass.APPROACH);
                }
            }
        }
    }
    /** One bite or combat strike at the end of a stoop; returns true when predation moved on to its eat pause. */
    private boolean hit(YukkuriEntity target) {
        biteTicks = 16;
        int since = ticks - lastHit; lastHit = ticks; hits++;
        float before = target.getHealth();
        if (retaliation) {
            // Combat wounds but never creates a mouthful or alters nutrition.
            boolean accepted = target.hurtBiteWound(level().damageSources().mobAttack(animal), COMBAT_DAMAGE);
            if (accepted) { animal.life().spendEnergy(2); bittenPrey = target; bittenAt = level().getGameTime(); }
            GameplayLog.event(animal,"predator","combat strike target=#{} uuid={} accepted={} damage={} health={} -> {} alive={} pass={} since_last={}",
                    target.getId(),target.getUUID(),accepted,COMBAT_DAMAGE,before,target.getHealth(),target.isAlive(),hits,since);
            return false;
        }
        BodyFood.Bite bite = target.hurtBite(level().damageSources().mobAttack(animal), BITE_DAMAGE);
        if (target.getHealth() < before) { bittenPrey = target; bittenAt = level().getGameTime(); }
        boolean taken = !bite.isEmpty() && animal.life().acceptBodyFood(bite);
        GameplayLog.event(animal,"predator","feeding bite target=#{} uuid={} mouthful_accepted={} damage={} health={} -> {} alive={} pass={} since_last={}",
                target.getId(),target.getUUID(),taken,BITE_DAMAGE,before,target.getHealth(),target.isAlive(),hits,since);
        if (!taken) return false;
        animal.life().spendEnergy(2);
        // The damage result supplied this one mouthful; it stays and eats before looking for anything else.
        cooldown = 25; rest(EAT_PAUSE, "feeding_bite");
        return true;
    }
    private void alertPrey() {
        for (var target : level().getEntitiesOfClass(YukkuriEntity.class, animal.getBoundingBox().inflate(8),
                y -> !EcologyRoutines.predator(y) && y.isAlive() && !y.isNoAi() && y.hasLineOfSight(animal)))
            target.frightenFrom(animal);
    }
    private void phase(Task next) {
        if(task!=next)GameplayLog.limited(animal,"predator","phase:"+next,100,"phase {} -> {}; prey={} elapsed={}",
                task,next,prey==null?"none":prey.getUUID(),ticks);
        task=next;
    }
    public void stop() {
        visiting = null;
        if (task == Task.BRANCH_HOP && animal.childhood().hopping()) animal.childhood().stopHop();
        if (task == Task.FIRST_FLIGHT && animal.childhood().nestling()) {
            GameplayLog.event(animal, "childhood", "first flight outcome={} feet={} energy={}",
                    animal.onGround() ? "interrupted" : "falling", animal.position(), animal.life().energy());
            animal.setPredatorFlying(false);
        }
        watchTicks = 0;
        takeoffExit = null;
        if (task == Task.FOUND) nestBuild.stop();
        if (animal.predatorFlying() && !dryLanded() && canSustainFlight()) {
            cancelNestSearch();
            if (task != Task.LAND) { pendingRest = 0; restTicks = 0; eatRemains = null; landAvoidingFoe(); }
            return;
        }
        if(task!=Task.NONE)GameplayLog.event(animal,"predator","activity stopped; task={} prey={} elapsed={} health={}/{} energy={}",
                task,prey==null?"none":prey.getUUID(),ticks,animal.getHealth(),animal.getMaxHealth(),animal.life().energy());
        if (task == Task.LAND && animal.onGround()) {
            animal.component(YukkuriComponents.MOUTH).releaseItems(Purpose.NEST, 20);
            if (landingAvoid != null && animal.position().distanceToSqr(landingAvoid) > WITHDRAW_CLEARANCE_SQR)
                animal.setPanicTicks(0);
        }
        landingAvoid = null;
        cancelNestSearch();
        mode(false, "stopped");
        task = Task.NONE; prey = null; food = null; corpse = null; territoryTarget = null; destination = null; restTicks = pendingRest = 0; eatRemains = null;
        animal.getNavigation().stop(); animal.setPredatorFlying(false);
        cooldown = Math.max(cooldown, 20);
    }
    public void interruptFounding() { if (task == Task.FOUND) stop(); }
    public CompoundTag save() {
        var tag = new CompoundTag(); tag.putString("Order", order.name()); tag.putInt("Cooldown", cooldown);
        tag.putBoolean("NaturalSpawn", naturalSpawn); if (commander != null) tag.putUUID("Commander", commander);
        if (animal.level().getGameTime() < avoidTerritoryUntil) tag.putLong("AvoidTerritoryUntil", avoidTerritoryUntil);
        nestBuild.save(tag);
        if (failedVisits.size() > 0) {
            long now = animal.level().getGameTime();
            var cells = new ListTag();
            failedVisits.snapshot().forEach((visit, until) -> {
                if (until <= now) return;
                var blocked = new CompoundTag();
                blocked.putUUID("Child", visit.child());
                blocked.putLong("Ground", visit.groundCell());
                blocked.putLong("Stand", visit.standCell());
                blocked.putLong("Until", until);
                cells.add(blocked);
            });
            if (!cells.isEmpty()) tag.put("FailedVisits", cells);
        }
        if (lostNest != null && animal.level().getGameTime() < lostNestUntil) {
            var lost = new CompoundTag(); lost.putLong("Pos", lostNest.asLong()); lost.putLong("Until", lostNestUntil);
            tag.put("LostNest", lost);
        }
        return tag;
    }
    public void load(CompoundTag tag) {
        // Vanilla may restore the former shared range from Attributes before this component loads.
        var followRange = animal.getAttribute(Attributes.FOLLOW_RANGE);
        if (EcologyRoutines.predator(animal) && followRange != null && followRange.getBaseValue() == 16)
            followRange.setBaseValue(32);
        stop(); try { order = Order.valueOf(tag.getString("Order")); } catch (IllegalArgumentException ignored) { order = Order.NATURAL; }
        commander = tag.hasUUID("Commander") ? tag.getUUID("Commander") : null;
        cooldown = Math.clamp(tag.getInt("Cooldown"), 0, 400); naturalSpawn = tag.getBoolean("NaturalSpawn");
        avoidTerritoryUntil = tag.getLong("AvoidTerritoryUntil");
        nestBuild.load(tag);
        failedVisits.clear();
        for (var saved : tag.getList("FailedVisits", Tag.TAG_COMPOUND)) {
            var blocked = (CompoundTag)saved;
            if (!blocked.hasUUID("Child")) continue;
            var visit = new Visit(blocked.getUUID("Child"), blocked.getLong("Ground"), blocked.getLong("Stand"));
            failedVisits.put(visit, blocked.getLong("Until"));
        }
        var lost = tag.getCompound("LostNest");
        lostNest = lost.contains("Pos") ? BlockPos.of(lost.getLong("Pos")) : null;
        lostNestUntil = lost.getLong("Until");
    }
    public static final class BehaviorGoal extends Goal {
        private final YukkuriEntity animal;
        public BehaviorGoal(YukkuriEntity animal) { this.animal = animal; setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK)); }
        @Override public boolean canUse() { return animal.predator().canUse(); }
        @Override public boolean canContinueToUse() { return animal.predator().active() && animal.predator().runnable(); }
        @Override public boolean requiresUpdateEveryTick() { return true; }
        @Override public void start() { animal.predator().start(); }
        @Override public void tick() { animal.predator().tickGoal(); }
        @Override public void stop() { animal.predator().stop(); }
    }
}
