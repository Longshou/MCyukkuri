package io.github.longshou.mcyukkuri.intent;

import io.github.longshou.mcyukkuri.status.GameplayLog;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.Set;
import java.util.function.DoubleSupplier;
import net.minecraft.world.entity.Entity;

/** Who helps whom in one incident: the owner opens and settles it, and each helper decides once, chance, roll and answer kept, so no pair is drawn twice and a refusal stays one. */
public final class AskLedger {
    /** Settled incidents kept before the oldest is evicted. */
    static final int KEEP = 128;
    private static final AskLedger SERVER = new AskLedger();

    /** One draw: which incident, which helper for which requester, the chance, the roll, the answer. */
    public record Decision(String incident, UUID helper, UUID requester, double chance, double roll, boolean accepted) {}

    private static final class Incident {
        final String id, cause;
        final UUID owner;
        final long opened;
        Entity body;
        final Map<UUID, Decision> decided = new LinkedHashMap<>();
        final Map<UUID, String> ended = new HashMap<>();
        Incident(String id, String cause, Entity body, UUID owner, long opened) { this.id = id; this.cause = cause; this.body = body; this.owner = owner; this.opened = opened; }
    }

    private final Map<String, Incident> open = new LinkedHashMap<>();
    private final Map<String, Incident> settled = new LinkedHashMap<>(16, .75f, false) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Incident> eldest) { return size() > KEEP; }
    };

    public static AskLedger server() { return SERVER; }

    /** Opens an incident {@code owner} owns ({@code cause}: water ...) and logs {@code [incident] open}; its id. */
    public String open(Entity owner, String cause, String detail) {
        long now = owner.level().getGameTime();
        String id = cause + "#" + owner.getId() + "@" + now;
        prune();
        open.put(id, new Incident(id, cause, owner, owner.getUUID(), now));
        GameplayLog.event(owner, "incident", "open id={} cause={} subject={} at={} {}", id, cause, owner.getUUID(), GameplayLog.at(owner.position()), detail);
        return id;
    }

    /** Opens an incident with no live owner (tests and replays): nothing is logged and it is never settled for the owner. */
    String open(String id, UUID owner, String cause, long tick) {
        open.put(id, new Incident(id, cause, null, owner, tick));
        return id;
    }

    /** The newest open incident of this cause that {@code owner} owns, or null. */
    public String openOf(UUID owner, String cause) {
        prune();
        String found = null;
        for (var i : open.values()) if (i.owner.equals(owner) && i.cause.equals(cause)) found = i.id;
        return found;
    }

    /** The one decision of {@code ask.helper()} for its incident: drawn now when there is none ({@code roll} uniform 0..1, accepted below {@code chance}), else the kept one. */
    public Decision decide(Ask ask, double chance, DoubleSupplier roll, Entity at) {
        prune();
        var i = open.get(ask.incidentId());
        if (i == null) {
            var gone = settled.get(ask.incidentId());
            return gone == null ? null : gone.decided.get(ask.helper());
        }
        var known = i.decided.get(ask.helper());
        if (known != null) return known;
        double p = Double.isFinite(chance) ? Math.min(Math.max(chance, 0), 1) : 0, r = roll.getAsDouble();
        var d = new Decision(i.id, ask.helper(), ask.requester(), p, r, r < p);
        i.decided.put(ask.helper(), d);
        if (at != null) GameplayLog.event(at, "incident", "decide id={} helper={} requester={} chance={} roll={} accepted={}",
                i.id, d.helper(), d.requester(), Attempts.amount(p), String.format(Locale.ROOT, "%.3f", r), d.accepted());
        return d;
    }

    /** {@link #decide(Ask, double, DoubleSupplier, Entity)} with nothing to log against (tests). */
    Decision decide(Ask ask, double chance, DoubleSupplier roll) { return decide(ask, chance, roll, null); }

    /** It accepted and its run is not over (the incident may be settled already: a carried faller is out of the water). */
    public boolean helping(String incident, UUID helper) {
        var i = find(incident);
        var d = i == null ? null : i.decided.get(helper);
        return d != null && d.accepted() && !i.ended.containsKey(helper);
    }

    /** Every accepted helper has ended while the owner is still in need: the help given did not get it out. */
    public boolean helpEnded(String incident) {
        var i = open.get(incident);
        return i != null && !i.ended.isEmpty()
                && i.decided.values().stream().noneMatch(d -> d.accepted() && !i.ended.containsKey(d.helper()));
    }

    /** The helper's run of the asked intent is over ({@code result}); it does not take this incident up again. */
    public void ended(String incident, UUID helper, String result) {
        var i = find(incident);
        if (i != null) i.ended.putIfAbsent(helper, result);
    }

    /** The owner closes its incident ({@code reason}); logs {@code [incident] close} with every decision. Idempotent. */
    public void settle(String incident, String reason) {
        var i = open.remove(incident);
        if (i == null) return;
        if (i.body != null) {
            var helpers = new StringBuilder("[");
            i.decided.values().forEach(d -> helpers.append(helpers.length() > 1 ? ", " : "").append(d.helper()).append(':')
                    .append(d.accepted() ? "accepted" : "refused").append('/').append(i.ended.getOrDefault(d.helper(), "-")));
            GameplayLog.event(i.body, "incident", "close id={} reason={} ticks={} decided={}", i.id, reason,
                    i.body.level().getGameTime() - i.opened, helpers.append(']'));
        }
        // a settled incident keeps only its decisions, no body
        i.body = null;
        settled.put(i.id, i);
    }

    /** Forgets everything (server stop), so a new world never inherits an old incident's decisions. */
    public void clear() { open.clear(); settled.clear(); }
    public void forget(Set<UUID> ids) {
        open.values().removeIf(i -> ids.stream().anyMatch(id -> mine(i, id)));
        settled.values().removeIf(i -> ids.stream().anyMatch(id -> mine(i, id)));
    }

    /** The newest incident this body owns or is decided in, read off the kept decisions: who answered and how that run ended, or empty. */
    public String outcome(UUID body) {
        prune();
        Incident found = null;
        for (var i : open.values()) if (mine(i, body)) found = i;
        for (var i : settled.values()) if (mine(i, body)) found = i;
        if (found == null) return "";
        var line = new StringBuilder(found.id).append(found.body == null ? " settled" : " open");
        for (var d : found.decided.values())
            line.append(' ').append(d.helper()).append(':').append(d.accepted() ? "accepted" : "refused").append('/')
                    .append(found.ended.getOrDefault(d.helper(), "-"));
        return line.toString();
    }

    private static boolean mine(Incident i, UUID body) {
        if (i.owner.equals(body)) return true;
        for (var d : i.decided.values()) if (d.helper().equals(body) || d.requester().equals(body)) return true;
        return false;
    }

    private Incident find(String id) {
        var i = open.get(id);
        return i != null ? i : settled.get(id);
    }

    /** An owner that died or left the world settles its open incident (only the open ones are looked at). */
    private void prune() {
        for (var i : List.copyOf(open.values()))
            if (i.body != null && (!i.body.isAlive() || i.body.isRemoved())) settle(i.id, i.body.isAlive() ? "owner_gone" : "owner_died");
    }

    /** Incidents held (open and settled), for tests. */
    int size() { return open.size() + settled.size(); }
}
