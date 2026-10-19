package io.github.longshou.mcyukkuri.subspecies;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Predicate;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.phys.Vec3;

/**
 * Server-owned berth state.  Values crossing this boundary are copied so a planner cannot retain
 * an array and silently mutate the authoritative seating arrangement.
 */
final class HatSeatState {
    private final LinkedHashMap<UUID, double[]> residents = new LinkedHashMap<>();
    private final LinkedHashMap<UUID, double[]> incoming = new LinkedHashMap<>();
    private final Map<UUID, HatBoatEntity.SeatMove> moves = new HashMap<>();
    private Vec3 ownerServiceSeat;
    private HatBoatEntity.SeatMove ownerSeatMove;

    int incomingCount() { return incoming.size(); }
    boolean hasIncoming() { return !incoming.isEmpty(); }
    boolean hasIncoming(UUID id) { return incoming.containsKey(id); }
    double[] incomingSeat(UUID id) { return copy(incoming.get(id)); }
    void reserveIncoming(UUID id, double[] seat) { incoming.put(id, copy(seat)); }
    double[] takeIncoming(UUID id) { return copy(incoming.remove(id)); }
    boolean releaseIncoming(UUID id) { return incoming.remove(id) != null; }
    void purgeIncoming(Predicate<UUID> remove) { incoming.keySet().removeIf(remove); }

    boolean hasResident(UUID id) { return residents.containsKey(id); }
    double[] residentSeat(UUID id) { return copy(residents.get(id)); }
    void putResident(UUID id, double[] seat) { residents.put(id, copy(seat)); }
    void putResidentIfAbsent(UUID id, double[] seat) { residents.putIfAbsent(id, copy(seat)); }
    boolean removeResident(UUID id) { return residents.remove(id) != null; }
    void clearResidents() { residents.clear(); }
    Map<UUID, double[]> residentSnapshot() {
        var snapshot = new LinkedHashMap<UUID, double[]>();
        residents.forEach((id, seat) -> snapshot.put(id, copy(seat)));
        return snapshot;
    }
    void forEachResident(BiConsumer<UUID, double[]> consumer) {
        residents.forEach((id, seat) -> consumer.accept(id, copy(seat)));
    }
    void forEachIncoming(BiConsumer<UUID, double[]> consumer) {
        incoming.forEach((id, seat) -> consumer.accept(id, copy(seat)));
    }
    boolean hasSeatBelow(double top) {
        return residents.values().stream().anyMatch(seat -> seat[1] < top)
                || incoming.values().stream().anyMatch(seat -> seat[1] < top);
    }

    HatBoatEntity.SeatMove move(UUID id) { return moves.get(id); }
    void setMove(UUID id, HatBoatEntity.SeatMove move) { moves.put(id, move); }
    void clearMove(UUID id) { moves.remove(id); }
    void clearMoves() { moves.clear(); }
    long latestMoveEndExcept(UUID id) {
        long latest = Long.MIN_VALUE;
        for (var entry : moves.entrySet()) if (!entry.getKey().equals(id)) {
            var move = entry.getValue();
            latest = Math.max(latest, move.start() + move.ticks());
        }
        return latest;
    }

    Vec3 ownerServiceSeat() { return ownerServiceSeat; }
    void ownerServiceSeat(Vec3 seat) { ownerServiceSeat = seat; }
    void clearOwnerServiceSeat() { ownerServiceSeat = null; }
    HatBoatEntity.SeatMove ownerSeatMove() { return ownerSeatMove; }
    void ownerSeatMove(HatBoatEntity.SeatMove move) { ownerSeatMove = move; }
    void clearOwnerSeatMove() { ownerSeatMove = null; }
    void clearAll() {
        residents.clear(); incoming.clear(); moves.clear(); ownerServiceSeat = null; ownerSeatMove = null;
    }

    CompoundTag presentationSnapshot(UUID owner, CompoundTag carry, CompoundTag crowding, boolean overcrowded) {
        var list = new ListTag();
        residents.forEach((id, seat) -> list.add(savedSeat(id, seat, moves.get(id), false)));
        if (owner != null && ownerServiceSeat != null) list.add(savedSeat(owner, ownerServiceSeat, ownerSeatMove, true));
        var tag = new CompoundTag();
        tag.put("S", list);
        if (carry != null) tag.put("Carry", carry);
        tag.put("Crowding", crowding);
        tag.putBoolean("Overcrowded", overcrowded);
        return tag;
    }

    private static CompoundTag savedSeat(UUID id, Vec3 seat, HatBoatEntity.SeatMove move, boolean ownerService) {
        var tag = new CompoundTag(); tag.putUUID("Id", id);
        if (ownerService) tag.putBoolean("OwnerService", true);
        tag.putDouble("X", seat.x); tag.putDouble("Y", seat.y); tag.putDouble("Z", seat.z);
        saveMove(tag, move); return tag;
    }
    private static CompoundTag savedSeat(UUID id, double[] seat, HatBoatEntity.SeatMove move, boolean ownerService) {
        return savedSeat(id, new Vec3(seat[0], seat[1], seat[2]), move, ownerService);
    }
    private static void saveMove(CompoundTag tag, HatBoatEntity.SeatMove move) {
        if (move == null) return;
        tag.putDouble("FromX", move.from().x); tag.putDouble("FromY", move.from().y); tag.putDouble("FromZ", move.from().z);
        tag.putLong("At", move.start()); tag.putInt("Ticks", move.ticks()); tag.putBoolean("Arc", move.arc());
        HatSurfaceRoute.save(tag, move.route());
    }
    private static double[] copy(double[] seat) { return seat == null ? null : seat.clone(); }
}
