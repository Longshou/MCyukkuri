package io.github.longshou.mcyukkuri.house;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.family.FamilyData;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import net.minecraft.core.GlobalPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;

/**
 * Persisted source-nest itinerary for one physical clutch transfer.  The source keeps the batch membership and the
 * one in-mouth ticket; target-side reservation only prevents another source from consuming its reserved slots.
 */
final class HouseEggRelocation {
    enum Phase { FORWARD, ROLLBACK, HANDOFF, ROLLBACK_HANDOFF }

    record Incoming(GlobalPos source, UUID sourceIdentity, int remaining) {
        boolean from(HouseNestBlockEntity nest) {
            return source != null && nest.getLevel() != null && source.dimension().equals(nest.getLevel().dimension()) && source.pos().equals(nest.getBlockPos())
                    && sourceIdentity != null && sourceIdentity.equals(nest.identity());
        }
        Incoming remaining(int next) { return new Incoming(source, sourceIdentity, Math.max(0, next)); }
        Incoming consumed() { return remaining(remaining - 1); }
        CompoundTag save() {
            var tag = new CompoundTag();
            FamilyData.putPosition(tag, "Source", source);
            if (sourceIdentity != null) tag.putUUID("SourceIdentity", sourceIdentity);
            tag.putInt("Remaining", remaining);
            return tag;
        }
        static Incoming load(CompoundTag tag) {
            var source = FamilyData.position(tag, "Source");
            if (source == null || !tag.hasUUID("SourceIdentity")) return null;
            return new Incoming(source, tag.getUUID("SourceIdentity"), Math.max(0, tag.getInt("Remaining")));
        }
    }

    private final GlobalPos target;
    private final UUID targetIdentity;
    private final List<HouseEggs.Ref> refs;
    /** Parents recorded from the clutch ledger at reservation time; the last handoff waits for this exact roster. */
    private final List<UUID> parents;
    /** Parents that actually reached the target during handoff and must return if the target fails before closure. */
    private final List<UUID> arrivedParents = new ArrayList<>();
    private final List<HouseEggs.Ref> moved = new ArrayList<>();
    private Phase phase = Phase.FORWARD;
    private UUID carrier;
    private HouseEggs.Ref carrying;
    private boolean carryingBack;

    HouseEggRelocation(GlobalPos target, UUID targetIdentity, List<HouseEggs.Ref> refs, List<UUID> parents) {
        this.target = target;
        this.targetIdentity = targetIdentity;
        this.refs = new ArrayList<>(refs);
        this.parents = new ArrayList<>();
        for(var parent:parents)if(parent!=null&&!this.parents.contains(parent))this.parents.add(parent);
    }

    GlobalPos target() { return target; }
    UUID targetIdentity() { return targetIdentity; }
    Phase phase() { return phase; }
    boolean targets(HouseNestBlockEntity nest) {
        return target != null && nest.getLevel() != null && target.dimension().equals(nest.getLevel().dimension())
                && target.pos().equals(nest.getBlockPos()) && targetIdentity.equals(nest.identity());
    }
    boolean forward() { return phase == Phase.FORWARD; }
    boolean rollback() { return phase == Phase.ROLLBACK; }
    boolean handoff() { return phase == Phase.HANDOFF; }
    boolean rollbackHandoff() { return phase == Phase.ROLLBACK_HANDOFF; }
    boolean empty() { return refs.isEmpty(); }
    boolean carrying() { return carrying != null; }
    boolean carryingBack() { return carryingBack; }
    HouseEggs.Ref carryingRef() { return carrying; }
    boolean ownedBy(UUID id) { return carrier != null && carrier.equals(id); }
    boolean readyFor(UUID id) { return carrier == null || ownedBy(id); }
    boolean matches(UUID id, HouseEggs.Ref ref) {
        return carrier != null && id.equals(carrier) && carrying != null && carrying.equals(ref);
    }
    boolean moved(HouseEggs.Ref ref) { return moved.contains(ref); }
    /** Pending parents are durable handoff obligations.  Each is removed only after actual destination ownership or a terminal ledger outcome. */
    List<UUID> parents() { return List.copyOf(parents); }
    boolean handoffParent(UUID id) { return id != null && parents.remove(id); }
    boolean arrivedParent(UUID id) {
        if (!handoffParent(id)) return false;
        if (!arrivedParents.contains(id)) arrivedParents.add(id);
        return true;
    }
    List<UUID> arrivedParents() { return List.copyOf(arrivedParents); }
    boolean returnedParent(UUID id) { return id != null && arrivedParents.remove(id); }
    int pendingParents() { return parents.size(); }
    int returningParents() { return arrivedParents.size(); }
    HouseEggs.Ref nextForward() {
        for (var ref : refs) if (!moved.contains(ref) && !ref.equals(carrying)) return ref;
        return null;
    }
    HouseEggs.Ref nextRollback() {
        for (var ref : moved) if (!ref.equals(carrying)) return ref;
        return null;
    }
    boolean beginCarry(UUID id, HouseEggs.Ref ref, boolean back) {
        if (carrier != null || ref == null || back != rollback() || !refs.contains(ref)
                || back && !moved.contains(ref) || !back && moved.contains(ref)) return false;
        carrier = id; carrying = ref; carryingBack = back; return true;
    }
    void returnedToOrigin() { carrier = null; carrying = null; carryingBack = false; }
    boolean depositedForward(UUID id, HouseEggs.Ref ref) {
        if (!matches(id, ref) || carryingBack || !forward()) return false;
        moved.add(ref); returnedToOrigin();
        if (moved.size() == refs.size()) phase = Phase.HANDOFF;
        return true;
    }
    boolean depositedRollback(UUID id, HouseEggs.Ref ref) {
        if (!matches(id, ref) || !carryingBack || !rollback()) return false;
        moved.remove(ref); returnedToOrigin();
        if (moved.isEmpty() && !arrivedParents.isEmpty()) phase = Phase.ROLLBACK_HANDOFF;
        return moved.isEmpty() && arrivedParents.isEmpty();
    }
    void startRollback() {
        phase = Phase.ROLLBACK;
        // Parents still listed here never left the source; only already-arrived parents remain rollback obligations.
        parents.clear();
        if (moved.isEmpty() && carrying == null && !arrivedParents.isEmpty()) phase = Phase.ROLLBACK_HANDOFF;
    }
    void completeHandoff() { parents.clear(); arrivedParents.clear(); }
    /** Source-owned close gate.  Each phase keeps only the obligations that can still require real world work. */
    boolean readyToClose() {
        if (carrying()) return false;
        if (handoff()) return parents.isEmpty();
        if (rollback() || rollbackHandoff()) return moved.isEmpty() && arrivedParents.isEmpty();
        // No ticket reached the target, so parent handoff never started and its original roster has no remaining work.
        return forward() && refs.isEmpty() && moved.isEmpty();
    }
    boolean rollbackComplete() { return (rollback() || rollbackHandoff()) && moved.isEmpty() && !carrying() && arrivedParents.isEmpty(); }
    int remainingAtSource() { return refs.size() - moved.size() - (carrying != null && !carryingBack ? 1 : 0); }
    int movedCount() { return moved.size(); }
    /** Slots still needed for this source's future forward legs, including the egg currently in its carrier's mouth. */
    int incomingReservation() { return forward() ? Math.max(0, refs.size() - moved.size()) : 0; }
    /**
     * FamilyData is authoritative for terminal eggs.  A live ref must still be in its recorded source or target, or in
     * the named loaded carrier's mouth; a player or another nest taking it is an external custody change, never a
     * successful migration or hatch.
     */
    boolean reconcile(ServerLevel level, HouseNestBlockEntity source) {
        boolean changed = false;
        for (var ref : List.copyOf(refs)) {
            var entry = HouseEggs.resolve(level, ref);
            if (entry == null) {
                changed |= remove(level, source, ref, "family_terminal");
                continue;
            }
            boolean atSource = HouseEggs.belongs(entry, source);
            boolean atTarget = HouseEggs.belongs(entry, target, targetIdentity);
            if (ref.equals(carrying)) {
                int carrierState = carrierState(level, ref);
                if (carrierState == 1 || carrierState == -1) continue; // Loaded carrier holds it, or carrier chunk is absent.
                if (!carryingBack && atSource || carryingBack && atTarget) {
                    returnedToOrigin(); changed = true; continue;
                }
                changed |= remove(level, source, ref, carrierState == -2 ? "carrier_terminal" : "carrier_custody_changed");
            } else if (moved.contains(ref) ? !atTarget : !atSource) {
                changed |= remove(level, source, ref, "external_custody");
            }
        }
        if (carrying != null && !refs.contains(carrying)) returnedToOrigin();
        if (forward() && !refs.isEmpty() && moved.size() == refs.size() && carrying == null) phase = Phase.HANDOFF;
        if (rollback() && moved.isEmpty() && carrying == null && !arrivedParents.isEmpty()) phase = Phase.ROLLBACK_HANDOFF;
        return changed;
    }
    /** 1 means the loaded named parent has the exact ticket; -1 waits for an unloaded carrier; -2 is a ledger-confirmed terminal carrier. */
    private int carrierState(ServerLevel level, HouseEggs.Ref ref) {
        if (carrier == null) return 0;
        var holder = level.getEntity(carrier);
        if (holder == null) {
            var person = FamilyData.get(level).person(carrier);
            return person != null && (person.status == FamilyData.Status.DEAD || person.status == FamilyData.Status.REMOVED || person.status == FamilyData.Status.LOST) ? -2 : -1;
        }
        return holder instanceof YukkuriEntity y && y.isAlive() && HouseEggs.ticket(level, y.house().carrying(), ref) ? 1 : -2;
    }
    private boolean remove(ServerLevel level, HouseNestBlockEntity source, HouseEggs.Ref ref, String reason) {
        if (!refs.remove(ref)) return false;
        moved.remove(ref);
        if (ref.equals(carrying)) returnedToOrigin();
        GameplayLog.world(level, "house", "egg relocation reference removed source={} destination={} child={} reason={}",
                source.getBlockPos(), target.pos(), ref.child(), reason);
        return true;
    }
    String status() {
        int source = remainingAtSource();
        int inFlight = carrying == null ? 0 : 1;
        int arrived = moved.size() - (carryingBack ? 1 : 0);
        return "target=" + target.pos().toShortString() + " source=" + source + " inflight=" + inFlight + " arrived=" + arrived
                + " reserved=" + incomingReservation() + " parents=" + pendingParents() + " returning=" + returningParents()
                + " phase=" + phase.name().toLowerCase(Locale.ROOT) + " handoff=" + handoff();
    }

    CompoundTag save() {
        var tag = new CompoundTag();
        FamilyData.putPosition(tag, "Target", target);
        tag.putUUID("TargetIdentity", targetIdentity);
        tag.putString("Phase", phase.name());
        var all = new ListTag();
        for (var ref : refs) all.add(ref.save());
        tag.put("Refs", all);
        var transferred = new ListTag();
        for (var ref : moved) transferred.add(ref.save());
        tag.put("Moved", transferred);
        var handoffParents = new ListTag();
        for (var parent : parents) { var entry = new CompoundTag(); entry.putUUID("Id", parent); handoffParents.add(entry); }
        tag.put("Parents", handoffParents);
        var returnedParents = new ListTag();
        for (var parent : arrivedParents) { var entry = new CompoundTag(); entry.putUUID("Id", parent); returnedParents.add(entry); }
        tag.put("ArrivedParents", returnedParents);
        if (carrier != null) tag.putUUID("Carrier", carrier);
        if (carrying != null) tag.put("Carrying", carrying.save());
        tag.putBoolean("CarryingBack", carryingBack);
        return tag;
    }

    static HouseEggRelocation load(CompoundTag tag) {
        var target = FamilyData.position(tag, "Target");
        if (target == null || !tag.hasUUID("TargetIdentity")) return null;
        var refs = refs(tag.getList("Refs", Tag.TAG_COMPOUND));
        var parents = parents(tag.getList("Parents", Tag.TAG_COMPOUND));
        var relocation = new HouseEggRelocation(target, tag.getUUID("TargetIdentity"), refs, parents);
        for (var parent : parents(tag.getList("ArrivedParents", Tag.TAG_COMPOUND))) if (!relocation.arrivedParents.contains(parent)) relocation.arrivedParents.add(parent);
        for (var ref : refs(tag.getList("Moved", Tag.TAG_COMPOUND))) if (refs.contains(ref)) relocation.moved.add(ref);
        try { relocation.phase = Phase.valueOf(tag.getString("Phase")); } catch (IllegalArgumentException ignored) { }
        // A completed clutch may have hatched before a delayed parent handoff; preserve that handoff across reload.
        if (refs.isEmpty() && !relocation.handoff() && !relocation.rollbackHandoff()) return null;
        if (refs.isEmpty() && parents.isEmpty() && relocation.arrivedParents.isEmpty()) return null;
        if (tag.hasUUID("Carrier") && tag.contains("Carrying", Tag.TAG_COMPOUND)) {
            var carrying = HouseEggs.Ref.load(tag.getCompound("Carrying"));
            if (carrying != null && refs.contains(carrying)) {
                relocation.carrier = tag.getUUID("Carrier");
                relocation.carrying = carrying;
                relocation.carryingBack = tag.getBoolean("CarryingBack");
            }
        }
        if (relocation.handoff()) {
            relocation.carrier = null;
            relocation.carrying = null;
            relocation.carryingBack = false;
        }
        if (relocation.rollback() && relocation.moved.isEmpty() && relocation.carrying == null) return null;
        return relocation;
    }

    private static List<HouseEggs.Ref> refs(ListTag list) {
        var refs = new ArrayList<HouseEggs.Ref>();
        for (Tag raw : list) if (raw instanceof CompoundTag tag) {
            var ref = HouseEggs.Ref.load(tag);
            if (ref != null && !refs.contains(ref)) refs.add(ref);
        }
        return refs;
    }
    private static List<UUID> parents(ListTag list) {
        var parents = new ArrayList<UUID>();
        for (Tag raw : list) if (raw instanceof CompoundTag tag && tag.hasUUID("Id")) {
            var parent = tag.getUUID("Id");
            if (!parents.contains(parent)) parents.add(parent);
        }
        return parents;
    }
}
