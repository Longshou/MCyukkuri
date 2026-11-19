package io.github.longshou.mcyukkuri.family;

import io.github.longshou.mcyukkuri.entity.Behaviors;
import io.github.longshou.mcyukkuri.entity.RemainsEntity;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.life.Duty.End;
import io.github.longshou.mcyukkuri.life.SurfaceApproach;
import io.github.longshou.mcyukkuri.life.TravelRange;
import io.github.longshou.mcyukkuri.mind.EmotionEvent;
import io.github.longshou.mcyukkuri.social.YukkuriPersonality.Trait;
import io.github.longshou.mcyukkuri.social.EcologyPresentation;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.phys.Vec3;

/** First-hand memory of a missing parent or mate. The ledger's global death and position are private to the world. */
final class KinAbsence {
    private enum Phase { WAIT, DOOR, CALL, SEARCH, RETURN, MOVED_ON }
    private static final class Memory {
        BlockPos seen;
        String dimension;
        int away, legTicks;
        Phase phase = Phase.WAIT;
        boolean confirmed;

        Memory(BlockPos seen, String dimension) {
            this.seen = seen;
            this.dimension = dimension;
        }
    }

    private final FamilyLife family;
    private final Map<UUID, Memory> kin = new LinkedHashMap<>(2);
    private UUID searching;
    private Vec3 destination;
    private boolean callStarted;
    private List<Vec3> sweep = List.of();
    private int index, pause;

    KinAbsence(FamilyLife family) { this.family = family; }

    private YukkuriEntity body() { return family.entity(); }
    private String dimension() { return family.level().dimension().location().toString(); }

    /** Young or anxious watchers begin to miss a regular companion sooner. */
    int expectation() {
        int base = family.adult() ? 1100 : baby() ? 620 : 800;
        return Math.clamp(base + 3 * body().life().personality().selfControl() - (int) (body().mind().unease() * 2), 520, 1400);
    }

    private boolean baby() { return family.stage() == FamilyLife.Stage.BABY; }
    boolean overdue(UUID id) {
        var memory = kin.get(id);
        return memory != null && (memory.confirmed || memory.away >= expectation());
    }

    boolean movedOn() { return kin.values().stream().anyMatch(m -> m.phase == Phase.MOVED_ON); }

    boolean expectingReturn(UUID id) {
        var memory = kin.get(id);
        return memory != null && memory.away > 0 && !memory.confirmed && memory.phase != Phase.MOVED_ON;
    }

    int away(UUID id) { return kin.get(id).away; }

    BlockPos lastSeen(UUID id) {
        var memory = kin.get(id);
        return memory == null ? null : memory.seen;
    }

    boolean gone(UUID id) {
        var memory = kin.get(id);
        return !present(id) && (memory != null && (memory.confirmed || memory.phase == Phase.MOVED_ON)
                || FamilyData.get(family.level()).knownDead(id));
    }

    boolean present(UUID id) {
        return id != null && family.level().getEntity(id) instanceof YukkuriEntity peer && peer.isAlive()
                && body().distanceToSqr(peer) <= 16 * 16 && body().life().canRecognize(peer)
                && (body().hasLineOfSight(peer) || FamilySeparation.near(body(), peer));
    }

    String describe() {
        return kin.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue().phase + "/" + e.getValue().away
                + (e.getValue().confirmed ? "/confirmed" : "")).toList() + " expected=" + expectation();
    }

    void observe() {
        var e = body();
        if (e.life().isSleeping() || e.isPassenger() || family.awaitingLanding()) return;
        if (family.adult() && FamilyProvision.home(e) == null) return;
        var mate = family.partner();
        if (family.adult()) {
            if (mate != null) {
                kin.keySet().removeIf(id -> !mate.equals(id));
                observe(mate);
            } else for (var id : kin.keySet()) observe(id);
        } else {
            UUID foster = family.fosterParent();
            kin.keySet().removeIf(id -> !id.equals(family.mother()) && !id.equals(family.father()) && !id.equals(foster));
            observe(family.mother());
            observe(family.father());
            observe(foster);
        }
        for (var entry : kin.entrySet()) {
            var memory = entry.getValue();
            if (!memory.confirmed && memory.away >= expectation()) {
                var corpse = RemainsEntity.find(family.level(), e, entry.getKey(), 8);
                if (corpse != null) {
                    witness(entry.getKey(), corpse.blockPosition());
                    e.life().personality().experienced(Trait.BOLDNESS, -3);
                    e.bereave(EmotionEvent.FAMILY_DEATH, entry.getKey(), corpse.blockPosition());
                    GameplayLog.event(e, "family", "missing kin found remains kin={} at={}", entry.getKey(), corpse.blockPosition());
                }
            }
            if (memory.away > 0 && !memory.confirmed && baby() && memory.phase == Phase.MOVED_ON
                    && memory.away % 1200 == 0 && !e.hasReaction() && family.atHome()) {
                e.showReaction("cry", "lost_parent", 80);
                BirthStaging.tears(e);
            }
        }
    }

    private void observe(UUID id) {
        if (id == null) return;
        var memory = kin.get(id);
        var e = body();
        if (present(id) && family.level().getEntity(id) instanceof YukkuriEntity peer) {
            if (memory != null && overdue(id) && !memory.confirmed) {
                GameplayLog.event(e, "family", "missing kin reunited kin={} away={} at={}", id, memory.away, peer.blockPosition());
                if (!e.grieving()) {
                    e.showReaction("happy", "caregiver", 80);
                    BirthStaging.affection(e);
                }
            }
            if (memory == null || memory.away != 0 || memory.confirmed) kin.put(id, new Memory(peer.blockPosition(), dimension()));
            else {
                memory.seen = peer.blockPosition();
                memory.dimension = dimension();
            }
        } else if (memory != null && !memory.confirmed && memory.away < 1_000_000) {
            memory.away += 20;
        }
    }

    void witness(UUID id, BlockPos at) {
        var memory = kin.get(id);
        if (memory == null && !(id.equals(family.mother()) || id.equals(family.father())
                || id.equals(family.partner()) || id.equals(family.fosterParent()))) return;
        if (memory == null) {
            memory = new Memory(at, dimension());
            kin.put(id, memory);
        }
        memory.confirmed = true;
        memory.phase = Phase.MOVED_ON;
    }

    boolean confirmed(UUID id) {
        var memory = kin.get(id);
        return memory != null && memory.confirmed;
    }

    boolean start() {
        var e = body();
        if (e.life().isSleeping() || e.grieving() || e.isPassenger() || e.forageTrip().outing()
                || !FamilyLife.ordinaryLandFree(e) || family.familyTravelling()) return false;
        var home = FamilyProvision.home(e);
        if (home == null || e.distanceToSqr(Vec3.atBottomCenterOf(home)) > 24 * 24) return false;
        for (var entry : kin.entrySet()) {
            var memory = entry.getValue();
            if (memory.confirmed || memory.phase == Phase.MOVED_ON || memory.away < expectation()) continue;
            if (!entry.getKey().equals(searching)) sweep = List.of();
            searching = entry.getKey();
            if (memory.phase == Phase.WAIT) {
                memory.phase = Phase.DOOR;
                GameplayLog.event(e, "family", "kin overdue kin={} away={} expected={} lastSeen={} stage={}",
                        searching, memory.away, expectation(), memory.seen, family.stageId());
            }
            if (memory.phase == Phase.SEARCH) {
                if (sweep.isEmpty()) beginSweep(home, memory);
            } else destination = aim(memory);
            return true;
        }
        return false;
    }

    boolean active() {
        var memory = kin.get(searching);
        return memory != null && memory.away > 0 && !memory.confirmed && memory.phase != Phase.MOVED_ON
                && !body().grieving() && !body().life().isSleeping() && !body().isPassenger();
    }

    String activity() {
        var call = family.call().activity();
        return call != null ? call : pause > 0 ? Behaviors.CALL_LISTEN : Behaviors.SEARCH_FAMILY;
    }

    End outcome() {
        var memory = kin.get(searching);
        if (present(searching) || memory != null && (memory.confirmed || memory.away == 0)) return End.DONE;
        if (memory == null || memory.phase == Phase.MOVED_ON || memory.phase == Phase.CALL && callStarted && baby())
            return End.FAILED;
        return null;
    }

    private Vec3 aim(Memory memory) {
        var e = body();
        var home = FamilyProvision.home(e);
        if (home == null) return null;
        if (memory.phase == Phase.DOOR) {
            var door = FamilyProvision.entrance(e);
            // The covered threshold is the furthest safe call stand when the outside lip is beyond a baby's leash.
            if (!TravelRange.babyDestination(e, door)) door = home;
            return TravelRange.babyDestination(e, door) ? SurfaceApproach.floor(e, Vec3.atBottomCenterOf(door)) : null;
        }
        if (memory.phase == Phase.RETURN) return FamilyProvision.entranceStand(e);
        return null;
    }

    private void beginSweep(BlockPos home, Memory memory) {
        sweep = List.of();
        index = pause = memory.legTicks = 0;
        destination = null;
        if (home == null || !dimension().equals(memory.dimension)) return;
        var e = body();
        var p = e.life().personality();
        double radius = (family.adult() ? 20 : 12) * e.life().personality().exploreMultiplier();
        var origin = Vec3.atBottomCenterOf(home);
        var toward = Vec3.atBottomCenterOf(memory.seen).subtract(origin);
        var shelter = e.habitat().shelter();
        double heading = shelter == null ? 0 : Math.toDegrees(Math.atan2(shelter.facing().getStepZ(), shelter.facing().getStepX()));
        var points = new ArrayList<Vec3>();
        var offsets = FamilyDutyMath.searchSweep(toward.x, toward.z, heading, radius,
                FamilyDutyMath.searchWaypoints(p.attachment(), p.selfControl()), FamilyDutyMath.searchSpread(p.intelligence()));
        for (var offset : offsets) points.add(origin.add(offset[0], toward.y, offset[1]));
        sweep = points;
        destination = searchStand(sweep.getFirst());
    }

    private Vec3 searchStand(Vec3 point) {
        var stand = SurfaceApproach.floor(body(), point);
        return stand != null && SurfaceApproach.canApproach(body(), stand) ? stand : null;
    }

    private void tickSweep(Memory memory) {
        var e = body();
        if (sweep.isEmpty()) {
            advance(memory, Phase.RETURN);
            return;
        }
        if (pause > 0) {
            boolean calling = index % 2 == 0 && family.call().tickAbsence();
            if (++pause > EcologyPresentation.duration(Behaviors.CALL_LISTEN) && !calling) nextWaypoint(memory);
            return;
        }
        var result = destination == null ? SurfaceApproach.Result.BLOCKED : SurfaceApproach.walkResult(e, destination, null, .9);
        boolean timeout = ++memory.legTicks > 460;
        if (!timeout && result != SurfaceApproach.Result.ARRIVED && result != SurfaceApproach.Result.BLOCKED) return;
        GameplayLog.event(e, "family", "kin search waypoint kin={} index={}/{} at={} result={}",
                searching, index + 1, sweep.size(), GameplayLog.at(destination == null ? sweep.get(index) : destination),
                timeout ? "TIMEOUT" : result);
        if (destination == null) {
            nextWaypoint(memory);
            return;
        }
        SurfaceApproach.halt(e);
        pause = 1;
        if (index % 2 == 0) family.call().beginAbsence(searching, sweep.get(index));
    }

    private void nextWaypoint(Memory memory) {
        if (++index == sweep.size()) advance(memory, Phase.RETURN);
        else {
            memory.legTicks = pause = 0;
            destination = searchStand(sweep.get(index));
        }
    }

    void tick() {
        var e = body();
        var memory = kin.get(searching);
        if (memory.phase == Phase.CALL) {
            if (!callStarted) {
                var toward = dimension().equals(memory.dimension) ? Vec3.atBottomCenterOf(memory.seen) : e.position();
                family.call().beginAbsence(searching, toward);
                callStarted = true;
            }
            if (family.call().tickAbsence()) return;
            advance(memory, baby() ? Phase.MOVED_ON : Phase.SEARCH);
            return;
        }
        if (memory.phase == Phase.SEARCH) {
            tickSweep(memory);
            return;
        }
        // Bounded physical walks. The same wall is not re-requested after the shared movement reports a refusal.
        var result = destination == null ? SurfaceApproach.Result.BLOCKED : SurfaceApproach.walkResult(e, destination, null, .9);
        if (++memory.legTicks <= 460 && result != SurfaceApproach.Result.BLOCKED && result != SurfaceApproach.Result.ARRIVED) return;
        advance(memory, switch (memory.phase) {
            case DOOR -> Phase.CALL;
            case RETURN -> Phase.MOVED_ON;
            default -> throw new IllegalStateException("Unexpected absence leg " + memory.phase);
        });
    }

    private void advance(Memory memory, Phase next) {
        var e = body();
        SurfaceApproach.halt(e);
        memory.phase = next;
        memory.legTicks = 0;
        if (next == Phase.SEARCH) beginSweep(FamilyProvision.home(e), memory);
        else {
            sweep = List.of();
            pause = 0;
            destination = aim(memory);
        }
        GameplayLog.event(e, "family", "kin absence kin={} phase={} destination={} stage={} nutrition={}",
                searching, next, next == Phase.SEARCH && !sweep.isEmpty() ? sweep.getFirst() : destination,
                family.stageId(), e.life().nutrition());
        if (next == Phase.MOVED_ON) {
            e.showReaction(baby() ? "cry" : "teary", "lost_parent", 80);
            BirthStaging.tears(e);
            family.stop();
        }
    }

    void stop() {
        var memory = kin.get(searching);
        if (memory != null && memory.phase == Phase.CALL && callStarted) {
            memory.phase = baby() ? Phase.MOVED_ON : Phase.SEARCH;
            if (memory.phase == Phase.MOVED_ON)
                GameplayLog.event(body(), "family", "kin absence kin={} phase=MOVED_ON destination=null stage={} nutrition={} reason=call_interrupted",
                        searching, family.stageId(), body().life().nutrition());
            sweep = List.of();
        }
        if (memory == null || memory.phase == Phase.MOVED_ON || memory.phase == Phase.WAIT) {
            searching = null;
            sweep = List.of();
            pause = 0;
            destination = null;
        }
        callStarted = false;
    }

    void save(CompoundTag tag) {
        var list = new ListTag();
        kin.forEach((id, memory) -> {
            var value = new CompoundTag();
            value.putUUID("Kin", id);
            value.putLong("Seen", memory.seen.asLong());
            value.putString("Dimension", memory.dimension);
            value.putInt("Away", memory.away);
            value.putInt("Phase", memory.phase.ordinal());
            value.putInt("LegTicks", memory.legTicks);
            value.putBoolean("Confirmed", memory.confirmed);
            list.add(value);
        });
        if (!list.isEmpty()) tag.put("KinAbsence", list);
    }

    void load(CompoundTag tag) {
        kin.clear();
        for (var raw : tag.getList("KinAbsence", Tag.TAG_COMPOUND)) {
            var value = (CompoundTag) raw;
            if (!value.hasUUID("Kin") || kin.size() >= 3) continue;
            var memory = new Memory(BlockPos.of(value.getLong("Seen")), value.getString("Dimension"));
            memory.away = Math.clamp(value.getInt("Away"), 0, 1_000_000);
            memory.phase = Phase.values()[Math.clamp(value.getInt("Phase"), 0, Phase.values().length - 1)];
            memory.legTicks = Math.clamp(value.getInt("LegTicks"), 0, 460);
            memory.confirmed = value.getBoolean("Confirmed");
            kin.put(value.getUUID("Kin"), memory);
        }
    }
}
