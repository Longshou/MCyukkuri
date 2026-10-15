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
    private final HatFamilyCare care;
    private BlockPos bank, water, rememberedBank, paddleStand;
    private BlockPos restWater;
    private int cooldown, elapsed, launchTicks, gatherTicks, provisionTicks, familyWait, hatCareTicks, restSearchTicks;
    private boolean stick, nightVoyage, hatCareDue, familyDelayed;
    private ItemStack provisions = ItemStack.EMPTY;
    private ItemEntity provisionSource;
    private final java.util.LinkedHashSet<java.util.UUID> expectedYoung = new java.util.LinkedHashSet<>();
    private String activity = "idle";
    private long nextPlan;

    public AquaticLife(YukkuriEntity entity) {
        this.entity = entity; care = new HatFamilyCare(entity); setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK, Flag.JUMP));
    }
    private ServerLevel level() { return (ServerLevel) entity.level(); }
    public String activity() { return activity; }
    public boolean active() { return !activity.equals("idle"); }
    public boolean hasStick() { return stick; }
    public HatFamilyCare care() { return care; }
    public int provisions() { return provisions.getCount(); }
    public boolean resting() { return "boat_sleep".equals(activity) || "boat_lullaby".equals(activity); }
    public boolean awaiting(YukkuriEntity young) { return !familyDelayed && expectedYoung.contains(young.getUUID()); }
    public BlockPos boardingBank() { return bank != null ? bank : rememberedBank; }
    public void landed() { cooldown = 1200; nightVoyage = false; hatCareDue = true; expectedYoung.clear(); restWater = null; care.cancel(); }
    public void clock() {
        if (cooldown > 0) cooldown--; care.clock();
        // A cancelled trip must not leave her starving while she still carries real provisions.
        if (!provisions.isEmpty() && available() && !entity.isPassenger() && !entity.life().isSleeping()
                && entity.life().nutrition() < 30 && entity.life().beginMeal(provisions.copyWithCount(1),null,true)) provisions.shrink(1);
    }
    public boolean giveStick(ItemStack stack, boolean creative) {
        if (!HatVessel.aquatic(entity) || stick || !stack.is(Items.STICK)) return false;
        stick = true; if (!creative) stack.shrink(1); return true;
    }
    public void equip(HatBoatEntity boat) {
        if (stick && boat.cargo().givePaddle(new ItemStack(Items.STICK), false)) stick = false;
        if (!provisions.isEmpty()) boat.cargo().store(provisions, provisions.getCount(), false);
        rememberedBank = entity.blockPosition(); nightVoyage = level().isNight();
        expectedYoung.clear(); nearbyYoung().stream().limit(HatBoatEntity.MAX_YOUNG).forEach(y -> expectedYoung.add(y.getUUID()));
        familyWait = restSearchTicks = 0; familyDelayed = false; restWater = null;
    }
    public void dropStick() {
        care.cancel();
        if (stick) { stick = false; entity.spawnAtLocation(new ItemStack(Items.STICK)); }
        if (!provisions.isEmpty()) { var food = provisions; provisions = ItemStack.EMPTY; entity.spawnAtLocation(food); }
    }
    private List<YukkuriEntity> nearbyYoung() {
        return level().getEntitiesOfClass(YukkuriEntity.class, entity.getBoundingBox().inflate(10,3,10),
                y -> y.isAlive() && !y.family().adult() && !y.isPassenger()
                        && (entity.getUUID().equals(y.family().mother()) || entity.getUUID().equals(y.family().father())));
    }
    private boolean canTendHat() {
        return hatCareDue && HatVessel.aquatic(entity) && entity.onGround() && !entity.isPassenger() && !entity.isPanicking()
                && entity.life().hasAccessory() && entity.life().mealTicks() == 0 && !entity.life().isSleeping()
                && entity.life().nutrition() >= 30 && !entity.family().active() && !entity.dailyLife().active();
    }
    private boolean available() {
        return entity.isAlive() && !entity.isNoAi() && !entity.isCommandPlaying() && !entity.operations().active()
                && !entity.isInWaterOrBubble() && !entity.isOnFire() && !entity.family().flying();
    }
    @Override public boolean canUse() {
        if (!available()) return false;
        if (entity.getVehicle() instanceof HatBoatEntity boat) return boat.protects(entity);
        if (canTendHat()) { hatCareTicks = 0; return true; }
        if (!HatVessel.aquatic(entity) || entity.isPassenger() || entity.isPanicking() || !entity.onGround()
                || entity.family().stage() == io.github.longshou.mcyukkuri.family.FamilyLife.Stage.BABY
                || !entity.life().hasAccessory() || entity.subspecies().boatId() != null || cooldown > 0
                || entity.family().active() || entity.family().brood() != null || entity.life().mealTicks() > 0
                || entity.life().hasUrgentNeed() || entity.life().isSleeping()
                || io.github.longshou.mcyukkuri.event.WildEvents.reserved(entity) || nearbyYoung().size() > HatBoatEntity.MAX_YOUNG) return false;
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
        elapsed = launchTicks = provisionTicks = familyWait = 0; return bank != null;
    }
    @Override public boolean canContinueToUse() {
        if (!available()) return false;
        if (entity.getVehicle() instanceof HatBoatEntity boat) return boat.protects(entity);
        if (canTendHat()) return hatCareTicks < 48;
        return bank != null && water != null && elapsed < 720 && !entity.isPanicking()
                && entity.life().mealTicks() == 0 && !entity.life().isSleeping()
                && entity.life().hasAccessory() && !entity.life().hasUrgentNeed() && !entity.family().active()
                && HatBoatRoute.calmSurface(level(), water) && entity.life().dryStand(bank);
    }
    @Override public boolean requiresUpdateEveryTick() { return true; }
    @Override public void start() { entity.getNavigation().stop(); }
    @Override public void tick() {
        elapsed++;
        if (entity.getVehicle() instanceof HatBoatEntity boat) { aboard(boat); return; }
        if (canTendHat()) {
            activity = "aquatic_hat_care"; entity.getNavigation().stop();
            if (++hatCareTicks >= 48) { entity.life().applyEnvironment(-8,0,0); hatCareDue = false; }
            return;
        }
        if (bank == null || water == null) return;
        if (!stick) { activity = "boat_find_paddle"; seekStick(); return; }
        if (provisionTicks < 120 && provisions.getCount() < Math.min(4, 1+nearbyYoung().size()) && prepareFood()) {
            provisionTicks++; launchTicks = 0; activity = "boat_provision"; return;
        }
        activity = "boat_seek_bank";
        if (entity.distanceToSqr(Vec3.atBottomCenterOf(bank)) > .4) {
            launchTicks = 0;
            if (elapsed%20 == 1 && !entity.life().navigate(bank, .9, false)) stop();
            return;
        }
        entity.getNavigation().stop();
        var young = nearbyYoung();
        if (young.size() > HatBoatEntity.MAX_YOUNG || young.stream().anyMatch(y -> y.distanceToSqr(entity) > 6.25
                || y.isNoAi() || y.isCommandPlaying() || y.operations().active() || y.isPanicking()
                || y.isInWaterOrBubble() || y.life().mealTicks() > 0 || y.life().nutrition() < 30)) {
            activity = "boat_wait_young";
            launchTicks = 0;
            if (elapsed%100 == 1) entity.speak("wait_family");
            if (++familyWait > 200) { cooldown = 400; stop(); }
            return;
        }
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
    /** Carry a small real food reserve to the bank; no provisions appear merely because a voyage starts. */
    private boolean prepareFood() {
        if (!EventHooks.canEntityGrief(level(), entity)) return false;
        if (provisionSource != null && (!provisionSource.isAlive() || provisionSource.hasPickUpDelay() || provisionSource.isInWater()
                || entity.distanceToSqr(provisionSource) > 64 || !entity.hasLineOfSight(provisionSource)
                || !entity.life().acceptsFood(provisionSource.getItem())
                || !provisions.isEmpty() && !ItemStack.isSameItemSameComponents(provisions,provisionSource.getItem()))) provisionSource = null;
        // Keep pursuing a chosen item, but bound new entity scans and path searches to once a second.
        if (provisionSource == null && elapsed%20 != 1) return false;
        if (provisionSource == null) provisionSource = level().getEntitiesOfClass(ItemEntity.class, entity.getBoundingBox().inflate(6,2,6),
                i -> i.isAlive() && !i.hasPickUpDelay() && !i.isInWater() && entity.life().acceptsFood(i.getItem())
                        && (provisions.isEmpty() || ItemStack.isSameItemSameComponents(provisions,i.getItem()))).stream()
                .filter(entity::hasLineOfSight).sorted(Comparator.comparingDouble(entity::distanceToSqr))
                .filter(i -> entity.life().dryStand(i.blockPosition()) && entity.life().reachable(i.blockPosition(),false)).findFirst().orElse(null);
        var item = provisionSource;
        if (item != null) {
            if (entity.distanceToSqr(item) <= 2.25) {
                int n = Math.min(4-provisions.getCount(),item.getItem().getCount());
                if (provisions.isEmpty()) provisions = item.getItem().split(n); else { provisions.grow(n); item.getItem().shrink(n); }
                if (item.getItem().isEmpty()) item.discard(); provisionSource = null;
                entity.setPersistenceRequired(); entity.getNavigation().stop(); entity.speak("take_food");
            } else if (elapsed%20 == 1) entity.life().navigate(item.blockPosition(),.85,false);
            return true;
        }
        // Harvest only a reachable ripe bush, leaving it at its harvested age. The berries are actual finite food.
        if (!provisions.isEmpty() && !provisions.is(Items.SWEET_BERRIES)) return false;
        for (var p : BlockPos.betweenClosed(entity.blockPosition().offset(-2,0,-2),entity.blockPosition().offset(2,1,2))) {
            if (!level().hasChunkAt(p) || entity.distanceToSqr(Vec3.atCenterOf(p)) > 4) continue;
            var state=level().getBlockState(p);
            if (!state.is(Blocks.SWEET_BERRY_BUSH) || state.getValue(net.minecraft.world.level.block.SweetBerryBushBlock.AGE) < 2) continue;
            var hit=level().clip(new net.minecraft.world.level.ClipContext(entity.getEyePosition(),Vec3.atCenterOf(p),
                    net.minecraft.world.level.ClipContext.Block.COLLIDER,net.minecraft.world.level.ClipContext.Fluid.NONE,entity));
            if (hit.getType()==net.minecraft.world.phys.HitResult.Type.BLOCK && !hit.getBlockPos().equals(p)) continue;
            int amount=state.getValue(net.minecraft.world.level.block.SweetBerryBushBlock.AGE)==3?2:1;
            amount=Math.min(amount,4-provisions.getCount());
            if (level().setBlock(p,state.setValue(net.minecraft.world.level.block.SweetBerryBushBlock.AGE,1),3)) {
                if (provisions.isEmpty()) provisions=new ItemStack(Items.SWEET_BERRIES,amount); else provisions.grow(amount);
                entity.setPersistenceRequired(); entity.speak("take_food"); return true;
            }
        }
        return false;
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
            var captain = boat.getControllingPassenger() instanceof YukkuriEntity y ? y : null;
            String response = captain == null ? "idle" : captain.aquaticLife().care().response(entity);
            if (!response.equals("idle")) {
                var toward = captain.position().subtract(entity.position());
                float yaw = (float)(Math.toDegrees(Math.atan2(toward.z,toward.x))-90);
                entity.setYRot(yaw); entity.yBodyRot = yaw; entity.setYHeadRot(yaw);
                activity = response; return;
            }
            // Normally the parent serves the young. An unavailable parent never blocks emergency self-feeding.
            if (entity.life().nutrition() < 25 || captain == null || captain.isNoAi() || captain.isPanicking())
                boat.cargo().feed(entity);
            if (entity.life().dryness() >= 40 && elapsed%40 == 0) entity.life().drink(30);
            if (captain == null || captain.aquaticLife().resting() || entity.life().energy() <= 20) entity.life().restInHat(boat);
            else entity.life().wakeInHat();
            activity = entity.life().isSleeping() ? "boat_sleep" : "hat_cradle";
            return;
        }
        if (!boat.isAfloat()) return;
        if (level().isNight()) nightVoyage = true;
        if (entity.isPanicking() || boat.flooding() > 0) {
            care.cancel(); boat.stopPaddling(); activity = "boat_steady";
            if (elapsed%100 == 1) entity.speak("afraid"); return;
        }
        if (boat.towed() || entity.family().laboring()) { care.cancel(); boat.stopPaddling(); return; }
        if (entity.life().mealTicks() > 0) { care.cancel(); boat.stopPaddling(); activity = "boat_eat"; return; }
        if (entity.life().dryness() >= 40 && !care.active()) {
            boat.stopPaddling(); entity.life().wakeInHat(); activity = "boat_drink";
            if (elapsed%40 == 0) entity.life().drink(30);
            return;
        }
        if (entity.life().nutrition() < 25 && boat.cargo().count() > 0) {
            care.cancel(); entity.life().wakeInHat(); boat.stopPaddling();
            if (boat.cargo().feed(entity)) { activity = "boat_eat"; return; }
        }
        // Remember who set out together. A timeout returns the family to shore, never abandons a late child.
        expectedYoung.removeIf(id -> level().getEntity(id) instanceof YukkuriEntity y && (!y.isAlive() || y.getVehicle() == boat));
        if (boat.hasIncoming() || !familyDelayed && !expectedYoung.isEmpty()) {
            care.cancel(); entity.life().wakeInHat(); boat.holdPosition(); activity = "boat_wait_young";
            gatherFamily(boat); boardYoung(boat);
            if (++familyWait > 200) familyDelayed = true;
            if (elapsed%100 == 1) entity.speak("wait_family");
            return;
        }
        boolean hungryCrew = boat.getPassengers().stream().anyMatch(p -> p instanceof YukkuriEntity y && y.life().nutrition() < 30 && y.life().mealTicks() == 0);
        boolean needsShore = boat.getPassengers().stream().anyMatch(p -> p instanceof YukkuriEntity y
                && (y.life().bladder() >= 70 || y != entity && y.isPanicking()));
        boolean returnToBank = familyDelayed || needsShore || level().isRainingAt(boat.blockPosition())
                || hungryCrew && boat.cargo().count() == 0 && care.serving().isEmpty() || nightVoyage && !level().isNight()
                || !HatVessel.aquatic(entity) || !boat.cargo().paddle() || !level().isNight() && elapsed > 2400 || entity.family().wantsBoatLanding()
                || boat.getPassengers().stream().anyMatch(p -> p instanceof YukkuriEntity y && y!=entity && y.family().adult());
        if (returnToBank) { land(boat); return; }
        boolean bedtime = level().isNight() && restWater != null && HatBoatRoute.quietSurface(level(),restWater)
                && boat.position().distanceToSqr(HatBoatRoute.surface(level(),restWater)) <= 1;
        if (care.tend(boat,bedtime)) { activity = care.active() ? care.activity() : "boat_drift"; return; }
        if (!entity.life().isSleeping() && entity.life().nutrition() < 60 && boat.cargo().feed(entity)) {
            boat.stopPaddling(); activity = "boat_eat"; return;
        }
        if (!entity.life().isSleeping() && elapsed%20 == 1) collect(boat);
        if (gatherTicks > 0) { gatherTicks--; boat.stopPaddling(); activity = "boat_gather"; return; }
        if (level().isNight() && !entity.life().isSleeping()) {
            if (restWater != null && !HatBoatRoute.quietSurface(level(),restWater)) restWater = null;
            if (restWater == null && (restSearchTicks == 0 || restSearchTicks%80 == 0)) restWater = HatBoatRoute.restingPlace(boat,rememberedBank);
            restSearchTicks++;
            if (restWater == null || boat.position().distanceToSqr(HatBoatRoute.surface(level(),restWater)) > 1) {
                activity = "boat_seek_rest";
                if (restSearchTicks > 200) { land(boat); return; }
                if (restWater != null && elapsed%20 == 1) boat.paddleTo(HatBoatRoute.surface(level(),restWater));
                else if (restWater == null) boat.stopPaddling();
                return;
            }
            boat.stopPaddling();
            // Let the parent finish the lullaby before starting the physiological sleep clock.
            if (care.tend(boat,true)) { activity = care.active() ? care.activity() : "boat_drift"; return; }
        }
        if (level().isNight() || entity.life().energy() <= 20 || entity.life().isSleeping()) {
            boat.stopPaddling(); entity.life().restInHat(boat); activity = "boat_sleep"; return;
        }
        restWater = null; restSearchTicks = 0;
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
    private void land(HatBoatEntity boat) {
        care.cancel(); entity.life().wakeInHat(); activity = "boat_land";
        var shore = boat.nearestBank();
        if (shore != null && boat.distanceToSqr(Vec3.atBottomCenterOf(shore)) <= 3.25 && boat.rescue(shore,null)) return;
        if (elapsed%40 == 1) boat.paddleTo(shore == null ? rememberedBank == null ? null : Vec3.atBottomCenterOf(rememberedBank) : Vec3.atBottomCenterOf(shore));
        if (!boat.paddling()) { activity = "boat_call"; if (elapsed%120 == 1) entity.speak("wait_family"); }
    }
    private void gatherFamily(HatBoatEntity boat) {
        if (elapsed%20 != 0 || rememberedBank == null) return;
        for (var id : expectedYoung) if (level().getEntity(id) instanceof YukkuriEntity young && young.isAlive()
                && !young.isPassenger() && !young.family().flying() && !young.isNoAi() && !young.isCommandPlaying()
                && !young.operations().active() && !young.isPanicking() && !young.life().hasUrgentNeed()
                && !young.life().isSleeping() && young.life().mealTicks() == 0 && young.distanceToSqr(boat) > 6.25
                && young.life().dryStand(rememberedBank)) young.life().navigate(rememberedBank,.85,false);
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
            if (expectedYoung.contains(young.getUUID()) && !young.isPanicking() && young.life().mealTicks() == 0
                    && !young.life().hasUrgentNeed() && !young.life().isSleeping()
                    && young.distanceToSqr(boat) <= 6.25 && young.hasLineOfSight(boat) && young.onGround()) {
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
        care.cancel();
        bank = water = paddleStand = null; provisionSource = null; launchTicks = 0; activity = "idle"; entity.getNavigation().stop();
        if (entity.getVehicle() instanceof HatBoatEntity boat && entity.getUUID().equals(boat.ownerYukkuri())) boat.stopPaddling();
    }
    public CompoundTag save() {
        var tag = new CompoundTag(); tag.putBoolean("Stick", stick); tag.putBoolean("NightVoyage", nightVoyage); tag.putInt("Cooldown", cooldown);
        tag.putBoolean("HatCareDue",hatCareDue); tag.putBoolean("FamilyDelayed",familyDelayed); tag.putInt("FamilyWait",familyWait);
        tag.put("Care",care.save());
        if (!provisions.isEmpty()) tag.put("Provisions",provisions.save(entity.registryAccess()));
        var young = new net.minecraft.nbt.ListTag();
        for (var id : expectedYoung) { var entry = new CompoundTag(); entry.putUUID("Id",id); young.add(entry); }
        tag.put("ExpectedYoung",young);
        if (rememberedBank != null) { tag.putLong("Bank", rememberedBank.asLong()); tag.putString("Dimension", entity.level().dimension().location().toString()); }
        return tag;
    }
    public void load(CompoundTag tag) {
        stick = tag.getBoolean("Stick"); nightVoyage = tag.getBoolean("NightVoyage"); cooldown = Math.clamp(tag.getInt("Cooldown"), 0, 1200);
        hatCareDue = tag.getBoolean("HatCareDue"); hatCareTicks = 0; familyDelayed = tag.getBoolean("FamilyDelayed"); familyWait = Math.clamp(tag.getInt("FamilyWait"),0,201);
        care.load(tag.getCompound("Care")); provisions = ItemStack.parseOptional(entity.registryAccess(),tag.getCompound("Provisions"));
        if (!provisions.isEmpty()) provisions.setCount(Math.min(4,provisions.getCount()));
        expectedYoung.clear(); restWater = null; restSearchTicks = 0;
        for (var raw : tag.getList("ExpectedYoung",net.minecraft.nbt.Tag.TAG_COMPOUND))
            if (raw instanceof CompoundTag entry && entry.hasUUID("Id") && expectedYoung.size() < HatBoatEntity.MAX_YOUNG) expectedYoung.add(entry.getUUID("Id"));
        rememberedBank = tag.contains("Bank") && tag.getString("Dimension").equals(entity.level().dimension().location().toString()) ? BlockPos.of(tag.getLong("Bank")) : null;
    }
}
