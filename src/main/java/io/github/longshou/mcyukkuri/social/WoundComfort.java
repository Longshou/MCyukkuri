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
 * <p>This only comforts. The lick lowers the wounded one's need for closeness and its stress and shows
 * relief; it never heals, never lowers infection, never replaces orange juice or a bandage, and does not
 * touch hygiene (which gates infection and bandage healing). Wiki N65 limits: licking a wound is not a
 * general treatment; the worried, crying lick itself is the deibu.html line about the licked traces.
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
    /** A wounded one that was comforted is not licked again for three minutes. */
    private static final int RELIEF_COOLDOWN = 3600;
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

    /** Neither party may be in an emergency or another activity; the urgent behaviours always win. */
    private static boolean calm(YukkuriEntity e) {
        return e.isAlive() && !e.isRemoved() && !e.isNoAi() && e.onGround() && !e.isPassenger() && !e.isPanicking() && e.hurtTime == 0
                && !e.isCommandPlaying() && !e.isOnFire() && !e.isInWaterOrBubble()
                && !e.operations().active() && !e.predator().active() && !e.social().holdingStill()
                && !e.life().hasUrgentNeed() && !e.life().isSleeping() && e.life().mealTicks() == 0 && e.life().activity().equals("idle")
                && !e.family().active() && !e.family().awaitingLanding() && !e.childhood().active() && !e.careApproach().waiting()
                && !e.house().active() && !e.habitat().active() && !e.independence().active()
                && !e.settlement().active() && !e.settlement().duties().active()
                && !e.groupInteraction().active() && !e.playLife().active() && !e.pet().busy() && !e.dailyLife().active()
                && !io.github.longshou.mcyukkuri.event.WildEvents.reserved(e)
                && !io.github.longshou.mcyukkuri.settlement.Seasons.confined(e);
    }

    private static boolean ablePartner(YukkuriEntity licker) {
        // A kin that is itself hurt, hungry or tired needs care before it can give any.
        return calm(licker) && licker.life().nutrition() >= 40 && licker.life().energy() >= 35
                && licker.body().wound() == 0 && licker.body().burns() == 0 && licker.body().infection() == 0
                && licker.life().spicyTicks() == 0
                && (licker.family().adult() || licker.family().stage() != FamilyLife.Stage.BABY && licker.childhood().canJump());
    }

    /** A visible wound, not a newborn (the birth staging already comforts a fallen newborn), nobody licking it yet. */
    private static boolean needsComfort(YukkuriEntity victim) {
        return victim.family().stage() != FamilyLife.Stage.BABY && victim.woundComfort().session == null
                && victim.woundComfort().relief == 0 && (victim.body().wound() > 0 || victim.body().burns() > 0) && calm(victim);
    }

    /** Mate (mutual), parent or child, who recognises the hurt one and does not dislike it. */
    private boolean kin(YukkuriEntity victim) {
        UUID me = entity.getUUID(), other = victim.getUUID();
        var mine = entity.family();
        var theirs = victim.family();
        boolean mates = other.equals(mine.partner()) && me.equals(theirs.partner());
        boolean parent = me.equals(theirs.mother()) || me.equals(theirs.father());
        boolean child = other.equals(mine.mother()) || other.equals(mine.father());
        return (mates || parent || child) && entity.life().canRecognize(victim)
                && YukkuriWorldData.get(level()).affinity(me, other) >= 0;
    }

    /** Autonomous noticing; scans a few times a second at most. */
    public boolean plan() {
        if (entity.level().isClientSide) return false;
        if (active()) return licking() && valid();
        if (scanWait > 0 || cooldown > 0) return false;
        scanWait = SCAN_TICKS + entity.getRandom().nextInt(20);
        if (!ablePartner(entity)) return false;
        var found = level().getEntitiesOfClass(YukkuriEntity.class, entity.getBoundingBox().inflate(NOTICE_RANGE),
                other -> other != entity && needsComfort(other) && kin(other) && entity.hasLineOfSight(other)).stream()
                .min(Comparator.comparingDouble(entity::distanceToSqr)).orElse(null);
        return found != null && begin(found);
    }

    private boolean begin(YukkuriEntity victim) {
        if (active() || victim.woundComfort().session != null) return false;
        session = new Session(entity, victim);
        victim.woundComfort().session = session;
        entity.social().stop(); victim.social().stop();
        victim.getNavigation().stop();
        entity.setPersistenceRequired();
        return true;
    }

    public boolean valid() {
        if (!active()) return false;
        var s = session;
        if (s.elapsed > APPROACH_LIMIT + CONTACT_TICKS + LOST_CONTACT) return false;
        var licker = s.licker;
        var victim = s.victim;
        if (licker.level() != victim.level() || licker.distanceToSqr(victim) > 144) return false;
        if (!licker.woundComfort().kin(victim) || !licker.hasLineOfSight(victim)) return false;
        // The lick only continues while the same wound is still there to worry about.
        return ablePartner(licker) && calm(victim) && (victim.body().wound() > 0 || victim.body().burns() > 0);
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

    /** Only mood: closeness need and stress. No health, wound, infection, bandage or hygiene change. */
    private void ease(YukkuriEntity victim) {
        victim.dailyLife().comfort(8);
        victim.pet().addStress(-1);
    }

    private void complete() {
        var s = session;
        var victim = s.victim;
        var licker = s.licker;
        victim.woundComfort().relief = RELIEF_COOLDOWN;
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
        if (session != null && !session.ended) end(session.contact > 0 ? KIN_COOLDOWN / 2 : FAILED_COOLDOWN);
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
