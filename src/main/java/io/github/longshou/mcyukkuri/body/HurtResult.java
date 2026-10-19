package io.github.longshou.mcyukkuri.body;

/** One accepted vanilla hit and its actual health loss; secondary consumers never deal that damage again. */
public record HurtResult(boolean accepted, float loss, BodyPart part, BodyFood.Bite food) {
    public static final HurtResult REJECTED = new HurtResult(false, 0, null, BodyFood.Bite.EMPTY);
}
