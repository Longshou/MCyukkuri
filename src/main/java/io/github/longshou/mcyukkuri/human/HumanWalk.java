package io.github.longshou.mcyukkuri.human;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import java.util.EnumSet;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.util.DefaultRandomPos;
import net.minecraft.server.level.ServerLevel;

/**
 * A resident out walking its own pet on the lead (docs/HUMAN_WORLD.md "Walking the pet"). The pet is an ordinary companion owned by the resident
 * (custody Owner = the resident, Use = companion, harness on), so the pet systems treat it as any owner's pet: the lead needs the harness, and the walk
 * counts blocks in {@code PetWalk} exactly as it does for a player (boredom, affection, stress, energy; the pet follows through the vanilla lead).
 *
 * Schedule: in daylight, outside rain, a resident that has a pet goes out once its cooldown is over, leashes the pet, strolls between waypoints near home
 * for about three minutes (stopping while the pet lags behind or has an urgent need), walks back to the post, and unleashes. Everything runs on the server's
 * game time, never frames; the resident's NBT keeps the pet's UUID, the cooldown and the phase.
 */
final class HumanWalk {
    static final int WALK_TICKS = 3600, COOLDOWN = 3000, LAG_BLOCKS = 5;
    private final HumanNpc npc;
    private UUID petId;
    private boolean walking, returning;
    private long walkUntil, nextWalk, walked;
    private BlockPos waypoint;
    private int linger, lostLead;

    HumanWalk(HumanNpc npc) { this.npc = npc; }

    UUID petId() { return petId; }
    boolean walking() { return walking; }
    boolean returning() { return returning; }

    YukkuriEntity pet() {
        return petId != null && npc.level() instanceof ServerLevel level && level.getEntity(petId) instanceof YukkuriEntity y && y.isAlive() ? y : null;
    }
    /** The resident takes the individual as its own companion and fits the harness. */
    void adopt(YukkuriEntity pet) {
        Custody.assign(pet, npc, Custody.Role.COMPANION); // first: a change of owner strips worn gear
        pet.pet().care().fitHarnessFor(npc.getUUID());
        petId = pet.getUUID(); nextWalk = npc.level().getGameTime() + 100;
    }
    private boolean fair() {
        return npc.level().isDay() && !npc.level().isRaining() && !npc.resting() && !npc.isTrading() && npc.isAlive();
    }

    /** Server tick of the resident. */
    void tick() {
        if (petId == null) return;
        long now = npc.level().getGameTime(); var pet = pet();
        if (!walking) {
            long dayTime = npc.level().getDayTime() % 24000;
            if (pet == null || now < nextWalk || !fair()) return;
            if (dayTime < 500 || dayTime > 6500) return; // the outing begins in the morning and ends before dusk
            begin();
            return;
        }
        // after a reload the lead is restored by the pet a few ticks later: give it a moment before calling it dropped
        lostLead = pet != null && pet.getLeashHolder() == npc ? 0 : lostLead + 1;
        if (pet == null || !fair() || lostLead > 60) { end(); return; }
        if (!returning && now >= walkUntil) { returning = true; waypoint = null; }
        if (returning && npc.blockPosition().distSqr(npc.home()) <= 9 || now > walkUntil + 1500) end();
    }

    /** Leashes the pet and starts the outing; false when the pet is busy, young, away or unable to wear the lead. */
    boolean begin() {
        var pet = pet(); long now = npc.level().getGameTime();
        if (pet == null || walking) return false;
        if (pet.pet().busy() || !pet.pet().ownedBy(npc.getUUID()) || !pet.pet().isPet() || !pet.canBeLeashed() || pet.distanceToSqr(npc) > 400) { nextWalk = now + 400; return false; }
        if (!pet.onGround() || pet.isPassenger()) return false;
        if (pet.isLeashed()) pet.dropLeash(true, false);
        pet.setLeashedTo(npc, true);
        walking = true; returning = false; lostLead = 0; walkUntil = now + WALK_TICKS; waypoint = null; linger = 0; walked++;
        return true;
    }
    void end() {
        var pet = pet();
        if (pet != null && pet.getLeashHolder() == npc) pet.dropLeash(true, false);
        walking = false; returning = false; waypoint = null; npc.getNavigation().stop();
        nextWalk = npc.level().getGameTime() + COOLDOWN + npc.getRandom().nextInt(1500);
    }

    Goal goal() { return new StrollGoal(); }

    private final class StrollGoal extends Goal {
        StrollGoal() { setFlags(EnumSet.of(Flag.MOVE)); }
        @Override public boolean canUse() { return walking && fair(); }
        @Override public boolean canContinueToUse() { return canUse(); }
        @Override public boolean requiresUpdateEveryTick() { return false; }
        @Override public void tick() {
            var pet = pet(); if (pet == null) return;
            var nav = npc.getNavigation();
            // the lead is short: wait for a pet that lags or that cannot follow right now
            if (pet.distanceTo(npc) > LAG_BLOCKS || pet.life().hasUrgentNeed() || pet.pet().busy()) {
                nav.stop(); npc.getLookControl().setLookAt(pet, 30, 30); return;
            }
            if (returning) {
                BlockPos home = npc.home();
                if (npc.blockPosition().distSqr(home) > 9 && (nav.isDone() || npc.tickCount % 40 == 0)) nav.moveTo(home.getX() + .5, home.getY(), home.getZ() + .5, .55);
                return;
            }
            if (linger > 0) { linger--; return; }
            if (waypoint != null && nav.isDone()) { waypoint = null; linger = 30 + npc.getRandom().nextInt(70); return; }
            if (waypoint == null) {
                var pos = DefaultRandomPos.getPos(npc, 10, 4);
                if (pos == null) { linger = 20; return; }
                waypoint = BlockPos.containing(pos);
                nav.moveTo(pos.x, pos.y, pos.z, .55);
            }
        }
        @Override public void stop() { npc.getNavigation().stop(); }
    }

    void save(CompoundTag t) {
        if (petId != null) t.putUUID("WalkPet", petId);
        t.putBoolean("Walking", walking); t.putBoolean("WalkReturning", returning);
        t.putLong("WalkUntil", walkUntil); t.putLong("NextWalk", nextWalk); t.putLong("WalksDone", walked);
    }
    void load(CompoundTag t) {
        petId = t.hasUUID("WalkPet") ? t.getUUID("WalkPet") : null;
        walking = t.getBoolean("Walking"); returning = t.getBoolean("WalkReturning");
        walkUntil = t.getLong("WalkUntil"); nextWalk = t.getLong("NextWalk"); walked = t.getLong("WalksDone");
    }
}
