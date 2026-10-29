package io.github.longshou.mcyukkuri.intent;

import io.github.longshou.mcyukkuri.MCYukkuri;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Supplier;

/**
 * The one desire competition of one domain (spec 11.4, design C4): the domain's Goal calls {@link #tick()} and this class owns
 * the domain's single {@link IntentRunner}. Every {@link DriveMath#EVALUATE_EVERY} ticks (staggered by entity id, at once after an
 * intent ends) it scores the desires with the data pack weights, starts the best one with a score above zero, and switches only
 * when a candidate clears the current commitment's margin for ten evaluations running (a danger desire at once). An intent that
 * ended EXHAUSTED or LOST does not compete again until the domain's world signature changed (spec 11.2), so a failure is never
 * chosen again on the same tick. Nothing is saved: after a reload the desires decide again.
 */
public final class Drive {
    /** The live drives by body, for the readout only (weak: a removed body takes its entry with it). */
    private static final Map<YukkuriEntity, List<Drive>> LIVE = new WeakHashMap<>();

    /** One evaluated desire and the score it got with the data weight applied. */
    public record Scored(Desire desire, double score) {}

    private final YukkuriEntity body;
    private final String domain;
    private final Supplier<List<Desire>> desires;
    private final Supplier<IntentRunner.Target> world;
    private final DriveMath.Sustain sustain = new DriveMath.Sustain();
    /** Intent ids that ended EXHAUSTED or LOST, with the world signature they ended under. */
    private final Map<String, WorldSignature> failed = new HashMap<>();
    private List<Scored> ranked = List.of();
    private long rankedAt = Long.MIN_VALUE, decided = Long.MIN_VALUE;
    private IntentRunner runner;
    private Desire current;
    private boolean urgent = true;

    public Drive(YukkuriEntity body, String domain, Supplier<List<Desire>> desires, Supplier<IntentRunner.Target> world) {
        this.body = body;
        this.domain = domain;
        this.desires = desires;
        this.world = world;
        synchronized (LIVE) { LIVE.computeIfAbsent(body, b -> new ArrayList<>()).add(this); }
    }

    public String domain() { return domain; }
    /** The desire being run, or null between intents. */
    public Desire current() { return current; }
    public IntentRunner runner() { return runner; }
    public boolean running() { return runner != null; }
    /** {@code intentId} ended EXHAUSTED or LOST under the world as it still is (it does not compete now). */
    public boolean failed(String intentId) { return failed.containsKey(intentId); }

    /** Some drive of {@code body} has {@code intentId} failed under the world as it still is (another body reads it). */
    public static boolean failed(YukkuriEntity body, String intentId) {
        synchronized (LIVE) { return LIVE.getOrDefault(body, List.of()).stream().anyMatch(d -> d.failed(intentId)); }
    }
    /** Evaluate on the next tick whatever the stagger (water entry, a hit, a fright, an ended intent). */
    public void urgent() { urgent = true; }

    /** Some desire would start now (the Goal's {@code canUse}); evaluates when due. */
    public boolean wants() { return !rank().isEmpty(); }

    /** The Goal's tick, inside a decision: the runner starts its actions through the BodyDriver. Never from a passive clock. */
    public void tick() {
        if (body.level().isClientSide) return;
        IntentRunner r = runner;
        if (r != null) {
            r.tick();
            if (r.result() != IntentRunner.Result.RUNNING) ended(r);
        }
        var list = rank();
        if (runner == null) { if (!list.isEmpty()) start(list.get(0), list); decided = rankedAt; return; }
        // one switching decision per evaluation, so the sustain counts evaluations
        if (decided == rankedAt) return;
        decided = rankedAt;
        Scored best = list.isEmpty() ? null : list.get(0), held = find(list, current.intentId());
        if (best == null || best.desire().intentId().equals(current.intentId())) { sustain.reset(); return; }
        double heldScore = held == null ? 0 : held.score();
        if (!DriveMath.switchable(best.score(), heldScore, runner.intent().commitment(), best.desire().danger())) { sustain.reset(); return; }
        if (DriveMath.needsSustain(best.desire().danger()) && !sustain.observe(best.desire().intentId(), true)) return;
        stop("drive_switch:" + best.desire().intentId());
        start(best, list);
    }

    /** The Goal's {@code stop()}: cancels the running action and ends the current intent INTERRUPTED. */
    public void stop(String why) {
        IntentRunner r = runner;
        runner = null;
        current = null;
        sustain.reset();
        if (r != null) r.stop(why);
        urgent = true;
    }

    /** The data weight of the intent id multiplies the desire (spec 11.4). */
    public double scoreOf(Desire d) { return DriveMath.score(d.desire(), DriveWeights.of(domain, d.intentId()), d.costEstimate()); }

    // ---- one evaluation ----------------------------------------------------------------------------------------------

    /** The desires scored above zero, best first, without the failed ones whose world has not changed; recomputed when due. */
    private List<Scored> rank() {
        long now = now();
        if (!urgent && (rankedAt == now || !DriveMath.due(now, body.getId())) && rankedAt != Long.MIN_VALUE) return ranked;
        urgent = false;
        rankedAt = now;
        var signature = signature();
        failed.values().removeIf(sig -> !sig.equals(signature));
        var out = new ArrayList<Scored>();
        List<Desire> all;
        try { all = desires.get(); } catch (RuntimeException broken) {
            MCYukkuri.LOGGER.warn("[intent] drive desires unreadable domain={} error={}", domain, broken.toString());
            all = List.of();
        }
        for (var d : all) {
            double s = scoreOf(d);
            if (s > 0 && !failed.containsKey(d.intentId())) out.add(new Scored(d, s));
        }
        out.sort(Comparator.comparingDouble(Scored::score).reversed());
        ranked = List.copyOf(out);
        GameplayLog.limited(body, domain, "drive", 200, "drive top={} current={} failed={} fp={}", top(all), current == null ? "none"
                : current.intentId(), failed.keySet(), DriveWeights.fingerprint());
        return ranked;
    }

    /** Starts {@code chosen}; the runner becomes the drive's only once its intent was built and started. */
    private void start(Scored chosen, List<Scored> list) {
        sustain.reset();
        Desire d = chosen.desire();
        try {
            Intent intent = d.intent().get();
            if (intent == null) return;
            var next = new IntentRunner(body, intent, "drive:" + domain).target(world);
            next.start(String.format(Locale.ROOT, "source=drive:%s top=%s why=%s fp=%s", domain, describe(list), d.why(), DriveWeights.fingerprint()));
            runner = next;
            current = d;
        } catch (RuntimeException broken) {
            failed.put(d.intentId(), signature());
            MCYukkuri.LOGGER.warn("[intent] drive intent unusable domain={} id={} error={}", domain, d.intentId(), broken.toString());
        }
    }

    private void ended(IntentRunner r) {
        var result = r.result();
        if (result == IntentRunner.Result.EXHAUSTED || result == IntentRunner.Result.LOST) failed.put(r.intent().id(), signature());
        runner = null;
        current = null;
        urgent = true;
    }

    private WorldSignature signature() {
        var t = world == null ? null : world.get();
        return t == null ? WorldSignature.of(now()) : WorldSignature.of(t.at(), t.host(), t.blockers(), now());
    }

    private static Scored find(List<Scored> list, String id) {
        for (var s : list) if (s.desire().intentId().equals(id)) return s;
        return null;
    }

    /** {@code [a:72, b:40, c:12]}: the top three scores (spec 13.1). */
    private static String describe(List<Scored> list) {
        var out = new StringBuilder("[");
        for (int i = 0; i < list.size() && i < 3; i++) {
            if (i > 0) out.append(", ");
            out.append(list.get(i).desire().intentId()).append(':').append(Math.round(list.get(i).score()));
        }
        return out.append(']').toString();
    }

    /** Every desire with its raw value and the facts it came from, for the rate-limited domain line and the readout. */
    private String top(List<Desire> all) {
        var out = new StringBuilder("[");
        for (var d : all) {
            if (out.length() > 1) out.append(", ");
            out.append(d.intentId()).append(':').append(Math.round(scoreOf(d)));
            if (!d.why().isEmpty()) out.append('(').append(d.why()).append(')');
        }
        return out.append(']').toString();
    }

    /** The {@code <domain>.desire} readout: each drive of this body with its current intent, every desire and the failed ones. */
    public static String status(YukkuriEntity body) {
        List<Drive> drives;
        synchronized (LIVE) { drives = List.copyOf(LIVE.getOrDefault(body, List.of())); }
        if (drives.isEmpty()) return "none";
        var out = new StringBuilder();
        for (var d : drives) {
            List<Desire> all;
            try { all = d.desires.get(); } catch (RuntimeException broken) { all = List.of(); }
            out.append(d.domain).append(" current=").append(d.current == null ? "none" : d.current.intentId()).append(" desires=")
                    .append(d.top(all)).append(" failed=").append(d.failed.keySet()).append(' ');
        }
        return out.toString().trim();
    }

    private long now() { return body.level().getGameTime(); }
}
