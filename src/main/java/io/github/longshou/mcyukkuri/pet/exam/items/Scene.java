package io.github.longshou.mcyukkuri.pet.exam.items;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.pet.PetLife;
import io.github.longshou.mcyukkuri.pet.exam.ExamContext;
import io.github.longshou.mcyukkuri.pet.exam.ExamItem;
import io.github.longshou.mcyukkuri.pet.stage.Staging;
import io.github.longshou.mcyukkuri.registry.ModItems;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import io.github.longshou.mcyukkuri.life.CareBlockEntity;

/** Shared phase/timer scaffolding for staged exam items: one phase counter, per-entity walk/look directors and a cleanup list. */
abstract class Scene implements ExamItem {
    static final String P = "exam.mcyukkuri.item.";
    int t, ph, pt;
    boolean passed;
    private final Map<YukkuriEntity, Act> acts = new IdentityHashMap<>();
    private final List<Runnable> undo = new ArrayList<>();

    /** Walks to a goal (re-pathing every 10 ticks) and keeps looking at a target while the scene acts. */
    static final class Act implements PetLife.Director {
        BlockPos goal; Entity look; Vec3 lookAt; Float lockYaw; double speed = 1.0;
        @Override public void tick(YukkuriEntity y) {
            if (goal != null && Staging.walkTo(y, goal, speed)) goal = null;
            if (lockYaw != null) { float w = lockYaw; y.setYRot(w); y.yBodyRot = w; y.yBodyRotO = w; y.setYHeadRot(w); return; }
            if (look != null) y.getLookControl().setLookAt(look, 30, 30);
            else if (lookAt != null) y.getLookControl().setLookAt(lookAt.x, lookAt.y, lookAt.z);
        }
        boolean done() { return goal == null; }
        void go(YukkuriEntity y, BlockPos pos) { y.playAction("idle"); goal = pos; }
        void face(Entity e) { look = e; lookAt = null; }
        void face(Vec3 p) { look = null; lookAt = p; }
    }

    final Act act(ExamContext c, YukkuriEntity y) {
        return acts.computeIfAbsent(y, k -> { var a = new Act(); c.direct(k, a); return a; });
    }
    @Override public final boolean tick(ExamContext c) { t++; pt++; if (run(c)) { cleanup(c); return true; } return false; }
    abstract boolean run(ExamContext c);
    @Override public boolean passed() { return passed; }
    @Override public void abort(ExamContext c) { cleanup(c); }
    void cleanup(ExamContext c) {
        for (var r : undo) r.run();
        undo.clear();
        for (var e : acts.entrySet()) {
            var a = e.getValue(); a.goal = null; a.look = null; a.lookAt = null; a.lockYaw = null;
            if (e.getKey().isAlive() && e.getKey().isCommandPlaying()) e.getKey().playAction("idle");
        }
    }
    final void next() { ph++; pt = 0; }
    final void onEnd(Runnable r) { undo.add(r); }

    static String outcome(boolean passed) { return passed ? "success" : "fail"; }
    static void log(ExamContext c, String id, boolean passed) { c.note(P + id + "." + outcome(passed)); }
    static Vec3 top(Entity e, double dy) { return e.position().add(0, e.getBbHeight() + dy, 0); }
    static Vec3 stand(BlockPos p) { return Vec3.atBottomCenterOf(p); }
    /** A point {@code dist} blocks from {@code from} toward {@code toward}. */
    static BlockPos toward(BlockPos from, BlockPos toward, double dist) {
        double dx = toward.getX() - from.getX(), dz = toward.getZ() - from.getZ(), n = Math.max(1e-6, Math.sqrt(dx * dx + dz * dz));
        return BlockPos.containing(from.getX() + .5 + dx / n * dist, from.getY(), from.getZ() + .5 + dz / n * dist);
    }
    static BlockPos first(List<BlockPos> l, BlockPos fallback) { return l == null || l.isEmpty() ? fallback : l.get(0); }
    static boolean close(Entity a, Entity b, double d) { return a.distanceToSqr(b) <= d * d; }

    /** One regular grain portion into a free slot of the bowl; the returned Runnable removes it again (idempotent). */
    Runnable grain(ExamContext c, BlockPos pos) {
        if (!c.level().hasChunkAt(pos) || !(c.level().getBlockEntity(pos) instanceof CareBlockEntity bowl)) return () -> {};
        for (int slot = 0; slot < bowl.getContainerSize(); slot++) if (bowl.getItem(slot).isEmpty()) {
            final int s = slot;
            bowl.setItem(s, new ItemStack(ModItems.GRAIN_REGULAR.get()));
            Runnable r = () -> { if (bowl.getItem(s).is(ModItems.GRAIN_REGULAR.get())) bowl.setItem(s, ItemStack.EMPTY); };
            onEnd(r); return r;
        }
        return () -> {};
    }
    /** O / triangle / X above the examiner, with the matching sound and particles. */
    void mark(ExamContext c, boolean completed) { mark(c, completed, top(c.examiner(), 1.0)); }
    void mark(ExamContext c, boolean completed, Vec3 at) {
        c.card(at, Component.literal(completed ? "○" : "×"), 50, 2.2f);
        if (completed) { Staging.note(c.level(), at); Staging.sound(c.level(), at, SoundEvents.EXPERIENCE_ORB_PICKUP, 1.2f); }
        else Staging.sound(c.level(), at, SoundEvents.VILLAGER_NO, 1.2f);
    }
    /** Final beat: show the mark, hold a moment, then end. */
    boolean wrap(ExamContext c, int hold) {
        if (pt == 1) mark(c, passed);
        return pt >= hold;
    }
    /** Podium setup shared by oral items: candidate on the podium facing the desk, examiner at the desk. True once both stand there. */
    boolean podiumReady(ExamContext c) {
        var h = c.hall(); var a = act(c, c.candidate());
        if (pt == 1) { a.go(c.candidate(), h.podium); a.face(stand(h.desk).add(0, 1, 0)); c.examinerWalkTo(h.desk); }
        return pt >= 20 && (a.done() && c.examinerAt(h.desk) || pt > 120);
    }
    Vec3 deskTop(ExamContext c) { return stand(c.hall().desk).add(0, 1.7, 0); }
}
