package io.github.longshou.mcyukkuri.intent;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;

/** A ritual script: which beat runs, who takes part and what is presented. It never moves a body: a role's goal reads {@link #intentFor} and {@link #report}s how it ended. */
public abstract class Choreography {
    public static final int ROLE_RADIUS = 48;
    /** Bounds an immediate Branch chain, so a wrong table ends the ceremony instead of spinning. */
    private static final int MAX_JUMPS = 8;

    private final String id;
    private final String start;
    private Map<String, Beat> table;
    private final Map<String, UUID> roles = new LinkedHashMap<>();
    /** The intent instances of the current beat, by role; a role whose intent ended is held for the rest of the beat. */
    private final Map<String, Intent> intents = new HashMap<>();
    private String beat;
    private long beatStart;
    private int beatTicks;
    private boolean resume;
    private YukkuriEntity host;

    protected Choreography(String id, String start) {
        this.id = id;
        this.start = start;
    }

    /** The beat table, built once on first use (its beats may read the subclass's own fields). */
    protected abstract Map<String, Beat> table();

    private Map<String, Beat> beats() {
        if (table != null) return table;
        var beats = Map.copyOf(table());
        for (var entry : beats.entrySet()) {
            for (String next : switch (entry.getValue()) {
                case Beat.Perform p -> new String[]{p.next(), p.onTimeout()};
                case Beat.Present p -> new String[]{p.next()};
                case Beat.Await a -> new String[]{a.next(), a.onTimeout()};
                case Beat.Branch ignored -> new String[0];
            }) if (next != null && !beats.containsKey(next))
                throw new IllegalArgumentException("Beat " + entry.getKey() + " points at missing beat " + next);
        }
        if (!beats.containsKey(start)) throw new IllegalArgumentException("Start beat missing: " + start);
        return table = beats;
    }

    /** The beat a lost role goes to (called before it is unbound): null ends the ceremony, the current beat carries on without it. */
    protected String onRoleLost(String role) { return null; }

    /** The ceremony's own clock (labour, births): domain data only, never a body. */
    protected void conductData(long now) {}

    /** Called once each time a beat (not a Branch) starts, before its tick window and intents are built; it may {@link #jump}. */
    protected void entered(String beat) {}

    /** The beat a saved beat resumes as; a beat whose transient data is gone may go back to the beat that builds it. */
    protected String resume(String saved) { return saved; }

    protected void saveData(CompoundTag tag) {}

    protected void loadData(CompoundTag tag) {}

    public final String id() { return id; }
    public final String beat() { return beat; }
    public final boolean running() { return beat != null; }
    public final long beatTick() { return beatStart; }
    public final YukkuriEntity host() { return host; }
    public final UUID role(String role) { return roles.get(role); }
    public final Map<String, UUID> roles() { return Map.copyOf(roles); }

    public final void bind(String role, UUID body) {
        if (body == null) roles.remove(role);
        else roles.put(role, body);
    }

    /** The first role of the current beat's cast that {@code body} plays, or null when the beat does not engage it. */
    public final String engaged(UUID body) {
        Beat current = beat == null ? null : beats().get(beat);
        if (current == null || body == null || resume) return null;
        for (String role : current.cast().keySet()) if (body.equals(roles.get(role))) return role;
        return null;
    }

    /** Some bound role of this body, engaged first. */
    public final String roleOf(UUID body) {
        String engaged = engaged(body);
        if (engaged != null) return engaged;
        for (var entry : roles.entrySet()) if (entry.getValue().equals(body)) return entry.getKey();
        return null;
    }

    /** The intent instance this body's engaged role runs now (one per beat, so one deadline and one budget), or null. */
    public final Intent intentFor(UUID body) {
        String role = engaged(body);
        return role == null ? null : intents.get(role);
    }

    /** The clip of the current Present for this body, or null. */
    public final String present(UUID body) {
        return beat != null && beats().get(beat) instanceof Beat.Present p && engaged(body) != null ? p.clip() : null;
    }

    public final void conduct() {
        if (beat == null || host == null) return;
        long now = now();
        if (resume) {
            resume = false;
            enter(beat, Report.NONE, "load");
            return;
        }
        conductData(now);
        if (beat == null) return;
        Beat current = beats().get(beat);
        for (String role : current.cast().keySet()) {
            UUID body = roles.get(role);
            String why = body == null ? null : lost(body);
            if (why == null) continue;
            String next = onRoleLost(role); // still bound here, so the branch can read what the role left behind
            roles.remove(role);
            intents.remove(role);
            log("role_lost id={} role={} reason={} next={}", id, role, why, next == null ? "end" : next);
            if (!beat.equals(next)) enter(next, new Report(role, "", Report.Verdict.LOST, why), "role_lost");
            return;
        }
        boolean over = now - beatStart >= beatTicks;
        switch (current) {
            case Beat.Perform p -> { if (over) enter(p.onTimeout(), new Report(lead(p), "", Report.Verdict.TIMEOUT, "ticks=" + beatTicks), "timeout"); }
            case Beat.Present p -> { if (over) enter(p.next(), Report.NONE, "present_end"); }
            case Beat.Await a -> {
                if (a.until().getAsBoolean()) enter(a.next(), Report.NONE, "await");
                else if (over) enter(a.onTimeout(), new Report("", "", Report.Verdict.TIMEOUT, "ticks=" + beatTicks), "await_timeout");
            }
            case Beat.Branch ignored -> enter(beat, Report.NONE, "branch");
        }
    }

    /** A role's intent ended. Only the current instance counts; the lead's report ends a Perform, a side job's only frees the role. */
    public final void report(UUID body, Intent intent, IntentRunner.Result result) {
        String role = engaged(body);
        if (role == null || intent == null || intents.get(role) != intent || result == IntentRunner.Result.RUNNING) return;
        intents.remove(role);
        if (beats().get(beat) instanceof Beat.Perform p && lead(p).equals(role)) enter(p.next(), Report.of(role, intent, result), "report");
    }

    /** A jump the domain clock decides (a blocked outlet, a finished brood), logged like every beat change. */
    protected final void jump(String target, String why) { enter(target, new Report("", "", Report.Verdict.NONE, why), why); }

    public final void cancel(String why) {
        if (beat == null) return;
        log("end id={} beat={} reason={}", id, beat, why);
        beat = null;
        intents.clear();
    }

    final void attach(YukkuriEntity body) { host = body; }

    final void begin() { enter(start, Report.NONE, "start"); }

    /** {@code {Beat, Roles:{<Role>:int[4]}, ...domain data}}. */
    public final CompoundTag save() {
        var tag = new CompoundTag();
        if (beat != null) tag.putString("Beat", beat);
        var saved = new CompoundTag();
        roles.forEach((role, body) -> saved.put(role.substring(0, 1).toUpperCase(Locale.ROOT) + role.substring(1), NbtUtils.createUUID(body)));
        tag.put("Roles", saved);
        saveData(tag);
        return tag;
    }

    /** Restores the beat and the roles without touching the world; the first {@link #conduct()} enters the beat again. */
    public final void load(CompoundTag tag) {
        roles.clear();
        intents.clear();
        var saved = tag.getCompound("Roles");
        for (String key : saved.getAllKeys()) if (saved.contains(key, Tag.TAG_INT_ARRAY))
            roles.put(key.substring(0, 1).toLowerCase(Locale.ROOT) + key.substring(1), NbtUtils.loadUUID(saved.get(key)));
        loadData(tag);
        String at = resume(tag.getString("Beat"));
        beat = beats().containsKey(at) ? at : null;
        resume = beat != null;
        log("load id={} beat={} roles={}", id, beat == null ? "none" : beat, roles.keySet());
    }

    private void enter(String target, Report cause, String why) {
        String from = beat;
        Report report = cause;
        String next = target;
        for (int jumps = 0; ; jumps++) {
            if (jumps > MAX_JUMPS || next != null && !beats().containsKey(next)) {
                log("warn id={} beat={} reason=branch_loop next={}", id, from, next);
                next = null;
            }
            log("beat id={} beat={} role={} outcome={} next={}", id, from == null ? "none" : from,
                    report.role().isEmpty() ? "none" : report.role(),
                    report.verdict() == Report.Verdict.NONE ? why.toUpperCase(Locale.ROOT) : report.verdict(), next == null ? "end" : next);
            intents.clear();
            if (next == null) {
                beat = null;
                return;
            }
            beat = next;
            beatStart = now();
            Beat b = beats().get(next);
            if (b instanceof Beat.Branch branch) {
                from = next;
                next = branch.choose().apply(report);
                report = Report.NONE;
                why = "branch";
                continue;
            }
            entered(next);
            if (!next.equals(beat)) return; // entered() jumped on
            beatTicks = switch (b) {
                case Beat.Perform p -> Math.max(1, p.ticks().getAsInt());
                case Beat.Await a -> Math.max(1, a.ticks().getAsInt());
                case Beat.Present p -> p.ticks();
                case Beat.Branch ignored -> 1;
            };
            for (var entry : b.cast().entrySet()) {
                Supplier<Intent> supplier = entry.getValue();
                Intent intent = supplier == null ? null : supplier.get();
                if (intent != null) intents.put(entry.getKey(), intent);
            }
            if (b instanceof Beat.Perform p && !intents.containsKey(lead(p))) {
                // A lead with nothing to do has already done it: the beat ends at once, through its own report path.
                from = next;
                report = new Report(lead(p), "", Report.Verdict.DONE, "nothing_to_do");
                next = p.next();
                why = "report";
                continue;
            }
            return;
        }
    }

    private static String lead(Beat.Perform p) { return p.cast().keySet().iterator().next(); }

    private String lost(UUID body) {
        if (!(host.level() instanceof ServerLevel level)) return null;
        if (!(level.getEntity(body) instanceof YukkuriEntity found)) return "gone";
        if (!found.isAlive()) return "dead";
        return found.distanceToSqr(host) > (double) ROLE_RADIUS * ROLE_RADIUS ? "distance" : null;
    }

    private long now() { return host == null ? 0 : host.level().getGameTime(); }

    private void log(String message, Object... values) { GameplayLog.event(host, "choreo", message, values); }

    /** How a role's intent ended, as a Branch reads it. */
    public record Report(String role, String intent, Verdict verdict, String detail) {
        public enum Verdict { NONE, DONE, BLOCKED, LOST, INTERRUPTED, TIMEOUT }
        public static final Report NONE = new Report("", "", Verdict.NONE, "");

        public boolean done() { return verdict == Verdict.DONE; }

        static Report of(String role, Intent intent, IntentRunner.Result result) {
            return new Report(role, intent.id(), switch (result) {
                case DONE -> Verdict.DONE;
                case EXHAUSTED, RUNNING -> Verdict.BLOCKED;
                case LOST -> Verdict.LOST;
                case INTERRUPTED -> Verdict.INTERRUPTED;
            }, result.name().toLowerCase(Locale.ROOT));
        }
    }
}
