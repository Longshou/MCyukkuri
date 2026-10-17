package io.github.longshou.mcyukkuri.command;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.family.FamilyData;
import io.github.longshou.mcyukkuri.life.YukkuriWorldData;
import io.github.longshou.mcyukkuri.subspecies.HatBoatEntity;
import io.github.longshou.mcyukkuri.subspecies.HatBoatRoute;
import io.github.longshou.mcyukkuri.subspecies.HatVessel;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Operator-authored starting conditions; every movement after setup belongs to real aquatic goals. */
public final class MuseumAquaticSetup {
    private static final String ACTIVE = "pm_museum_aquatic";
    private static final String GROUP = "MuseumAquaticOwner";
    private static final String MEMBERS = "MuseumAquaticMembers";
    private static final Set<String> MODES = Set.of("low", "high", "voyage", "self", "braid", "shore", "birth", "sail");

    /**
     * bank is the dry feet block, water the adjacent surface-fluid block (bank.y = water.y + 1).
     * Supply entities must carry pm_aquatic_supply plus the parent's room tag, within eight blocks.
     * A 9 by 5 clear surface is recommended; voyage needs room for an actual out-and-back route.
     * This method never creates children, grants lesson completion or refills a previously prepared boat.
     */
    public static boolean prepare(String mode, YukkuriEntity parent, List<YukkuriEntity> young,
                                  BlockPos bank, BlockPos water) {
        if (!MODES.contains(mode) || !(parent.level() instanceof ServerLevel level)
                || !parent.getTags().contains("pm_actor") || !HatVessel.aquatic(parent)
                || !parent.family().adult() && !mode.equals("self") || !parent.isAlive()) return false;
        if (mode.equals("self") && (!parent.childhood().canJump()
                || !parent.family().adult() && !parent.aquaticLife().firstVoyage().readySize())) return false;
        if (parent.getTags().contains(ACTIVE)) {
            if (mode.equals("shore")) return parent.aquaticLife().requestShoreVisit();
            return (mode.equals("birth") || mode.equals("sail"))
                    && parent.getVehicle() instanceof HatBoatEntity existing && existing.isAfloat()
                    && parent.getUUID().equals(existing.ownerYukkuri())
                    && young.stream().allMatch(actor -> actor != null && actor.getTags().contains(ACTIVE)
                    && actor.getPersistentData().hasUUID(GROUP) && actor.getPersistentData().getUUID(GROUP).equals(parent.getUUID()));
        }
        if (!level.hasChunkAt(bank) || !level.hasChunkAt(water) || bank.getY() != water.getY() + 1
                || Math.abs(bank.getX() - water.getX()) + Math.abs(bank.getZ() - water.getZ()) != 1
                || !parent.life().dryStand(bank) || !HatBoatRoute.calmSurface(level, water)) return false;
        var room = parent.getTags().stream().filter(t -> t.startsWith("room_")).sorted().findFirst().orElse(null);
        if (room == null || young.contains(parent) || young.stream().anyMatch(java.util.Objects::isNull) || young.size() > HatBoatEntity.MAX_YOUNG
                || new HashSet<>(young).size() != young.size()) return false;
        if (mode.equals("self") && !young.isEmpty() || mode.equals("low") && (young.isEmpty() || young.size() > 2)
                || mode.equals("high") && young.size() < 4
                || (mode.equals("voyage") || mode.equals("braid") || mode.equals("birth")) && young.size() != 1) return false;
        if (mode.equals("birth") && !young.getFirst().family().adult()) return false;
        var actors = new ArrayList<YukkuriEntity>(); actors.add(parent); actors.addAll(young);
        for (var actor : actors) if (actor == null || !actor.isAlive() || actor.level() != level
                || !actor.getTags().contains("pm_actor") || !actor.getTags().contains(room)
                || !HatVessel.aquatic(actor) || actor.getTags().contains(ACTIVE)
                || actor.isPassenger() || actor.isVehicle() || actor.subspecies().boatId() != null
                || actor != parent && actor.family().adult() && !mode.equals("birth")) return false;
        if (mode.equals("voyage") && !young.getFirst().aquaticLife().firstVoyage().readySize()) return false;
        var bankPoint = Vec3.atBottomCenterOf(bank);
        if (mode.equals("birth") && (young.getFirst().distanceToSqr(bankPoint) > 64 || !young.getFirst().onGround()
                || !young.getFirst().life().dryStand(young.getFirst().blockPosition()))) return false;
        if (!level.noCollision(parent, parent.getBoundingBox().move(bankPoint.subtract(parent.position())))) return false;
        var away = new Vec3(water.getX() - bank.getX(), 0, water.getZ() - bank.getZ());
        var immersion = new Vec3(water.getX() + .5, water.getY() + .12, water.getZ() + .5)
                .add(away.scale(mode.equals("braid") ? 1.4 : 1));
        var swimmer = mode.equals("braid") ? young.getFirst() : parent;
        if ((mode.equals("braid") || mode.equals("self"))
                && (!HatBoatRoute.calmSurface(level, BlockPos.containing(immersion))
                || !level.noCollision(swimmer, swimmer.getBoundingBox().move(immersion.subtract(swimmer.position()))))) return false;

        List<ItemEntity> supplies = level.getEntitiesOfClass(ItemEntity.class, new AABB(bank).inflate(8),
                item -> item.isAlive() && item.getTags().contains("pm_aquatic_supply") && item.getTags().contains(room));
        int sticks = supplies.stream().filter(item -> item.getItem().is(Items.STICK)).mapToInt(item -> item.getItem().getCount()).sum();
        int food = supplies.stream().filter(item -> parent.life().acceptsFood(item.getItem())).mapToInt(item -> item.getItem().getCount()).sum();
        if (!mode.equals("self") && (sticks < (mode.equals("voyage") ? 2 : 1) || food < 2)) return false;

        // Local transaction only: it cannot survive this command or undo a single simulation tick.
        // Source entities stay present until commit; failure restores their exact stacks in this command.
        var before = new LinkedHashMap<YukkuriEntity, CompoundTag>();
        var ancestry = new LinkedHashMap<YukkuriEntity, Ancestry>();
        var affinity = new LinkedHashMap<YukkuriEntity, int[]>();
        var supplyBefore = new LinkedHashMap<ItemEntity, net.minecraft.world.item.ItemStack>();
        for (var item : supplies) supplyBefore.put(item, item.getItem().copy());
        var ledger = FamilyData.get(level); var relations = YukkuriWorldData.get(level);
        for (var actor : actors) {
            var tag = new CompoundTag(); actor.saveWithoutId(tag); before.put(actor, tag);
            var person = ledger.person(actor.getUUID());
            ancestry.put(actor, person == null ? null : new Ancestry(person.mother, person.father, person.brood));
            if (actor != parent) affinity.put(actor, new int[]{relations.affinity(parent.getUUID(), actor.getUUID()),
                    relations.affinity(actor.getUUID(), parent.getUUID())});
        }
        Runnable rollback = () -> {
            var created = HatBoatEntity.owned(parent);
            if (created != null) { created.ejectPassengers(); created.discard(); }
            before.forEach((actor, tag) -> {
                actor.stopRiding(); actor.load(tag);
                var prior = ancestry.get(actor);
                if (prior == null) ledger.rollbackEncounter(Set.of(actor.getUUID()));
                else {
                    var person = ledger.register(actor);
                    person.mother = prior.mother(); person.father = prior.father(); person.brood = prior.brood();
                }
            });
            affinity.forEach((child, old) -> {
                relations.relate(parent.getUUID(), child.getUUID(), old[0] - relations.affinity(parent.getUUID(), child.getUUID()));
                relations.relate(child.getUUID(), parent.getUUID(), old[1] - relations.affinity(child.getUUID(), parent.getUUID()));
            });
            supplyBefore.forEach((item, stack) -> item.setItem(stack.copy()));
            ledger.setDirty();
        };

        var members = new ListTag();
        for (var actor : actors) { var member = new CompoundTag(); member.putUUID("Id", actor.getUUID()); members.add(member); }
        for (var actor : actors) {
            if (!MuseumLiveActors.prepare(actor, "observe")) { rollback.run(); return false; }
            actor.addTag(ACTIVE);
            actor.getPersistentData().putUUID(GROUP, parent.getUUID());
            actor.getPersistentData().put(MEMBERS, members.copy());
        }
        parent.setPos(bankPoint); parent.setDeltaMovement(Vec3.ZERO); parent.setOnGround(true);
        if (!mode.equals("birth")) for (var child : young) {
            boolean fresh = child.family().mother() == null && child.family().father() == null && child.family().birthId() == null;
            if (!MuseumLiveActors.parent(parent, child, false)) { rollback.run(); return false; }
            // Legacy fixed baby actors had no childhood state and therefore appeared
            // able to jump. Only a fresh fixture gets infant prerequisites; repeat play
            // keeps the actual care and movement evidence it has since earned.
            if (fresh && child.family().stage() == io.github.longshou.mcyukkuri.family.FamilyLife.Stage.BABY) child.childhood().newborn();
        }
        if (mode.equals("self")) {
            parent.setPos(immersion); parent.setOnGround(false); parent.waterDistress().plunge(null, null);
            return true;
        }

        // Initial "already aboard" fixture, using the real unique accessory and real seating planner.
        // Embarkation itself is demonstrated by voyage/shore and their ordinary controllers.
        var boat = HatBoatEntity.createFor(parent, water);
        if (boat == null) { rollback.run(); return false; }
        boat.addTag(ACTIVE); boat.addTag(room);
        parent.setPos(boat.toWorld(boat.ownerSeat(parent)));
        // Reserve the real food envelopes before selecting child seats, so E's shared
        // cargo/body space contract can never seat a baby inside a subsequently added pile.
        if (boat.cargoRoom() < 2) { rollback.run(); return false; }
        int remaining = 2;
        for (var item : supplies) {
            var stack = item.getItem();
            if (stack.is(Items.STICK)) {
                if (!boat.cargo().paddle()) boat.cargo().givePaddle(stack, false);
                if (!stack.isEmpty() && mode.equals("voyage") && !young.getFirst().aquaticLife().hasStick())
                    young.getFirst().aquaticLife().giveStick(stack, false);
            } else if (remaining > 0 && parent.life().acceptsFood(stack))
                remaining -= boat.cargo().store(stack, remaining, false);
        }
        if (remaining != 0 || !boat.cargo().paddle()
                || mode.equals("voyage") && !young.getFirst().aquaticLife().hasStick()) { rollback.run(); return false; }
        if (mode.equals("birth")) {
            // The existing adult mate keeps his reachable dry-shore position. Actual
            // pregnancy and unique children belong to birth_scene deliver and the ledger.
        } else if (mode.equals("braid")) {
            var child = young.getFirst(); child.setPos(immersion); child.setDeltaMovement(Vec3.ZERO);
            child.setOnGround(false); child.waterDistress().plunge(boat.getUUID(), null);
        } else {
            for (var child : young) {
                if (mode.equals("high") && !boat.hasRoomFor(child)) boat.requestEmergencyBerth(child);
                if (!boat.boardYoung(child)) { rollback.run(); return false; }
            }
        }
        if (mode.equals("shore") && !parent.aquaticLife().requestShoreVisit()) {
            rollback.run(); return false;
        }
        for (var item : supplies) {
            var stack = item.getItem();
            if (stack.isEmpty()) item.discard();
            else item.setItem(stack.copy());
        }
        return true;
    }

    /** Only relinquish control after real landing/retrieval. Every hat, cargo and learned milestone survives. */
    public static boolean stop(YukkuriEntity actor) {
        if (!actor.getTags().contains(ACTIVE)) return true;
        if (!(actor.level() instanceof ServerLevel level)) return false;
        if (actor.family().cradlePickup().active() || actor.family().cradleHeld()) return false;
        // A dry child cannot be teleported/frozen while the captain's landing still
        // checks that child at its real landing point. Release the whole family together.
        boolean safe = true;
        for (var raw : actor.getPersistentData().getList(MEMBERS, Tag.TAG_COMPOUND)) {
            var member = (CompoundTag)raw;
            if (!member.hasUUID("Id")) { safe = false; continue; }
            if (!(level.getEntity(member.getUUID("Id")) instanceof YukkuriEntity relative)) {
                var record = FamilyData.get(level).person(member.getUUID("Id"));
                if (record == null || record.status != FamilyData.Status.DEAD && record.status != FamilyData.Status.REMOVED) safe = false;
                continue;
            }
            // Do not request shore movement or hat retrieval during the real braid transfer.
            if (relative.family().cradlePickup().active() || relative.family().cradleHeld()) { safe = false; continue; }
            if (relative.getVehicle() instanceof HatBoatEntity riding && riding.ownerYukkuri() != null
                    && level.getEntity(riding.ownerYukkuri()) instanceof YukkuriEntity captain)
                captain.aquaticLife().requestShoreVisit();
            var owned = HatBoatEntity.owned(relative);
            if (owned != null && owned.catches() && relative.onGround() && !relative.isInWaterOrBubble()
                    && !relative.family().flying() && !relative.family().braidRescueActive()) relative.family().fetchHat(owned);
            if (relative.isInWaterOrBubble() || relative.isPassenger() || relative.isVehicle() || !relative.onGround()
                    || relative.subspecies().boatId() != null || relative.family().flying() || relative.family().braidRescueActive()
                    || relative.birthScene() != null || relative.family().awaitingLanding() || relative.family().laboring()
                    || relative.aquaticLife().selfRescue().active() || relative.aquaticLife().firstVoyage().active()) safe = false;
        }
        if (!safe) return false;
        var boat = HatBoatEntity.owned(actor);
        if (actor.subspecies().boatId() != null && boat == null) return false;
        if (boat != null && boat.getPassengers().stream().anyMatch(passenger -> !passenger.getTags().contains(ACTIVE)
                || !passenger.getPersistentData().hasUUID(GROUP) || !actor.getPersistentData().hasUUID(GROUP)
                || !passenger.getPersistentData().getUUID(GROUP).equals(actor.getPersistentData().getUUID(GROUP)))) return false;
        if (actor.getVehicle() instanceof HatBoatEntity riding) {
            if (riding.ownerYukkuri() != null && level.getEntity(riding.ownerYukkuri()) instanceof YukkuriEntity captain)
                captain.aquaticLife().requestShoreVisit();
            return false;
        }
        if (boat != null) {
            // AquaticLanding owns the actual mouth contact, crew landing and token return.
            // While it is still doing so, no reset may freeze the dry owner beside her hat.
            if (boat.catches() && !actor.family().flying() && !actor.family().braidRescueActive()) actor.family().fetchHat(boat);
            return false;
        }
        if (actor.isInWaterOrBubble() || actor.isPassenger() || actor.isVehicle() || !actor.onGround()
                || actor.family().flying() || actor.family().braidRescueActive()
                || actor.aquaticLife().selfRescue().active() || actor.aquaticLife().firstVoyage().active()) return false;
        actor.aquaticLife().stop();
        actor.removeTag(ACTIVE);
        actor.getPersistentData().remove(GROUP);
        actor.getPersistentData().remove(MEMBERS);
        return true;
    }

    private record Ancestry(UUID mother, UUID father, UUID brood) {}

    private MuseumAquaticSetup() {}
}
