package io.github.longshou.mcyukkuri.motor;

/** What one {@link Action#tick} produced: still working, or an {@link Outcome} that ends the action. */
public sealed interface Progress permits Running, Outcome {}
