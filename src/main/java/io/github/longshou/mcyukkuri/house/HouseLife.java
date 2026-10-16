package io.github.longshou.mcyukkuri.house;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.family.FamilyData;
import io.github.longshou.mcyukkuri.registry.ModItems;
import java.util.Comparator;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.EventHooks;

/** Loaded-time house life. Residents remain real entities and carry exactly one real resource. */
public final class HouseLife {
    private enum Phase { NONE, APPROACH, ENTER, REST, LEAVE, PEEK, TEND, LISTEN, STARTLED, SEARCH }
    private final YukkuriEntity entity;
    private GlobalPos nest;
    private UUID nestIdentity;
    private ItemStack carrying = ItemStack.EMPTY;
    private ItemEntity target;
    private int wait, ticks, careWait, sleepTicks, startledTicks, lostTicks;
    private boolean active, tending, rested, careContact;
    private Phase phase = Phase.NONE;
    private int interaction, interactionTicks;
    private Vec3 contactTarget, restPosition;
    private BlockPos refuge;

    public HouseLife(YukkuriEntity entity) { this.entity = entity; }
    public ItemStack carrying() { return carrying; }
    public BlockPos nestPosition() { return nest != null && nest.dimension().equals(entity.level().dimension()) ? nest.pos() : null; }
    public HouseNestBlockEntity nest() {
        BlockPos pos = nestPosition();
        if (pos == null || !entity.level().hasChunkAt(pos)
                || !(entity.level().getBlockEntity(pos) instanceof HouseNestBlockEntity value)) return null;
        return nestIdentity == null || nestIdentity.equals(value.identity()) ? value : null;
    }
    public boolean choose(HouseNestBlockEntity value) {
        if (!entity.getVariant().isHouse() || !value.usable(entity) || !entity.life().reachable(value.entrance(), false)) return false;
        nest = GlobalPos.of(entity.level().dimension(), value.getBlockPos()); nestIdentity = value.identity();
        lostTicks = 0; refuge = null; entity.setPersistenceRequired(); return true;
    }
    public BlockPos homeStand() { var value = nest(); return value != null && value.usable(entity) ? value.entrance() : null; }
    public boolean active() { return active; }
    public boolean sleeping() { return active && phase == Phase.REST && sleepTicks >= 20 && physicallyInside(); }
    public boolean wantsCrawl() {
        return active && interaction == 0 && carrying.isEmpty() && (phase == Phase.ENTER || phase == Phase.LEAVE
                || phase == Phase.APPROACH && nestPosition() != null
                && entity.distanceToSqr(Vec3.atBottomCenterOf(nestPosition().north())) < 2.25
                || target != null && target.isAlive() && entity.distanceToSqr(target) < .36);
    }
    private boolean physicallyInside() {
        var value = nest();
        return value != null && entity.onGround() && entity.blockPosition().equals(value.getBlockPos())
                && entity.getY() >= value.getBlockPos().getY() && entity.getY() < value.getBlockPos().getY() + .3
                && Math.abs(entity.getX() - (value.getBlockPos().getX() + .5)) < .3
                && entity.getZ() > value.getBlockPos().getZ() + .02 && entity.getZ() < value.getBlockPos().getZ() + .82;
    }
    public boolean mustExit() {
        var value = nest(); if (value == null) return false; var pos = value.getBlockPos();
        // A vanilla path can start inside this low block after a resident steps onto its roof.
        boolean onRoof = entity.getY() > pos.getY() + .45 && entity.getY() < pos.getY() + 1.2
                && Math.abs(entity.getX() - (pos.getX() + .5)) < .75 && entity.getZ() > pos.getZ() - .2 && entity.getZ() < pos.getZ() + 1.2;
        return onRoof || entity.getZ() > pos.getZ() + .08 && entity.blockPosition().equals(pos)
                && (entity.isPanicking() || entity.isOnFire() || entity.isInWaterOrBubble()
                || !active && startledTicks == 0 && !entity.life().isSleeping() && entity.life().mealTicks() == 0);
    }
    public void tickExit() {
        var value = nest(); if (value == null) return; var exit = Vec3.atBottomCenterOf(value.entrance());
        entity.getNavigation().stop(); entity.getMoveControl().setWantedPosition(exit.x, exit.y, exit.z, 1.15);
    }
    public String activity() {
        if (interaction > 0) return interaction == 1 ? "house_pickup" : "house_deposit";
        return switch (phase) {
            case APPROACH -> tending ? "house_tend" : "house_hide";
            case ENTER -> "house_enter";
            case REST -> sleeping() ? "house_sleep" : "house_hide";
            case LEAVE -> "house_exit";
            case PEEK -> "house_peek";
            case TEND -> "house_tend";
            case LISTEN -> "house_listen";
            case STARTLED -> "house_startled";
            case SEARCH -> "house_search_home";
            default -> carrying.isEmpty() ? "house_scavenge" : "house_carry";
        };
    }
    public void tick() {
        if (entity.level().isClientSide || !entity.getVariant().isHouse() || entity.isNoAi()) return;
        if (wait > 0) wait--;
        if (careWait > 0) careWait--;
        if (startledTicks > 0) startledTicks--;
        if (lostTicks > 0) lostTicks--;
        if (entity.isPanicking() || entity.isOnFire() || entity.isInWaterOrBubble()) { stop(); return; }
        if (entity.tickCount % 100 != 0) return;
        // Unloaded and other-dimension homes are memories, not evidence of destruction.
        if (nest != null && (!nest.dimension().equals(entity.level().dimension()) || !entity.level().hasChunkAt(nest.pos()))) return;
        var value = nest();
        if (value != null && value.usable(entity)) {
            if (nestIdentity == null) nestIdentity = value.identity(); // Upgrade old coordinate-only saves.
            return;
        }
        if (nest != null) disturb(true);
        HouseNestBlockEntity best = null; double score = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.betweenClosed(entity.blockPosition().offset(-8, -1, -8), entity.blockPosition().offset(8, 1, 8))) {
            if (!entity.level().hasChunkAt(pos) || !(entity.level().getBlockEntity(pos) instanceof HouseNestBlockEntity candidate)
                    || !candidate.usable(entity) || candidate.disturbedTicks() > 0) continue;
            double candidateScore = entity.distanceToSqr(Vec3.atBottomCenterOf(candidate.entrance())) - (candidate.built() ? 4 : 0);
            if (candidateScore < score && entity.life().reachable(candidate.entrance(), false)) { best = candidate; score = candidateScore; }
        }
        if (best != null && choose(best)) { stop(); wait = 0; entity.showReaction("surprise", "house_arrival", 80); }
    }
    private boolean available() {
        return entity.getVariant().isHouse() && entity.isAlive() && !entity.isNoAi() && !entity.isPassenger()
                && !entity.isPanicking() && !entity.isCommandPlaying() && !entity.operations().active()
                && !entity.isInWaterOrBubble() && !entity.isOnFire() && entity.life().mealTicks() == 0
                && entity.life().drugSleep() == 0 && entity.life().sprayDelay() == 0
                && entity.life().wetness() <= 40 && !entity.family().awaitingLanding()
                && (entity.family().adult() || entity.tickCount >= 40);
    }
    private boolean mayTransfer() { return EventHooks.canEntityGrief((ServerLevel) entity.level(), entity); }
    private boolean daytime() { return !entity.level().isNight(); }
    private boolean wantsShelter() { return daytime() || entity.life().energy() <= 35 || !entity.family().adult(); }
    private boolean needsTending(HouseNestBlockEntity value) {
        return entity.family().adult() && value.built() && careWait == 0 && (value.eggs() > 0 && value.careTicks() < 120 || value.dirt() >= 3)
                && entity.life().nutrition() >= 40 && entity.life().energy() >= 25;
    }

    /** Called by the higher-priority life goal after executable thirst, toilet and thermal needs. */
    public boolean preferRoutine() {
        if (!available() || entity.family().active()) return false;
        if (entity.life().nutrition() < 30) {
            var value = nest();
            if (value == null || value.food() == 0 || !entity.life().canEat() || !mayTransfer()) {
                if (!carrying.isEmpty()) dropCarrying();
                return false;
            }
        }
        return canUse();
    }
    public boolean canUse() {
        if (!available()) return false;
        if (active) return true;
        if (wait > 0) return false;
        wait = 40;
        if (startledTicks > 0) return start(Phase.STARTLED);
        var value = nest();
        if (value == null || !value.usable(entity)) {
            if (lostTicks > 0 && entity.life().nutrition() >= 30 && entity.life().energy() >= 25) return start(Phase.SEARCH);
            // A carried item must remain real even when no reachable replacement home exists.
            if (nest == null && !carrying.isEmpty()) dropCarrying();
            return false;
        }
        if (value.disturbedTicks() > 0) return start(physicallyInside() ? Phase.LEAVE : Phase.SEARCH);
        if (!carrying.isEmpty() && mayTransfer()) return start(Phase.NONE);
        if (entity.life().canEat() && entity.life().nutrition() < 65 && value.food() > 0 && mayTransfer()) return start(Phase.NONE);
        if (entity.life().nutrition() < 30) return false;
        tending = needsTending(value);
        if (tending || wantsShelter()) return enterHome();
        if (mayTransfer()) {
            target = entity.level().getEntitiesOfClass(ItemEntity.class, entity.getBoundingBox().inflate(8), item ->
                    item.isAlive() && !item.hasPickUpDelay() && wantedResource(value, item.getItem()))
                    .stream().filter(item -> entity.life().reachable(item.blockPosition(), false))
                    .min(Comparator.comparingDouble(entity::distanceToSqr)).orElse(null);
        }
        if (target != null) return start(Phase.NONE);
        // An empty search is a short watch at the entrance, then ordinary exploration may resume.
        if (entity.distanceToSqr(Vec3.atBottomCenterOf(value.entrance())) < 16) return start(Phase.PEEK);
        return false;
    }
    private boolean wantedResource(HouseNestBlockEntity value, ItemStack item) {
        return item.is(ModItems.FOOD_SCRAP.get()) && value.food() < HouseNestBlockEntity.STOCK_CAP
                || HouseNestBlockEntity.isMaterial(item) && !value.built();
    }
    private boolean enterHome() {
        restPosition = null; sleepTicks = 0; rested = false;
        return start(physicallyInside() ? tending ? Phase.TEND : Phase.REST : Phase.APPROACH);
    }
    private boolean start(Phase next) { active = true; phase = next; ticks = 0; return true; }
    private void phase(Phase next) { phase = next; ticks = 0; if (next == Phase.TEND) careContact = false; }
    public boolean canContinue() {
        if (!active || !available()) return false;
        if (phase == Phase.STARTLED || phase == Phase.SEARCH) return ticks < 160;
        if (homeStand() == null) return false;
        if (phase == Phase.REST) return true;
        int limit = phase == Phase.ENTER || phase == Phase.LEAVE
                ? Math.max(300, (int) Math.ceil(1200 / Math.max(.25, entity.getScale() * entity.growthScale()))) : 400;
        return ticks < limit;
    }
    public void tickGoal() {
        if (!canContinue()) { stop(); return; }
        ticks++; var value = nest();
        if (phase == Phase.STARTLED) {
            entity.getNavigation().stop();
            if (startledTicks == 0) phase(value != null && physicallyInside() ? Phase.LEAVE : Phase.SEARCH);
            return;
        }
        if (phase == Phase.SEARCH) { seekRefuge(); return; }
        if (interaction > 0) { tickInteraction(value); return; }
        if (phase != Phase.NONE) { tickHome(value); return; }
        if (!carrying.isEmpty()) {
            Vec3 entrance = Vec3.atBottomCenterOf(value.entrance());
            if (inReach(entrance)) beginInteraction(2, entrance);
            else if (entity.distanceToSqr(entrance) < 2.25) approach(entrance);
            else if (ticks % 10 == 1 && !entity.life().navigate(value.entrance(), .8, false)) stop();
        } else if (entity.life().canEat() && entity.life().nutrition() < 65 && value.food() > 0 && mayTransfer()) {
            Vec3 entrance = Vec3.atBottomCenterOf(value.entrance());
            if (entity.distanceToSqr(entrance) < .36) {
                ItemStack food = value.takeFood();
                if (!food.isEmpty() && !entity.life().beginMeal(food, null, false)) value.deposit(food);
                stop();
            } else if (ticks % 10 == 1 && !entity.life().navigate(value.entrance(), 1, false)) stop();
        } else if (target != null && target.isAlive()) {
            if (!wantedResource(value, target.getItem()) || !mayTransfer()) { stop(); return; }
            if (inReach(target.position()) && entity.hasLineOfSight(target)) {
                if (!target.hasPickUpDelay()) beginInteraction(1, target.position()); else stop();
            } else if (entity.distanceToSqr(target) < 2.25 && entity.hasLineOfSight(target) && entity.life().dryStand(target.blockPosition())) approach(target.position());
            else if (ticks % 10 == 1 && !entity.life().navigate(target.blockPosition(), 1.1, false)) stop();
        } else stop();
    }
    private Vec3 restingPlace(HouseNestBlockEntity value) {
        if (restPosition == null) {
            var residents = entity.level().getEntitiesOfClass(YukkuriEntity.class, new AABB(value.getBlockPos()).inflate(2),
                    other -> other != entity && other.isAlive() && other.house().nest() == value);
            // Reserve the back bed first so a sleeper at the entrance cannot block the next arrival.
            // The .38 gap also leaves separate body boxes for two maximum-size (.324 wide) adults.
            Vec3 back = Vec3.atBottomCenterOf(value.getBlockPos()).add(-.11, .025, .19);
            boolean backReserved = residents.stream().anyMatch(other -> other.house().restPosition != null
                    && other.house().restPosition.distanceToSqr(back) < .01);
            restPosition = backReserved ? Vec3.atBottomCenterOf(value.getBlockPos()).add(-.11, .025, -.19) : back;
        }
        return restPosition;
    }
    private void tickHome(HouseNestBlockEntity value) {
        Vec3 outside = Vec3.atBottomCenterOf(value.entrance());
        switch (phase) {
            case APPROACH -> {
                double distance = entity.distanceToSqr(outside);
                // Block navigation accepts the entrance cell before a body is aligned with its narrow door.
                // Finish on the open ground in front of the door before moving diagonally to a bed.
                if (distance < .0025 && entity.onGround()) phase(Phase.ENTER);
                else if (distance < 2.25) direct(outside, .65);
                else if (ticks % 10 == 1 && !entity.life().navigate(value.entrance(), .8, false)) stop();
            }
            case ENTER -> {
                Vec3 inside = restingPlace(value);
                if (entity.distanceToSqr(inside) < .0025 && entity.onGround()) {
                    entity.getNavigation().stop(); phase(tending ? Phase.TEND : Phase.REST);
                } else direct(inside, .65);
            }
            case REST -> {
                entity.getNavigation().stop();
                if (!physicallyInside() || value.disturbedTicks() > 0) { finishRest(); phase(Phase.LEAVE); return; }
                if (needsTending(value)) { finishRest(); tending = true; phase(Phase.TEND); return; }
                // Executable thirst, toilet and thermal plans preempt this goal in YukkuriLife.
                // An unavailable resource must not cause an endless leave/re-enter loop.
                if (!daytime() && entity.family().adult() && entity.life().energy() >= 70 || entity.life().nutrition() < 40) {
                    finishRest(); phase(Phase.LEAVE); return;
                }
                sleepTicks++;
                if (sleepTicks == 20) entity.speak("sleep");
                if (sleepTicks >= 20) entity.life().restInHouse();
                if (sleepTicks >= 220) rested = true;
            }
            case TEND, LISTEN -> {
                if (!physicallyInside() || value.eggs() == 0 && value.dirt() == 0 && !careContact || value.disturbedTicks() > 0) {
                    careWait = 100; tending = false; phase(Phase.LEAVE); return;
                }
                entity.getNavigation().stop(); contactTarget = value.eggPosition(0); faceContact();
                if (phase == Phase.TEND && ticks == 26) { careContact = value.tend(entity); careWait = careContact ? 120 : 100; }
                if (phase == Phase.TEND && ticks >= 40) {
                    if (careContact && value.eggs() > 0) { phase(Phase.LISTEN); if (value.progress() >= .75f) entity.showReaction("happy", "house_listen", 80); }
                    else { tending = false; phase(wantsShelter() ? Phase.REST : Phase.LEAVE); }
                } else if (phase == Phase.LISTEN && ticks >= 80) { tending = false; phase(wantsShelter() ? Phase.REST : Phase.LEAVE); }
            }
            case LEAVE -> {
                finishRest();
                Vec3 peek = Vec3.atBottomCenterOf(value.getBlockPos()).add(0, 0, -.55);
                if (entity.distanceToSqr(peek) < .025) { phase(Phase.PEEK); entity.showReaction("neutral", "house_peek", 60); }
                else direct(peek, .65);
            }
            case PEEK -> {
                if (entity.distanceToSqr(outside) > .36) { if (ticks % 10 == 1 && !entity.life().navigate(value.entrance(), .8, false)) stop(); return; }
                entity.getNavigation().stop();
                double side = Math.sin(ticks * .06) * 1.4;
                entity.getLookControl().setLookAt(outside.x + side, outside.y + .1, outside.z - 2, 20, 20);
                if (ticks >= 60) { stop(); wait = 160; }
            }
            default -> { }
        }
    }
    private void finishRest() {
        if (rested) { rested = false; entity.family().woke(); }
        sleepTicks = 0;
    }
    private void seekRefuge() {
        if (refuge == null) {
            double best = Double.MAX_VALUE;
            for (var pos : BlockPos.betweenClosed(entity.blockPosition().offset(-3, 0, -3), entity.blockPosition().offset(3, 0, 3))) {
                double distance = entity.distanceToSqr(Vec3.atBottomCenterOf(pos));
                if (distance < .36 || distance >= best || !entity.life().dryStand(pos) || !entity.life().covered(pos)
                        || !entity.life().reachable(pos, false)) continue;
                var value = nest();
                if (value != null && pos.distSqr(value.getBlockPos()) < 2) continue;
                refuge = pos.immutable(); best = distance;
            }
        }
        if (refuge != null && entity.distanceToSqr(Vec3.atBottomCenterOf(refuge)) > .16) {
            if (ticks % 10 == 1 && !entity.life().navigate(refuge, .85, false)) stop();
        } else {
            entity.getNavigation().stop();
            if (ticks % 30 == 1) entity.getLookControl().setLookAt(entity.getX() + Math.sin(ticks * .1), entity.getEyeY(), entity.getZ() - 1, 20, 20);
        }
        if (ticks >= 100) { stop(); wait = 100; }
    }
    /** Cleaning surprises the real residents. Demolition forgets only the home, never the family. */
    public void disturb(boolean destroyed) {
        if (entity.level().isClientSide || !entity.getVariant().isHouse()) return;
        stop(); entity.life().interrupt(); entity.family().stop(); entity.dailyLife().stop(); entity.social().stop();
        if (destroyed) { nest = null; nestIdentity = null; lostTicks = 1200; }
        refuge = null; startledTicks = 24; wait = 0;
        entity.showReaction("surprise", destroyed ? "house_search_home" : "house_startled", 100);
    }
    private void direct(Vec3 point, double speed) { entity.getNavigation().stop(); entity.getMoveControl().setWantedPosition(point.x, point.y, point.z, speed); }
    private void approach(Vec3 point) {
        var delta = point.subtract(entity.position()).multiply(1, 0, 1);
        if (delta.lengthSqr() < .0001) delta = new Vec3(0, 0, 1);
        direct(point.subtract(delta.normalize().scale(reach())), .65);
    }
    private double reach() { return .28 * entity.getScale() * entity.growthScale(); }
    private boolean inReach(Vec3 point) {
        var delta = point.subtract(entity.position());
        return Math.abs(delta.y) < .12 && Math.abs(delta.horizontalDistance() - reach()) < .045;
    }
    private void beginInteraction(int kind, Vec3 point) { interaction = kind; interactionTicks = 0; contactTarget = point; entity.getNavigation().stop(); faceContact(); }
    private void faceContact() {
        if (contactTarget == null) return;
        var delta = contactTarget.subtract(entity.position()); float yaw = (float) (Math.atan2(-delta.x, delta.z) * 180 / Math.PI);
        entity.setYRot(yaw); entity.yBodyRot = yaw; entity.yHeadRot = yaw;
        entity.getLookControl().setLookAt(contactTarget.x, entity.getEyeY(), contactTarget.z, 180, 180);
    }
    private void tickInteraction(HouseNestBlockEntity value) {
        entity.getNavigation().stop(); entity.setDeltaMovement(0, entity.getDeltaMovement().y, 0); faceContact(); interactionTicks++;
        if (interactionTicks == 13) {
            if (!mayTransfer()) { stop(); return; }
            if (interaction == 1) {
                if (target == null || !target.isAlive() || target.hasPickUpDelay() || !inReach(target.position()) || !entity.hasLineOfSight(target)
                        || !wantedResource(value, target.getItem())) { stop(); return; }
                carrying = target.getItem().split(1); if (target.getItem().isEmpty()) target.discard(); target = null;
                entity.showReaction("happy", "house_pickup", 60);
            } else if (!carrying.isEmpty()) {
                if (!inReach(Vec3.atBottomCenterOf(value.entrance()))) { stop(); return; }
                if (value.deposit(carrying)) { carrying = ItemStack.EMPTY; entity.showReaction("happy", "house_deposit", 60); }
                else { dropCarrying(); stop(); return; }
            }
        }
        if (interactionTicks >= 26) {
            if (interaction == 2) { stop(); return; }
            interaction = interactionTicks = 0; contactTarget = null; ticks = 0;
        }
    }
    public void stop() {
        finishRest(); active = false; target = null; contactTarget = restPosition = null;
        interaction = interactionTicks = ticks = 0; phase = Phase.NONE; tending = careContact = false; wait = Math.max(wait, 40);
    }
    public void dropCarrying() { if (!carrying.isEmpty()) { entity.spawnAtLocation(carrying.copy()); carrying = ItemStack.EMPTY; } }
    public CompoundTag save() {
        var tag = new CompoundTag(); tag.putInt("Version", 2); FamilyData.putPosition(tag, "Nest", nest);
        if (nestIdentity != null) tag.putUUID("NestIdentity", nestIdentity);
        tag.putInt("CareWait", careWait); tag.putInt("LostTicks", lostTicks); tag.putInt("StartledTicks", startledTicks);
        if (!carrying.isEmpty()) tag.put("Carrying", carrying.save(entity.registryAccess())); return tag;
    }
    public void load(CompoundTag tag) {
        stop(); nest = FamilyData.position(tag, "Nest"); nestIdentity = tag.hasUUID("NestIdentity") ? tag.getUUID("NestIdentity") : null;
        carrying = tag.contains("Carrying") ? ItemStack.parseOptional(entity.registryAccess(), tag.getCompound("Carrying")) : ItemStack.EMPTY;
        careWait = Math.clamp(tag.getInt("CareWait"), 0, 120); lostTicks = Math.clamp(tag.getInt("LostTicks"), 0, 1200);
        startledTicks = Math.clamp(tag.getInt("StartledTicks"), 0, 24); refuge = null;
    }
}
