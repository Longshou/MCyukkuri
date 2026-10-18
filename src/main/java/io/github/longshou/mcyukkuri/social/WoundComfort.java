package io.github.longshou.mcyukkuri.social;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.family.BirthStaging;
import io.github.longshou.mcyukkuri.family.FamilyFeedingContact;
import io.github.longshou.mcyukkuri.family.FamilyLife;
import io.github.longshou.mcyukkuri.life.SurfaceApproach;
import io.github.longshou.mcyukkuri.life.YukkuriWorldData;
import java.util.Comparator;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;

/**
 * A mate, parent or child who notices an injured companion walks up and licks it, crying with worry.
 *
 * <p>The saliva is sugar water, and sweetness heals (komicolle 11518838: "facing any harm, yukkuri lick to heal or comfort"). A finished
 * lick closes one level of a mild wound (severity 1, or a scratch of severity 2) and returns a little health; each ease step
 * also restores a quarter point while a mild wound is open. The licker pays 3 nutrition. Deep tears and dents, serious burns,
 * infection, mould and lost filling are beyond a lick: the licker still comforts (closeness and stress) but nothing mends. It never
 * replaces orange juice, flour paste or a bandage, and does not touch hygiene. The wiki N65 note ("not a general treatment")
 * is kept for those deep injuries; komicolle wins for the mild ones (worldview decision C2). Config {@code lickHeals} turns the healing off.
 * Server-authoritative and transient: a session is never saved, only the two cooldowns are.</p>
 */
public final class WoundComfort {
    /** Radius within which a kin notices a hurt one it can see. */
    private static final double NOTICE_RANGE = 10;
    private static final int SCAN_TICKS = 60;
    /** Longest walk to the wounded one before the kin gives up. */
    private static final int APPROACH_LIMIT = 400;
    /** Uninterrupted mouth-contact ticks of one comforting session (10 s). */
    public static final int CONTACT_TICKS = 200;
    /** Contact may be lost this long (e.g. a bump) before the session ends. */
    private static final int LOST_CONTACT = 30;
    /** After a session the kin does not start another one for a minute; a failed attempt waits less. */
    private static final int KIN_COOLDOWN = 1200;
    private static final int FAILED_COOLDOWN = 400;
    /** Interrupted by something urgent (the hurt one bolted, a hunger emergency): the kin tries again soon, not after a minute. */
    private static final int INTERRUPTED_COOLDOWN = 80;
    /** A wounded one that was comforted is not licked again for three minutes. */
    private static final int RELIEF_COOLDOWN = 3600;
    /** After a lick that mended something but left more mild wounds, the next lick waits only this long. */
    private static final int RELIEF_SHORT = 400;
    /** Every this many contact ticks the wounded one is eased a little. */
    private static final int EASE_INTERVAL = 40;

    private static final class Session {
        final YukkuriEntity licker, victim;
        int elapsed, contact, apart;
        boolean ended;
        Session(YukkuriEntity licker, YukkuriEntity victim) { this.licker = licker; this.victim = victim; }
    }

    private final YukkuriEntity entity;
    private Session session;
    private int cooldown, relief, scanWait;

    public WoundComfort(YukkuriEntity entity) { this.entity = entity; }

    public boolean active() { return session != null && !session.ended; }
    public boolean licking() { return active() && session.licker == entity; }
    public boolean receiving() { return active() && session.victim == entity; }
    /** True while a comforting lick is touching the wounded one. */
    public boolean inContact() { return active() && session.contact > 0; }
    /** Recently comforted: the cry gives way to a calmer face and the one is not licked again for a while. */
    public boolean soothed() { return relief > 0; }
    public int victimId() { return licking() ? session.victim.getId() : -1; }
    public float contactProgress() { return licking() ? Math.min(1f, session.contact / 20f) : 0f; }
    public String activity() { return !active() ? "idle" : licking() ? "comfort_lick" : "comfort_receive"; }

    private ServerLevel level() { return (ServerLevel) entity.level(); }

    public void clock() {
        if (entity.level().isClientSide) return;
        if (cooldown > 0) cooldown--;
        if (relief > 0) relief--;
        if (scanWait > 0) scanWait--;
        if (active() && !valid()) stop();
    }

    /** Why this one cannot take part right now (an emergency or another activity wins); null when calm. */
    private static String busyReason(YukkuriEntity e, boolean hurtOne) {
        if (!e.isAlive() || e.isRemoved()) return "dead";
        if (e.isNoAi()) return "noai";
        if (!e.onGround()) return "airborne";
        if (e.isPassenger()) return "passenger";
        if (e.isPanicking()) return "panic";
        // The hurt one flashes whenever its own wound bleeds; that is the very reason it is being comforted, not an interruption.
        if (e.hurtTime != 0 && !hurtOne) return "hurt_flash";
        if (e.isCommandPlaying()) return "command_action";
        if (e.isOnFire()) return "fire";
        if (e.isInWaterOrBubble()) return "water";
        if (e.operations().active()) return "operation";
        if (e.predator().active()) return "predator";
        if (e.social().holdingStill()) return "social_hold";
        if (e.life().hasUrgentNeed()) return "urgent_need";
        if (e.life().isSleeping()) return "sleeping";
        if (e.life().mealTicks() != 0) return "meal";
        if (!e.life().activity().equals("idle")) return "life_" + e.life().activity();
        if (e.family().active() && !e.family().yieldsToComfort()) return "family_" + e.family().activity();
        if (e.family().awaitingLanding()) return "awaiting_landing";
        if (e.childhood().active()) return "childhood";
        if (e.careApproach().waiting()) return "care_approach";
        if (e.house().active()) return "house";
        if (e.habitat().active()) return "habitat";
        if (e.independence().active()) return "independence";
        if (e.settlement().active() || e.settlement().duties().active()) return "settlement";
        if (e.groupInteraction().active()) return "group";
        if (e.playLife().active()) return "play";
        if (e.pet().busy()) return "pet";
        if (e.dailyLife().active()) return "daily";
        if (io.github.longshou.mcyukkuri.event.WildEvents.reserved(e)) return "wild_event";
        if (io.github.longshou.mcyukkuri.settlement.Seasons.confined(e)) return "confined";
        return null;
    }


    /** A kin that is itself hurt, hungry or tired needs care before it can give any. */
    private static String unableReason(YukkuriEntity licker) {
        var busy = busyReason(licker, false);
        if (busy != null) return "licker_" + busy;
        if (licker.life().nutrition() < 40) return "licker_hungry";
        if (licker.life().energy() < 35) return "licker_tired";
        if (licker.body().wound() != 0 || licker.body().burns() != 0 || licker.body().infection() != 0) return "licker_hurt";
        if (licker.life().spicyTicks() != 0) return "licker_spicy";
        if (!(licker.family().adult() || licker.family().stage() != FamilyLife.Stage.BABY && licker.childhood().canJump())) return "licker_too_young";
        return null;
    }

    private static boolean ablePartner(YukkuriEntity licker) { return unableReason(licker) == null; }

    /** A visible wound, not a newborn (the birth staging already comforts a fallen newborn), nobody licking it yet. */
    private static String victimReason(YukkuriEntity victim) {
        if (victim.family().stage() == FamilyLife.Stage.BABY) return "newborn";
        if (victim.woundComfort().session != null) return "being_licked";
        if (victim.woundComfort().relief != 0) return "already_soothed";
        if (victim.body().wound() <= 0 && victim.body().burns() <= 0) return "no_wound";
        var busy = busyReason(victim, true);
        return busy == null ? null : "victim_" + busy;
    }

    private static boolean needsComfort(YukkuriEntity victim) { return victimReason(victim) == null; }

    /**
     * Mate (mutual), parent or child, who does not dislike it. Kin are known by blood or bond, not by the
     * badge/accessory test of {@link io.github.longshou.mcyukkuri.life.YukkuriLife#canRecognize}, which is for strangers.
     */
    private String kinReason(YukkuriEntity victim) {
        UUID me = entity.getUUID(), other = victim.getUUID();
        var mine = entity.family();
        var theirs = victim.family();
        boolean mates = other.equals(mine.partner()) && me.equals(theirs.partner());
        boolean parent = me.equals(theirs.mother()) || me.equals(theirs.father());
        boolean child = other.equals(mine.mother()) || other.equals(mine.father());
        if (!(mates || parent || child)) return "not_kin";
        if (YukkuriWorldData.get(level()).affinity(me, other) < 0) return "dislikes";
        return null;
    }

    private boolean kin(YukkuriEntity victim) { return kinReason(victim) == null; }

    /** Why this one is not licking {@code victim} right now (null: it would start). Diagnostic and plan share it. */
    public String whyNot(YukkuriEntity victim) {
        if (victim == entity) return "self";
        var unable = unableReason(entity);
        if (unable != null) return unable;
        var v = victimReason(victim);
        if (v != null) return v;
        var k = kinReason(victim);
        if (k != null) return k;
        if (!entity.hasLineOfSight(victim)) return "no_line_of_sight";
        if (cooldown > 0) return "cooldown_" + cooldown;
        return null;
    }

    /** Autonomous noticing; scans a few times a second at most. */
    public boolean plan() {
        if (entity.level().isClientSide) return false;
        if (active()) return licking() && valid();
        if (scanWait > 0 || cooldown > 0) return false;
        scanWait = SCAN_TICKS + entity.getRandom().nextInt(20);
        var near = level().getEntitiesOfClass(YukkuriEntity.class, entity.getBoundingBox().inflate(NOTICE_RANGE),
                other -> other != entity && (other.body().wound() > 0 || other.body().burns() > 0));
        YukkuriEntity found = null;
        for (var other : near.stream().sorted(Comparator.comparingDouble(entity::distanceToSqr)).toList()) {
            String why = whyNot(other);
            if (DEBUG) io.github.longshou.mcyukkuri.MCYukkuri.LOGGER.info("[comfort-debug] {} -> {}: {}", entity.getId(), other.getId(), why);
            if (why == null && found == null) found = other;
        }
        return found != null && begin(found);
    }

    static final boolean DEBUG = false;

    private boolean begin(YukkuriEntity victim) {
        if (active() || victim.woundComfort().session != null) return false;
        session = new Session(entity, victim);
        victim.woundComfort().session = session;
        entity.social().stop(); victim.social().stop();
        // Everyday errands (waiting for the child, following the parent) are put down for the comforting.
        if (entity.family().yieldsToComfort()) entity.family().stop();
        if (victim.family().yieldsToComfort()) victim.family().stop();
        victim.getNavigation().stop();
        entity.setPersistenceRequired();
        return true;
    }

    public boolean valid() {
        var why = invalidReason();
        if (why != null && DEBUG) io.github.longshou.mcyukkuri.MCYukkuri.LOGGER.info("[comfort-debug] session {} end: {}", entity.getId(), why);
        return why == null;
    }

    private String invalidReason() {
        if (!active()) return "inactive";
        var s = session;
        if (s.elapsed > APPROACH_LIMIT + CONTACT_TICKS + LOST_CONTACT) return "timeout";
        var licker = s.licker;
        var victim = s.victim;
        if (licker.level() != victim.level() || licker.distanceToSqr(victim) > 144) return "far";
        var k = licker.woundComfort().kinReason(victim);
        if (k != null) return k;
        if (!licker.hasLineOfSight(victim)) return "no_line_of_sight";
        // The lick only continues while the same wound is still there to worry about.
        var able = unableReason(licker);
        if (able != null) return able;
        var busy = busyReason(victim, true);
        if (busy != null) return "victim_" + busy;
        if (victim.body().wound() <= 0 && victim.body().burns() <= 0) return "healed";
        return null;
    }

    private boolean touching(Session s) {
        return s.licker.onGround() && s.victim.onGround() && FamilyFeedingContact.mouthsMeet(s.licker, s.victim);
    }

    /** Licker side: walk up beside the hurt one without teleporting, then lick. The hurt one is held still by {@link #tickReceiver()}. */
    public void tickGoal() {
        if (!licking() || !valid()) { stop(); return; }
        var s = session;
        var victim = s.victim;
        s.elapsed++;
        entity.getLookControl().setLookAt(victim, 30, 30);
        if (s.contact == 0 && !touching(s)) {
            if (s.elapsed > APPROACH_LIMIT) { failed(); return; }
            var spot = FamilyFeedingContact.approachSpot(entity, victim);
            if (SurfaceApproach.walk(entity, spot, .9)
                    || entity.position().subtract(victim.position()).multiply(1, 0, 1).length() < FamilyFeedingContact.mouthDistance(entity, victim) + .3) {
                FamilyFeedingContact.faceEachOther(entity, victim);
            }
            return;
        }
        SurfaceApproach.halt(entity);
        FamilyFeedingContact.faceEachOther(entity, victim);
        if (!touching(s)) {
            if (++s.apart > LOST_CONTACT) failed();
            return;
        }
        s.apart = 0;
        s.contact++;
        if (s.contact % 30 == 1) BirthStaging.tears(entity);
        if (s.contact % EASE_INTERVAL == 0) ease(victim);
        if (s.contact >= CONTACT_TICKS) complete();
    }

    /** The hurt one waits for its kin and turns its face to it; it is not chased while fleeing (see {@link #calm}). */
    public void tickReceiver() {
        if (!receiving()) return;
        var licker = session.licker;
        SurfaceApproach.halt(entity);
        SurfaceApproach.face(entity, SurfaceApproach.yaw(entity.position(), licker.position()));
        entity.getLookControl().setLookAt(licker, 30, 30);
    }

    /** Mood (closeness need, stress) and, while a mild wound is open, a little health from the sugar-water saliva. */
    private void ease(YukkuriEntity victim) {
        victim.dailyLife().comfort(8);
        victim.pet().addStress(-1);
        if (io.github.longshou.mcyukkuri.life.YukkuriConfig.LICK_HEALS.get() && victim.body().canLickMend()) victim.heal(.25f);
    }

    private void complete() {
        var s = session;
        var victim = s.victim;
        var licker = s.licker;
        boolean mended = io.github.longshou.mcyukkuri.life.YukkuriConfig.LICK_HEALS.get() && victim.body().canLickMend() && victim.body().lickMend();
        if (mended) licker.life().spendNutrition(3);
        // Mild wounds still open after a good lick are licked again soon; deep ones only wait the full comfort cooldown.
        victim.woundComfort().relief = mended && victim.body().canLickMend() ? RELIEF_SHORT : RELIEF_COOLDOWN;
        victim.dailyLife().comfort(10);
        licker.dailyLife().comfort(10);
        victim.showReaction("closed_smile", "", 60);
        licker.showReaction("teary", "", 50);
        BirthStaging.affection(licker);
        var relations = YukkuriWorldData.get(level());
        relations.relate(licker.getUUID(), victim.getUUID(), 1);
        relations.relate(victim.getUUID(), licker.getUUID(), 1);
        end(KIN_COOLDOWN);
    }

    private void failed() { end(FAILED_COOLDOWN); }

    public void stop() {
        if (DEBUG && session != null && !session.ended) {
            var trace = new Throwable().getStackTrace();
            io.github.longshou.mcyukkuri.MCYukkuri.LOGGER.info("[comfort-debug] stop entity {} contact {} elapsed {} by {} <- {} <- {}", entity.getId(), session.contact, session.elapsed,
                    trace.length > 1 ? trace[1] : "?", trace.length > 2 ? trace[2] : "?", trace.length > 3 ? trace[3] : "?");
        }
        if (session != null && !session.ended) end(INTERRUPTED_COOLDOWN);
        session = null;
    }

    private void end(int kinCooldown) {
        var s = session;
        if (s == null || s.ended) return;
        s.ended = true;
        s.licker.woundComfort().session = null;
        s.victim.woundComfort().session = null;
        s.licker.woundComfort().cooldown = Math.max(s.licker.woundComfort().cooldown, kinCooldown);
        s.licker.getNavigation().stop(); s.victim.getNavigation().stop();
    }

    public CompoundTag save() {
        var tag = new CompoundTag();
        tag.putInt("Cooldown", cooldown); tag.putInt("Relief", relief);
        return tag;
    }

    public void load(CompoundTag tag) {
        session = null;
        cooldown = Math.clamp(tag.getInt("Cooldown"), 0, KIN_COOLDOWN);
        relief = Math.clamp(tag.getInt("Relief"), 0, RELIEF_COOLDOWN);
    }
}
