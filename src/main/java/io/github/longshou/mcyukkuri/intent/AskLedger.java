package io.github.longshou.mcyukkuri.intent;

import io.github.longshou.mcyukkuri.status.GameplayLog;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.DoubleSupplier;
import net.minecraft.world.entity.Entity;

/**
 * Who helps whom in one incident (spec 11.3). The incident's owner opens it ({@link #open}: a body that fell into the water, a
 * mother asking her mate's boat over) and settles it when it is over ({@link #settle}). Each helper decides once per incident:
 * the first {@link #decide} of an {@code (incident, helper)} draws one roll against the chance and keeps chance, roll and the
 * answer, so every later call (another evaluation, the helper back on its hat) reads the same decision; a refusal stays a
 * refusal. A helper whose run of the asked intent is over is {@link #ended}: it does not take the incident up again. Only
 * settled incidents are evicted, so no pair is ever drawn twice; an owner that died or left the world settles its incident on
 * the next read. Server-wide, bounded by the open incidents, never saved; cleared when the server stops.
 */
public final class AskLedger {
    /** Settled incidents kept (their decisions still answer late reads) before the oldest is evicted. */
    static final int KEEP = 128;
    private static final AskLedger SERVER = new AskLedger();

    /** One draw: which incident, which helper for which requester, the chance, the roll, the answer. */
    public record Decision(String incident, UUID helper, UUID requester, double chance, double roll, boolean accepted) {}

    private static final class Incident {
        final String id, cause;
        final UUID owner;
        final long opened;
        Entity body;
        boolean settled;
        final Map<UUID, Ask> offered = new LinkedHashMap<>();
        final Map<UUID, Decision> decided = new LinkedHashMap<>();
        final Map<UUID, String> ended = new HashMap<>();
        Incident(String id, String cause, Entity body, UUID owner, long opened) { this.id = id; this.cause = cause; this.body = body; this.owner = owner; this.opened = opened; }
    }

    private final Map<String, Incident> incidents = new LinkedHashMap<>();

    public static AskLedger server() { return SERVER; }

    /** Opens an incident {@code owner} owns ({@code cause}: water, share_young ...) and logs {@code [incident] open}; its id. */
    public String open(Entity owner, String cause, String detail) {
        long now = owner.level().getGameTime();
        String id = cause + "#" + owner.getId() + "@" + now;
        prune();
        incidents.put(id, new Incident(id, cause, owner, owner.getUUID(), now));
        GameplayLog.event(owner, "incident", "open id={} cause={} subject={} at={} {}", id, cause, owner.getUUID(), GameplayLog.at(owner.position()), detail);
        return id;
    }

    /** Opens an incident with no live owner (tests and replays): nothing is logged and it is never settled for the owner. */
    String open(String id, UUID owner, String cause, long tick) {
        incidents.put(id, new Incident(id, cause, null, owner, tick));
        return id;
    }

    /** The newest open incident of this cause that {@code owner} owns, or null. */
    public String openOf(UUID owner, String cause) {
        prune();
        String found = null;
        for (var i : incidents.values()) if (!i.settled && i.owner.equals(owner) && i.cause.equals(cause)) found = i.id;
        return found;
    }

    /** Puts an ask before its helper (a directed request); an incident that is not open takes none. */
    public void offer(Ask ask) {
        var i = incidents.get(ask.incidentId());
        if (i != null && !i.settled) i.offered.put(ask.helper(), ask);
    }

    /** The asks offered to {@code helper} in open incidents that it has not refused nor finished. */
    public List<Ask> offered(UUID helper) {
        prune();
        var out = new ArrayList<Ask>();
        for (var i : incidents.values()) {
            var ask = i.offered.get(helper);
            var d = i.decided.get(helper);
            if (ask != null && !i.settled && (d == null || d.accepted()) && !i.ended.containsKey(helper)) out.add(ask);
        }
        return out;
    }

    /**
     * The one decision of {@code ask.helper()} for its incident: drawn now when there is none ({@code roll} gives a uniform
     * 0..1, accepted when below {@code chance}) and logged against {@code at}, else the kept one. Null when the incident is
     * unknown, or settled before this helper ever decided (an incident over is not decided any more).
     */
    public Decision decide(Ask ask, double chance, DoubleSupplier roll, Entity at) {
        prune();
        var i = incidents.get(ask.incidentId());
        if (i == null) return null;
        var known = i.decided.get(ask.helper());
        if (known != null || i.settled) return known;
        double p = Double.isFinite(chance) ? Math.min(Math.max(chance, 0), 1) : 0, r = roll.getAsDouble();
        var d = new Decision(i.id, ask.helper(), ask.requester(), p, r, r < p);
        i.decided.put(ask.helper(), d);
        if (at != null) GameplayLog.event(at, "incident", "decide id={} helper={} requester={} chance={} roll={} accepted={}",
                i.id, d.helper(), d.requester(), Attempts.amount(p), String.format(Locale.ROOT, "%.3f", r), d.accepted());
        return d;
    }

    /** {@link #decide(Ask, double, DoubleSupplier, Entity)} with nothing to log against (tests). */
    Decision decide(Ask ask, double chance, DoubleSupplier roll) { return decide(ask, chance, roll, null); }

    public Decision decision(String incident, UUID helper) {
        var i = incidents.get(incident);
        return i == null ? null : i.decided.get(helper);
    }

    /** It accepted and its run is not over (the incident may be settled already: a carried faller is out of the water). */
    public boolean helping(String incident, UUID helper) {
        var i = incidents.get(incident);
        var d = i == null ? null : i.decided.get(helper);
        return d != null && d.accepted() && !i.ended.containsKey(helper);
    }

    /** The helper's run of the asked intent is over ({@code result}); it does not take this incident up again. */
    public void ended(String incident, UUID helper, String result) {
        var i = incidents.get(incident);
        if (i != null) i.ended.putIfAbsent(helper, result);
    }

    /** The owner closes its incident ({@code reason}); logs {@code [incident] close} with every decision. Idempotent. */
    public void settle(String incident, String reason) {
        var i = incidents.get(incident);
        if (i == null || i.settled) return;
        i.settled = true;
        if (i.body != null) {
            var helpers = new StringBuilder("[");
            i.decided.values().forEach(d -> helpers.append(helpers.length() > 1 ? ", " : "").append(d.helper()).append(':')
                    .append(d.accepted() ? "accepted" : "refused").append('/').append(i.ended.getOrDefault(d.helper(), "-")));
            GameplayLog.event(i.body, "incident", "close id={} reason={} ticks={} offered={} decided={}", i.id, reason,
                    i.body.level().getGameTime() - i.opened, i.offered.size(), helpers.append(']'));
        }
        i.body = null;
        i.offered.clear();
        trim();
    }

    /** Forgets everything (server stop), so a new world never inherits an old incident's decisions. */
    public void clear() { incidents.clear(); }

    /** An owner that died or left the world settles its open incident. */
    private void prune() {
        for (var i : List.copyOf(incidents.values()))
            if (!i.settled && i.body != null && (!i.body.isAlive() || i.body.isRemoved())) settle(i.id, i.body.isAlive() ? "owner_gone" : "owner_died");
    }

    /** Evicts settled incidents beyond {@link #KEEP}, oldest first; an open one is never evicted. */
    private void trim() {
        int settled = 0;
        for (var i : incidents.values()) if (i.settled) settled++;
        for (Iterator<Incident> it = incidents.values().iterator(); settled > KEEP && it.hasNext(); ) {
            if (!it.next().settled) continue;
            it.remove();
            settled--;
        }
    }

    /** Incidents held (open and settled), for tests. */
    int size() { return incidents.size(); }
}
