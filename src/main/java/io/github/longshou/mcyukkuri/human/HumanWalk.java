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
 * A resident out walking its own pet on the lead (docs/FEATURES.md). The pet is an ordinary companion owned by the resident
 * (custody Owner = the resident, Use = companion, harness on), so the pet systems treat it as any owner's pet: the lead needs the harness, and the walk
 * counts blocks in {@code PetWalk} exactly as it does for a player (boredom, affection, stress, energy; the pet follows through the vanilla lead).
 *
 * Only an explicit walker request fits the lead. Ordinary street outings use the resident's street stroll.
 */
final class HumanWalk {
    static final int WALK_TICKS = 3600, LAG_BLOCKS = 5;
    private final HumanNpc npc;
    private UUID petId;
    private UUID legacyLedPet;
    private boolean walking, returning;
    private long walkUntil;
    private BlockPos waypoint;
    private int linger, lostLead;

    HumanWalk(HumanNpc npc) { this.npc = npc; }

    boolean walking() { return walking; }

    YukkuriEntity pet() {
        return petId != null && npc.level() instanceof ServerLevel level && level.getEntity(petId) instanceof YukkuriEntity y && y.isAlive() ? y : null;
    }
    /** The resident takes the individual as its own companion and fits the harness. */
    void adopt(YukkuriEntity pet) {
        Custody.assign(pet, npc, Custody.Role.COMPANION); // first: a change of owner strips worn gear
        pet.pet().care().fitHarnessFor(npc.getUUID());
    }
    private boolean fair() {
        return npc.level().isDay() && !npc.level().isRaining() && !npc.resting() && !npc.isTrading() && npc.isAlive();
    }

    /** Server tick of the resident. */
    void tick() {
        if (!walking) return;
        long now = npc.level().getGameTime(); var pet = pet();
        // after a reload the lead is restored by the pet a few ticks later: give it a moment before calling it dropped
        lostLead = pet != null && pet.getLeashHolder() == npc ? 0 : lostLead + 1;
        if (pet == null || !fair() || lostLead > 60) { end(); return; }
        if (!returning && now >= walkUntil) { returning = true; waypoint = null; }
        if (returning && npc.blockPosition().distSqr(npc.home()) <= 9 || now > walkUntil + 1500) end();
    }

    /** Leashes the pet and starts the outing; false when the pet is busy, young, away or unable to wear the lead. */
    boolean begin(YukkuriEntity pet) {
        if (walking || pet.pet().busy() || !pet.pet().ownedBy(npc.getUUID()) || !pet.canBeLeashed()
                || pet.isLeashed() || pet.isPassenger() || pet.distanceToSqr(npc) > 400) return false;
        petId = pet.getUUID();
        pet.setLeashedTo(npc, true);
        walking = true; returning = false; lostLead = 0; walkUntil = npc.level().getGameTime() + WALK_TICKS; waypoint = null; linger = 0;
        return true;
    }
    void end() {
        var pet = pet();
        if (pet != null && pet.getLeashHolder() == npc) pet.dropLeash(true, false);
        walking = false; returning = false; waypoint = null; npc.getNavigation().stop();
        petId = null;
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
        if (legacyLedPet != null) t.putUUID("LegacyLedPet", legacyLedPet);
        if (walking) {
            t.putUUID("LedPet", petId);
            t.putBoolean("WalkReturning", returning);
            t.putLong("WalkUntil", walkUntil);
        }
    }
    void load(CompoundTag t) {
        // The old walk could end while its pet was unloaded, leaving the lead in the pet's save.
        legacyLedPet = t.hasUUID("LegacyLedPet") ? t.getUUID("LegacyLedPet")
                : t.hasUUID("WalkPet") ? t.getUUID("WalkPet") : null;
        walking = t.hasUUID("LedPet");
        petId = walking ? t.getUUID("LedPet") : null;
        returning = t.getBoolean("WalkReturning");
        walkUntil = t.getLong("WalkUntil");
    }

    void releaseLegacyLead(YukkuriEntity pet) {
        if (!pet.getUUID().equals(legacyLedPet)) return;
        legacyLedPet = null;
        if (!pet.getUUID().equals(petId)) pet.dropLeash(true, false);
    }
}
