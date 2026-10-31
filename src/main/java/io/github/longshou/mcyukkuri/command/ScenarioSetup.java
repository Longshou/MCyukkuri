package io.github.longshou.mcyukkuri.command;

import io.github.longshou.mcyukkuri.entity.DomainHooks;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.entity.YukkuriVariant;
import io.github.longshou.mcyukkuri.registry.ModEntities;
import io.github.longshou.mcyukkuri.subspecies.SubspeciesLife;
import net.minecraft.world.entity.MobSpawnType;
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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import io.github.longshou.mcyukkuri.house.HouseNestBlockEntity;
import io.github.longshou.mcyukkuri.settlement.Homes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import io.github.longshou.mcyukkuri.headwear.HatGeometry;
import io.github.longshou.mcyukkuri.headwear.PlacedHatEntity;
import io.github.longshou.mcyukkuri.motor.Motor;
import io.github.longshou.mcyukkuri.motor.geom.Disc;
import io.github.longshou.mcyukkuri.motor.surface.Occupancy;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import net.minecraft.tags.FluidTags;

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

    /** Sticks go into the doorway stock of the individual's own den through the ordinary deposit (it holds three at most); returns how many were taken. */
    static int twigs(YukkuriEntity y, int count) {
        var shelter = y.habitat().shelter();
        if (shelter == null) return -1;
        int put = 0;
        for (int i = 0; i < count; i++) if (shelter.deposit(new ItemStack(Items.STICK))) put++;
        return put;
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
     * A real LIVE conception for an adult pair, then the brood is brought to the end of its term. {@code caught}: a Marisa
     * father (if the pair has one) has time to come over and take off his hat; {@code natural}: two ticks before term. Who
     * catches, and whether there is a hat, a nest or her own boat, comes from the real pair and their home (decision R5), never
     * from here.
     * {@code stem}: a real STEM conception brought to 400 ticks before term (a hatted Marisa father sets the hat under it).
     */
    static String birth(YukkuriEntity mother, YukkuriEntity father, String outcome) {
        if (!(mother.level() instanceof ServerLevel level) || father.level() != level || mother == father
                || !mother.family().adult() || !father.family().adult()) return "接生场景要在同一维度放两只成年个体。";
        if (!Set.of("natural", "caught", "stem").contains(outcome)) return "结果必须为 natural、caught 或 stem。";
        boolean stem = outcome.equals("stem");
        if (mother.distanceToSqr(father) > 64) return "父油要在母油八格内。";
        if (mother.family().brood() != null) return "母油已经有胎次，不会覆盖。";
        // Where she lives now: a nest within two blocks, or (aquatic) the boat she rides; the brood is anchored there.
        var home = mother.life().homeStand();
        boolean boat = mother.getVehicle() instanceof PlacedHatEntity hat && hat == PlacedHatEntity.find(mother);
        if (home == null && !boat) return "母油需要两格内可用的窝位，或正乘在自己浮在水上的帽子里。";
        var data = FamilyData.get(level);
        var anchor = GlobalPos.of(level.dimension(), home != null ? home : mother.blockPosition());
        FamilyData.Brood brood;
        // Skip courtship only: fertility, food, health, kinship, population and the ledger all still apply.
        mother.family().arrange(father.getUUID());
        father.family().arrange(mother.getUUID());
        try {
            var kind = stem ? FamilyData.BirthKind.STEM : FamilyData.BirthKind.LIVE;
            String reason = data.conceiveRefusal(mother, father, kind, anchor);
            if (reason != null) return "真实受孕条件未满足：" + reason + "（没有创建胎次）。";
            brood = data.conceive(mother, father, kind, anchor);
        } finally {
            mother.family().arrange(null);
            father.family().arrange(null);
        }
        if (brood == null) return "真实受孕没有成功。";
        if (stem) {
            brood.elapsed = Math.max(0, brood.duration - 400);
            data.setDirty();
        } else {
            var helper = FamilyLife.catcher(mother, brood);
            int lead = helper != null && helper != mother ? 150 : 2;
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

    /**
     * The owner's real hat set down on the surface of {@code water} through the one placement path, every young on its own free
     * supported spot nearest the hat's front (or its middle), then, when asked, the owner climbing in last into the middle of her
     * own hat whether or not her young already sit there (a crowd the hat then squeezes apart), and last {@code food} apples
     * stored in it through the same storing a player's click uses, each on floor no one stands on. Kinship is written once every
     * one sits aboard: a setup that fails takes the hat back with the reason and leaves nothing half built. Everything afterwards
     * is physics.
     */
    static String afloat(YukkuriEntity owner, BlockPos water, boolean aboard, List<YukkuriEntity> young, int food, boolean middle, boolean stick) {
        if (!(owner.level() instanceof ServerLevel level) || !owner.isAlive()) return "帽主要是活着的魔理沙。";
        if (!level.getFluidState(water).is(FluidTags.WATER) || !level.getFluidState(water.above()).isEmpty())
            return "目标格 " + water.toShortString() + " 不是水面（要是水，且上方不是水）。";
        for (var child : young) {
            if (child == owner || child.isPassenger() || child.isVehicle()) return child.getName().getString() + " 已经在乘坐或背着别的个体。";
            String refusal = kinRefusal(owner, child, false);
            if (refusal != null) return refusal;
        }
        var hat = PlacedHatEntity.setDown(owner, Vec3.atBottomCenterOf(water), owner.getYRot(), HatGeometry.Role.HELPER);
        if (hat == null) return "帽子没能放下：帽主要戴着自己的帽子、没有乘坐别的东西（原因见日志 hat refused）。";
        if (stick && !hat.stow(new ItemStack(Items.STICK))) return takeBack(owner, hat, "帽子没能收下木棍，帽子已收回。");
        var riders = new ArrayList<YukkuriEntity>(young);
        var spots = new ArrayList<Vec3>();
        var taken = new ArrayList<Disc>(hat.behavior().obstacles(hat));
        for (var body : riders) {
            double foot = Occupancy.foot(body.getBbWidth()) / hat.unit();
            var spot = freeSpot(hat, body, foot, middle ? 0 : hat.profile().opening()[3] * .75, taken);
            if (spot == null) return takeBack(owner, hat, body.getName().getString() + " 在帽子上没有放得下的位置，帽子已收回。");
            spots.add(spot);
            taken.add(new Disc(spot.x, spot.z, foot));
        }
        if (aboard) {
            var middleHeight = hat.support(Vec3.ZERO, owner);
            if (middleHeight.isEmpty()) return takeBack(owner, hat, "帽子中央没有支撑，帽子已收回。");
            riders.add(owner);
            spots.add(new Vec3(0, middleHeight.getAsDouble(), 0));
        }
        for (int i = 0; i < riders.size(); i++) {
            var body = riders.get(i);
            if (!Motor.mount(body, hat, "scenario_afloat")) {
                for (var aboardNow : List.copyOf(hat.getPassengers()))
                    if (aboardNow instanceof YukkuriEntity y) Motor.dismount(y, y.position(), Vec3.ZERO, "scenario_undo");
                return takeBack(owner, hat, body.getName().getString() + " 没能坐上帽子，帽子已收回。");
            }
            hat.riders().put(body.getUUID(), spots.get(i), 0);
        }
        if (food > 0) {
            var stock = new ItemStack(Items.APPLE, food);
            if (!hat.behavior().store(hat, stock, null) || stock.getCount() == food) {
                for (var aboardNow : List.copyOf(hat.getPassengers()))
                    if (aboardNow instanceof YukkuriEntity y) Motor.dismount(y, y.position(), Vec3.ZERO, "scenario_undo");
                return takeBack(owner, hat, "帽子没能装粮（原因见日志 [hat] refused），帽子已收回。");
            }
        }
        for (var child : young) kin(owner, child, false);
        GameplayLog.event(owner, "hat", "scenario afloat prepared hat={} water={} owner_aboard={} young={} young_at={} food={}",
                hat.getUUID(), water.toShortString(), aboard, young.size(), middle ? "middle" : "front", food);
        return null;
    }

    /**
     * {@code count} adult aquatic marisa join the world beside {@code at} (two blocks apart along z) the way a natural spawn places
     * them: an ordinary creation, then the natural-spawn hook ({@code DomainHooks.spawned}, cause natural) before they are added,
     * so one by calm water rides her own hat from her first tick and one with no water near stays ashore and logs why. The
     * settlement discovery a vanilla natural spawn can also start is not part of it.
     */
    static String spawn(ServerLevel level, BlockPos at, int count, String form) {
        var kind=Arrays.stream(SubspeciesLife.Kind.values()).filter(k->k.aquatic()&&k.hullForm().equals(form)).findFirst();
        if(kind.isEmpty())return "未知帽形："+form;
        for (int i = 0; i < count; i++) {
            var pos = at.offset(0, 0, 2 * i);
            // vanilla's creation places and initialises it (as /summon does); the kind is set the way a settlement sets an aquatic family's
            if (!(ModEntities.forVariant(YukkuriVariant.MARISA).create(level, null, pos, MobSpawnType.COMMAND, false, false) instanceof YukkuriEntity body))
                return "实体没能创建。";
            body.subspecies().set(kind.get(), SubspeciesLife.Congenital.NORMAL);
            body.addTag("scn");
            DomainHooks.spawned(body, "natural");
            level.addFreshEntityWithPassengers(body);
        }
        return null;
    }

    private static String takeBack(YukkuriEntity owner, PlacedHatEntity hat, String reason) {
        owner.life().returnAccessory(owner.getUUID(), hat.launchToken(), null);
        hat.state(PlacedHatEntity.State.RESCUED);
        return reason;
    }

    /** The supported spot (hat units, surface height) nearest {@code z} along the hat's axis whose foot circle of radius
     *  {@code foot} (hat units) overlaps none already {@code taken}; null when there is none. */
    private static Vec3 freeSpot(PlacedHatEntity hat, YukkuriEntity body, double foot, double z, List<Disc> taken) {
        float[] brim = hat.profile().brim();
        Vec3 best = null;
        double bestDistance = Double.MAX_VALUE;
        for (double x = -brim[0]; x <= brim[0]; x += .5)
            for (double lz = -brim[1]; lz <= brim[1]; lz += .5) {
                double distance = Math.hypot(x, lz - z);
                if (distance >= bestDistance) continue;
                var disc = new Disc(x, lz, foot);
                if (taken.stream().anyMatch(other -> disc.overlaps(other, .02))) continue;
                var height = hat.support(new Vec3(x, 0, lz), body);
                if (height.isEmpty()) continue;
                best = new Vec3(x, height.getAsDouble(), lz);
                bestDistance = distance;
            }
        return best;
    }

    private ScenarioSetup() {}
}
