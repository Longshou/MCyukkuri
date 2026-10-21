package io.github.longshou.mcyukkuri.command;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.family.FamilyLife;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import io.github.longshou.mcyukkuri.subspecies.HatBoatEntity;
import io.github.longshou.mcyukkuri.subspecies.HatBoatRoute;
import io.github.longshou.mcyukkuri.subspecies.HatVessel;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

/**
 * Starting conditions of the water scenarios: the owner's real hat boat afloat with a real paddle and finite provisions,
 * young aboard or in the water. Movement, cargo, shore visits and rescues afterwards belong to ordinary goals.
 * Cargo modes start empty or full, with a child before loading, or a full hat before rescue; stow keeps seven portions beside one child.
 * afloat: an adult or already trained young captain starts directly aboard one physical hat on the specified calm water.
 * Ordinary afloat scenarios share the same spawn-time hull creation path; unattended and self retain their shore/water setup.
 * drop: 1-4 young aboard; the first listed one is shaken into the water {@link #DROP_DELAY} ticks later through the hat's own
 * fall path (HatBoatEntity.dropRiderAfter), so the ordinary plunge/rescue behaviour follows. shake: 0-4 young aboard and one
 * scenario shove at once (HatBoatEntity.shake with no person), which calls the family only if the captain really has young.
 */
final class ScenarioBoats {
    /** Ticks between the end of preparation and the scripted fall of the first young in mode drop. */
    static final int DROP_DELAY = 100;
    private static final Set<String> MODES = Set.of("high", "voyage", "self", "braid", "shore", "birth", "sail", "drop", "shake", "afloat", "unattended",
            "self_far", "self_tired", "return_capsized", "capsize_family", "cargo_empty", "cargo_full", "cargo_load", "cargo_rescue", "cargo_stow");

    /**
     * bank is a dry feet block, water a calm surface-water block two to eight horizontal blocks seaward
     * ({@code bank.y = water.y + 1}). The gap leaves the hull clear of the bank while retaining a real shore route.
     * Returns null on success, otherwise the reason nothing was prepared.
     */
    static String prepare(String mode, YukkuriEntity parent, List<YukkuriEntity> young, BlockPos bank, BlockPos water) {
        if (!MODES.contains(mode)) return "未知模式 " + mode + "。";
        if (!(parent.level() instanceof ServerLevel level) || !parent.isAlive() || !parent.profile().aquatic())
            return "亲船的主角必须是活着的水生魔理沙。";
        if (mode.equals("afloat") && !parent.aquaticLife().firstVoyage().mayLaunch())
            return "直接水上生成需要成年帽主，或已完成初航、具备操帽能力的子油。";
        if (!parent.family().adult() && !mode.equals("self") && !mode.equals("afloat")) return "这个模式的亲船主角要是成年。";
        if (mode.equals("self") && (!parent.childhood().canJump()
                || !parent.family().adult() && !parent.aquaticLife().firstVoyage().readySize()))
            return "自救场景的小子油要已能跳跃，且体型够大（readySize）。";
        int waterDistance = Math.abs(bank.getX() - water.getX()) + Math.abs(bank.getZ() - water.getZ());
        if (!level.hasChunkAt(bank) || !level.hasChunkAt(water) || bank.getY() != water.getY() + 1
                || waterDistance < 2 || waterDistance > 8)
            return "水面格要在干岸外 2–8 格（岸比水面高一格）。";
        if (!parent.life().dryStand(bank)) return "岸边格站不下（要干燥、有支撑、净空）。";
        if (!HatBoatRoute.calmSurface(level, water)) return "水面格不是平静的水源（水深、流动或上方有遮挡）。";
        if (young.contains(parent) || young.size() > HatBoatEntity.MAX_YOUNG || new HashSet<>(young).size() != young.size())
            return "幼体名单不合法。";
        if (mode.equals("self") && !young.isEmpty() || mode.equals("high") && young.size() < 4
                || mode.equals("drop") && (young.isEmpty() || young.size() > HatBoatEntity.MAX_YOUNG)
                || (mode.equals("voyage") || mode.equals("braid") || mode.equals("birth") || mode.equals("cargo_rescue")
                || mode.equals("cargo_load") || mode.equals("cargo_stow")) && young.size() != 1)
            return "幼体数量与模式 " + mode + " 不符。";
        if (mode.equals("birth") && !young.getFirst().family().adult()) return "接生场景岸上的伴侣要是成年。";
        var actors = new ArrayList<YukkuriEntity>();
        actors.add(parent);
        actors.addAll(young);
        for (var actor : actors) {
            if (!actor.isAlive() || actor.level() != level || !actor.profile().aquatic() || actor.isPassenger() || actor.isVehicle()
                    || actor.subspecies().boatId() != null) return actor.getName().getString() + " 不能参加：已死亡、不是水生魔理沙、已在船上或已有帽船。";
            if (actor.family().active() || actor.family().flying() || actor.family().heldByBraid() || actor.operations().active())
                return actor.getName().getString() + " 不能参加：正处在不能安全回滚的照护、飞行、辫子救援或操作中。";
            if (actor != parent && actor.family().adult() && !mode.equals("birth")) return "同船的幼体要是未成年。";
        }
        if (mode.equals("voyage") && !young.getFirst().aquaticLife().firstVoyage().readySize()) return "初航的小子油体型还不够（readySize）。";
        var bankPoint = Vec3.atBottomCenterOf(bank);
        if (mode.equals("birth") && (young.getFirst().distanceToSqr(bankPoint) > 64 || !young.getFirst().onGround()
                || !young.getFirst().life().dryStand(young.getFirst().blockPosition())))
            return "接生场景里岸上的伴侣要站在岸边八格内的干地上。";
        if (!level.noCollision(parent, parent.getBoundingBox().move(bankPoint.subtract(parent.position())))) return "岸边格放不下亲油的身体。";
        var away = new Vec3(water.getX() - bank.getX(), 0, water.getZ() - bank.getZ()).normalize();
        boolean rescue = mode.equals("braid") || mode.equals("cargo_rescue");
        boolean waterReturn = mode.equals("self_far") || mode.equals("self_tired");
        if ((waterReturn || mode.equals("return_capsized")) && !young.isEmpty()) return "返帽场景只放一位帽主。";
        var immersion = new Vec3(water.getX() + .5, water.getY() + .12, water.getZ() + .5).add(away.scale(waterReturn ? 9 : rescue ? 1.4 : 1));
        var returnStand = bankPoint.subtract(away.scale(6));
        if (mode.equals("return_capsized") && (!parent.life().dryStand(BlockPos.containing(returnStand))
                || !level.noCollision(parent,parent.getBoundingBox().move(returnStand.subtract(parent.position())))))
            return "岸上的返帽起点站不下。";
        var swimmer = rescue ? young.getFirst() : parent;
        if ((rescue || mode.equals("self") || waterReturn)
                && (!HatBoatRoute.calmSurface(level, BlockPos.containing(immersion))
                || !level.noCollision(swimmer, swimmer.getBoundingBox().move(immersion.subtract(swimmer.position())))))
            return "落水点不是平静开阔的水面。";
        if (!mode.equals("birth")) for (var child : young) {
            String refusal = ScenarioSetup.kinRefusal(parent, child, false);
            if (refusal != null) return refusal;
        }

        if (mode.equals("self")) {
            parent.setPos(immersion);
            parent.setOnGround(false);
            parent.waterDistress().plunge(null, null);
            return null;
        }

        var parentPose = ParentPose.capture(parent);
        var scenarioTransaction = ScenarioTransaction.capture(parent, young, !mode.equals("birth"));
        var boat = mode.equals("unattended")
                ? HatBoatEntity.setDown(parent, Vec3.atBottomCenterOf(water), parent.yBodyRot,
                        HatVessel.aquatic(parent)?HatVessel.Mode.AQUATIC_FLOAT:HatVessel.Mode.FLOAT, HatVessel.Role.HELPER)
                : HatBoatEntity.createAfloatAndBoard(parent, water);
        if (boat == null) return abort(null, parent, parentPose, scenarioTransaction, "帽船没能生成（帽主要有自己的帽子与操帽能力，水面平静且船体放得下）。");
        if (mode.equals("unattended") && (!level.getWorldBorder().isWithinBounds(boat.getBoundingBox())
                || !level.noCollision(boat, boat.getBoundingBox())))
            return abort(boat, parent, parentPose, scenarioTransaction, "暂放帽船的船体被方块、实体或世界边界阻挡。");
        if (mode.equals("unattended")) {
            parent.setPos(bankPoint);parent.setDeltaMovement(Vec3.ZERO);parent.setOnGround(true);
        } else parent.setPos(boat.toWorld(boat.ownerSeat(parent)));
        int provisions = switch (mode) {
            case "cargo_empty", "cargo_load" -> 0;
            case "cargo_full", "cargo_rescue" -> 11;
            case "cargo_stow" -> 7;
            case "high" -> 3;
            default -> 2;
        };
        if (boat.cargoRoom() < provisions) return abort(boat, parent, parentPose, scenarioTransaction, "船舱放不下场景所需的 " + provisions + " 份口粮。");
        if (!boat.cargo().paddle() && !boat.cargo().givePaddle(new ItemStack(Items.STICK), false)) {
            return abort(boat, parent, parentPose, scenarioTransaction, "桨没能交给帽船。");
        }
        if (provisions > 0 && boat.cargo().store(new ItemStack(Items.APPLE, provisions), provisions, false) < provisions) {
            return abort(boat, parent, parentPose, scenarioTransaction, "口粮没能放进船舱。");
        }
        // A temporary actor-local family mirror lets the shared berth code see the prospective parent without writing a ledger.
        scenarioTransaction.establishTemporaryKin();
        if (mode.equals("voyage")) {
            var child = young.getFirst();
            if (!child.aquaticLife().hasStick()) child.aquaticLife().giveStick(new ItemStack(Items.STICK, 1), false);
        }
        if (!mode.equals("birth") && !rescue) {
            for (var child : young) {
                // Ask for an existing safe refuge layout; the shared capacity limit still applies.
                if (!boat.hasRoomFor(child)) boat.requestEmergencyBerth(child);
                if (!boat.boardYoung(child)) return abort(boat, parent, parentPose, scenarioTransaction, "幼体没能上船（船满了或还不能载）。");
            }
        }
        String kinCommitRefusal = scenarioTransaction.commitRealKin();
        if (kinCommitRefusal != null) return abort(boat, parent, parentPose, scenarioTransaction, kinCommitRefusal);
        // Only committed starting conditions may trigger the ordinary fall, alarm, shore or first-voyage controllers.
        if (rescue) {
            var child = young.getFirst();
            child.setPos(immersion);
            child.setDeltaMovement(Vec3.ZERO);
            child.setOnGround(false);
            child.waterDistress().plunge(boat.getUUID(), null);
        }
        if (mode.equals("drop")) boat.dropRiderAfter(young.getFirst(), DROP_DELAY);
        if (mode.equals("shake")) boat.shake(null, 1);
        if (mode.equals("capsize_family")) boat.capsize();
        if (waterReturn || mode.equals("return_capsized")) {
            parent.stopRiding();
            parent.setPos(waterReturn ? immersion : returnStand);
            parent.setDeltaMovement(Vec3.ZERO);
            parent.setOnGround(!waterReturn);
            if (mode.equals("self_tired")) parent.life().spendEnergy(Math.max(0,parent.life().energy()-3));
            if (waterReturn) parent.waterDistress().plunge(boat.getUUID(),null);
            else boat.capsize();
        }
        // The call-back may be refused on the very tick the boat is created; the data pack asks again (scenario shore) a moment later.
        if (mode.equals("shore")) parent.aquaticLife().requestShoreVisit();
        if (mode.equals("voyage")) young.getFirst().aquaticLife().firstVoyage().tryStart();
        GameplayLog.event(parent,"aquatic","scenario initial state prepared mode={} hat={} passengers={} food={} paddle={}",
                mode,boat.getUUID(),boat.getPassengers().size(),boat.cargo().count(),boat.cargo().paddle());
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
