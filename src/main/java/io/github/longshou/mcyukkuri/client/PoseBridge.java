package io.github.longshou.mcyukkuri.client;

import io.github.longshou.mcyukkuri.entity.Behaviors;
import io.github.longshou.mcyukkuri.entity.YukkuriView;
import io.github.longshou.mcyukkuri.motor.ClipMarkers;
import io.github.longshou.mcyukkuri.pose.Clips;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.BiConsumer;

/** Carries only the sampled clip layer between draws. Overlays keep their own clocks and contacts. */
final class PoseBridge {
    enum Mode { BLEND, RECORD, OFF }

    private static final Set<String> TAKEOVERS = Set.of(Behaviors.FLEE, Behaviors.BOAT_FLEE, Behaviors.THREAT_HIDE,
            Behaviors.STRUCK, Behaviors.SHELL_RETRACT, Behaviors.ESCAPE_WATER, Behaviors.HAT_SLIPPING);
    private static final String[] CONTACTS = {"contact", "contact_closed", "bite_contact", "hook_contact", "grip", "pick", "impact"};
    private static final WeakHashMap<YukkuriView, State> history = new WeakHashMap<>();
    private static final ArrayList<YukkuriView> expired = new ArrayList<>(0);
    private static long pruneTick = Long.MIN_VALUE;
    private static final BiConsumer<YukkuriView, State> CHECK_AGE = (entity, state) -> {
        if (pruneTick < state.lastSeen || pruneTick - state.lastSeen > 20) expired.add(entity);
    };

    private PoseBridge() {}

    static Mode mode(boolean preview, boolean constrained, int hurt, int death, String water, String behavior) {
        if (preview) return Mode.OFF;
        return constrained || hurt > 0 || death > 0 || !water.equals("IDLE") || TAKEOVERS.contains(behavior)
                ? Mode.RECORD : Mode.BLEND;
    }

    static State history(YukkuriView entity, float[][] rig, long tick) {
        if (tick != pruneTick) {
            pruneTick = tick;
            history.forEach(CHECK_AGE);
            for (int i = 0; i < expired.size(); i++) history.remove(expired.get(i));
            expired.clear();
        }
        State state = history.get(entity);
        if (state == null || state.owner.get() != entity || state.rig != rig) {
            // WeakHashMap.put alone keeps the old (equal by numeric id) weak key.
            history.remove(entity);
            state = new State(entity, rig);
            history.put(entity, state);
            expired.ensureCapacity(history.size());
        }
        state.lastSeen = tick;
        return state;
    }

    static final class State {
        private final WeakReference<YukkuriView> owner;
        private final float[][] rig;
        private final float[] from, last;
        private String clip;
        private boolean looping;
        private long lastSeen;
        private double changed;
        private float window, contactTick, fromPuff;
        String fromClip;
        float weight, puff;

        State(YukkuriView entity, float[][] rig) {
            owner = new WeakReference<>(entity);
            this.rig = rig;
            from = new float[rig.length * 9];
            last = new float[from.length];
        }

        void sample(Mode mode, String incoming, boolean loop, float sampleTicks, float tempo, double now, float incomingPuff) {
            if (!incoming.equals(clip)) {
                fromClip = clip;
                changed = now;
                window = 0;
                // A fresh packet can be a tick or two late. Further into the clip, join its live clock.
                if (clip != null && mode == Mode.BLEND && sampleTicks <= 3) {
                    contactTick = Float.POSITIVE_INFINITY;
                    var markers = ClipMarkers.of(incoming).markers();
                    for (String name : CONTACTS) {
                        Float seconds = markers.get(name);
                        if (seconds != null) contactTick = Math.min(contactTick, seconds * 20);
                    }
                    // Reactions start quickly, loops settle, and a finished action gets a longer release.
                    window = Math.min(!loop ? 3 : looping ? 6 : 8, (contactTick - sampleTicks) / tempo);
                    System.arraycopy(last, 0, from, 0, last.length);
                    fromPuff = puff;
                }
                clip = incoming;
                looping = loop;
            }
            if (mode == Mode.RECORD || sampleTicks >= contactTick) window = 0;
            weight = window > 0 ? Clips.smooth((float)((now - changed) / window)) : 1;
            puff = weight < 1 ? fromPuff + weight * (incomingPuff - fromPuff) : incomingPuff;
            for (int i = 0; i < rig.length; i++) {
                float[] bone = rig[i];
                int offset = i * 9;
                if (weight < 1) for (int k = 0; k < 9; k++) {
                    float start = from[offset + k], delta = bone[k] - start;
                    if (k >= 3 && k < 6) delta -= 360 * Math.floor((delta + 180) / 360);
                    bone[k] = start + weight * delta;
                }
                System.arraycopy(bone, 0, last, offset, 9);
            }
        }
    }
}
