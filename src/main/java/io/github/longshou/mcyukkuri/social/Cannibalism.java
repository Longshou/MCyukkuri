package io.github.longshou.mcyukkuri.social;

import io.github.longshou.mcyukkuri.status.GameplayLog;
import io.github.longshou.mcyukkuri.body.BodyFood;
import io.github.longshou.mcyukkuri.body.CorpseFood;
import io.github.longshou.mcyukkuri.body.FillingResource;
import io.github.longshou.mcyukkuri.body.RemainsRot;
import io.github.longshou.mcyukkuri.entity.Behaviors;
import io.github.longshou.mcyukkuri.entity.RemainsEntity;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.headwear.Headwear;
import io.github.longshou.mcyukkuri.headwear.Recognition;
import io.github.longshou.mcyukkuri.life.YukkuriConfig;
import io.github.longshou.mcyukkuri.life.YukkuriWorldData;
import io.github.longshou.mcyukkuri.life.EcologyMovement;
import io.github.longshou.mcyukkuri.life.SurfaceApproach;
import io.github.longshou.mcyukkuri.body.MouthPlacement;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import io.github.longshou.mcyukkuri.status.Identity;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.util.DefaultRandomPos;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.Vec3;

/**
 * The smell of the dead and the taboo of eating one's own kind (komicolle 11518847: eating one's kind is forbidden yet not rare; the dead give off
 * a stench only yukkuri smell). User ruling 2026-10-18: a kind's corpse reeks to every yukkuri, hat or no hat, fresh or not, so there is no
 * "hatless remains as a sweet". Everything below is project design built on that:
 * <ul>
 * <li>Avoid: any idle individual that is not starving moves away from remains (smell radius {@link RemainsRot#radius}, stronger with decay);
 *     the unease itself is in {@code MindLife.surroundings}.</li>
 * <li>Carrion: only an extremely starved free adult (below 8 nutrition; below 4 for rotten remains) overcomes the disgust and eats remains,
 *     with a heavy rise in unease ("cannibal_guilt"). Willingness depends on hunger, friendliness, self-control and the bond. Pets, owned and house individuals never take part, and the remains of kept
 *     individuals are never eaten. <b>Exception:</b> a body its owner offered to this very child ({@code social/SelfOffering}) is eaten
 *     without disgust, by anyone of that child's age, with grief instead.</li>
 * <li>Hunt: below 12 nutrition with no food in sight, a free adult may bite a living, adult, free-living yukkuri that the intelligence-based
 *     recognition treats as fair game ({@code headwear.Headwear.fairGame}: a hatless one it takes for a monster) and eat what leaks.</li>
 * </ul>
 */
public final class Cannibalism extends Goal {
    private enum Mode { NONE, AVOID, CARRION, HUNT, GUARD }
    private final YukkuriEntity entity;
    private Mode mode = Mode.NONE;
    private RemainsEntity body;
    private YukkuriEntity prey;
    private Vec3 away;
    private int elapsed, biteClock, bites, scanWait, smellWait;
    /** Remaining loaded ticks, so unloading pauses the cooldown just as it did before persistence. */
    private static final String HUNT_COOLDOWN = "CannibalHuntCooldown";
    private int huntCooldown() { return entity.getPersistentData().getInt(HUNT_COOLDOWN); }
    private boolean offeredBody;
    private String lastReason = "idle";
    private boolean running;
    private double decisionScore;
    private String decision = "none";
    private int guardCooldown;
    private boolean guardAttacks;
    private int guardQuietTicks;

    public Cannibalism(YukkuriEntity entity) { this.entity = entity; setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK)); scanWait = 40 + entity.getRandom().nextInt(60); }

    private ServerLevel level() { return (ServerLevel) entity.level(); }
    public boolean active() { return mode != Mode.NONE; }
    /** Status observation: what it is doing and why not. */
    // Same contact boundaries as carrion()/hunt(); observation never performs another bite.
    private static final double CARRION_CONTACT_BASE = 1.6;
    private static final double HUNT_CONTACT_MARGIN = .18;

    /** Current phase only; the presentation arbiter separately requires this exact Goal to be running. */
    @Nullable public String activity() {
        if (mode == Mode.NONE) return null;
        if (entity.life().mealTicks() > 0) return entity.life().activity();
        return switch (mode) {
            case AVOID -> away == null ? null : Behaviors.CANNIBAL_AVOID;
            case CARRION -> body == null || !body.isAlive() ? null
                    : entity.distanceToSqr(body) > CARRION_CONTACT_BASE + body.getBbWidth() * body.getBbWidth()
                    ? Behaviors.CANNIBAL_APPROACH : Behaviors.CANNIBAL_WAIT;
            case GUARD -> prey != null && prey.isAlive() ? guardAttacks ? Behaviors.SELF_DEFENSE : Behaviors.SANCTION_DECLARE : null;
            case HUNT -> {
                if (prey == null || !prey.isAlive()) yield null;
                double reach = (entity.getBbWidth() + prey.getBbWidth()) * .5 + HUNT_CONTACT_MARGIN;
                yield entity.distanceToSqr(prey) > reach * reach ? Behaviors.CANNIBAL_HUNT : Behaviors.CANNIBAL_WAIT;
            }
            default -> null;
        };
    }

    public String describe() {
        return "mode=" + mode.name().toLowerCase() + " smellWait=" + smellWait + " scanWait=" + scanWait + " free=" + free() + " eligible=" +
                eligible() + " last=" + lastReason + " huntCooldown=" + huntCooldown()
                + " decision=" + decision + " score=" + Math.round(decisionScore) + " guardStyle=" + (guardAttacks ? "attack" : "puff")
                + " body=" + (body == null ? null : body.getUUID()) + " target=" + (prey == null ? null : prey.getUUID());
    }
    private boolean free() {
        Identity id = entity.status().identity();
        return id == Identity.WILD || id == Identity.STRAY || id == Identity.SETTLER;
    }
    private boolean eligible() {
        return entity.isAlive() && !entity.isNoAi() && !entity.isPanicking() && !entity.isCommandPlaying() && !entity.profile().houseBody()
                && !entity.profile().dangerToYukkuri() && !entity.life().isSleeping() && !entity.body().dying() && !entity.operations().active()
                && (!entity.isPassenger() || EcologyMovement.supported(entity))
                && !entity.playLife().active() && !entity.isInWaterOrBubble() && !entity.mind().nonYukkuri();
    }

    /** Every tick from the entity. */
    public void clock() {
        if (entity.level().isClientSide) return;
        if (scanWait > 0) scanWait--;
        if (smellWait > 0) smellWait--;
        if (guardCooldown > 0) guardCooldown--;
        int cooldown = huntCooldown();
        if (cooldown > 1) entity.getPersistentData().putInt(HUNT_COOLDOWN, cooldown - 1);
        else if (cooldown == 1) entity.getPersistentData().remove(HUNT_COOLDOWN);
    }

    /** A body its owner offered to this very individual (social/SelfOffering). */
    private boolean offeredToMe(CompoundTag data) {
        if (!data.getBoolean("Offered")) return false;
        if (data.hasUUID("OfferedTo") && data.getUUID("OfferedTo").equals(entity.getUUID())) return true;
        // The same finite offered body can feed the donor's other dependent children too.
        return !entity.family().adult() && data.hasUUID("Identity")
                && (data.getUUID("Identity").equals(entity.family().mother()) || data.getUUID("Identity").equals(entity.family().father()));
    }
    /** Whether this individual could bring itself to eat these remains right now. */
    public boolean mayEat(RemainsEntity remains) {
        var data = remains.data();
        if (CorpseFood.remaining(data) <= 0) return false;
        if (offeredToMe(data)) return entity.isAlive();
        if (!free() || !entity.family().adult() || !YukkuriConfig.CANNIBAL_CARRION.get() || data.getBoolean("Kept")) return false;

        // Every corpse of a kind reeks; only extreme hunger overcomes it, the more so the further it has decayed.
        return entity.life().nutrition() < (RemainsRot.stage(data) >= RemainsRot.ROTTEN ? 4 : 8);
    }
    private boolean relatedBody(RemainsEntity remains) {
        var data = remains.data();
        return data.hasUUID("Identity") && kin(data.getUUID("Identity"), data.hasUUID("Partner") ? data.getUUID("Partner") : null);
    }
    private double carrionScore(RemainsEntity remains) {
        var p = entity.life().personality();
        int stage = RemainsRot.stage(remains.data());
        int affection = remains.data().hasUUID("Identity")
                ? YukkuriWorldData.get(level()).affinity(entity.getUUID(), remains.data().getUUID("Identity")) : 0;
        return 70 + ((stage >= RemainsRot.ROTTEN ? 4 : 8) - entity.life().nutrition()) * 4
                - p.friendliness() * .35 - p.selfControl() * .3 - stage * 8
                - (relatedBody(remains) ? 20 : 0) - Math.max(0, affection) * .2;
    }
    private double huntScore(YukkuriEntity target) {
        var p = entity.life().personality();
        return 55 + (12 - entity.life().nutrition()) * 3 + p.boldness() * .25
                - p.friendliness() * .45 - p.selfControl() * .2 - p.fear(target.getUUID()) * .5 + powerDifference(target);
    }
    private double powerDifference(YukkuriEntity target) {
        double own = entity.getBbWidth() * entity.body().healthRatio() * entity.species().traits().might();
        double other = target.getBbWidth() * target.body().healthRatio() * target.species().traits().might();
        return 20 * Math.clamp(own / Math.max(.01, other) - 1, -1, 1);
    }
    private boolean combatFit() {
        return entity.body().healthRatio() > .4f && entity.life().energy() >= 15
                && !entity.isOnFire() && !entity.body().footBurned();
    }
    private boolean decide(String kind, Entity target, double score, double threshold, boolean capable) {
        decisionScore = score;
        boolean accepted = capable && score >= threshold;
        decision = kind + (accepted ? "_accepted" : capable ? "_unwilling" : "_unfit");
        var p = entity.life().personality();
        GameplayLog.limited(entity, "cannibal", decision, 200,
                "decision={} target={} score={} threshold={} nutrition={} health={} energy={} friendliness={} selfControl={} boldness={}",
                decision, target.getUUID(), score, threshold, entity.life().nutrition(), entity.body().healthRatio(),
                entity.life().energy(), p.friendliness(), p.selfControl(), p.boldness());
        lastReason = decision;
        return accepted;
    }
    /** Only actual running approach/feeding counts as a threat, not a candidate discovered by canUse. */
    private boolean eatingBody(RemainsEntity remains) { return running && mode == Mode.CARRION && body == remains; }
    private boolean beginGuard() {
        if (guardCooldown > 0 || !free() || !entity.family().adult()) return false;
        for (var eater : level().getEntitiesOfClass(YukkuriEntity.class, entity.getBoundingBox().inflate(10, 3, 10),
                y -> y != entity && y.isAlive() && entity.hasLineOfSight(y))) {
            var other = eater.cannibalism();
            var remains = other.body;
            if (remains == null || !remains.isAlive() || !other.eatingBody(remains) || !relatedBody(remains)
                    || other.offeredToMe(remains.data()) || !entity.hasLineOfSight(remains)) continue;
            var p = entity.life().personality();
            int affection = YukkuriWorldData.get(level()).affinity(entity.getUUID(), remains.data().getUUID("Identity"));
            double score = p.friendliness() * .7 + p.selfControl() * .2 + Math.max(0, affection) * .2
                    - Math.max(0, 15 - entity.life().nutrition()) * 1.5;
            guardCooldown = 400;
            if (!decide("guard", eater, score, 50, entity.body().healthRatio() > .2f && entity.life().energy() >= 10)) return false;
            guardAttacks = p.boldness() - p.fear(eater.getUUID()) * .5 + powerDifference(eater) >= 50 && combatFit();
            guardQuietTicks = 0;
            body = remains; prey = eater;
            return begin(Mode.GUARD, guardAttacks ? "guard_attack" : "guard_puff");
        }
        return false;
    }
    private boolean kin(UUID dead, UUID deadPartner) {
        return Bonds.corpseKin(entity, dead, deadPartner);
    }

    @Override public boolean canUse() {
        if (active()) return true;
        if (scanWait > 0 || !eligible()) return false;
        scanWait = 40 + entity.getRandom().nextInt(40);
        if (beginGuard()) return true;
        float nutrition = entity.life().nutrition();
        boolean foodAvailable = ordinaryFoodAvailable();
        if (entity.life().canEat() && !foodAvailable && nutrition < 40) {
            var meal = level().getEntitiesOfClass(RemainsEntity.class, entity.getBoundingBox().inflate(12, 4, 12), this::mayEat).stream()
                    .filter(this::reachable).min(Comparator.comparingDouble(entity::distanceToSqr)).orElse(null);
            if (meal != null && (offeredToMe(meal.data()) || decide("carrion", meal, carrionScore(meal), 35, true))) {
                body = meal; offeredBody = offeredToMe(meal.data());
                return begin(Mode.CARRION, offeredBody ? "offered" : "carrion");
            }
        }
        if (free() && nutrition < 12 && huntCooldown() == 0 && YukkuriConfig.CANNIBAL_UNRECOGNIZED.get() && entity.family().adult() && !foodAvailable) {
            var target = findPrey();
            if (target != null && decide("hunt", target, huntScore(target), 45, combatFit())) {
                prey = target; return begin(Mode.HUNT, "hunt");
            }
        }
        if (smellWait == 0 && nutrition >= 8) {
            var source = RemainsRot.source(level(), entity.position());
            if (source != null) {
                var spot = EcologyMovement.boat(entity) == null ? DefaultRandomPos.getPosAway(entity, 14, 6, source.position()) : waterAway(source.position());
                if (spot == null && EcologyMovement.boat(entity) != null) { smellWait = 100; return false; }
                if (spot == null) {
                    // No random spot fitted the navigation: walk straight away from the smell instead.
                    var dir = entity.position().subtract(source.position()).multiply(1, 0, 1);
                    dir = dir.lengthSqr() < 1e-4 ? new Vec3(1, 0, 0) : dir.normalize();
                    spot = entity.position().add(dir.scale(10));
                }
                smellWait = 600;
                away = spot; return begin(Mode.AVOID, "stench");
            }
        }
        if (decision.equals("none")) lastReason = "nothing to do";
        return false;
    }
    /** Water routes and nearby supported contact replace land navigation only for an actual hat rider. */
    private boolean reachable(Entity target) {
        return EcologyMovement.reachable(entity, target.blockPosition())
                || EcologyMovement.boat(entity) != null && entity.distanceToSqr(target) <= 4 && entity.hasLineOfSight(target);
    }
    private Vec3 waterAway(Vec3 source) {
        var boat = EcologyMovement.boat(entity);
        if (boat == null || !EcologyMovement.captain(entity)) return null;
        Vec3 direction = boat.position().subtract(source).multiply(1, 0, 1);
        direction = direction.lengthSqr() < 1e-4 ? new Vec3(1, 0, 0) : direction.normalize();
        for (float angle : new float[]{0, .7854f, -.7854f, 1.5708f, -1.5708f}) {
            Vec3 point = boat.position().add(direction.yRot(angle).scale(8));
            if (EcologyMovement.reachable(entity, BlockPos.containing(point))) return point;
        }
        return null;
    }
    /** The same authored mouth used for held food must meet the target's real body before a water-side bite. */
    private boolean mouthTouches(Entity target) {
        Vec3 toward = target.position().subtract(entity.position());
        SurfaceApproach.face(entity, (float)(Math.toDegrees(Math.atan2(toward.z, toward.x)) - 90));
        Vec3 mouth = MouthPlacement.position(entity);
        var box = target.getBoundingBox();
        Vec3 point = new Vec3(Math.clamp(mouth.x, box.minX, box.maxX), Math.clamp(mouth.y, box.minY, box.maxY),
                Math.clamp(mouth.z, box.minZ, box.maxZ));
        return mouth.distanceToSqr(point) <= .04 && entity.level().clip(new ClipContext(mouth, point,
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, entity)).getType() == HitResult.Type.MISS;
    }
    private boolean begin(Mode next, String reason) {
        mode = next; elapsed = 0; bites = 0; biteClock = 20; lastReason = reason;
        return true;
    }
    private boolean ordinaryFoodAvailable() {
        if (entity.profile().aquatic() && entity.aquaticLife().availableFood() > 0) return true;
        var carried = entity.habitat().carrying();
        if (!carried.isEmpty() && entity.life().acceptsFood(carried)) return true;
        var home = entity.habitat().shelter();
        var pile = home == null ? null : home.pile();
        if (!entity.settlement().underway() && home != null && home.usable(entity) && pile != null
                && home.hasFoodFor(entity, entity) && entity.life().reachable(pile.standCell(), false)) return true;
        return foodNearby() != null;
    }
    private ItemEntity foodNearby() {
        return level().getEntitiesOfClass(ItemEntity.class, entity.getBoundingBox().inflate(8, 3, 8),
                i -> i.isAlive() && !i.hasPickUpDelay() && entity.life().acceptsFood(i.getItem()) && !FillingResource.isFilling(i.getItem())
                        && reachable(i)).stream().findFirst().orElse(null);
    }
    /** A living adult, free-living, unrecognised hatless yukkuri that is not a pet and not a monster; kin and group get no exemption. */
    private YukkuriEntity findPrey() {
        return level().getEntitiesOfClass(YukkuriEntity.class, entity.getBoundingBox().inflate(10, 3, 10), y -> y != entity && y.isAlive()
                        && !y.profile().dangerToYukkuri() && !y.profile().houseBody() && y.family().adult()
                        && Headwear.fairGame(entity, y) && (!y.isPassenger() || EcologyMovement.boat(y) != null) && !y.body().dying()
                        && !Recognition.grotesque(y)
                        && y.getScale() * y.growthScale() <= entity.getScale() * entity.growthScale() * 1.25f
                        && entity.hasLineOfSight(y) && reachable(y)).stream()
                .min(Comparator.comparingDouble(entity::distanceToSqr)).orElse(null);
    }

    @Override public boolean canContinueToUse() {
        if (!eligible() || elapsed > (mode == Mode.AVOID ? 240 : 600)) return false;
        return switch (mode) {
            case AVOID -> away != null && entity.position().distanceToSqr(away) > 4;
            case CARRION -> body != null && body.isAlive() && mayEat(body);
            case HUNT -> prey != null && prey.isAlive() && !prey.body().dying() && bites < 6 && entity.life().nutrition() < 30
                    && combatFit() && Headwear.fairGame(entity, prey) && !Recognition.grotesque(prey);
            case GUARD -> body != null && body.isAlive() && prey != null && prey.isAlive()
                    && (guardAttacks ? combatFit() : entity.body().healthRatio() > .2f && entity.life().energy() >= 10)
                    && guardQuietTicks < 60 && elapsed < 240 && entity.distanceToSqr(body) <= 144;
            default -> false;
        };
    }
    @Override public boolean requiresUpdateEveryTick() { return true; }
    @Override public void start() {
        running = true;
        entity.life().interrupt(); entity.social().stop();
        if (mode == Mode.AVOID) entity.showReaction("teary", "stench", 80);
        else if (mode == Mode.HUNT) entity.showReaction("neutral", "hunt_sweet", 100);
        else if (mode == Mode.GUARD && !guardAttacks) entity.showReaction("angry", "puff", 80);
        GameplayLog.limited(entity, "cannibal", "start." + mode, 200, "started mode={} body={} prey={} nutrition={}",
                mode, body == null ? null : body.getUUID(), prey == null ? null : prey.getUUID(), entity.life().nutrition());
    }
    @Override public void tick() {
        elapsed++;
        if (biteClock > 0) biteClock--;
        switch (mode) {
            case AVOID -> { if (elapsed % 20 == 1) EcologyMovement.navigate(entity, away, 1.1); }
            case CARRION -> carrion();
            case HUNT -> hunt();
            case GUARD -> guard();
            default -> { }
        }
    }
    private void carrion() {
        entity.getLookControl().setLookAt(body, 30, 30);
        if (entity.distanceToSqr(body) > 1.6 + body.getBbWidth() * body.getBbWidth()
                || EcologyMovement.boat(entity) != null && !mouthTouches(body)) {
            if (!EcologyMovement.approach(entity, body, 1)) lastReason = "approaching_contact";
            return;
        }
        EcologyMovement.halt(entity);
        if (biteClock > 0 || entity.life().mealTicks() > 0 || !entity.life().canAcceptBodyFood()) return;
        var data = body.data();
        boolean offered = offeredToMe(data);
        int stage = RemainsRot.stage(data);
        BodyFood.Bite bite = body.biteFood();
        if (bite.isEmpty() || !entity.life().acceptBodyFood(bite)) {
            GameplayLog.limited(entity, "cannibal", "carrion.refused", 200,
                    "body meal refused body={} portions={} reason={}", body.getUUID(), bite.portions(), bite.isEmpty() ? "empty_bite" : "meal_rejected");
            mode = Mode.NONE; return;
        }
        bites++; biteClock = 70;
        GameplayLog.event(entity, "cannibal", "body meal handed off body={} offered={} rot={} bite={} portions={} nutrition={}",
                body.getUUID(), offered, RemainsRot.id(stage), bites, bite.portions(), entity.life().nutrition());
        if (bites == 1) {
            if (offered) { entity.mind().add(25, "offered_meal"); entity.showReaction("teary", "offer_eat", 120); }
            else { entity.mind().add(25 + stage * 5, "cannibal_guilt"); entity.showReaction("teary", "eat_kin", 100); }
        }
    }
    private void guard() {
        if (prey.cannibalism().eatingBody(body)) guardQuietTicks = 0;
        else guardQuietTicks++;
        entity.getLookControl().setLookAt(prey, 30, 30);
        if (!guardAttacks) {
            // A timid protector protests beside the body without being promoted to an attacker.
            if (entity.distanceToSqr(body) > 2.25) EcologyMovement.approach(entity, body, 1);
            else EcologyMovement.halt(entity);
            return;
        }
        if (!prey.cannibalism().eatingBody(body)) { EcologyMovement.halt(entity); return; }
        double reach = (entity.getBbWidth() + prey.getBbWidth()) * .5 + .18;
        if (entity.distanceToSqr(prey) > reach * reach || !entity.hasLineOfSight(prey)
                || (EcologyMovement.boat(entity) != null || EcologyMovement.boat(prey) != null) && !mouthTouches(prey)) {
            EcologyMovement.approach(entity, prey, 1.2);
            lastReason = "guard_approach";
            return;
        }
        EcologyMovement.halt(entity);
        if (biteClock > 0 || !entity.life().spendEnergy(2)) return;
        biteClock = 30;
        float before = prey.getHealth();
        boolean accepted = prey.hurt(entity.damageSources().mobAttack(entity), 1);
        GameplayLog.event(entity,"cannibal","guard strike target={} body={} accepted={} health={} -> {}",
                prey.getUUID(),body.getUUID(),accepted,before,prey.getHealth());
    }
    private void hunt() {
        entity.getLookControl().setLookAt(prey, 30, 30);
        double reach = (entity.getBbWidth() + prey.getBbWidth()) * .5 + .18;
        if (entity.life().mealTicks() > 0) return;
        boolean afloat = EcologyMovement.boat(entity) != null || EcologyMovement.boat(prey) != null;
        if (entity.distanceToSqr(prey) > reach * reach || !entity.hasLineOfSight(prey) || afloat && !mouthTouches(prey)) {
            if (afloat) { EcologyMovement.approach(entity, prey, 1.25); return; }
            // The last blocks are walked straight at the target: path navigation ends beside it, still out of biting range.
            if (entity.distanceToSqr(prey) < 16) entity.getMoveControl().setWantedPosition(prey.getX(), prey.getY(), prey.getZ(), 1.25);
            else if (elapsed % 8 == 1) entity.getNavigation().moveTo(prey, 1.25);
            return;
        }
        if (afloat) EcologyMovement.halt(entity);
        if (biteClock > 0) return;
        if (!entity.life().canAcceptBodyFood()) return;
        float healthBefore = prey.getHealth();
        BodyFood.Bite bite = prey.hurtBite(level().damageSources().mobAttack(entity), 2);
        if (bite.isEmpty() || !entity.life().acceptBodyFood(bite)) {
            GameplayLog.limited(entity, "cannibal", "hunt.refused", 200,
                    "hunt meal refused prey={} health={} -> {} portions={} reason={}",
                    prey.getUUID(), healthBefore, prey.getHealth(), bite.portions(), bite.isEmpty() ? "empty_bite" : "meal_rejected");
            biteClock = 20; return;
        }
        bites++; biteClock = 35;
        prey.frightenFrom(entity);
        GameplayLog.event(entity, "cannibal", "hunt meal handed off prey={} health={} -> {} bite={} portions={}",
                prey.getUUID(), healthBefore, prey.getHealth(), bites, bite.portions());
        if (!prey.isAlive()) bites = 99;
    }
    @Override public void stop() {
        running = false;
        if ((mode == Mode.HUNT || mode == Mode.GUARD) && prey != null && prey.isAlive() && !combatFit()) {
            lastReason = "combat_unfit";
            entity.fleeFrom(prey);
        }
        if (mode != Mode.NONE) GameplayLog.limited(entity, "cannibal", "stop." + mode, 200,
                "stopped mode={} body={} prey={} elapsed={} bites={} last={} nutrition={}",
                mode, body == null ? null : body.getUUID(), prey == null ? null : prey.getUUID(), elapsed, bites, lastReason, entity.life().nutrition());
        if (mode == Mode.HUNT) entity.getPersistentData().putInt(HUNT_COOLDOWN, 3000);
        if (mode == Mode.CARRION && bites > 0 && offeredBody) { entity.mind().add(15, "offered_grief"); entity.showReactionDelayed("blank_cry", "offer_grief", 100, 20); }
        offeredBody = false;
        mode = Mode.NONE; body = null; prey = null; away = null;
        EcologyMovement.halt(entity);
        scanWait = 60;
    }
}
