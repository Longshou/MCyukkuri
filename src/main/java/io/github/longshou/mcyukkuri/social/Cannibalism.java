package io.github.longshou.mcyukkuri.social;

import io.github.longshou.mcyukkuri.MCYukkuri;
import io.github.longshou.mcyukkuri.body.FillingResource;
import io.github.longshou.mcyukkuri.body.RemainsRot;
import io.github.longshou.mcyukkuri.entity.RemainsEntity;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.family.FamilyData;
import io.github.longshou.mcyukkuri.life.YukkuriConfig;
import io.github.longshou.mcyukkuri.status.Identity;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.util.DefaultRandomPos;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.Vec3;

/**
 * The smell of the dead and the taboo of eating one's own kind (komicolle 11518847: eating one's kind is forbidden, the dead give off a stench
 * only yukkuri smell, yet it is not rare: without a hat the other is not recognised as kin and is eaten as a sweet; a reply adds that eating
 * a hatless one is not killing kin). Everything below is project design built on that:
 * <ul>
 * <li>Avoid: any idle individual moves away from stale or rotten remains (smell radius {@link RemainsRot#radius}); the unease itself is in
 *     {@code MindLife.surroundings}.</li>
 * <li>Carrion: a starving free individual (wild, stray or settler) eats remains. Hatless remains are just food (nutrition below 20);
 *     remains with a hat are kin, taboo, eaten only stale or rotten and only below 10 nutrition, with guilt. Kin of the dead, pets, owned and
 *     house individuals never take part, and the remains of kept individuals are never eaten.</li>
 * <li>Hunt: below 12 nutrition with no food in sight, a free adult may bite a living, adult, free-living, hatless yukkuri it cannot
 *     recognise (HAT INTERFACE: {@code life().canRecognize}, to be replaced by the hat package's judgement) and eat what leaks.</li>
 * </ul>
 */
public final class Cannibalism extends Goal {
    private enum Mode { NONE, AVOID, CARRION, HUNT }
    private final YukkuriEntity entity;
    private Mode mode = Mode.NONE;
    private RemainsEntity body;
    private YukkuriEntity prey;
    private Vec3 away;
    private int elapsed, biteClock, bites, scanWait, smellWait, huntCooldown;
    private String lastReason = "idle";

    public Cannibalism(YukkuriEntity entity) { this.entity = entity; setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK)); scanWait = 40 + entity.getRandom().nextInt(60); }

    private ServerLevel level() { return (ServerLevel) entity.level(); }
    public boolean active() { return mode != Mode.NONE; }
    /** Status observation: what it is doing and why not. */
    public String describe() {
        return "mode=" + mode.name().toLowerCase() + " free=" + free() + " eligible=" + eligible() + " last=" + lastReason + " huntCooldown=" + huntCooldown;
    }
    private boolean free() {
        Identity id = entity.status().identity();
        return id == Identity.WILD || id == Identity.STRAY || id == Identity.SETTLER;
    }
    private boolean eligible() {
        return entity.isAlive() && !entity.isNoAi() && !entity.isPanicking() && !entity.isCommandPlaying() && !entity.getVariant().isHouse()
                && !entity.getVariant().isPredator() && !entity.life().isSleeping() && !entity.body().dying() && !entity.operations().active()
                && !entity.playLife().active() && !entity.isInWaterOrBubble() && !entity.mind().nonYukkuri();
    }

    /** Every tick from the entity. */
    public void clock() {
        if (entity.level().isClientSide) return;
        if (scanWait > 0) scanWait--;
        if (smellWait > 0) smellWait--;
        if (huntCooldown > 0) huntCooldown--;
    }

    /** Whether this individual could bring itself to eat these remains right now. */
    public boolean mayEat(RemainsEntity remains) {
        if (!free() || !entity.family().adult() || !YukkuriConfig.CANNIBAL_CARRION.get()) return false;
        var data = remains.data();
        if (data.getInt("Filling") <= 0 || data.getBoolean("Kept")) return false;
        if (data.hasUUID("Identity") && kin(data.getUUID("Identity"), data.hasUUID("Partner") ? data.getUUID("Partner") : null)) return false;
        float nutrition = entity.life().nutrition();
        boolean hat = !data.contains("Accessory") || data.getBoolean("Accessory");
        // HAT INTERFACE: "recognised as kin" is the hat package's judgement; the remains keep only whether the hat was worn at death.
        if (!hat) return nutrition < 20;
        return RemainsRot.stage(data) >= RemainsRot.STALE && nutrition < 10;
    }
    private boolean kin(UUID dead, UUID deadPartner) {
        var data = FamilyData.get(level());
        return data.socialKin(entity.getUUID(), dead) || dead.equals(entity.family().partner()) || entity.getUUID().equals(deadPartner)
                || data.related(entity.getUUID(), dead);
    }

    @Override public boolean canUse() {
        if (active()) return true;
        if (scanWait > 0 || !eligible()) return false;
        scanWait = 40 + entity.getRandom().nextInt(40);
        float nutrition = entity.life().nutrition();
        if (free() && nutrition < 20 && entity.life().canEat() && foodNearby() == null) {
            var meal = level().getEntitiesOfClass(RemainsEntity.class, entity.getBoundingBox().inflate(12, 4, 12), this::mayEat).stream()
                    .filter(r -> entity.life().reachable(r.blockPosition(), false)).min(Comparator.comparingDouble(entity::distanceToSqr)).orElse(null);
            if (meal != null) { body = meal; return begin(Mode.CARRION, "carrion"); }
        }
        if (free() && nutrition < 12 && huntCooldown == 0 && YukkuriConfig.CANNIBAL_UNRECOGNIZED.get() && entity.family().adult() && foodNearby() == null
                && entity.getRandom().nextInt(3) == 0) {
            var target = findPrey();
            if (target != null) { prey = target; return begin(Mode.HUNT, "hunt"); }
        }
        if (smellWait == 0 && nutrition >= 20 && entity.family().adult()) {
            var source = RemainsRot.source(level(), entity.position());
            if (source != null) {
                var spot = DefaultRandomPos.getPosAway(entity, 14, 6, source.position());
                smellWait = 600;
                if (spot != null) { away = spot; return begin(Mode.AVOID, "stench"); }
            }
        }
        lastReason = "nothing to do";
        return false;
    }
    private boolean begin(Mode next, String reason) {
        mode = next; elapsed = 0; bites = 0; biteClock = 20; lastReason = reason;
        return true;
    }
    private ItemEntity foodNearby() {
        return level().getEntitiesOfClass(ItemEntity.class, entity.getBoundingBox().inflate(8, 3, 8),
                i -> i.isAlive() && entity.life().acceptsFood(i.getItem()) && !FillingResource.isFilling(i.getItem())).stream().findFirst().orElse(null);
    }
    /** A living adult, free-living, hatless yukkuri that is not kin, not in my group and not a pet. */
    private YukkuriEntity findPrey() {
        return level().getEntitiesOfClass(YukkuriEntity.class, entity.getBoundingBox().inflate(10, 3, 10), y -> y != entity && y.isAlive()
                        && !y.getVariant().isPredator() && !y.getVariant().isHouse() && y.family().adult() && !y.life().hasAccessory()
                        && !entity.life().canRecognize(y) && y.status().identity().free() && !y.isPassenger() && !y.body().dying()
                        && !kin(y.getUUID(), null) && !(entity.settlement().groupId() != null && entity.settlement().groupId().equals(y.settlement().groupId()))
                        && y.getScale() * y.growthScale() <= entity.getScale() * entity.growthScale() * 1.25f
                        && entity.hasLineOfSight(y) && entity.life().reachable(y.blockPosition(), false)).stream()
                .min(Comparator.comparingDouble(entity::distanceToSqr)).orElse(null);
    }

    @Override public boolean canContinueToUse() {
        if (!eligible() || elapsed > (mode == Mode.AVOID ? 240 : 600)) return false;
        return switch (mode) {
            case AVOID -> away != null && entity.position().distanceToSqr(away) > 4;
            case CARRION -> body != null && body.isAlive() && mayEat(body);
            case HUNT -> prey != null && prey.isAlive() && bites < 6 && entity.life().nutrition() < 30;
            default -> false;
        };
    }
    @Override public boolean requiresUpdateEveryTick() { return true; }
    @Override public void start() {
        entity.life().interrupt(); entity.social().stop();
        if (mode == Mode.AVOID) entity.showReaction("teary", "stench", 80);
        else if (mode == Mode.HUNT) entity.showReaction("neutral", "hunt_sweet", 100);
        MCYukkuri.LOGGER.info("[cannibal] {} #{} starts {} (nutrition {})", entity.getVariant().assetId(), entity.getId(), mode, Math.round(entity.life().nutrition()));
    }
    @Override public void tick() {
        elapsed++;
        if (biteClock > 0) biteClock--;
        switch (mode) {
            case AVOID -> { if (elapsed % 20 == 1) entity.life().navigate(net.minecraft.core.BlockPos.containing(away), 1.1, false); }
            case CARRION -> carrion();
            case HUNT -> hunt();
            default -> { }
        }
    }
    private void carrion() {
        entity.getLookControl().setLookAt(body, 30, 30);
        if (entity.distanceToSqr(body) > 1.6 + body.getBbWidth() * body.getBbWidth()) {
            if (elapsed % 10 == 1 && !entity.life().navigate(body.blockPosition(), 1, false)) lastReason = "unreachable";
            return;
        }
        entity.getNavigation().stop();
        if (biteClock > 0 || entity.life().mealTicks() > 0 || !entity.life().canEat()) return;
        var data = body.data();
        var probe = FillingResource.carrion(data, RemainsRot.stage(data));
        if (!entity.life().acceptsFood(probe)) { lastReason = "refuses " + RemainsRot.id(RemainsRot.stage(data)); mode = Mode.NONE; return; }
        boolean hat = !data.contains("Accessory") || data.getBoolean("Accessory");
        int stage = RemainsRot.stage(data);
        var portion = body.bite();
        if (portion.isEmpty() || !entity.life().beginMeal(portion, null, true)) { mode = Mode.NONE; return; }
        bites++; biteClock = 70;
        MCYukkuri.LOGGER.info("[cannibal] {} #{} eats {} remains ({}) bite {}", entity.getVariant().assetId(), entity.getId(), hat ? "kin" : "hatless", RemainsRot.id(stage), bites);
        if (bites == 1) {
            if (hat) { entity.mind().add(10, "cannibal_guilt"); entity.showReaction("teary", "eat_kin", 100); }
            else entity.showReaction("happy", "eat_sweet", 80);
        }
    }
    private void hunt() {
        entity.getLookControl().setLookAt(prey, 30, 30);
        double reach = (entity.getBbWidth() + prey.getBbWidth()) * .5 + .6;
        if (entity.life().mealTicks() > 0) return;
        if (entity.distanceToSqr(prey) > reach * reach) {
            // The last blocks are walked straight at the target: path navigation ends beside it, still out of biting range.
            if (entity.distanceToSqr(prey) < 16) entity.getMoveControl().setWantedPosition(prey.getX(), prey.getY(), prey.getZ(), 1.25);
            else if (elapsed % 8 == 1) entity.getNavigation().moveTo(prey, 1.25);
            return;
        }
        if (biteClock > 0) return;
        // What leaked from the last bite lying close by is eaten before the next bite.
        var leaked = level().getEntitiesOfClass(ItemEntity.class, prey.getBoundingBox().inflate(2, 1, 2),
                i -> i.isAlive() && FillingResource.isFilling(i.getItem()) && entity.life().acceptsFood(i.getItem())).stream().findFirst().orElse(null);
        if (leaked != null && entity.life().canEat()) {
            var one = leaked.getItem().copyWithCount(1);
            if (entity.life().beginMeal(one, null, true)) { leaked.getItem().shrink(1); if (leaked.getItem().isEmpty()) leaked.discard(); biteClock = 60; return; }
        }
        bites++; biteClock = 35;
        prey.frightenFrom(entity);
        if (prey.hurt(level().damageSources().mobAttack(entity), 2) && prey.isAlive()) prey.body().releaseFilling("predation");
        MCYukkuri.LOGGER.info("[cannibal] {} #{} bites hatless {} #{} (bite {})", entity.getVariant().assetId(), entity.getId(), prey.getVariant().assetId(), prey.getId(), bites);
        if (!prey.isAlive()) bites = 99;
    }
    @Override public void stop() {
        if (mode != Mode.NONE) MCYukkuri.LOGGER.info("[cannibal] {} #{} stops {} after {} ticks, {} bites ({})", entity.getVariant().assetId(), entity.getId(), mode, elapsed, bites, lastReason);
        if (mode == Mode.HUNT) huntCooldown = 3000;
        mode = Mode.NONE; body = null; prey = null; away = null;
        entity.getNavigation().stop();
        scanWait = 60;
    }
}
