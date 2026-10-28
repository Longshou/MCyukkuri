package io.github.longshou.mcyukkuri.intent;

/**
 * How hard the current intent holds the body against a competing one (spec 11.4). WP1 only uses it as the default
 * of {@link Intent#commitment()}; Drive reads it from WP3.
 */
public enum Commitment { NONE, SOFT, HARD }
