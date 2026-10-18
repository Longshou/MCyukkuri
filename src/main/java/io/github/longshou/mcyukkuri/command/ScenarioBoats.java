package io.github.longshou.mcyukkuri.command;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.subspecies.HatBoatEntity;
import io.github.longshou.mcyukkuri.subspecies.HatBoatRoute;
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
 * Starting conditions of the water scenarios: the owner's real hat boat afloat with a real paddle and finite provisions,
 * young aboard or in the water. Movement, cargo, shore visits and rescues afterwards belong to ordinary goals.
 * Cargo modes start with 0/2/5/8 portions, a child before loading, a full hat before rescue, or a full hat with a child on its brim.
 * afloat: the spawn-time path itself (AquaticLife.requestAfloatSpawn): within about a second the adult is put into her own hat boat on
 * the nearest calm surface within 14 blocks (her young nearby board too), or stays on the bank if there is none; bank/water are not used.
 * drop: 1-4 young aboard; the first listed one is shaken into the water {@link #DROP_DELAY} ticks later through the hat's own
 * fall path (HatBoatEntity.dropRiderAfter), so the ordinary plunge/rescue behaviour follows. shake: 0-4 young aboard and one
 * scenario shove at once (HatBoatEntity.shake with no person), which calls the family only if the captain really has young.
 */
final class ScenarioBoats {
    /** Ticks between the end of preparation and the scripted fall of the first young in mode drop. */
    static final int DROP_DELAY = 100;
    private static final Set<String> MODES = Set.of("low", "high", "voyage", "self", "braid", "shore", "birth", "sail", "drop", "shake", "afloat",
            "cargo_empty", "cargo_little", "cargo_much", "cargo_full", "cargo_load", "cargo_rescue", "cargo_stow");

    /**
     * bank is the dry feet block, water the adjacent surface-water block (bank.y = water.y + 1). Returns null on
     * success, otherwise the reason nothing was prepared.
     */
    static String prepare(String mode, YukkuriEntity parent, List<YukkuriEntity> young, BlockPos bank, BlockPos water) {
        if (!MODES.contains(mode)) return "未知模式 " + mode + "。";
        if (!(parent.level() instanceof ServerLevel level) || !parent.isAlive() || !parent.profile().aquatic())
            return "亲船的主角必须是活着的水生魔理沙。";
        if (mode.equals("afloat")) {
            if (!parent.family().adult() || parent.isPassenger() || parent.subspecies().boatId() != null) return "上船生成要是成年、没在船上也没有帽船。";
            parent.aquaticLife().requestAfloatSpawn();
            return null;
        }
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
                || mode.equals("high") && young.size() < 4 || mode.equals("drop") && (young.isEmpty() || young.size() > HatBoatEntity.MAX_YOUNG)
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
            if (actor != parent && actor.family().adult() && !mode.equals("birth")) return "同船的幼体要是未成年。";
        }
        if (mode.equals("voyage") && !young.getFirst().aquaticLife().firstVoyage().readySize()) return "初航的小子油体型还不够（readySize）。";
        var bankPoint = Vec3.atBottomCenterOf(bank);
        if (mode.equals("birth") && (young.getFirst().distanceToSqr(bankPoint) > 64 || !young.getFirst().onGround()
                || !young.getFirst().life().dryStand(young.getFirst().blockPosition())))
            return "接生场景里岸上的伴侣要站在岸边八格内的干地上。";
        if (!level.noCollision(parent, parent.getBoundingBox().move(bankPoint.subtract(parent.position())))) return "岸边格放不下亲油的身体。";
        var away = new Vec3(water.getX() - bank.getX(), 0, water.getZ() - bank.getZ());
        boolean rescue = mode.equals("braid") || mode.equals("cargo_rescue");
        var immersion = new Vec3(water.getX() + .5, water.getY() + .12, water.getZ() + .5).add(away.scale(rescue ? 1.4 : 1));
        var swimmer = rescue ? young.getFirst() : parent;
        if ((rescue || mode.equals("self"))
                && (!HatBoatRoute.calmSurface(level, BlockPos.containing(immersion))
                || !level.noCollision(swimmer, swimmer.getBoundingBox().move(immersion.subtract(swimmer.position())))))
            return "落水点不是平静开阔的水面。";

        if (!mode.equals("birth")) for (var child : young) {
            boolean fresh = child.family().mother() == null && child.family().father() == null && child.family().birthId() == null;
            String refused = ScenarioSetup.kin(parent, child, false);
            if (refused != null) return refused;
            // A fresh baby gets the infant care contract; a child that already has kin keeps what it has earned.
            if (fresh && child.family().stage() == io.github.longshou.mcyukkuri.family.FamilyLife.Stage.BABY) child.childhood().newborn();
            // Young ride bare-headed in the parent's hat; their own hats come with their own first voyage.
            child.life().removeAccessory();
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
        int provisions = switch (mode) {
            case "cargo_empty", "cargo_load" -> 0;
            case "cargo_much" -> 5;
            case "cargo_full", "cargo_rescue", "cargo_stow" -> 8;
            default -> 2;
        };
        if (boat.cargoRoom() < provisions) { boat.abortDeployment(parent); return "船舱放不下场景所需的 " + provisions + " 份口粮。"; }
        if (!boat.cargo().paddle() && !boat.cargo().givePaddle(new ItemStack(Items.STICK), false)) {
            boat.abortDeployment(parent); return "桨没能交给帽船。";
        }
        if (provisions > 0 && boat.cargo().store(new ItemStack(Items.APPLE, provisions), provisions, false) < provisions) {
            boat.abortDeployment(parent); return "口粮没能放进船舱。";
        }
        if (mode.equals("voyage")) {
            var child = young.getFirst();
            if (!child.aquaticLife().hasStick()) child.aquaticLife().giveStick(new ItemStack(Items.STICK, 1), false);
        }
        if (mode.equals("birth")) {
            // the mate keeps standing on the bank; the conception itself is scenario birth
        } else if (rescue) {
            var child = young.getFirst();
            child.setPos(immersion);
            child.setDeltaMovement(Vec3.ZERO);
            child.setOnGround(false);
            child.waterDistress().plunge(boat.getUUID(), null);
        } else {
            for (var child : young) {
                // Several young may need a compressed refuge patch in a full hat: ask for it before giving up.
                if (!boat.hasRoomFor(child)) boat.requestEmergencyBerth(child);
                if (!boat.boardYoung(child)) { boat.abortDeployment(parent); return "幼体没能上船（船满了或还不能载）。"; }
            }
            if (mode.equals("drop")) boat.dropRiderAfter(young.getFirst(), DROP_DELAY);
            if (mode.equals("shake")) boat.shake(null, 1);
        }
        // The call-back may be refused on the very tick the boat is created; the data pack asks again (scenario shore) a moment later.
        if (mode.equals("shore")) parent.aquaticLife().requestShoreVisit();
        if (mode.equals("voyage")) young.getFirst().aquaticLife().firstVoyage().tryStart();
        return null;
    }

    private ScenarioBoats() {}
}
