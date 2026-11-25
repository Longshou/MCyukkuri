package io.github.longshou.mcyukkuri.client;

import io.github.longshou.mcyukkuri.entity.Behaviors;
import io.github.longshou.mcyukkuri.entity.YukkuriView;
import io.github.longshou.mcyukkuri.motor.ClipMarkers;
import io.github.longshou.mcyukkuri.pose.Clips;
import io.github.longshou.mcyukkuri.pose.ClipBlend;
import io.github.longshou.mcyukkuri.pose.ContactBounds;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.BiConsumer;

/** Carries only the sampled clip layer between draws. Overlays keep their own clocks and contacts. */
final class PoseBridge {
    enum Mode { BLEND, RECORD, OFF }

    private static final Set<String> TAKEOVERS = Set.of(Behaviors.FLEE, Behaviors.BOAT_ALARM_WAIT, Behaviors.THREAT_HIDE,
            Behaviors.STRUCK, Behaviors.SHELL_RETRACT, Behaviors.ESCAPE_WATER, Behaviors.HAT_SLIPPING);
    private static final String[] CONTACTS = {"contact", "contact_closed", "bite_contact", "hook_contact", "grip", "pick", "impact"};
    private static final WeakHashMap<YukkuriView, State> history = new WeakHashMap<>();
    private static final ArrayList<YukkuriView> expired = new ArrayList<>(0);
    private static long pruneTick = Long.MIN_VALUE;
    private static final BiConsumer<YukkuriView, State> CHECK_AGE = (entity, state) -> {
        if (pruneTick < state.lastSeen || pruneTick - state.lastSeen > 20) expired.add(entity);
    };

    private PoseBridge() {}

    static State drawn(YukkuriView entity) {
        var state = history.get(entity);
        return state != null && state.owner.get() == entity ? state : null;
    }

    static Mode mode(boolean preview, boolean constrained, int hurt, int death, String water, String behavior) {
        if (preview) return Mode.OFF;
        return constrained || hurt > 0 || death > 0 || !water.equals("IDLE") || TAKEOVERS.contains(behavior)
                ? Mode.RECORD : Mode.BLEND;
    }

    private static float firstContactTick(String clip, String previous) {
        // The mirrored press and the rub that follows it share the contact already reached by both peers.
        String press = clip.equals("nuzzle") ? previous : clip;
        if (press.equals("snuggle_left") || press.equals("snuggle_right")) return 0;
        float first = Float.POSITIVE_INFINITY;
        var markers = ClipMarkers.of(clip).markers();
        for (String name : CONTACTS) {
            Float seconds = markers.get(name);
            if (seconds != null) first = Math.min(first, seconds * 20);
        }
        return first;
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
        boolean contactFit;
        private long lastSeen;
        private double changed;
        private float window, contactTick, fromPuff;
        String fromClip;
        float weight, puff;
        final ContactBounds bounds = new ContactBounds();
        long boundsFrame = -1;

        State(YukkuriView entity, float[][] rig) {
            owner = new WeakReference<>(entity);
            this.rig = rig;
            from = new float[rig.length * 9];
            last = new float[from.length];
        }

        void takeContactFrame(ClipBlend fit) {
            contactFit = true;
            if (!fit.takeContactFrame(rig, last)) weight = 1;
        }

        void sample(Mode mode, String incoming, boolean loop, float sampleTicks, float tempo, double now, float incomingPuff,
                    ClipBlend fit, boolean external) {
            if (!incoming.equals(clip)) {
                fromClip = clip;
                changed = now;
                window = 0;
                contactFit = false;
                // A fresh packet can be a tick or two late. Further into the clip, join its live clock.
                if (clip != null && mode == Mode.BLEND && sampleTicks <= 3) {
                    contactTick = firstContactTick(incoming, clip);
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
            // Once a contact took its incoming frame, a later draw order must not pull it back.
            if (weight < 1 && external) contactFit = true;
            puff = weight < 1 && !fit.keepPuff(contactFit) ? fromPuff + weight * (incomingPuff - fromPuff) : incomingPuff;
            if (weight < 1 && !fit.apply(rig, from, weight, contactFit) && fit.keepPuff(contactFit)) weight = 1;
            for (int i = 0; i < rig.length; i++) {
                float[] bone = rig[i];
                int offset = i * 9;
                System.arraycopy(bone, 0, last, offset, 9);
            }
        }
    }
}
