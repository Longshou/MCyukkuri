package io.github.longshou.mcyukkuri.entity;

/** One immutable source value for a synced field whose encoding is more expensive than equality. */
final class LastSent<T> {
    private T value;

    boolean changed(T next) {
        if (next.equals(value)) return false;
        value = next;
        return true;
    }

    void invalidate() { value = null; }
}
