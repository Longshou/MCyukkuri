package io.github.longshou.mcyukkuri.social;

import io.github.longshou.mcyukkuri.status.GameplayLog;
import io.github.longshou.mcyukkuri.entity.Behaviors;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.event.WildEvents;
import io.github.longshou.mcyukkuri.life.SurfaceApproach;
import io.github.longshou.mcyukkuri.life.EcologyMovement;
import io.github.longshou.mcyukkuri.mind.EmotionEvent;
import io.github.longshou.mcyukkuri.mind.MindLife;
import io.github.longshou.mcyukkuri.motor.ClipMarkers;
import io.github.longshou.mcyukkuri.motor.Motor;
import io.github.longshou.mcyukkuri.settlement.SettlementSeasons;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/** Loaded-time emotional needs and interruptible leisure. A pair shares one contact clock. */
public final class DailyLife {
    public enum Kind { NUZZLE, SING, EXERCISE }
    private static final class Session {
        final YukkuriEntity leader;
        final Entity companion;
        final Kind kind;
        /** A comfort visit to a suffering group-mate, not a nuzzle between friends. */
        final boolean pity;
        int elapsed, contact, greetHold;
        boolean ended, greeted;
        Session(YukkuriEntity leader, Entity companion, Kind kind, boolean pity) {
            this.leader = leader; this.companion = companion; this.kind = kind; this.pity = pity;
        }
    }
    private final YukkuriEntity entity;
    private Session session;
    /** A lonely individual walks to a trusted friend it can see this far off and asks for a nuzzle. */
    private static final int NUZZLE_SIGHT = 16;
    /** A nuzzle broken off before any contact (the one who asked was called away by its own need) is asked for again soon. */
    private static final int INTERRUPTED_COOLDOWN = 200;
    private static final double LEAN = 1.1;
    private int affection = 20, boredom = 20, clock, cooldown, scanWait;
    private int nuzzles, songs, exercises;
    private final Map<UUID, Integer> invitations = new HashMap<>();

    public DailyLife(YukkuriEntity entity) { this.entity = entity; }
    public int affectionNeed() { return affection; }
    public int boredom() { return boredom; }
    public int nuzzles() { return nuzzles; }
    public int songs() { return songs; }
    public int exercises() { return exercises; }
    public boolean active() { return session != null && !session.ended; }
    public boolean inContact() { return active() && session.contact > 0; }
    public String detail() {
        return !active() ? "idle" : "kind=" + session.kind
                + " contact=" + session.contact + " elapsed=" + session.elapsed;
    }
    public int nuzzlePartnerId() {
        if (!active() || session.kind != Kind.NUZZLE) return -1;
        Entity peer = session.leader == entity ? session.companion : session.leader;
        return peer == null ? -1 : peer.getId();
    }
    public YukkuriEntity songListener() {
        return active() && session.leader == entity && session.kind == Kind.SING && session.companion instanceof YukkuriEntity peer ? peer : null;
    }
    /** Ephemeral identity only; events must not serialize or recreate a daily session from it. */
    public Object songSessionToken() { return active() && session.leader == entity && session.kind == Kind.SING ? session : null; }
    public String activity() {
        if (!active()) return Behaviors.IDLE;
        return switch (session.kind) {
            case NUZZLE -> inContact() ? Behaviors.NUZZLE : Behaviors.SEEK_AFFECTION;
            case SING -> session.leader == entity ? Behaviors.SING : Behaviors.LISTEN_SONG;
            case EXERCISE -> entity.family().adult() ? Behaviors.EXERCISE_WATCH : Behaviors.UNUN_EXERCISE;
        };
    }
    private ServerLevel level() { return (ServerLevel) entity.level(); }
    public void comfort(int amount) { affection = Math.max(0, affection - Math.max(0, amount)); }
    public void entertained(int amount) { boredom = Math.max(0, boredom - Math.max(0, amount)); }
    public void clock() {
        if (entity.level().isClientSide || entity.isNoAi() || !entity.isAlive()) return;
        if (cooldown > 0) cooldown--;
        if (scanWait > 0) scanWait--;
        invitations.replaceAll((id, ticks) -> ticks - 1);
        invitations.values().removeIf(ticks -> ticks <= 0);
        if (++clock >= 200) {
            clock = 0;
            if (!entity.life().isSleeping()) {
                affection = Math.min(100, affection + (entity.life().personality().attachment() >= Temperament.SOCIABILITY_AT_65 ? 2 : 1)
                        + (entity.getRandom().nextFloat() < entity.species().traits().affection() - 1 ? 1 : 0));
                boredom = Math.min(100, boredom + 1);
            }
            if (entity.body().weak() && entity.life().activity().equals(Behaviors.IDLE) && !entity.isCommandPlaying())
                entity.mind().perform("droop", 80);
        }
        if (active() && !valid()) stop();
    }
    private static boolean available(YukkuriEntity e) { return available(e, false); }
    private static boolean available(YukkuriEntity e, boolean continuing) {
        return !WildEvents.reserved(e) && e.isAlive() && !e.isRemoved() && !e.isNoAi()
                // A session under way survives the step down a bank it is walking to (a friend in a hollow); a new one starts on the ground.
                && (continuing || e.onGround()) && !e.isPassenger()
                && !e.isPanicking() && !e.isCommandPlaying() && !e.operations().active() && !e.predator().active() && !e.social().holdingStill()
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
     * The one "well and free" gate for leisure and wild cards: a weak body rests, and a calm mind with nothing pressing
     * (an own newborn awaiting its first meal or toilet within 12, a swaddle needing care, a comfort walk under way, a heat with its partner in sight)
     * is what songs, exercise and cards need. The scans run only when a session is being admitted, never while one continues.
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
    /**
     * One may be comforted while it is starving or chewing the portion it was given: that distress is the reason to come, not a
     * reason to stay away. Only a body that cannot take part at all (asleep, carried, panicking, in an operation) is out.
     */
    private static boolean receptiveToComfort(YukkuriEntity peer) {
        return !WildEvents.reserved(peer) && peer.isAlive() && !peer.isRemoved() && !peer.isNoAi()
                && peer.onGround() && !peer.isPassenger() && !peer.isPanicking() && !peer.isCommandPlaying()
                && !peer.isOnFire() && !peer.isInWaterOrBubble() && !peer.life().isSleeping()
                && !peer.operations().active() && !peer.predator().active();
    }
    private boolean trusted(Player player) {
        return player.isAlive() && !player.isSpectator() && player.level() == entity.level()
                && Bonds.trustedPlayer(entity, player.getUUID());
    }
    public boolean nuzzle(Entity companion) { return nuzzle(companion, false); }
    /** A gentle one comforts a suffering group-mate that knows it: the same nuzzle, without the trust a leisure friend needs. */
    public boolean console(YukkuriEntity peer) { return nuzzle(peer, true); }
    private boolean nuzzle(Entity companion, boolean pity) {
        if (companion == entity || companion.level() != entity.level() || !companion.isAlive()
                || entity.distanceToSqr(companion) > NUZZLE_SIGHT * NUZZLE_SIGHT || !entity.hasLineOfSight(companion)) return false;
        if (companion instanceof YukkuriEntity peer) {
            var why = refusal(peer, pity);
            if (why != null) {
                if (pity) GameplayLog.limited(entity, "settlement", "pit_care_refused", 600,
                        "pit care refused slave={} reason={} distance={}", peer.getUUID(), why,
                        String.format("%.1f", Math.sqrt(entity.distanceToSqr(peer))));
                return false;
            }
        } else if (!(companion instanceof Player player) || !trusted(player)) return false;
        return begin(Kind.NUZZLE, companion, pity);
    }
    /** Why this one cannot nuzzle {@code peer} right now (null: it can); the comfort of a suffering group-mate reports it. */
    private String refusal(YukkuriEntity peer, boolean pity) {
        if (pity ? !receptiveToComfort(peer) : !available(peer)) return "companion_busy";
        if (peer.dailyLife().active()) return "companion_session";
        if (peer.dailyLife().cooldown > 0) return "companion_cooldown";
        if (pity ? !unafraid(peer) : !closePeer(peer)) return "companion_untrusted";
        return null;
    }
    /** A care-menu invitation respects the same needs, trust, space and cooldown as autonomous leisure. */
    public boolean invite(Player player, Kind kind) {
        if (!trusted(player) || entity.distanceToSqr(player) > 16 || !entity.hasLineOfSight(player)) return false;
        return kind == Kind.NUZZLE ? nuzzle(player) : begin(kind, player);
    }
    public boolean begin(Kind kind) { return kind != Kind.NUZZLE && begin(kind, null); }
    public boolean singTo(YukkuriEntity listener) {
        return listener != entity && listener.level() == entity.level() && available(listener)
                && !listener.dailyLife().active() && listener.dailyLife().cooldown == 0
                && entity.distanceToSqr(listener) <= 36 && entity.hasLineOfSight(listener) && closePeer(listener)
                && begin(Kind.SING, listener);
    }
    private boolean begin(Kind kind, Entity companion) { return begin(kind, companion, false); }
    private boolean begin(Kind kind, Entity companion, boolean pity) {
        if (entity.level().isClientSide || !available(entity) || active() || cooldown > 0) return false;
        if (kind == Kind.EXERCISE && (!entity.childhood().canExercise()
                || companion instanceof YukkuriEntity peer && !peer.childhood().canExercise())) return false;
        if (kind != Kind.NUZZLE && !room()) return false;
        entity.social().stop();
        session = new Session(entity, companion, kind, pity); cooldown = 600;
        if (companion != null) entity.life().personality().protectRelation(companion.getUUID());
        if (companion instanceof YukkuriEntity peer) {
            peer.social().stop(); peer.life().personality().protectRelation(entity.getUUID()); peer.dailyLife().session = session; peer.dailyLife().cooldown = 600;
        }
        entity.setPersistenceRequired();
        if (kind == Kind.NUZZLE) entity.speak("want_nuzzle");
        if (kind == Kind.NUZZLE) entity.life().personality().decided("seek_companion");
        GameplayLog.limited(entity,"daily","started."+kind,1200,
                "session reserved kind={} companion={} distance={} affectionNeed={} boredom={}",
                kind,companion==null?null:companion.getUUID(),companion==null?0:Math.round(entity.distanceTo(companion)*10)/10.0,affection,boredom);
        return true;
    }
    private boolean room() {
        var pos = entity.blockPosition();
        for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++)
            if (!entity.life().dryStand(pos.offset(x, 0, z))) return false;
        return true;
    }
    public boolean plan() {
        if (active()) return valid();
        if (scanWait > 0 || cooldown > 0 || entity.social().active() || !available(entity)) return false;
        scanWait = 100;
        if (boredom < 45 && affection < entity.life().personality().affectionThreshold()
                && !entity.life().constipated()) return false;
        scanWait = 600;
        boolean lonely = affection >= entity.life().personality().affectionThreshold();
        var peers = level().getEntitiesOfClass(YukkuriEntity.class, entity.getBoundingBox().inflate(lonely ? NUZZLE_SIGHT : 5),
                peer -> peer != entity && available(peer) && !peer.dailyLife().active() && peer.dailyLife().cooldown == 0
                        && !invitations.containsKey(peer.getUUID())
                        && entity.hasLineOfSight(peer) && closePeer(peer)
                        && (entity.distanceToSqr(peer) <= 25 || entity.life().reachable(peer.blockPosition(), false))).stream()
                .sorted(Comparator.comparingInt((YukkuriEntity peer) -> entity.life().personality().trust(peer.getUUID())).reversed()
                        .thenComparingDouble(entity::distanceToSqr)).toList();
        // Loneliness decides the asking; the friend's own roll below still decides the answer.
        boolean together = !peers.isEmpty() && (lonely || entity.getRandom().nextFloat() < entity.life().personality().companionshipProbability());
        YukkuriEntity companion = null;
        if (together) {
            var candidate = peers.getFirst();
            invitations.put(candidate.getUUID(), 600);
            candidate.dailyLife().invitations.put(entity.getUUID(), 600);
            if (candidate.getRandom().nextFloat() < candidate.life().personality().companionshipProbability()) companion = candidate;
        }
        if (entity.life().constipated() && entity.childhood().canExercise()) {
            if (companion != null && companion.family().adult()
                    && companion.getRandom().nextFloat() >= companion.life().personality().helpProbability(true, false)) companion = null;
            return begin(Kind.EXERCISE, inEarshot(companion));
        }
        if (companion != null && lonely && nuzzle(companion)) return true;
        float song = 2 * entity.species().traits().song();
        Kind kind = !entity.childhood().canExercise() || entity.getRandom().nextFloat() * (song + 1) >= 1 ? Kind.SING : Kind.EXERCISE;
        return begin(kind, inEarshot(companion));
    }
    /** Songs and exercise are shared only with a companion close by; a far friend is for the nuzzle walk. */
    private YukkuriEntity inEarshot(YukkuriEntity companion) { return companion != null && entity.distanceToSqr(companion) <= 25 ? companion : null; }

    public boolean valid() {
        if (!active() || !available(session.leader, true) || session.elapsed >= 320) return false;
        if (session.kind != Kind.SING && (session.leader.isPassenger()
                || session.companion instanceof YukkuriEntity peer && peer.isPassenger())) return false;
        var other = session.companion;
        if (other == null) return true;
        // A comfort visit walks to the group's own pit, whose rim keeps hiding the slave below it: the walk needs no sight line.
        if (!other.isAlive() || other.isRemoved() || other.level() != entity.level()
                || session.leader.distanceToSqr(other) > (session.kind == Kind.NUZZLE ? NUZZLE_SIGHT * NUZZLE_SIGHT : 36)
                || !session.pity && session.leader.distanceToSqr(other) > 4 && !session.leader.hasLineOfSight(other)) return false;
        if (other instanceof YukkuriEntity peer)
            return (session.pity ? receptiveToComfort(peer) : available(peer, true)) && peer.dailyLife().session == session
                    && peer.life().personality().fear(session.leader.getUUID()) < 60
                    && session.leader.life().personality().fear(peer.getUUID()) < 60;
        return other instanceof Player player && player.isAlive() && session.leader.life().personality().fear(player.getUUID()) < 60;
    }
    /** A stand beside the other at its own height, or (a bank over a hollow) a block above or below it. */
    private Vec3 standBeside(Entity other, double distance) {
        var stand = SurfaceApproach.beside(entity, other, distance, LEAN);
        for (double lift = 1; stand == null && lift >= -1; lift -= 2)
            stand = SurfaceApproach.beside(entity, other.position().add(0, lift, 0), distance, at -> Math.abs(at.y - other.getY()) <= LEAN);
        return stand;
    }
    public void tickGoal() {
        if (!valid()) { stop(); return; }
        var current = session;
        var other = current.leader == entity ? current.companion : current.leader;
        if (other != null) entity.getLookControl().setLookAt(other, 30, 30);
        if (current.kind == Kind.NUZZLE && other != null) {
            double reach = (entity.getBbWidth() + other.getBbWidth()) * .5 + .10;
            // A nuzzle leans over one block of height (a friend on the bank, a hollow below it), and the lean itself reaches that much further.
            double lift = Math.abs(entity.getY() - other.getY());
            boolean touching = lift <= LEAN
                    && entity.position().subtract(other.position()).horizontalDistance() <= reach + (lift > .35 ? LEAN : 0);
            if (!touching) {
                // One member approaches the consenting partner. Two centre-seeking controllers otherwise push
                // each other past their contact envelope, especially when their bodies meet diagonally.
                if(current.leader==entity){
                    var stand=standBeside(other,reach-.04);
                    if(stand==null)SurfaceApproach.halt(entity);
                    else SurfaceApproach.walkAround(entity,stand,other,.8);
                    current.contact=0;current.elapsed++;
                }else SurfaceApproach.halt(entity);
                return;
            }
            SurfaceApproach.halt(entity);
            // A peer whose greeting is due is greeted first, then nuzzled.
            if (current.leader == entity && !current.greeted && other instanceof YukkuriEntity peer) {
                current.greeted = true;
                if (entity.social().greetingDue(peer) != null) {
                    peer.social().noteContact(entity);
                    entity.showReaction("happy", "hello", 80);
                    peer.showReactionDelayed("happy", "hello", 80, 20);
                    current.greetHold = ClipMarkers.of("greet").length() + 20;
                }
            }
            if (current.greetHold > 0) {
                if (current.leader == entity) current.greetHold--;
                return;
            }
        }
        if (current.kind == Kind.SING) EcologyMovement.halt(entity);
        else entity.getNavigation().stop();
        if (other != null && current.kind == Kind.NUZZLE) {
            float yaw=(float)(Math.atan2(other.getZ()-entity.getZ(),other.getX()-entity.getX())*180/Math.PI)-90;
            Motor.face(entity, yaw);
        }
        if (current.leader != entity) return;
        current.elapsed++;
        current.contact++;
        if (current.contact == 1) {
            entity.speak(current.kind == Kind.NUZZLE ? "nuzzle" : current.kind == Kind.SING ? "song" : "exercise");
        }
        if (current.kind == Kind.SING && current.contact % 20 == 1)
            level().sendParticles(ParticleTypes.NOTE, entity.getX(), entity.getEyeY()+.2, entity.getZ(), 1, .15, .05, .15, 0);
        int duration = current.kind == Kind.NUZZLE ? 60 : current.kind == Kind.SING ? 160 : 280;
        if (current.contact < duration) return;
        complete(current.leader, current.kind);
        if(current.companion!=null&&current.kind==Kind.NUZZLE) {
            current.leader.life().personality().relationshipChanged(current.companion.getUUID(), 3, -2);
            if(current.companion instanceof YukkuriEntity peer)peer.life().personality().relationshipChanged(current.leader.getUUID(), 3, -2);
        }
        if (current.companion instanceof YukkuriEntity peer) {
            complete(peer, current.kind);
            if (current.kind == Kind.NUZZLE) { current.leader.social().noteContact(peer); peer.social().noteContact(current.leader); }
            if (current.kind == Kind.NUZZLE && peer.settlement().society().slave()) peer.settlement().society().kindContact(current.leader);
        }
        stop();
    }
    private static void complete(YukkuriEntity e, Kind kind) {
        var daily = e.dailyLife();
        int affectionBefore=daily.affection,boredomBefore=daily.boredom;
        float energyBefore=e.life().energy();
        if (kind == Kind.NUZZLE) { daily.comfort(35); daily.nuzzles++; }
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
        if(log)GameplayLog.limited(current.leader,"daily","stopped."+current.kind,1200,
                "session stopped kind={} companion={} elapsed={} contactTicks={}",
                current.kind,current.companion==null?null:current.companion.getUUID(),current.elapsed,current.contact);
        if (current.companion != null) current.leader.life().personality().releaseRelation(current.companion.getUUID());
        if (current.companion instanceof YukkuriEntity peer) peer.life().personality().releaseRelation(current.leader.getUUID());
        boolean unanswered = current.kind == Kind.NUZZLE && current.contact == 0;
        current.leader.dailyLife().session = null; current.leader.getNavigation().stop();
        if (unanswered) current.leader.dailyLife().cooldown = INTERRUPTED_COOLDOWN;
        if (current.companion instanceof YukkuriEntity peer) {
            peer.dailyLife().session = null; peer.getNavigation().stop();
            if (unanswered) peer.dailyLife().cooldown = INTERRUPTED_COOLDOWN;
        }
    }
    public CompoundTag save() {
        var tag = new CompoundTag(); tag.putInt("Affection", affection); tag.putInt("Boredom", boredom);
        tag.putInt("Clock", clock); tag.putInt("Cooldown", cooldown);
        tag.putInt("Nuzzles", nuzzles); tag.putInt("Songs", songs); tag.putInt("Exercises", exercises); return tag;
    }
    public void load(CompoundTag tag) {
        stop(false); affection = tag.contains("Affection") ? Math.clamp(tag.getInt("Affection"), 0, 100) : 20;
        boredom = tag.contains("Boredom") ? Math.clamp(tag.getInt("Boredom"), 0, 100) : 20;
        clock = Math.clamp(tag.getInt("Clock"), 0, 199); cooldown = Math.clamp(tag.getInt("Cooldown"), 0, 2400);
        nuzzles = Math.max(0, tag.getInt("Nuzzles")); songs = Math.max(0, tag.getInt("Songs")); exercises = Math.max(0, tag.getInt("Exercises"));
    }
}
