package io.github.longshou.mcyukkuri.command;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.family.FamilyData;
import io.github.longshou.mcyukkuri.family.FamilyLife;
import io.github.longshou.mcyukkuri.life.YukkuriWorldData;
import io.github.longshou.mcyukkuri.settlement.NaturalShelter;
import io.github.longshou.mcyukkuri.settlement.SettlementData;
import io.github.longshou.mcyukkuri.settlement.ShelterBlock;
import io.github.longshou.mcyukkuri.settlement.ShelterPlacement;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.phys.Vec3;

/**
 * Starting conditions of the playground scenarios that cannot be written into entity NBT: relationships, a claimed
 * nest, a real conception, the independence project. Every method only sets the beginning of an ordinary process and
 * returns a refusal text (or null on success); nothing here controls an entity afterwards.
 */
final class ScenarioSetup {
    /** Parent and child become real kin. With {@code newborn} the baby also keeps its first-stool and movement care. */
    static String kin(YukkuriEntity parent, YukkuriEntity child, boolean newborn) {
        if (parent == child || !(parent.level() instanceof ServerLevel level) || parent.level() != child.level()
                || !parent.isAlive() || !child.isAlive() || !parent.family().adult() || child.family().adult())
            return "亲子关系没能建立：亲油要是成年，子油要未成年且在同一维度。";
        if (parent.family().caresFor(child)) {
            if (!newborn) return null;
            if (child.family().stage() != FamilyLife.Stage.BABY || child.childhood().active() || parent.childhood().active()
                    || child.family().active() || parent.family().active()) return "亲子已经建立，且幼体不是新生或双方正忙于照护。";
            child.childhood().newborn();
            return null;
        }
        if (child.family().mother() != null || child.family().father() != null || child.family().birthId() != null)
            return "子油已经有别的亲缘。";
        if (newborn && (child.family().stage() != FamilyLife.Stage.BABY || child.childhood().active() || parent.childhood().active()
                || child.family().active() || parent.family().active())) return "首便/学起跳场景要求幼体仍是新生赤油。";
        var growth = child.family().save();
        child.family().generatedChild(parent, null);
        var linked = child.family().save();
        for (String key : java.util.List.of("Stage", "Age", "Nourishment", "Maturity", "Lifespan", "Scale", "AdultScale"))
            if (growth.contains(key)) linked.put(key, growth.get(key).copy());
        child.family().load(linked);
        if (newborn) child.childhood().newborn();
        FamilyData.get(level).register(child);
        return null;
    }

    /** Two individuals who know and like each other (both directions), so the ordinary affection logic can pair them. */
    static void bond(YukkuriEntity first, YukkuriEntity second) {
        var data = YukkuriWorldData.get((ServerLevel)first.level());
        data.relate(first.getUUID(), second.getUUID(), 30 - data.affinity(first.getUUID(), second.getUUID()));
        data.relate(second.getUUID(), first.getUUID(), 30 - data.affinity(second.getUUID(), first.getUUID()));
    }

    /** The nest (or shelter, or house nest) at {@code pos} becomes this individual's home. */
    static boolean home(YukkuriEntity y, BlockPos pos) {
        if (y.level().getBlockEntity(pos) instanceof io.github.longshou.mcyukkuri.house.HouseNestBlockEntity nest) return y.house().choose(nest);
        var shelter = ShelterBlock.shelter(y.level(), pos);
        if (shelter != null) return y.habitat().choose(shelter);
        return y.life().assignHome(GlobalPos.of(y.level().dimension(), pos));
    }

    /**
     * A real LIVE conception for an adult pair, then the brood is brought to the end of its term. {@code caught}: the
     * hatted father (if the pair has one) has time to take off his hat; {@code missed}: nobody cushions the newborn;
     * {@code natural}: the usual branch.
     */
    static String birth(YukkuriEntity mother, YukkuriEntity father, String outcome) {
        if (!(mother.level() instanceof ServerLevel level) || father.level() != level || mother == father
                || !mother.family().adult() || !father.family().adult()) return "接生场景要在同一维度放两只成年个体。";
        if (!java.util.Set.of("natural", "caught", "missed").contains(outcome)) return "结果必须为 natural、caught 或 missed。";
        if (mother.distanceToSqr(father) > 64) return "父油要在母油八格内。";
        if (mother.family().brood() != null) return "母油已经有胎次，不会覆盖。";
        var boat = mother.family().boatHome();
        var home = mother.life().homeStand();
        if (boat == null && home == null) return "母油需要两格内可用的窝位，或者已坐进自己的帽船。";
        var data = FamilyData.get(level);
        var anchor = GlobalPos.of(level.dimension(), boat == null ? home : boat.blockPosition());
        FamilyData.Brood brood;
        // Skip courtship only: fertility, food, health, kinship, population and the ledger all still apply.
        mother.family().arrange(father.getUUID());
        father.family().arrange(mother.getUUID());
        try {
            String reason = data.conceiveRefusal(mother, father, FamilyData.BirthKind.LIVE, anchor);
            if (reason != null) return "真实受孕条件未满足：" + reason + "（没有创建胎次）。";
            brood = data.conceive(mother, father, FamilyData.BirthKind.LIVE, anchor);
        } finally {
            mother.family().arrange(null);
            father.family().arrange(null);
        }
        if (brood == null) return "真实受孕没有成功。";
        if (outcome.equals("missed")) {
            if (!mother.family().prepareBirthScene(brood, "missed")) return "胎次已建立，但不能提前临产。";
        } else {
            var helper = FamilyLife.catcher(mother, brood);
            int lead = helper != null && helper != mother ? 150 : 2;   // time for the father to notice and take off his hat
            brood.elapsed = Math.max(0, brood.duration - lead);
            data.setDirty();
            if (outcome.equals("caught") && helper == null)
                return "已进入临产，但没有人能接：父油要是戴帽的魔理沙、认识母油并在十六格内；这一胎会按普通规则发生。";
        }
        mother.setNoAi(false); father.setNoAi(false);
        mother.setPersistenceRequired(); father.setPersistenceRequired();
        mother.syncFamily(); father.syncFamily();
        return null;
    }

    /**
     * Parent and teen are put into one family group in a real shelter at {@code home}; a second cave mouth must exist
     * eight blocks east. Only the starting project and three loose leaf portions are set up; the teen walks, gathers,
     * carries and builds by the ordinary goal.
     */
    static String independence(YukkuriEntity parent, YukkuriEntity teen, BlockPos home) {
        if (!(parent.level() instanceof ServerLevel level) || parent == teen || teen.level() != level
                || !parent.family().adult() || teen.family().adult()) return "独立场景要成年和亚成两只都在场。";
        if (!teen.childhood().canLiveIndependently()
                || !parent.getUUID().equals(teen.family().mother()) && !parent.getUUID().equals(teen.family().father()))
            return "亚成还不能独立生活，或两只不是亲子（先用 kin 建立亲子）。";
        if (parent.habitat().hasMemory() || teen.habitat().hasMemory() || parent.settlement().groupId() != null
                || teen.settlement().groupId() != null) return "两只已经有住所或群落。";
        BlockPos site = home.east(8);
        var oldSurvey = NaturalShelter.survey(level, home, Direction.SOUTH);
        var newSurvey = NaturalShelter.survey(level, site, Direction.SOUTH);
        if (oldSurvey == null || oldSurvey.beds().size() < 2) return "旧家洞口 " + home.toShortString() + " 不是一个完整的朝南洞穴。";
        if (newSurvey == null) return "新巢洞口 " + site.toShortString() + " 不是一个完整的朝南洞穴。";
        if (!level.getBlockState(home).isAir() || !level.getBlockState(site).isAir()) return "洞口格子被占用。";
        if (!teen.life().reachable(site, false) || !teen.life().reachable(newSurvey.entrance(), false)) return "亚成走不到新巢。";
        var plan = new ShelterPlacement.Plan(home, ShelterBlock.Kind.BURROW, Direction.SOUTH);
        if (!ShelterPlacement.canPlace(level, plan)
                || !ShelterPlacement.canPlace(level, new ShelterPlacement.Plan(site, ShelterBlock.Kind.BURROW, Direction.SOUTH)))
            return "洞口位置放不下住所。";
        var group = new SettlementData.Group(UUID.randomUUID(), SettlementData.Kind.FAMILY, home, null);
        var placed = ShelterPlacement.place(level, plan);
        if (placed == null) return "住所没能放进洞里。";
        placed.shelter().initializeEncounter(2);
        SettlementData.get(level).add(group);
        parent.settlement().join(group);
        teen.settlement().join(group);
        if (!parent.habitat().choose(placed.shelter()) || !teen.habitat().choose(placed.shelter())) {
            SettlementData.get(level).remove(group.id);
            return "两只没能认下同一个窝。";
        }
        for (var actor : new YukkuriEntity[]{parent, teen}) {
            var life = actor.life().save();
            life.putFloat("Nutrition", 100); life.putFloat("Energy", 100);
            life.putFloat("Bladder", 0); life.putFloat("Dryness", 0);
            actor.life().load(life);
        }
        if (!teen.independence().eligible()) return "亚成现在不满足独立条件（需白天、吃饱睡足、同群落、父母拥有这个窝）。";
        var project = new CompoundTag();
        FamilyData.putPosition(project, "Origin", GlobalPos.of(level.dimension(), home));
        FamilyData.putPosition(project, "Site", GlobalPos.of(level.dimension(), site));
        project.putUUID("OriginalGroup", group.id);
        project.putString("Kind", "BURROW"); project.putString("Facing", "south");
        project.putInt("Wait", 0);
        teen.independence().load(project);
        for (int i = 0; i < 3; i++) {
            var stack = new ItemStack(Items.OAK_LEAVES);
            CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.putBoolean("PlaygroundMaterial", true));
            var point = Vec3.atBottomCenterOf(home.south(2).east(i + 2)).add(0, .03, 0);
            var item = new ItemEntity(level, point.x, point.y, point.z, stack);
            item.setDeltaMovement(Vec3.ZERO); item.setNoPickUpDelay();
            level.addFreshEntity(item);
        }
        return null;
    }

    private ScenarioSetup() {}
}
