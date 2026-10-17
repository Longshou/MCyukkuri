package io.github.longshou.mcyukkuri.status;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import net.neoforged.bus.api.Event;

/** Posted on the NeoForge bus (server side) when a registered status entry crosses its change threshold. */
public final class StatusChangedEvent extends Event {
    private final YukkuriEntity entity;
    private final String key, before, after;

    public StatusChangedEvent(YukkuriEntity entity, String key, String before, String after) {
        this.entity = entity; this.key = key; this.before = before; this.after = after;
    }
    public YukkuriEntity entity() { return entity; }
    /** Registry id such as {@code needs.nutrition} or {@code identity.kind}. */
    public String key() { return key; }
    public String before() { return before; }
    public String after() { return after; }
}
