package io.github.longshou.mcyukkuri.pet.exam;

/** One observable exam question. */
public interface ExamItem {
    /** Stable task identifier used only for the in-memory result decision. */
    String id();
    /** Called once when the task begins. */
    void start(ExamContext ctx);
    /** Called every server tick until it returns true. */
    boolean tick(ExamContext ctx);
    /** Whether the candidate completed this concrete task. */
    boolean passed();
    /** The exam was interrupted: undo anything not registered with the context (cards are removed by the session). */
    default void abort(ExamContext ctx) {}
}
