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
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import io.github.longshou.mcyukkuri.house.HouseNestBlockEntity;
import io.github.longshou.mcyukkuri.settlement.Homes;
import java.util.List;
import java.util.Set;

/**
 * Starting conditions of the playground scenarios that cannot be written into entity NBT: relationships, a claimed
 * nest, a real conception, the independence project. Every method only sets the beginning of an ordinary process and
 * returns a refusal text (or null on success); nothing here controls an entity afterwards.
 */
final class ScenarioSetup {
    /** Parent and child become real kin. With {@code newborn} the baby also keeps its first-stool and movement care. */
    static String kin(YukkuriEntity parent, YukkuriEntity child, boolean newborn) {
        String refusal = kinRefusal(parent, child, newborn);
        if (refusal != null) return refusal;
        if (parent.family().caresFor(child)) {
            if (newborn) child.childhood().newborn();
            return null;
        }
        var level = (ServerLevel)parent.level();
        var growth = child.family().save();
        child.family().generatedChild(parent, null);
        var linked = child.family().save();
        for (String key : List.of("Stage", "Age", "Nourishment", "Maturity", "Lifespan", "Scale", "AdultScale"))
            if (growth.contains(key)) linked.put(key, growth.get(key).copy());
        child.family().load(linked);
        if (newborn) child.childhood().newborn();
        FamilyData.get(level).register(child);
        return null;
    }

    /** Read-only gate for callers that need to reserve physical space before committing a real family ledger entry. */
    static String kinRefusal(YukkuriEntity parent, YukkuriEntity child, boolean newborn) {
        if (parent == child || !(parent.level() instanceof ServerLevel level) || parent.level() != child.level()
                || !parent.isAlive() || !child.isAlive() || !parent.family().adult() || child.family().adult())
            return "亲子关系没能建立：亲油要是成年，子油要未成年且在同一维度。";
        if (parent.family().caresFor(child)) {
            if (!newborn) return null;
            if (child.family().stage() != FamilyLife.Stage.BABY || child.childhood().active() || parent.childhood().active()
                    || child.family().active() || parent.family().active()) return "亲子已经建立，且幼体不是新生或双方正忙于照护。";
            return null;
        }
        if (child.family().mother() != null || child.family().father() != null || child.family().birthId() != null)
            return "子油已经有别的亲缘。";
        if (newborn && (child.family().stage() != FamilyLife.Stage.BABY || child.childhood().active() || parent.childhood().active()
                || child.family().active() || parent.family().active())) return "首便/学起跳场景要求幼体仍是新生赤油。";
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
        if (y.level().getBlockEntity(pos) instanceof HouseNestBlockEntity nest) return y.house().choose(nest);
        var shelter = ShelterBlock.shelter(y.level(), pos);
        // a south-facing cave doorway that qualifies is registered as a home first (terrain untouched), like the generator does
        if (shelter == null && y.level() instanceof ServerLevel server && Homes.canRecognise(server, pos, Direction.SOUTH))
            shelter = Homes.recognise(server, pos, Direction.SOUTH);
        if (shelter != null) return y.habitat().choose(shelter);
        return y.life().assignHome(GlobalPos.of(y.level().dimension(), pos));
    }

    /** A real egg brood in the pair's claimed house nest, ready for the ordinary care and relocation rules. */
    static String eggs(YukkuriEntity mother, YukkuriEntity father) {
        if (!(mother.level() instanceof ServerLevel level) || father.level() != level || mother == father
                || !mother.profile().houseBody() || !father.profile().houseBody()
                || !mother.family().adult() || !father.family().adult()) return "卵生场景需要同一维度的两只成年家油。";
        var nest = mother.house().nest();
        if (nest == null || father.house().nest() != nest) return "双亲要先认下同一个完整家油窝。";
        if (mother.distanceToSqr(father) > 9 || mother.distanceToSqr(Vec3.atCenterOf(nest.getBlockPos())) > 9
                || father.distanceToSqr(Vec3.atCenterOf(nest.getBlockPos())) > 9)
            return "双亲要在窝边三格内相聚。";
        var data = FamilyData.get(level);
        mother.family().arrange(father.getUUID());
        father.family().arrange(mother.getUUID());
        try {
            String reason = data.conceiveRefusal(mother, father, FamilyData.BirthKind.EGG,
                    GlobalPos.of(level.dimension(), nest.getBlockPos()));
            if (reason != null) return "真实产卵条件未满足：" + reason + "（没有创建胎次）。";
            if (!nest.createClutch(mother, father)) return "窝要完整、干净、未被扰动、有四份食物且没有旧卵或残壳。";
        } finally {
            mother.family().arrange(null);
            father.family().arrange(null);
        }
        mother.syncFamily(); father.syncFamily();
        return null;
    }

    /**
     * A real LIVE conception for an adult pair, then the brood is brought to the end of its term. {@code caught}: the
     * hatted father (if the pair has one) has time to take off his hat; {@code missed}: nobody cushions the newborn;
     * {@code natural}: the usual branch.
     */
    static String birth(YukkuriEntity mother, YukkuriEntity father, String outcome) {
        if (!(mother.level() instanceof ServerLevel level) || father.level() != level || mother == father
                || !mother.family().adult() || !father.family().adult()) return "接生场景要在同一维度放两只成年个体。";
        if (!Set.of("natural", "caught", "missed").contains(outcome)) return "结果必须为 natural、caught 或 missed。";
        if (mother.distanceToSqr(father) > 64) return "父油要在母油八格内。";
        if (mother.family().brood() != null) return "母油已经有胎次，不会覆盖。";
        var boat = mother.family().boatHome();
        var home = mother.life().homeStand();
        boolean aquatic = mother.profile().aquatic();
        if (aquatic) {
            if (mother.isPassenger() || father.isPassenger() || !mother.onGround() || !father.onGround()
                    || !mother.life().dryStand(mother.blockPosition()) || !father.life().dryStand(father.blockPosition()))
                return "水生双亲要先在干岸相聚受孕，再由母油下水进入自己的帽船。";
            home = mother.blockPosition();
        }
        if (boat == null && home == null) return "母油需要两格内可用的窝位。";
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
            if (aquatic) { brood.elapsed = Math.max(0, brood.duration - 400); data.setDirty(); }
        } else {
            var helper = FamilyLife.catcher(mother, brood);
            int lead = aquatic ? 400 : helper != null && helper != mother ? 150 : 2;
            brood.elapsed = Math.max(0, brood.duration - lead);
            data.setDirty();
            if (!aquatic && outcome.equals("caught") && helper == null)
                return "已进入临产，但没有人能接：父油要是戴帽的魔理沙、认识母油并在十六格内；这一胎会按普通规则发生。";
        }
        mother.setNoAi(false); father.setNoAi(false);
        mother.setPersistenceRequired(); father.setPersistenceRequired();
        mother.syncFamily(); father.syncFamily();
        return null;
    }

    /**
     * Parent and teen are put into one family group in a real shelter at {@code home} (a south-facing cave doorway), and the
     * teen's independence is armed: the next daytime chance it leaves, with a farewell when the parent is near. Nothing else
     * is scripted; the departure, the search for a place and the digging are the ordinary behaviour.
     */
    static String independence(YukkuriEntity parent, YukkuriEntity teen, BlockPos home) {
        if (!(parent.level() instanceof ServerLevel level) || parent == teen || teen.level() != level
                || !parent.family().adult() || teen.family().adult()) return "独立场景要成年和亚成两只都在场。";
        if (!teen.childhood().canLiveIndependently()
                || !parent.getUUID().equals(teen.family().mother()) && !parent.getUUID().equals(teen.family().father()))
            return "亚成还不能独立生活，或两只不是亲子（先用 kin 建立亲子）。";
        if (parent.habitat().hasMemory() || teen.habitat().hasMemory() || parent.settlement().groupId() != null
                || teen.settlement().groupId() != null) return "两只已经有住所或群落。";
        var survey = NaturalShelter.survey(level, home, Direction.SOUTH);
        if (survey == null || survey.beds().size() < 2) return "旧家洞口 " + home.toShortString() + " 不是一个完整的朝南洞穴。";
        if (!level.getBlockState(home).isAir()) return "洞口格子被占用。";
        var plan = new ShelterPlacement.Plan(home, ShelterBlock.Kind.BURROW, Direction.SOUTH);
        if (!ShelterPlacement.canPlace(level, plan)) return "洞口位置放不下住所。";
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
        teen.independence().arm();
        return null;
    }

    private ScenarioSetup() {}
}
