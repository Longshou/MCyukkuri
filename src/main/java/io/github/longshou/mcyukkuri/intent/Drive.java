package io.github.longshou.mcyukkuri.intent;

import io.github.longshou.mcyukkuri.MCYukkuri;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;
import java.util.function.Supplier;

/**
 * The one desire competition of one domain (spec 11.4, design C4): the domain's Goal calls {@link #tick()} and this class owns
 * the domain's single {@link IntentRunner}. Every {@link DriveMath#EVALUATE_EVERY} ticks (staggered by entity id, at once after an
 * intent ends) it scores the desires with the data pack weights, starts the best one with a score above zero, and switches only
 * when a candidate clears the current commitment's margin for ten evaluations running (a danger desire at once). An intent that
 * ended EXHAUSTED or LOST does not compete again until the stable facts its desire was read from ({@link Desire#world()}) or the
 * {@link WorldSignature} time bucket changed, so a failure is never chosen again on the same facts. Nothing is saved.
 */
public final class Drive {
    /** The live drives by body, for the readout only: weak keys and weak values, so neither keeps a removed body. */
    private static final Map<YukkuriEntity, List<WeakReference<Drive>>> LIVE = new WeakHashMap<>();

    /** One evaluated desire and the score it got with the data weight applied. */
    public record Scored(Desire desire, double score) {}

    /** The facts a failed intent's desire was read from and the time bucket it failed in. */
    private record Failure(Object world, long bucket) {}

    private final YukkuriEntity body;
    private final String domain;
    private final Supplier<List<Desire>> desires;
    private final Supplier<IntentRunner.Target> target;
    private final DriveMath.Sustain sustain = new DriveMath.Sustain();
    private final Map<String, Failure> failed = new HashMap<>();
    private List<Scored> ranked = List.of();
    private long rankedAt = Long.MIN_VALUE, decided = Long.MIN_VALUE;
    private IntentRunner runner;
    private Desire current;
    private boolean urgent = true;

    /** {@code target} keys the runner's own method exclusion (spec 11.2); the drive's failure exclusion uses each desire's world. */
    public Drive(YukkuriEntity body, String domain, Supplier<List<Desire>> desires, Supplier<IntentRunner.Target> target) {
        this.body = body;
        this.domain = domain;
        this.desires = desires;
        this.target = target;
        synchronized (LIVE) { LIVE.computeIfAbsent(body, b -> new ArrayList<>()).add(new WeakReference<>(this)); }
    }

    public String domain() { return domain; }
    /** The desire being run, or null between intents. */
    public Desire current() { return current; }
    public IntentRunner runner() { return runner; }
    public boolean running() { return runner != null; }
    /** {@code intentId} ended EXHAUSTED or LOST on facts that still hold (it does not compete now). */
    public boolean failed(String intentId) { return failed.containsKey(intentId); }

    /** Some drive of {@code body} has {@code intentId} failed on facts that still hold (another body reads it). */
    public static boolean failed(YukkuriEntity body, String intentId) {
        return drives(body).stream().anyMatch(d -> d.failed(intentId));
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

    /** The desires scored above zero, best first, without the failed ones whose facts still hold; recomputed when due. */
    private List<Scored> rank() {
        long now = now();
        if (!urgent && (rankedAt == now || !DriveMath.due(now, body.getId())) && rankedAt != Long.MIN_VALUE) return ranked;
        urgent = false;
        rankedAt = now;
        List<Desire> all;
        try { all = desires.get(); } catch (RuntimeException broken) {
            MCYukkuri.LOGGER.warn("[intent] drive desires unreadable domain={} error={}", domain, broken.toString());
            all = List.of();
        }
        long bucket = bucket();
        for (var d : all) {
            var f = failed.get(d.intentId());
            if (f != null && (f.bucket() != bucket || !Objects.equals(f.world(), d.world()))) failed.remove(d.intentId());
        }
        failed.values().removeIf(f -> f.bucket() != bucket);
        var out = new ArrayList<Scored>();
        for (var d : all) {
            double s = scoreOf(d);
            if (s > 0 && !failed.containsKey(d.intentId())) out.add(new Scored(d, s));
        }
        out.sort(Comparator.comparingDouble(Scored::score).reversed());
        ranked = List.copyOf(out);
        var shown = all;
        GameplayLog.limited(body, domain, "drive", 200, "drive top={} current={} failed={} fp={}", lazy(() -> top(shown)),
                current == null ? "none" : current.intentId(), failed.keySet(), DriveWeights.fingerprint());
        return ranked;
    }

    /** Starts {@code chosen}; the runner becomes the drive's only once its intent was built and started. */
    private void start(Scored chosen, List<Scored> list) {
        sustain.reset();
        Desire d = chosen.desire();
        try {
            Intent intent = d.intent().get();
            if (intent == null) return;
            var next = new IntentRunner(body, intent, "drive:" + domain).target(target);
            next.start(String.format(Locale.ROOT, "source=drive:%s top=%s why=%s fp=%s", domain, describe(list), d.why().get(), DriveWeights.fingerprint()));
            runner = next;
            current = d;
        } catch (RuntimeException broken) {
            failed.put(d.intentId(), new Failure(d.world(), bucket()));
            MCYukkuri.LOGGER.warn("[intent] drive intent unusable domain={} id={} error={}", domain, d.intentId(), broken.toString());
        }
    }

    private void ended(IntentRunner r) {
        var result = r.result();
        if ((result == IntentRunner.Result.EXHAUSTED || result == IntentRunner.Result.LOST) && current != null)
            failed.put(current.intentId(), new Failure(current.world(), bucket()));
        runner = null;
        current = null;
        urgent = true;
    }

    private long bucket() { return Math.floorDiv(now(), WorldSignature.BUCKET_TICKS); }

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

    /** Every desire with its score and the facts it came from, for the rate-limited domain line and the readout. */
    private String top(List<Desire> all) {
        var out = new StringBuilder("[");
        for (var d : all) {
            if (out.length() > 1) out.append(", ");
            out.append(d.intentId()).append(':').append(Math.round(scoreOf(d)));
            var why = d.why().get();
            if (!why.isEmpty()) out.append('(').append(why).append(')');
        }
        return out.append(']').toString();
    }

    /** A log argument built only if the line is written. */
    private static Object lazy(Supplier<String> text) {
        return new Object() { @Override public String toString() { return text.get(); } };
    }

    private static List<Drive> drives(YukkuriEntity body) {
        var out = new ArrayList<Drive>();
        synchronized (LIVE) {
            var refs = LIVE.get(body);
            if (refs == null) return out;
            refs.removeIf(ref -> ref.get() == null);
            for (var ref : refs) { var d = ref.get(); if (d != null) out.add(d); }
        }
        return out;
    }

    /** The {@code <domain>.desire} readout: each drive of this body with its current intent, every desire and the failed ones. */
    public static String status(YukkuriEntity body) {
        var drives = drives(body);
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
