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
    private int elapsed, biteClock, bites, scanWait, smellWait;
    /** Remaining loaded ticks, so unloading pauses the cooldown just as it did before persistence. */
    private static final String HUNT_COOLDOWN = "CannibalHuntCooldown";
    private int huntCooldown() { return entity.getPersistentData().getInt(HUNT_COOLDOWN); }
    private boolean offeredBody;
    private String lastReason = "idle";

    public Cannibalism(YukkuriEntity entity) { this.entity = entity; setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK)); scanWait = 40 + entity.getRandom().nextInt(60); }

    private ServerLevel level() { return (ServerLevel) entity.level(); }
    public boolean active() { return mode != Mode.NONE; }
    /** Status observation: what it is doing and why not. */
    // Same contact boundaries as carrion()/hunt(); observation never performs another bite.
    private static final double CARRION_CONTACT_BASE = 1.6;
    private static final double HUNT_CONTACT_MARGIN = .6;

    /** Current phase only; the presentation arbiter separately requires this exact Goal to be running. */
    @Nullable public String activity() {
        if (mode == Mode.NONE) return null;
        if (entity.life().mealTicks() > 0) return entity.life().activity();
        return switch (mode) {
            case AVOID -> away == null ? null : Behaviors.CANNIBAL_AVOID;
            case CARRION -> body == null || !body.isAlive() ? null
                    : entity.distanceToSqr(body) > CARRION_CONTACT_BASE + body.getBbWidth() * body.getBbWidth()
                    ? Behaviors.CANNIBAL_APPROACH : Behaviors.CANNIBAL_WAIT;
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
                eligible() + " last=" + lastReason + " huntCooldown=" + huntCooldown();
    }
    private boolean free() {
        Identity id = entity.status().identity();
        return id == Identity.WILD || id == Identity.STRAY || id == Identity.SETTLER;
    }
    private boolean eligible() {
        return entity.isAlive() && !entity.isNoAi() && !entity.isPanicking() && !entity.isCommandPlaying() && !entity.profile().houseBody()
                && !entity.profile().dangerToYukkuri() && !entity.life().isSleeping() && !entity.body().dying() && !entity.operations().active()
                && !entity.playLife().active() && !entity.isInWaterOrBubble() && !entity.mind().nonYukkuri();
    }

    /** Every tick from the entity. */
    public void clock() {
        if (entity.level().isClientSide) return;
        if (scanWait > 0) scanWait--;
        if (smellWait > 0) smellWait--;
        int cooldown = huntCooldown();
        if (cooldown > 1) entity.getPersistentData().putInt(HUNT_COOLDOWN, cooldown - 1);
        else if (cooldown == 1) entity.getPersistentData().remove(HUNT_COOLDOWN);
    }

    /** A body its owner offered to this very individual (social/SelfOffering). */
    private boolean offeredToMe(CompoundTag data) {
        return data.getBoolean("Offered") && data.hasUUID("OfferedTo") && data.getUUID("OfferedTo").equals(entity.getUUID());
    }
    /** Whether this individual could bring itself to eat these remains right now. */
    public boolean mayEat(RemainsEntity remains) {
        var data = remains.data();
        if (CorpseFood.remaining(data) <= 0) return false;
        if (offeredToMe(data)) return entity.isAlive();
        if (!free() || !entity.family().adult() || !YukkuriConfig.CANNIBAL_CARRION.get() || data.getBoolean("Kept")) return false;
        if (data.hasUUID("Identity") && kin(data.getUUID("Identity"), data.hasUUID("Partner") ? data.getUUID("Partner") : null)) return false;
        // Every corpse of a kind reeks; only extreme hunger overcomes it, the more so the further it has decayed.
        return entity.life().nutrition() < (RemainsRot.stage(data) >= RemainsRot.ROTTEN ? 4 : 8);
    }
    private boolean kin(UUID dead, UUID deadPartner) {
        return Bonds.corpseKin(entity, dead, deadPartner);
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
        if (free() && nutrition < 12 && huntCooldown() == 0 && YukkuriConfig.CANNIBAL_UNRECOGNIZED.get() && entity.family().adult() && foodNearby() == null
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
                        && !y.profile().dangerToYukkuri() && !y.profile().houseBody() && y.family().adult()
                        && Headwear.fairGame(entity, y) && !y.isPassenger() && !y.body().dying()
                        && !Recognition.grotesque(y)
                        && y.getScale() * y.growthScale() <= entity.getScale() * entity.growthScale() * 1.25f
                        && entity.hasLineOfSight(y) && entity.life().reachable(y.blockPosition(), false)).stream()
                .min(Comparator.comparingDouble(entity::distanceToSqr)).orElse(null);
    }

    @Override public boolean canContinueToUse() {
        if (!eligible() || elapsed > (mode == Mode.AVOID ? 240 : 600)) return false;
        return switch (mode) {
            case AVOID -> away != null && entity.position().distanceToSqr(away) > 4;
            case CARRION -> body != null && body.isAlive() && mayEat(body);
            case HUNT -> prey != null && prey.isAlive() && !prey.body().dying() && bites < 6 && entity.life().nutrition() < 30;
            default -> false;
        };
    }
    @Override public boolean requiresUpdateEveryTick() { return true; }
    @Override public void start() {
        entity.life().interrupt(); entity.social().stop();
        if (mode == Mode.AVOID) entity.showReaction("teary", "stench", 80);
        else if (mode == Mode.HUNT) entity.showReaction("neutral", "hunt_sweet", 100);
        GameplayLog.limited(entity, "cannibal", "start." + mode, 200, "started mode={} body={} prey={} nutrition={}",
                mode, body == null ? null : body.getUUID(), prey == null ? null : prey.getUUID(), entity.life().nutrition());
    }
    @Override public void tick() {
        elapsed++;
        if (biteClock > 0) biteClock--;
        switch (mode) {
            case AVOID -> { if (elapsed % 20 == 1) entity.life().navigate(BlockPos.containing(away), 1.1, false); }
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
        if (mode != Mode.NONE) GameplayLog.limited(entity, "cannibal", "stop." + mode, 200,
                "stopped mode={} body={} prey={} elapsed={} bites={} last={} nutrition={}",
                mode, body == null ? null : body.getUUID(), prey == null ? null : prey.getUUID(), elapsed, bites, lastReason, entity.life().nutrition());
        if (mode == Mode.HUNT) entity.getPersistentData().putInt(HUNT_COOLDOWN, 3000);
        if (mode == Mode.CARRION && bites > 0 && offeredBody) { entity.mind().add(15, "offered_grief"); entity.showReactionDelayed("blank_cry", "offer_grief", 100, 20); }
        offeredBody = false;
        mode = Mode.NONE; body = null; prey = null; away = null;
        entity.getNavigation().stop();
        scanWait = 60;
    }
}
