package io.github.longshou.mcyukkuri.social;

import io.github.longshou.mcyukkuri.MCYukkuri;
import io.github.longshou.mcyukkuri.entity.Behaviors;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.event.WildEvents;
import io.github.longshou.mcyukkuri.family.BirthStaging;
import io.github.longshou.mcyukkuri.family.FamilyFeedingContact;
import io.github.longshou.mcyukkuri.family.FamilyLife;
import io.github.longshou.mcyukkuri.life.SurfaceApproach;
import io.github.longshou.mcyukkuri.life.EcologyMovement;
import io.github.longshou.mcyukkuri.life.YukkuriConfig;
import io.github.longshou.mcyukkuri.subspecies.HatBoatEntity;
import io.github.longshou.mcyukkuri.life.YukkuriWorldData;
import io.github.longshou.mcyukkuri.settlement.SettlementSeasons;
import io.github.longshou.mcyukkuri.status.GameplayLog;
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
 * replaces orange juice or flour paste, and does not touch hygiene. The wiki N65 note ("not a general treatment")
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
    private static final int FAILED_COOLDOWN = 600;
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
        final UUID boat;
        int elapsed, contact, apart;
        boolean ended;
        Session(YukkuriEntity licker, YukkuriEntity victim) { this(licker, victim, null); }
        Session(YukkuriEntity licker, YukkuriEntity victim, UUID boat) { this.licker = licker; this.victim = victim; this.boat = boat; }
        boolean afloat() { return boat != null; }
    }

    private final YukkuriEntity entity;
    private Session session;
    private int cooldown, relief, scanWait;

    public WoundComfort(YukkuriEntity entity) { this.entity = entity; }

    public boolean active() { return session != null && !session.ended; }
    public boolean licking() { return active() && session.licker == entity; }
    public boolean receiving() { return active() && session.victim == entity; }
    /** The ordinary goals may only drive a ground session; hat care owns an afloat session. */
    public boolean groundLicking() { return licking() && !session.afloat(); }
    public boolean groundReceiving() { return receiving() && !session.afloat(); }
    /** True while a comforting lick is touching the wounded one. */
    public boolean inContact() { return active() && session.contact > 0; }
    /** Recently comforted: the cry gives way to a calmer face and the one is not licked again for a while. */
    public boolean soothed() { return relief > 0; }
    public int victimId() { return licking() ? session.victim.getId() : -1; }
    public float contactProgress() { return licking() ? Math.min(1f, session.contact / 20f) : 0f; }
    public String activity() { return !active() ? Behaviors.IDLE : licking() ? Behaviors.COMFORT_LICK : Behaviors.COMFORT_RECEIVE; }

    private ServerLevel level() { return (ServerLevel) entity.level(); }

    public void clock() {
        if (entity.level().isClientSide) return;
        if (cooldown > 0) cooldown--;
        if (relief > 0) relief--;
        if (scanWait > 0) scanWait--;
        if (active() && !(session.afloat() ? afloatValid(session) : valid())) stop();
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
        if (e.woundComfort().active() ? e.life().nutrition() < 15 || e.life().energy() < 15
                : e.life().hasUrgentNeed()) return "urgent_need";
        if (e.life().isSleeping()) return "sleeping";
        if (e.life().mealTicks() != 0) return "meal";
        if (!e.life().activity().equals(Behaviors.IDLE)) return "life_" + e.life().activity();
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
        if (WildEvents.reserved(e)) return "wild_event";
        if (SettlementSeasons.confined(e)) return "confined";
        return null;
    }


    /** A kin that is itself hurt, hungry or tired needs care before it can give any. */
    private static String unableReason(YukkuriEntity licker) {
        var busy = busyReason(licker, false);
        if (busy != null) return "licker_" + busy;
        if (licker.life().nutrition() < (licker.woundComfort().active() ? 15 : 40)) return "licker_hungry";
        if (licker.life().energy() < (licker.woundComfort().active() ? 15 : 35)) return "licker_tired";
        if (!licker.body().condition().readyToLick()) return "licker_hurt";
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
        if (!victim.body().condition().hasSkinOrBurnDamage()) return "no_wound";
        var busy = busyReason(victim, true);
        return busy == null ? null : "victim_" + busy;
    }

    private static boolean needsComfort(YukkuriEntity victim) { return victimReason(victim) == null; }

    /**
     * Mate (mutual), parent or child, who does not dislike it and actually recognises it: without its accessory a
     * kin is known only by an observer clever enough ({@link io.github.longshou.mcyukkuri.headwear.Recognition}); kin are no
     * exception (user correction 2026-10-18).
     */
    private String kinReason(YukkuriEntity victim) {
        UUID me = entity.getUUID(), other = victim.getUUID();
        if (!entity.life().canRecognize(victim)) return "unrecognized";
        if (!(Bonds.comfortFamily(entity, victim))) return "not_kin";
        if (YukkuriWorldData.get(level()).affinity(me, other) < BondLevels.AFFINITY_NEUTRAL) return "dislikes";
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

    /**
     * The same kin/condition policy as {@link #whyNot(YukkuriEntity)}, but for a real stable
     * parent-and-child berth in one floating hat.  It deliberately permits neither a general
     * passenger exception nor cross-hat contact.
     */
    public String whyNotAfloat(YukkuriEntity victim, HatBoatEntity boat) {
        if (victim == entity) return "self";
        if (boat == null || entity.getVehicle() != boat || victim.getVehicle() != boat
                || !boat.protects(entity) || !boat.protects(victim) || !EcologyMovement.captain(entity)
                || !boat.isAfloat() || boat.unsteadyMotion() || boat.towed() || boat.flooding() != 0) return "boat_unavailable";
        var unable = afloatUnable(entity);
        if (unable != null) return unable;
        var victimReason = afloatVictimReason(victim, false);
        if (victimReason != null) return victimReason;
        var kin = kinReason(victim);
        if (kin != null) return kin;
        if (!entity.hasLineOfSight(victim)) return "no_line_of_sight";
        if (cooldown > 0) return "cooldown_" + cooldown;
        return null;
    }

    /** Start a boat-only comfort session.  Seat movement and mouth contact remain the caller's live proof. */
    public boolean beginAfloat(YukkuriEntity victim, HatBoatEntity boat) {
        String why = whyNotAfloat(victim, boat);
        if (why != null) return false;
        float chance = entity.life().personality().helpProbability(true, victim.body().healthRatio() < .35f);
        float roll = entity.getRandom().nextFloat();
        if (roll >= chance) {
            cooldown = KIN_COOLDOWN;
            entity.life().personality().decided("comfort_declined");
            GameplayLog.event(entity,"comfort","afloat help declined; victim={} boat={} roll={} chance={}",
                    victim.getUUID(),boat.getUUID(),roll,chance);
            return false;
        }
        return open(new Session(entity, victim, boat.getUUID()));
    }

    /** Drive only the temporary care berth already owned by the hat; never navigates a passenger over a deck. */
    public boolean tickAfloat(HatBoatEntity boat) {
        if (!licking() || session == null || !session.afloat() || !session.boat.equals(boat.getUUID()) || !afloatValid(session)) {
            stop(); return false;
        }
        var s = session;
        var victim = s.victim;
        s.elapsed++;
        entity.getLookControl().setLookAt(victim, 30, 30);
        victim.getLookControl().setLookAt(entity, 30, 30);
        if (!boat.prepareCareSeat(victim, entity) || !boat.careSeatReady(victim)) {
            if (s.elapsed > APPROACH_LIMIT) failed();
            return active();
        }
        FamilyFeedingContact.faceEachOther(entity, victim);
        if (!FamilyFeedingContact.mouthsMeet(entity, victim)) {
            // Match the ground session: only uninterrupted mouth contact heals.  A berth
            // can stay assigned while the hull rocks, but a gap after contact may never be
            // accumulated into a remote completed lick.
            if (s.contact == 0) {
                if (s.elapsed > APPROACH_LIMIT) failed();
            } else if (++s.apart > LOST_CONTACT) failed();
            return active();
        }
        s.apart = 0;
        if (s.contact == 0) GameplayLog.event(entity,"comfort","afloat mouth contact started; victim={} boat={} elapsed={}",
                victim.getUUID(),boat.getUUID(),s.elapsed);
        s.contact++;
        if (s.contact % 30 == 1) BirthStaging.tears(entity);
        if (s.contact % EASE_INTERVAL == 0) ease(victim);
        if (s.contact >= CONTACT_TICKS) complete();
        return active();
    }

    private static String afloatBusyReason(YukkuriEntity e, boolean hurtOne) {
        if (!e.isAlive() || e.isRemoved()) return "dead";
        if (e.isNoAi()) return "noai";
        if (e.isPanicking()) return "panic";
        if (e.hurtTime != 0 && !hurtOne) return "hurt_flash";
        if (e.isCommandPlaying()) return "command_action";
        if (e.isOnFire()) return "fire";
        if (e.operations().active()) return "operation";
        if (e.predator().active()) return "predator";
        if (e.social().holdingStill()) return "social_hold";
        if (e.woundComfort().active() ? e.life().nutrition() < 15 || e.life().energy() < 15 : e.life().hasUrgentNeed()) return "urgent_need";
        if (e.life().isSleeping()) return "sleeping";
        if (e.life().mealTicks() != 0) return "meal";
        if (!e.life().activity().equals(Behaviors.IDLE)) return "life_" + e.life().activity();
        if (e.family().active() && !e.family().yieldsToComfort()) return "family_" + e.family().activity();
        if (e.family().awaitingLanding()) return "awaiting_landing";
        if (e.childhood().active()) return "childhood";
        if (e.careApproach().waiting()) return "care_approach";
        if (e.house().active() || e.habitat().active() || e.independence().active()) return "home";
        if (e.settlement().active() || e.settlement().duties().active()) return "settlement";
        if (e.groupInteraction().active() || e.playLife().active() || e.pet().busy() || e.dailyLife().active()) return "activity";
        if (WildEvents.reserved(e) || SettlementSeasons.confined(e)) return "reserved";
        return null;
    }
    private static String afloatUnable(YukkuriEntity licker) {
        var busy = afloatBusyReason(licker, false);
        if (busy != null) return "licker_" + busy;
        if (licker.life().nutrition() < (licker.woundComfort().active() ? 15 : 40)) return "licker_hungry";
        if (licker.life().energy() < (licker.woundComfort().active() ? 15 : 35)) return "licker_tired";
        if (!licker.body().condition().readyToLick()) return "licker_hurt";
        if (licker.life().spicyTicks() != 0) return "licker_spicy";
        return !(licker.family().adult() || licker.family().stage() != FamilyLife.Stage.BABY && licker.childhood().canJump()) ? "licker_too_young" : null;
    }
    private static String afloatVictimReason(YukkuriEntity victim, boolean sessionOwner) {
        if (!sessionOwner && victim.woundComfort().session != null) return "being_licked";
        if (!sessionOwner && victim.woundComfort().relief != 0) return "already_soothed";
        if (!victim.body().condition().hasSkinOrBurnDamage()) return "no_wound";
        var busy = afloatBusyReason(victim, true);
        return busy == null ? null : "victim_" + busy;
    }
    private boolean afloatValid(Session s) {
        if (!s.afloat() || !(s.licker.getVehicle() instanceof HatBoatEntity boat) || !s.boat.equals(boat.getUUID())
                || s.victim.getVehicle() != boat || !boat.protects(s.licker) || !boat.protects(s.victim)
                || !EcologyMovement.captain(s.licker) || !boat.isAfloat() || boat.unsteadyMotion() || boat.towed() || boat.flooding() != 0
                || s.elapsed > APPROACH_LIMIT + CONTACT_TICKS + LOST_CONTACT || s.licker.distanceToSqr(s.victim) > 144
                || !s.licker.hasLineOfSight(s.victim) || s.victim.life().personality().fear(s.licker.getUUID()) >= 60) return false;
        return afloatUnable(s.licker) == null && afloatVictimReason(s.victim, true) == null;
    }
    public String describe() {
        var candidates = entity.level().getEntitiesOfClass(YukkuriEntity.class, entity.getBoundingBox().inflate(NOTICE_RANGE),
                        other -> other != entity && Bonds.comfortFamily(entity, other))
                .stream().limit(6).map(other -> "#" + other.getId() + ":" + whyNot(other)).toList();
        return "active=" + active() + " licking=" + licking() + " scan=" + scanWait
                + " cooldown=" + cooldown + " relief=" + relief + " kin=" + String.join(",", candidates);
    }

    /** Autonomous noticing; scans a few times a second at most. */
    public boolean plan() {
        if (entity.level().isClientSide) return false;
        if (active()) return groundLicking() && valid();
        if (scanWait > 0 || cooldown > 0) return false;
        scanWait = SCAN_TICKS + entity.getRandom().nextInt(20);
        var near = level().getEntitiesOfClass(YukkuriEntity.class, entity.getBoundingBox().inflate(NOTICE_RANGE),
                other -> other != entity && (other.body().condition().hasSkinOrBurnDamage()));
        YukkuriEntity found = null;
        double nearest = Double.MAX_VALUE;
        for (var other : near) {
            String why = whyNot(other);
            if (DEBUG) MCYukkuri.LOGGER.info("[comfort-debug] {} -> {}: {}", entity.getId(), other.getId(), why);
            double distance = entity.distanceToSqr(other);
            // Keep the encounter-order winner for an exact-distance tie, as the stable sort did.
            if (why == null && distance < nearest) { found = other; nearest = distance; }
        }
        if (found == null) return false;
        float roll=entity.getRandom().nextFloat();
        double chance=entity.life().personality().helpProbability(true,found.body().healthRatio()<.35f);
        if (roll >= chance) {
            cooldown = KIN_COOLDOWN;
            entity.life().personality().decided("comfort_declined");
            GameplayLog.event(entity,"comfort","help declined; victim={} roll={} chance={}",found.getUUID(),roll,chance);
            return false;
        }
        return begin(found);
    }

    static final boolean DEBUG = false;

    private boolean begin(YukkuriEntity victim) {
        if (active() || victim.woundComfort().session != null || victim.life().personality().fear(entity.getUUID()) >= 60) {
            GameplayLog.limited(entity,"comfort","begin_refused",200,"start refused; victim={} self_active={} victim_active={} victim_fear={}",
                    victim.getUUID(),active(),victim.woundComfort().session!=null,victim.life().personality().fear(entity.getUUID()));return false;
        }
        return open(new Session(entity, victim));
    }
    private boolean open(Session next) {
        var victim = next.victim;
        if (active() || victim.woundComfort().session != null || victim.life().personality().fear(entity.getUUID()) >= 60) return false;
        session = next;
        entity.life().personality().protectRelation(victim.getUUID());
        victim.life().personality().protectRelation(entity.getUUID());
        victim.woundComfort().session = session;
        entity.social().stop(); victim.social().stop();
        // Everyday errands (waiting for the child, following the parent) are put down for the comforting.
        if (entity.family().yieldsToComfort()) entity.family().stop();
        if (victim.family().yieldsToComfort()) victim.family().stop();
        victim.getNavigation().stop();
        entity.setPersistenceRequired();
        GameplayLog.event(entity,"comfort","approach started; victim=#{} uuid={} wounds={} health={} boat={}",
                victim.getId(),victim.getUUID(),victim.body().wounds().size(),victim.getHealth(),next.boat);
        return true;
    }

    public boolean valid() {
        if (active() && session.afloat()) return afloatValid(session);
        var why = invalidReason();
        if (why != null && DEBUG) MCYukkuri.LOGGER.info("[comfort-debug] session {} end: {}", entity.getId(), why);
        return why == null;
    }

    private String invalidReason() {
        if (!active()) return "inactive";
        var s = session;
        if (s.elapsed > APPROACH_LIMIT + CONTACT_TICKS + LOST_CONTACT) return "timeout";
        var licker = s.licker;
        var victim = s.victim;
        if (licker.level() != victim.level() || licker.distanceToSqr(victim) > 144) return "far";
        if (victim.life().personality().fear(licker.getUUID()) >= 60) return "fear";
        if (!licker.hasLineOfSight(victim)) return "no_line_of_sight";
        // The lick only continues while the same wound is still there to worry about.
        var able = unableReason(licker);
        if (able != null) return able;
        var busy = busyReason(victim, true);
        if (busy != null) return "victim_" + busy;
        if (!victim.body().condition().hasSkinOrBurnDamage()) return "healed";
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
        if(s.contact==0)GameplayLog.event(entity,"comfort","mouth contact started; victim={} elapsed={}",victim.getUUID(),s.elapsed);
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
        if (YukkuriConfig.LICK_HEALS.get() && victim.body().canLickMend()) victim.heal(.25f);
    }

    private void complete() {
        var s = session;
        var victim = s.victim;
        var licker = s.licker;
        float beforeHealth=victim.getHealth(),beforeNutrition=licker.life().nutrition();
        boolean mended = YukkuriConfig.LICK_HEALS.get() && victim.body().canLickMend() && victim.body().lickMend();
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
        victim.life().personality().relationshipChanged(licker.getUUID(), 4, -3);
        GameplayLog.event(licker,"comfort","completed; victim={} mended={} final_heal_health={} -> {} nutrition={} -> {} contact={} elapsed={} relief={}",
                victim.getUUID(),mended,beforeHealth,victim.getHealth(),beforeNutrition,licker.life().nutrition(),s.contact,s.elapsed,victim.woundComfort().relief);
        end(KIN_COOLDOWN);
    }

    private void failed() {
        if(active())GameplayLog.event(session.licker,"comfort","failed; victim={} reason={} elapsed={} contact={} apart={}",
                session.victim.getUUID(),session.contact==0?"approach_timeout":"contact_lost",session.elapsed,session.contact,session.apart);
        end(FAILED_COOLDOWN);
    }

    public void stop() {
        if (DEBUG && session != null && !session.ended) {
            var trace = new Throwable().getStackTrace();
            MCYukkuri.LOGGER.info("[comfort-debug] stop entity {} contact {} elapsed {} by {} <- {} <- {}", entity.getId(), session.contact, session.elapsed,
                    trace.length > 1 ? trace[1] : "?", trace.length > 2 ? trace[2] : "?", trace.length > 3 ? trace[3] : "?");
        }
        if (session != null && !session.ended) {
            GameplayLog.event(session.licker,"comfort","interrupted; victim={} condition={} elapsed={} contact={}",
                    session.victim.getUUID(),invalidReason(),session.elapsed,session.contact);
            end(INTERRUPTED_COOLDOWN);
        }
        session = null;
    }

    private void end(int kinCooldown) {
        var s = session;
        if (s == null || s.ended) return;
        s.ended = true;
        s.licker.life().personality().releaseRelation(s.victim.getUUID());
        s.victim.life().personality().releaseRelation(s.licker.getUUID());
        s.licker.woundComfort().session = null;
        s.victim.woundComfort().session = null;
        s.licker.woundComfort().cooldown = Math.max(s.licker.woundComfort().cooldown, kinCooldown);
        s.licker.getNavigation().stop(); s.victim.getNavigation().stop();
        if (s.afloat()) EcologyMovement.releaseContact(s.licker, s.victim);
    }

    public CompoundTag save() {
        var tag = new CompoundTag();
        tag.putInt("Cooldown", cooldown); tag.putInt("Relief", relief);
        return tag;
    }

    public void load(CompoundTag tag) {
        stop();
        cooldown = Math.clamp(tag.getInt("Cooldown"), 0, KIN_COOLDOWN);
        relief = Math.clamp(tag.getInt("Relief"), 0, RELIEF_COOLDOWN);
    }
}
