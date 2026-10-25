package io.github.longshou.mcyukkuri.predator;

import io.github.longshou.mcyukkuri.coordination.DeadlineMap;
import io.github.longshou.mcyukkuri.world.SearchBudget;
import io.github.longshou.mcyukkuri.world.SearchBudget.Result;

import io.github.longshou.mcyukkuri.mind.EmotionEvent;

import io.github.longshou.mcyukkuri.life.CombatCourage;
import io.github.longshou.mcyukkuri.life.EcologyRoutine;
import io.github.longshou.mcyukkuri.life.EcologyRoutines;
import io.github.longshou.mcyukkuri.life.EcologyMovement;
import io.github.longshou.mcyukkuri.entity.Behaviors;
import io.github.longshou.mcyukkuri.body.BodyFood;
import io.github.longshou.mcyukkuri.body.CorpseFood;
import io.github.longshou.mcyukkuri.body.FillingResource;
import io.github.longshou.mcyukkuri.body.RemainsRot;
import io.github.longshou.mcyukkuri.entity.RemainsEntity;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import io.github.longshou.mcyukkuri.human.HumanCommerce;
import io.github.longshou.mcyukkuri.pet.PetScenes;
import io.github.longshou.mcyukkuri.registry.ModItems;
import io.github.longshou.mcyukkuri.settlement.DenBarrier;
import io.github.longshou.mcyukkuri.social.Bonds;
import io.github.longshou.mcyukkuri.social.YukkuriPersonality.Trait;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.navigation.FlyingPathNavigation;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import io.github.longshou.mcyukkuri.pet.BadgeGrade;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;

/** A bounded, interruptible hunt. Movement uses the engine's flying paths and block collision. */
public final class PredatorLife implements EcologyRoutine {
    /** Acquisition stays within a loaded local view; an active hunt uses bounded path segments to continue past it. */
    private static final double HUNT_RANGE = 60;
    /** Leave margin below the predator-only follow range so a moving target is still reachable by each path search. */
    private static final double FLIGHT_PATH_SEGMENT = 24;
    private static final double PATROL_RADIUS = 60;
    public enum Order { NATURAL, PEACEFUL, HUNT, RETURN }
    private enum Task { NONE, TAKEOFF, CHASE, DIVE, SCAVENGE, RETURN, LAND, PATROL, EAT }
    /** One attack run: climb above the target, stoop onto it, pull away, circle back (project design, after the Phantom's swoop). */
    private enum Pass { APPROACH, STOOP, WITHDRAW }
    /** A feeding bite and a combat strike hurt less than the former 2.0 so a blocker survives several passes (project design). */
    private static final float BITE_DAMAGE = 1.5f, COMBAT_DAMAGE = 1.5f;
    /** Ticks a predator stays and eats after one feeding bite, and stays settled after a combat kill (project design). */
    private static final int EAT_PAUSE = 160, KILL_PAUSE = 100;
    /** Least ticks between two hits, stoop timeout, contact hold after a hit, and pull-away flight time (project design). */
    private static final int SWOOP_GAP = 40, STOOP_LIMIT = 30, CONTACT_HOLD = 5, WITHDRAW_TICKS = 14;
    private static final double SWOOP_HEIGHT = 4.5;
    private final YukkuriEntity animal;
    private Task task = Task.NONE;
    private Order order = Order.NATURAL;
    private UUID commander;
    private YukkuriEntity prey;
    private ItemEntity food;
    private RemainsEntity corpse;
    private Vec3 destination;
    private int ticks, cooldown, biteTicks, patrolWait = 160, nestWait, reactWait, restrainWait;
    /** Combat (true) is a continuous defensive fight that never feeds or depends on hunger; predation (false) is bite, then eat. */
    private boolean retaliation;
    private Pass pass = Pass.APPROACH;
    private int passTicks, lastHit = -SWOOP_GAP, hits, restTicks, pendingRest;
    private String restReason = "";
    private boolean contactHold;
    private Vec3 withdrawGoal;
    private RemainsEntity eatRemains;
    private YukkuriEntity bittenPrey;
    private long bittenAt;
    /** Prey with an owner that has already been reported to that owner (game time until which the alert is quiet). */
    private static final DeadlineMap<UUID> ALERTED = new DeadlineMap<>();
    private boolean naturalSpawn;

    public PredatorLife(YukkuriEntity animal) { this.animal = animal; }
    /** Owner alerts never outlive their server. */
    public static void resetAlerts() { ALERTED.clear(); }
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
            default -> Behaviors.IDLE;
        };
    }
    public String animation() {
        if (biteTicks > 0) return "predator_bite";
        if (!animal.predatorFlying()) return null;
        return task == Task.DIVE ? "predator_dive" : task == Task.LAND ? "predator_land" : "predator_fly";
    }
    /** The certificate belongs to this animal; putting on someone else's token cannot teach commands. */
    public boolean obedient() {
        return EcologyRoutines.predator(animal) && animal.pet().effectiveGrade().id() >= BadgeGrade.SILVER.id()
                && animal.life().personality().knowsWait() && animal.life().personality().intelligence() >= 60;
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
        reactWait = 100; PetScenes.act(animal, "ignore", "neutral", "predator_defy", 40, null);
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
    private boolean runnable() { return task == Task.EAT || task == Task.LAND && pendingRest > 0 ? readyExceptMeal() : ready(); }
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
        int threshold = 45 + animal.life().personality().trait(Trait.BOLDNESS) / 5;
        if (order() == Order.HUNT) threshold = 75;
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
                || !waterbornePrey(attacker) && !canFlyTo(attacker.blockPosition())) {
            GameplayLog.limited(animal,"predator","retaliation_refused",200,
                    "retaliation refused; attacker={} health={}/{} energy={} order={} ready={}",
                    attacker==null?"none":attacker.getUUID(),animal.getHealth(),animal.getMaxHealth(),animal.life().energy(),order(),retaliationReady());
            return false;
        }
        prey = attacker; food = null; corpse = null; destination = null;
        task = Task.TAKEOFF; mode(true, "struck_by " + attacker.getUUID());
        // The Goal may already be running for a previous target; reset its shared pursuit setup now.
        start();
        return true;
    }
    private boolean retaliationReady() {
        return ready() && animal.family().adult() && CombatCourage.willing(animal)
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
    public boolean mayHunt(YukkuriEntity target) { return visibleHuntable(target, true); }
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
                || EcologyRoutines.house(target) || target.isPassenger() && !waterbornePrey(target) || target.operations().active()
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
    /** A protected passenger may be hunted only while its actual hat is steady and afloat.
     * Loading, a service-seat move, or a temporary captain transfer does not make the hull's
     * visible passenger immune.  Those states govern what the passenger may initiate; the
     * predator still has to satisfy sight and its later physical attack checks. */
    private boolean waterbornePrey(YukkuriEntity target) {
        var hat = target == null ? null : EcologyMovement.boat(target);
        return hat != null && hat.isAfloat() && !hat.unsteadyMotion() && !hat.towed() && hat.flooding() == 0;
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
        if (bittenPrey != null && level().getGameTime() - bittenAt >= 100) bittenPrey = null;
        if (cooldown > 0) cooldown--;
        if (biteTicks > 0) biteTicks--;
        if (patrolWait > 0) patrolWait--;
        if (nestWait > 0) nestWait--;
        if (reactWait > 0) reactWait--;
        if (restrainWait > 0) restrainWait--;
        if (active() && !runnable()) stop();
        if (animal.predatorFlying() && !active()) animal.setPredatorFlying(false);
        if (animal.isNoAi()) { cancelNestSearch(); return; }
        if (naturalSpawn && nestWait == 0) {
            var result = findNest(true);
            nestWait = result == Result.DEFERRED ? 1 : 400;
            if (result == Result.FOUND) naturalSpawn = false;
        }
        if (animal.predatorFlying() && animal.tickCount % 20 == 0) animal.life().spendEnergy(1);
    }
    public boolean canFlyTo(BlockPos pos) {
        if (!level().hasChunkAt(pos) || !level().getFluidState(pos).isEmpty()) return false;
        var navigation = new FlyingPathNavigation(animal, level());
        SearchBudget.path(animal);
        var path = navigation.createPath(pos, 0);
        return path != null && path.canReach();
    }
    private PredatorNestSearch nestSearch;
    private void cancelNestSearch() {
        if (nestSearch != null) { nestSearch.cancel(animal); nestSearch = null; }
    }
    /** Claims real existing nests first; budget deferral retains the cursor and never reports absence. */
    public Result findNest(boolean weave) {
        if (!(animal.level() instanceof ServerLevel level) || !EcologyRoutines.predator(animal) || !animal.isAlive()) {
            cancelNestSearch(); return Result.NOT_FOUND;
        }
        if (animal.life().homeStand() != null) { cancelNestSearch(); return Result.NOT_FOUND; }
        if (nestSearch != null && (!nestSearch.current(level, animal) || weave && !nestSearch.weave)) cancelNestSearch();
        if (nestSearch == null) nestSearch = new PredatorNestSearch(level, animal, weave);
        // Home maintenance must not downgrade or complete a founder's weave-capable search.
        if (!weave && nestSearch.weave) return Result.DEFERRED;
        var result = nestSearch.advance(level, animal);
        if (result != Result.DEFERRED) cancelNestSearch();
        return result;
    }
    private boolean canUse() {
        if (!ready()) return false;
        if (active()) return true;
        var home = animal.life().homeStand();
        if (home != null && (order() == Order.RETURN || animal.life().energy() < 35 || level().isNight() || level().isRaining())
                && animal.distanceToSqr(Vec3.atBottomCenterOf(home)) > .64) {
            destination = Vec3.atBottomCenterOf(home); task = Task.RETURN; return true;
        }
        if (cooldown > 0 || animal.life().energy() < 20) return false;
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
            prey = level().getEntitiesOfClass(YukkuriEntity.class, animal.getBoundingBox().inflate(HUNT_RANGE, 8, HUNT_RANGE), this::mayHunt).stream()
                    .min(Comparator.<YukkuriEntity>comparingInt(y -> slowChild(y) ? 0 : 1)
                            .thenComparingDouble(y -> slowChild(y) ? y.getAttributeValue(Attributes.MOVEMENT_SPEED) : 0)
                            .thenComparingDouble(y -> animal.distanceToSqr(y) + y.getHealth() - y.mind().preyAppeal())).orElse(null);
            if (prey != null) {
                GameplayLog.event(animal,"predator","prey selected target={} stage={} speed={} slow_child={} range={} distance={}",
                        prey.getUUID(),prey.family().stage(),prey.getAttributeValue(Attributes.MOVEMENT_SPEED),slowChild(prey),HUNT_RANGE,animal.distanceTo(prey));
                task = Task.TAKEOFF; return true;
            }
            if (restrain()) { cooldown = 20; return false; }
        }
        if (patrolWait == 0 && order() != Order.RETURN && animal.family().adult() && animal.life().energy() > 45
                && !level().isNight() && !level().isRaining() && !animal.life().hasUrgentNeed()) {
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
                && target.getAttributeValue(Attributes.MOVEMENT_SPEED) < .18;
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
        ticks = 0; pass = Pass.APPROACH; passTicks = 0; lastHit = -SWOOP_GAP; hits = 0; restTicks = pendingRest = 0; contactHold = false; withdrawGoal = null;
        animal.life().interrupt(); animal.social().stop(); animal.groupInteraction().stop(); animal.playLife().stop();
        GameplayLog.event(animal,"predator","activity started; task={} prey={} food={} corpse={} retaliation={} nutrition={} energy={} destination={}",
                task,prey==null?"none":prey.getUUID(),food==null?"none":food.getUUID(),corpse==null?"none":corpse.getUUID(),
                retaliation,animal.life().nutrition(),animal.life().energy(),destination);
        if (task != Task.SCAVENGE || (corpse != null ? corpse.getY() : food.getY()) > animal.getY() + 1) animal.setPredatorFlying(true);
    }
    private void fly(Vec3 goal, double speed) { fly(goal, speed, 6.25); }
    /** A clear line within directRange squared is flown straight instead of by path nodes. */
    private void fly(Vec3 goal, double speed, double directRange) {
        if (!level().hasChunkAt(BlockPos.containing(goal))) { land(); return; }
        // Path nodes end at block centers. Finish the last approach at the moving target's
        // exact position, otherwise the animal circles just outside bite or landing range.
        if (animal.distanceToSqr(goal) < directRange && level().clip(new ClipContext(
                animal.getEyePosition(), goal.add(0,.2,0), ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE, animal)).getType() == HitResult.Type.MISS) {
            animal.getNavigation().stop();
            animal.getMoveControl().setWantedPosition(goal.x,goal.y,goal.z,speed);
            return;
        }
        if (ticks % 10 == 1 || animal.getNavigation().isDone()) {
            Vec3 pathGoal = flightPathGoal(goal);
            if (!animal.getNavigation().moveTo(pathGoal.x, pathGoal.y, pathGoal.z, speed)) {
                GameplayLog.limited(animal,"predator","path_refused",200,"flight path refused; task={} prey={} destination={} elapsed={}",
                        task,prey==null?"none":prey.getUUID(),goal,ticks);
                // Let the pathfinder try a short rising waypoint before abandoning a blocked approach.
                if (ticks > 60) land();
            }
        }
    }
    /** A chase can cross the acquisition radius without ever asking vanilla's local pathfinder for an overlong route. */
    private Vec3 flightPathGoal(Vec3 goal) {
        Vec3 from = animal.position();
        Vec3 delta = goal.subtract(from);
        double distance = delta.length();
        return distance <= FLIGHT_PATH_SEGMENT ? goal : from.add(delta.scale(FLIGHT_PATH_SEGMENT / distance));
    }
    private void land() {
        if(task!=Task.LAND)GameplayLog.event(animal,"predator","landing started; previous_task={} prey={} elapsed={} energy={}",
                task,prey==null?"none":prey.getUUID(),ticks,animal.life().energy());
        prey = null; food = null; corpse = null; mode(false, "landing"); task = Task.LAND;
        destination = null;
        for (int y = 0; y <= 8 && destination == null; y++) {
            var base = animal.blockPosition().below(y);
            for (var pos : BlockPos.betweenClosed(base.offset(-2,0,-2), base.offset(2,0,2)))
                if (animal.life().dryStand(pos)) { destination = Vec3.atBottomCenterOf(pos); break; }
        }
        if (destination == null) { GameplayLog.event(animal,"predator","landing failed; reason=no_dry_stand");stop(); }
    }
    private void tickGoal() {
        ticks++;
        if (!runnable() || ticks > 800) {
            GameplayLog.event(animal,"predator","activity cancelled; reason={} elapsed={}",ticks>800?"timeout":"not_ready",ticks);
            stop(); return;
        }
        if (animal.life().energy() < 15 && task != Task.LAND && task != Task.EAT) land();
        if (task == Task.NONE) return;
        if (task == Task.EAT) { eat(); return; }
        if (task == Task.TAKEOFF || task == Task.CHASE || task == Task.DIVE) {
            if (!(retaliation ? retaliationReady() : wantsHunt()) || prey == null || !huntable(prey, true)) {
                GameplayLog.event(animal,"predator","pursuit ended; prey={} prey_alive={} retaliation={} energy={} nutrition={} health={}",
                        prey==null?"none":prey.getUUID(),prey!=null&&prey.isAlive(),retaliation,animal.life().energy(),animal.life().nutrition(),animal.getHealth());
                // A fight that ended in a kill leaves the winner settled beside the body, not chasing the next target.
                if (retaliation && prey != null && (!prey.isAlive() || prey.body().dying())) rest(KILL_PAUSE, "combat_kill");
                else land();
                return;
            }
            if (ticks % 20 == 1) alertPrey();
            // A genuine visible predator is the danger that wakes a crew.  The hat's alarm retains
            // the actual escape route; this merely prevents a sleeping passenger from ignoring it.
            if (waterbornePrey(prey)) prey.life().wakeInHat();
            if (task == Task.TAKEOFF) {
                YukkuriEntity target = prey;
                fly(animal.position().add(0, 1.2, 0), 1);
                if (task != Task.TAKEOFF || prey != target) return;
                if (ticks >= 15) { phase(Task.CHASE); alertOwner(); }
            } else swoop();
        } else if (task == Task.SCAVENGE) {
            if (corpse != null) {
                if (!edibleCorpse(corpse) || !animal.life().canAcceptBodyFood()) { stop(); return; }
                var feeding = corpse;
                fly(feeding.position(), 1);
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
            fly(feeding.position(), 1);
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
        } else {
            if (destination == null) { stop(); return; }
            Task current = task;
            Vec3 goal = destination;
            fly(goal, .85);
            if (task != current || destination != goal) return;
            if (animal.position().distanceToSqr(goal) < .36) {
                if (task == Task.PATROL) land();
                else if (task == Task.LAND && pendingRest > 0) beginRest(pendingRest, restReason);
                else { stop(); if (order() == Order.RETURN) order = Order.PEACEFUL; }
            } else if (task == Task.PATROL && ticks > 200) land();
        }
    }
    private void mode(boolean combat, String reason) {
        if (retaliation == combat) return;
        retaliation = combat;
        GameplayLog.event(animal,"predator","mode {} -> {}; reason={} task={} prey={} nutrition={} health={}/{}",
                combat?"predation":"combat",combat?"combat":"predation",reason,task,prey==null?"none":prey.getUUID(),
                animal.life().nutrition(),animal.getHealth(),animal.getMaxHealth());
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
        if (animal.onGround() && !animal.isInWaterOrBubble()) { beginRest(ticks, reason); return; }
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
                fly(at.add(0, .12, 0), 1.6, 49);
                if (task != Task.DIVE || prey != target) return;
                double reach = (animal.getBbWidth() + target.getBbWidth()) * .5 + .18;
                if (animal.distanceToSqr(target) <= reach * reach && biteTicks == 0 && animal.hasLineOfSight(target)
                        && (retaliation || animal.life().canAcceptBodyFood())) {
                    if (hit(target)) return;
                    contactHold = true; withdrawGoal = null; setPass(Pass.WITHDRAW);
                } else if (passTicks > STOOP_LIMIT || horizontal > 100) { contactHold = false; withdrawGoal = null; setPass(Pass.WITHDRAW); }
            }
            case WITHDRAW -> {
                if (contactHold && passTicks <= CONTACT_HOLD) {
                    // The run ends with the body still on the target for a moment: a close foe gets its answering blow here.
                    phase(Task.DIVE);
                    fly(at.add(0, .12, 0), .6, 49);
                    return;
                }
                phase(Task.CHASE);
                if (withdrawGoal == null) {
                    Vec3 away = new Vec3(-dx, 0, -dz);
                    away = away.lengthSqr() < 1e-4 ? animal.getLookAngle().multiply(-1, 0, -1) : away;
                    away = away.lengthSqr() < 1e-4 ? new Vec3(1, 0, 0) : away.normalize();
                    withdrawGoal = animal.position().add(away.scale(6)).add(0, clearHeight(animal.position()), 0);
                }
                fly(withdrawGoal, 1.5);
                if (task == Task.CHASE && prey == target && passTicks > CONTACT_HOLD + WITHDRAW_TICKS) { contactHold = false; withdrawGoal = null; setPass(Pass.APPROACH); }
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
        if(task!=Task.NONE)GameplayLog.event(animal,"predator","activity stopped; task={} prey={} elapsed={} health={}/{} energy={}",
                task,prey==null?"none":prey.getUUID(),ticks,animal.getHealth(),animal.getMaxHealth(),animal.life().energy());
        cancelNestSearch();
        mode(false, "stopped");
        task = Task.NONE; prey = null; food = null; corpse = null; destination = null; restTicks = pendingRest = 0; eatRemains = null;
        animal.getNavigation().stop(); animal.setPredatorFlying(false);
        cooldown = Math.max(cooldown, 20);
    }
    public CompoundTag save() {
        var tag = new CompoundTag(); tag.putString("Order", order.name()); tag.putInt("Cooldown", cooldown);
        tag.putBoolean("NaturalSpawn", naturalSpawn); if (commander != null) tag.putUUID("Commander", commander);
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
