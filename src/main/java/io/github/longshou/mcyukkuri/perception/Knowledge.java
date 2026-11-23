package io.github.longshou.mcyukkuri.perception;

/** Which belief about a fixture counts when a body looks for water or food. */
public enum Knowledge {
    /** Planning: a fixture believed stocked or never looked at is worth walking to; one believed empty is not. */
    WORTH_A_LOOK,
    /** Asking and closing a request: only a fixture believed stocked, or open water in sight. */
    KNOWN_STOCKED,
    /** The world's truth. Scores the owner's answer only; nothing that moves a body may use it. */
    ACTUAL
}
