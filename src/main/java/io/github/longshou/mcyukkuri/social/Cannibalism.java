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
 *     with a heavy rise in unease ("cannibal_guilt"). Kin of the dead, pets, owned and house individuals never take part, and the remains of kept
 *     individuals are never eaten. <b>Exception:</b> a body its owner offered to this very child ({@code social/SelfOffering}) is eaten
 *     without disgust, by anyone of that child's age, with grief instead.</li>
 * <li>Hunt: below 12 nutrition with no food in sight, a free adult may bite a living, adult, free-living yukkuri that the intelligence-based
 *     recognition treats as fair game ({@code headwear.Headwear.fairGame}: a hatless one it takes for a monster) and eat what leaks.</li>
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
    private boolean offeredBody;
    private String lastReason = "idle";

    public Cannibalism(YukkuriEntity entity) { this.entity = entity; setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK)); scanWait = 40 + entity.getRandom().nextInt(60); }

    private ServerLevel level() { return (ServerLevel) entity.level(); }
    public boolean active() { return mode != Mode.NONE; }
    /** Status observation: what it is doing and why not. */
    public String describe() {
        return "mode=" + mode.name().toLowerCase() + " smellWait=" + smellWait + " scanWait=" + scanWait + " free=" + free() + " eligible=" + eligible() + " last=" + lastReason + " huntCooldown=" + huntCooldown;
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

    /** A body its owner offered to this very individual (social/SelfOffering). */
    private boolean offeredToMe(CompoundTag data) {
        return data.getBoolean("Offered") && data.hasUUID("OfferedTo") && data.getUUID("OfferedTo").equals(entity.getUUID());
    }
    /** Whether this individual could bring itself to eat these remains right now. */
    public boolean mayEat(RemainsEntity remains) {
        var data = remains.data();
        if (data.getInt("Filling") <= 0) return false;
        if (offeredToMe(data)) return entity.isAlive();
        if (!free() || !entity.family().adult() || !YukkuriConfig.CANNIBAL_CARRION.get() || data.getBoolean("Kept")) return false;
        if (data.hasUUID("Identity") && kin(data.getUUID("Identity"), data.hasUUID("Partner") ? data.getUUID("Partner") : null)) return false;
        // Every corpse of a kind reeks; only extreme hunger overcomes it, the more so the further it has decayed.
        return entity.life().nutrition() < (RemainsRot.stage(data) >= RemainsRot.ROTTEN ? 4 : 8);
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
        if (entity.life().canEat() && foodNearby() == null && nutrition < 40) {
            var meal = level().getEntitiesOfClass(RemainsEntity.class, entity.getBoundingBox().inflate(12, 4, 12), this::mayEat).stream()
                    .filter(r -> entity.life().reachable(r.blockPosition(), false)).min(Comparator.comparingDouble(entity::distanceToSqr)).orElse(null);
            if (meal != null) { body = meal; offeredBody = offeredToMe(meal.data()); return begin(Mode.CARRION, offeredBody ? "offered" : "carrion"); }
        }
        if (free() && nutrition < 12 && huntCooldown == 0 && YukkuriConfig.CANNIBAL_UNRECOGNIZED.get() && entity.family().adult() && foodNearby() == null
                && entity.getRandom().nextInt(3) == 0) {
            var target = findPrey();
            if (target != null) { prey = target; return begin(Mode.HUNT, "hunt"); }
        }
        if (smellWait == 0 && nutrition >= 8) {
            var source = RemainsRot.source(level(), entity.position());
            if (source != null) {
                var spot = DefaultRandomPos.getPosAway(entity, 14, 6, source.position());
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
    /** A living adult, free-living, unrecognised hatless yukkuri that is not a pet and not a monster; kin and group get no exemption. */
    private YukkuriEntity findPrey() {
        return level().getEntitiesOfClass(YukkuriEntity.class, entity.getBoundingBox().inflate(10, 3, 10), y -> y != entity && y.isAlive()
                        && !y.getVariant().isPredator() && !y.getVariant().isHouse() && y.family().adult() 
                        && io.github.longshou.mcyukkuri.headwear.Headwear.fairGame(entity, y) && !y.isPassenger() && !y.body().dying()
                        && !io.github.longshou.mcyukkuri.headwear.Recognition.grotesque(y)
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
        boolean offered = offeredToMe(data);
        int stage = RemainsRot.stage(data);
        var portion = body.bite();
        if (portion.isEmpty() || !entity.life().beginMeal(portion, null, true)) { mode = Mode.NONE; return; }
        bites++; biteClock = 70;
        MCYukkuri.LOGGER.info("[cannibal] {} #{} eats {} remains ({}) bite {}", entity.getVariant().assetId(), entity.getId(), offered ? "offered" : "reeking", RemainsRot.id(stage), bites);
        if (bites == 1) {
            if (offered) { entity.mind().add(25, "offered_meal"); entity.showReaction("teary", "offer_eat", 120); }
            else { entity.mind().add(25 + stage * 5, "cannibal_guilt"); entity.showReaction("teary", "eat_kin", 100); }
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
        if (mode == Mode.CARRION && bites > 0 && offeredBody) { entity.mind().add(15, "offered_grief"); entity.showReactionDelayed("blank_cry", "offer_grief", 100, 20); }
        offeredBody = false;
        mode = Mode.NONE; body = null; prey = null; away = null;
        entity.getNavigation().stop();
        scanWait = 60;
    }
}
