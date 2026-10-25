package io.github.longshou.mcyukkuri.command;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.family.FamilyLife;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import io.github.longshou.mcyukkuri.subspecies.HatBoatEntity;
import io.github.longshou.mcyukkuri.subspecies.HatBoatRoute;
import io.github.longshou.mcyukkuri.subspecies.HatVessel;
import io.github.longshou.mcyukkuri.subspecies.boat.HullFactory;
import io.github.longshou.mcyukkuri.subspecies.boat.HullFootprint;
import io.github.longshou.mcyukkuri.subspecies.boat.ShoreBerth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

/**
 * Starting conditions of the water scenarios, as two primitives. {@link #aboard}: the captain's real hat boat on open water
 * found by {@link ShoreBerth} near the bank, a real paddle and finite provisions, the listed young aboard; the whole
 * transaction is rolled back, with the precise obstruction, if any step fails, and the kin ledger is written only on success.
 * {@link #swim}: one individual put into searched open water and really plunged. Movement, cargo, shore visits and rescues
 * afterwards belong to ordinary goals; external causes (a shaken or struck hull, fire) come from real player actions.
 */
final class ScenarioBoats {
    private static final int DEFAULT_FOOD = 2, MAX_SCENARIO_YOUNG = 16;
    private static final Pattern OPTION = Pattern.compile("([a-z]+)=(\\S+)");

    /** Parses {@code key=value} options separated by spaces. Returns the map, or null with the reason in {@code error[0]}. */
    private static Map<String, String> options(String text, Set<String> allowed, String[] error) {
        var result = new HashMap<String, String>();
        if (text == null || text.isBlank()) return result;
        for (var word : text.trim().split("\\s+")) {
            var m = OPTION.matcher(word);
            if (!m.matches() || !allowed.contains(m.group(1))) { error[0] = "选项 " + word + " 不认识（可用：" + String.join(" ", allowed) + "）。"; return null; }
            result.put(m.group(1), m.group(2));
        }
        return result;
    }

    /**
     * {@code bank} is the dry feet block the scenario stands around. The hull goes on calm water two or more blocks away that
     * {@link ShoreBerth} finds free for the whole brim rectangle. Returns null on success, otherwise the reason nothing was prepared.
     */
    static String aboard(YukkuriEntity parent, List<YukkuriEntity> young, BlockPos bank, String optionText) {
        var error = new String[1];
        var opt = options(optionText, Set.of("food", "paddle"), error);
        if (opt == null) return error[0];
        int food; boolean paddle;
        try {
            food = Integer.parseInt(opt.getOrDefault("food", Integer.toString(DEFAULT_FOOD)));
            paddle = !opt.getOrDefault("paddle", "1").equals("0");
        } catch (NumberFormatException ex) { return "food 要是整数。"; }
        if (food < 0 || food > 12) return "food 要在 0 到 12 之间（粮袋最多 12 份，每份 0.5 载重；桨 1 载重，总载重 12）。";
        if (!(parent.level() instanceof ServerLevel level) || !parent.isAlive() || !parent.profile().aquatic())
            return "亲船的主角必须是活着的水生魔理沙。";
        if (!parent.aquaticLife().firstVoyage().mayLaunch()) return "直接水上生成需要成年帽主，或已完成初航、具备操帽能力的子油。";
        if (!level.hasChunkAt(bank)) return "岸边格所在区块没有加载。";
        if (young.contains(parent) || young.size() > MAX_SCENARIO_YOUNG || new HashSet<>(young).size() != young.size())
            return "幼体名单不合法。";
        var actors = new ArrayList<YukkuriEntity>();
        actors.add(parent);
        actors.addAll(young);
        for (var actor : actors) {
            if (!actor.isAlive() || actor.level() != level || !actor.profile().aquatic() || actor.isPassenger() || actor.isVehicle()
                    || actor.subspecies().boatId() != null) return actor.getName().getString() + " 不能参加：已死亡、不是水生魔理沙、已在船上或已有帽船。";
            if (actor.family().active() || actor.family().flying() || actor.family().crew().holdsBody() || actor.operations().active())
                return actor.getName().getString() + " 不能参加：正处在不能安全回滚的照护、飞行、辫子救援或操作中。";
            if (actor != parent && actor.family().adult()) return "同船的幼体要是未成年。";
        }
        for (var child : young) {
            String refusal = ScenarioSetup.kinRefusal(parent, child, false);
            if (refusal != null) return refusal;
        }
        var outcome = ShoreBerth.find(level, parent, HatVessel.hatScale(parent), bank, ShoreBerth.Purpose.SCENARIO, bank);
        if (outcome instanceof ShoreBerth.Refusal refusal) {
            GameplayLog.event(parent, "aquatic", "scenario aboard refused berth={}", refusal.describe());
            return "岸边找不到放得下帽船的开阔水面：" + refusal.describe();
        }
        var berth = (ShoreBerth.Berth) outcome;
        var parentPose = ParentPose.capture(parent);
        var scenarioTransaction = ScenarioTransaction.capture(parent, young, true);
        var deployed = HullFactory.deployOnBerth(parent, HullFactory.Origin.SCENARIO, berth);
        if (deployed instanceof HullFactory.Refused refused) return "帽船没能生成：" + refused.describe();
        var boat = ((HullFactory.Deployed) deployed).boat();
        if (boat.cargoRoom() < food) return abort(boat, parent, parentPose, scenarioTransaction, "船舱放不下场景所需的 " + food + " 份口粮。");
        if (paddle && !boat.cargo().paddle() && !boat.cargo().givePaddle(new ItemStack(Items.STICK), false))
            return abort(boat, parent, parentPose, scenarioTransaction, "桨没能交给帽船。");
        if (food > 0 && boat.cargo().store(new ItemStack(Items.APPLE, food), food, false) < food)
            return abort(boat, parent, parentPose, scenarioTransaction, "口粮没能放进船舱。");
        // A temporary actor-local family mirror lets the shared berth code see the prospective parent without writing a ledger.
        scenarioTransaction.establishTemporaryKin();
        for (var child : young) {
            // Ask for an existing safe refuge layout; the shared capacity limit still applies.
            if (!boat.canBoard(child)) boat.requestEmergencyBerth(child);
            if (!boat.boardYoung(child)) return abort(boat, parent, parentPose, scenarioTransaction, child.getName().getString() + " 没能上船（船满了或还不能载）。");
        }
        String kinCommitRefusal = scenarioTransaction.commitRealKin();
        if (kinCommitRefusal != null) return abort(boat, parent, parentPose, scenarioTransaction, kinCommitRefusal);
        GameplayLog.event(parent, "aquatic", "scenario aboard prepared hat={} water={} passengers={} food={} paddle={}",
                boat.getUUID(), berth.water(), boat.getPassengers().size(), boat.cargo().count(), boat.cargo().paddle());
        return null;
    }

    /**
     * Put one individual into searched open water and plunge it for real. With {@code near=nearest|<uuid>} the water is about
     * {@code dist} blocks (default 4) from that hat boat; otherwise it is the free open water {@link ShoreBerth} finds near
     * {@code anchor}. {@code energy=E} leaves the swimmer with that much energy.
     */
    static String swim(YukkuriEntity actor, BlockPos anchor, String optionText) {
        var error = new String[1];
        var opt = options(optionText, Set.of("near", "dist", "energy"), error);
        if (opt == null) return error[0];
        double dist; Float energy;
        try {
            dist = Double.parseDouble(opt.getOrDefault("dist", "4"));
            energy = opt.containsKey("energy") ? Float.parseFloat(opt.get("energy")) : null;
        } catch (NumberFormatException ex) { return "dist 和 energy 要是数字。"; }
        if (!(actor.level() instanceof ServerLevel level) || !actor.isAlive() || !actor.profile().aquatic()) return "落水的必须是活着的水生魔理沙。";
        if (actor.family().flying() || actor.family().crew().holdsBody() || actor.operations().active() || actor.isVehicle())
            return actor.getName().getString() + " 不能落水：正处在飞行、辫子救援、操作或载着别人的状态。";
        if (!level.hasChunkAt(anchor)) return "锚点所在区块没有加载。";
        HatBoatEntity boat = null;
        if (opt.containsKey("near")) {
            String near = opt.get("near");
            if (near.equals("nearest")) boat = level.getEntitiesOfClass(HatBoatEntity.class, actor.getBoundingBox().inflate(48),
                    candidate -> !candidate.isRemoved()).stream().min(Comparator.comparingDouble(actor::distanceToSqr)).orElse(null);
            else try { boat = level.getEntity(UUID.fromString(near)) instanceof HatBoatEntity found ? found : null; }
            catch (IllegalArgumentException ex) { return "near 要是 nearest 或帽船的 UUID。"; }
            if (boat == null) return "附近 48 格内没有指定的帽船。";
        }
        float scale = HatVessel.hatScale(actor);
        Vec3 point = null;
        if (boat == null) {
            var outcome = ShoreBerth.find(level, actor, scale, anchor, ShoreBerth.Purpose.SCENARIO, anchor);
            if (outcome instanceof ShoreBerth.Refusal refusal) return "岸边找不到开阔的落水水面：" + refusal.describe();
            var berth = (ShoreBerth.Berth) outcome;
            point = new Vec3(berth.center().x, berth.water().getY() + .12, berth.center().z);
        } else {
            var origin = boat.position();
            double bestCost = Double.MAX_VALUE;
            String detail = "";
            var reasons = new LinkedHashMap<String, Integer>();
            int reach = (int) Math.ceil(dist) + 3;
            for (var p : BlockPos.betweenClosed(BlockPos.containing(origin).offset(-reach, -2, -reach), BlockPos.containing(origin).offset(reach, 2, reach))) {
                if (!HatBoatRoute.calmSurface(level, p)) continue;
                double cost = Math.abs(Math.hypot(p.getX() + .5 - origin.x, p.getZ() + .5 - origin.z) - dist);
                if (cost >= bestCost || cost > 1.5) continue;
                var at = new Vec3(p.getX() + .5, p.getY() + .12, p.getZ() + .5);
                // The swimmer's own hat must also find room to be put on the water here, beside the boat but never inside it.
                var fit = HullFootprint.fits(level, actor, boat, HatBoatRoute.surface(level, p), actor.yBodyRot, scale, 0);
                if (!fit.ok()) { reasons.merge(fit.reason(), 1, Integer::sum); if (detail.isEmpty()) detail = fit.detail(); continue; }
                if (!level.noCollision(actor, actor.getBoundingBox().move(at.subtract(actor.position())))) { reasons.merge("body_blocked", 1, Integer::sum); continue; }
                bestCost = cost; point = at;
            }
            if (point == null) return "帽船周围 " + dist + " 格找不到开阔的落水水面：" + reasons + (detail.isEmpty() ? "" : " (" + detail + ")");
        }
        if (actor.isPassenger()) actor.stopRiding();
        actor.setPos(point);
        actor.setDeltaMovement(Vec3.ZERO);
        actor.setOnGround(false);
        if (energy != null) actor.life().spendEnergy(Math.max(0, actor.life().energy() - energy));
        actor.waterDistress().plunge(boat == null ? null : boat.getUUID(), null);
        GameplayLog.event(actor, "aquatic", "scenario swim plunged at={} near={} energy={}", point, boat == null ? null : boat.getUUID(), actor.life().energy());
        return null;
    }

    /** Undo the scenario-only deployment transaction, including the owner's position after an afloat spawn. */
    private static String abort(HatBoatEntity boat, YukkuriEntity parent, ParentPose parentPose, ScenarioTransaction scenarioTransaction, String reason) {
        if (boat != null) {
            for (var rider : List.copyOf(boat.getPassengers())) rider.stopRiding();
            boat.discard();
        }
        scenarioTransaction.restoreActors();
        parentPose.restore(parent);
        GameplayLog.event(parent,"aquatic","scenario preparation rolled back hat={} reason={}",boat==null?null:boat.getUUID(),reason);
        return reason;
    }

    /** Holds only actor-local preimages until every fallible berth step has passed. */
    private static final class ScenarioTransaction {
        private record ActorSnapshot(YukkuriEntity actor, CompoundTag state, CompoundTag family, boolean provisionalKin) {}

        private final YukkuriEntity parent;
        private final List<ActorSnapshot> actors;

        private ScenarioTransaction(YukkuriEntity parent, List<ActorSnapshot> actors) {
            this.parent = parent; this.actors = actors;
        }

        static ScenarioTransaction capture(YukkuriEntity parent, List<YukkuriEntity> young, boolean createsKin) {
            var snapshots = new ArrayList<ActorSnapshot>();
            var parentState = new CompoundTag(); parent.saveWithoutId(parentState);
            snapshots.add(new ActorSnapshot(parent, parentState, null, false));
            for (var child : young) {
                var state = new CompoundTag(); child.saveWithoutId(state);
                boolean allocated = createsKin && !parent.family().caresFor(child);
                snapshots.add(new ActorSnapshot(child, state, child.family().save(), allocated));
            }
            return new ScenarioTransaction(parent, List.copyOf(snapshots));
        }

        void establishTemporaryKin() {
            for (var snapshot : actors) {
                if (!snapshot.provisionalKin()) continue;
                var family = snapshot.family().copy(); family.putUUID("Mother", parent.getUUID());
                snapshot.actor().family().load(family);
                if (snapshot.actor().family().stage() == FamilyLife.Stage.BABY) snapshot.actor().childhood().newborn();
            }
        }

        String commitRealKin() {
            for (var snapshot : actors) if (snapshot.provisionalKin()) snapshot.actor().family().load(snapshot.family());
            for (var snapshot : actors) if (snapshot.provisionalKin()) {
                String refusal = ScenarioSetup.kinRefusal(parent, snapshot.actor(), false);
                if (refusal != null) return refusal;
            }
            // The server has not ticked between this gate and commit; kin changes no other actor's eligibility.
            for (var snapshot : actors) if (snapshot.provisionalKin()) ScenarioSetup.kin(parent, snapshot.actor(), false);
            return null;
        }

        void restoreActors() { for (var snapshot : actors) snapshot.actor().load(snapshot.state()); }
    }

    private record ParentPose(Vec3 position, Vec3 motion, boolean onGround, float yaw, float pitch, float bodyYaw, float headYaw) {
        static ParentPose capture(YukkuriEntity parent) {
            return new ParentPose(parent.position(), parent.getDeltaMovement(), parent.onGround(), parent.getYRot(), parent.getXRot(), parent.yBodyRot, parent.getYHeadRot());
        }

        void restore(YukkuriEntity parent) {
            parent.moveTo(position.x, position.y, position.z, yaw, pitch);
            parent.setDeltaMovement(motion); parent.setOnGround(onGround);
            parent.setYRot(yaw); parent.yBodyRot = bodyYaw; parent.setYHeadRot(headYaw);
        }
    }

    private ScenarioBoats() {}
}
