package io.github.longshou.mcyukkuri.event;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.event.WildEventData.Kind;
import io.github.longshou.mcyukkuri.event.WildEventData.Phase;
import io.github.longshou.mcyukkuri.event.WildEventData.Scene;
import io.github.longshou.mcyukkuri.family.FamilyData;
import io.github.longshou.mcyukkuri.life.YukkuriConfig;
import io.github.longshou.mcyukkuri.life.YukkuriWorldData;
import io.github.longshou.mcyukkuri.settlement.SettlementData;
import io.github.longshou.mcyukkuri.settlement.ShelterBlockEntity;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.EventHooks;

/** Supplementary group scenes around shelters: nest monopoly, bandit raids and burrow collapse. Food only moves between real stores and mouths. */
public final class GroupCards {
    public static final String MONOPOLY = "nest-monopoly", RAID = "bandit-raid", COLLAPSE = "burrow-collapse";
    private static final String BAR = "ShelterBarredUntil";
    public static boolean handles(String card) { return card.equals(MONOPOLY) || card.equals(RAID) || card.equals(COLLAPSE); }
    public static boolean discover(YukkuriEntity e, List<YukkuriEntity> peers) {
        if (!(e.level() instanceof ServerLevel level)) return false;
        return bandits(e, peers, level) || monopoly(e, peers, level);
    }
    private static ShelterBlockEntity shelterAt(ServerLevel level, Scene s) {
        if (s.destination == null || !level.hasChunkAt(s.destination.pos())) return null;
        if (level.getBlockEntity(s.destination.pos()) instanceof ShelterBlockEntity shelter) return shelter;
        return null;
    }
    /** True while an excluded relative of a live nest-monopoly scene may not use the shelter. Expires on its own. */
    public static boolean barred(YukkuriEntity e) {
        if (!(e.level() instanceof ServerLevel level) || e.getPersistentData().getLong(BAR) <= level.getGameTime()) return false;
        Scene s = WildEventData.get(level).forMember(e.getUUID());
        return s != null && s.active() && s.kind == Kind.CARD && s.card.equals(MONOPOLY);
    }
    private static void go(YukkuriEntity e, Scene s, BlockPos goal, double distance) {
        if (e.distanceToSqr(Vec3.atBottomCenterOf(goal)) <= distance * distance) { e.getNavigation().stop(); return; }
        if (s.phaseTicks % 20 == 0) e.life().navigate(goal, .7, false);
    }

    // ---- 1. Nest monopoly -------------------------------------------------------------------------------------
    private static boolean sameMother(FamilyData family, YukkuriEntity a, YukkuriEntity b) {
        var first = family.person(a.getUUID()); var second = family.person(b.getUUID());
        return first != null && second != null && first.mother != null && first.mother.equals(second.mother);
    }
    private static boolean monopoly(YukkuriEntity e, List<YukkuriEntity> peers, ServerLevel level) {
        var shelter = e.habitat().shelter();
        if (shelter == null || !e.family().adult() || e.life().personality().selfishness() < 65
                || e.getRandom().nextInt(YukkuriConfig.MONOPOLY_ONE_IN.get()) != 0
                || e.blockPosition().distSqr(shelter.getBlockPos()) > 36 || !shelter.usable(e)) return false;
        var family = FamilyData.get(level);
        List<YukkuriEntity> kin = new ArrayList<>();
        for (var p : peers)
            if (p.habitat().shelter() == shelter && p.family().adult() && family.related(e.getUUID(), p.getUUID())
                    && p.blockPosition().distSqr(shelter.getBlockPos()) <= 36 && shelter.usable(p)) kin.add(p);
        if (kin.isEmpty()) return false;
        kin.sort(Comparator.comparingInt(p -> sameMother(family, e, p) ? 0 : 1)); // sisters first
        List<YukkuriEntity> roster = new ArrayList<>();
        roster.add(e); roster.addAll(kin.stream().limit(3).toList());
        var scene = WildEventData.get(level).start(Kind.CARD, MONOPOLY, GlobalPos.of(level.dimension(), e.blockPosition()),
                roster.stream().map(YukkuriEntity::getUUID).toList(), null, level.getGameTime());
        if (scene == null) return false;
        scene.destination = GlobalPos.of(level.dimension(), shelter.getBlockPos().immutable());
        scene.sourceCount = 600 + e.getRandom().nextInt(301); // [original] 30-45 seconds
        roster.get(1).getPersistentData().putLong(BAR, level.getGameTime() + scene.sourceCount + 200);
        for (var member : roster) { member.social().stop(); member.setPersistenceRequired(); }
        return true;
    }
    private static void release(List<YukkuriEntity> all) { for (var e : all) e.getPersistentData().remove(BAR); }
    private static void monopolyAdvance(ServerLevel level, Scene s, List<YukkuriEntity> all) {
        var shelter = shelterAt(level, s);
        if (all.size() < 2 || shelter == null || !shelter.sound()) { release(all); WildEvents.finish(level, s, Phase.INTERRUPTED, "nest_lost"); return; }
        var owner = all.getFirst(); var barredOut = all.get(1);
        var relations = YukkuriWorldData.get(level);
        if (s.phaseTicks == 1 || s.phaseTicks % 200 == 100) {
            owner.showReaction("angry", "mine", 80);
            barredOut.showReaction("teary", "wait_turn", 80);
            for (int i = 2; i < all.size(); i++) all.get(i).showReaction("neutral", "shy", 60);
        }
        if (s.phaseTicks % 100 == 50) for (int i = 1; i < all.size(); i++) relations.relate(all.get(i).getUUID(), owner.getUUID(), -2);
        if (s.phaseTicks < s.sourceCount) return;
        int regard = 0;
        for (int i = 1; i < all.size(); i++) regard += relations.affinity(all.get(i).getUUID(), owner.getUUID());
        release(all);
        if (regard < 0) {
            owner.showReaction("teary", "gentle", 90);
            for (int i = 1; i < all.size(); i++) relations.relate(all.get(i).getUUID(), owner.getUUID(), 4);
            barredOut.showReaction("happy", "share", 70);
            WildEvents.finish(level, s, Phase.COMPLETE, "apologized_and_shared");
        } else WildEvents.finish(level, s, Phase.COMPLETE, "kept_the_nest");
    }
    private static void monopolyMove(YukkuriEntity e, Scene s, ShelterBlockEntity shelter) {
        int index = s.members.indexOf(e.getUUID());
        if (index == 0) go(e, s, shelter.entrance(), 1.2);
        else if (index == 1) go(e, s, shelter.entrance().relative(shelter.facing(), 2), 1.5);
        else e.getNavigation().stop();
    }

    // ---- 2. Bandit raid -------------------------------------------------------------------------------------------
    private static boolean bandits(YukkuriEntity e, List<YukkuriEntity> peers, ServerLevel level) {
        var group = e.settlement().group();
        if (group == null || group.kind != SettlementData.Kind.BANDITS || !e.family().adult()
                || e.getRandom().nextInt(YukkuriConfig.BANDIT_RAID_ONE_IN.get()) != 0) return false;
        var mates = peers.stream().filter(p -> group.id.equals(p.settlement().groupId())).limit(2).toList();
        if (mates.isEmpty()) return false;
        ShelterBlockEntity best = null;
        for (BlockPos pos : BlockPos.betweenClosed(e.blockPosition().offset(-16, -3, -16), e.blockPosition().offset(16, 3, 16))) {
            if (pos.distSqr(e.blockPosition()) > 256 || !level.hasChunkAt(pos)) continue;
            if (level.getBlockEntity(pos) instanceof ShelterBlockEntity shelter && shelter.occupants() > 0 && shelter.food() > 0 && shelter.sound()
                    && (best == null || pos.distSqr(e.blockPosition()) < best.getBlockPos().distSqr(e.blockPosition()))) best = shelter;
        }
        if (best == null || !e.life().reachable(best.entrance(), false)) return false;
        List<YukkuriEntity> roster = new ArrayList<>();
        roster.add(e); roster.addAll(mates);
        var scene = WildEventData.get(level).start(Kind.CARD, RAID, GlobalPos.of(level.dimension(), e.blockPosition()),
                roster.stream().map(YukkuriEntity::getUUID).toList(), null, level.getGameTime());
        if (scene == null) return false;
        scene.destination = GlobalPos.of(level.dimension(), best.getBlockPos().immutable());
        for (var member : roster) { member.social().stop(); member.setPersistenceRequired(); }
        return true;
    }
    private static void raidAdvance(ServerLevel level, Scene s, List<YukkuriEntity> all) {
        var thief = all.getFirst();
        var shelter = shelterAt(level, s);
        if (shelter == null) { WildEvents.finish(level, s, Phase.INTERRUPTED, "store_missing"); return; }
        if (s.phaseTicks >= 1200) {
            WildEvents.finish(level, s, s.sourceCount > 0 ? Phase.COMPLETE : Phase.INTERRUPTED, s.sourceCount > 0 ? "raided_store" : "raid_timed_out"); return;
        }
        if (s.phaseTicks == 1) thief.showReaction("angry", "mine", 50);
        if (thief.life().mealTicks() > 0 || thief.distanceToSqr(Vec3.atBottomCenterOf(shelter.entrance())) > 6.25) return;
        if (s.sourceCount > 0 && s.phaseTicks - s.responses < 60) return;
        var loot = shelter.raid();
        if (loot.isEmpty()) {
            thief.showReaction("neutral", "wait_turn", 50);
            WildEvents.finish(level, s, Phase.COMPLETE, s.sourceCount > 0 ? "raided_store" : "store_empty"); return;
        }
        s.sourceCount++; s.responses = s.phaseTicks;
        // Eat on the spot, or carry it off to the ground nearby; the portion itself is the one taken from the store.
        if (!thief.life().beginMeal(loot, null, false)) {
            var drop = thief.spawnAtLocation(loot);
            if (drop != null) drop.setPickUpDelay(40);
        }
        for (UUID id : shelter.residentIds()) {
            if (level.getEntity(id) instanceof YukkuriEntity resident && resident.isAlive() && resident.distanceToSqr(thief) <= 100) {
                boolean fights = resident.life().personality().protects();
                resident.showReaction(fights ? "angry" : "teary", fights ? "mine" : "afraid", 80);
                YukkuriWorldData.get(level).relate(resident.getUUID(), thief.getUUID(), -3);
            }
        }
        if (s.sourceCount >= 3 || shelter.food() == 0) WildEvents.finish(level, s, Phase.COMPLETE, "raided_store");
    }
    private static void raidMove(YukkuriEntity e, Scene s, ShelterBlockEntity shelter) {
        if (s.members.indexOf(e.getUUID()) == 0) { go(e, s, shelter.entrance(), 1.2); return; }
        var level = (ServerLevel) e.level();
        if (level.getEntity(s.members.getFirst()) instanceof YukkuriEntity thief) go(e, s, thief.blockPosition(), 3);
        else e.getNavigation().stop();
    }

    // ---- 3. Burrow collapse -------------------------------------------------------------------------------------
    /** Called by a soaked, crowded natural burrow. Returns true when the shelter has been taken down. */
    public static boolean collapse(ServerLevel level, ShelterBlockEntity shelter) {
        List<YukkuriEntity> residents = new ArrayList<>();
        for (UUID id : shelter.residentIds())
            if (level.getEntity(id) instanceof YukkuriEntity y && y.isAlive()) residents.add(y);
        if (residents.size() < 3 || !EventHooks.canEntityGrief(level, residents.getFirst())) return false;
        BlockPos at = shelter.getBlockPos();
        level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, Blocks.DIRT.defaultBlockState()),
                at.getX() + .5, at.getY() + .5, at.getZ() + .5, 40, .8, .5, .8, .1);
        level.playSound(null, at, SoundEvents.GRAVEL_BREAK, SoundSource.BLOCKS, 1f, .7f);
        for (var resident : residents) {
            if (resident.getHealth() > 3) resident.hurt(level.damageSources().generic(), 1 + level.random.nextInt(2));
            resident.showReaction("cry", "afraid", 80);
            resident.life().navigate(shelter.entrance(), 1.4, false);
        }
        var ids = residents.stream().limit(6).map(YukkuriEntity::getUUID).toList();
        var scene = WildEventData.get(level).start(Kind.CARD, COLLAPSE, GlobalPos.of(level.dimension(), at), ids, null, level.getGameTime());
        if (scene != null) WildEvents.finish(level, scene, Phase.COMPLETE, "collapsed_in_long_rain");
        shelter.dismantle();
        return true;
    }

    // ---- dispatch ---------------------------------------------------------------------------------------------------
    public static void advance(ServerLevel level, Scene s, List<YukkuriEntity> all) {
        if (s.card.equals(MONOPOLY)) monopolyAdvance(level, s, all);
        else if (s.card.equals(RAID)) raidAdvance(level, s, all);
        else WildEvents.finish(level, s, Phase.COMPLETE, "collapsed_in_long_rain");
    }
    public static void move(YukkuriEntity e, Scene s) {
        var shelter = shelterAt((ServerLevel) e.level(), s);
        if (shelter == null) return;
        if (s.card.equals(MONOPOLY)) monopolyMove(e, s, shelter);
        else if (s.card.equals(RAID)) raidMove(e, s, shelter);
    }
    private GroupCards() {}
}
