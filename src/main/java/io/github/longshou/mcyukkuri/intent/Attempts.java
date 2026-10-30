package io.github.longshou.mcyukkuri.intent;

import io.github.longshou.mcyukkuri.motor.Reason;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** The switch budget of one intent: a method that failed under a signature is not chosen again under it, a method out of the body's ability stays excluded, one failure counts once. */
public final class Attempts {
    public static final int DEFAULT_MAX_SWITCHES = 6;

    /** One counted failure: which method, which reason, by how much, and under which signature. */
    public record Failure(String methodId, Reason reason, double shortfall, WorldSignature signature) {}

    private final int max;
    private final List<Failure> failures = new ArrayList<>();
    private final Set<String> incapable = new LinkedHashSet<>();
    private int used;

    public Attempts() { this(DEFAULT_MAX_SWITCHES); }

    public Attempts(int maxSwitches) {
        if (maxSwitches <= 0) throw new IllegalArgumentException("Attempts needs a positive maxSwitches");
        this.max = maxSwitches;
    }

    public int max() { return max; }

    /** Switches already spent; one blocked or incapable end spends exactly one. */
    public int used() { return used; }

    public int remaining() { return Math.max(0, max - used); }

    public boolean exhausted() { return used >= max; }

    /** Counts one blocked end of {@code method}; the caller counts each action outcome once. */
    public void fail(Method method, Reason reason, double shortfall, WorldSignature signature) {
        if (method == null || reason == null) return;
        failures.add(new Failure(method.id(), reason, shortfall, signature));
        used++;
    }

    /** Marks a method as beyond this body's ability for the rest of the intent, wherever the world moves. */
    public void incapable(Method method, Reason reason) {
        if (method == null) return;
        incapable.add(method.id());
        if (reason != null) failures.add(new Failure(method.id(), reason, 0, null));
        used++;
    }

    /** True when {@code method} already failed under {@code signature}, or is marked incapable. */
    public boolean excluded(Method method, WorldSignature signature) {
        if (method == null) return true;
        if (incapable.contains(method.id())) return true;
        for (Failure f : failures) {
            if (f.methodId().equals(method.id()) && Objects.equals(f.signature(), signature)) return true;
        }
        return false;
    }

    /** Every counted reason with its shortfall, one line per reason in the order it was first counted. */
    public List<Failure> reasons() {
        Map<Reason, Failure> byReason = new LinkedHashMap<>();
        for (Failure f : failures) {
            Failure before = byReason.get(f.reason());
            if (before == null || Math.abs(f.shortfall()) > Math.abs(before.shortfall())) byReason.put(f.reason(), f);
        }
        return List.copyOf(byReason.values());
    }

    /** {@code [NO_ROOM:0, NO_CONTACT:0.3]}, the {@code [intent] end} and {@code body.attempts} form. */
    public String describe() {
        StringBuilder out = new StringBuilder("[");
        for (Failure f : reasons()) {
            if (out.length() > 1) out.append(", ");
            out.append(f.reason()).append(':').append(amount(f.shortfall()));
        }
        return out.append(']').toString();
    }

    /** A shortfall without trailing zeros, so the log reads {@code 0} and {@code 0.3}. */
    public static String amount(double value) {
        String text = String.format(Locale.ROOT, "%.2f", value);
        while (text.endsWith("0")) text = text.substring(0, text.length() - 1);
        if (text.endsWith(".")) text = text.substring(0, text.length() - 1);
        return text;
    }
}
