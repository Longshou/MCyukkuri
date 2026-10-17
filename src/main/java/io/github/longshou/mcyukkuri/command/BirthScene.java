package io.github.longshou.mcyukkuri.command;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.family.FamilyData;
import io.github.longshou.mcyukkuri.family.FamilyLife;
import java.util.Set;
import net.minecraft.core.GlobalPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

/** Operator entry into a real family birth. Never installs a separate actor controller. */
public final class BirthScene {
    /** Kept solely to clean up hats written by older disposable performances. */
    public static final String HAT_TAG = "pm_birth_hat";
    /** natural: the usual random branch. caught: the father takes his hat off and cushions the newborn. missed: the father is too
     *  late and there is no cushion, so the newborn drops, is hurt and cries but lives. overstrain: the hat is in place and the
     *  newborn is pushed past it (the original fatal branch). */
    public static final Set<String> OUTCOMES = Set.of("natural", "caught", "missed", "overstrain");
    public record Result(FamilyData.Brood brood, String refusal) {}

    public static boolean eligible(YukkuriEntity actor) {
        return actor.isAlive() && actor.family().adult() && actor.birthScene() == null
                && (actor.getTags().contains("pm_actor") || actor.getTags().contains("living_showcase"));
    }

    /**
     * Accelerates an unstarted live brood, or conceives one using the normal ledger and costs.
     * Parents must already have a real usable home or the mother must own the boat she rides.
     * A requested catch only selects the normal launch; collision can still defeat it.
     */
    public static Result deliver(YukkuriEntity mother, YukkuriEntity father, String outcome) {
        if (!OUTCOMES.contains(outcome)) return refused("结果必须为 natural、caught、missed 或 overstrain。");
        if (!(mother.level() instanceof ServerLevel level) || father.level() != level || mother == father
                || !eligible(mother) || !eligible(father))
            return refused("只能选择同一维度的两只成年展示油（pm_actor 或 living_showcase）。");
        if (mother.isCommandPlaying() || father.isCommandPlaying())
            return refused("请先将双亲的动作命令恢复为 idle，再开始真实分娩。");
        if (mother.distanceToSqr(father) > 64)
            return refused("请让真实父油走到母油八格内，再开始分娩。");
        var boat = mother.family().boatHome();
        if (mother.isPassenger() && boat == null)
            return refused("母油必须在自己实际拥有的帽船中，或站在实际窝位旁。");
        var home = mother.life().homeStand();
        if (boat == null && (home == null || mother.distanceToSqr(Vec3.atBottomCenterOf(home)) > 4))
            return refused("母油需要两格内实际可用的窝位；请先认窝或登上自己的真实帽船。");
        var brood = mother.family().brood();
        if (brood != null && (brood.kind != FamilyData.BirthKind.LIVE || brood.cancelled || brood.complete()
                || !father.getUUID().equals(brood.father) || brood.children.stream().anyMatch(child -> child.born)
                || brood.elapsed > brood.duration))
            return refused("已有胎次不是这对双亲尚未开始分娩的活胎；不会覆盖或倒放出生。");
        var candidate = brood;
        if (candidate == null) {
            candidate = new FamilyData.Brood();
            candidate.father = father.getUUID();
        }
        if ((outcome.equals("caught") || outcome.equals("overstrain")) && boat == null && FamilyLife.catcher(mother, candidate) == null)
            return refused("指定接生分支需要双亲实际可用的魔理沙帽子（父油须戴着帽子、认识母油且在十六格内）；不会临时生成帽子。");
        var data = FamilyData.get(level);
        if (brood == null) {
            var anchor = GlobalPos.of(level.dimension(), boat == null ? home : boat.blockPosition());
            // Explicit demonstration pairing skips acquaintance only; fertility, health, food,
            // population, kinship, brood ownership and all normal birth records still apply.
            mother.family().arrange(father.getUUID());
            father.family().arrange(mother.getUUID());
            try {
                String reason = data.conceiveRefusal(mother, father, FamilyData.BirthKind.LIVE, anchor);
                if (reason != null) return refused("真实受孕条件未满足：" + reason + "；没有创建胎次。");
                brood = data.conceive(mother, father, FamilyData.BirthKind.LIVE, anchor);
            } finally {
                mother.family().arrange(null);
                father.family().arrange(null);
            }
            if (brood == null) return refused("真实受孕未成功；没有生成幼体。");
        }
        if (!mother.family().prepareBirthScene(brood, outcome))
            return new Result(brood, "胎次已存在，但当前不能提前临产；真实胎次保持原状。");
        mother.setNoAi(false); father.setNoAi(false);
        mother.setNoGravity(false); father.setNoGravity(false);
        mother.setPersistenceRequired(); father.setPersistenceRequired();
        mother.syncFamily(); father.syncFamily();
        return new Result(brood, null);
    }

    private static Result refused(String reason) { return new Result(null, reason); }

    /* Legacy API for YukkuriEntity's old save migration. New births never attach this class,
       so normal family/life ticks and synchronized birth data are never bypassed. */
    public static void restore(YukkuriEntity actor, CompoundTag tag) {
        actor.setNoGravity(tag.getBoolean("NoGravity"));
        actor.setPos(tag.getDouble("X"), tag.getDouble("Y"), tag.getDouble("Z"));
        actor.setYRot(tag.getFloat("Yaw")); actor.yBodyRot = tag.getFloat("Yaw");
        actor.setYHeadRot(tag.getFloat("Yaw"));
    }
    public boolean isMother(YukkuriEntity actor) { return false; }
    public boolean valid() { return false; }
    public CompoundTag restorePoint(YukkuriEntity actor) { return new CompoundTag(); }
    public boolean hidesHat(YukkuriEntity actor) { return false; }
    public float childScale(YukkuriEntity actor) { return actor.family().scale(); }
    public float belly() { return 0; }
    public int deliveryTicks() { return -1; }
    public CompoundTag liveChildren() { return new CompoundTag(); }
    public void tick() {}
    public void stop() {}
    private BirthScene() {}
}
