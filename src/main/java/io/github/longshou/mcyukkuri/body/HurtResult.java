package io.github.longshou.mcyukkuri.body;

/** One accepted vanilla hit and its actual health loss; secondary consumers never deal that damage again. */
public record HurtResult(boolean accepted, float loss, BodyPart part, BodyFood.Bite food, BodyWound wound) {
    public static final HurtResult REJECTED = new HurtResult(false, 0, null, BodyFood.Bite.EMPTY);

    public HurtResult(boolean accepted, float loss, BodyPart part, BodyFood.Bite food) {
        this(accepted, loss, part, food, null);
    }
}
