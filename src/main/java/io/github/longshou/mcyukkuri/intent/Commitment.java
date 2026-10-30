package io.github.longshou.mcyukkuri.intent;

/** How hard the current intent holds the body against a competing one; {@link Intent#commitment()} defaults to {@link #NONE}. */
public enum Commitment { NONE, SOFT, HARD }
