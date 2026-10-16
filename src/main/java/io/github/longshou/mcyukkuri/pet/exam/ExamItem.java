package io.github.longshou.mcyukkuri.pet.exam;

import io.github.longshou.mcyukkuri.pet.Ability;

/** One staged exam question (docs/PET_SHOP.md section 3). Items act the scene out with real entities; the session owns timing, interruption and cleanup. */
public interface ExamItem {
    Ability ability();
    /** Called once when the item begins: decide the outcome with ctx.roll(...) and set up props. */
    void start(ExamContext ctx);
    /** Called every server tick until it returns true. */
    boolean tick(ExamContext ctx);
    /** 0 fail, 50 partial, 100 success; read after tick returned true. */
    int score();
    /** The exam was interrupted: undo anything not registered with the context (cards are removed by the session). */
    default void abort(ExamContext ctx) {}
}
