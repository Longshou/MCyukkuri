package io.github.longshou.mcyukkuri.command;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.subspecies.HatBoatEntity;
import io.github.longshou.mcyukkuri.subspecies.HatBoatRoute;
import io.github.longshou.mcyukkuri.subspecies.HatVessel;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

/**
 * Starting conditions of the water scenarios: the owner's real hat boat afloat with a real paddle and two portions of
 * food, young aboard or in the water. Movement, cargo, shore visits and rescues afterwards belong to ordinary goals.
 * Modes: sail, shore, low, high, voyage, braid, self, birth.
 */
final class ScenarioBoats {
    private static final Set<String> MODES = Set.of("low", "high", "voyage", "self", "braid", "shore", "birth", "sail");

    /**
     * bank is the dry feet block, water the adjacent surface-water block (bank.y = water.y + 1). Returns null on
     * success, otherwise the reason nothing was prepared.
     */
    static String prepare(String mode, YukkuriEntity parent, List<YukkuriEntity> young, BlockPos bank, BlockPos water) {
        if (!MODES.contains(mode)) return "未知模式 " + mode + "。";
        if (!(parent.level() instanceof ServerLevel level) || !parent.isAlive() || !HatVessel.aquatic(parent))
            return "亲船的主角必须是活着的水生魔理沙。";
        if (!parent.family().adult() && !mode.equals("self")) return "这个模式的亲船主角要是成年。";
        if (mode.equals("self") && (!parent.childhood().canJump()
                || !parent.family().adult() && !parent.aquaticLife().firstVoyage().readySize()))
            return "自救场景的小子油要已能跳跃，且体型够大（readySize）。";
        if (!level.hasChunkAt(bank) || !level.hasChunkAt(water) || bank.getY() != water.getY() + 1
                || Math.abs(bank.getX() - water.getX()) + Math.abs(bank.getZ() - water.getZ()) != 1)
            return "岸边格与水面格要相邻（岸比水面高一格）。";
        if (!parent.life().dryStand(bank)) return "岸边格站不下（要干燥、有支撑、净空）。";
        if (!HatBoatRoute.calmSurface(level, water)) return "水面格不是平静的水源（水深、流动或上方有遮挡）。";
        if (young.contains(parent) || young.size() > HatBoatEntity.MAX_YOUNG || new HashSet<>(young).size() != young.size())
            return "幼体名单不合法。";
        if (mode.equals("self") && !young.isEmpty() || mode.equals("low") && (young.isEmpty() || young.size() > 2)
                || mode.equals("high") && young.size() < 4
                || (mode.equals("voyage") || mode.equals("braid") || mode.equals("birth")) && young.size() != 1)
            return "幼体数量与模式 " + mode + " 不符。";
        if (mode.equals("birth") && !young.getFirst().family().adult()) return "接生场景岸上的伴侣要是成年。";
        var actors = new ArrayList<YukkuriEntity>();
        actors.add(parent);
        actors.addAll(young);
        for (var actor : actors) {
            if (!actor.isAlive() || actor.level() != level || !HatVessel.aquatic(actor) || actor.isPassenger() || actor.isVehicle()
                    || actor.subspecies().boatId() != null) return actor.getName().getString() + " 不能参加：已死亡、不是水生魔理沙、已在船上或已有帽船。";
            if (actor != parent && actor.family().adult() && !mode.equals("birth")) return "同船的幼体要是未成年。";
        }
        if (mode.equals("voyage") && !young.getFirst().aquaticLife().firstVoyage().readySize()) return "初航的小子油体型还不够（readySize）。";
        var bankPoint = Vec3.atBottomCenterOf(bank);
        if (mode.equals("birth") && (young.getFirst().distanceToSqr(bankPoint) > 64 || !young.getFirst().onGround()
                || !young.getFirst().life().dryStand(young.getFirst().blockPosition())))
            return "接生场景里岸上的伴侣要站在岸边八格内的干地上。";
        if (!level.noCollision(parent, parent.getBoundingBox().move(bankPoint.subtract(parent.position())))) return "岸边格放不下亲油的身体。";
        var away = new Vec3(water.getX() - bank.getX(), 0, water.getZ() - bank.getZ());
        var immersion = new Vec3(water.getX() + .5, water.getY() + .12, water.getZ() + .5).add(away.scale(mode.equals("braid") ? 1.4 : 1));
        var swimmer = mode.equals("braid") ? young.getFirst() : parent;
        if ((mode.equals("braid") || mode.equals("self"))
                && (!HatBoatRoute.calmSurface(level, BlockPos.containing(immersion))
                || !level.noCollision(swimmer, swimmer.getBoundingBox().move(immersion.subtract(swimmer.position())))))
            return "落水点不是平静开阔的水面。";

        if (!mode.equals("birth")) for (var child : young) {
            boolean fresh = child.family().mother() == null && child.family().father() == null && child.family().birthId() == null;
            String refused = ScenarioSetup.kin(parent, child, false);
            if (refused != null) return refused;
            // A fresh baby gets the infant care contract; a child that already has kin keeps what it has earned.
            if (fresh && child.family().stage() == io.github.longshou.mcyukkuri.family.FamilyLife.Stage.BABY) child.childhood().newborn();
        }
        parent.setPos(bankPoint);
        parent.setDeltaMovement(Vec3.ZERO);
        parent.setOnGround(true);
        if (mode.equals("self")) {
            parent.setPos(immersion);
            parent.setOnGround(false);
            parent.waterDistress().plunge(null, null);
            return null;
        }

        var boat = HatBoatEntity.createFor(parent, water);
        if (boat == null) return "帽船没能放下（亲油要戴着帽子、离水三格内、水面平静且空间够）。";
        parent.setPos(boat.toWorld(boat.ownerSeat(parent)));
        if (boat.cargoRoom() < 2) { boat.ejectPassengers(); boat.discard(); return "船舱放不下两份口粮。"; }
        if (!boat.cargo().givePaddle(new ItemStack(Items.STICK, 2), false)) { boat.ejectPassengers(); boat.discard(); return "桨没能交给帽船。"; }
        var food = new ItemStack(Items.APPLE, 8);
        if (boat.cargo().store(food, 2, false) < 2) { boat.ejectPassengers(); boat.discard(); return "口粮没能放进船舱。"; }
        if (mode.equals("voyage")) {
            var child = young.getFirst();
            if (!child.aquaticLife().hasStick()) child.aquaticLife().giveStick(new ItemStack(Items.STICK, 1), false);
        }
        if (mode.equals("birth")) {
            // the mate keeps standing on the bank; the conception itself is scenario birth
        } else if (mode.equals("braid")) {
            var child = young.getFirst();
            child.setPos(immersion);
            child.setDeltaMovement(Vec3.ZERO);
            child.setOnGround(false);
            child.waterDistress().plunge(boat.getUUID(), null);
        } else {
            for (var child : young) {
                if (mode.equals("high") && !boat.hasRoomFor(child)) boat.requestEmergencyBerth(child);
                if (!boat.boardYoung(child)) { boat.ejectPassengers(); boat.discard(); return "幼体没能上船（船满了或还不能载）。"; }
            }
        }
        // The call-back may be refused on the very tick the boat is created; the data pack asks again (scenario shore) a moment later.
        if (mode.equals("shore")) parent.aquaticLife().requestShoreVisit();
        if (mode.equals("voyage")) young.getFirst().aquaticLife().firstVoyage().tryStart();
        return null;
    }

    private ScenarioBoats() {}
}
