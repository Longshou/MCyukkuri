package io.github.longshou.mcyukkuri.family;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

/** The parent's durable choice for one child-care need, independent of the route used to serve it. */
public final class CareDecisionLedger {
    public enum Need { FEED, FIRST_TOILET, NUZZLE, LULLABY }
    public record Result(boolean accepted, boolean newDecision) {}

    private static final int DECISION_TICKS = 1200, MAX_DECISIONS = 64;
    private record Key(UUID child, Need need) {}
    private record Decision(boolean accepted, long until) {}

    private final YukkuriEntity parent;
    private final Map<Key, Decision> decisions = new HashMap<>();

    CareDecisionLedger(YukkuriEntity parent) { this.parent = parent; }

    /** Reads a refusal without opening another choice. */
    public boolean refused(YukkuriEntity child, Need need) {
        if (child == null) return false;
        var decision = decisions.get(new Key(child.getUUID(), need));
        return decision != null && !decision.accepted() && now() < decision.until();
    }

    /** Reuses a still-live result; only an absent or expired result rolls the parent's real willingness. */
    public Result decide(YukkuriEntity child, Need need, boolean urgent) {
        if (child == null) return new Result(false, false);
        long now = now();
        trim(now);
        var key = new Key(child.getUUID(), need);
        var previous = decisions.get(key);
        if (previous != null) return new Result(previous.accepted(), false);
        float chance = parent.life().personality().helpProbability(true, urgent);
        float roll = parent.getRandom().nextFloat();
        boolean accepted = roll < chance;
        long until = now + DECISION_TICKS;
        decisions.put(key, new Decision(accepted, until));
        trim(now);
        GameplayLog.limited(parent, "family", "care.decision." + need.name().toLowerCase() + "." + child.getUUID(), 200,
                "care decision kind={} child={} urgent={} chance={} roll={} accepted={} until={}",
                need, child.getUUID(), urgent, chance, roll, accepted, until);
        return new Result(accepted, true);
    }

    /** An active care route keeps its accepted episode alive without opening or changing a decision. */
    public boolean touch(YukkuriEntity child, Need need) {
        return child != null && touch(child.getUUID(), need);
    }

    /** An active care route with only the child's retained identity can renew its accepted episode. */
    public boolean touch(UUID child, Need need) {
        if (child == null) return false;
        long now = now();
        var key = new Key(child, need);
        var decision = decisions.get(key);
        if (decision == null || !decision.accepted() || decision.until() <= now) return false;
        decisions.put(key, new Decision(true, now + DECISION_TICKS));
        return true;
    }

    /** A completed handoff or release ends this need's episode; a cancelled retry keeps its decision. */
    public void complete(YukkuriEntity child, Need need) {
        if (child != null) complete(child.getUUID(), need);
    }

    /** Completes an active route that retained only the child's identity. */
    public void complete(UUID child, Need need) {
        if (child != null) decisions.remove(new Key(child, need));
    }

    /** Passive inspection only: no trim, decision, or random roll. */
    public String describe() {
        long now = now();
        String active = decisions.entrySet().stream().filter(entry -> now < entry.getValue().until())
                .sorted(Comparator.comparing((Map.Entry<Key, Decision> entry) -> entry.getKey().need().name())
                        .thenComparing(entry -> entry.getKey().child().toString()))
                .map(entry -> "need=" + entry.getKey().need().name().toLowerCase() + " child=" + entry.getKey().child()
                        + " accepted=" + entry.getValue().accepted() + " remaining=" + (entry.getValue().until() - now))
                .collect(Collectors.joining(";"));
        return active.isEmpty() ? "none" : active;
    }

    CompoundTag save() {
        trim(now());
        var tag = new CompoundTag();
        var list = new ListTag();
        decisions.forEach((key, decision) -> {
            var entry = new CompoundTag();
            entry.putUUID("Child", key.child());
            entry.putString("Need", key.need().name());
            entry.putBoolean("Accepted", decision.accepted());
            entry.putLong("Until", decision.until());
            list.add(entry);
        });
        tag.put("Entries", list);
        return tag;
    }

    void load(CompoundTag tag) {
        decisions.clear();
        for (var raw : tag.getList("Entries", Tag.TAG_COMPOUND)) {
            if (!(raw instanceof CompoundTag entry) || !entry.hasUUID("Child") || !entry.contains("Need", Tag.TAG_STRING)) continue;
            try {
                var need = Need.valueOf(entry.getString("Need"));
                decisions.put(new Key(entry.getUUID("Child"), need), new Decision(entry.getBoolean("Accepted"), entry.getLong("Until")));
            } catch (IllegalArgumentException ignored) {
                // A malformed or future decision kind has no safe route to resume.
            }
        }
        trim(now());
    }

    private long now() { return parent.level().getGameTime(); }

    private void trim(long now) {
        decisions.entrySet().removeIf(entry -> entry.getValue().until() <= now);
        while (decisions.size() > MAX_DECISIONS) {
            var oldest = decisions.entrySet().stream().min(Comparator.comparingLong(entry -> entry.getValue().until()))
                    .map(Map.Entry::getKey).orElse(null);
            if (oldest == null) return;
            decisions.remove(oldest);
        }
    }
}
