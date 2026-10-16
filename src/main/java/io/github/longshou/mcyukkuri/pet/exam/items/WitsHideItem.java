package io.github.longshou.mcyukkuri.pet.exam.items;

import io.github.longshou.mcyukkuri.pet.Ability;
import io.github.longshou.mcyukkuri.pet.exam.ExamContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

/** Hide so you will not be found (approx. 10-13 s). 100 hides silently in the box; 50 hides but blurts once;
 *  0 is the M02 gag: head into the box opening (or a wall), rear sticking out toward the examiner (back to the examiner), shouting. */
public final class WitsHideItem extends Scene {
    private int out, stage; private boolean found;
    private BlockPos entrance, stage1, rear; private Vec3 nudge; private Direction facing; private float yaw;
    @Override public Ability ability() { return Ability.WITS; }
    @Override public void start(ExamContext c) {
        var h = c.hall(); var y = c.candidate(); out = roll3(c, Ability.WITS); score = out;
        entrance = h.hideBox == null ? h.room : h.hideBox.relative(h.hideOpen);
        if (out == 0) {
            if (h.hideBox != null) { // head 0.4 inside the opening, facing into the box
                facing = h.hideOpen.getOpposite(); stage1 = entrance;
                double r = y.getBbWidth() / 2, off = .5 + r - .4;
                nudge = Vec3.atBottomCenterOf(h.hideBox).add(h.hideOpen.getStepX() * off, 0, h.hideOpen.getStepZ() * off);
            } else wall(c);
            double jitter = c.random().nextFloat() * 40 - 20;
            yaw = (float) (Math.atan2(facing.getStepZ(), facing.getStepX()) * 57.29578 - 90 + jitter);
        }
        c.examinerSay(P + "wits_hide.say"); log(c, "wits_hide", out);
    }
    /** No box: face the nearest solid wall within 3 blocks, preferring the side whose rear points at the examiner; stay put if none. */
    private void wall(ExamContext c) {
        var y = c.candidate(); var lvl = c.level(); var from = y.blockPosition(); var ex = c.examiner();
        double best = 1e9; facing = null; stage1 = from;
        for (Direction d : Direction.Plane.HORIZONTAL) for (int k = 1; k <= 3; k++) {
            var p = from.relative(d, k);
            if (lvl.getBlockState(p).getCollisionShape(lvl, p).isEmpty()) continue;
            double dot = d.getStepX() * (ex.getX() - y.getX()) + d.getStepZ() * (ex.getZ() - y.getZ()), score = k + (dot > 0 ? 4 : 0);
            if (score < best) { best = score; facing = d; stage1 = from.relative(d, k - 1); }
            break;
        }
        if (facing == null) { // last resort: stand here with the back to the examiner
            double dx = y.getX() - ex.getX(), dz = y.getZ() - ex.getZ();
            facing = Math.abs(dx) > Math.abs(dz) ? (dx > 0 ? Direction.EAST : Direction.WEST) : (dz > 0 ? Direction.SOUTH : Direction.NORTH);
        }
    }
    @Override boolean run(ExamContext c) {
        var y = c.candidate(); var a = act(c, y); var h = c.hall(); var ex = c.examiner();
        switch (ph) {
            case 0 -> { if (pt == 1) a.face(ex); if (pt >= 35) next(); }
            case 1 -> { // examiner turned away for ~4 s
                Vec3 away = ex.position().add(ex.position().subtract(y.position()).multiply(1, 0, 1).normalize().scale(6)).add(0, ex.getEyeHeight(), 0);
                ex.getLookControl().setLookAt(away.x, away.y, away.z);
                if (pt == 1) { a.face((Vec3) null); a.go(y, out == 0 ? stage1 : entrance); }
                if (a.done() && stage == 0) {
                    stage = 1;
                    if (out != 0) a.go(y, h.hideBox != null ? h.hideBox : h.room);
                    else if (nudge == null) stage = 2;
                }
                if (out == 0 && stage == 1 && !close2(y, nudge)) { if (pt % 10 == 0) y.getNavigation().moveTo(nudge.x, y.getY(), nudge.z, .6); }
                else if (out == 0 && stage == 1) stage = 2;
                if (out == 0 && stage == 1 && pt > 70) stage = 2;
                if (out == 0 && stage == 2) {
                    stage = 3; y.getNavigation().stop(); a.face((Vec3) null); y.playAction("hide_face"); a.lockYaw = yaw; y.showReaction("neutral", "exam_hide_fail", 90);
                }
                if (out == 0 && stage == 3 && pt % 45 == 0) y.speak("exam_hide_fail");
                if (out != 0 && stage == 1 && a.done()) { stage = 3; y.playAction("idle"); if (out == 50) y.showReaction("surprise", "exam_hide_fail", 70); }
                if (pt >= 85) next();
            }
            case 2 -> {
                if (pt == 1) {
                    c.examinerLookAt(y);
                    rear = out == 0 ? BlockPos.containing(y.getX() - facing.getStepX() * 1.6, y.getY(), y.getZ() - facing.getStepZ() * 1.6) : out == 50 ? entrance : h.room;
                    c.examinerWalkTo(rear);
                }
                if (pt % 10 == 0) c.examinerLookAt(y);
                boolean there = out == 100 ? c.examinerAt(h.room) : close(ex, y, 2.6);
                if (there && pt > 30 || pt > 110) {
                    c.examinerLookAt(y);
                    if (out == 100) c.examinerSay(P + "wits_hide.cantfind"); else { c.examinerSwing(); c.examinerSay(P + "wits_hide.found"); }
                    next();
                }
            }
            default -> { c.examinerLookAt(y); return wrap(c, 40); }
        }
        return false;
    }
    private static boolean close2(net.minecraft.world.entity.Entity e, Vec3 p) { double dx = e.getX() - p.x, dz = e.getZ() - p.z; return dx * dx + dz * dz < .06; }
}
