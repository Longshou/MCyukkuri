package io.github.longshou.mcyukkuri.entity.component;

import java.util.Objects;
import java.util.function.Supplier;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializer;
import net.minecraft.network.syncher.SynchedEntityData;

/** A slot declared in the static component catalogue, never allocated by an entity or a lookup. */
public final class SyncSlot<T> {
    public interface Allocator {
        <T> EntityDataAccessor<T> allocate(EntityDataSerializer<T> serializer);
    }

    private final EntityDataSerializer<T> serializer;
    private final Supplier<T> defaultValue;
    private EntityDataAccessor<T> accessor;

    public SyncSlot(EntityDataSerializer<T> serializer, Supplier<T> defaultValue) {
        this.serializer = Objects.requireNonNull(serializer);
        this.defaultValue = Objects.requireNonNull(defaultValue);
    }

    public void install(Allocator allocator) {
        if (accessor != null) throw new IllegalStateException("Component sync slot already installed");
        accessor = allocator.allocate(serializer);
    }

    public void define(SynchedEntityData.Builder builder) {
        builder.define(accessor(), copy(defaultValue.get()));
    }

    public EntityDataAccessor<T> accessor() {
        return Objects.requireNonNull(accessor, "Component sync schema has not been installed");
    }

    public T copy(T value) { return serializer.copy(value); }
}
