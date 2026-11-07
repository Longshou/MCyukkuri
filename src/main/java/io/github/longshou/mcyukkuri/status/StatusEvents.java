package io.github.longshou.mcyukkuri.status;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import java.util.UUID;
import net.neoforged.neoforge.common.NeoForge;

/** One door for status change notifications: the NeoForge bus plus the structured server trace. */
public final class StatusEvents {
    /** Existing dense-only consumers keep their call sites; light recording never enables these extra lines. */
    public static boolean trace() { return TraceRecorder.mode() == TraceRecorder.Mode.DENSE; }
    public static void trace(boolean on) { TraceRecorder.setMode(on ? TraceRecorder.Mode.DENSE : TraceRecorder.Mode.OFF); }

    static void changed(YukkuriEntity entity, String key, String before, String after) {
        NeoForge.EVENT_BUS.post(new StatusChangedEvent(entity, key, before, after));
        if (TraceRecorder.recorded(entity)) {
            if (trace()) GameplayLog.event(entity, "status", "changed key={} before={} after={}", key, before, after);
            TraceRecorder.changed(entity, key, before, after);
        }
    }

    /** Free-form trace line for effects that are not status values (particles and the like). */
    public static void note(YukkuriEntity entity, String text) {
        if (trace()) GameplayLog.event(entity, "status", "{}", text);
    }

    public static void relationChanged(UUID from, UUID to, int before, int after) {
        NeoForge.EVENT_BUS.post(new RelationChangedEvent(from, to, before, after));
        if (trace()) TraceRecorder.relationChanged(from, to, before, after);
    }

    private StatusEvents() {}
}
