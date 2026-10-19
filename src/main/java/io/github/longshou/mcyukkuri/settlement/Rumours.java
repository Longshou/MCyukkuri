package io.github.longshou.mcyukkuri.settlement;

import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import java.util.Comparator;
import java.util.Objects;

/** What a community has come to believe about humans and pets, from arcs its members really witnessed (pet/social/BadgeArcs). At most six per group;
 *  a rumour lives three game days and loses one point of strength per day. Only read by the badge-social policy (elders warn, pleas get bolder or shyer). */
public final class Rumours {
    public static final int HUMAN_KIND = 0, BADGE_FED = 1, THIEF = 2, BULLY = 3, WARY_HUMAN = 4;
    public static final int MAX = 6, MAX_STRENGTH = 3;
    public static final long LIFETIME = 72000L, DAY = 24000L;
    public record Rumour(int kind, UUID subject, int strength, long expiresAt) {}
    private Rumours() {}

    /**
     * Strengthen an existing rumour about the same subject or add a new one (the weakest is dropped when full). The same
     * rumour heard again within a day changes nothing: otherwise, an elder's warning could re-spread the wary rumour that
     * made the warning likelier, so it would never fade.
     */
    public static void add(SettlementData data, SettlementData.Group group, int kind, UUID subject, int strength, long now) {
        if (data == null || group == null || strength <= 0) return;
        group.rumours.removeIf(r -> r.expiresAt() <= now);
        for (int i = 0; i < group.rumours.size(); i++) {
            var r = group.rumours.get(i);
            if (r.kind() == kind && Objects.equals(r.subject(), subject)) {
                if (now - (r.expiresAt() - LIFETIME) < DAY) return;
                group.rumours.set(i, new Rumour(kind, subject, Math.min(MAX_STRENGTH, r.strength() + strength), now + LIFETIME));
                data.setDirty(); return;
            }
        }
        if (group.rumours.size() >= MAX) {
            var weakest = group.rumours.stream().min(Comparator.comparingInt(Rumour::strength).thenComparingLong(Rumour::expiresAt)).orElse(null);
            if (weakest != null) group.rumours.remove(weakest);
        }
        group.rumours.add(new Rumour(kind, subject, Math.min(MAX_STRENGTH, strength), now + LIFETIME));
        data.setDirty();
    }

    /** Current strength (0 = not believed) of a kind of rumour, summed over subjects and capped. */
    public static int strength(SettlementData.Group group, int kind, long now) {
        if (group == null) return 0;
        int total = 0;
        for (var r : group.rumours) {
            if (r.kind() != kind || r.expiresAt() <= now) continue;
            long age = now - (r.expiresAt() - LIFETIME);
            total += Math.max(0, r.strength() - (int)Math.max(0, age / DAY));
        }
        return Math.min(MAX_STRENGTH, total);
    }
    /** Strength of a kind of rumour about one subject. */
    public static int strength(SettlementData.Group group, int kind, UUID subject, long now) {
        if (group == null) return 0;
        int total = 0;
        for (var r : group.rumours) {
            if (r.kind() != kind || r.expiresAt() <= now || !Objects.equals(r.subject(), subject)) continue;
            long age = now - (r.expiresAt() - LIFETIME);
            total += Math.max(0, r.strength() - (int)Math.max(0, age / DAY));
        }
        return Math.min(MAX_STRENGTH, total);
    }

    static ListTag save(SettlementData.Group group) {
        var list = new ListTag();
        for (var r : group.rumours) {
            var n = new CompoundTag(); n.putInt("Kind", r.kind()); n.putInt("Strength", r.strength()); n.putLong("Expires", r.expiresAt());
            if (r.subject() != null) n.putUUID("Subject", r.subject());
            list.add(n);
        }
        return list;
    }
    static void load(ListTag list, SettlementData.Group group) {
        group.rumours.clear();
        for (Tag raw : list) {
            if (group.rumours.size() >= MAX) break;
            var n = (CompoundTag)raw;
            group.rumours.add(new Rumour(Math.clamp(n.getInt("Kind"), 0, 15), n.hasUUID("Subject") ? n.getUUID("Subject") : null,
                    Math.clamp(n.getInt("Strength"), 1, MAX_STRENGTH), n.getLong("Expires")));
        }
    }
}
