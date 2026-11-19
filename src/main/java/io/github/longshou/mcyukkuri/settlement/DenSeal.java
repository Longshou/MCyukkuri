package io.github.longshou.mcyukkuri.settlement;

import io.github.longshou.mcyukkuri.cognition.Ability;
import io.github.longshou.mcyukkuri.entity.Behaviors;
import io.github.longshou.mcyukkuri.entity.YukkuriAction;
import io.github.longshou.mcyukkuri.entity.YukkuriComponents;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.life.Duty.End;
import io.github.longshou.mcyukkuri.life.Duty.Kind;
import io.github.longshou.mcyukkuri.life.SurfaceApproach;
import io.github.longshou.mcyukkuri.motor.BodyAbility;
import io.github.longshou.mcyukkuri.registry.ModBlocks;
import io.github.longshou.mcyukkuri.social.Bonds;
import io.github.longshou.mcyukkuri.social.YukkuriPersonality;
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

/** A skilled den resident uses the real doorway stock to close a burrow against any danger that drives it home, or during a family outing. */
public final class DenSeal {
    public static final int WORK_TICKS = 40;
    // Allow one press-length for another resident to pass through the narrow mouth.
    private static final int DOORWAY_WAIT = WORK_TICKS, RECHECK = 10, RETRY = 100, MAX_CELLS = 3;
    private static final double WORK_PACE = 1.2;
    public enum Side { INSIDE, OUTSIDE }
    private static final double WATCH_SQR = 24 * 24, DOOR_SAFE_SQR = 9;
    // Start after the parent leaves the doorstep. During work, cancel if they actually approach the mouth again.
    private static final double DEPARTED_SQR = 5 * 5, RETURN_SQR = 4.5 * 4.5;
    private static final double MOUTH_APPROACH_SQR = 2 * 2;
    private enum Phase { IDLE, WALK, WORK }
    private enum ThreatDecision { UNDECIDED, SEAL, DONE }

    private final YukkuriEntity y;
    private Phase phase = Phase.IDLE;
    private int ticks;
    private ThreatDecision threatDecision = ThreatDecision.UNDECIDED;
    private long nextCheck, lastDrive;
    private BlockPos den;
    private UUID dangerId;
    private record Stand(Vec3 at, double route) {}
    private Stand stand;
    private int walkBudget, paused;
    private List<BlockPos> cells = List.of();
    private List<UUID> absent = List.of(), considered = List.of();
    private boolean remembered;

    public DenSeal(YukkuriEntity y) { this.y = y; }

    /** The cells of an open mouth, or the reason it cannot be closed. */
    record Plan(List<BlockPos> cells, String refusal) {
        static Plan refuse(String reason) { return new Plan(List.of(), reason); }
    }

    private record Demonstration(YukkuriEntity learner, String missed) {}

    public boolean active() { return phase != Phase.IDLE; }
    public boolean finishingThreatSeal() {
        return active() && dangerId != null && y.level().getGameTime() - lastDrive <= RECHECK;
    }
    public boolean pressing() { return phase == Phase.WORK; }
    public String describe() { return phase == Phase.IDLE ? (threatDecision == ThreatDecision.DONE ? "done" : "idle") : phase.name().toLowerCase() + ":" + ticks; }
    public String familyActivity() { return pressing() ? Behaviors.NEST_PRESS : Behaviors.RETURN_HOME; }

    /** A new threat episode starts clean; ending one while she works stops the work. */
    public void reset() {
        if (active() && absent.isEmpty()) abort("response_ended");
        dangerId = null;
        threatDecision = ThreatDecision.UNDECIDED; nextCheck = 0;
    }

    public void abort(String reason) {
        if (!active()) return;
        if (!absent.isEmpty()) y.duty().release(Kind.SEAL, End.FAILED, reason);
        boolean spent = !reason.equals("doorway_held");
        if (spent && !absent.isEmpty()) remembered = false;
        if (spent && absent.isEmpty()) threatDecision = ThreatDecision.DONE;
        GameplayLog.event(y, "home", "den seal aborted reason={} phase={} ticks={} den={} cells={} spent={} twigs_spent=0",
                reason, phase, ticks, den, cells.size(), spent);
        phase = Phase.IDLE; ticks = 0; dangerId = null; SurfaceApproach.halt(y);
        nextCheck = y.level().getGameTime() + RETRY;
    }

    public boolean drive(LivingEntity reported) {
        var level = (ServerLevel) y.level();
        long now = level.getGameTime();
        if (active() && (!absent.isEmpty() || now - lastDrive > RECHECK)) abort("interrupted");
        lastDrive = now;
        // Vanilla may forget the attacker partway through the work. Only an active seal needs to carry that memory.
        var danger = reported != null ? reported : dangerAt(level);
        if (danger == null || !danger.isAlive()) { abort("danger_lost"); return false; }
        var home = y.habitat().shelter();
        if (phase == Phase.IDLE) {
            if (threatDecision == ThreatDecision.DONE || now < nextCheck || danger.distanceToSqr(y) > WATCH_SQR) return false;
            nextCheck = now + (home != null && y.distanceToSqr(Vec3.atBottomCenterOf(home.getBlockPos())) < 36 ? 3 : RECHECK);
            if (!canSeal(home) || !home.contains(y.blockPosition())) return false;
            // A member of the household still outside is not shut out; the sealer keeps the mouth open while it waits.
            if (familyOutside(level, home)) {
                GameplayLog.limited(y, "threat", "seal_family_outside", 200, "den seal waiting reason=family_outside den={}", home.getBlockPos());
                return false;
            }
            if (!decidesToSeal(danger, home) || !begin(level, home, danger, List.of())) return false;
        }
        if (danger.distanceToSqr(y) > WATCH_SQR) { abort("danger_lost"); return false; }
        dangerId = danger.getUUID();
        return work(level, home, danger);
    }

    private LivingEntity dangerAt(ServerLevel level) {
        return dangerId != null && level.getEntity(dangerId) instanceof LivingEntity other ? other : null;
    }

    /** Skill and self-control decide once per danger episode whether the frightened resident does the extra work. */
    private boolean decidesToSeal(LivingEntity danger, ShelterBlockEntity home) {
        if (threatDecision != ThreatDecision.UNDECIDED) return threatDecision == ThreatDecision.SEAL;
        var traits = y.life().personality();
        double chance = chance(traits), roll = y.getRandom().nextDouble();
        boolean seals = roll < chance;
        GameplayLog.event(y, "home", "den seal decided den={} danger={} self_control={} chance={} roll={} seals={}",
                home.getBlockPos(), danger.getUUID(), traits.selfControl(), chance, roll, seals);
        threatDecision = seals ? ThreatDecision.SEAL : ThreatDecision.DONE;
        return seals;
    }

    /** The shared remembered-rule chance: the family outing and the threat response draw the same number. */
    private static double chance(YukkuriPersonality traits) {
        return traits.ruleProbability() * (.20 + .008 * traits.selfControl());
    }

    /** A living member of this den is outside it and near: the mouth stays open for it. */
    private boolean familyOutside(ServerLevel level, ShelterBlockEntity home) {
        var mouth = Vec3.atCenterOf(home.entrance());
        for (var id : home.residentIds()) {
            if (level.getEntity(id) instanceof YukkuriEntity other && other != y && other.isAlive()
                    && !home.contains(other.blockPosition()) && other.distanceToSqr(mouth) <= WATCH_SQR) return true;
        }
        return false;
    }

    private boolean available() {
        return y.isAlive() && !y.isNoAi() && !y.isPassenger() && !y.isOnFire() && !y.isInWaterOrBubble()
                && !y.life().isSleeping() && !y.life().sedated() && !y.operations().active() && !y.body().weak() && !y.body().dying()
                && !SettlementEvents.shelterBarred(y) && y.habitat().wildLiver()
                && y.settlement().role() != SettlementData.Role.UNUN_SLAVE;
    }

    private boolean canSeal(ShelterBlockEntity home) {
        return y.skills().knows(Ability.DEN_SEAL) && y.childhood().canLearnDenSeal() && available()
                && home != null && home.kind() == ShelterBlock.Kind.BURROW && home.owns(y.getUUID())
                && y.life().mealTicks() == 0 && y.component(YukkuriComponents.MOUTH).freeForExternalWork()
                && y.forageTrip().load().isEmpty() && y.habitat().carrying().isEmpty();
    }

    private boolean work(ServerLevel level, ShelterBlockEntity home, LivingEntity danger) {
        if (!canSeal(home) || !den.equals(home.getBlockPos())) { abort("den_or_body_changed"); return false; }
        var mouth = Vec3.atCenterOf(home.entrance());
        if (danger != null && danger.distanceToSqr(mouth) < DOOR_SAFE_SQR) { abort("danger_at_door"); return false; }
        if (phase == Phase.WALK) {
            var step = SurfaceApproach.walkResult(y, stand.at(), null, WORK_PACE);
            if (step == SurfaceApproach.Result.ARRIVED) {
                phase = Phase.WORK;
                ticks = 0;
            } else {
                if (step == SurfaceApproach.Result.BLOCKED || ++ticks >= walkBudget) abort("path_stuck");
                return active();
            }
        }
        if (!SurfaceApproach.at(y, stand.at()) || !clearOfScreen(y.getBoundingBox(), cells)) { abort("left_stand"); return false; }
        var occupant = occupant(level, cells);
        if (occupant != null) {
            y.getLookControl().setLookAt(occupant, 30, 30);
            GameplayLog.limited(y, "home", "doorway_occupied", DOORWAY_WAIT,
                    "den seal paused reason=doorway_occupied occupant={}", occupant.getUUID());
            if (++paused >= DOORWAY_WAIT) abort("doorway_held");
            return active();
        }
        ticks++;
        SurfaceApproach.halt(y);
        SurfaceApproach.face(y, SurfaceApproach.yaw(y.position(), mouth));
        y.getLookControl().setLookAt(mouth.x, mouth.y + .2, mouth.z, 30, 30);
        if (ticks % 8 == 4) {
            level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, Blocks.OAK_LEAVES.defaultBlockState()),
                    mouth.x, mouth.y + .3, mouth.z, 3, .25, .15, .25, .02);
            level.playSound(null, y.blockPosition(), SoundType.GRASS.getPlaceSound(), SoundSource.NEUTRAL, .5f, 1.1f);
        }
        if (ticks >= y.childhood().denSealPressTicks()) complete(level, home, danger);
        return true;
    }

    private Stand standFor(ShelterBlockEntity home, List<BlockPos> targets, Side side) {
        if (side == Side.OUTSIDE && targets.size() != 1) return null;
        var axis = Vec3.atLowerCornerOf(home.facing().getNormal());
        var center = Vec3.atBottomCenterOf(side == Side.INSIDE ? home.getBlockPos() : home.entrance().relative(home.facing()));
        double edge = .5 - y.getBbWidth() / 2 - .05;
        var candidates = targets.size() > 1 ? List.of(center)
                : List.of(center, center.add(axis.scale(side == Side.INSIDE ? edge : -edge)));
        Stand best = null;
        for (var candidate : candidates) {
            var at = SurfaceApproach.restingFloor(y, candidate);
            if (at == null) continue;
            var box = y.getBoundingBox().move(at.subtract(y.position()));
            if (!clearOfScreen(box, targets) || !y.level().getEntitiesOfClass(LivingEntity.class, box,
                    other -> other != y && other.isAlive() && !other.isSpectator()).isEmpty()) continue;
            double length = SurfaceApproach.routeLength(y, at, null);
            if (Double.isFinite(length) && (best == null || length < best.route())) best = new Stand(at, length);
        }
        return best;
    }

    private double travelTicks(Stand target) { return target.route() / (BodyAbility.of(y).walkSpeed() * WORK_PACE); }

    private static boolean clearOfScreen(AABB body, List<BlockPos> cells) {
        return cells.stream().noneMatch(cell -> body.intersects(new AABB(cell)));
    }

    private boolean begin(ServerLevel level, ShelterBlockEntity home, LivingEntity danger, List<UUID> parents) {
        if (!EventHooks.canEntityGrief(level, y)) {
            GameplayLog.limited(y, "threat", "seal_griefing", 1200, "den seal skipped reason=mob_griefing_off den={}", home.getBlockPos());
            if (danger != null) threatDecision = ThreatDecision.DONE;
            return false;
        }
        var plan = planFor(level, home);
        if (plan.refusal() != null) {
            if (!plan.refusal().equals("already_sealed"))
                GameplayLog.limited(y, "threat", "seal_plan", 1200, "den seal skipped reason={} den={}", plan.refusal(), home.getBlockPos());
            if (danger != null) threatDecision = ThreatDecision.DONE;
            return false;
        }
        if (home.branches() < plan.cells().size()) {
            GameplayLog.limited(y, "threat", "seal_twigs", 600, "den seal skipped reason=no_twigs have={} need={} den={}",
                    home.branches(), plan.cells().size(), home.getBlockPos());
            nextCheck = level.getGameTime() + RETRY; return false;
        }
        if (danger != null && danger.distanceToSqr(Vec3.atCenterOf(home.entrance())) < DOOR_SAFE_SQR) {
            GameplayLog.limited(y, "threat", "seal_door", 200, "den seal waiting reason=danger_at_door danger={} den={}", danger.getUUID(), home.getBlockPos());
            return false;
        }
        // On a family outing the sealer may still be standing in its own doorway as it heads inside.
        if (occupant(level, plan.cells()) != null) {
            GameplayLog.limited(y, "threat", "seal_cell", 200, "den seal waiting reason=cell_occupied den={}", home.getBlockPos());
            return false;
        }
        stand = standFor(home, plan.cells(), home.contains(y.blockPosition()) || plan.cells().size() > 1 ? Side.INSIDE : Side.OUTSIDE);
        if (stand == null) return false;
        walkBudget = (int) Math.ceil(2 * travelTicks(stand));
        paused = 0;
        if (!parents.isEmpty() && !y.duty().take(Kind.SEAL, walkBudget + y.childhood().denSealPressTicks() + DOORWAY_WAIT + RECHECK, 0, "den_seal_outing"))
            return false;
        den = home.getBlockPos().immutable(); cells = plan.cells(); absent = parents;
        phase = Phase.WALK; ticks = 0;
        GameplayLog.event(y, "home", "den seal started den={} cells={} twigs={} danger={} absent={}",
                den, cells, home.branches(), danger == null ? null : danger.getUUID(), absent);
        return true;
    }

    private void complete(ServerLevel level, ShelterBlockEntity home, LivingEntity danger) {
        if (!EventHooks.canEntityGrief(level, y)) { abort("mob_griefing_off"); return; }
        if (danger != null && familyOutside(level, home)) { abort("family_outside"); return; }
        var plan = planFor(level, home);
        if (plan.refusal() != null || !plan.cells().equals(cells) || home.branches() < cells.size()) {
            abort(plan.refusal() != null ? plan.refusal() : home.branches() < cells.size() ? "twigs_gone" : "cells_changed"); return;
        }
        // Check sight before the screen goes up. Record the outcome only if the parent finishes the work.
        var demonstrations = y.family().adult() ? demonstrations(level, home) : List.<Demonstration>of();
        int before = home.branches(), placed = 0;
        for (var cell : cells) {
            if (!level.setBlock(cell, ModBlocks.DEN_BARRIER.get().defaultBlockState(), 3)) break;
            home.takeBranch();
            ((DenBarrierBlockEntity) level.getBlockEntity(cell)).lay(y, home, absent, danger == null ? null : danger.getUUID());
            level.playSound(null, cell, SoundType.GRASS.getPlaceSound(), SoundSource.BLOCKS, 1f, .9f);
            placed++;
        }
        GameplayLog.event(y, "home", "den seal {} den={} cells={} placed={} twigs {} -> {} danger={} absent={} work_ticks={}",
                placed == cells.size() ? "completed" : "placement_refused", den, cells, placed, before, home.branches(),
                danger == null ? null : danger.getUUID(), absent, ticks);
        if (!absent.isEmpty()) {
            remembered = false;
            y.duty().release(Kind.SEAL, placed == cells.size() ? End.DONE : End.FAILED, "seal_finished");
        }
        if (placed == cells.size()) for (var demonstration : demonstrations) {
            var learner = demonstration.learner();
            if (demonstration.missed() == null) learner.childhood().sawDenSeal(y);
            else GameplayLog.event(y, "childhood", "den seal demonstration missed learner={} reason={}",
                    learner.getUUID(), demonstration.missed());
        }
        phase = Phase.IDLE; ticks = 0; dangerId = null;
        if (danger != null) threatDecision = ThreatDecision.DONE;
    }

    private List<Demonstration> demonstrations(ServerLevel level, ShelterBlockEntity home) {
        var result = new ArrayList<Demonstration>();
        for (var id : home.residentIds()) {
            if (!(level.getEntity(id) instanceof YukkuriEntity child) || !child.isAlive()
                    || !child.family().subadult() || !child.childhood().canLearnDenSeal()
                    || child.skills().knows(Ability.DEN_SEAL) || !y.family().careParentOf(child)) continue;
            String missed = null;
            if (!home.contains(child.blockPosition()) || child.distanceToSqr(y) > 16) missed = "away";
            else if (child.life().isSleeping()) missed = "asleep";
            else if (child.isPanicking() || !child.hasLineOfSight(y)) missed = "out_of_sight";
            result.add(new Demonstration(child, missed));
        }
        return result;
    }

    private LivingEntity occupant(ServerLevel level, List<BlockPos> targets) {
        for (var cell : targets) {
            var bodies = level.getEntitiesOfClass(LivingEntity.class, new AABB(cell), e -> e != y && e.isAlive() && !e.isSpectator());
            if (!bodies.isEmpty()) return bodies.getFirst();
        }
        return null;
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
        var oldest = inside.stream().filter(child -> child.family().subadult() && child.skills().knows(Ability.DEN_SEAL)
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
        if (parents.isEmpty()) return false;
        // The decision is drawn once per trip; a doorway still occupied is retried, not re-rolled.
        if (!parents.equals(considered)) {
            considered = parents;
            var traits = y.life().personality();
            double chance = chance(traits), roll = y.getRandom().nextDouble();
            remembered = roll < chance;
            GameplayLog.event(y, "home", "den seal remembered den={} absent={} self_control={} chance={} roll={} remembered={}",
                    home.getBlockPos(), parents, traits.selfControl(), chance, roll, remembered);
        }
        return remembered && begin(level, home, null, parents);
    }

    /** Family work yields to ordinary life needs and threat goals. The same worker owns the threat route above. */
    public static final class FamilyGoal extends Goal {
        private final YukkuriEntity y;
        private DenBarrierBlockEntity opening;
        private BlockPos stand;
        private BlockPos refusedFrom;
        private int walkTicks, openBudget;

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
                openBudget = (int) Math.ceil(2 * SurfaceApproach.routeLength(y, Vec3.atBottomCenterOf(outer), null) / BodyAbility.of(y).walkSpeed());
                return true;
            }
            return seal().startFamily(level, home);
        }

        @Override public boolean canContinueToUse() {
            return !y.isPanicking() && !y.life().hasUrgentNeed() && seal().available()
                    && (opening == null ? seal().active() : walkTicks <= openBudget && opening.canOpen(y));
        }

        @Override public boolean requiresUpdateEveryTick() { return true; }

        @Override public void tick() {
            if (opening != null) {
                var step = SurfaceApproach.walkResult(y, Vec3.atBottomCenterOf(stand), null, 1);
                walkTicks++;
                if (step == SurfaceApproach.Result.ARRIVED || y.onGround() && stand.equals(y.blockPosition())) {
                    refusedFrom = null;
                    var mouth = Vec3.atCenterOf(opening.getBlockPos());
                    SurfaceApproach.face(y, SurfaceApproach.yaw(y.position(), mouth));
                    y.getLookControl().setLookAt(mouth.x, mouth.y, mouth.z, 30, 30);
                    y.showReaction(YukkuriAction.BOW, "happy", null);
                    opening.open(y);
                } else if (step == SurfaceApproach.Result.BLOCKED || walkTicks >= openBudget) {
                    refusedFrom = y.blockPosition().immutable();
                    walkTicks = openBudget + 1;
                    GameplayLog.event(y, "home", "den barrier opening refused reason=path_stuck mouth={}", opening.getBlockPos());
                }
                return;
            }
            var seal = seal();
            if (!seal.active()) return;
            var level = (ServerLevel) y.level();
            var home = y.habitat().shelter();
            if (home != null && (seal.ticks % RECHECK == 0 || seal.ticks + 1 >= y.childhood().denSealPressTicks())
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
        if (home.flooded()) return Plan.refuse("den_flooded");
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
