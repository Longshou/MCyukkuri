package io.github.longshou.mcyukkuri.entity.component;

import java.util.Objects;

/** Typed identity; instances are owned by one entity, never constructed by lookup. */
public record ComponentKey<C>(String id, Class<C> type) {
    public ComponentKey {
        Objects.requireNonNull(id);
        Objects.requireNonNull(type);
    }
}
