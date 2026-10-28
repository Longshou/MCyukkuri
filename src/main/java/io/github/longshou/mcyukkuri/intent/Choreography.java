package io.github.longshou.mcyukkuri.intent;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.motor.Outcome;
import io.github.longshou.mcyukkuri.motor.Reason;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

/**
 * A ritual script (spec 11.5, design P13): the beats of one ceremony, the roles that take part, and the report that moves
 * the table. The choreography decides <em>when, in what order, who and what is presented</em>; it never moves a body, never
 * calls {@code BodyDriver.run} and never edits another entity. A role's own goal asks
 * {@link #roleIntent(UUID)} / {@link #present(UUID)} and runs the intent through its own {@link IntentRunner}.
 *
 * <p>The beat table is an ordered map {@code id -> Beat} with explicit successors (see {@link Beat}); a null successor
 * ends the choreography. {@link #conduct()} is the conductor's passive clock: it advances the time beats ({@link
 * Beat.Present}, {@link Beat.Await}) and re-checks role binding every tick and before each beat starts. {@code report} is the other way
 * in: the role says how its intent ended and a {@link Beat.Branch} picks the next beat.
 *
 * <p>Roles live only here ({@link #roles()}): a role goal finds its choreography through
 * {@link Conductor#roleOf(YukkuriEntity)}, which scans live conductors; nothing stores "who is in which choreography" on
 * the body (design red line 9). Before each beat starts every bound role is checked once — alive, in this level, within
 * {@value #ROLE_RADIUS} blocks of the conductor. A lost role is unbound and the choreography jumps to
 * {@link #onRoleLost(String)} (null ends it); it never waits for the role to come back.
 *
 * <p>Saving keeps only the beat id and the role UUIDs (spec 12). Domain data is appended by {@link #saveData} /
 * {@link #loadData}. A reloaded {@link Beat.Perform} beat builds its intent again from the start.
 */
public abstract class Choreography {
    /** How far a bound role may be from the conductor before the role counts as lost (blocks, spec 11.5). */
    public static final int ROLE_RADIUS = 48;
    /** Bounds an immediate Branch-to-Branch chain so a wrong table ends the choreography instead of recursing. */
    private static final int MAX_JUMPS = 8;
    /** Bounds role-loss jumps (not counted in MAX_JUMPS) so an onRoleLost that rebinds to a dead body cannot spin. */
    private static final int MAX_LOSSES = 16;

    private final String start;
    private final Map<String, Beat> beats;
    private final Map<String, UUID> roles = new LinkedHashMap<>();
    private String beat;
    private long beatStart;
    private Report last = Report.NONE;
    private String performRole;
    private Intent performIntent;
    private YukkuriEntity host;

    /**
     * @param start the beat the choreography begins with; must be a key of {@code beats}
     * @param beats the ordered beat table; successors that are not null must name a beat in the same table
     */
    protected Choreography(String start, Map<String, Beat> beats) {
        this.start = Objects.requireNonNull(start, "start");
        Objects.requireNonNull(beats, "beats");
        Map<String, Beat> table = new LinkedHashMap<>(beats);
        if (!table.containsKey(start)) throw new IllegalArgumentException("Start beat missing: " + start);
        for (var entry : table.entrySet()) {
            String id = entry.getKey();
            Beat value = entry.getValue();
            if (value == null) throw new IllegalArgumentException("Beat " + id + " has no definition");
            if (id == null || id.isBlank()) throw new IllegalArgumentException("A beat needs an id");
            if (value instanceof Beat.Perform perform) {
                require(table, id, perform.next());
                require(table, id, perform.onTimeout());
            }
            if (value instanceof Beat.Present present) require(table, id, present.next());
            if (value instanceof Beat.Await await) {
                require(table, id, await.next());
                require(table, id, await.onTimeout());
            }
        }
        this.beats = Collections.unmodifiableMap(table);
    }

    /** Lower-case with underscores; the {@code id=} field of every {@code [choreo]} line. */
    public abstract String id();

    /** Domain data saved next to the beat and the roles; the base class owns those two keys. */
    protected void saveData(CompoundTag tag) {}

    /** Reads what {@link #saveData} wrote; runs before the beat is restored. */
    protected void loadData(CompoundTag tag) {}

    /**
     * The beat a lost role goes to, or null to end the choreography. Called once per lost role, after the role has been
     * unbound, so the branch may rebind that role to a new body.
     */
    protected String onRoleLost(String role) { return null; }

    // ---- the table ---------------------------------------------------------------------------------------------

    /** Role name to the body playing it; immutable view of the single store. */
    public final Map<String, UUID> roles() { return Collections.unmodifiableMap(roles); }

    /** The current beat id, or null once the choreography ended. */
    public final String beat() { return beat; }

    /** Whether a beat is running. */
    public final boolean running() { return beat != null; }

    /** The current beat, or null. */
    public final Beat currentBeat() { return beat == null ? null : beats.get(beat); }

    /** The last story report (a role report or an await timeout), or {@link Report#NONE} before the first. */
    public final Report lastReport() { return last; }

    /** The game tick the current beat started on (clip phase for a presentation). */
    public final long beatTick() { return beatStart; }

    /** The whole beat table, ordered. */
    public final Map<String, Beat> beats() { return beats; }

    /** Binds (or rebinds) a role. The conductor checks the body when the next beat starts. */
    public final Choreography bind(String role, UUID body) {
        if (role == null || role.isBlank() || body == null) return this;
        roles.put(role.trim(), body);
        Conductor.invalidate();
        return this;
    }

    /** Drops a role without a loss branch (the ceremony declares it will not use that role any more). */
    public final void unbind(String role) {
        if (role != null) {
            roles.remove(role.trim());
            Conductor.invalidate();
        }
    }

    /** The role name bound to {@code body}, or null. The reverse of {@link #roles()}. */
    public final String roleOf(UUID body) {
        if (body == null) return null;
        for (var entry : roles.entrySet()) if (body.equals(entry.getValue())) return entry.getKey();
        return null;
    }

    // ---- what a role's own goal reads --------------------------------------------------------------------------

    /**
     * The intent of the current {@link Beat.Perform} for this role, or null. The same intent object is returned for the
     * whole beat, so a goal that ticks every game tick starts one runner with one deadline and one attempt budget.
     */
    public final Intent roleIntent(UUID role) {
        if (performIntent == null) return null;
        String name = roleOf(role);
        return name != null && name.equals(performRole) ? performIntent : null;
    }

    /**
     * The presentation published for this role by the current {@link Beat.Present}, or empty. The role's own goal plays
     * it on its own body (clip/expression/sound) and stops when {@link Presentation#finished(long)}; the conductor owns
     * the beat's clock and advances the table itself, so the role never reports a presentation.
     */
    public final Optional<Presentation> present(UUID role) {
        if (beat == null) return Optional.empty();
        if (!(beats.get(beat) instanceof Beat.Present present)) return Optional.empty();
        String name = roleOf(role);
        if (name == null || !name.equals(present.role())) return Optional.empty();
        return Optional.of(new Presentation(beat, present.role(), present.clip(), present.expression(),
                present.sound(), present.ticks(), beatStart));
    }

    // ---- the two ways the table moves --------------------------------------------------------------------------

    /**
     * Domain data that advances with the ceremony's own clock (a labour counter, a phase, an outlet choice); called by
     * {@link #conduct()} before the beat table is advanced. It may change this choreography's data and the table through
     * {@link #report} / {@link #cancel}, but never a body (design red line 8).
     */
    protected void conductData(long now) {}

    /**
     * The conductor's passive clock. Advances the beats that are purely time or condition based, and nothing else: no
     * body write, no {@code BodyDriver.run}, no change to another entity. Called once per conductor tick; it re-checks the roles of the running beat first, and gives a Perform its finite window.
     */
    public final void conduct() {
        if (beat == null || host == null) return;
        long now = now();
        conductData(now);
        if (beat == null) return;
        // Every tick, also the first one after a load: a role that left is a branch, not a wait.
        if (lostRole() != null) {
            enter(beat, Report.NONE, "role_check");
            return;
        }
        if (beats.get(beat) instanceof Beat.Perform perform) {
            if (now - beatStart >= perform.ticks()) {
                enter(perform.onTimeout(), new Report(perform.role(), performIntent == null ? "" : performIntent.id(),
                        Report.Verdict.TIMEOUT, null, 0, "timeout ticks=" + perform.ticks()), "perform_timeout");
            }
            return;
        }
        if (beats.get(beat) instanceof Beat.Present present) {
            if (now - beatStart >= present.ticks()) {
                // A presentation ending is not a story outcome: the sticky last report (the Perform's) survives it.
                enter(present.next(), Report.NONE, "present_end");
            }
            return;
        }
        if (beats.get(beat) instanceof Beat.Await await) {
            if (await.condition().getAsBoolean()) {
                enter(await.next(), Report.NONE, "await");
                return;
            }
            if (now - beatStart >= await.ticks()) {
                enter(await.onTimeout(), new Report("", "", Report.Verdict.TIMEOUT, null, 0,
                        "timeout ticks=" + await.ticks()), "await_timeout");
            }
        }
    }

    /**
     * A role finished its {@link Beat.Perform}: the report moves the table to that beat's explicit next beat. Only the
     * intent instance of the current Perform counts; a replaced runner's late end is dropped.
     */
    public final void report(UUID role, Intent intent, Outcome outcome) {
        if (outcome == null || !(beats.get(beat) instanceof Beat.Perform perform)) return;
        if (!performs(role, intent)) return;
        enter(perform.next(), Report.of(roleOf(role), performIntent, outcome), "report");
    }

    /** As {@link #report(UUID, Intent, Outcome)} for a role whose {@link IntentRunner} ended. */
    public final void report(UUID role, Intent intent, IntentRunner.Result result) {
        if (result == null || result == IntentRunner.Result.RUNNING) return;
        if (!(beats.get(beat) instanceof Beat.Perform perform)) return;
        if (!performs(role, intent)) return;
        enter(perform.next(), Report.of(roleOf(role), performIntent, result), "report");
    }

    /** Ends the choreography now (owner died, replacement, reload): logs {@code [choreo] end} and clears the beat. */
    public final void cancel(String why) {
        if (beat == null) return;
        finish(beat, why == null ? "cancelled" : why);
    }

    /**
     * Starts the declared first beat. The domain binds the roles first; a role that is already lost goes straight to its
     * loss branch.
     */
    public final void begin() {
        enter(start, Report.NONE, "start");
    }

    // ---- saving (spec 12: only the beat, the roles and the domain's own data) -----------------------------------

    /** {@code {Beat:string, Roles:{<role>:int[4]}, ...domain data}}. */
    public final CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        if (beat != null) tag.putString("Beat", beat);
        CompoundTag roleTag = new CompoundTag();
        for (var entry : roles.entrySet()) {
            if (entry.getValue() != null) roleTag.put(entry.getKey(), NbtUtils.createUUID(entry.getValue()));
        }
        tag.put("Roles", roleTag);
        saveData(tag);
        return tag;
    }

    /**
     * Restores the beat and the roles; a {@link Beat.Perform} beat builds its intent again, from the top (spec 11.5).
     * Does not run the role check: a conductor may load before the roles' chunks are back.
     */
    public final void load(CompoundTag tag) {
        if (tag == null) return;
        Conductor.invalidate();
        roles.clear();
        CompoundTag roleTag = tag.getCompound("Roles");
        for (String name : roleTag.getAllKeys()) {
            if (!roleTag.contains(name, Tag.TAG_INT_ARRAY)) continue;
            try {
                roles.put(name, NbtUtils.loadUUID(roleTag.get(name)));
            } catch (RuntimeException ignored) {
                // A malformed UUID is a dead role, not a saved one: skip it and let onRoleLost name the branch.
            }
        }
        loadData(tag);
        String saved = tag.getString("Beat");
        performRole = null;
        performIntent = null;
        if (saved.isEmpty() || !beats.containsKey(saved)) {
            beat = null;
            log("load id={} beat=none roles={}", id(), roles.keySet());
            return;
        }
        beat = saved;
        beatStart = now();
        last = Report.NONE;
        if (beats.get(saved) instanceof Beat.Perform perform) {
            performRole = perform.role();
            performIntent = perform.intent().get();
        }
        log("load id={} beat={} roles={}", id(), beat, roles.keySet());
    }

    // ---- internals ---------------------------------------------------------------------------------------------

    /** Whether this body plays the role the current Perform waits for, with that beat's own intent instance. */
    private boolean performs(UUID role, Intent intent) {
        String name = roleOf(role);
        return intent != null && intent == performIntent && name != null && name.equals(performRole);
    }

    /** The conductor hands its body over so role UUIDs resolve and logs carry the conductor's identity. */
    final void attach(YukkuriEntity body) {
        this.host = body;
    }

    /** Ends the beat table and logs; the conductor clears its reference separately. */
    private void finish(String from, String why) {
        log("end id={} beat={} reason={}", id(), from == null ? "none" : from, why);
        Conductor.invalidate();
        beat = null;
        performRole = null;
        performIntent = null;
        beatStart = 0;
    }

    /**
     * Moves the table. Before each beat starts the bound roles are checked; a lost role is unbound and the choreography
     * jumps to {@link #onRoleLost(String)}, never waiting. A Branch is applied in the same tick, bounded by
     * {@link #MAX_JUMPS}. A null target ends the choreography.
     */
    private void enter(String target, Report cause, String why) {
        String from = beat;
        Report report = cause == null ? Report.NONE : cause;
        String next = target;
        int jumps = 0;
        int losses = 0;
        while (true) {
            if (jumps > MAX_JUMPS || losses > MAX_LOSSES) {
                String loop = jumps > MAX_JUMPS ? "branch_loop" : "role_loop";
                log("warn id={} beat={} reason={}", id(), from == null ? "none" : from, loop);
                finish(from, loop);
                return;
            }
            if (next == null) {
                finish(from, why);
                return;
            }
            Beat nextBeat = beats.get(next);
            if (nextBeat == null) {
                log("warn id={} beat={} reason=no_such_beat next={}", id(), from == null ? "none" : from, next);
                finish(from, "no_such_beat:" + next);
                return;
            }
            RoleLoss lost = lostRole();
            if (lost != null) {
                losses++; // role-loss jumps are not branch jumps (MAX_JUMPS)
                roles.remove(lost.role());
                Conductor.invalidate();
                String branch = onRoleLost(lost.role());
                log("role_lost id={} role={} uuid={} reason={} next={}", id(), lost.role(), lost.body(),
                        lost.reason(), branch == null ? "end" : branch);
                report = new Report(lost.role(), "", Report.Verdict.LOST, null, 0, lost.reason());
                from = beat;
                next = branch;
                continue;
            }
            beat = next;
            beatStart = now();
            performRole = null;
            performIntent = null;
            if (nextBeat instanceof Beat.Perform perform) {
                performRole = perform.role();
                performIntent = perform.intent().get();
            }
            if (report.verdict() != Report.Verdict.NONE) last = report;
            log("beat id={} beat={} role={} outcome={} next={}", id(), from == null ? "none" : from,
                    report.role().isEmpty() ? "none" : report.role(),
                    report.verdict() == Report.Verdict.NONE ? why.toUpperCase(Locale.ROOT) : report.verdict(), next);
            if (nextBeat instanceof Beat.Branch branch) {
                from = next;
                next = branch.choose().apply(report);
                jumps++;
                report = Report.NONE;
                continue;
            }
            return;
        }
    }

    /** The first bound role that is not alive, in this level and within {@link #ROLE_RADIUS}, or null. */
    private RoleLoss lostRole() {
        if (host == null || roles.isEmpty()) return null;
        if (!(host.level() instanceof ServerLevel level)) return null;
        for (var entry : List.copyOf(roles.entrySet())) {
            UUID id = entry.getValue();
            if (id == null) continue;
            Entity found = level.getEntity(id);
            if (!(found instanceof YukkuriEntity body)) return new RoleLoss(entry.getKey(), id, "gone");
            if (!body.isAlive() || body.isRemoved()) return new RoleLoss(entry.getKey(), id, "dead");
            if (body.distanceToSqr(host) > (double) ROLE_RADIUS * ROLE_RADIUS) {
                return new RoleLoss(entry.getKey(), id, "distance");
            }
        }
        return null;
    }

    private long now() {
        return host == null ? 0 : host.level().getGameTime();
    }

    private void log(String message, Object... values) {
        GameplayLog.event(host, "choreo", message, values);
    }

    private static void require(Map<String, Beat> table, String from, String next) {
        if (next != null && !table.containsKey(next)) {
            throw new IllegalArgumentException("Beat " + from + " points at missing beat " + next);
        }
    }

    private record RoleLoss(String role, UUID body, String reason) {}

    /** One role's report to the choreography: who, which intent, how it ended, and the measured failure. */
    public record Report(String role, String intent, Verdict verdict, Reason reason, double shortfall,
                         String detail) {
        /** The story outcome a {@link Beat.Branch} reads. */
        public enum Verdict { NONE, DONE, BLOCKED, LOST, INTERRUPTED, TIMEOUT }

        /**
         * No story report: the first beat starts with this, and a time beat that ended normally (a {@link Present} or
         * an {@link Await} whose condition held) does not replace the last role report or await timeout.
         */
        public static final Report NONE = new Report("", "", Verdict.NONE, null, 0, "");

        public Report {
            role = role == null ? "" : role;
            intent = intent == null ? "" : intent;
            verdict = verdict == null ? Verdict.NONE : verdict;
            detail = detail == null ? "" : detail;
        }

        public boolean done() { return verdict == Verdict.DONE; }

        /** Maps one action end onto the report a Branch decides on (spec 9.2 classes stay as they are). */
        public static Report of(String role, Intent intent, Outcome outcome) {
            String id = intent == null ? "" : intent.id();
            return switch (outcome) {
                case Outcome.Done done -> new Report(role, id, Verdict.DONE, null, 0, done.detail());
                case Outcome.Blocked blocked -> new Report(role, id, Verdict.BLOCKED, blocked.reason(),
                        blocked.shortfall(), blocked.detail());
                case Outcome.Incapable incapable -> new Report(role, id, Verdict.BLOCKED, incapable.reason(), 0,
                        incapable.detail());
                case Outcome.Lost lost -> new Report(role, id, Verdict.LOST, lost.reason(), 0, lost.detail());
                case Outcome.Interrupted interrupted -> new Report(role, id, Verdict.INTERRUPTED, null, 0,
                        interrupted.why());
            };
        }

        /** Maps one {@link IntentRunner} end onto the report a Branch decides on. */
        public static Report of(String role, Intent intent, IntentRunner.Result result) {
            String id = intent == null ? "" : intent.id();
            return switch (result) {
                case DONE -> new Report(role, id, Verdict.DONE, null, 0, "done");
                case EXHAUSTED -> new Report(role, id, Verdict.BLOCKED, null, 0, "exhausted");
                case LOST -> new Report(role, id, Verdict.LOST, null, 0, "lost");
                case INTERRUPTED -> new Report(role, id, Verdict.INTERRUPTED, null, 0, "interrupted");
                case RUNNING -> new Report(role, id, Verdict.NONE, null, 0, "running");
            };
        }
    }

    /** A {@link Beat.Present} as one role's goal reads it: what to play, for how long and since when. */
    public record Presentation(String beat, String role, String clip, String expression, String sound, int ticks,
                               long startTick) {
        /** Ticks elapsed since the presentation started, at {@code now}. */
        public long elapsed(long now) { return Math.max(0, now - startTick); }

        /** Whether the presentation's own window has passed. */
        public boolean finished(long now) { return elapsed(now) >= ticks; }
    }
}
