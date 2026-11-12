package io.github.longshou.mcyukkuri.motor;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;

/** An exclusive payload. The current holder retires it only when the receiving side commits. */
public final class CarrierSlot<T> {
    private T payload;

    public boolean isEmpty() { return payload == null; }

    public Optional<T> contents() { return Optional.ofNullable(payload); }

    public boolean acquire(T incoming) {
        Objects.requireNonNull(incoming);
        if (!isEmpty()) return false;
        payload = incoming;
        return true;
    }

    /** For a slot or external receiver at real contact. Refusal leaves this slot untouched. */
    public boolean release(Predicate<T> accept) {
        if (isEmpty() || !accept.test(payload)) return false;
        payload = null;
        return true;
    }

    public boolean transferTo(CarrierSlot<T> destination) {
        return release(destination::acquire);
    }

    /** The action has used the payload at its consumption marker. */
    public void consume() { payload = null; }

    /** Load the saved payload. Null represents an empty slot. */
    public void restore(T saved) { payload = saved; }
}
