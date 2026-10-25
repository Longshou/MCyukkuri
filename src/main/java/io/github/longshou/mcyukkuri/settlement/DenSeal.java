package io.github.longshou.mcyukkuri.settlement;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.entity.YukkuriVariant;
import io.github.longshou.mcyukkuri.registry.ModBlocks;
import io.github.longshou.mcyukkuri.social.YukkuriPersonality.Trait;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.EventHooks;

/**
 * A clever, level-headed wild Reimu in her own den seals the mouth against a predator (project design, user request).
 * The Reimu must be a wild den-dweller with intelligence and self-control both at least 70 (the 0-100 personality axes),
 * a predator must be within 24 blocks and not yet at the door, and the doorway stock must hold one twig per cell to close.
 * She walks back to the doorway cell, then spends {@link #WORK_TICKS} ticks pushing twigs and leaves into the mouth with
 * her mouth; an actual attack, a predator at the door or a lost danger stops her with nothing spent. The result is a
 * {@link DenBarrierBlock} per open cell: a physical screen, not magic. Predators do not hunt anyone inside a sealed den
 * ({@code PredatorLife.huntable}); the owner takes the screen down when the danger has passed ({@link DenBarrierBlockEntity}).
 */
public final class DenSeal {
    public static final int MIN_INTELLIGENCE = 70, MIN_SELF_CONTROL = 70;
    static final int WORK_TICKS = 40, WALK_LIMIT = 100, RECHECK = 10, RETRY = 100, MAX_CELLS = 3;
    private static final double WATCH_SQR = 24 * 24, DOOR_SAFE_SQR = 9;
    private enum Phase { IDLE, WALK, WORK }

    private final YukkuriEntity y;
    private Phase phase = Phase.IDLE;
    private int ticks;
    private boolean done;
    private long nextCheck, lastDrive;
    private BlockPos den;
    private List<BlockPos> cells = List.of();

    public DenSeal(YukkuriEntity y) { this.y = y; }

    /** A cell of an open mouth the Reimu closes, or a reason why this den cannot be closed at all. */
    record Plan(List<BlockPos> cells, String refusal) {
        static Plan refuse(String reason) { return new Plan(List.of(), reason); }
    }

    public boolean active() { return phase != Phase.IDLE; }
    public String describe() { return phase == Phase.IDLE ? (done ? "done" : "idle") : phase.name().toLowerCase() + ":" + ticks; }

    /** A new threat episode starts clean; ending one while she works stops the work. */
    public void reset() {
        if (active()) abort("response_ended");
        done = false; nextCheck = 0;
    }

    public void abort(String reason) {
        if (!active()) return;
        GameplayLog.event(y, "threat", "den seal aborted reason={} phase={} ticks={} den={} cells={} twigs_spent=0", reason, phase, ticks, den, cells.size());
        phase = Phase.IDLE; ticks = 0; y.getNavigation().stop();
        nextCheck = y.level().getGameTime() + RETRY;
    }

    /** True while the sealing holds the body this tick; the caller then skips its own movement. */
    public boolean drive(LivingEntity danger) {
        if (!(y.level() instanceof ServerLevel level)) return false;
        if (!(danger instanceof YukkuriEntity predator && predator.isAlive() && predator.profile().dangerToYukkuri())) {
            abort("danger_lost"); return false;
        }
        long now = level.getGameTime();
        // Nobody drove the sealing for a while (the panic or threat goal ended in between): it was interrupted, not paused.
        if (active() && now - lastDrive > 10) abort("interrupted");
        lastDrive = now;
        if (phase == Phase.IDLE) {
            if (done || now < nextCheck) return false;
            // Near her door the check is frequent: she passes the doorway cell in a few ticks when running home.
            var near = y.habitat().shelter();
            nextCheck = now + (near != null && y.distanceToSqr(Vec3.atBottomCenterOf(near.getBlockPos())) < 36 ? 3 : RECHECK);
            if (!begin(level, predator)) return false;
        }
        var home = y.habitat().shelter();
        if (home == null || !home.owns(y.getUUID())) { abort("den_lost"); return false; }
        var mouth = center(home.entrance());
        if (predator.distanceToSqr(mouth) < DOOR_SAFE_SQR) { abort("danger_at_door"); return false; }
        ticks++;
        var door = Vec3.atBottomCenterOf(home.getBlockPos());
        if (phase == Phase.WALK) {
            double dx = door.x - y.getX(), dz = door.z - y.getZ();
            if (dx * dx + dz * dz <= .25 && Math.abs(door.y - y.getY()) < 1) { phase = Phase.WORK; ticks = 0; y.getNavigation().stop(); }
            else if (ticks > WALK_LIMIT) { abort("path_stuck"); return false; }
            else {
                if (ticks % 10 == 1 || y.getNavigation().isDone()) y.life().navigate(home.getBlockPos(), 1.2, false);
                return true;
            }
        }
        y.getNavigation().stop();
        y.getLookControl().setLookAt(mouth.x, mouth.y + .2, mouth.z, 30, 30);
        if (ticks % 8 == 4) {
            level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, Blocks.OAK_LEAVES.defaultBlockState()),
                    mouth.x, mouth.y + .3, mouth.z, 3, .25, .15, .25, .02);
            level.playSound(null, y.blockPosition(), SoundType.GRASS.getPlaceSound(), SoundSource.NEUTRAL, .5f, 1.1f);
        }
        if (ticks >= WORK_TICKS) complete(level, home, predator);
        return true;
    }

    private boolean begin(ServerLevel level, YukkuriEntity predator) {
        if (y.getVariant() != YukkuriVariant.REIMU || !y.habitat().wildLiver() || y.life().isSleeping()) return false;
        var home = y.habitat().shelter();
        if (home == null || home.kind() != ShelterBlock.Kind.BURROW || !home.owns(y.getUUID()) || !home.contains(y.blockPosition())) return false;
        if (predator.distanceToSqr(y) > WATCH_SQR) return false;
        int wit = y.life().personality().trait(Trait.INTELLIGENCE), control = y.life().personality().trait(Trait.SELF_CONTROL);
        if (wit < MIN_INTELLIGENCE || control < MIN_SELF_CONTROL) {
            GameplayLog.limited(y, "threat", "seal_traits", 1200, "den seal skipped reason=traits_low intelligence={} self_control={} need={}/{} danger={}",
                    wit, control, MIN_INTELLIGENCE, MIN_SELF_CONTROL, predator.getUUID());
            done = true; return false;
        }
        if (!EventHooks.canEntityGrief(level, y)) {
            GameplayLog.limited(y, "threat", "seal_griefing", 1200, "den seal skipped reason=mob_griefing_off den={}", home.getBlockPos());
            done = true; return false;
        }
        var plan = planFor(level, home);
        if (plan.refusal() != null) {
            if (!plan.refusal().equals("already_sealed"))
                GameplayLog.limited(y, "threat", "seal_plan", 1200, "den seal skipped reason={} den={}", plan.refusal(), home.getBlockPos());
            done = true; return false;
        }
        if (home.branches() < plan.cells().size()) {
            GameplayLog.limited(y, "threat", "seal_twigs", 600, "den seal skipped reason=no_twigs have={} need={} den={}",
                    home.branches(), plan.cells().size(), home.getBlockPos());
            nextCheck = level.getGameTime() + RETRY; return false;
        }
        if (predator.distanceToSqr(center(home.entrance())) < DOOR_SAFE_SQR) {
            GameplayLog.limited(y, "threat", "seal_door", 200, "den seal waiting reason=danger_at_door danger={} den={}", predator.getUUID(), home.getBlockPos());
            return false;
        }
        if (occupied(level, plan.cells())) {
            GameplayLog.limited(y, "threat", "seal_cell", 200, "den seal waiting reason=cell_occupied den={}", home.getBlockPos());
            return false;
        }
        den = home.getBlockPos().immutable(); cells = plan.cells();
        Vec3 door = Vec3.atBottomCenterOf(den);
        double dx = door.x - y.getX(), dz = door.z - y.getZ();
        phase = dx * dx + dz * dz <= .25 && Math.abs(door.y - y.getY()) < 1 ? Phase.WORK : Phase.WALK; ticks = 0;
        GameplayLog.event(y, "threat", "den seal started den={} cells={} twigs={} danger={} danger_distance={} intelligence={} self_control={} phase={}",
                den, cells, home.branches(), predator.getUUID(), Math.round(predator.distanceTo(y)), wit, control, phase);
        return true;
    }

    private void complete(ServerLevel level, ShelterBlockEntity home, YukkuriEntity predator) {
        var plan = planFor(level, home);
        if (plan.refusal() != null || !plan.cells().equals(cells) || home.branches() < cells.size() || occupied(level, cells)) {
            abort(plan.refusal() != null ? plan.refusal() : home.branches() < cells.size() ? "twigs_gone" : "cells_changed"); return;
        }
        int before = home.branches(), placed = 0;
        for (var cell : cells) {
            if (!home.takeBranch()) break;
            level.setBlock(cell, ModBlocks.DEN_BARRIER.get().defaultBlockState(), 3);
            if (level.getBlockEntity(cell) instanceof DenBarrierBlockEntity barrier) barrier.lay(y, home);
            level.playSound(null, cell, SoundType.GRASS.getPlaceSound(), SoundSource.BLOCKS, 1f, .9f);
            placed++;
        }
        GameplayLog.event(y, "threat", "den seal completed den={} cells={} placed={} twigs {} -> {} danger={} danger_distance={} work_ticks={}",
                den, cells, placed, before, home.branches(), predator.getUUID(), Math.round(predator.distanceTo(y)), ticks);
        phase = Phase.IDLE; ticks = 0; done = true;
    }

    private boolean occupied(ServerLevel level, List<BlockPos> targets) {
        for (var cell : targets)
            if (!level.getEntitiesOfClass(LivingEntity.class, new AABB(cell), e -> e != y && e.isAlive()).isEmpty()) return true;
        return false;
    }

    private static Vec3 center(BlockPos pos) { return Vec3.atCenterOf(pos); }

    /** Every open cell of the den mouth (the outer cell first, then any open side of the doorway cell), or why it cannot be closed. */
    static Plan planFor(ServerLevel level, ShelterBlockEntity home) {
        BlockPos core = home.getBlockPos(), mouth = home.entrance();
        if (mouth.getY() != core.getY() || mouth.distManhattan(core) != 1) return Plan.refuse("open_pit");
        if (!NaturalShelter.roof(level, core)) return Plan.refuse("no_roof");
        if (level.getBlockState(mouth).getBlock() instanceof DenBarrierBlock) return Plan.refuse("already_sealed");
        var found = new ArrayList<BlockPos>();
        found.add(mouth.immutable());
        for (Direction side : Direction.Plane.HORIZONTAL) {
            BlockPos next = core.relative(side);
            if (next.equals(mouth) || home.contains(next) || !level.hasChunkAt(next)) continue;
            if (!level.getBlockState(next).getCollisionShape(level, next).isEmpty() || NaturalShelter.enclosed(level, next)) continue;
            found.add(next.immutable());
        }
        if (found.size() > MAX_CELLS) return Plan.refuse("mouth_too_wide");
        for (var cell : found) {
            var state = level.getBlockState(cell);
            if (!state.getFluidState().isEmpty()) return Plan.refuse("mouth_flooded");
            if (!state.isAir() && !state.canBeReplaced()) return Plan.refuse("mouth_not_free");
        }
        return new Plan(List.copyOf(found), null);
    }
}
