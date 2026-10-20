package io.github.longshou.mcyukkuri.social;

import io.github.longshou.mcyukkuri.status.GameplayLog;
import io.github.longshou.mcyukkuri.entity.Behaviors;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.event.WildEvents;
import io.github.longshou.mcyukkuri.human.HumanCommerce;
import io.github.longshou.mcyukkuri.life.SurfaceApproach;
import io.github.longshou.mcyukkuri.life.YukkuriWorldData;
import io.github.longshou.mcyukkuri.mind.EmotionEvent;
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

/** Loaded-time emotional needs and interruptible leisure. A pair shares one contact clock. */
public final class DailyLife {
    public enum Kind { NUZZLE, SING, EXERCISE }
    private static final class Session {
        final YukkuriEntity leader;
        final Entity companion;
        final Kind kind;
        int elapsed, contact;
        boolean ended;
        Session(YukkuriEntity leader, Entity companion, Kind kind) {
            this.leader = leader; this.companion = companion; this.kind = kind;
        }
    }
    private final YukkuriEntity entity;
    private Session session;
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
        }
        if (active() && !valid()) stop();
    }
    private static boolean available(YukkuriEntity e) { return available(e, false); }
    private static boolean available(YukkuriEntity e, boolean continuing) {
        return !WildEvents.reserved(e) && e.isAlive() && !e.isRemoved() && !e.isNoAi() && e.onGround()
                && !e.isPanicking() && !e.isCommandPlaying() && !e.operations().active() && !e.predator().active() && !e.social().holdingStill()
                && !(continuing ? e.life().nutrition() < 15 || e.life().energy() < 15
                    || e.isOnFire() || e.isInWaterOrBubble() : e.life().hasUrgentNeed())
                && e.life().activity().equals(Behaviors.IDLE)
                && e.life().nutrition() >= (continuing ? 15 : 40) && e.life().energy() >= (continuing ? 15 : 35)
                && e.body().condition().readyForAffection() && e.life().spicyTicks() == 0
                && !e.groupInteraction().active() && !e.playLife().active() && !e.pet().busy()
                && !e.family().active() && !e.house().active() && !e.habitat().active()
                && !e.independence().active() && !e.forageTrip().outing() && !e.settlement().active() && !e.settlement().duties().active()
                && !SettlementSeasons.confined(e);
    }
    private boolean closePeer(YukkuriEntity peer) {
        if (!entity.life().canRecognize(peer) || !peer.life().canRecognize(entity)) return false;
        return entity.life().personality().trust(peer.getUUID()) > 0
                && peer.life().personality().trust(entity.getUUID()) > 0
                && entity.life().personality().fear(peer.getUUID()) < 60
                && peer.life().personality().fear(entity.getUUID()) < 60;
    }
    private boolean trusted(Player player) {
        return player.isAlive() && !player.isSpectator() && player.level() == entity.level()
                && Bonds.trustedPlayer(entity, player.getUUID());
    }
    public boolean nuzzle(Entity companion) {
        if (companion == entity || companion.level() != entity.level() || !companion.isAlive()
                || entity.distanceToSqr(companion) > 25 || !entity.hasLineOfSight(companion)) return false;
        if (companion instanceof YukkuriEntity peer) {
            if (!available(peer) || peer.dailyLife().active() || peer.dailyLife().cooldown > 0 || !closePeer(peer)) return false;
        } else if (!(companion instanceof Player player) || !trusted(player)) return false;
        return begin(Kind.NUZZLE, companion);
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
    private boolean begin(Kind kind, Entity companion) {
        if (entity.level().isClientSide || !available(entity) || active() || cooldown > 0) return false;
        if (kind == Kind.EXERCISE && (!entity.childhood().canExercise()
                || companion instanceof YukkuriEntity peer && !peer.childhood().canExercise())) return false;
        if (kind != Kind.NUZZLE && !room()) return false;
        entity.social().stop();
        session = new Session(entity, companion, kind); cooldown = 600;
        if (companion != null) entity.life().personality().protectRelation(companion.getUUID());
        if (companion instanceof YukkuriEntity peer) {
            peer.social().stop(); peer.life().personality().protectRelation(entity.getUUID()); peer.dailyLife().session = session; peer.dailyLife().cooldown = 600;
        }
        entity.setPersistenceRequired();
        if (kind == Kind.NUZZLE) entity.speak("want_nuzzle");
        if (kind == Kind.NUZZLE) entity.life().personality().decided("seek_companion");
        GameplayLog.limited(entity,"daily","started."+kind,1200,
                "session started kind={} companion={} affectionNeed={} boredom={}",
                kind,companion==null?null:companion.getUUID(),affection,boredom);
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
        var peers = level().getEntitiesOfClass(YukkuriEntity.class, entity.getBoundingBox().inflate(5),
                peer -> peer != entity && available(peer) && !peer.dailyLife().active() && peer.dailyLife().cooldown == 0
                        && !invitations.containsKey(peer.getUUID())
                        && entity.hasLineOfSight(peer) && closePeer(peer)).stream()
                .sorted(Comparator.comparingInt((YukkuriEntity peer) -> entity.life().personality().trust(peer.getUUID())).reversed()
                        .thenComparingDouble(entity::distanceToSqr)).toList();
        boolean together = !peers.isEmpty() && entity.getRandom().nextFloat() < entity.life().personality().companionshipProbability();
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
            return begin(Kind.EXERCISE, companion);
        }
        if (companion != null && affection >= entity.life().personality().affectionThreshold() && nuzzle(companion)) return true;
        float song = 2 * entity.species().traits().song();
        Kind kind = !entity.childhood().canExercise() || entity.getRandom().nextFloat() * (song + 1) >= 1 ? Kind.SING : Kind.EXERCISE;
        return begin(kind, companion);
    }

    public boolean valid() {
        if (!active() || !available(session.leader, true) || session.elapsed >= 320) return false;
        var other = session.companion;
        if (other == null) return true;
        if (!other.isAlive() || other.isRemoved() || other.level() != entity.level()
                || session.leader.distanceToSqr(other) > 36 || !session.leader.hasLineOfSight(other)) return false;
        if (other instanceof YukkuriEntity peer)
            return available(peer, true) && peer.dailyLife().session == session
                    && peer.life().personality().fear(session.leader.getUUID()) < 60
                    && session.leader.life().personality().fear(peer.getUUID()) < 60;
        return other instanceof Player player && player.isAlive() && session.leader.life().personality().fear(player.getUUID()) < 60;
    }
    public void tickGoal() {
        if (!valid()) { stop(); return; }
        var current = session;
        var other = current.leader == entity ? current.companion : current.leader;
        if (other != null) entity.getLookControl().setLookAt(other, 30, 30);
        if (current.kind == Kind.NUZZLE && other != null) {
            double reach = (entity.getBbWidth() + other.getBbWidth()) * .5 + .10;
            boolean touching = entity.distanceToSqr(other) <= reach * reach
                    && Math.abs(entity.getY() - other.getY()) < .35;
            if (!touching) {
                // Path nodes end up to ~0.55 block short of the target block centre, which is farther than body contact:
                // the last stretch on a clear, level surface is steered directly, as in other close approaches.
                double flat = Math.sqrt((entity.getX() - other.getX()) * (entity.getX() - other.getX())
                        + (entity.getZ() - other.getZ()) * (entity.getZ() - other.getZ()));
                if (flat < 2.0 && entity.onGround() && Math.abs(entity.getY() - other.getY()) < .35
                        && SurfaceApproach.supported(entity, entity.position())) {
                    entity.getNavigation().stop();
                    entity.getMoveControl().setWantedPosition(other.getX(), entity.getY(), other.getZ(), .6);
                } else if (current.elapsed % 10 == 0 && !entity.life().navigate(other.blockPosition(), .8, false)) { stop(); return; }
                if (current.leader == entity) { current.contact = 0; current.elapsed++; }
                return;
            }
        }
        entity.getNavigation().stop();
        if (other != null && current.kind == Kind.NUZZLE) {
            float yaw=(float)(Math.atan2(other.getZ()-entity.getZ(),other.getX()-entity.getX())*180/Math.PI)-90;
            entity.setYRot(yaw);entity.setYHeadRot(yaw);entity.yBodyRot=yaw;
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
        current.leader.dailyLife().session = null; current.leader.getNavigation().stop();
        if (current.companion instanceof YukkuriEntity peer) { peer.dailyLife().session = null; peer.getNavigation().stop(); }
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
