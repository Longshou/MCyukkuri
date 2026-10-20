package io.github.longshou.mcyukkuri.pet.exam;

import io.github.longshou.mcyukkuri.pet.BadgeGrade;

/** One staged, observable exam task. It reports whether that task was completed; no aptitude roll or subject score exists. */
public interface ExamItem {
    ExamTaskArea area();
    /** The lowest badge level for which this concrete task is required. */
    BadgeGrade minimumGrade();
    /** Stable task identifier used only for the in-memory result decision. */
    String id();
    /** Called once when the task begins. */
    void start(ExamContext ctx);
    /** Called every server tick until it returns true. */
    boolean tick(ExamContext ctx);
    /** Whether the candidate completed this concrete task. */
    boolean passed();
    /** A named, non-score reason for a failed task, when the result needs to be distinguished in the exam log. */
    default String failureReason() { return null; }
    /** The exam was interrupted: undo anything not registered with the context (cards are removed by the session). */
    default void abort(ExamContext ctx) {}
}
