package io.github.longshou.mcyukkuri.family;

import io.github.longshou.mcyukkuri.entity.Behaviors;
import io.github.longshou.mcyukkuri.entity.RemainsEntity;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.life.SurfaceApproach;
import io.github.longshou.mcyukkuri.life.TravelRange;
import io.github.longshou.mcyukkuri.mind.EmotionEvent;
import io.github.longshou.mcyukkuri.social.YukkuriPersonality.Trait;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import java.util.LinkedHashMap;
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

    KinAbsence(FamilyLife family) { this.family = family; }

    private YukkuriEntity body() { return family.entity(); }
    private String dimension() { return family.level().dimension().location().toString(); }

    /** Young or anxious watchers begin to miss a regular companion sooner. */
    private int expectation() {
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

    boolean gone(UUID id) {
        var memory = kin.get(id);
        return !present(id) && (memory != null && (memory.confirmed || memory.phase == Phase.MOVED_ON)
                || FamilyData.get(family.level()).knownDead(id));
    }

    private boolean present(UUID id) {
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
                for (var corpse : family.level().getEntitiesOfClass(RemainsEntity.class, e.getBoundingBox().inflate(8), e::hasLineOfSight)) {
                    var data = corpse.data();
                    if (data.hasUUID("Identity") && entry.getKey().equals(data.getUUID("Identity"))) {
                        witness(entry.getKey(), corpse.blockPosition());
                        e.life().personality().experienced(Trait.BOLDNESS, -3);
                        e.bereave(EmotionEvent.FAMILY_DEATH);
                        GameplayLog.event(e, "family", "missing kin found remains kin={} at={}", entry.getKey(), corpse.blockPosition());
                        break;
                    }
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
            searching = entry.getKey();
            if (memory.phase == Phase.WAIT) {
                memory.phase = Phase.DOOR;
                GameplayLog.event(e, "family", "kin overdue kin={} away={} expected={} lastSeen={} stage={}",
                        searching, memory.away, expectation(), memory.seen, family.stageId());
            }
            destination = aim(memory);
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
        return call == null ? Behaviors.SEARCH_FAMILY : call;
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
        if (memory.phase != Phase.SEARCH || !dimension().equals(memory.dimension)) return null;
        double radius = (family.adult() ? 20 : 12) * e.life().personality().exploreMultiplier();
        var origin = Vec3.atBottomCenterOf(home);
        var toward = Vec3.atBottomCenterOf(memory.seen).subtract(origin);
        double distance = toward.horizontalDistance();
        // Walk as far as the remembered trail and the caller's range allow, using the same dry routes as care.
        var direction = toward.multiply(1, 0, 1).normalize();
        var far = origin.add(direction.scale(Math.min(radius, distance + 4))).add(0, toward.y, 0);
        var near = origin.add(direction.scale(Math.min(radius, distance))).add(0, toward.y, 0);
        for (var point : new Vec3[]{far, near}) {
            var stand = SurfaceApproach.floor(e, point);
            if (stand != null && SurfaceApproach.canApproach(e, stand)) return stand;
        }
        return null;
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
        // Bounded physical walks. The same wall is not re-requested after the shared movement reports a refusal.
        var result = destination == null ? SurfaceApproach.Result.BLOCKED : SurfaceApproach.walkResult(e, destination, null, .9);
        if (++memory.legTicks <= 460 && result != SurfaceApproach.Result.BLOCKED && result != SurfaceApproach.Result.ARRIVED) return;
        advance(memory, switch (memory.phase) {
            case DOOR -> Phase.CALL;
            case SEARCH -> Phase.RETURN;
            case RETURN -> Phase.MOVED_ON;
            default -> throw new IllegalStateException("Unexpected absence leg " + memory.phase);
        });
    }

    private void advance(Memory memory, Phase next) {
        var e = body();
        SurfaceApproach.halt(e);
        memory.phase = next;
        memory.legTicks = 0;
        destination = aim(memory);
        GameplayLog.event(e, "family", "kin absence kin={} phase={} destination={} stage={} nutrition={}",
                searching, next, destination, family.stageId(), e.life().nutrition());
        if (next == Phase.MOVED_ON) {
            e.showReaction(baby() ? "cry" : "teary", "lost_parent", 80);
            BirthStaging.tears(e);
            family.stop();
        }
    }

    void stop() {
        var memory = kin.get(searching);
        if (memory != null && memory.phase == Phase.CALL && callStarted)
            memory.phase = baby() ? Phase.MOVED_ON : Phase.SEARCH;
        searching = null;
        destination = null;
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
