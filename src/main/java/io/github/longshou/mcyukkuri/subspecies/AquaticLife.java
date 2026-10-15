package io.github.longshou.mcyukkuri.subspecies;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.EventHooks;

/** Server-owned shore, provisions, family boarding and sailing. Water tolerance is deliberately unchanged. */
public final class AquaticLife extends Goal {
    private final YukkuriEntity entity;
    private BlockPos bank, water, rememberedBank, paddleStand;
    private int cooldown, elapsed, launchTicks, gatherTicks;
    private boolean stick, nightVoyage;
    private String activity = "idle";
    private long nextPlan;

    public AquaticLife(YukkuriEntity entity) {
        this.entity = entity; setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK, Flag.JUMP));
    }
    private ServerLevel level() { return (ServerLevel) entity.level(); }
    public String activity() { return activity; }
    public boolean active() { return !activity.equals("idle"); }
    public boolean hasStick() { return stick; }
    public void landed() { cooldown = 1200; nightVoyage = false; }
    public void clock() { if (cooldown > 0) cooldown--; }
    public boolean giveStick(ItemStack stack, boolean creative) {
        if (!HatVessel.aquatic(entity) || stick || !stack.is(Items.STICK)) return false;
        stick = true; if (!creative) stack.shrink(1); return true;
    }
    public void equip(HatBoatEntity boat) {
        if (stick && boat.cargo().givePaddle(new ItemStack(Items.STICK), false)) stick = false;
        rememberedBank = entity.blockPosition(); nightVoyage = level().isNight();
    }
    public void dropStick() {
        if (stick) { stick = false; entity.spawnAtLocation(new ItemStack(Items.STICK)); }
    }
    private boolean available() {
        return entity.isAlive() && !entity.isNoAi() && !entity.isCommandPlaying() && !entity.operations().active()
                && !entity.isInWaterOrBubble() && !entity.isOnFire() && !entity.family().flying();
    }
    @Override public boolean canUse() {
        if (!available()) return false;
        if (entity.getVehicle() instanceof HatBoatEntity boat) return boat.protects(entity);
        if (!HatVessel.aquatic(entity) || entity.isPassenger() || entity.isPanicking() || !entity.onGround()
                || entity.family().stage() == io.github.longshou.mcyukkuri.family.FamilyLife.Stage.BABY
                || !entity.life().hasAccessory() || entity.subspecies().boatId() != null || cooldown > 0
                || entity.family().active() || entity.family().brood() != null || entity.life().mealTicks() > 0
                || entity.life().hasUrgentNeed() || entity.life().isSleeping()
                || io.github.longshou.mcyukkuri.event.WildEvents.reserved(entity)) return false;
        long now = level().getGameTime(); if (now < nextPlan) return false;
        nextPlan = now+100;
        // Dusk launches are purposeful. Daytime excursions leave time for terrestrial meals and social life.
        if (!level().isNight() && entity.getRandom().nextInt(4) != 0) return false;
        bank = null; water = null;
        double nearest = Double.MAX_VALUE;
        for (var p : BlockPos.betweenClosed(entity.blockPosition().offset(-8,-2,-8), entity.blockPosition().offset(8,2,8))) {
            if (!HatBoatRoute.calmSurface(level(), p)) continue;
            for (var side : net.minecraft.core.Direction.Plane.HORIZONTAL) {
                var stand = p.relative(side).above(); double distance = stand.distSqr(entity.blockPosition());
                if (distance >= nearest || !level().hasChunkAt(stand) || !entity.life().dryStand(stand)
                        || !entity.life().reachable(stand, false)) continue;
                nearest = distance; bank = stand.immutable(); water = p.immutable();
            }
        }
        elapsed = launchTicks = 0; return bank != null;
    }
    @Override public boolean canContinueToUse() {
        if (!available()) return false;
        if (entity.getVehicle() instanceof HatBoatEntity boat) return boat.protects(entity);
        return bank != null && water != null && elapsed < 400 && !entity.isPanicking()
                && entity.life().hasAccessory() && !entity.life().hasUrgentNeed() && !entity.family().active()
                && HatBoatRoute.calmSurface(level(), water) && entity.life().dryStand(bank);
    }
    @Override public boolean requiresUpdateEveryTick() { return true; }
    @Override public void start() { entity.getNavigation().stop(); }
    @Override public void tick() {
        elapsed++;
        if (entity.getVehicle() instanceof HatBoatEntity boat) { aboard(boat); return; }
        if (bank == null || water == null) return;
        if (!stick) { activity = "boat_find_paddle"; seekStick(); return; }
        activity = "boat_seek_bank";
        if (entity.distanceToSqr(Vec3.atBottomCenterOf(bank)) > .4) {
            if (elapsed%20 == 1 && !entity.life().navigate(bank, .9, false)) stop();
            return;
        }
        entity.getNavigation().stop();
        var delta = HatBoatRoute.surface(level(), water).subtract(entity.position());
        float yaw = (float) (Math.toDegrees(Math.atan2(delta.z, delta.x))-90);
        entity.setYRot(yaw); entity.yBodyRot = yaw; entity.setYHeadRot(yaw);
        activity = "boat_launch";
        if (++launchTicks >= 36) {
            var boat = HatBoatEntity.launchFromBank(entity, water);
            bank = water = null; launchTicks = 0;
            if (boat == null) { cooldown = 200; activity = "idle"; }
        }
    }
    private void seekStick() {
        if (findStick()) { paddleStand=null; entity.getNavigation().stop(); return; }
        if (!EventHooks.canEntityGrief(level(), entity)) return;
        if (elapsed%40 == 1) {
            paddleStand = level().getEntitiesOfClass(ItemEntity.class, entity.getBoundingBox().inflate(8,3,8),
                    i -> i.isAlive() && !i.hasPickUpDelay() && i.getItem().is(Items.STICK)).stream()
                    .sorted(Comparator.comparingDouble(entity::distanceToSqr))
                    .map(ItemEntity::blockPosition).filter(p -> entity.life().dryStand(p) && entity.life().reachable(p,false)).findFirst().orElse(null);
            if (paddleStand==null) {
                for (var p : BlockPos.betweenClosed(entity.blockPosition().offset(-6,0,-6),entity.blockPosition().offset(6,2,6))) {
                    if (!level().hasChunkAt(p)) continue; var block=level().getBlockState(p);
                    if (!block.is(BlockTags.LEAVES) && !block.is(Blocks.DEAD_BUSH)) continue;
                    for (var side : net.minecraft.core.Direction.Plane.HORIZONTAL) {
                        var stand=p.relative(side);
                        for (int down=0;down<=2;down++) if (entity.life().dryStand(stand.below(down)) && entity.life().reachable(stand.below(down),false)) {
                            paddleStand=stand.below(down); break;
                        }
                        if (paddleStand!=null) break;
                    }
                    if (paddleStand!=null) break;
                }
            }
        }
        if (paddleStand!=null && elapsed%20==1) entity.life().navigate(paddleStand,.9,false);
    }
    private boolean findStick() {
        if (!EventHooks.canEntityGrief(level(), entity)) return false;
        for (var item : level().getEntitiesOfClass(ItemEntity.class, entity.getBoundingBox().inflate(1.5),
                i -> i.isAlive() && !i.hasPickUpDelay() && i.getItem().is(Items.STICK))) {
            if (entity.hasLineOfSight(item) && giveStick(item.getItem(), false)) { if (item.getItem().isEmpty()) item.discard(); return true; }
        }
        // A real nearby twig source is consumed. This finite harvesting rule is a project balance choice.
        for (var p : BlockPos.betweenClosed(entity.blockPosition().offset(-2,0,-2), entity.blockPosition().offset(2,2,2))) {
            if (!level().hasChunkAt(p) || entity.distanceToSqr(Vec3.atCenterOf(p)) > 6.25) continue;
            var state = level().getBlockState(p);
            if (!state.is(BlockTags.LEAVES) && !state.is(Blocks.DEAD_BUSH)) continue;
            var hit = level().clip(new net.minecraft.world.level.ClipContext(entity.getEyePosition(), Vec3.atCenterOf(p),
                    net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.NONE, entity));
            if (hit.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK && !hit.getBlockPos().equals(p)) continue;
            if (level().setBlock(p, Blocks.AIR.defaultBlockState(), 3)) { stick = true; return true; }
        }
        return false;
    }
    private void aboard(HatBoatEntity boat) {
        entity.getNavigation().stop();
        boolean owner = entity.getUUID().equals(boat.ownerYukkuri());
        activity = "boat_drift";
        if (!owner) {
            if (entity.life().needsFood()) boat.cargo().feed(entity);
            entity.life().restInHat(boat);
            activity = entity.life().isSleeping() ? "boat_sleep" : "hat_cradle";
            return;
        }
        if (!boat.isAfloat()) return;
        if (level().isNight()) nightVoyage = true;
        if (entity.isPanicking() || boat.flooding() > 0) {
            boat.stopPaddling(); activity = "boat_steady";
            if (elapsed%100 == 1) entity.speak("afraid"); return;
        }
        if (boat.towed() || entity.family().laboring()) { boat.stopPaddling(); return; }
        if (gatherTicks > 0) { gatherTicks--; boat.stopPaddling(); activity = "boat_gather"; return; }
        if (elapsed%20 == 1) {
            collect(boat);
            // The hungriest passenger gets the next real meal, including the parent.
            boat.getPassengers().stream().filter(p -> p instanceof YukkuriEntity).map(p -> (YukkuriEntity)p)
                    .filter(y -> y.life().canEat()).sorted(Comparator.comparingDouble(y -> y.life().nutrition()))
                    .forEach(y -> boat.cargo().feed(y));
        }
        if (entity.life().mealTicks() > 0) { boat.stopPaddling(); activity = "boat_eat"; return; }
        if (entity.life().dryness() >= 40) {
            boat.stopPaddling(); activity = "boat_drink";
            if (elapsed%40 == 0) entity.life().drink(30);
            return;
        }
        // Keep the boat by its launch bank until nearby offspring have actually hopped aboard.
        if (boat.hasIncoming() || boat.tickCount < 160 && !youngOnBank(boat).isEmpty()) {
            boat.holdPosition(); activity = "boat_wait_young"; boardYoung(boat); return;
        }
        boolean hungryCrew = boat.getPassengers().stream().anyMatch(p -> p instanceof YukkuriEntity y && y.life().nutrition() < 30);
        boolean returnToBank = entity.life().bladder() >= 70 || level().isRainingAt(boat.blockPosition())
                || hungryCrew && boat.cargo().count() == 0 || nightVoyage && !level().isNight()
                || !HatVessel.aquatic(entity) || !boat.cargo().paddle() || !level().isNight() && elapsed > 2400 || entity.family().wantsBoatLanding()
                || boat.getPassengers().stream().anyMatch(p -> p instanceof YukkuriEntity y && y!=entity && y.family().adult());
        if (returnToBank) {
            entity.life().wakeInHat(); activity = "boat_land";
            var shore = boat.nearestBank();
            if (shore != null && boat.distanceToSqr(Vec3.atBottomCenterOf(shore)) <= 3.25 && boat.rescue(shore, null)) return;
            if (elapsed%40 == 1) boat.paddleTo(shore == null ? rememberedBank == null ? null : Vec3.atBottomCenterOf(rememberedBank) : Vec3.atBottomCenterOf(shore));
            if (!boat.paddling()) { activity = "boat_call"; if (elapsed%120 == 1) entity.speak("wait_family"); }
            return;
        }
        if (level().isNight() || entity.life().energy() <= 20 || entity.life().isSleeping()) {
            boat.stopPaddling(); entity.life().restInHat(boat); activity = "boat_sleep"; return;
        }
        if (elapsed%40 == 1 && boat.cargo().count() < HatBoatCargo.CAPACITY && EventHooks.canEntityGrief(level(), entity)) {
            var target = level().getEntitiesOfClass(ItemEntity.class, boat.getBoundingBox().inflate(10,2,10),
                    item -> item.isAlive() && !item.hasPickUpDelay() && entity.life().acceptsFood(item.getItem())
                            && item.isInWater() && boat.cargo().canStore(item.getItem())).stream()
                    .sorted(Comparator.comparingDouble(boat::distanceToSqr)).limit(5)
                    .filter(item -> !HatBoatRoute.find(boat, item.position()).isEmpty()).findFirst().orElse(null);
            if (target != null) boat.paddleTo(target.position());
        }
        if (!boat.paddling() && elapsed%160 == 1) {
            var around = boat.position().add(entity.getRandom().nextInt(11)-5, 0, entity.getRandom().nextInt(11)-5);
            boat.paddleTo(around);
        }
        activity = boat.paddling() ? "boat_paddle" : "boat_drift";
    }
    private List<YukkuriEntity> youngOnBank(HatBoatEntity boat) {
        return level().getEntitiesOfClass(YukkuriEntity.class, boat.getBoundingBox().inflate(4,2,4),
                y -> boat.young(y) && y.isAlive() && !y.isPassenger() && !y.family().flying()
                        && !y.isNoAi() && !y.isCommandPlaying() && !y.operations().active() && !y.isInWaterOrBubble());
    }
    private void boardYoung(HatBoatEntity boat) {
        if (elapsed%10 != 0) return;
        for (var young : youngOnBank(boat)) {
            if (!boat.hasRoomFor(young)) break;
            if (young.distanceToSqr(boat) <= 6.25 && young.hasLineOfSight(boat) && young.onGround()) {
                young.family().launchInto(boat, HatVessel.HOP_TMIN, null); break;
            }
        }
    }
    private void collect(HatBoatEntity boat) {
        if (!EventHooks.canEntityGrief(level(), entity)) return;
        for (var item : level().getEntitiesOfClass(ItemEntity.class, boat.getBoundingBox().inflate(.65,.8,.65),
                i -> i.isAlive() && !i.hasPickUpDelay())) {
            if (!entity.hasLineOfSight(item)) continue;
            boolean changed = !boat.cargo().paddle() && boat.cargo().givePaddle(item.getItem(), false);
            if (entity.life().acceptsFood(item.getItem())) changed |= boat.cargo().store(item.getItem(), 2, false) > 0;
            if (changed) { if (item.getItem().isEmpty()) item.discard(); gatherTicks = 32; entity.speak("take_food"); }
        }
    }
    @Override public void stop() {
        bank = water = paddleStand = null; launchTicks = 0; activity = "idle"; entity.getNavigation().stop();
        if (entity.getVehicle() instanceof HatBoatEntity boat && entity.getUUID().equals(boat.ownerYukkuri())) boat.stopPaddling();
    }
    public CompoundTag save() {
        var tag = new CompoundTag(); tag.putBoolean("Stick", stick); tag.putBoolean("NightVoyage", nightVoyage); tag.putInt("Cooldown", cooldown);
        if (rememberedBank != null) { tag.putLong("Bank", rememberedBank.asLong()); tag.putString("Dimension", entity.level().dimension().location().toString()); }
        return tag;
    }
    public void load(CompoundTag tag) {
        stick = tag.getBoolean("Stick"); nightVoyage = tag.getBoolean("NightVoyage"); cooldown = Math.clamp(tag.getInt("Cooldown"), 0, 1200);
        rememberedBank = tag.contains("Bank") && tag.getString("Dimension").equals(entity.level().dimension().location().toString()) ? BlockPos.of(tag.getLong("Bank")) : null;
    }
}
