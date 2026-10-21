package io.github.longshou.mcyukkuri.social;

import javax.annotation.Nullable;
import io.github.longshou.mcyukkuri.entity.Behaviors;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.life.YukkuriConfig;
import io.github.longshou.mcyukkuri.life.EcologyMovement;
import io.github.longshou.mcyukkuri.life.YukkuriWorldData;
import io.github.longshou.mcyukkuri.status.Identity;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.item.ItemEntity;
import net.neoforged.neoforge.event.EventHooks;

/**
 * 「ゆっくりたべていってね」 ("please eat me"): the parent's last gift in a famine, a special mechanism of its own and not the ordinary dying
 * words ({@link GriefLife#dying}) nor the predator-blocking {@code sacrifice} (wiki E05 / E08, glossary 献身／おたべなさい; the project takes the
 * version in which the rite can be interrupted). Everything numeric is project design.
 * <ul>
 * <li>Trigger: a free adult (wild, stray, settler) whose own child (or, with no child in need, partner) within 12 blocks has been starving
 *     (nutrition below 10) for three scans in a row without an available meal or viable parental care. Friendliness and the
 *     familiar, urgent relationship determine willingness. A parent already dying near such a child offers its body too ({@link #dedicate}).</li>
 * <li>Chain: approach, ASK ({@code offer_ask}; the child hesitates and cries, unease up), INSIST ({@code offer_insist}), GIVE
 *     ({@code offer_give}): the parent's core gives out ({@code damageCore("offering")}) and the body is left marked
 *     {@code Offered}/{@code OfferedTo}. Feeding the child before the GIVE stops it ({@code offer_spared}).</li>
 * <li>The child then eats the body (social/Cannibalism): an offered body does not reek and the disgust does not stop it, the one exception to the
 *     stench rule; it still grieves ({@code offer_eat}, {@code offer_grief}, unease).</li>
 * </ul>
 */
public final class SelfOffering extends Goal {
    /** GIVE remains an actual goal while the body uses its existing short dying state. */
    private enum Phase { NONE, APPROACH, ASK, INSIST, GIVE }
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
    /** Dialogue events are not animation IDs.  GIVE reuses the existing insist presentation while the dying body is visible. */
    @Nullable public String activity() {
        if (phase == Phase.NONE) return null;
        if (entity.life().mealTicks() > 0) return entity.life().activity();
        if (child == null || !child.isAlive() || child.body().dying()) return null;
        return switch (phase) {
            case APPROACH -> Behaviors.SELF_OFFER_APPROACH;
            case ASK -> Behaviors.SELF_OFFER_ASK;
            case INSIST, GIVE -> Behaviors.SELF_OFFER_INSIST;
            default -> null;
        };
    }

    /** Who this body was given to (set when the offering is made); read by {@code RemainsEntity.snapshot}. */
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
                && (!entity.isPassenger() || EcologyMovement.captain(entity) && EcologyMovement.supported(entity))
                && !entity.operations().active() && !entity.playLife().active() && !entity.isInWaterOrBubble() && !entity.mind().nonYukkuri();
    }
    private boolean mine(YukkuriEntity other) {
        return Bonds.parentOf(entity, other);
    }
    /** A real current meal, never merely an item that happens to be nearby. */
    private boolean foodAvailable(YukkuriEntity target) {
        if (target.profile().aquatic() && target.aquaticLife().availableFood() > 0) return true;
        if (!target.life().canEat()) return false;
        var carried=target.habitat().carrying();
        if (!carried.isEmpty() && carried.is(YukkuriEntity.FOOD) && target.life().acceptsFood(carried)) return true;
        var home=target.habitat().shelter();
        var pile=home==null?null:home.pile();
        if (!target.settlement().underway() && home!=null && home.usable(target) && pile!=null && home.hasFoodFor(target,target)
                && target.life().reachable(pile.standCell(),false)) return true;
        return EventHooks.canEntityGrief(level(),target) && !level().getEntitiesOfClass(ItemEntity.class,
                target.getBoundingBox().inflate(8,3,8), item -> item.isAlive()&&!item.hasPickUpDelay()
                        && target.life().acceptsFood(item.getItem())
                        && (target.distanceToSqr(item)<.5&&target.hasLineOfSight(item)||target.life().reachable(item.blockPosition(),false))).isEmpty();
    }
    /** A parent may prevent the rite only through the existing, physically checked care path. */
    private boolean parentCanFeed(YukkuriEntity target, UUID parentId) {
        return parentId!=null && level().getEntity(parentId) instanceof YukkuriEntity parent && parent.isAlive()
                && parent.family().hasFeedingOpportunity(target);
    }
    private boolean anotherParentOffering(YukkuriEntity target, UUID parentId) {
        return parentId!=null&&!parentId.equals(entity.getUUID())&&level().getEntity(parentId) instanceof YukkuriEntity parent
                && parent.isAlive()&&parent.offering().active()&&target.getUUID().equals(parent.offering().recipient());
    }
    /** Do not replace food currently being eaten or the first parent's already active offering. */
    private boolean alreadyRescued(YukkuriEntity target) {
        if (target.life().mealTicks() > 0) return true;
        UUID mother=target.family().mother(), father=target.family().father();
        return anotherParentOffering(target,mother)||anotherParentOffering(target,father);
    }
    /** A real independent meal or an existing, physically checked care path can take over before GIVE. */
    private boolean remedyAvailable(YukkuriEntity target) {
        if (foodAvailable(target)) return true;
        UUID mother=target.family().mother(), father=target.family().father();
        return parentCanFeed(target,mother)||parentCanFeed(target,father);
    }
    /** The one to give oneself to: a starving child first, then the partner. */
    private YukkuriEntity findRecipient(boolean ignoreHunger) {
        var near = level().getEntitiesOfClass(YukkuriEntity.class, entity.getBoundingBox().inflate(12, 4, 12), y -> y != entity && y.isAlive()
                && !y.body().dying() && !y.profile().dangerToYukkuri() && !y.life().isSleeping() && y.hasLineOfSight(entity)
                && !alreadyRescued(y) && (ignoreHunger || y.life().nutrition() < 10 && !remedyAvailable(y))
                && (mine(y) || y.getUUID().equals(entity.family().partner())));
        return near.stream().min(Comparator.comparingInt((YukkuriEntity y) -> mine(y) ? 0 : 1).thenComparingDouble(entity::distanceToSqr)).orElse(null);
    }

    /** Percent chance of offering, from character, bond, own state and the children's need (see docs/INJURIES_AND_REMAINS.md). */
    private int offerChance(YukkuriEntity target) {
        var per = entity.life().personality();
        boolean familiar = Bonds.careOrMate(entity, target)
                || YukkuriWorldData.get(level()).affinity(entity.getUUID(), target.getUUID()) > 0;
        return Math.round(100 * per.helpProbability(familiar, target.life().nutrition() < 15));
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
            int roll = entity.getRandom().nextInt(100);
            GameplayLog.limited(entity, "offering", "decision", 200,
                    "decision recipient={} nutrition={} recipientNutrition={} scans={} chance={} roll={} accepted={}",
                    target.getUUID(), entity.life().nutrition(), target.life().nutrition(), hungerScans, pct, roll, roll < pct);
            if (roll >= pct) { scanWait = COOLDOWN; lastReason = "declined (" + pct + "%)"; return false; }
        }
        forced = false; hungerScans = 0;
        child = target; recipient = target.getUUID(); phase = Phase.APPROACH; elapsed = 0; phaseTicks = 0; lastReason = "approach";
        return true;
    }
    @Override public boolean canContinueToUse() {
        if (phase == Phase.NONE || elapsed > 900) return false;
        // The final words are part of the offering rather than a detached death reaction.  The usual eligibility check
        // deliberately excludes a dying body, so retain this real Goal until BodyLife completes the existing 80-tick death.
        if (phase == Phase.GIVE) return entity.body().dying();
        if (!eligible()) return false;
        return child != null && child.isAlive() && !child.body().dying();
    }
    @Override public boolean requiresUpdateEveryTick() { return true; }
    @Override public void start() {
        entity.life().interrupt(); entity.social().stop();
        GameplayLog.event(entity, "offering", "approach recipient={} nutrition={} recipientNutrition={} carried={} group={}",
                child.getUUID(), entity.life().nutrition(), child.life().nutrition(), entity.habitat().carrying().getCount(),
                entity.settlement().groupId());
    }
    @Override public void tick() {
        elapsed++;
        if (child == null) return;
        entity.getLookControl().setLookAt(child, 30, 30);
        if (phase == Phase.GIVE) {
            EcologyMovement.halt(entity);
            EcologyMovement.halt(child);
            child.getLookControl().setLookAt(entity, 30, 30);
            return;
        }
        boolean rescued = alreadyRescued(child);
        boolean remedy = remedyAvailable(child);
        // A new real meal or care opportunity takes over at every pre-GIVE phase, including the approach.
        if (child.life().nutrition() >= 25 || rescued || remedy) {
            EcologyMovement.halt(entity); EcologyMovement.halt(child);
            entity.showReaction("happy", "offer_spared", 80);
            lastReason = child.life().nutrition() >= 25 ? "spared (child fed)"
                    : rescued ? "spared (care already started)" : "spared (food available)";
            GameplayLog.event(entity, "offering", "spared recipient={} nutrition={} phase={} elapsed={} reason={}",
                    child.getUUID(), child.life().nutrition(), phase, elapsed, lastReason);
            phase = Phase.NONE; recipient = null;
            return;
        }
        if (phase == Phase.APPROACH) {
            if (entity.distanceToSqr(child) > 6 || !entity.hasLineOfSight(child)) {
                if (elapsed % 10 == 1) EcologyMovement.approach(entity, child, 1.1);
                return;
            }
            EcologyMovement.halt(entity);
            phase = Phase.ASK; phaseTicks = 0; lastReason = "ask";
            GameplayLog.event(entity, "offering", "phase=ASK recipient={} elapsed={}", recipient, elapsed);
            child.life().interrupt();
            entity.showReaction("teary", "offer_ask", ASK_TICKS);
            child.showReactionDelayed("teary", "offer_refuse", ASK_TICKS, 24);
            child.mind().add(12, "offer_dread");
            return;
        }
        EcologyMovement.halt(entity); EcologyMovement.halt(child);
        child.getLookControl().setLookAt(entity, 30, 30);
        if ((EcologyMovement.boat(entity) != null || EcologyMovement.boat(child) != null)
                && (entity.distanceToSqr(child) > 6 || !entity.hasLineOfSight(child))) {
            phase = Phase.APPROACH; phaseTicks = 0; lastReason = "recipient_moved"; return;
        }
        phaseTicks++;
        if (phase == Phase.ASK && phaseTicks >= ASK_TICKS) {
            phase = Phase.INSIST; phaseTicks = 0; lastReason = "insist";
            GameplayLog.event(entity, "offering", "phase=INSIST recipient={} elapsed={}", recipient, elapsed);
            entity.showReaction("teary", "offer_insist", INSIST_TICKS);
            child.showReactionDelayed("blank_cry", "offer_refuse", INSIST_TICKS, 30);
            child.mind().add(10, "offer_dread");
        } else if (phase == Phase.INSIST && phaseTicks >= INSIST_TICKS) {
            give();
        }
    }
    private void give() {
        lastReason = "given";
        phase = Phase.GIVE; // recipient stays set for the snapshot taken at death and the Goal presents the final words
        entity.body().damageCore("offering");
        GameplayLog.event(entity, "offering", "phase=GIVE recipient={} dying={} recipientNutrition={} elapsed={}",
                recipient, entity.body().dying(), child.life().nutrition(), elapsed);
    }
    /** A dying individual (core hurt for another reason) with a starving child close by gives that child its body too. */
    public boolean dedicate() {
        if (!YukkuriConfig.SELF_OFFERING.get() || !free() || !entity.family().adult() || entity.profile().dangerToYukkuri() || entity.profile().houseBody()) return false;
        var near = level().getEntitiesOfClass(YukkuriEntity.class, entity.getBoundingBox().inflate(10, 3, 10), y -> y != entity && y.isAlive() && !y.body().dying()
                && !y.profile().dangerToYukkuri() && mine(y) && y.life().nutrition() < 10 && y.hasLineOfSight(entity)
                && !alreadyRescued(y));
        var target = near.stream().min(Comparator.comparingDouble(entity::distanceToSqr)).orElse(null);
        if (target == null) return false;
        recipient = target.getUUID(); child = target; phase = Phase.GIVE; elapsed = phaseTicks = 0; lastReason = "dedicate";
        GameplayLog.event(entity, "offering", "dedicated recipient={} recipientNutrition={} dying={}",
                recipient, target.life().nutrition(), entity.body().dying());
        return true;
    }
    @Override public void stop() {
        if (phase != Phase.NONE) GameplayLog.event(entity, "offering",
                "goal stopped phase={} recipient={} elapsed={} dying={} alive={} recipientAlive={} recipientDying={} panic={} water={} timeout={}",
                phase, recipient, elapsed, entity.body().dying(), entity.isAlive(), child != null && child.isAlive(),
                child != null && child.body().dying(), entity.isPanicking(), entity.isInWaterOrBubble(), elapsed > 900);
        if (phase != Phase.NONE) lastReason = "interrupted";
        phase = Phase.NONE; EcologyMovement.halt(entity);
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
