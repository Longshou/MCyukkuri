package io.github.longshou.mcyukkuri.entity.component;

/** Call-site boundaries preserve business callbacks between interruption actions. */
public enum InterruptPhase {
    MAIN,
    BEFORE_INJURY,
    AFTER_STATUS_HURT,
    AFTER_ATTACK_NOTIFICATIONS,
    DEATH_PREPARE,
    DEATH_CLEANUP,
    DEATH_ONCE,
    DEATH_FINISH
}
