package io.github.longshou.mcyukkuri.settlement;

import io.github.longshou.mcyukkuri.entity.Behaviors;
import io.github.longshou.mcyukkuri.entity.YukkuriAction;
import io.github.longshou.mcyukkuri.entity.YukkuriComponents;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.life.SurfaceApproach;
import io.github.longshou.mcyukkuri.registry.ModBlocks;
import io.github.longshou.mcyukkuri.social.Bonds;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.EventHooks;

/** A skilled den resident uses the real doorway stock to close a burrow against danger or during a family outing. */
public final class DenSeal {
    static final int WORK_TICKS = 40, WALK_LIMIT = 100, RECHECK = 10, RETRY = 100, MAX_CELLS = 3;
    private static final double WATCH_SQR = 24 * 24, DOOR_SAFE_SQR = 9;
    // Start after the parent leaves the doorstep. During work, cancel if they actually approach the mouth again.
    private static final double DEPARTED_SQR = 5 * 5, RETURN_SQR = 4.5 * 4.5;
    private static final double MOUTH_APPROACH_SQR = 2 * 2;
    private enum Phase { IDLE, WALK, WORK }

    private final YukkuriEntity y;
    private Phase phase = Phase.IDLE;
    private int ticks;
    private boolean done;
    private long nextCheck, lastDrive;
    private BlockPos den;
    private List<BlockPos> cells = List.of();
    private List<UUID> absent = List.of(), considered = List.of();

    public DenSeal(YukkuriEntity y) { this.y = y; }

    /** The cells of an open mouth, or the reason it cannot be closed. */
    record Plan(List<BlockPos> cells, String refusal) {
        static Plan refuse(String reason) { return new Plan(List.of(), reason); }
    }

    public boolean active() { return phase != Phase.IDLE; }
    public String describe() { return phase == Phase.IDLE ? (done ? "done" : "idle") : phase.name().toLowerCase() + ":" + ticks; }
    public String familyActivity() { return phase == Phase.WORK ? Behaviors.NEST_PRESS : Behaviors.RETURN_HOME; }

    /** A new threat episode starts clean; ending one while she works stops the work. */
    public void reset() {
        if (active() && absent.isEmpty()) abort("response_ended");
        done = false; nextCheck = 0;
    }

    public void abort(String reason) {
        if (!active()) return;
        GameplayLog.event(y, "home", "den seal aborted reason={} phase={} ticks={} den={} cells={} twigs_spent=0", reason, phase, ticks, den, cells.size());
        phase = Phase.IDLE; ticks = 0; SurfaceApproach.halt(y);
        nextCheck = y.level().getGameTime() + RETRY;
    }

    public boolean drive(LivingEntity danger) {
        if (!(danger instanceof YukkuriEntity predator && predator.isAlive() && predator.profile().dangerToYukkuri())) {
            abort("danger_lost"); return false;
        }
        var level = (ServerLevel) y.level();
        long now = level.getGameTime();
        if (active() && (!absent.isEmpty() || now - lastDrive > RECHECK)) abort("interrupted");
        lastDrive = now;
        var home = y.habitat().shelter();
        if (phase == Phase.IDLE) {
            if (done || now < nextCheck || predator.distanceToSqr(y) > WATCH_SQR) return false;
            nextCheck = now + (home != null && y.distanceToSqr(Vec3.atBottomCenterOf(home.getBlockPos())) < 36 ? 3 : RECHECK);
            if (!canSeal(home) || !home.contains(y.blockPosition()) || !begin(level, home, predator, List.of())) return false;
        }
        if (predator.distanceToSqr(y) > WATCH_SQR) { abort("danger_lost"); return false; }
        return work(level, home, predator);
    }

    private boolean available() {
        return y.isAlive() && !y.isNoAi() && !y.isPassenger() && !y.isOnFire() && !y.isInWaterOrBubble()
                && !y.life().isSleeping() && !y.life().sedated() && !y.operations().active() && !y.body().weak() && !y.body().dying()
                && !SettlementEvents.shelterBarred(y) && y.habitat().wildLiver()
                && y.settlement().role() != SettlementData.Role.UNUN_SLAVE;
    }

    private boolean canSeal(ShelterBlockEntity home) {
        return y.childhood().knowsDenSeal() && y.childhood().canLearnDenSeal() && available()
                && home != null && home.kind() == ShelterBlock.Kind.BURROW && home.owns(y.getUUID())
                && y.life().mealTicks() == 0 && y.component(YukkuriComponents.MOUTH).freeForExternalWork()
                && y.forageTrip().load().isEmpty() && y.habitat().carrying().isEmpty();
    }

    private boolean work(ServerLevel level, ShelterBlockEntity home, YukkuriEntity predator) {
        if (!canSeal(home) || !den.equals(home.getBlockPos())) { abort("den_or_body_changed"); return false; }
        var mouth = Vec3.atCenterOf(home.entrance());
        if (predator != null && predator.distanceToSqr(mouth) < DOOR_SAFE_SQR) { abort("danger_at_door"); return false; }
        ticks++;
        if (phase == Phase.WALK) {
            var step = SurfaceApproach.walkResult(y, Vec3.atBottomCenterOf(den), null, 1.2);
            if (step == SurfaceApproach.Result.BLOCKED || ticks > WALK_LIMIT) {
                abort("path_stuck");
                if (predator != null) done = true;
                return false;
            }
            if (step != SurfaceApproach.Result.ARRIVED) return true;
            phase = Phase.WORK; ticks = 0;
        }
        if (!home.contains(y.blockPosition())) { abort("left_den"); return false; }
        SurfaceApproach.halt(y);
        SurfaceApproach.face(y, home.facing().toYRot());
        y.getLookControl().setLookAt(mouth.x, mouth.y + .2, mouth.z, 30, 30);
        if (ticks % 8 == 4) {
            level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, Blocks.OAK_LEAVES.defaultBlockState()),
                    mouth.x, mouth.y + .3, mouth.z, 3, .25, .15, .25, .02);
            level.playSound(null, y.blockPosition(), SoundType.GRASS.getPlaceSound(), SoundSource.NEUTRAL, .5f, 1.1f);
        }
        if (ticks >= WORK_TICKS) complete(level, home, predator);
        return true;
    }

    private boolean begin(ServerLevel level, ShelterBlockEntity home, YukkuriEntity predator, List<UUID> parents) {
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
        if (predator != null && predator.distanceToSqr(Vec3.atCenterOf(home.entrance())) < DOOR_SAFE_SQR) {
            GameplayLog.limited(y, "threat", "seal_door", 200, "den seal waiting reason=danger_at_door danger={} den={}", predator.getUUID(), home.getBlockPos());
            return false;
        }
        // On a family outing the sealer may still be standing in its own doorway as it heads inside.
        if (occupied(level, plan.cells(), y)) {
            GameplayLog.limited(y, "threat", "seal_cell", 200, "den seal waiting reason=cell_occupied den={}", home.getBlockPos());
            return false;
        }
        den = home.getBlockPos().immutable(); cells = plan.cells(); absent = parents;
        phase = Phase.WALK; ticks = 0;
        GameplayLog.event(y, "home", "den seal started den={} cells={} twigs={} danger={} absent={}",
                den, cells, home.branches(), predator == null ? null : predator.getUUID(), absent);
        return true;
    }

    private void complete(ServerLevel level, ShelterBlockEntity home, YukkuriEntity predator) {
        if (!EventHooks.canEntityGrief(level, y)) { abort("mob_griefing_off"); return; }
        var plan = planFor(level, home);
        if (plan.refusal() != null || !plan.cells().equals(cells) || home.branches() < cells.size() || occupied(level, cells, null)) {
            abort(plan.refusal() != null ? plan.refusal() : home.branches() < cells.size() ? "twigs_gone" : "cells_changed"); return;
        }
        // The screen itself occludes some viewpoints, so collect witnesses just before placing it.
        var witnesses = y.family().adult() ? residents(level, home).stream().filter(child -> !child.family().adult()
                && child.childhood().canLearnDenSeal() && !child.childhood().knowsDenSeal()
                && y.family().careParentOf(child) && !child.life().isSleeping() && !child.isPanicking()
                && child.distanceToSqr(y) <= 16 && child.hasLineOfSight(y)).toList() : List.<YukkuriEntity>of();
        int before = home.branches(), placed = 0;
        for (var cell : cells) {
            if (!level.setBlock(cell, ModBlocks.DEN_BARRIER.get().defaultBlockState(), 3)) break;
            home.takeBranch();
            ((DenBarrierBlockEntity) level.getBlockEntity(cell)).lay(y, home, absent);
            level.playSound(null, cell, SoundType.GRASS.getPlaceSound(), SoundSource.BLOCKS, 1f, .9f);
            placed++;
        }
        GameplayLog.event(y, "home", "den seal {} den={} cells={} placed={} twigs {} -> {} danger={} absent={} work_ticks={}",
                placed == cells.size() ? "completed" : "placement_refused", den, cells, placed, before, home.branches(),
                predator == null ? null : predator.getUUID(), absent, ticks);
        if (placed == cells.size()) for (var child : witnesses) child.childhood().sawDenSeal(y);
        phase = Phase.IDLE; ticks = 0; done = predator != null;
    }

    private boolean occupied(ServerLevel level, List<BlockPos> targets, LivingEntity walkingIn) {
        for (var cell : targets)
            if (!level.getEntitiesOfClass(LivingEntity.class, new AABB(cell),
                    e -> e != walkingIn && e.isAlive() && !e.isSpectator()).isEmpty()) return true;
        return false;
    }

    private static List<YukkuriEntity> residents(ServerLevel level, ShelterBlockEntity home) {
        return level.getEntitiesOfClass(YukkuriEntity.class, home.interior(),
                other -> other.isAlive() && home.owns(other.getUUID()) && home.contains(other.blockPosition()));
    }

    private static YukkuriEntity resident(ServerLevel level, ShelterBlockEntity home, UUID id) {
        return id != null && home.owns(id) && level.getEntity(id) instanceof YukkuriEntity other && other.isAlive() ? other : null;
    }

    private static boolean departed(YukkuriEntity other, ShelterBlockEntity home, double clearDistanceSqr) {
        return other != null && other.forageTrip().outing() && !home.contains(other.blockPosition())
                && other.distanceToSqr(Vec3.atCenterOf(home.entrance())) > clearDistanceSqr;
    }

    private List<UUID> departure(ServerLevel level, ShelterBlockEntity home, double clearDistanceSqr) {
        if (y.forageTrip().outing()) return List.of();
        var inside = residents(level, home);
        // A shared den stays open while another adult is using it.
        if (inside.stream().anyMatch(other -> other != y && other.family().adult())) return List.of();
        if (y.family().adult()) {
            var partner = resident(level, home, y.family().partner());
            return departed(partner, home, clearDistanceSqr) && inside.stream().anyMatch(child -> child != y
                    && !child.family().adult() && y.family().careParentOf(child) && partner.family().careParentOf(child))
                    ? List.of(partner.getUUID()) : List.of();
        }
        if (!home.contains(y.blockPosition())) return List.of();
        var mother = resident(level, home, y.family().mother());
        var father = resident(level, home, y.family().father());
        if (!departed(mother, home, clearDistanceSqr) || !departed(father, home, clearDistanceSqr)) return List.of();
        var oldest = inside.stream().filter(child -> child.family().subadult() && child.childhood().knowsDenSeal()
                && Objects.equals(child.family().mother(), mother.getUUID()) && Objects.equals(child.family().father(), father.getUUID()))
                .max(Comparator.comparingLong((YukkuriEntity child) -> child.family().age()).thenComparing(YukkuriEntity::getUUID));
        return oldest.orElseThrow() == y ? List.of(mother.getUUID(), father.getUUID()) : List.of();
    }

    private boolean startFamily(ServerLevel level, ShelterBlockEntity home) {
        // Remember a decision until the actual trips end, including while a parent is approaching the door.
        if (!considered.isEmpty()) considered = considered.stream().filter(id -> level.getEntity(id) instanceof YukkuriEntity parent
                && parent.isAlive() && parent.forageTrip().outing()).toList();
        if (active()) {
            if (level.getGameTime() - lastDrive <= RECHECK) return false;
            abort("interrupted");
        }
        if (!canSeal(home) || DenBarrier.sealed(home) || level.getGameTime() < nextCheck) return false;
        nextCheck = level.getGameTime() + RECHECK;
        if (!home.contains(y.blockPosition()) && y.distanceToSqr(Vec3.atCenterOf(home.entrance())) > RETURN_SQR) return false;
        var parents = departure(level, home, DEPARTED_SQR);
        if (parents.isEmpty() || parents.equals(considered)) return false;
        considered = parents;
        var traits = y.life().personality();
        double chance = traits.ruleProbability() * (.20 + .008 * traits.selfControl());
        double roll = y.getRandom().nextDouble();
        GameplayLog.event(y, "home", "den seal remembered den={} absent={} self_control={} chance={} roll={} remembered={}",
                home.getBlockPos(), parents, traits.selfControl(), chance, roll, roll < chance);
        return roll < chance && begin(level, home, null, parents);
    }

    /** Family work yields to ordinary life needs and threat goals. The same worker owns the predator route above. */
    public static final class FamilyGoal extends Goal {
        private final YukkuriEntity y;
        private DenBarrierBlockEntity opening;
        private BlockPos stand;
        private BlockPos refusedFrom;
        private int walkTicks;

        public FamilyGoal(YukkuriEntity y) {
            this.y = y;
            setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
        }

        private DenSeal seal() { return y.status().reactions().threat().seal(); }

        @Override public boolean canUse() {
            if (!(y.level() instanceof ServerLevel level) || y.isPanicking() || y.life().hasUrgentNeed() || !seal().available()) return false;
            var home = y.habitat().shelter();
            if (home != null && y.family().adult() && home.owns(y.getUUID()) && !home.contains(y.blockPosition())
                    && y.distanceToSqr(Vec3.atCenterOf(home.entrance())) <= RETURN_SQR
                    && level.getBlockEntity(home.entrance()) instanceof DenBarrierBlockEntity barrier && barrier.canOpen(y)) {
                var outer = home.entrance().relative(home.facing());
                if (y.blockPosition().equals(refusedFrom) || !y.life().reachable(outer, false)) return false;
                opening = barrier; stand = outer; walkTicks = 0;
                return true;
            }
            return seal().startFamily(level, home);
        }

        @Override public boolean canContinueToUse() {
            return !y.isPanicking() && !y.life().hasUrgentNeed() && seal().available()
                    && (opening == null ? seal().active() : walkTicks < WALK_LIMIT && opening.canOpen(y));
        }

        @Override public boolean requiresUpdateEveryTick() { return true; }

        @Override public void tick() {
            if (opening != null) {
                var step = SurfaceApproach.walkResult(y, Vec3.atBottomCenterOf(stand), null, 1);
                walkTicks++;
                if (step == SurfaceApproach.Result.ARRIVED) {
                    refusedFrom = null;
                    var mouth = Vec3.atCenterOf(opening.getBlockPos());
                    SurfaceApproach.face(y, SurfaceApproach.yaw(y.position(), mouth));
                    y.getLookControl().setLookAt(mouth.x, mouth.y, mouth.z, 30, 30);
                    y.showReaction(YukkuriAction.BOW, "happy", null);
                    opening.open(y);
                } else if (step == SurfaceApproach.Result.BLOCKED || walkTicks >= WALK_LIMIT) {
                    refusedFrom = y.blockPosition().immutable();
                    walkTicks = WALK_LIMIT;
                    GameplayLog.event(y, "home", "den barrier opening refused reason=path_stuck mouth={}", opening.getBlockPos());
                }
                return;
            }
            var seal = seal();
            var level = (ServerLevel) y.level();
            var home = y.habitat().shelter();
            if (home != null && (seal.ticks % RECHECK == 0 || seal.ticks + 1 >= WORK_TICKS)
                    && !seal.absent.equals(seal.departure(level, home, MOUTH_APPROACH_SQR))) seal.abort("departure_changed");
            else seal.work(level, home, null);
        }

        @Override public void stop() {
            if (opening == null) seal().abort("family_work_interrupted");
            opening = null;
            SurfaceApproach.halt(y);
        }
    }

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
