package io.github.longshou.mcyukkuri.status;

import java.util.UUID;
import net.neoforged.bus.api.Event;

/** Posted when a directed acquaintance score between two individuals changes. */
public final class RelationChangedEvent extends Event {
    private final UUID from, to;
    private final int before, after;

    public RelationChangedEvent(UUID from, UUID to, int before, int after) {
        this.from = from; this.to = to; this.before = before; this.after = after;
    }
    public UUID from() { return from; }
    public UUID to() { return to; }
    public int before() { return before; }
    public int after() { return after; }
}
