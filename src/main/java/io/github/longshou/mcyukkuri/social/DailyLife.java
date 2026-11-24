package io.github.longshou.mcyukkuri.social;

import io.github.longshou.mcyukkuri.pet.PetScenes;
import io.github.longshou.mcyukkuri.pet.rules.HouseRule;
import io.github.longshou.mcyukkuri.pet.rules.RulesMath.Choice;

import io.github.longshou.mcyukkuri.social.Temperament.Gate;
import io.github.longshou.mcyukkuri.social.PairContact.Pose;

import io.github.longshou.mcyukkuri.status.GameplayLog;
import io.github.longshou.mcyukkuri.entity.Behaviors;
import io.github.longshou.mcyukkuri.life.TravelRange;
import io.github.longshou.mcyukkuri.life.YukkuriWorldData;
import io.github.longshou.mcyukkuri.entity.YukkuriAction;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.event.WildEvents;
import io.github.longshou.mcyukkuri.life.SurfaceApproach;
import io.github.longshou.mcyukkuri.life.BehaviorContinuity;
import io.github.longshou.mcyukkuri.life.EcologyMovement;
import io.github.longshou.mcyukkuri.mind.EmotionEvent;
import io.github.longshou.mcyukkuri.mind.MindLife;
import io.github.longshou.mcyukkuri.motor.BodyAbility;
import io.github.longshou.mcyukkuri.motor.ClipMarkers;
import io.github.longshou.mcyukkuri.motor.GreetingStance;
import io.github.longshou.mcyukkuri.motor.Motor;
import io.github.longshou.mcyukkuri.motor.MotorOwner;
import io.github.longshou.mcyukkuri.motor.SideContact;
import io.github.longshou.mcyukkuri.motor.action.Face;
import io.github.longshou.mcyukkuri.motor.action.Hop;
import io.github.longshou.mcyukkuri.motor.geom.ContactStandSearch;
import io.github.longshou.mcyukkuri.perception.Senses;
import io.github.longshou.mcyukkuri.settlement.SettlementSeasons;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/** Loaded-time emotional needs and interruptible leisure. A pair shares one contact clock. */
public final class DailyLife {
    public enum Kind { NUZZLE, SING, EXERCISE }
    private static final class Session {
        final YukkuriEntity leader;
        final Entity companion;
        final Kind kind;
        int elapsed, contact, greetHold, approachBudget;
        boolean ended, greeted;
        String greeting;
        UUID songRuleOwner;
        PeerRub rub;
        PairContact pair;
        Session(YukkuriEntity leader, Entity companion, Kind kind, boolean welcome) {
            this.leader = leader; this.companion = companion; this.kind = kind;
            if (kind == Kind.NUZZLE)
                approachBudget = approachBudget(leader, leader.distanceTo(companion), nuzzleReach(leader, companion));
            if (kind == Kind.NUZZLE && companion instanceof YukkuriEntity peer)
                greeting = welcome ? "welcome" : leader.social().greetingDue(peer);
        }
        int contactTicks() { return pair == null ? contact : pair.ticks(); }
        boolean hasTime() {
            if (kind != Kind.NUZZLE) return elapsed < 320;
            var phase = pair != null || contact > 0 ? ContactPhase.NUZZLE
                    : greetHold > 0 ? ContactPhase.GREETING : ContactPhase.APPROACH;
            return phase.canTick(elapsed, approachBudget);
        }
    }
    private record PeerRub(PeerNuzzle mode, YukkuriEntity mover, YukkuriEntity anchor, Vec3 anchorAt,
                           SurfaceApproach.ContactRoute route) {
        private PeerRub(PeerNuzzle mode, YukkuriEntity mover, YukkuriEntity anchor, SurfaceApproach.ContactRoute route) {
            this(mode, mover, anchor, anchor.position(), route);
        }
        private double effort() {
            return PeerNuzzle.effort(route.length(), BodyAbility.of(mover).walkSpeed(), mover.dailyLife().affection);
        }
    }
    private final YukkuriEntity entity;
    private Session session;
    /** The session's own turn request: the greeting beats turn both bodies to face each other, before the side press. */
    private final MotorOwner body = new MotorOwner() {
        @Override public String ownerId() { return "daily_nuzzle"; }
        @Override public boolean active() { return DailyLife.this.active(); }
    };
    /** A lonely individual walks to a trusted friend it can see this far off and asks for a nuzzle. */
    private static final int NUZZLE_SIGHT = 16;
    /** A nuzzle broken off before any contact (the one who asked was called away by its own need) is asked for again soon. */
    private static final int INTERRUPTED_COOLDOWN = 200;
    private static final double LEAN = 1.1;
    private static final double PLAYER_LEAN_STEP = .35;
    private static final double NUZZLE_WALK_SPEED = .8;
    // As with owner greetings, allow turning and route settling on top of the body's predicted walk.
    private static final int APPROACH_SLACK = 200;
    private int affection = 20, boredom = 20, clock, cooldown, scanWait;
    private int nuzzles, songs, exercises;
    private final Map<UUID, Integer> invitations = new HashMap<>();
    /** A homecoming is noticed this long after the member came in (30 s, project design): time for it to set its load down. */
    private static final int WELCOME_WINDOW = 600;
    /** Homecomings (member, its arrival tick) this one already welcomed or let pass; transient. */
    private final Map<UUID, Long> welcomed = new HashMap<>();

    public DailyLife(YukkuriEntity entity) { this.entity = entity; }
    public int affectionNeed() { return affection; }
    public int boredom() { return boredom; }
    public int nuzzles() { return nuzzles; }
    public int songs() { return songs; }
    public int exercises() { return exercises; }
    public boolean active() { return session != null && !session.ended; }
    public boolean inContact() { return observedContactTicks() > 0; }
    /** Actual shared contact clock; observation does not evaluate session validity. */
    public int observedContactTicks() { return active() ? session.contactTicks() : 0; }
    /** Remaining time of the greeting and reply from the shared greeting stance. */
    public int observedGreetingWait() { return active() ? session.greetHold : 0; }
    private static boolean aligned(YukkuriEntity body, float yaw, double tolerance) {
        return Math.abs(Mth.wrapDegrees(yaw - body.yBodyRot)) <= tolerance;
    }
    public String detail() {
        return !active() ? "idle" : "kind=" + session.kind
                + " contact=" + session.contactTicks() + " elapsed=" + session.elapsed;
    }
    public int nuzzlePartnerId() {
        if (!active() || session.kind != Kind.NUZZLE) return -1;
        Entity peer = session.leader == entity ? session.companion : session.leader;
        return peer == null ? -1 : peer.getId();
    }
    public String activity() {
        if (!active()) return Behaviors.IDLE;
        return switch (session.kind) {
            case NUZZLE -> session.contact > 0 ? Behaviors.NUZZLE : Behaviors.SEEK_AFFECTION;
            case SING -> session.leader == entity ? Behaviors.SING : Behaviors.LISTEN_SONG;
            case EXERCISE -> entity.family().adult() ? Behaviors.EXERCISE_WATCH : Behaviors.UNUN_EXERCISE;
        };
    }
    private ServerLevel level() { return (ServerLevel) entity.level(); }
    public void comfort(int amount) { affection = Math.max(0, affection - Math.max(0, amount)); }
    public void entertained(int amount) { boredom = Math.max(0, boredom - Math.max(0, amount)); }
    public void clock() {
        if (entity.level().isClientSide || entity.inert() || !entity.isAlive()) return;
        if (cooldown > 0) cooldown--;
        if (scanWait > 0) scanWait--;
        invitations.replaceAll((id, ticks) -> ticks - 1);
        invitations.values().removeIf(ticks -> ticks <= 0);
        if (++clock >= 200) {
            clock = 0;
            if (!entity.life().isSleeping()) {
                affection = Math.min(100, affection + (entity.life().personality().temperament().is(Gate.CLINGY) ? 2 : 1)
                        + (entity.getRandom().nextFloat() < entity.species().traits().affection() - 1 ? 1 : 0));
                boredom = Math.min(100, boredom + 1);
            }
            if (entity.body().weak() && entity.life().activity().equals(Behaviors.IDLE) && !entity.isCommandPlaying())
                entity.mind().perform("droop", 80);
        }
        if (active() && !valid()) stop();
    }
    private static boolean available(YukkuriEntity e) { return available(e, false, false); }
    private static boolean available(YukkuriEntity e, boolean continuing, boolean exercise) {
        return !WildEvents.reserved(e) && e.isAlive() && !e.isRemoved() && !e.inert()
                && (!e.mind().feeling().prefersPartingGaze() || exercise && e.life().constipated()
                    || continuing && e.dailyLife().inContact() && e.dailyLife().nuzzlePartnerId() >= 0)
                // A session under way survives the step down a bank it is walking to (a friend in a hollow); a new one starts on the ground.
                && (continuing || e.onGround()) && !e.isPassenger()
                && !e.isPanicking() && !e.isCommandPlaying() && !e.operations().active() && !e.predator().active() && !e.pet().lessons().directed()
                && !(continuing ? e.life().nutrition() < 15 || e.life().energy() < 15
                    || e.isOnFire() || e.isInWaterOrBubble() : e.life().hasUrgentNeed())
                && e.life().activity().equals(Behaviors.IDLE)
                && e.life().nutrition() >= (continuing ? 15 : 40) && e.life().energy() >= (continuing ? 15 : 35)
                && !e.childhood().needsNewbornCare() && e.body().condition().readyForAffection() && e.life().spicyTicks() == 0
                && wellAndFree(e, continuing)
                && !e.groupInteraction().active() && !e.playLife().active() && !e.pet().busy()
                && !e.family().active() && !e.house().active() && !e.habitat().active()
                && !e.independence().active() && !e.forageTrip().outing() && !e.settlement().active() && !e.settlement().duties().active()
                && !SettlementSeasons.confined(e);
    }
    /**
     * The one "well and free" gate for leisure: a weak body rests, and a calm mind with nothing pressing
     * (an own newborn awaiting its first meal or toilet within 12, a swaddle needing care, a comfort walk under way, a heat with its partner in sight)
     * is what songs and exercise need. The scans run only when a session is being admitted, never while one continues.
     */
    public static boolean wellAndFree(YukkuriEntity e, boolean continuing) {
        if (e.body().weak() || e.mind().unease() >= MindLife.UNEASY || e.leafSwaddle().needsCare() || e.woundComfort().active()) return false;
        if (continuing || !e.family().adult()) return true;
        if (e.family().inBreedingPeriod() && e.family().partner() != null
                && e.level() instanceof ServerLevel level && level.getEntity(e.family().partner()) instanceof YukkuriEntity mate
                && mate.isAlive() && mate.family().inBreedingPeriod() && e.distanceToSqr(mate) <= 256 && e.hasLineOfSight(mate)) return false;
        return e.level().getEntitiesOfClass(YukkuriEntity.class, e.getBoundingBox().inflate(12),
                child -> child.childhood().needsNewbornCare() && e.family().caresFor(child)).isEmpty();
    }
    private boolean closePeer(YukkuriEntity peer) {
        return unafraid(peer) && entity.life().personality().trust(peer.getUUID()) > 0
                && peer.life().personality().trust(entity.getUUID()) > 0;
    }
    private boolean unafraid(YukkuriEntity peer) {
        return entity.life().canRecognize(peer) && peer.life().canRecognize(entity)
                && entity.life().personality().fear(peer.getUUID()) < 60 && peer.life().personality().fear(entity.getUUID()) < 60;
    }
    private boolean trusted(Player player) {
        return player.isAlive() && !player.isSpectator() && player.level() == entity.level()
                && Bonds.trustedPlayer(entity, player.getUUID());
    }
    public boolean nuzzle(Entity companion) {
        if (companion == entity || companion.level() != entity.level() || !companion.isAlive()) return false;
        if (companion instanceof ItemEntity doll) {
            if (entity.distanceToSqr(doll) > 100 || doll.isInWater()
                    || !Keepsakes.fond(entity, doll.getItem())
                    || !entity.life().dryStand(doll.blockPosition())
                    || !Senses.sees(entity, doll)
                    || !entity.keepsake().comfortEligible(false)) return false;
            return begin(Kind.NUZZLE, doll);
        }
        if (entity.distanceToSqr(companion) > NUZZLE_SIGHT * NUZZLE_SIGHT || !entity.hasLineOfSight(companion)) return false;
        if (companion instanceof YukkuriEntity peer) {
            if (!available(peer) || peer.dailyLife().active() || peer.dailyLife().cooldown > 0 || !closePeer(peer)) return false;
        } else if (!(companion instanceof Player player) || !trusted(player)) return false;
        return begin(Kind.NUZZLE, companion);
    }
    /** A social arrival uses the same admission and session as every other nuzzle invitation. */
    public boolean nuzzleOnArrival(YukkuriEntity peer) {
        return (affection >= entity.life().personality().affectionThreshold()
                || peer.dailyLife().affection >= peer.life().personality().affectionThreshold())
                && nuzzle(peer);
    }
    /** A care-menu invitation respects the same needs, trust, space and cooldown as autonomous leisure. */
    public boolean canInvite(Player player, Kind kind) {
        return trusted(player) && entity.hasLineOfSight(player) && canBegin(kind, player);
    }
    public boolean invite(Player player, Kind kind) {
        if (kind == Kind.SING && !entity.pet().owned(player)) return begin(kind, player);
        start(kind, player, false);
        return true;
    }
    public boolean begin(Kind kind) { return kind != Kind.NUZZLE && begin(kind, null); }
    private boolean begin(Kind kind, Entity companion) { return begin(kind, companion, false); }
    private static boolean permitsPerformance(YukkuriEntity leader, Entity companion, Kind kind) {
        boolean optional = kind == Kind.SING || kind == Kind.EXERCISE && !leader.life().constipated();
        return leader.outing().permitsPerformance(optional)
                && (!(companion instanceof YukkuriEntity peer) || peer.outing().permitsPerformance(optional));
    }

    private boolean canBegin(Kind kind, Entity companion) {
        if (!permitsPerformance(entity, companion, kind)) return false;
        if (entity.level().isClientSide || !available(entity, false, kind == Kind.EXERCISE) || active() || cooldown > 0) return false;
        if (kind == Kind.EXERCISE && (!entity.childhood().canExercise()
                || companion instanceof YukkuriEntity peer && !peer.childhood().canExercise())) return false;
        return kind == Kind.NUZZLE || room() && TravelRange.nearHomeForLeisure(entity);
    }
    private boolean begin(Kind kind, Entity companion, boolean welcome) {
        if (!canBegin(kind, companion)) return false;
        Choice song = Choice.ALLOW;
        if (kind == Kind.SING) {
            song = entity.pet().conscience().tempted(HouseRule.NO_SINGING, boredom / 100.0, 1);
            if (song == Choice.HOLD || song == Choice.WAIT) {
                cooldown = 600;
                if (song == Choice.HOLD) {
                    boredom = Math.min(100, boredom + 5);
                    PetScenes.act(entity, YukkuriAction.LOOK_AROUND, "open_smile", "", 12);
                    PetScenes.later(entity, 12, () -> PetScenes.act(entity, YukkuriAction.LOOK_AROUND, "pursed", "", 28, entity.pet().presentOwner()));
                }
                return false;
            }
        }
        start(kind, companion, welcome);
        if (song == Choice.BREAK) session.songRuleOwner = entity.pet().owner();
        return true;
    }
    private void start(Kind kind, Entity companion, boolean welcome) {
        entity.social().stop();
        session = new Session(entity, companion, kind, welcome); cooldown = 600;
        if (companion != null && !(companion instanceof ItemEntity)) entity.life().personality().protectRelation(companion.getUUID());
        if (companion instanceof YukkuriEntity peer) {
            peer.social().stop(); peer.life().personality().protectRelation(entity.getUUID()); peer.dailyLife().session = session;
            // The one welcomed home stays free to be welcomed by the rest of its family in turn.
            if (!welcome) peer.dailyLife().cooldown = 600;
        }
        entity.setPersistenceRequired();
        if (kind == Kind.NUZZLE && !welcome) entity.speak("want_nuzzle");
        if (kind == Kind.NUZZLE && companion instanceof ItemEntity) {
            entity.showReaction("teary_smile", "", 30);
            entity.keepsake().emitExternalBeat("ask", "teary_smile", "want_nuzzle", "ground", false, "");
        }
        if (kind == Kind.NUZZLE) entity.life().personality().decided("seek_companion");
        GameplayLog.limited(entity,"daily","started."+kind,1200,
                "session reserved kind={} companion={} distance={} affectionNeed={} boredom={} homeDistance={}", () -> new Object[]{
                    kind,companion==null?null:companion.getUUID(),companion==null?0:Math.round(entity.distanceTo(companion)*10)/10.0,affection,boredom,
                    entity.life().homeStand()==null?"none":Math.round(Math.sqrt(entity.distanceToSqr(Vec3.atBottomCenterOf(entity.life().homeStand())))*10)/10.0});
    }
    private boolean room() {
        var pos = entity.blockPosition();
        for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++)
            if (!entity.life().dryStand(pos.offset(x, 0, z))) return false;
        return true;
    }

    private String companionRefusal(YukkuriEntity peer) {
        if (!available(peer)) return peer.onGround() ? "unavailable" : "airborne";
        if (peer.dailyLife().active()) return "in_session";
        if (peer.dailyLife().cooldown > 0) return "cooldown";
        if (invitations.containsKey(peer.getUUID())) return "recent_invitation";
        if (!entity.hasLineOfSight(peer)) return "out_of_sight";
        if (!closePeer(peer)) return "trust_or_recognition";
        if (entity.distanceToSqr(peer) > 25 && !entity.life().reachable(peer.blockPosition(), false)) return "unreachable";
        return null;
    }

    public boolean plan() {
        if (active()) return valid();
        if (cooldown > 0 || entity.social().active()) return false;
        if (entity.tickCount % 10 == 0 && welcomeHome() == Welcome.STARTED) return true;
        if (scanWait > 0 || !available(entity, false, entity.childhood().canExercise())) return false;
        // Someone of its own just came in and is still busy (setting its load down, eating): it keeps an eye on them for the
        // welcome rather than starting a song or a game of its own.
        if (welcomeHome() == Welcome.WATCHING) { scanWait = 10; return false; }
        scanWait = 100;
        boolean dollComfort = Keepsakes.wantsComfort(entity);
        if (boredom < 45 && affection < entity.life().personality().affectionThreshold()
                && !dollComfort && !entity.life().constipated()) return false;
        scanWait = 600;
        boolean lonely = affection >= entity.life().personality().affectionThreshold();
        var checked = new ArrayList<String>();
        var peers = new ArrayList<YukkuriEntity>();
        for (var peer : level().getEntitiesOfClass(YukkuriEntity.class, entity.getBoundingBox().inflate(lonely || dollComfort ? NUZZLE_SIGHT : 5),
                peer -> peer != entity)) {
            var refusal = companionRefusal(peer);
            if (refusal == null) peers.add(peer);
            if (checked.size() < 8) checked.add(peer.getUUID() + ":" + (refusal == null ? "eligible" : refusal));
        }
        peers.sort(Comparator.comparingInt((YukkuriEntity peer) -> entity.life().personality().trust(peer.getUUID())).reversed()
                .thenComparingDouble(entity::distanceToSqr));
        // Loneliness decides the asking; the friend's own roll below still decides the answer.
        boolean together = !peers.isEmpty() && (lonely || dollComfort || entity.getRandom().nextFloat() < entity.life().personality().companionshipProbability());
        YukkuriEntity candidate = together ? peers.getFirst() : null;
        YukkuriEntity companion = null;
        Float roll = null, chance = null;
        if (together) {
            invitations.put(candidate.getUUID(), 600);
            candidate.dailyLife().invitations.put(entity.getUUID(), 600);
            roll = candidate.getRandom().nextFloat();
            chance = candidate.life().personality().companionshipProbability();
            if (roll < chance) companion = candidate;
        }
        boolean exercise = entity.life().constipated() && entity.childhood().canExercise();
        boolean nuzzling = !exercise && companion != null && (lonely || dollComfort) && nuzzle(companion);
        GameplayLog.limited(entity, "daily", "companion_choice", 200,
                "companion choice affectionNeed={} checked={} eligible={} asked={} willingnessRoll={} willingnessChance={} accepted={} nuzzleReserved={}",
                affection, checked, peers.size(), together ? candidate.getUUID() : null, roll, chance, companion != null, nuzzling);
        if (exercise) {
            if (companion != null && companion.family().adult()
                    && companion.getRandom().nextFloat() >= companion.life().personality().helpProbability(true, false)) companion = null;
            return begin(Kind.EXERCISE, inEarshot(companion));
        }
        if (nuzzling) return true;
        // Only when no eligible peer was found does a lonely or timid body turn to a doll for comfort.
        if (peers.isEmpty() && dollComfort && entity.keepsake().comfortEligible(false)) {
            ItemEntity doll = Keepsakes.nearestVisibleDoll(level(), entity, 10, false);
            if (doll != null && nuzzle(doll)) return true;
        }
        // Grief, fright or a fit of anger leave no heart for a song or a workout; seeking a nuzzle above still goes on.
        if (entity.mind().feeling().subdued()) return false;
        float song = 2 * entity.species().traits().song();
        Kind kind = !entity.childhood().canExercise() || entity.getRandom().nextFloat() * (song + 1) >= 1 ? Kind.SING : Kind.EXERCISE;
        return begin(kind, inEarshot(companion));
    }
    /**
     * A member of its own family (mate, parent or child) that just came home after a real absence, seen from inside the same home:
     * it gets up, goes to greet it and nuzzles it (project design, user 2026-11-07 life presentation). A dependent young always goes
     * to its returning parent; others as their attachment moves them. Each homecoming is answered once; a busy arrival is asked
     * again while the window lasts, and meanwhile it is WATCHING.
     */
    private Welcome welcomeHome() {
        var family = entity.family();
        long now = level().getGameTime();
        if (!entity.life().activity().equals(Behaviors.IDLE) || !family.atHome() || now - family.cameHomeAt() <= WELCOME_WINDOW) return Welcome.NONE;
        welcomed.values().removeIf(at -> now - at > WELCOME_WINDOW);
        var arrivals = level().getEntitiesOfClass(YukkuriEntity.class, entity.getBoundingBox().inflate(NUZZLE_SIGHT), peer -> peer != entity
                && now - peer.family().cameHomeAt() <= WELCOME_WINDOW && !welcomed.containsKey(peer.getUUID())
                && Bonds.comfortFamily(entity, peer) && peer.family().atHome() && family.sharesHome(peer));
        if (arrivals.isEmpty() || !available(entity)) return Welcome.NONE;
        arrivals.sort(Comparator.comparingDouble(entity::distanceToSqr));
        var result = Welcome.NONE;
        for (var peer : arrivals) {
            if (!entity.hasLineOfSight(peer) || !unafraid(peer)) continue;
            if (!available(peer) || peer.dailyLife().active()) { result = Welcome.WATCHING; continue; }
            welcomed.put(peer.getUUID(), peer.family().cameHomeAt());
            boolean young = !family.adult() && Bonds.parentOf(peer, entity);
            float chance = young ? 1 : entity.life().personality().companionshipProbability();
            if (!young && entity.getRandom().nextFloat() >= chance) {
                GameplayLog.event(entity, "family", "welcome home declined member={} chance={} attachment={}", peer.getUUID(), chance,
                        entity.life().personality().attachment());
                continue;
            }
            if (begin(Kind.NUZZLE, peer, true)) {
                GameplayLog.event(entity, "family", "welcome home started member={} cameHomeAgo={} distance={} young={}", peer.getUUID(),
                        now - peer.family().cameHomeAt(), Math.round(entity.distanceTo(peer) * 10) / 10.0, young);
                return Welcome.STARTED;
            }
        }
        return result;
    }
    private enum Welcome { NONE, WATCHING, STARTED }
    /** Songs and exercise are shared only with a companion close by; a far friend is for the nuzzle walk. */
    private YukkuriEntity inEarshot(YukkuriEntity companion) { return companion != null && entity.distanceToSqr(companion) <= 25 ? companion : null; }

    private static double nuzzleReach(YukkuriEntity actor, Entity companion) {
        double gap = companion instanceof ItemEntity ? .20 : .10;
        return (actor.getBbWidth() + companion.getBbWidth()) * .5 + gap;
    }

    private boolean hasEligiblePeer() {
        return !level().getEntitiesOfClass(YukkuriEntity.class, entity.getBoundingBox().inflate(NUZZLE_SIGHT),
                peer -> peer != entity && companionRefusal(peer) == null).isEmpty();
    }

    private static int approachBudget(YukkuriEntity mover, double distance, double reach) {
        double pace = BodyAbility.of(mover).walkSpeed() * BodyAbility.carrying(mover) * NUZZLE_WALK_SPEED;
        return APPROACH_SLACK + ContactPhase.walkTicks(distance, reach, pace);
    }

    public boolean valid() {
        if (!active()) return false;
        if (session.pair != null && session.pair.complete()) return !session.pair.finished();
        if (!permitsPerformance(session.leader, session.companion, session.kind)) return false;
        if (!available(session.leader, true, session.kind == Kind.EXERCISE) || !session.hasTime()) return false;
        if (session.kind != Kind.SING && (session.leader.isPassenger()
                || session.companion instanceof YukkuriEntity peer && peer.isPassenger())) return false;
        var other = session.companion;
        if (other == null) return true;
        if (!other.isAlive() || other.isRemoved() || other.level() != entity.level()) return false;
        if (other instanceof ItemEntity doll) {
            return session.kind == Kind.NUZZLE
                    && !doll.isInWater()
                    && Keepsakes.fond(session.leader, doll.getItem())
                    && session.leader.keepsake().comfortEligible(true)
                    && session.leader.distanceToSqr(doll) <= 100
                    && (session.leader.distanceToSqr(doll) <= 4 || Senses.sees(session.leader, doll))
                    && (session.elapsed % 20 != 0 || !session.leader.dailyLife().hasEligiblePeer());
        }
        if (session.leader.distanceToSqr(other) > (session.kind == Kind.NUZZLE ? NUZZLE_SIGHT * NUZZLE_SIGHT : 36)
                || session.leader.distanceToSqr(other) > 4 && !session.leader.hasLineOfSight(other)) return false;
        if (other instanceof YukkuriEntity peer)
            return available(peer, true, session.kind == Kind.EXERCISE) && peer.dailyLife().session == session
                    && (session.pair == null || !BehaviorContinuity.emergency(session.leader) && !BehaviorContinuity.emergency(peer))
                    && peer.life().personality().fear(session.leader.getUUID()) < 60
                    && session.leader.life().personality().fear(peer.getUUID()) < 60;
        return other instanceof Player player && player.isAlive() && session.leader.life().personality().fear(player.getUUID()) < 60;
    }
    /**
     * One motor turn of the whole body toward {@code other}: the look control only tilts the head, so a body left facing away
     * answers with its back to the one it answers. Requested once per missing facing, at the ability's turn rate.
     */
    private boolean turnToFace(Entity other) {
        float yaw = SurfaceApproach.yaw(entity.position(), other.position());
        if (Math.abs(Mth.wrapDegrees(yaw - entity.yBodyRot)) <= Face.FACE_ARRIVED) return true;
        if (entity.motor().current() instanceof Face) return false;
        GameplayLog.limited(entity, "daily", "greet_face." + other.getUUID(), 200,
                "greet face turn to={} yaw={} bodyYaw={}", () -> new Object[]{other.getUUID(), Math.round(yaw * 10) / 10.0,
                Math.round(entity.yBodyRot * 10) / 10.0});
        entity.motor().run(body, new Face(yaw));
        return false;
    }
    /** A stand beside the other at its own height, or (a bank over a hollow) a block above or below it. */
    private Vec3 standBeside(Entity other, double distance) {
        var stand = SurfaceApproach.beside(entity, other, distance, LEAN);
        for (double lift = 1; stand == null && lift >= -1; lift -= 2)
            stand = SurfaceApproach.beside(entity, other.position().add(0, lift, 0), distance, at -> Math.abs(at.y - other.getY()) <= LEAN);
        return stand;
    }

    private static PeerRub surveyRub(YukkuriEntity mover, YukkuriEntity anchor, PeerNuzzle mode) {
        var route = mode == PeerNuzzle.LEVEL
                ? SurfaceApproach.contactRoute(mover, anchor, anchor.getY(), yaw -> SideContact.levelStand(mover, anchor, yaw))
                : SurfaceApproach.contactRoute(mover, anchor, mover.getY(), yaw -> SideContact.riseStand(mover, anchor, yaw));
        return route == null ? null : new PeerRub(mode, mover, anchor, route);
    }

    private static PeerRub easier(PeerRub first, PeerRub second) {
        return first == null || second != null && second.effort() < first.effort() ? second : first;
    }

    private static PeerRub peerRub(YukkuriEntity leader, YukkuriEntity peer, boolean sameLevel) {
        var level = surveyRub(leader, peer, PeerNuzzle.LEVEL);
        if (level != null && sameLevel) return level;
        level = easier(level, surveyRub(peer, leader, PeerNuzzle.LEVEL));
        var rise = level == null && !sameLevel
                ? easier(surveyRub(leader, peer, PeerNuzzle.RISE), surveyRub(peer, leader, PeerNuzzle.RISE)) : null;
        return switch (PeerNuzzle.choose(level != null, rise != null)) {
            case LEVEL -> level;
            case RISE -> rise;
            case GREET -> new PeerRub(PeerNuzzle.GREET, leader, peer, null);
        };
    }

    private void greetPeer(Session current, YukkuriEntity peer, String greeting, boolean onlyGreeting) {
        if (current.leader != entity) { SurfaceApproach.halt(entity); turnToFace(peer); return; }
        var arrival = GreetingStance.approach(entity, peer, NUZZLE_WALK_SPEED, LEAN);
        if (arrival == SurfaceApproach.Result.BLOCKED) {
            if (onlyGreeting) stop();
            else current.greeting = null;
            return;
        }
        if (arrival != SurfaceApproach.Result.ARRIVED || !turnToFace(peer)) return;
        current.greeted = true;
        current.greeting = null;
        entity.social().noteContact(peer);
        entity.social().startGreeting(peer, greeting);
        current.greetHold = 2 * ClipMarkers.of("greet").length() - 1;
    }

    private void greetInstead(Session current, String reason) {
        var peer = (YukkuriEntity)current.companion;
        GameplayLog.event(entity, "daily", "peer rub unavailable partner={} reason={} affectionNeed={}",
                peer.getUUID(), reason, affection);
        if (current.pair != null) stop();
        else current.rub = new PeerRub(PeerNuzzle.GREET, current.leader, peer, null);
    }

    /** Both goals read the same stand. Its mover closes the pose's gap and advances their shared contact clock. */
    private boolean peerContact(Session current, YukkuriEntity other) {
        boolean lead = current.leader == entity;
        var peer = (YukkuriEntity)current.companion;
        if (current.greetHold > 0) {
            SurfaceApproach.halt(entity);
            turnToFace(other);
            if (lead) {
                if (current.greetHold == ClipMarkers.of("greet").length()
                        && peer.onGround() && GreetingStance.clear(peer, peer.position(), entity))
                    peer.social().answerGreeting(entity, "greet", "nuzzle");
                current.greetHold--;
            }
            return false;
        }
        if (lead && current.pair == null) current.elapsed++;
        boolean grounded = entity.onGround() && other.onGround();
        boolean sameLevel = ContactStandSearch.sameLevel(entity.getY(), other.getY());
        boolean hopping = entity.motor().current() instanceof Hop || other.motor().current() instanceof Hop;
        var rub = current.rub;
        if (rub != null && rub.mode == PeerNuzzle.GREET) {
            if (current.greeted) stop();
            else if (grounded && !hopping)
                greetPeer(current, other, current.greeting == null ? "nuzzle_out_of_reach" : current.greeting, true);
            return false;
        }
        if (grounded && !hopping && current.greeting != null && (sameLevel || rub != null && rub.mode == PeerNuzzle.RISE)) {
            greetPeer(current, other, current.greeting, false);
            return false;
        }
        if (rub == null || !hopping && (!SurfaceApproach.at(rub.anchor, rub.anchorAt)
                || !ContactStandSearch.sameLevel(rub.anchor.getY(), rub.anchorAt.y))) {
            if (current.pair != null) { stop(); return false; }
            SurfaceApproach.halt(entity);
            if (!lead || !grounded) return false;
            boolean firstRoute = rub == null;
            rub = current.rub = peerRub(entity, peer, sameLevel);
            if (rub.mode == PeerNuzzle.GREET) {
                greetInstead(current, "no_contact_stand");
                return false;
            }
            if (firstRoute) current.approachBudget = Math.max(current.approachBudget,
                    current.elapsed + approachBudget(rub.mover, rub.route.length(), 0));
            GameplayLog.event(entity, "daily", "peer rub approach partner={} mover={} mode={} origin={} stand={} approachTicks={}/{}",
                    peer.getUUID(), rub.mover.getUUID(), rub.mode, rub.mover.position(), rub.route.feet(),
                    current.elapsed, current.approachBudget);
        }
        var feet = rub.route.feet();
        if (!grounded || hopping || !ContactStandSearch.sameLevel(rub.mover.getY(), feet.y)
                || current.pair == null && !SurfaceApproach.at(rub.mover, feet)) {
            if (current.pair != null) { stop(); return false; }
            if (rub.mover != entity) SurfaceApproach.halt(entity);
            else if (SurfaceApproach.moveToContact(entity, rub.route, other, NUZZLE_WALK_SPEED, body) == SurfaceApproach.Result.BLOCKED)
                greetInstead(current, "contact_route_blocked");
            return false;
        }
        SurfaceApproach.halt(entity);
        float yaw = rub.route.yaw();
        Motor.face(entity, yaw);
        if (!aligned(entity, yaw, .0001) || !aligned(other, yaw, .0001)) {
            if (current.pair != null) stop();
            return false;
        }
        if (rub.mover != entity) return false;
        var contactFeet = SideContact.snuggleStand(entity, other, yaw, feet.y - rub.anchorAt.y, current.contactTicks());
        if (contactFeet == null) {
            greetInstead(current, "posed_contact_lost");
            return false;
        }
        double gap = entity.position().subtract(contactFeet).horizontalDistance();
        if (gap > BodyAbility.of(entity).walkSpeed() + SurfaceApproach.ARRIVAL) {
            if (current.pair != null) stop();
            return false;
        }
        var settlement = SurfaceApproach.settleContact(entity, contactFeet, other, current.pair != null);
        if (settlement == SurfaceApproach.Result.WAITING && current.pair.waitsForClearance()) return false;
        if (settlement != SurfaceApproach.Result.ARRIVED) {
            greetInstead(current, "contact_stand_blocked");
            return false;
        }
        if (current.pair == null) {
            int duration = ClipMarkers.of(Pose.RIGHT.clip()).length() + (rub.mode == PeerNuzzle.LEVEL ? 60 : 0);
            current.pair = new PairContact(other, entity, Pose.LEFT, duration);
            GameplayLog.event(current.leader, "daily", "peer rub started partner={} mode={} lift={} moverAt={} anchorAt={}",
                    peer.getUUID(), rub.mode, Math.abs(entity.getY() - other.getY()), entity.position(), other.position());
        }
        current.pair.publish((float)contactFeet.subtract(other.position()).horizontalDistance());
        return true;
    }

    public void tickGoal() {
        if (!valid()) { stop(); return; }
        var current = session;
        if (current.pair != null && current.pair.complete()) return;
        var other = current.leader == entity ? current.companion : current.leader;
        boolean peerNuzzle = current.kind == Kind.NUZZLE && other instanceof YukkuriEntity;
        if (peerNuzzle) {
            if (!peerContact(current, (YukkuriEntity)other)) return;
        } else if (other != null) {
            entity.getLookControl().setLookAt(other, 30, 30);
        }
        if (current.kind == Kind.NUZZLE && (other instanceof Player || other instanceof ItemEntity)) {
            double reach = nuzzleReach(entity, other);
            double lift = Math.abs(entity.getY() - other.getY());
            if (lift > LEAN || entity.position().subtract(other.position()).horizontalDistance() > reach + (lift > PLAYER_LEAN_STEP ? LEAN : 0)) {
                var stand = standBeside(other, reach - .04);
                current.contact = 0;
                if (!current.hasTime() || stand == null
                        || SurfaceApproach.walkResult(entity, stand, other, NUZZLE_WALK_SPEED) == SurfaceApproach.Result.BLOCKED) stop();
                else current.elapsed++;
                return;
            }
            SurfaceApproach.halt(entity);
            if (other instanceof ItemEntity) {
                if (!turnToFace(other)) { current.elapsed++; return; }
            } else {
                Motor.face(entity, SurfaceApproach.yaw(entity.position(), other.position()));
            }
        }
        if (current.kind == Kind.SING) EcologyMovement.halt(entity);
        else entity.getNavigation().stop();
        if (!peerNuzzle && current.leader != entity) return;
        if (current.kind != Kind.NUZZLE) current.elapsed++;
        var leader = current.leader;
        if (!peerNuzzle) current.contact++;
        if (current.contactTicks() == 1) {
            leader.speak(current.kind == Kind.NUZZLE ? "nuzzle" : current.kind == Kind.SING ? "song" : "exercise");
            if (current.kind == Kind.NUZZLE && current.companion instanceof ItemEntity) {
                leader.showReaction("closed_smile", "", 60);
                leader.keepsake().emitExternalBeat("rub", "closed_smile", "nuzzle", "ground", false, "");
            }
            if (leader.pet().ownedBy(current.songRuleOwner) && !leader.pet().conscience().breach(HouseRule.NO_SINGING))
                current.songRuleOwner = null;
        }
        if (current.contact > 1 && current.contact % 5 == 1 && entity.pet().ownedBy(current.songRuleOwner))
            entity.pet().conscience().witnessedSong();
        if (current.kind == Kind.SING && current.contact % 20 == 1)
            level().sendParticles(ParticleTypes.NOTE, entity.getX(), entity.getEyeY()+.2, entity.getZ(), 1, .15, .05, .15, 0);
        // The audience answers the singer by how well it knows it: a happy face if acquainted, a neutral one if not.
        if (current.kind == Kind.SING && current.contact % 40 == 1 && current.companion instanceof YukkuriEntity listener) {
            boolean known = YukkuriWorldData.get(level()).affinity(listener.getUUID(), entity.getUUID()) >= BondLevels.AFFINITY_ACQUAINTED;
            listener.showReaction(known ? "happy" : "neutral", "known_peer", 35);
            level().sendParticles(ParticleTypes.NOTE, listener.getX(), listener.getEyeY() + .1, listener.getZ(), 1, .05, .05, .05, 0);
        }
        int duration = current.kind == Kind.NUZZLE ? 60 : current.kind == Kind.SING ? 160 : 280;
        if (peerNuzzle ? !current.pair.complete() : current.contact < duration) return;
        boolean dollCompanion = current.kind == Kind.NUZZLE && current.companion instanceof ItemEntity;
        int affectionBefore = current.leader.dailyLife().affection;
        complete(current.leader, current.kind, dollCompanion ? 15 : 35);
        if (dollCompanion) {
            current.leader.keepsake().emitExternalBeat("done", "satisfied", "nuzzle_done", "ground", false,
                    "comfort=" + affectionBefore + "->" + current.leader.dailyLife().affection);
        }
        if (current.kind == Kind.NUZZLE && current.companion instanceof Player player)
            current.leader.pet().care().nuzzled(player);
        if (current.companion != null && !dollCompanion && current.kind == Kind.NUZZLE) {
            current.leader.life().personality().relationshipChanged(current.companion.getUUID(), 3, -2);
            if(current.companion instanceof YukkuriEntity peer)peer.life().personality().relationshipChanged(current.leader.getUUID(), 3, -2);
        }
        if (current.companion instanceof YukkuriEntity peer) {
            complete(peer, current.kind, 35);
            if (current.kind == Kind.NUZZLE) { current.leader.social().noteContact(peer); peer.social().noteContact(current.leader); }
        }
        if (!peerNuzzle) stop();
    }
    private static void complete(YukkuriEntity e, Kind kind, int nuzzleComfort) {
        var daily = e.dailyLife();
        int affectionBefore=daily.affection,boredomBefore=daily.boredom;
        float energyBefore=e.life().energy();
        if (kind == Kind.NUZZLE) { daily.comfort(nuzzleComfort); daily.nuzzles++; }
        else if (kind == Kind.SING) { daily.entertained(30); daily.comfort(5); daily.songs++; }
        else { daily.entertained(30); daily.exercises++; e.life().finishExercise(); }
        e.mind().felt(kind == Kind.NUZZLE ? EmotionEvent.NUZZLED : EmotionEvent.LEISURE_COMPLETED); e.showReaction("satisfied", kind == Kind.NUZZLE ? "nuzzle_done" : "leisure_done", 50);
        GameplayLog.limited(e,"daily","completed."+kind,1200,
                "session result kind={} affectionNeed={} -> {} boredom={} -> {} energy={} -> {} exerciseReliefTicks={}",
                kind,affectionBefore,daily.affection,boredomBefore,daily.boredom,energyBefore,e.life().energy(),e.life().exerciseReliefTicks());
    }
    public void stop() { stop(true); }
    private void stop(boolean log) {
        var current = session; session = null;
        if (current == null || current.ended) return;
        current.ended = true;
        int shown = current.pair == null ? current.contact : current.pair.release();
        if(log)GameplayLog.limited(current.leader,"daily","stopped."+current.kind,1200,
                "session stopped kind={} companion={} elapsed={} contactTicks={}",
                current.kind,current.companion==null?null:current.companion.getUUID(),current.elapsed,shown);
        if (current.companion != null && !(current.companion instanceof ItemEntity)) current.leader.life().personality().releaseRelation(current.companion.getUUID());
        if (current.companion instanceof YukkuriEntity peer) peer.life().personality().releaseRelation(current.leader.getUUID());
        if (current.leader.motor().owner() == current.leader.dailyLife().body) current.leader.motor().cancel(current.leader.dailyLife().body, "daily_stop");
        boolean unanswered = current.kind == Kind.NUZZLE && current.contactTicks() == 0
                && (current.rub == null || current.rub.mode != PeerNuzzle.GREET);
        current.leader.dailyLife().session = null;
        // Either partner can end the session after the other has handed MOVE to a higher-priority goal.
        if (current.leader.competingMoveGoalId() == null) current.leader.getNavigation().stop();
        if (unanswered) current.leader.dailyLife().cooldown = INTERRUPTED_COOLDOWN;
        if (current.companion instanceof YukkuriEntity peer) {
            peer.dailyLife().session = null;
            if (peer.competingMoveGoalId() == null) peer.getNavigation().stop();
            if (unanswered) peer.dailyLife().cooldown = INTERRUPTED_COOLDOWN;
        }
    }
    public CompoundTag save() {
        var tag = new CompoundTag(); tag.putInt("Affection", affection); tag.putInt("Boredom", boredom);
        tag.putInt("Clock", clock); tag.putInt("Cooldown", cooldown);
        tag.putInt("Nuzzles", nuzzles); tag.putInt("Songs", songs); tag.putInt("Exercises", exercises);
        return tag;
    }
    public void load(CompoundTag tag) {
        stop(false); affection = tag.contains("Affection") ? Math.clamp(tag.getInt("Affection"), 0, 100) : 20;
        boredom = tag.contains("Boredom") ? Math.clamp(tag.getInt("Boredom"), 0, 100) : 20;
        clock = Math.clamp(tag.getInt("Clock"), 0, 199); cooldown = Math.clamp(tag.getInt("Cooldown"), 0, 2400);
        nuzzles = Math.max(0, tag.getInt("Nuzzles")); songs = Math.max(0, tag.getInt("Songs")); exercises = Math.max(0, tag.getInt("Exercises"));
    }
}
