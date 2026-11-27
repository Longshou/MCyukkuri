package io.github.longshou.mcyukkuri.play;

import io.github.longshou.mcyukkuri.entity.Behaviors;
import io.github.longshou.mcyukkuri.entity.WaitImpatience;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.entity.YukkuriGroundNavigation;
import io.github.longshou.mcyukkuri.entity.presentation.FaceTier;
import io.github.longshou.mcyukkuri.life.SurfaceApproach;
import io.github.longshou.mcyukkuri.mind.EmotionEvent;
import io.github.longshou.mcyukkuri.motor.BodyAbility;
import io.github.longshou.mcyukkuri.motor.PlayMotion;
import io.github.longshou.mcyukkuri.motor.TrampolineMotion;
import io.github.longshou.mcyukkuri.pet.rules.HouseRule;
import io.github.longshou.mcyukkuri.pet.rules.RulesMath.Choice;
import io.github.longshou.mcyukkuri.social.Contest;
import io.github.longshou.mcyukkuri.social.Temperament;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/** Interruptible physical routes driven by {@link PlayWant} and {@link PlayMotion}. No route teleports or advances unloaded. */
public final class PlayLife {
    /** Where a finished body stands clear of the exit lane: a body width (.8) plus margin beside it and half a body past it. */
    private static final double ASIDE_X = 1.1, ASIDE_Z = .6;
    private static final double WAIT_SIDE_TRAMPOLINE = 2.1, WAIT_SIDE_OTHER = 1.7;
    private static final double[] WAIT_OFFSETS_Z = {0, -.8, .8};
    /** Maximum consecutive repeat passes on a single slide or tube before re-evaluating the park. */
    private static final int MAX_REPEAT_ROUNDS = 4;
    /** Maximum approach distance squared for a chosen facility (covers a full park from the owner's bench). */
    private static final double MAX_APPROACH_SQR = 24 * 24;
    private static final double MAX_VALID_SQR = 28 * 28;
    private static final int MAX_REMEMBERED_GIVEUPS = 16;
    /** A body that lost a station contest plays nowhere for this long: half the 600-tick session cap, so the winner is well into its first round. */
    private static final int LOSS_COOLDOWN = 300;
    /** Approach stall budget (~1.25 s) before giving up and switching to another facility; interior safety bound (~2.5 s). */
    private static final int APPROACH_STALL_TICKS = 25;
    private static final int INTERIOR_STALL_TICKS = 50;
    /** The aside is a suggested clear spot: pressed this long within a body width of it, the body stands where it is. */
    private static final int ASIDE_ARRIVE_TICKS = 10;
    /** A walk that ends within three blocks of the owner is not routed round the owner's body (its stand lies beside it). */
    private static final double OWNER_BERTH_SQR = 9;

    private record Beat(String name, String declared, String line, PlayBlock.Kind kind) {}

    private final YukkuriEntity entity;
    private final Map<BlockPos, Integer> gaveUp = new HashMap<>();
    private final Map<BlockPos, Integer> refusedAt = new HashMap<>();
    private PlayBlockEntity facility, waitingFor;
    private BlockPos lastFacility;
    private List<Vec3> route = List.of();
    private List<Vec3> interiorLocal = List.of();
    private Vec3 waitStand, lastPos;
    private int cooldown, scanWait, elapsed, waypoint, entryAt, interiorAt, bounces, rounds, stalled;
    /** Route index of the owner-side exit aside (-1 when the route has none); it is a suggested spot, not a strict target. */
    private int asideAt = -1;
    private int waitTicks, waitLimit, waitElapsed, lastWaitElapsed;
    /** A tub session: the route index of the seat (-1 elsewhere), the ticks soaked there and the state at the start of the soak. */
    private int soakAt = -1, soakTicks;
    private float soakHealth, soakUnease;
    private int soakWounds, soakJuice;
    private boolean soakHurt;
    private HouseRule soakRule;
    private int lastElapsed;
    private String refused, stopReason;
    private Beat pendingBeat;
    private double lastDistance = Double.MAX_VALUE;
    private boolean completed, springing, peakVoiced, playVoiced, waitWatching, waitFaced, waitImpatient, waitImpatientRefused;

    public PlayLife(YukkuriEntity entity) { this.entity = entity; }

    public boolean active() { return facility != null; }
    /**
     * The facility itself carries the body through its own interior now (the slide chute, the tunnel, the trampoline bed, the tub
     * route), not the walk to it: its authored clip holds for that movement instead of the ground walk clip.
     */
    public boolean inFacilityMovement() {
        if (facility == null || waypoint <= entryAt) return false;
        return switch (facility.kind()) {
            case SLIDE -> waypoint - interiorAt == 6;
            case CERAMIC, POOL -> true;
            case TRAMPOLINE -> springing;
        };
    }
    public boolean waiting() { return waitingFor != null; }
    public boolean isWaitingFor(PlayBlockEntity target) { return waitingFor != null && waitingFor == target; }
    public boolean completed() { return completed; }
    public int bounces() { return bounces; }
    public int rounds() { return rounds; }
    public String refused() { return refused; }

    public void resetScan() { scanWait = 0; }

    public void resetParkMemory() {
        lastFacility = null;
        gaveUp.clear();
        refusedAt.clear();
        cooldown = 0;
        scanWait = 0;
    }

    private static double halfWidth(PlayBlock.Kind kind) {
        return switch (kind) {
            case TRAMPOLINE -> 1.25;
            case CERAMIC -> 1.05;
            case SLIDE -> 0.85;
            case POOL -> 1.15;
        };
    }

    private static double halfDepth(PlayBlock.Kind kind) {
        return switch (kind) {
            case TRAMPOLINE -> 1.25;
            case CERAMIC -> 1.45;
            case SLIDE -> 2.35;
            case POOL -> 1.15;
        };
    }

    /** World position of the entry approach point for {@code target}, used by strays walking to a playground. */
    public static Vec3 entryStand(PlayBlockEntity target) {
        Vec3 local = switch (target.kind()) {
            case CERAMIC -> new Vec3(0, 0, -2);
            case SLIDE -> new Vec3(0, 0, -2.8);
            case TRAMPOLINE -> new Vec3(0, 0, -1.7);
            case POOL -> new Vec3(0, 0, -1.8);
        };
        return PlayGeometry.world(target.getBlockPos(), target.facing(), local.x, local.y, local.z);
    }

    public String activityFace() {
        if (waiting() && waitWatching) return entity.dailyLife().boredom() >= 75 ? "sparkle" : "dull";
        return "";
    }

    /** Logs each play beat on the same tick with the actual face published by {@code PresentationArbiter}. */
    public void observeFace(String shown) {
        if (pendingBeat == null) return;
        var beat = pendingBeat;
        pendingBeat = null;
        var holder = shown.equals(beat.declared()) ? null : entity.faceHold(shown);
        if (holder != null) {
            GameplayLog.event(entity, "play",
                    "play beat={} face={} declared={} held_by={} held_tier={} held_face={} line={} kind={} clip={} rounds={} bounces={} energy={} boredom={} unease={}",
                    beat.name(), shown, beat.declared(), holder.source(), holder.tier() == null ? "none" : holder.tier(), holder.face(),
                    beat.line(), beat.kind() == null ? "NONE" : beat.kind(),
                    entity.getAnimationId(), rounds, bounces,
                    entity.life().energy(), entity.dailyLife().boredom(), entity.mind().unease());
        } else {
            GameplayLog.event(entity, "play",
                    "play beat={} face={} declared={} line={} kind={} clip={} rounds={} bounces={} energy={} boredom={} unease={}",
                    beat.name(), shown, beat.declared(), beat.line(), beat.kind() == null ? "NONE" : beat.kind(),
                    entity.getAnimationId(), rounds, bounces,
                    entity.life().energy(), entity.dailyLife().boredom(), entity.mind().unease());
        }
    }
    public String activity() {
        if (!active()) return Behaviors.IDLE;
        int rel = waypoint - interiorAt;
        return switch (facility.kind()) {
            case SLIDE -> rel == 6 ? Behaviors.SLIDE_DOWN : Behaviors.SLIDE_CLIMB;
            case TRAMPOLINE -> Behaviors.TRAMPOLINE;
            case POOL -> soaking() ? Behaviors.JUICE_SOAK : Behaviors.TUB_CLIMB;
            case CERAMIC -> Behaviors.TUNNEL;
        };
    }

    private boolean soaking() { return soakAt >= 0 && waypoint == soakAt; }

    private int waitBudget() {
        var personality = entity.life().personality();
        return Math.max(60, (int) Math.round(personality.waitingTicks() * (1.5 + personality.temperament().patient())));
    }

    /** The face first, so the line spoken over it extends its hold (the face never drops to the default before the words end). A beat is
     *  the performance's own next frame: it replaces its own previous beat whatever the tier. */
    private void emitBeat(String name, String declaredFace, String line, FaceTier.Tier tier, PlayBlock.Kind kind) {
        entity.forceReaction(declaredFace, "", tier);
        if (!"none".equals(line)) entity.speakCue(line);
        pendingBeat = new Beat(name, declaredFace, line, kind);
    }

    private void rememberSkip(Map<BlockPos, Integer> map, BlockPos pos) {
        if (map.size() >= MAX_REMEMBERED_GIVEUPS) map.clear();
        map.put(pos.immutable(), entity.dailyLife().boredom());
    }

    private boolean skipped(Map<BlockPos, Integer> map, BlockPos pos) {
        Integer at = map.get(pos);
        if (at == null) return false;
        if (entity.dailyLife().boredom() > at + 10) {
            map.remove(pos);
            return false;
        }
        return true;
    }

    public void clock() {
        if (cooldown > 0) cooldown--;
        if (scanWait > 0) scanWait--;
        if (active()) {
            if (elapsed == lastElapsed || !valid()) stop();
            lastElapsed = elapsed;
        } else if (waiting()) {
            if (waitElapsed == lastWaitElapsed || !waitValid()) stopWait("interrupted");
            lastWaitElapsed = waitElapsed;
        }
    }

    public boolean canUse() {
        if (active()) return valid();
        if (waiting()) return waitValid();
        if (entity.outing().onOuting()) return entity.outing().tryParkPlay();
        if (entity.walkingToPlayground()) return false;
        if (cooldown > 0 || scanWait > 0 || !PlayWant.bodyReady(entity, true, false) || entity.inert()) return false;
        scanWait = PlayWant.wants(entity) || PlayWant.wantsTub(entity) ? 40 : 160;
        if (entity.dailyLife().active() || entity.social().active()) return false;
        // Strays leave playgrounds at night (a wounded body still uses a tub at any hour).
        boolean day = entity.level().isDay();
        var center = entity.blockPosition();
        var candidates = new ArrayList<PlayBlockEntity>();
        for (var pos : BlockPos.betweenClosed(center.offset(-8, -1, -8), center.offset(8, 1, 8))) {
            if (!entity.level().hasChunkAt(pos)) continue;
            if (entity.level().getBlockEntity(pos) instanceof PlayBlockEntity found) {
                if (!day && found.kind() != PlayBlock.Kind.POOL) continue;
                candidates.add(found);
            }
        }
        return chooseAndStart(candidates);
    }

    /** Outing entry point: chooses among all facilities scanned by the owner at the park stand. */
    public boolean chooseFromPark(List<PlayBlockEntity> facilities) {
        if (active() || waiting() || cooldown > 0 || facilities.isEmpty()) return false;
        return chooseAndStart(facilities);
    }

    /** True when no facility in {@code facilities} is both wanted and still untried/unexhausted. */
    public boolean parkExhausted(List<PlayBlockEntity> facilities) {
        for (var f : facilities) {
            if (f.isRemoved() || !PlayWant.wants(entity, f)) continue;
            if (skipped(refusedAt, f.getBlockPos())) continue;
            if (f.occupiedByOther(entity) && skipped(gaveUp, f.getBlockPos())) continue;
            return false;
        }
        return true;
    }

    private boolean chooseAndStart(List<PlayBlockEntity> candidates) {
        if (candidates.isEmpty()) return false;
        record Scored(PlayBlockEntity facility, double score) {}
        var scored = new ArrayList<Scored>();
        for (var f : candidates) {
            if (f.isRemoved()) continue;
            // Clear a wait give-up once the facility is actually free again.
            if (!f.occupiedByOther(entity)) gaveUp.remove(f.getBlockPos());
            if (skipped(refusedAt, f.getBlockPos())) continue;
            double score = PlayWant.preference(entity, f, lastFacility);
            if (score > 0) scored.add(new Scored(f, score));
        }
        if (scored.isEmpty()) return false;
        scored.sort(Comparator.comparingDouble(Scored::score).reversed());
        // If the strongly preferred top facility is occupied (and not yet gave-up), watch and wait for it first;
        // once wait patience expires (gaveUp), the next tick falls through to the best free alternative.
        var top = scored.getFirst();
        if (top.facility().occupiedByOther(entity) && !skipped(gaveUp, top.facility().getBlockPos())) {
            double bestFreeScore = scored.stream()
                    .filter(s -> !s.facility().occupiedByOther(entity))
                    .mapToDouble(Scored::score).findFirst().orElse(0.0);
            if (top.score() > bestFreeScore + 0.12 && beginWait(top.facility())) return true;
        }
        // First pass: start the highest-scoring free facility.
        for (var entry : scored) {
            var target = entry.facility();
            if (target.occupiedByOther(entity)) continue;
            // The bout took the body: this is no session, so the goal does not start.
            if (contestsStation(target)) return false;
            if (begin(target)) return true;
            if (!"cooldown".equals(refused) && !"occupied".equals(refused)) {
                rememberSkip(refusedAt, target.getBlockPos());
                if (entity.outing().onOuting()) {
                    GameplayLog.limited(entity, "pet", "park_refused:" + refused, 600,
                            "outing play refused reason={} kind={} at={}", refused, target.kind(), target.getBlockPos());
                }
            }
        }
        // Second pass: if every wanted facility is occupied, stand beside the best non-gave-up one and watch.
        for (var entry : scored) {
            var target = entry.facility();
            if (!target.occupiedByOther(entity) || skipped(gaveUp, target.getBlockPos())) continue;
            if (beginWait(target)) return true;
        }
        return false;
    }

    /**
     * Two bored adults in sight of each other who both want one free station do not race for it: they stage a contest at it (a bout
     * that stands both down), and the verdict lands in {@link #contested}. An occupied station is not contested; it is waited for.
     */
    private boolean contestsStation(PlayBlockEntity target) {
        if (entity.outing().onOuting() || target.kind() == PlayBlock.Kind.POOL) return false;
        var prize = new StationPrize(target);
        var center = Vec3.atCenterOf(target.getBlockPos());
        return entity.level().getEntitiesOfClass(YukkuriEntity.class, entity.getBoundingBox().inflate(6), o -> o != entity && o.isAlive()
                && !o.outing().onOuting() && !o.playLife().active() && !o.playLife().waiting() && o.playLife().cooldown == 0
                && o.distanceToSqr(center) <= MAX_APPROACH_SQR && entity.hasLineOfSight(o) && PlayWant.wants(o, target))
                .stream().anyMatch(rival -> entity.groupInteraction().contest(rival, prize));
    }

    /**
     * The verdict of a station contest: the winner starts its session after its smug beat; the loser lets this station be, sulks for
     * {@link #LOSS_COOLDOWN} ticks without playing anywhere and, once the station is free again, may take its turn.
     */
    public void contested(PlayBlockEntity target, boolean won, UUID rival) {
        scanWait = Contest.GLOAT_TICKS;
        if (won) {
            cooldown = 0;
        } else {
            cooldown = LOSS_COOLDOWN;
            rememberSkip(gaveUp, target.getBlockPos());
        }
        GameplayLog.event(entity, "play", "contested station {} kind={} at={} rival={} boredom={}", won ? "won" : "yielded",
                target.kind(), target.getBlockPos(), rival, entity.dailyLife().boredom());
    }

    /** Stand beside an occupied facility and watch the player on it without reserving the facility. */
    public boolean beginWait(PlayBlockEntity target) {
        if (active() || waiting() || cooldown > 0) return false;
        if (target.getLevel() != entity.level() || target.isRemoved()) return false;
        if (!PlayWant.wants(entity, target)) return false;
        if (skipped(gaveUp, target.getBlockPos())) return false;
        if (entity.distanceToSqr(Vec3.atCenterOf(target.getBlockPos())) > MAX_APPROACH_SQR) return false;
        Vec3 stand = findWaitStand(target);
        if (stand == null) return false;
        entity.social().stop();
        waitingFor = target;
        waitStand = stand;
        waitTicks = 0;
        waitLimit = waitBudget();
        waitWatching = false;
        waitFaced = false;
        waitImpatient = waitImpatientRefused = false;
        waitElapsed = 0;
        lastWaitElapsed = -1;
        GameplayLog.limited(entity, "play", "wait_start:" + target.kind(), 600,
                "facility wait started kind={} at={} budget={} boredom={}",
                target.kind(), target.getBlockPos(), waitLimit, entity.dailyLife().boredom());
        return true;
    }

    private boolean clearOfOthers(Vec3 at) {
        var level = entity.level();
        var box = entity.getBoundingBox().move(at.subtract(entity.position()));
        var below = BlockPos.containing(at).below();
        return level.noCollision(entity, box)
                && level.getEntitiesOfClass(YukkuriEntity.class, box.inflate(.1, 0, .1), o -> o != entity).isEmpty()
                && level.getBlockState(below).isFaceSturdy(level, below, Direction.UP);
    }

    private Vec3 findWaitStand(PlayBlockEntity target) {
        double sideX = target.kind() == PlayBlock.Kind.TRAMPOLINE ? WAIT_SIDE_TRAMPOLINE : WAIT_SIDE_OTHER;
        int preferred = preferredSide(target);
        for (int s : new int[]{preferred, -preferred}) {
            for (double z : WAIT_OFFSETS_Z) {
                var candidate = PlayGeometry.world(target.getBlockPos(), target.facing(), s * sideX, 0, z);
                if (!clearOfOthers(candidate)) continue;
                var path = entity.getNavigation().createPath(BlockPos.containing(candidate), 0);
                if (path != null && path.canReach()) return candidate;
            }
        }
        return null;
    }

    /** Side of {@code target} toward the escorting owner when on an outing, else toward the body's own side. */
    private int preferredSide(PlayBlockEntity target) {
        Vec3 ref = entity.outing().onOuting() ? entity.outing().ownerPosition() : entity.position();
        if (ref != null) {
            double lx = PlayGeometry.local(target.getBlockPos(), target.facing(), ref).x;
            if (Math.abs(lx) > 0.2) return lx >= 0 ? 1 : -1;
        }
        return (entity.getUUID().hashCode() & 1) == 0 ? 1 : -1;
    }

    private boolean waitValid() {
        return waiting() && PlayWant.wants(entity, waitingFor) && !waitingFor.isRemoved()
                && waitingFor.getLevel() == entity.level()
                && entity.level() instanceof ServerLevel level && level.hasChunkAt(waitingFor.getBlockPos())
                && level.getBlockEntity(waitingFor.getBlockPos()) == waitingFor
                && (!entity.outing().onOuting() || (entity.outing().parkFacilities().contains(waitingFor)
                        && !entity.outing().calledByOwner() && !entity.outing().tired()))
                && entity.distanceToSqr(Vec3.atCenterOf(waitingFor.getBlockPos())) < MAX_VALID_SQR;
    }

    private void tickWait() {
        if (entity.outing().onOuting() && entity.outing().calledByOwner()) {
            emitBeat("called_home", "surprise", "none", FaceTier.Tier.SHORT, waitingFor.kind());
            stopWait("called");
            return;
        }
        if (!waitValid()) { stopWait("interrupted"); return; }
        waitElapsed++;
        if (!waitingFor.occupiedByOther(entity)) {
            var target = waitingFor;
            stopWait("free");
            if (PlayWant.wants(entity, target)) begin(target);
            return;
        }
        double dist = waitStand.subtract(entity.position()).horizontalDistance();
        if (dist > .35) {
            var result = SurfaceApproach.walkResult(entity, waitStand, null, 1.0);
            if (result == SurfaceApproach.Result.BLOCKED) {
                rememberSkip(gaveUp, waitingFor.getBlockPos());
                stopWait("blocked");
                return;
            }
            return;
        }
        if (!waitWatching && !clearOfOthers(waitStand)) {
            Vec3 alt = findWaitStand(waitingFor);
            if (alt != null) { waitStand = alt; return; }
            rememberSkip(gaveUp, waitingFor.getBlockPos());
            stopWait("blocked");
            return;
        }
        SurfaceApproach.stopWalking(entity);
        Vec3 focus = watchFocus(waitingFor);
        entity.getLookControl().setLookAt(focus.x, focus.y, focus.z, 30, 35);
        if (!waitFaced) {
            PlayMotion.faceToward(entity, focus);
            waitFaced = true;
        }
        if (!waitWatching) {
            waitWatching = true;
            String face = entity.dailyLife().boredom() >= 75 ? "sparkle" : "dull";
            String line = entity.life().personality().temperament().patient() >= Temperament.TOP35 ? "wait_turn" : "none";
            emitBeat("wait_watch", face, line, FaceTier.Tier.SHORT, waitingFor.kind());
        }
        if (++waitTicks >= waitLimit) {
            double patient = entity.life().personality().temperament().patient();
            if (!waitImpatient && waitImpatientRefused) {
                GameplayLog.event(entity, "play", "play beat=wait_impatient dropped=held kind={} waited={}/{}", waitingFor.kind(), waitTicks, waitLimit);
            }
            emitBeat("wait_give_up", patient < Temperament.LOW35 ? "hmph" : "dull", "mood_bad", FaceTier.Tier.MEDIUM, waitingFor.kind());
            rememberSkip(gaveUp, waitingFor.getBlockPos());
            stopWait("patience");
        } else if (!waitImpatient && waitTicks >= WaitImpatience.triggerTick(entity, waitLimit)) {
            // A refusal (a longer hold, a bound clip) does not consume the beat: it is retried each tick until the hold ends.
            var beat = WaitImpatience.perform(entity);
            if (beat != null) {
                waitImpatient = true;
                pendingBeat = new Beat("wait_impatient", beat.face(), beat.line(), waitingFor.kind());
            } else {
                waitImpatientRefused = true;
            }
        }
    }

    /** The escorting owner's body when the walk does not end beside it: the route then goes round it (it is no obstacle to ground navigation). */
    private Entity routeAroundOwner(Vec3 target) {
        var owner = entity.outing().escortBody();
        return owner != null && owner.distanceToSqr(target) > OWNER_BERTH_SQR ? owner : null;
    }

    private Vec3 watchFocus(PlayBlockEntity target) {
        UUID occupant = target.occupant();
        if (occupant != null && entity.level() instanceof ServerLevel server
                && server.getEntity(occupant) instanceof YukkuriEntity bouncer && bouncer.isAlive()) {
            return bouncer.getEyePosition();
        }
        return target.getBlockPos().getCenter().add(0, PlayBlock.BED_HEIGHT + .5, 0);
    }

    private void stopWait(String reason) {
        if (waitingFor != null && reason != null) {
            GameplayLog.limited(entity, "play", "wait_ended:" + reason, 600,
                    "facility wait ended kind={} reason={} at={} waited={}/{} boredom={}",
                    waitingFor.kind(), reason, waitingFor.getBlockPos(), waitTicks, waitLimit, entity.dailyLife().boredom());
        }
        waitingFor = null; waitStand = null; waitTicks = waitLimit = waitElapsed = 0; lastWaitElapsed = -1;
        waitWatching = waitFaced = waitImpatient = waitImpatientRefused = false;
        SurfaceApproach.stopWalking(entity);
    }

    /** Builds the full waypoint list (shell-clearing approach + interior + owner-side exit aside) and sets {@code entryAt}/{@code interiorAt}. */
    private List<Vec3> buildRoute(PlayBlockEntity target, List<Vec3> local) {
        var approach = PlayMotion.approachWaypoints(entity, target.getBlockPos(), target.facing(),
                halfWidth(target.kind()), halfDepth(target.kind()), local.getFirst());
        entryAt = approach.size() - 1;
        asideAt = -1;
        var points = new ArrayList<Vec3>(approach);
        Vec3 firstWorld = PlayGeometry.world(target.getBlockPos(), target.facing(),
                local.getFirst().x, local.getFirst().y, local.getFirst().z);
        if (points.getLast().distanceToSqr(firstWorld) > 0.04) {
            points.add(firstWorld);
            interiorAt = points.size() - 1;
        } else {
            interiorAt = entryAt;
        }
        for (int i = 1; i < local.size(); i++) {
            var v = local.get(i);
            points.add(PlayGeometry.world(target.getBlockPos(), target.facing(), v.x, v.y, v.z));
        }
        if (target.kind() == PlayBlock.Kind.CERAMIC || target.kind() == PlayBlock.Kind.TRAMPOLINE
                || target.kind() == PlayBlock.Kind.SLIDE) {
            var exit = local.getLast();
            double zDir = exit.z >= 0 ? 1.0 : -1.0;
            int side = preferredSide(target);
            outer:
            for (int s : new int[]{side, -side}) {
                for (double dx : new double[]{ASIDE_X, 1.7, 1.1}) {
                    for (double dz : new double[]{ASIDE_Z, 0.7}) {
                        var aside = PlayGeometry.world(target.getBlockPos(), target.facing(),
                                s * dx, exit.y, exit.z + zDir * dz);
                        if (clearOfOthers(aside)) {
                            points.add(aside);
                            asideAt = points.size() - 1;
                            break outer;
                        }
                    }
                }
            }
        }
        return points;
    }

    private List<Vec3> ceramicLocal(PlayBlockEntity target) {
        // Symmetric tunnel: enter from whichever mouth is closer to where the body currently stands.
        double bz = PlayGeometry.local(target.getBlockPos(), target.facing(), entity.position()).z;
        if (bz > 0) {
            return List.of(new Vec3(0, 0, 2), new Vec3(0, .125, 1), new Vec3(0, .125, 0), new Vec3(0, .125, -1), new Vec3(0, 0, -2));
        }
        return List.of(new Vec3(0, 0, -2), new Vec3(0, .125, -1), new Vec3(0, .125, 0), new Vec3(0, .125, 1), new Vec3(0, 0, 2));
    }

    public boolean begin(PlayBlockEntity target) {
        if (active() || cooldown > 0) return refuse("cooldown");
        if (!PlayWant.bodyReady(entity, target.kind() == PlayBlock.Kind.POOL, false)) return refuse("unfit");
        if (target.getLevel() != entity.level() || target.isRemoved()) return refuse("gone");
        if (target.kind() == PlayBlock.Kind.TRAMPOLINE
                && (!entity.childhood().canJump() || BodyAbility.cannotSpring(entity))) return refuse("cannot_jump");
        if (entity.distanceToSqr(Vec3.atCenterOf(target.getBlockPos())) > MAX_APPROACH_SQR) return refuse("far");
        JuiceTub.Seat seat = null;
        boolean hurt = false;
        if (target.kind() == PlayBlock.Kind.POOL) {
            if (target.juice() == 0) return refuse("tub_empty");
            if (JuiceTub.tooBig(entity.getBbWidth())) return refuse("too_big");
            var seats = JuiceTub.seatsFor(JuiceTub.layout(), entity.getBbWidth(), target.juice());
            if (seats.isEmpty()) return refuse("tub_shallow");
            hurt = entity.body().hasJuiceTreatableWound();
            soakRule = null;
            if (!tubPermitted(hurt)) return refuse("house_rule");
            seat = seats.get(Math.floorMod(entity.getUUID().hashCode(), seats.size()));
        }
        var tub = seat == null ? null : JuiceTub.route(JuiceTub.layout(), seat);
        int localSeatAt = tub == null ? -1 : tub.seat();
        var local = switch (target.kind()) {
            case CERAMIC -> ceramicLocal(target);
            case SLIDE -> List.of(new Vec3(0, 0, -2.8), new Vec3(0, .25, -1.9), new Vec3(0, .5, -1.4),
                    new Vec3(0, .75, -.9), new Vec3(0, 1, -.4), new Vec3(0, 1, 0), new Vec3(0, 0, 2.8));
            case TRAMPOLINE -> List.of(new Vec3(0, 0, -1.7), new Vec3(0, PlayBlock.BED_HEIGHT, 0),
                    new Vec3(0, 0, Math.max(1.7, PlayBlock.RIM_RADIUS + entity.getBbWidth() / 2.0 + .22)));
            case POOL -> tub.points().stream().map(p -> new Vec3(p[0], p[1], p[2])).toList();
        };
        var points = buildRoute(target, local);
        var first = points.getFirst();
        var path = entity.getNavigation().createPath(BlockPos.containing(first), 0);
        if (path == null || !path.canReach()) return refuse("blocked");
        if (!target.reserve(entity)) return refuse("occupied");
        entity.social().stop();
        facility = target;
        interiorLocal = local;
        route = points;
        elapsed = waypoint = bounces = rounds = stalled = 0;
        lastDistance = Double.MAX_VALUE;
        lastPos = entity.position();
        completed = springing = peakVoiced = playVoiced = false;
        stopReason = null;
        soakAt = localSeatAt < 0 ? -1 : interiorAt + localSeatAt;
        soakTicks = 0;
        soakHurt = hurt;
        lastElapsed = -1;
        entity.getNavigation().moveTo(path, .9);
        boolean firstOrNew = lastFacility == null || !lastFacility.equals(target.getBlockPos());
        String chooseFace = target.kind() == PlayBlock.Kind.POOL && hurt ? "teary"
                : entity.dailyLife().boredom() >= 60 ? "sparkle" : "happy";
        String chooseLine = target.kind() == PlayBlock.Kind.POOL && hurt ? "none" : firstOrNew ? "toy" : "none";
        emitBeat("choose_facility", chooseFace, chooseLine, FaceTier.Tier.SHORT, target.kind());
        GameplayLog.event(entity, "play", "facility session started kind={} at={} energy={} boredom={}",
                target.kind(), target.getBlockPos(), entity.life().energy(), entity.dailyLife().boredom());
        return true;
    }

    private boolean tubPermitted(boolean hurt) {
        HouseRule rule = JuiceTub.tubRule(hurt);
        var choice = entity.pet().conscience().tempted(rule, tubPressure(hurt), 1);
        if (!JuiceTub.permitsSoak(choice)) {
            GameplayLog.limited(entity, "play", "tub_rule:" + rule.key(), 1200,
                    "tub refused by house rule rule={} choice={} hurt={} obedience={}",
                    rule.key(), choice, hurt, entity.pet().obedience());
            return false;
        }
        if (choice == Choice.BREAK) soakRule = rule;
        return true;
    }

    private double tubPressure(boolean hurt) {
        return hurt
                ? Math.max(entity.body().condition().painSeverity() / 3.0, 1 - entity.body().healthRatio())
                : Math.max(entity.dailyLife().boredom() / 100.0, entity.mind().unease() / 100.0);
    }

    private boolean refuse(String reason) { refused = reason; return false; }

    private boolean valid() {
        return active() && PlayWant.bodyReady(entity, facility.kind() == PlayBlock.Kind.POOL, true)
                && !(facility.kind() == PlayBlock.Kind.TRAMPOLINE && BodyAbility.cannotSpring(entity))
                && elapsed < (facility.kind() == PlayBlock.Kind.POOL ? 600 + JuiceTub.SOAK_TICKS : 600)
                && !facility.isRemoved() && facility.getLevel() == entity.level()
                && entity.level() instanceof ServerLevel level && level.hasChunkAt(facility.getBlockPos())
                && level.getBlockEntity(facility.getBlockPos()) == facility
                && (!entity.outing().onOuting() || entity.outing().parkFacilities().contains(facility)
                        || (springing && entity.outing().calledByOwner()))
                && entity.distanceToSqr(Vec3.atCenterOf(facility.getBlockPos())) < MAX_VALID_SQR
                && facility.reserve(entity);
    }

    public boolean canContinue() { return active() ? valid() : waitValid(); }

    boolean springingAt(BlockPos root) {
        return springing && stopReason == null && facility != null && facility.getBlockPos().equals(root);
    }

    boolean stoppingAt(BlockPos root) {
        return springing && stopReason != null && facility != null && facility.getBlockPos().equals(root);
    }

    private String trampolineStopReason() {
        if (BodyAbility.cannotSpring(entity)) return "frail";
        if (entity.outing().onOuting() && (!entity.outing().parkFacilities().contains(facility) || entity.outing().calledByOwner()))
            return "called";
        if (PlayWant.tired(entity)) return "tired";
        if (entity.mind().unease() >= PlayWant.scareCeiling(entity)) return "scared";
        if (entity.dailyLife().boredom() <= PlayWant.boredomFloor(entity)) return "enough";
        if (bounces >= 10 && hasBetterFreeAlternative(facility)) return "switch";
        return null;
    }

    private void markTrampolineStop(String reason) {
        if (stopReason != null) return;
        stopReason = reason;
        String face = switch (reason) {
            case "enough" -> "satisfied";
            case "tired" -> "sleepy";
            case "scared" -> "afraid";
            case "called" -> "surprise";
            case "switch" -> "happy";
            default -> "teary";
        };
        String line = "scared".equals(reason) ? "uneasy" : "none";
        String beat = "called".equals(reason) ? "called_home" : "stop_" + reason;
        emitBeat(beat, face, line, FaceTier.Tier.SHORT, PlayBlock.Kind.TRAMPOLINE);
    }

    void trampolineBounced() {
        bounces++;
        entity.life().spendEnergy(2);
        entity.dailyLife().entertained(3);
        double timid = entity.life().personality().temperament().timid();
        if (timid >= Temperament.TOP35) entity.mind().add((float) (timid * 5), "trampoline_height");
        entity.mind().felt(EmotionEvent.PLAYED);
        String reason = trampolineStopReason();
        if (reason != null) {
            markTrampolineStop(reason);
        } else if (bounces == 3 && !peakVoiced) {
            peakVoiced = true;
            String peakFace = entity.life().personality().temperament().is(Temperament.Gate.BOLD) ? "open_smile" : "happy";
            emitBeat("peak", peakFace, "glow", FaceTier.Tier.SHORT, PlayBlock.Kind.TRAMPOLINE);
        } else {
            emitBeat("bounce", "happy", "none", FaceTier.Tier.SHORT, PlayBlock.Kind.TRAMPOLINE);
        }
    }

    private boolean someoneWaitingFor(PlayBlockEntity target) {
        var box = entity.getBoundingBox().inflate(6, 2, 6);
        return !entity.level().getEntitiesOfClass(YukkuriEntity.class, box,
                o -> o != entity && o.isAlive() && o.playLife().isWaitingFor(target)).isEmpty();
    }

    private boolean hasBetterFreeAlternative(PlayBlockEntity current) {
        if (!entity.outing().onOuting()) return false;
        double repeatScore = PlayWant.preference(entity, current, current.getBlockPos());
        for (var other : entity.outing().parkFacilities()) {
            if (other == current || other.isRemoved() || other.occupiedByOther(entity)) continue;
            if (skipped(refusedAt, other.getBlockPos())) continue;
            if (PlayWant.preference(entity, other, current.getBlockPos()) > repeatScore) return true;
        }
        return false;
    }

    public void tickGoal() {
        if (waiting()) { tickWait(); return; }
        if (active() && entity.outing().onOuting() && entity.outing().calledByOwner() && !springing) {
            stopReason = "called";
            emitBeat("called_home", "surprise", "none", FaceTier.Tier.SHORT, facility.kind());
            stop();
            return;
        }
        if (!valid()) { stop(); return; }
        elapsed++;
        Vec3 target = route.get(waypoint);
        double distance = target.subtract(entity.position()).horizontalDistance();
        entity.getLookControl().setLookAt(target.x, entity.getEyeY(), target.z, 25, 25);
        int rel = waypoint - interiorAt;
        boolean trampoline = facility.kind() == PlayBlock.Kind.TRAMPOLINE;
        if (soaking() && distance < .22 && Math.abs(entity.getY() - target.y) < .6) {
            if (soak()) { waypoint++; lastDistance = Double.MAX_VALUE; lastPos = entity.position(); stalled = 0; }
            return;
        }
        if (trampoline && rel == 1) {
            if (springing) {
                if (stopReason == null) {
                    String liveStop = trampolineStopReason();
                    if ("called".equals(liveStop) || "frail".equals(liveStop)) markTrampolineStop(liveStop);
                }
                if (!entity.onGround() || stopReason == null) {
                    if (entity.onGround() && entity.getDeltaMovement().y <= 0 && stopReason == null) {
                        TrampolineMotion.hop(entity);
                    } else {
                        TrampolineMotion.steady(entity);
                    }
                    return;
                }
                springing = false;
                if (entity.getDeltaMovement().y <= 0) TrampolineMotion.hop(entity);
                waypoint++; lastDistance = Double.MAX_VALUE; lastPos = entity.position(); stalled = 0;
                target = route.get(waypoint);
                distance = target.subtract(entity.position()).horizontalDistance();
                rel = waypoint - interiorAt;
            } else if (distance < .22 && PlayBlock.onTrampolineBed(entity)) {
                entity.getNavigation().stop();
                if (entity.motor().current() == null) {
                    springing = true;
                    stopReason = null;
                    peakVoiced = false;
                    TrampolineMotion.hop(entity);
                    String mountFace = entity.life().personality().temperament().timid() >= Temperament.TOP35 ? "confused" : "happy";
                    emitBeat("mount", mountFace, "none", FaceTier.Tier.SHORT, PlayBlock.Kind.TRAMPOLINE);
                }
                return;
            }
        }
        boolean leavingTrampoline = trampoline && rel == 2;
        if (leavingTrampoline && distance < .22 && !entity.onGround()) {
            TrampolineMotion.steady(entity);
            return;
        }
        if (!playVoiced && facility.kind() == PlayBlock.Kind.SLIDE && rel == 6) {
            playVoiced = true;
            boolean bold = entity.life().personality().temperament().is(Temperament.Gate.BOLD);
            emitBeat("play", bold ? "open_smile" : "happy", bold ? "glow" : "none", FaceTier.Tier.SHORT, PlayBlock.Kind.SLIDE);
        } else if (!playVoiced && facility.kind() == PlayBlock.Kind.CERAMIC && rel == 2) {
            playVoiced = true;
            emitBeat("play", "happy", "none", FaceTier.Tier.SHORT, PlayBlock.Kind.CERAMIC);
        }
        double arriveDist = waypoint <= entryAt ? .45 : .22;
        // A peer may settle on the few centimetres of the exit aside: the body stands where it is rather than pressing on.
        boolean asideReached = waypoint == asideAt && stalled >= ASIDE_ARRIVE_TICKS
                && distance < arriveDist + entity.getBbWidth();
        if ((distance < arriveDist || asideReached) && Math.abs(entity.getY() - target.y) < .6 && !(trampoline && rel == 1)
                && (!leavingTrampoline || PlayBlock.facility(entity.level(), entity.getOnPos()) != facility)) {
            waypoint++; lastDistance = Double.MAX_VALUE; lastPos = entity.position(); stalled = 0;
            if (waypoint >= route.size()) {
                finishPass(trampoline);
                return;
            }
            if (!entity.life().spendEnergy(.5f)) { stop(); return; }
            target = route.get(waypoint);
            rel = waypoint - interiorAt;
            distance = target.subtract(entity.position()).horizontalDistance();
        }
        if (entity.horizontalCollision || entity.motor().contact.pushedThisTick(entity.level().getGameTime()) || stalled >= 5) {
            askToYield(target);
        }
        // Approach phase (up to the standoff in front of the mouth): walk via ground navigation around the shell, giving the
        // escorting owner's body a berth (ground navigation plans round yukkuri bodies only). The mouth and the interior are the body's
        // own gait below, which turns first and steps up a lip. Progress is measured by actual displacement so curving round a
        // shell or bench never false-stalls.
        if (waypoint <= entryAt) {
            if (lastPos == null || entity.position().distanceToSqr(lastPos) > 0.04) {
                stalled = 0;
                lastPos = entity.position();
            } else {
                ++stalled;
            }
            var res = SurfaceApproach.walkResult(entity, target, routeAroundOwner(target), 1.0);
            int approachLimit = BodyAbility.of(entity).walkSpeed() < 0.06 ? 50 : APPROACH_STALL_TICKS;
            if (res == SurfaceApproach.Result.BLOCKED || stalled > approachLimit) {
                rememberSkip(refusedAt, facility.getBlockPos());
                stopReason = "unreachable";
                stop();
            }
            return;
        }
        if (distance < lastDistance - .015) { stalled = 0; lastDistance = distance; }
        else ++stalled;
        // Interior phase (from the mouth through the authored facility route): turn first and drive with body gait.
        if (stalled > INTERIOR_STALL_TICKS) {
            GameplayLog.limited(entity, "play", "stalled", 1200,
                    "facility route stalled waypoint={} at={} target={} collided={} onGround={} navDone={} navTarget={} wanted={} speed={} motor={} leashed={} riding={}",
                    waypoint, GameplayLog.at(entity.position()), GameplayLog.at(target), entity.horizontalCollision, entity.onGround(),
                    entity.getNavigation().isDone(), entity.getNavigation().getTargetPos(), entity.getMoveControl().hasWanted(), entity.getSpeed(),
                    entity.motor().owner() == null ? "none" : entity.motor().owner().ownerId(), entity.isLeashed(), entity.isPassenger());
            rememberSkip(refusedAt, facility.getBlockPos());
            stop();
            return;
        }
        boolean turning;
        if (facility.kind() == PlayBlock.Kind.SLIDE && rel == 6) {
            turning = PlayMotion.slideDescent(entity, target);
        } else {
            double effort = facility.kind() == PlayBlock.Kind.SLIDE && rel >= 1 && rel <= 5 ? 0.85 : 1.0;
            turning = PlayMotion.routeStep(entity, target, effort);
        }
        if (turning && stalled > 0) stalled--;
    }

    private void finishPass(boolean trampoline) {
        rounds++;
        completed = !trampoline || bounces > 0;
        if (!completed) { stop(); return; }
        var kind = facility.kind();
        if (trampoline) {
            entity.dailyLife().entertained(5);
            entity.mind().felt(EmotionEvent.PLAYED);
            lastFacility = facility.getBlockPos();
            gaveUp.clear();
            if (!PlayWant.wants(entity) || "enough".equals(stopReason) || "tired".equals(stopReason)) {
                String face = PlayWant.tired(entity) || "tired".equals(stopReason) ? "sleepy" : "satisfied";
                emitBeat("had_enough", face, "none", FaceTier.Tier.MEDIUM, kind);
            } else {
                String dismountFace = "scared".equals(stopReason) ? "teary" : "happy";
                emitBeat("dismount", dismountFace, "none", FaceTier.Tier.MEDIUM, kind);
            }
            stop();
            return;
        }
        entity.dailyLife().entertained(28);
        entity.mind().felt(EmotionEvent.PLAYED);
        boolean canRepeat = (kind == PlayBlock.Kind.SLIDE || kind == PlayBlock.Kind.CERAMIC)
                && rounds < MAX_REPEAT_ROUNDS
                && (!entity.outing().onOuting() || !entity.outing().calledByOwner())
                && (entity.outing().onOuting() || entity.level().isDay())
                && PlayWant.wants(entity, facility)
                && !someoneWaitingFor(facility)
                && !hasBetterFreeAlternative(facility);
        if (canRepeat) {
            var nextLocal = kind == PlayBlock.Kind.CERAMIC ? ceramicLocal(facility) : interiorLocal;
            interiorLocal = nextLocal;
            route = buildRoute(facility, nextLocal);
            waypoint = 0;
            elapsed = 0;
            lastElapsed = -1;
            lastDistance = Double.MAX_VALUE;
            lastPos = entity.position();
            stalled = 0;
            playVoiced = false;
            emitBeat("go_again", "sparkle", "toy", FaceTier.Tier.SHORT, kind);
            var start = route.getFirst();
            entity.getNavigation().moveTo(start.x, start.y, start.z, .9);
            return;
        }
        lastFacility = facility.getBlockPos();
        gaveUp.clear();
        if (kind == PlayBlock.Kind.POOL || !PlayWant.wants(entity)) {
            String face = PlayWant.tired(entity) ? "sleepy" : "satisfied";
            emitBeat("had_enough", face, "none", FaceTier.Tier.MEDIUM, kind);
        } else {
            emitBeat("dismount", "happy", "none", FaceTier.Tier.SHORT, kind);
        }
        stop();
    }

    /** Pushing against a non-playing body on or beside the facility: ask it to step aside so the route stays clear. */
    private void askToYield(Vec3 target) {
        var line = target.subtract(entity.position()).multiply(1, 0, 1);
        if (line.lengthSqr() < 1e-6) return;
        var ahead = entity.getBoundingBox().expandTowards(line.normalize().scale(.9)).inflate(.35, .25, .35);
        for (var other : entity.level().getEntitiesOfClass(YukkuriEntity.class, ahead,
                o -> o != entity && !o.playLife().active())) {
            YukkuriGroundNavigation.giveWay(other, entity, target);
        }
    }

    private boolean soak() {
        var back = PlayGeometry.world(facility.getBlockPos(), facility.facing(), 0, 0, 1);
        entity.getNavigation().stop();
        entity.getMoveControl().setWantedPosition(entity.getX(), entity.getY(), entity.getZ(), 0);
        PlayMotion.faceToward(entity, back);
        if (soakTicks == 0) {
            soakHealth = entity.getHealth(); soakWounds = entity.body().wounds().size();
            soakJuice = facility.juice(); soakUnease = entity.mind().unease();
            if (soakRule != null) entity.pet().conscience().breach(soakRule);
            emitBeat("play", "closed_smile", "none", FaceTier.Tier.SHORT, PlayBlock.Kind.POOL);
            GameplayLog.event(entity, "play", "tub soak started at={} juice={} health={}/{} wounds={} hygiene={} hurt={} unease={}",
                    facility.getBlockPos(), soakJuice, entity.getHealth(), entity.getMaxHealth(), soakWounds,
                    entity.life().hygiene(), soakHurt, soakUnease);
        }
        if (facility.juice() == 0) { soakEnded(false); return true; }
        soakTicks++;
        if (soakTicks % JuiceTub.STEP_TICKS == 0) {
            int step = soakTicks / JuiceTub.STEP_TICKS;
            var payout = JuiceTub.payout(soakHurt, step);
            if (payout.heal()) entity.heal(JuiceTub.healthStep(entity.getMaxHealth()));
            if (payout.mend()) entity.body().soakMend();
            entity.life().juiceSoakStep(JuiceTub.washesMarks(step));
            entity.mind().add(-JuiceTub.uneaseStep(entity.life().personality().temperament().timid()), "juice_soak");
        }
        if (soakTicks < JuiceTub.SOAK_TICKS) return false;
        facility.soaked(); soakEnded(true); return true;
    }

    private void soakEnded(boolean used) {
        GameplayLog.event(entity, "play",
                "tub soak ended at={} used={} juice={} -> {} health={} -> {} wounds={} -> {} hygiene={} unease={} -> {}",
                facility.getBlockPos(), used, soakJuice, facility.juice(), soakHealth, entity.getHealth(),
                soakWounds, entity.body().wounds().size(), entity.life().hygiene(), soakUnease, entity.mind().unease());
    }

    public void stop() { stop(true); }

    private void stop(boolean log) {
        if (waitingFor != null) stopWait(log ? "interrupted" : null);
        if (facility != null) {
            if (soaking() && soakTicks > 0 && soakTicks < JuiceTub.SOAK_TICKS) soakEnded(false);
            if (log && entity.outing().onOuting() && entity.outing().calledByOwner() && stopReason == null) {
                stopReason = "called";
                emitBeat("called_home", "surprise", "none", FaceTier.Tier.SHORT, facility.kind());
            }
            facility.release(entity);
            if (log) {
                GameplayLog.event(entity, "play",
                        "facility session ended kind={} at={} completed={} reason={} elapsed={} waypoint={} rounds={} bounces={} stalled={} nutrition={} energy={} boredom={}",
                        facility.kind(), facility.getBlockPos(), completed,
                        stopReason == null ? (completed ? "done" : "interrupted") : stopReason,
                        elapsed, waypoint, rounds, bounces, stalled,
                        entity.life().nutrition(), entity.life().energy(), entity.dailyLife().boredom());
                // Short pause while still wanting to play so the body re-evaluates smoothly; longer rest once satisfied.
                cooldown = PlayWant.wants(entity) ? 20 : 200;
            }
        }
        springing = false; peakVoiced = false; playVoiced = false; stopReason = null;
        facility = null; route = List.of(); interiorLocal = List.of(); entryAt = 0; interiorAt = 0; asideAt = -1; soakAt = -1; soakTicks = 0; soakHurt = false; soakRule = null; lastPos = null;
        if (!log) { pendingBeat = null; gaveUp.clear(); refusedAt.clear(); lastFacility = null; }
        entity.getNavigation().stop();
    }

    public CompoundTag save() {
        var tag = new CompoundTag();
        tag.putInt("Cooldown", cooldown);
        return tag;
    }

    public void load(CompoundTag tag) {
        stop(false);
        cooldown = Math.clamp(tag.getInt("Cooldown"), 0, 24000);
        completed = false;
    }
}
