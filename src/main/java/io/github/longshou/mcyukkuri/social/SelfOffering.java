package io.github.longshou.mcyukkuri.social;

import javax.annotation.Nullable;
import io.github.longshou.mcyukkuri.entity.Behaviors;
import io.github.longshou.mcyukkuri.MCYukkuri;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.life.YukkuriConfig;
import io.github.longshou.mcyukkuri.life.YukkuriWorldData;
import io.github.longshou.mcyukkuri.status.Identity;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.item.ItemEntity;

/**
 * 「ゆっくりたべていってね」 ("please eat me"): the parent's last gift in a famine, a special mechanism of its own and not the ordinary dying
 * words ({@link GriefLife#dying}) nor the predator-blocking {@code sacrifice} (wiki E05 / E08, glossary 献身／おたべなさい; the project takes the
 * version in which the rite can be interrupted). Everything numeric is project design.
 * <ul>
 * <li>Trigger: a free adult (wild, stray, settler) whose own child (or, with no child in need, partner) within 12 blocks has been starving
 *     (nutrition below 10) for three scans in a row with no food in sight. A parent that is itself starving does it at once; a well-fed
 *     one only now and then (voluntary). A parent already dying near such a child offers its body too ({@link #dedicate}).</li>
 * <li>Chain: approach, ASK ({@code offer_ask}; the child hesitates and cries, unease up), INSIST ({@code offer_insist}), GIVE
 *     ({@code offer_give}): the parent's core gives out ({@code damageCore("offering")}) and the body is left marked
 *     {@code Offered}/{@code OfferedTo}. Feeding the child before the GIVE stops it ({@code offer_spared}).</li>
 * <li>The child then eats the body (social/Cannibalism): an offered body does not reek and the disgust does not stop it, the one exception to the
 *     stench rule; it still grieves ({@code offer_eat}, {@code offer_grief}, unease).</li>
 * </ul>
 */
public final class SelfOffering extends Goal {
    private enum Phase { NONE, APPROACH, ASK, INSIST }
    private static final int ASK_TICKS = 70, INSIST_TICKS = 70, COOLDOWN = 600;
    private final YukkuriEntity entity;
    private Phase phase = Phase.NONE;
    private YukkuriEntity child;
    private UUID recipient;
    private int elapsed, phaseTicks, scanWait, hungerScans;
    private boolean forced;
    private String lastReason = "idle";

    public SelfOffering(YukkuriEntity entity) { this.entity = entity; setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK)); scanWait = 100 + entity.getRandom().nextInt(100); }

    private ServerLevel level() { return (ServerLevel) entity.level(); }
    public boolean active() { return phase != Phase.NONE; }
    /** Who this body was given to (set when the offering is made); read by {@code RemainsEntity.snapshot}. */
    /** Dialogue events are not animation IDs; NONE also covers the instantaneous give/dedicate path. */
    @Nullable public String activity() {
        if (phase == Phase.NONE) return null;
        if (entity.life().mealTicks() > 0) return entity.life().activity();
        if (child == null || !child.isAlive() || child.body().dying()) return null;
        return switch (phase) {
            case APPROACH -> Behaviors.SELF_OFFER_APPROACH;
            case ASK -> Behaviors.SELF_OFFER_ASK;
            case INSIST -> Behaviors.SELF_OFFER_INSIST;
            default -> null;
        };
    }

    public UUID recipient() { return recipient; }
    public String describe() { return "phase=" + phase.name().toLowerCase() + " recipient=" + recipient + " scans=" + hungerScans + " last=" + lastReason; }
    /** Testing door: offer to the nearest own child (or partner) at once, hungry or not. */
    public void force() { forced = true; scanWait = 0; }

    private boolean free() {
        Identity id = entity.status().identity();
        return id == Identity.WILD || id == Identity.STRAY || id == Identity.SETTLER;
    }
    private boolean eligible() {
        return entity.isAlive() && !entity.isNoAi() && !entity.isPanicking() && !entity.isCommandPlaying() && free() && entity.family().adult()
                && !entity.profile().dangerToYukkuri() && !entity.profile().houseBody() && !entity.life().isSleeping() && !entity.body().dying()
                && !entity.operations().active() && !entity.playLife().active() && !entity.isInWaterOrBubble() && !entity.mind().nonYukkuri();
    }
    private boolean mine(YukkuriEntity other) {
        return Bonds.parentOf(entity, other);
    }
    private boolean foodNear(YukkuriEntity around) {
        return !level().getEntitiesOfClass(ItemEntity.class, around.getBoundingBox().inflate(8, 3, 8),
                i -> i.isAlive() && around.life().acceptsFood(i.getItem())).isEmpty();
    }
    /** The one to give oneself to: a starving child first, then the partner. */
    private YukkuriEntity findRecipient(boolean ignoreHunger) {
        var near = level().getEntitiesOfClass(YukkuriEntity.class, entity.getBoundingBox().inflate(12, 4, 12), y -> y != entity && y.isAlive()
                && !y.body().dying() && !y.profile().dangerToYukkuri() && !y.life().isSleeping() && y.hasLineOfSight(entity)
                && (ignoreHunger || y.life().nutrition() < 10 && !foodNear(y)) && (mine(y) || y.getUUID().equals(entity.family().partner())));
        return near.stream().min(Comparator.comparingInt((YukkuriEntity y) -> mine(y) ? 0 : 1).thenComparingDouble(entity::distanceToSqr)).orElse(null);
    }

    /** Percent chance of offering, from character, bond, own state and the children's need (see docs/INJURIES_AND_REMAINS.md). */
    private int offerChance(YukkuriEntity target) {
        var per = entity.life().personality();
        float p = 10 + .5f * (per.attachment() - 50) - .7f * (per.selfishness() - 50) + .25f * (per.selfControl() - 50);
        p += Math.clamp(YukkuriWorldData.get(level()).affinity(entity.getUUID(), target.getUUID()) / 4f, -15f, 15f);
        p += Math.clamp((20 - entity.life().nutrition()) * 1.5f, -15f, 30f);
        p += entity.body().wound() / 4f + entity.mind().unease() / 10f;
        p += Math.clamp((10 - target.life().nutrition()) * 1.5f, 0f, 15f);
        int kids = level().getEntitiesOfClass(YukkuriEntity.class, entity.getBoundingBox().inflate(12, 4, 12), y -> y != entity && y.isAlive() && mine(y) && y.life().nutrition() < 10).size();
        p += Math.min(8, Math.max(0, kids - 1) * 4);
        return Math.clamp(Math.round(p), 0, 95);
    }

    /** Every tick from the entity. */
    public void clock() { if (!entity.level().isClientSide && scanWait > 0) scanWait--; }

    @Override public boolean canUse() {
        if (active()) return true;
        if (!YukkuriConfig.SELF_OFFERING.get() || scanWait > 0 || !eligible()) return false;
        scanWait = 100;
        var target = findRecipient(forced);
        if (target == null) { hungerScans = 0; lastReason = "nobody starving in reach"; forced = false; return false; }
        if (!forced) {
            if (++hungerScans < 3) { lastReason = "child starving, scan " + hungerScans; return false; }
            int pct = offerChance(target);
            if (entity.getRandom().nextInt(100) >= pct) { scanWait = COOLDOWN; lastReason = "declined (" + pct + "%)"; return false; }
        }
        forced = false; hungerScans = 0;
        child = target; recipient = target.getUUID(); phase = Phase.APPROACH; elapsed = 0; phaseTicks = 0; lastReason = "approach";
        return true;
    }
    @Override public boolean canContinueToUse() {
        if (phase == Phase.NONE || !eligible() || elapsed > 900) return false;
        return child != null && child.isAlive() && !child.body().dying();
    }
    @Override public boolean requiresUpdateEveryTick() { return true; }
    @Override public void start() {
        entity.life().interrupt(); entity.social().stop();
        MCYukkuri.LOGGER.info("[offering] {} #{} sets out to offer itself to {} #{}", entity.getVariant().assetId(), entity.getId(), child.getVariant().assetId(), child.getId());
    }
    @Override public void tick() {
        elapsed++;
        entity.getLookControl().setLookAt(child, 30, 30);
        if (phase == Phase.APPROACH) {
            if (entity.distanceToSqr(child) > 6) { if (elapsed % 10 == 1) entity.life().navigate(child.blockPosition(), 1.1, false); return; }
            entity.getNavigation().stop();
            phase = Phase.ASK; phaseTicks = 0; lastReason = "ask";
            child.life().interrupt();
            entity.showReaction("teary", "offer_ask", ASK_TICKS);
            child.showReactionDelayed("teary", "offer_refuse", ASK_TICKS, 24);
            child.mind().add(12, "offer_dread");
            return;
        }
        entity.getNavigation().stop(); child.getNavigation().stop();
        child.getLookControl().setLookAt(entity, 30, 30);
        // The rite can be interrupted: fed in time, the child is no longer starving and the parent lives.
        if (child.life().nutrition() >= 25) {
            entity.showReaction("happy", "offer_spared", 80);
            lastReason = "spared (child fed)";
            MCYukkuri.LOGGER.info("[offering] {} #{} is spared: child #{} was fed", entity.getVariant().assetId(), entity.getId(), child.getId());
            phase = Phase.NONE; recipient = null;
            return;
        }
        phaseTicks++;
        if (phase == Phase.ASK && phaseTicks >= ASK_TICKS) {
            phase = Phase.INSIST; phaseTicks = 0; lastReason = "insist";
            entity.showReaction("teary", "offer_insist", INSIST_TICKS);
            child.showReactionDelayed("blank_cry", "offer_refuse", INSIST_TICKS, 30);
            child.mind().add(10, "offer_dread");
        } else if (phase == Phase.INSIST && phaseTicks >= INSIST_TICKS) {
            give();
        }
    }
    private void give() {
        lastReason = "given";
        MCYukkuri.LOGGER.info("[offering] {} #{} gives its body to {} #{}", entity.getVariant().assetId(), entity.getId(), child.getVariant().assetId(), child.getId());
        phase = Phase.NONE; // recipient stays set for the snapshot taken at death
        entity.body().damageCore("offering");
    }
    /** A dying individual (core hurt for another reason) with a starving child close by gives that child its body too. */
    public boolean dedicate() {
        if (!YukkuriConfig.SELF_OFFERING.get() || !free() || !entity.family().adult() || entity.profile().dangerToYukkuri() || entity.profile().houseBody()) return false;
        var near = level().getEntitiesOfClass(YukkuriEntity.class, entity.getBoundingBox().inflate(10, 3, 10), y -> y != entity && y.isAlive() && !y.body().dying()
                && !y.profile().dangerToYukkuri() && mine(y) && y.life().nutrition() < 10 && y.hasLineOfSight(entity));
        var target = near.stream().min(Comparator.comparingDouble(entity::distanceToSqr)).orElse(null);
        if (target == null) return false;
        recipient = target.getUUID(); child = target;
        return true;
    }
    @Override public void stop() {
        if (phase != Phase.NONE) lastReason = "interrupted";
        phase = Phase.NONE; entity.getNavigation().stop();
        if (entity.isAlive() && !entity.body().dying()) { recipient = null; child = null; }
        scanWait = 200;
    }
    public CompoundTag save() {
        var tag = new CompoundTag();
        if (recipient != null && entity.body().dying()) tag.putUUID("Recipient", recipient);
        return tag;
    }
    public void load(CompoundTag tag) { recipient = tag.hasUUID("Recipient") ? tag.getUUID("Recipient") : null; }
}
