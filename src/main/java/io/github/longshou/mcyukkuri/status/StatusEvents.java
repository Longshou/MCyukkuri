package io.github.longshou.mcyukkuri.status;

import com.mojang.logging.LogUtils;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import java.util.UUID;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

/** One door for status change notifications: the NeoForge bus plus an optional server-log trace for headless checks. */
public final class StatusEvents {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static boolean trace;

    public static boolean trace() { return trace; }
    public static void trace(boolean on) { trace = on; }

    static void changed(YukkuriEntity entity, String key, String before, String after) {
        NeoForge.EVENT_BUS.post(new StatusChangedEvent(entity, key, before, after));
        if (trace) LOGGER.info("[status] {} #{} {}: {} -> {}", entity.getType().getDescriptionId(), entity.getId(), key, before, after);
    }

    public static void relationChanged(UUID from, UUID to, int before, int after) {
        NeoForge.EVENT_BUS.post(new RelationChangedEvent(from, to, before, after));
        if (trace) LOGGER.info("[status] relation {} -> {}: {} -> {}", from, to, before, after);
    }

    private StatusEvents() {}
}
