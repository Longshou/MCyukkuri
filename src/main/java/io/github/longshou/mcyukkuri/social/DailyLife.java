package io.github.longshou.mcyukkuri.social;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.family.FamilyData;
import io.github.longshou.mcyukkuri.human.HumanCommerce;
import io.github.longshou.mcyukkuri.life.YukkuriWorldData;
import io.github.longshou.mcyukkuri.social.YukkuriPersonality.Trait;
import java.util.Comparator;
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
        if (!active()) return "idle";
        return switch (session.kind) {
            case NUZZLE -> inContact() ? "nuzzle" : "seek_affection";
            case SING -> session.leader == entity ? "sing" : "listen_song";
            case EXERCISE -> entity.family().adult() ? "exercise_watch" : "unun_exercise";
        };
    }
    private ServerLevel level() { return (ServerLevel) entity.level(); }
    public void comfort(int amount) { affection = Math.max(0, affection - Math.max(0, amount)); }
    public void entertained(int amount) { boredom = Math.max(0, boredom - Math.max(0, amount)); }
    public void clock() {
        if (entity.level().isClientSide || entity.isNoAi() || !entity.isAlive()) return;
        if (cooldown > 0) cooldown--;
        if (scanWait > 0) scanWait--;
        if (++clock >= 200) {
            clock = 0;
            if (!entity.life().isSleeping()) {
                affection = Math.min(100, affection + (entity.life().personality().trait(Trait.SOCIABILITY) >= 65 ? 2 : 1));
                boredom = Math.min(100, boredom + 1);
            }
        }
        if (active() && !valid()) stop();
    }
    private static boolean available(YukkuriEntity e) {
        return !io.github.longshou.mcyukkuri.event.WildEvents.reserved(e) && e.isAlive() && !e.isRemoved() && !e.isNoAi() && e.onGround()
                && !e.isPanicking() && !e.isCommandPlaying() && !e.operations().active() && !e.predator().active() && !e.social().holdingStill()
                && !e.life().hasUrgentNeed() && e.life().activity().equals("idle")
                && e.life().nutrition() >= 40 && e.life().energy() >= 35
                && e.body().wound() == 0 && e.body().infection() == 0 && e.life().spicyTicks() == 0
                && !e.groupInteraction().active() && !e.playLife().active() && !e.pet().busy()
                && !e.family().active() && !e.house().active() && !e.habitat().active()
                && !io.github.longshou.mcyukkuri.settlement.Seasons.confined(e);
    }
    private boolean closePeer(YukkuriEntity peer) {
        if (!entity.life().canRecognize(peer) || !peer.life().canRecognize(entity)) return false;
        var relations = YukkuriWorldData.get(level());
        int a = relations.affinity(entity.getUUID(), peer.getUUID()), b = relations.affinity(peer.getUUID(), entity.getUUID());
        return a >= 0 && b >= 0 && (a >= 10 && b >= 10
                || FamilyData.get(level()).related(entity.getUUID(), peer.getUUID()));
    }
    private boolean trusted(Player player) {
        var personality = entity.life().personality();
        return player.isAlive() && !player.isSpectator() && player.level() == entity.level()
                && personality.trust(player.getUUID()) >= 10 && personality.fear(player.getUUID()) < 25;
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
        if (companion instanceof YukkuriEntity peer) {
            peer.social().stop(); peer.dailyLife().session = session; peer.dailyLife().cooldown = 600;
        }
        entity.setPersistenceRequired();
        if (kind == Kind.NUZZLE) entity.speak("want_nuzzle");
        if (kind == Kind.NUZZLE) entity.life().personality().decided("seek_companion");
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
        var peers = level().getEntitiesOfClass(YukkuriEntity.class, entity.getBoundingBox().inflate(5),
                peer -> peer != entity && available(peer) && !peer.dailyLife().active() && peer.dailyLife().cooldown == 0
                        && entity.hasLineOfSight(peer) && closePeer(peer)).stream()
                .sorted(Comparator.comparingDouble(entity::distanceToSqr)).toList();
        if (affection >= entity.life().personality().affectionThreshold()) {
            // Known owners are a preference, not automatic trust or a replacement for friends.
            var players = level().getEntitiesOfClass(Player.class, entity.getBoundingBox().inflate(5), this::trusted).stream()
                    .sorted(Comparator.comparingDouble(p -> entity.distanceToSqr(p) - (HumanCommerce.ownedBy(entity, p.getUUID()) ? 25 : 0))).toList();
            for (var player : players) if (nuzzle(player)) return true;
            for (var peer : peers) if (nuzzle(peer)) return true;
        }
        if (entity.life().constipated() && !entity.family().adult()) {
            var parent = peers.stream().filter(p -> p.family().adult()).findFirst().orElse(null);
            if (parent != null && begin(Kind.EXERCISE, parent)) return true;
        }
        if (boredom >= 45 && level().isDay()) {
            Kind kind = entity.getRandom().nextInt(3) == 0 ? Kind.EXERCISE : Kind.SING;
            return begin(kind, peers.isEmpty() ? null : peers.getFirst());
        }
        return false;
    }
    public boolean valid() {
        if (!active() || !available(session.leader) || session.elapsed >= 320) return false;
        var other = session.companion;
        if (other == null) return true;
        if (!other.isAlive() || other.isRemoved() || other.level() != entity.level()
                || session.leader.distanceToSqr(other) > 36 || !session.leader.hasLineOfSight(other)) return false;
        if (other instanceof YukkuriEntity peer)
            return available(peer) && peer.dailyLife().session == session && session.leader.dailyLife().closePeer(peer);
        return other instanceof Player player && session.leader.dailyLife().trusted(player);
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
                if (current.elapsed % 10 == 0 && !entity.life().navigate(other.blockPosition(), .8, false)) { stop(); return; }
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
            level().sendParticles(net.minecraft.core.particles.ParticleTypes.NOTE, entity.getX(), entity.getEyeY()+.2, entity.getZ(), 1, .15, .05, .15, 0);
        int duration = current.kind == Kind.NUZZLE ? 60 : current.kind == Kind.SING ? 160 : 280;
        if (current.contact < duration) return;
        complete(current.leader, current.kind);
        if(current.companion!=null&&current.kind==Kind.NUZZLE) {
            current.leader.life().personality().environment().record(current.companion.getUUID(),"nuzzle",3);
            if(current.companion instanceof YukkuriEntity peer)peer.life().personality().environment().record(current.leader.getUUID(),"nuzzle",3);
        }
        if (current.companion instanceof YukkuriEntity peer) {
            complete(peer, current.kind);
            if (current.kind == Kind.NUZZLE) {
                var relations = YukkuriWorldData.get(level());
                relations.relate(entity.getUUID(), peer.getUUID(), 2); relations.relate(peer.getUUID(), entity.getUUID(), 2);
            }
        }
        stop();
    }
    private static void complete(YukkuriEntity e, Kind kind) {
        var daily = e.dailyLife();
        if (kind == Kind.NUZZLE) { daily.comfort(35); daily.nuzzles++; }
        else if (kind == Kind.SING) { daily.entertained(30); daily.comfort(5); daily.songs++; }
        else { daily.entertained(30); daily.exercises++; e.life().finishExercise(); }
        e.showReaction("satisfied", kind == Kind.NUZZLE ? "nuzzle_done" : "leisure_done", 50);
    }
    public void stop() {
        var current = session; session = null;
        if (current == null || current.ended) return;
        current.ended = true;
        current.leader.dailyLife().session = null; current.leader.getNavigation().stop();
        if (current.companion instanceof YukkuriEntity peer) { peer.dailyLife().session = null; peer.getNavigation().stop(); }
    }
    public CompoundTag save() {
        var tag = new CompoundTag(); tag.putInt("Affection", affection); tag.putInt("Boredom", boredom);
        tag.putInt("Clock", clock); tag.putInt("Cooldown", cooldown);
        tag.putInt("Nuzzles", nuzzles); tag.putInt("Songs", songs); tag.putInt("Exercises", exercises); return tag;
    }
    public void load(CompoundTag tag) {
        stop(); affection = tag.contains("Affection") ? Math.clamp(tag.getInt("Affection"), 0, 100) : 20;
        boredom = tag.contains("Boredom") ? Math.clamp(tag.getInt("Boredom"), 0, 100) : 20;
        clock = Math.clamp(tag.getInt("Clock"), 0, 199); cooldown = Math.clamp(tag.getInt("Cooldown"), 0, 2400);
        nuzzles = Math.max(0, tag.getInt("Nuzzles")); songs = Math.max(0, tag.getInt("Songs")); exercises = Math.max(0, tag.getInt("Exercises"));
    }
}
