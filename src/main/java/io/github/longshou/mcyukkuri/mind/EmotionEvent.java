package io.github.longshou.mcyukkuri.mind;

/** Real causes of unease or relief, independent of dialogue and facial presentation. */
public enum EmotionEvent {
    FAMILY_DEATH(30, 600, "family_died"),
    BIRTH_LOSS(30, 600, "birth_lost"),
    ACCESSORY_LOST(15, 600, "lost_accessory"),
    SOCIAL_HURT(8, 600, "settlement_hurt"),
    SUBMITTED(8, 600, "settlement_submit"),
    WOUND_DISTRESS(4, 600, "wounded"),
    FRIGHTENED(3, 600, "afraid"),
    SEPARATED(5, 1200, "lost_parent"),
    REJECTED(3, 600, "unrecognized"),
    CHILLED(2, 600, "cold"),
    COLD_TREATMENT(2, 600, "cold"),
    SOLE_BURN(8, 600, "sole_burned"),
    BITTER_TASTE(3, 300, "bitter"),
    ACCESSORY_RETURNED(-20, 0, "returned_accessory"),
    TREATED(-4, 200, "treated"),
    NUZZLED(-4, 200, "nuzzle_done"),
    COMFORTED(-3, 200, "gentle"),
    PRAISED(-3, 200, "lesson_praised"),
    LEISURE_COMPLETED(-2, 400, "leisure_done"),
    PLAYED(-2, 400, "toy");

    final float delta;
    final int cooldown;
    // Explicit identities: equal values do not imply a shared cooldown; the two cold causes do share it.
    final String bucket;

    EmotionEvent(float delta, int cooldown, String bucket) {
        this.delta = delta; this.cooldown = cooldown; this.bucket = bucket;
    }
}
