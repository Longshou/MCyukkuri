package io.github.longshou.mcyukkuri.random;

import java.util.function.LongFunction;

/** One run stream, or the caller's ordinary world stream when no run seed is set. */
public final class SeededSource<R> {
    private final LongFunction<R> factory;
    private volatile R seeded;
    public SeededSource(LongFunction<R> factory) { this.factory = factory; }
    public void seed(long seed) { seeded = factory.apply(seed); }
    public void clear() { seeded = null; }
    public R source(R ordinary) { return seeded == null ? ordinary : seeded; }
}
