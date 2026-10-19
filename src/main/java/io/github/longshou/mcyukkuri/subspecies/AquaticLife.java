package io.github.longshou.mcyukkuri.subspecies;

import io.github.longshou.mcyukkuri.life.EcologyRoutine;
import io.github.longshou.mcyukkuri.life.EcologyRoutines;
import io.github.longshou.mcyukkuri.entity.Behaviors;
import io.github.longshou.mcyukkuri.social.Bonds;
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
import io.github.longshou.mcyukkuri.event.WildEvents;
import io.github.longshou.mcyukkuri.life.SurfaceApproach;
import java.util.LinkedHashSet;
import java.util.UUID;
import net.minecraft.core.Direction;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.SweetBerryBushBlock;
import net.minecraft.world.phys.HitResult;

/** Server-owned shore, provisions, family boarding and sailing. Water tolerance is deliberately unchanged. */
public final class AquaticLife extends Goal implements EcologyRoutine {
    private final YukkuriEntity entity;
    private final HatFamilyCare care;
    private final FirstVoyage firstVoyage;
    private final AquaticShore shore;
    private final HatShoreForage shoreForage;
    private final HatFamilyPaddle familyPaddle;
    private final AquaticFamilyLife familyLife;
    private final AquaticDailyLife daily;
    private final AquaticSelfRescue selfRescue;
    private BlockPos bank, water, rememberedBank, paddleStand;
    private BlockPos restWater;
    private int cooldown, elapsed, launchTicks, gatherTicks, provisionTicks, familyWait, hatCareTicks, restSearchTicks;
    private boolean nightVoyage, hatCareDue, familyDelayed;
    private boolean shoreVisitRequested;
    /** Ticks a shore trip or a stranded hull has gone without finishing; not saved, so a reload simply starts the clock again. */
    private int shoreTripTicks, beachedTicks;
    private ItemStack provisions = ItemStack.EMPTY;
    /** A real pre-launch paddle, persisted separately from the boat's cargo. */
    private ItemStack stick = ItemStack.EMPTY;
    private ItemEntity provisionSource, floatingSource;
    private long nextFoodSearch;
    private final LinkedHashSet<UUID> expectedYoung = new LinkedHashSet<>();
    private String activity = Behaviors.IDLE;
    private long nextPlan;

    public AquaticLife(YukkuriEntity entity) {
        this.entity = entity; care = new HatFamilyCare(entity); firstVoyage = new FirstVoyage(entity); shore = new AquaticShore(entity); shoreForage = new HatShoreForage(entity);
        familyLife = new AquaticFamilyLife(entity); daily = new AquaticDailyLife(entity); familyPaddle = new HatFamilyPaddle(entity);
        selfRescue=new AquaticSelfRescue(entity);
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK, Flag.JUMP));
    }
    private ServerLevel level() { return (ServerLevel) entity.level(); }
    public String activity() { return activity; }
    public boolean active() { return !activity.equals(Behaviors.IDLE); }
    public boolean hasStick() { return !stick.isEmpty(); }
    /** Count the same return paddle while it is held, stowed, or still aboard the child's own hull. */
    boolean hasPaddleForReturn() {
        if (hasStick() || firstVoyage.hasRetainedPaddle()) return true;
        UUID id=entity.subspecies().boatId();
        if(id==null)id=entity.life().custodyBoat();
        if(id==null||entity.life().accessoryCustodyEnded()||!(entity.level() instanceof ServerLevel server))return false;
        var loaded=server.getEntity(id);
        // Unloaded inventory is unknown, so keep its paddle slot until the hull can be inspected.
        return loaded==null || loaded instanceof HatBoatEntity own && own.ownsPhysicalHat(entity) && own.cargo().paddle();
    }
    public HatFamilyCare care() { return care; }
    public FirstVoyage firstVoyage() { return firstVoyage; }
    public AquaticShore shore() { return shore; }
    public HatShoreForage shoreForage() { return shoreForage; }
    public AquaticFamilyLife familyLife() { return familyLife; }
    public AquaticDailyLife daily() { return daily; }
    public HatFamilyPaddle familyPaddle() { return familyPaddle; }
    public AquaticSelfRescue selfRescue(){return selfRescue;}
    public int provisions() { return provisions.getCount(); }
    public boolean resting() { return Behaviors.BOAT_SLEEP.equals(activity) || Behaviors.BOAT_LULLABY.equals(activity); }
    public boolean awaiting(YukkuriEntity young) { return !familyDelayed && expectedYoung.contains(young.getUUID()); }
    public BlockPos boardingBank() { return bank != null ? bank : rememberedBank; }
    public void landed() { shoreVisitRequested = false; cooldown = 80; nightVoyage = false; hatCareDue = true; expectedYoung.clear(); restWater = null; yieldAllBut(BoatTask.TRIP_ENDED); }
    /** Request real shore travel; the normal water route, landing contact and accessory rules still apply. */
    public boolean requestShoreVisit() {
        if (entity.level().isClientSide || !EcologyRoutines.aquaticVoyage(entity) || !available()
                || !(entity.getVehicle() instanceof HatBoatEntity boat) || !entity.getUUID().equals(boat.ownerYukkuri())
                || !boat.isAfloat() || boat.nearestBank()==null) return false;
        shoreVisitRequested=true; entity.life().wakeInHat(); return true;
    }
    public boolean shoreVisitRequested() { return shoreVisitRequested; }
    private boolean afloatSpawn;
    /** A freshly spawned aquatic adult starts on the water in her own boat, not on the bank: once she is in the world,
     *  the nearest calm surface with a dry bank within 14 blocks is used (see launchAfloat). Without one she lives the ordinary way. */
    public void requestAfloatSpawn() { if (EcologyRoutines.aquaticVoyage(entity)) afloatSpawn = true; }
    /** Put this individual, her paddle, a little food and any of her young nearby onto the water at once, through the
     *  normal hat/boat objects: the hat leaves her head as the boat, her young board it. Returns false when nothing fits. */
    public boolean launchAfloat() {
        if (level().isClientSide || !EcologyRoutines.aquaticVoyage(entity) || !available() || entity.isPassenger() || entity.isVehicle()
                || entity.subspecies().boatId() != null || !entity.life().hasAccessory() || !firstVoyage.mayLaunch()
                || entity.life().isSleeping() || entity.family().active() || entity.family().brood() != null) return false;
        BlockPos bank = null, water = null; double nearest = Double.MAX_VALUE;
        for (var p : BlockPos.betweenClosed(entity.blockPosition().offset(-14,-3,-14), entity.blockPosition().offset(14,3,14))) {
            if (!HatBoatRoute.calmSurface(level(), p)) continue;
            for (var side : Direction.Plane.HORIZONTAL) {
                var stand = p.relative(side).above(); double distance = stand.distSqr(entity.blockPosition());
                if (distance >= nearest || !level().hasChunkAt(stand) || !entity.life().dryStand(stand)
                        || !level().noCollision(entity, entity.getBoundingBox().move(Vec3.atBottomCenterOf(stand).subtract(entity.position())))) continue;
                nearest = distance; bank = stand.immutable(); water = p.immutable();
            }
        }
        if (bank == null) return false;
        boolean initialEquipment = afloatSpawn;
        if (!initialEquipment && stick.isEmpty()) return false;
        var from = entity.position(); var hadStick = stick.copy(); var hadProvisions = provisions.copy();
        // Only the one-time afloat spawn is allowed to receive its initial equipment.
        if (stick.isEmpty()) stick = new ItemStack(Items.STICK);
        if (provisions.isEmpty() && initialEquipment) provisions = new ItemStack(Items.APPLE, 2);
        entity.setPos(Vec3.atBottomCenterOf(bank)); entity.setDeltaMovement(Vec3.ZERO); entity.getNavigation().stop();
        var boat = HatBoatEntity.createFor(entity, water);
        if (boat == null) { entity.setPos(from); stick = hadStick; provisions = hadProvisions; return false; }
        entity.setPos(boat.toWorld(boat.ownerSeat(entity)));
        for (var child : nearbyYoung()) if (boat.hasRoomFor(child)) boat.boardYoung(child);
        return true;
    }
    public void clock() { EcologyRoutines.tick(this); }
    // This clock serves ordinary passengers and hat owners as well as aquatic voyagers.
    @Override public boolean supportsPassiveTick() { return true; }
    @Override public void passiveTick() {
        if (afloatSpawn && entity.tickCount >= 20 && entity.tickCount % 10 == 0 && !entity.level().isClientSide) {
            if (launchAfloat() || entity.tickCount > 1200) afloatSpawn = false;
        }
        if (cooldown > 0) cooldown--; shore.tick(); care.clock(); firstVoyage.clock(); familyLife.clock(); daily.clock();
        selfRescue.clock();
        // A cancelled trip must not leave her starving while she still carries real provisions.
        if (!provisions.isEmpty() && available() && !entity.isPassenger() && !entity.life().isSleeping()
                && entity.life().nutrition() < 30 && entity.life().beginMeal(provisions.copyWithCount(1),null,true)) provisions.shrink(1);
    }
    public boolean giveStick(ItemStack stack, boolean creative) {
        if (!EcologyRoutines.aquaticVoyage(entity) || hasPaddleForReturn() || !stack.is(Items.STICK)
                || entity.family().flying() || entity.family().braidRescueActive()) return false;
        if(entity.getVehicle() instanceof HatBoatEntity boat&&!entity.getUUID().equals(boat.ownerYukkuri())
                &&boat.remainingCapacity()<HatBoatCargo.PADDLE_UNITS)return false;
        stick = creative ? stack.copyWithCount(1) : stack.split(1); return true;
    }
    /** A family paddle handoff is the same already-accounted held stick, never an external player/item insertion. */
    boolean receiveFamilyPaddle(HatFamilyPaddle source,ItemStack stack){
        if(!stick.isEmpty()||!(entity.getVehicle() instanceof HatBoatEntity boat)||boat.owner()==null
                ||source!=boat.owner().aquaticLife().familyPaddle()||!source.handingOverTo(entity,stack))return false;
        stick=stack.split(1);return true;
    }
    public void equip(HatBoatEntity boat) {
        if (!stick.isEmpty() && boat.cargo().givePaddle(stick, false)) stick = ItemStack.EMPTY;
        if (!provisions.isEmpty()) boat.cargo().store(provisions, provisions.getCount(), false);
        rememberedBank = entity.getVehicle() instanceof HatBoatEntity home && home!=boat && home.getControllingPassenger() instanceof YukkuriEntity parent
                ? parent.aquaticLife().boardingBank() : entity.blockPosition(); nightVoyage = level().isNight();
        expectedYoung.clear(); nearbyYoung().forEach(y -> expectedYoung.add(y.getUUID()));
        familyWait = restSearchTicks = 0; familyDelayed = false; restWater = null;
    }
    public void dropStick() {
        selfRescue.stop();
        yieldAllBut(BoatTask.TRIP_ENDED);
        firstVoyage.dropStoredCargo();
        if (!stick.isEmpty()) {
            var dropped = entity.spawnAtLocation(stick); stick = ItemStack.EMPTY;
            if (dropped != null) familyPaddle.rememberOwnDroppedPaddle(dropped.getUUID());
        }
        if (!provisions.isEmpty()) { var food = provisions; provisions = ItemStack.EMPTY; entity.spawnAtLocation(food); }
    }
    private List<YukkuriEntity> nearbyYoung() {
        return level().getEntitiesOfClass(YukkuriEntity.class, entity.getBoundingBox().inflate(10,3,10),
                y -> y.isAlive() && !y.family().adult() && !y.isPassenger() && !y.aquaticLife().firstVoyage().independent()
                        && (Bonds.parentOf(entity, y)));
    }
    private boolean canTendHat() {
        return hatCareDue && EcologyRoutines.aquaticVoyage(entity) && entity.onGround() && !entity.isPassenger() && !entity.isPanicking()
                && entity.life().hasAccessory() && entity.life().mealTicks() == 0 && !entity.life().isSleeping()
                && entity.life().nutrition() >= 30 && !entity.family().active() && !entity.dailyLife().active();
    }
    private boolean available() {
        return entity.isAlive() && !entity.isNoAi() && !entity.isCommandPlaying() && !entity.operations().active()
                && !entity.isInWaterOrBubble() && !entity.isOnFire() && !entity.family().flying();
    }
    private boolean reactingToBoatMotion() {
        return entity.isAlive() && !entity.isNoAi() && !entity.isCommandPlaying() && !entity.operations().active()
                && !entity.family().flying() && entity.getVehicle() instanceof HatBoatEntity boat && boat.unsteadyMotion();
    }
    private boolean respondingToFamilyAlarm() {
        return entity.isAlive()&&!entity.isNoAi()&&!entity.isCommandPlaying()&&!entity.operations().active()
                &&!entity.family().flying()&&!entity.isInWaterOrBubble()&&!entity.isOnFire()
                &&entity.getVehicle() instanceof HatBoatEntity boat&&boat.protects(entity)
                &&familyLife.alarm().preventsOuting();
    }
    private HatBoatEntity waitingOwnHat(){
        if(!available()||entity.isPassenger()||!entity.onGround()||entity.family().flying()
                ||firstVoyage.active()||entity.subspecies().boatId()==null)return null;
        var hat=HatBoatEntity.owned(entity);
        return hat!=null&&hat.isAfloat()&&!hat.isVehicle()&&!hat.landing()&&!hat.unsteadyMotion()
                &&hat.distanceToSqr(entity)<=9&&hat.ownsPhysicalHat(entity)?hat:null;
    }
    private HatBoatEntity landingFamilyHat(){
        if(!available()||entity.family().flying())return null;
        var own=HatBoatEntity.owned(entity);
        if(own!=null&&own.landing())return own;
        for(var id:new UUID[]{entity.family().mother(),entity.family().father()}){
            if(id!=null&&level().getEntity(id) instanceof YukkuriEntity parent){
                var hat=HatBoatEntity.owned(parent);
                if(hat!=null&&hat.landing()&&hat.young(entity)&&entity.distanceToSqr(hat)<=64)return hat;
            }
        }
        return null;
    }
    @Override public boolean canUse() {
        if (reactingToBoatMotion()) return true;
        if (respondingToFamilyAlarm()) return true;
        if (familyLife.alarm().shoreResponse()) return true;
        if (firstVoyage.active() && eventAvailable()) return true;
        if (landingFamilyHat()!=null) return true;
        if (waitingOwnHat()!=null) return true;
        if (!available()) return false;
        if (firstVoyage.tryStart()) return true;
        if (entity.getVehicle() instanceof HatBoatEntity boat) return boat.protects(entity);
        if (canTendHat()) { hatCareTicks = 0; return true; }
        if (!EcologyRoutines.aquaticVoyage(entity) || entity.isPassenger() || entity.isPanicking() || !entity.onGround()
                || !firstVoyage.mayLaunch()
                || !entity.life().hasAccessory() || entity.subspecies().boatId() != null || cooldown > 0
                || entity.family().active() || entity.family().brood() != null || entity.life().mealTicks() > 0
                || needsBeforeLaunch() || infantsAshore() || entity.life().isSleeping()
                || WildEvents.reserved(entity)) return false;
        long now = level().getGameTime(); if (now < nextPlan) return false;
        nextPlan = now+40;
        // Her home is afloat. Shore visits end as soon as actual food/toilet/hat-care needs allow.
        bank = null; water = null;
        double nearest = Double.MAX_VALUE;
        int range=shore.coastal(entity.blockPosition())?8:16;
        for (var p : BlockPos.betweenClosed(entity.blockPosition().offset(-range,-2,-range), entity.blockPosition().offset(range,2,range))) {
            if (!HatBoatRoute.calmSurface(level(), p)) continue;
            for (var side : Direction.Plane.HORIZONTAL) {
                var stand = p.relative(side).above(); double distance = stand.distSqr(entity.blockPosition());
                if (distance >= nearest || !level().hasChunkAt(stand) || !entity.life().dryStand(stand)
                        || !entity.life().reachable(stand, false)) continue;
                nearest = distance; bank = stand.immutable(); water = p.immutable();
            }
        }
        elapsed = launchTicks = provisionTicks = familyWait = 0; return bank != null;
    }
    @Override public boolean canContinueToUse() {
        if (reactingToBoatMotion()) return true;
        if (respondingToFamilyAlarm()) return true;
        if (familyLife.alarm().shoreResponse()) return true;
        if (firstVoyage.active() && eventAvailable()) return true;
        if (landingFamilyHat()!=null) return true;
        if (waitingOwnHat()!=null) return true;
        if (!available()) return false;
        if (entity.getVehicle() instanceof HatBoatEntity boat) return boat.protects(entity);
        if (canTendHat()) return hatCareTicks < 48;
        return bank != null && water != null && elapsed < 720 && !entity.isPanicking()
                && entity.life().mealTicks() == 0 && !entity.life().isSleeping()
                && entity.life().hasAccessory() && !needsBeforeLaunch() && !infantsAshore() && !entity.family().active()
                && HatBoatRoute.calmSurface(level(), water) && entity.life().dryStand(bank);
    }
    @Override public boolean requiresUpdateEveryTick() { return true; }
    @Override public void start() { entity.getNavigation().stop(); }
    @Override public void tick() {
        elapsed++;
        var landingBoat=landingFamilyHat();
        if(landingBoat!=null&&!entity.family().braidRescueActive()&&!familyLife.alarm().preventsOuting()){
            yieldAllBut(BoatTask.LANDING);
            // The boat controller handles one crossing at a time; landed siblings wait on dry ground.
            if(!entity.getUUID().equals(landingBoat.ownerYukkuri()))entity.getNavigation().stop();
            activity=Behaviors.BOAT_LAND;return;
        }
        var waiting=waitingOwnHat();
        if(waiting!=null){
            activity=Behaviors.BOAT_LAUNCH;entity.getNavigation().stop();waiting.holdPosition();
            if(elapsed%10==1)entity.family().embark(waiting);
            return;
        }
        if (reactingToBoatMotion() && entity.getVehicle() instanceof HatBoatEntity boat) { aboard(boat); return; }
        if (familyLife.alarm().shoreResponse() && familyLife.alarm().tickResponse()) {
            yieldAllBut(BoatTask.SHORE_ALARM);activity=familyLife.alarmActivity();return;
        }
        if (familyLife.alarm().responding() && entity.getVehicle() instanceof HatBoatEntity own
                && entity.getUUID().equals(own.ownerYukkuri()) && !firstVoyage.returningToParent()
                && familyLife.alarm().tickResponse()) {
            yieldAllBut(BoatTask.AFLOAT_ALARM);activity=familyLife.alarmActivity();return;
        }
        if (firstVoyage.active() || firstVoyage.tryStart()) { yieldAllBut(BoatTask.FIRST_VOYAGE); firstVoyage.tick(); activity = familyLife.alarm().responding()
                ?familyLife.alarmActivity():firstVoyage.activity(); return; }
        if (entity.getVehicle() instanceof HatBoatEntity boat) { aboard(boat); return; }
        if (canTendHat()) {
            activity = Behaviors.AQUATIC_HAT_CARE; entity.getNavigation().stop();
            if (++hatCareTicks >= 48) { entity.life().applyEnvironment(-8,0,0); hatCareDue = false; }
            return;
        }
        if (bank == null || water == null) return;
        if (stick.isEmpty() && elapsed<120 && !shore.tired()) { activity = Behaviors.BOAT_FIND_PADDLE; seekStick(); return; }
        if (!shore.tired() && provisionTicks < 120 && provisions.getCount() < Math.min(4, 1+nearbyYoung().size()) && prepareFood()) {
            provisionTicks++; launchTicks = 0; activity = Behaviors.BOAT_PROVISION; return;
        }
        activity = Behaviors.BOAT_SEEK_BANK;
        if (entity.distanceToSqr(Vec3.atBottomCenterOf(bank)) > .4) {
            launchTicks = 0;
            if (elapsed%20 == 1 && !entity.life().navigate(bank, .9, false)) stop();
            return;
        }
        entity.getNavigation().stop();
        if (firstVoyage.needsLesson()) {
            // A novice may collect its paddle, but cannot bypass the supervised launch by ordinary wandering.
            launchTicks = 0; activity = Behaviors.VOYAGE_WAIT_PARENT;
            if (++familyWait > 200) { cooldown = 200; stop(); }
            return;
        }
        var young = nearbyYoung();
        if (young.stream().anyMatch(y -> y.distanceToSqr(entity) > 6.25
                || y.isNoAi() || y.isCommandPlaying() || y.operations().active() || y.isPanicking()
                || y.isInWaterOrBubble() || y.life().mealTicks() > 0 || y.life().nutrition() < 30)) {
            activity = Behaviors.BOAT_WAIT_YOUNG;
            launchTicks = 0;
            if (elapsed%100 == 1) entity.speak("wait_family");
            if (++familyWait > 200) { cooldown = 400; stop(); }
            return;
        }
        var delta = HatBoatRoute.surface(level(), water).subtract(entity.position());
        float yaw = (float) (Math.toDegrees(Math.atan2(delta.z, delta.x))-90);
        entity.setYRot(yaw); entity.yBodyRot = yaw; entity.setYHeadRot(yaw);
        activity = Behaviors.BOAT_LAUNCH;
        if (++launchTicks >= 36) {
            var boat = HatBoatEntity.launchFromBank(entity, water);
            bank = water = null; launchTicks = 0;
            if (boat == null) { cooldown = 200; activity = Behaviors.IDLE; }
        }
    }
    /** An infant cannot hop into a hat, and nothing carries it from the bank to the water: while one of her own stands on
     *  the shore near her she stays, rather than sail off and leave it (it grows into a hopper, or she stays on). */
    private boolean infantsAshore() {
        return !level().getEntitiesOfClass(YukkuriEntity.class, entity.getBoundingBox().inflate(16,4,16),
                y -> y.isAlive() && !y.family().adult() && !y.isPassenger() && !y.aquaticLife().firstVoyage().independent()
                        && !y.childhood().canJump() && (Bonds.parentOf(entity, y))).isEmpty();
    }
    private boolean needsBeforeLaunch() {
        return entity.life().bladder()>=70&&!entity.life().constipated()
                ||entity.life().nutrition()<25&&provisions.isEmpty();
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
            if (!state.is(Blocks.SWEET_BERRY_BUSH) || state.getValue(SweetBerryBushBlock.AGE) < 2) continue;
            var hit=level().clip(new ClipContext(entity.getEyePosition(),Vec3.atCenterOf(p),
                    ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,entity));
            if (hit.getType()==HitResult.Type.BLOCK && !hit.getBlockPos().equals(p)) continue;
            int amount=state.getValue(SweetBerryBushBlock.AGE)==3?2:1;
            amount=Math.min(amount,4-provisions.getCount());
            if (level().setBlock(p,state.setValue(SweetBerryBushBlock.AGE,1),3)) {
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
                    for (var side : Direction.Plane.HORIZONTAL) {
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
        for (var item : level().getEntitiesOfClass(ItemEntity.class, entity.getBoundingBox().inflate(1.5),
                i -> i.isAlive() && !i.hasPickUpDelay() && i.getItem().is(Items.STICK))) {
            if (entity.hasLineOfSight(item) && giveStick(item.getItem(), false)) { if (item.getItem().isEmpty()) item.discard(); return true; }
        }
        // A real nearby twig source is consumed. This finite harvesting rule is a project balance choice.
        if (!EventHooks.canEntityGrief(level(), entity)) return false;
        for (var p : BlockPos.betweenClosed(entity.blockPosition().offset(-2,0,-2), entity.blockPosition().offset(2,2,2))) {
            if (!level().hasChunkAt(p) || entity.distanceToSqr(Vec3.atCenterOf(p)) > 6.25) continue;
            var state = level().getBlockState(p);
            if (!state.is(BlockTags.LEAVES) && !state.is(Blocks.DEAD_BUSH)) continue;
            var hit = level().clip(new ClipContext(entity.getEyePosition(), Vec3.atCenterOf(p),
                    ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, entity));
            if (hit.getType() == HitResult.Type.BLOCK && !hit.getBlockPos().equals(p)) continue;
            if (level().setBlock(p, Blocks.AIR.defaultBlockState(), 3)) { stick = new ItemStack(Items.STICK); return true; }
        }
        return false;
    }
    private void aboard(HatBoatEntity boat) {
        entity.getNavigation().stop();
        boolean owner = entity.getUUID().equals(boat.ownerYukkuri());
        activity = Behaviors.BOAT_DRIFT;
        if (boat.unsteadyMotion()) {
            yieldAllBut(BoatTask.UNSTEADY); entity.childhood().stopHatPractice();
            entity.life().wakeInHat(); boat.stopPaddling();
            activity = boat.capsizing() ? Behaviors.BOAT_CAPSIZING : owner ? Behaviors.BOAT_STEADY : Behaviors.BOAT_ROCKED;
            return;
        }
        if (familyPaddle.active() && (!owner || !boat.isAfloat() || boat.towed() || boat.flooding()>0
                || !boat.cargo().paddle() || care.active() || entity.life().mealTicks()>0 || entity.life().isSleeping()
                || entity.life().nutrition()<60 || entity.life().energy()<40 || entity.life().dryness()>=40
                || entity.isPanicking() || entity.family().laboring() || boat.hasIncoming() || !expectedYoung.isEmpty()
                || level().isNight() || level().isRainingAt(boat.blockPosition())
                || boat.getPassengers().stream().anyMatch(p->p instanceof YukkuriEntity y && (y.isPanicking()||y.life().hasUrgentNeed())))) yieldAllBut(BoatTask.PADDLE_UNAVAILABLE);
        if (shoreForage.active() && (boat.transportingInHat(entity) || care.active() || !boat.isAfloat()
                || boat.towed() || boat.flooding()>0 || !boat.cargo().paddle() || boat.cargo().count()>2
                || entity.life().mealTicks()>0 || entity.life().isSleeping() || entity.life().dryness()>=40
                || entity.isPanicking() || entity.family().laboring() || boat.hasIncoming() || !expectedYoung.isEmpty()
                || boat.getPassengers().stream().anyMatch(p->p instanceof YukkuriEntity y && y.isPanicking()))) yieldAllBut(BoatTask.FORAGE_UNAVAILABLE);
        if (boat.transportingInHat(entity)) {
            yieldAllBut(BoatTask.TRANSPORT);
            boat.stopPaddling();
            activity = boat.carryActivity(entity);
            return;
        }
        if (!owner) {
            var captain = boat.getControllingPassenger() instanceof YukkuriEntity y ? y : null;
            String response = captain == null ? Behaviors.IDLE : captain.aquaticLife().care().response(entity);
            if (response.equals(Behaviors.IDLE) && captain != null) response = captain.aquaticLife().familyPaddle().response(entity);
            if (!response.equals(Behaviors.IDLE)) {
                yieldAllBut(BoatTask.PASSENGER_ACTIVITY);
                entity.childhood().stopHatPractice();
                // Rear care owns the child's facing until contact completes. Turning a stimulated
                // newborn back toward its parent here undoes the parent's opposite turn every tick.
                if (!response.equals(Behaviors.FIRST_TOILET_WAIT)&&!response.equals(Behaviors.FIRST_TOILET_STIMULATED)
                        &&!response.equals(Behaviors.BOAT_TOILET)&&!response.equals(Behaviors.HAT_WALK)&&!response.equals(Behaviors.BOAT_EAT)) {
                    var toward = captain.position().subtract(entity.position());
                    float yaw = (float)(Math.toDegrees(Math.atan2(toward.z,toward.x))-90);
                    SurfaceApproach.face(entity,yaw);
                }
                activity = response; return;
            }
            // Normally the parent serves the young. An unavailable parent never blocks emergency self-feeding.
            if (entity.life().nutrition() < 25 || captain == null || captain.isNoAi() || captain.isPanicking())
                boat.cargo().feed(entity);
            if (entity.life().dryness() >= 40 && elapsed%40 == 0) entity.life().drink(30);
            if (entity.life().toiletInHat(boat)) { yieldAllBut(BoatTask.PASSENGER_ACTIVITY); entity.childhood().stopHatPractice(); activity = boat
                    .walkingInHat(entity)?Behaviors.HAT_WALK:Behaviors.BOAT_TOILET; return; }
            if (familyLife.alarm().tickOnboard(boat)) { yieldAllBut(BoatTask.PASSENGER_ACTIVITY); entity.childhood().stopHatPractice(); activity=familyLife.alarmActivity(); return; }
            if (familyLife.reactInHat(boat)) { yieldAllBut(BoatTask.PASSENGER_ACTIVITY); entity.childhood().stopHatPractice(); activity=familyLife.activity(); return; }
            if (captain == null || captain.aquaticLife().resting() || entity.life().isSleeping()
                    || entity.life().energy() <= (entity.family().needsMash() ? 65 : 35)) entity.life().restInHat(boat);
            else entity.life().wakeInHat();
            if (familyLife.tickPassenger(boat)) { yieldAllBut(BoatTask.PASSENGER_ACTIVITY); activity = familyLife.activity(); return; }
            if (daily.tick(boat)) { entity.childhood().stopHatPractice(); activity = daily.activity(); return; }
            if (entity.childhood().practiseInHat(boat)) { yieldAllBut(BoatTask.PASSENGER_ACTIVITY); activity = entity.childhood().activity(); return; }
            activity = boat.walkingInHat(entity)?Behaviors.HAT_WALK:entity.life().isSleeping() ? Behaviors.BOAT_SLEEP : boat.onBrim(entity) ? Behaviors.HAT_BRIM : Behaviors.HAT_CRADLE;
            return;
        }
        if (!boat.isAfloat()) {
            // A hull run aground is no home: the landing slides it back into the water beside the nearest bank, then lands the family there.
            if (boat.state() == HatBoatEntity.State.BEACHED && ++beachedTicks >= 40 && !boat.landing()) {
                yieldAllBut(BoatTask.BEACHED_RESCUE);
                beachedTicks = boat.rescue(null, null) ? 0 : -200;
            }
            return;
        }
        beachedTicks = 0;
        familyLife.beforeNeeds(boat);
        if (level().isNight()) nightVoyage = true;
        if (boat.towed()) { yieldAllBut(BoatTask.TOWED); boat.stopPaddling(); return; }
        if (entity.family().laboring()) {
            // In labour she cannot leave the delivery (a missed catch is a drowning child); only in the rest between two births
            // may she lift a newborn that is floating at her rim, and the next push waits for it (FamilyLife.advanceLive).
            yieldAllBut(BoatTask.LABOR); boat.stopPaddling();
            if (entity.family().deliveryGap() && familyLife.gapRescue(boat)) activity = Behaviors.BOAT_RESCUE_YOUNG;
            else if(boat.cargo().handling())activity=boat.cargo().activity();
            return;
        }
        if (entity.life().mealTicks() > 0) { yieldAllBut(BoatTask.EATING); boat.stopPaddling(); activity = Behaviors.BOAT_EAT; return; }
        if (entity.life().dryness() >= 40 && !care.active()) {
            yieldAllBut(BoatTask.DRINKING);
            boat.stopPaddling(); entity.life().wakeInHat(); activity = Behaviors.BOAT_DRINK;
            if (elapsed%40 == 0) entity.life().drink(30);
            return;
        }
        if (entity.life().nutrition() < 25 && boat.cargo().count() > 0) {
            yieldAllBut(BoatTask.URGENT_FOOD); entity.life().wakeInHat(); boat.stopPaddling();
            if (boat.cargo().feed(entity)) { activity = Behaviors.BOAT_EAT; return; }
        }
        if ((boat.landing() || shoreVisitRequested) && !familyLife.alarm().preventsOuting() && !entity.family().braidRescueActive()) {
            yieldAllBut(BoatTask.LANDING); land(boat); return;
        }
        if (EcologyRoutines.aquaticVoyage(entity)) shoreTripTicks = 0;
        // Independent boat use starts in childhood; protection and regrouping remain a family duty.
        // Basic needs above still run while the flotilla waits for a tired or frightened child.
        if (familyLife.tickOwner(boat)) {
            yieldAllBut(BoatTask.FAMILY_ROUTE);
            activity=familyLife.activity();
            if(boat.cargo().handling()&&!familyLife.alarm().active()&&!entity.family().braidRescueActive()
                    &&(activity.equals(Behaviors.BOAT_WAIT_YOUNG)||activity.equals(Behaviors.BOAT_SLEEP))){
                entity.life().wakeInHat();boat.holdPosition();activity=boat.cargo().activity();
            }else yieldAllBut(BoatTask.FAMILY_CARE_YIELD);
            return;
        }
        // Cargo work yields to rescue above, but retains a live care transaction.
        // Its meal and seat must survive the short move-aside/retrieve animation.
        if (boat.cargo().handling()) {
            yieldAllBut(BoatTask.CARGO);
            boat.holdPosition(); activity=boat.cargo().activity(); return;
        }
        if (boat.overcrowded()) {
            yieldAllBut(BoatTask.CROWD_GUARD); boat.holdPosition();
            activity=Behaviors.BOAT_CROWD_GUARD;
            if(care.tend(boat,false))activity=care.activity();
            return;
        }
        if (familyLife.reactInHat(boat)) {
            yieldAllBut(BoatTask.FAMILY_REACTION); activity=familyLife.activity(); return;
        }
        if (entity.isPanicking() || boat.flooding() > 0) {
            yieldAllBut(BoatTask.DISTRESS); boat.stopPaddling(); activity = Behaviors.BOAT_STEADY;
            if (elapsed%100 == 1) entity.speak("afraid"); return;
        }
        if (elapsed%20==1) collect(boat);
        // Remember who set out together; wait and care for the family without an automatic shore trip.
        expectedYoung.removeIf(id -> level().getEntity(id) instanceof YukkuriEntity y
                && (!y.isAlive() || y.getVehicle() == boat || y.aquaticLife().firstVoyage().independent()));
        if (boat.hasIncoming() || !expectedYoung.isEmpty()) {
            yieldAllBut(BoatTask.FAMILY_WAIT);
            boat.holdPosition(); activity = Behaviors.BOAT_WAIT_YOUNG;
            gatherFamily(boat); boardYoung(boat);
            if (++familyWait > 200) familyDelayed = true;
            if (!boat.hasIncoming()) {
                if (!care.active() && entity.life().toiletInHat(boat)) activity = Behaviors.BOAT_TOILET;
                else if (care.tend(boat, level().isNight())) activity = care.activity();
                else if (entity.life().nutrition() < 60 && boat.cargo().feed(entity)) activity = Behaviors.BOAT_EAT;
                else if (level().isNight() || entity.life().isSleeping() || entity.life().energy() <= 35) entity.life().restInHat(boat);
            }
            if (elapsed%100 == 1) entity.speak("wait_family");
            return;
        }
        boolean hungryCrew = boat.getPassengers().stream().anyMatch(p -> p instanceof YukkuriEntity y && y.life().nutrition() < 30 && y.life().mealTicks() == 0);
        if (boat.getPassengers().stream().anyMatch(p -> p instanceof YukkuriEntity y && y != entity && y.isPanicking())) {
            yieldAllBut(BoatTask.DISTRESS); boat.holdPosition(); activity = Behaviors.BOAT_STEADY; return;
        }
        if (!care.active() && entity.life().toiletInHat(boat)) { yieldAllBut(BoatTask.TOILET); boat.holdPosition(); activity = boat.walkingInHat(entity)
                ? Behaviors.HAT_WALK : Behaviors.BOAT_TOILET; return; }
        if (!shoreForage.active() && hungryCrew && boat.cargo().count()==0 && care.serving().isEmpty() && seekFloatingFood(boat)) {
            yieldAllBut(BoatTask.FLOATING_FOOD);
            activity=Behaviors.BOAT_PADDLE; return;
        }
        boolean returnToBank = !EcologyRoutines.aquaticVoyage(entity);
        if (returnToBank) { land(boat); return; }
        if (!boat.cargo().paddle()) {
            yieldAllBut(BoatTask.NO_PADDLE);
            if(familyPaddle.recoverOwnPaddle(boat)){activity=Behaviors.BOAT_GATHER;return;}
            boat.stopPaddling(); activity=boat.drifting()?Behaviors.BOAT_STEADY:Behaviors.BOAT_CALL;
            // A lost paddle is not a reason to throw a dry family onto land. Food, care and sleep still work.
            if (care.tend(boat,level().isNight())) activity=care.activity();
            else if(level().isNight()||entity.life().energy()<=20){entity.life().restInHat(boat);activity=Behaviors.BOAT_SLEEP;}
            return;
        }
        boolean bedtime = level().isNight() && restWater != null && HatBoatRoute.quietSurface(level(),restWater)
                && boat.position().distanceToSqr(HatBoatRoute.surface(level(),restWater)) <= 1;
        // Finish a safe real twig handoff before starting an optional care session on the same berth.
        if (familyPaddle.handingOver() && familyPaddle.tick(boat)) {
            yieldAllBut(BoatTask.PADDLE_HANDOFF); activity = familyPaddle.activity(); return;
        }
        if (care.tend(boat,bedtime)) { yieldAllBut(BoatTask.CARE); activity = care.active() ? care.activity() : Behaviors.BOAT_DRIFT; return; }
        if (!entity.life().isSleeping() && entity.life().nutrition() < 60 && boat.cargo().feed(entity)) {
            yieldAllBut(BoatTask.SELF_FEED);
            boat.stopPaddling(); activity = Behaviors.BOAT_EAT; return;
        }
        // Small captains replenish a real reserve while still well fed; parent refuge is the fallback when it runs out.
        boolean lowStores = boat.cargo().count()<=2 && (familyLife.followsParent() && boat.cargo().count()<2
                || boat.getPassengers().stream().anyMatch(p->p instanceof YukkuriEntity y && y.life().nutrition()<60));
        if ((shoreForage.active() || lowStores) && shoreForage.tick(boat)) { yieldAllBut(BoatTask.SHORE_FORAGE); activity=shoreForage.activity(); return; }
        if (gatherTicks > 0) { yieldAllBut(BoatTask.GATHERING); gatherTicks--; boat.stopPaddling(); activity = Behaviors.BOAT_GATHER; return; }
        if ((level().isNight() || level().isRainingAt(boat.blockPosition())) && !entity.life().isSleeping()) {
            yieldAllBut(BoatTask.REST);
            if (restWater != null && (!HatBoatRoute.quietSurface(level(),restWater)
                    || !familyLife.allowsDestination(boat,HatBoatRoute.surface(level(),restWater)))) restWater = null;
            if (restWater == null && (restSearchTicks == 0 || restSearchTicks%80 == 0))
                restWater = HatBoatRoute.restingPlace(boat,rememberedBank,p -> familyLife.allowsDestination(boat,p));
            if (restWater != null && !familyLife.allowsDestination(boat,HatBoatRoute.surface(level(),restWater))) restWater = null;
            restSearchTicks++;
            if (restWater == null || boat.position().distanceToSqr(HatBoatRoute.surface(level(),restWater)) > 1) {
                activity = Behaviors.BOAT_SEEK_REST;
                if (restSearchTicks > 200) {
                    // Only actual soaking forces an emergency shore visit; a new day never does.
                    if(boat.getPassengers().stream().anyMatch(p->p instanceof YukkuriEntity y&&y.life().wetness()>60)){land(boat);return;}
                    boat.stopPaddling();activity=Behaviors.BOAT_STEADY;
                    if(level().isNight()&&!level().isRainingAt(boat.blockPosition())
                            &&HatBoatRoute.calmSurface(level(),BlockPos.containing(boat.getX(),boat.getY()-.12,boat.getZ()))){
                        if(care.tend(boat,true))activity=care.activity();
                        else{entity.life().restInHat(boat);activity=Behaviors.BOAT_SLEEP;}
                    }
                    return;
                }
                if (restWater != null && elapsed%20 == 1) paddleWithinFamily(boat,HatBoatRoute.surface(level(),restWater));
                else if (restWater == null) boat.stopPaddling();
                return;
            }
            boat.stopPaddling();
            // Let the parent finish the lullaby before starting the physiological sleep clock.
            if (care.tend(boat,level().isNight())) { activity = care.active() ? care.activity() : Behaviors.BOAT_DRIFT; return; }
        }
        if (level().isNight() || entity.life().energy() <= 20 || entity.life().isSleeping()) {
            yieldAllBut(BoatTask.REST);
            boat.stopPaddling(); entity.life().restInHat(boat); activity = Behaviors.BOAT_SLEEP; return;
        }
        restWater = null; restSearchTicks = 0;
        if ((lowStores || floatingSource != null) && seekFloatingFood(boat)) {
            yieldAllBut(BoatTask.FLOATING_FOOD); activity = Behaviors.BOAT_PADDLE; return;
        }
        if (familyPaddle.tick(boat)) { yieldAllBut(BoatTask.PADDLE); activity = familyPaddle.activity(); return; }
        if (daily.tick(boat)) { activity = daily.activity(); return; }
        if (elapsed%40 == 1 && boat.cargo().remainingCapacity() > 0) seekFloatingFood(boat);
        if (!boat.paddling() && elapsed%160 == 1) {
            var around = boat.position().add(entity.getRandom().nextInt(11)-5, 0, entity.getRandom().nextInt(11)-5);
            paddleWithinFamily(boat,around);
        }
        activity = boat.paddling() ? Behaviors.BOAT_PADDLE : Behaviors.BOAT_DRIFT;
    }
    private boolean seekFloatingFood(HatBoatEntity boat) {
        if(!boat.cargo().paddle()||!EventHooks.canEntityGrief(level(),entity))return false;
        if(floatingSource!=null&&(!floatingSource.isAlive()||floatingSource.hasPickUpDelay()||!floatingSource.isInWater()
                ||floatingSource.distanceToSqr(boat)>144||!boat.cargo().canStore(floatingSource.getItem())
                ||!familyLife.allowsDestination(boat,floatingSource.position())))floatingSource=null;
        long now=level().getGameTime();
        if(floatingSource==null&&now>=nextFoodSearch){
            nextFoodSearch=now+40;
            var food=level().getEntitiesOfClass(ItemEntity.class,boat.getBoundingBox().inflate(10,2,10),i->i.isAlive()
                    &&!i.hasPickUpDelay()&&i.isInWater()&&entity.life().acceptsFood(i.getItem())&&boat.cargo().canStore(i.getItem())
                    &&familyLife.allowsDestination(boat,i.position())).stream()
                    .sorted(Comparator.comparingDouble(boat::distanceToSqr)).limit(5).toList();
            for(var item:food){
                if(boat.distanceToSqr(item)<2){collect(boat);if(boat.cargo().count()>0)return false;}
                if(paddleWithinFamily(boat,item.position())){floatingSource=item;return true;}
            }
        }
        if(floatingSource!=null){
            if(boat.paddling())return true;
            if(paddleWithinFamily(boat,floatingSource.position()))return true;
            floatingSource=null;
        }
        return false;
    }
    private boolean paddleWithinFamily(HatBoatEntity boat, Vec3 destination) {
        if (destination == null || !familyLife.allowsDestination(boat,destination)) return false;
        var path = HatBoatRoute.find(boat,destination,p -> familyLife.allowsDestination(boat,p));
        return !path.isEmpty() && path.stream().allMatch(p -> familyLife.allowsDestination(boat,p)) && boat.paddleAlong(path);
    }
    /** A shore trip that cannot finish within this many ticks is completed directly, or given up on, so nobody stays stuck aboard. */
    private static final int SHORE_TRIP_LIMIT = 1200;
    private void land(HatBoatEntity boat) {
        yieldAllBut(BoatTask.LAND); entity.life().wakeInHat(); activity = Behaviors.BOAT_LAND;
        if (++shoreTripTicks > SHORE_TRIP_LIMIT) {
            shoreTripTicks = 0;
            // No usable bank was reached in time: stay afloat in the normal routine and try again later. Nobody is moved.
            AquaDiag.log(entity, "shore.giveup", 0, "no landing within " + SHORE_TRIP_LIMIT + " ticks");
            shoreVisitRequested = false; cooldown = 600; boat.stopPaddling(); return;
        }
        if(boat.landing()){boat.holdPosition();return;}
        var shore = boat.nearestBank();
        // Close enough to dock: the landing presses the hull against the bank itself.
        if (shore != null && boat.distanceToSqr(Vec3.atBottomCenterOf(shore)) <= 49 && boat.rescue(shore,null)) return;
        // Without a paddle the hull can only drift; the same gentle pull used for a player's tow carries it toward the bank.
        if (shore != null && !boat.cargo().paddle() && elapsed%40 == 1 && boat.distanceToSqr(Vec3.atBottomCenterOf(shore)) > 9)
            boat.towTo(Vec3.atBottomCenterOf(shore), 60);
        if (elapsed%40 == 1) boat.paddleTo(shore == null ? rememberedBank == null ? null : Vec3.atBottomCenterOf(rememberedBank) : Vec3.atBottomCenterOf(shore));
        if (!boat.paddling()) { activity = Behaviors.BOAT_CALL; if (elapsed%120 == 1) entity.speak("wait_family"); }
    }
    private void gatherFamily(HatBoatEntity boat) {
        if (elapsed%20 != 0 || rememberedBank == null) return;
        for (var id : expectedYoung) if (level().getEntity(id) instanceof YukkuriEntity young && young.isAlive() && !young.aquaticLife().firstVoyage().independent()
                && !young.isPassenger() && !young.family().flying() && !young.isNoAi() && !young.isCommandPlaying()
                && !young.operations().active() && !young.isPanicking() && !young.life().hasUrgentNeed()
                && !young.life().isSleeping() && young.life().mealTicks() == 0 && young.distanceToSqr(boat) > 6.25
                && young.life().dryStand(rememberedBank)) young.life().navigate(rememberedBank,.85,false);
    }
    private List<YukkuriEntity> youngOnBank(HatBoatEntity boat) {
        return level().getEntitiesOfClass(YukkuriEntity.class, boat.getBoundingBox().inflate(4,2,4),
                y -> boat.young(y) && y.isAlive() && !y.isPassenger() && !y.family().flying() && !y.aquaticLife().firstVoyage().independent()
                        && !y.isNoAi() && !y.isCommandPlaying() && !y.operations().active() && !y.isInWaterOrBubble());
    }
    private void boardYoung(HatBoatEntity boat) {
        if (elapsed%10 != 0) return;
        for (var young : youngOnBank(boat)) {
            if(boat.cargo().count()==0&&!boat.hasSafeRoomFor(young))continue;
            if (expectedYoung.contains(young.getUUID()) && !young.isPanicking() && young.life().mealTicks() == 0
                    && !young.life().hasUrgentNeed() && !young.life().isSleeping()
                    && young.distanceToSqr(boat) <= 6.25 && young.hasLineOfSight(boat) && young.onGround()&&young.childhood().canJump()) {
                if(boat.cargo().count()>0&&!boat.prepareCargoSpace(young,false)){
                    if(boat.cargo().handling())break;
                    continue;
                }
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
    /** Apply only this branch's explicit handoff list, never the complement of a winning task.
     * STOP may stop an owned route; YIELD preserves a route already written by the caller.
     * FAMILY_ROUTE and FAMILY_CARE_YIELD are separate so the caller retains its care guard.
     */
    void yieldAllBut(BoatTask task) {
        switch (task) {
            case TRIP_ENDED -> { familyPaddle.stop(); care.cancel(); shoreForage.stop(); }
            case LANDING, SHORE_ALARM, FIRST_VOYAGE, UNSTEADY, BEACHED_RESCUE -> {
                daily.stop(); familyPaddle.stop(); care.cancel(); shoreForage.stop();
            }
            case AFLOAT_ALARM -> {
                daily.yieldToOtherActivity(); familyPaddle.yieldToOtherActivity(); care.cancel(); shoreForage.stop();
            }
            case PADDLE_UNAVAILABLE -> familyPaddle.stop();
            case FORAGE_UNAVAILABLE -> shoreForage.stop();
            case TRANSPORT, PASSENGER_ACTIVITY, PADDLE -> daily.yieldToOtherActivity();
            case TOWED, LABOR, EATING, URGENT_FOOD, DISTRESS -> { daily.stop(); care.cancel(); }
            case DRINKING, FAMILY_WAIT, NO_PADDLE, SELF_FEED, GATHERING, REST -> daily.stop();
            case FAMILY_ROUTE, CARGO, CROWD_GUARD, FAMILY_REACTION -> {
                daily.yieldToOtherActivity(); familyPaddle.yieldToOtherActivity(); shoreForage.stop();
            }
            case FAMILY_CARE_YIELD, RESCUE -> care.cancel();
            case TOILET -> { daily.stop(); shoreForage.stop(); }
            case FLOATING_FOOD, SHORE_FORAGE -> { daily.yieldToOtherActivity(); familyPaddle.yieldToOtherActivity(); }
            case PADDLE_HANDOFF -> { daily.yieldToOtherActivity(); shoreForage.stop(); }
            case CARE -> { daily.stop(); familyPaddle.yieldToOtherActivity(); shoreForage.stop(); }
            case LAND -> { familyPaddle.stop(); care.cancel(); }
            case STOP -> { daily.stop(); familyPaddle.stop(); familyLife.stop(); care.cancel(); shoreForage.stop(); }
        }
    }
    @Override public void stop() {
        entity.childhood().stopHatPractice();
        yieldAllBut(BoatTask.STOP);
        bank = water = paddleStand = null; provisionSource = floatingSource = null; nextFoodSearch=0; launchTicks = 0; activity = Behaviors.IDLE; entity.getNavigation().stop();
        if (entity.getVehicle() instanceof HatBoatEntity boat && entity.getUUID().equals(boat.ownerYukkuri())) boat.stopPaddling();
    }
    private boolean eventAvailable() {
        return entity.isAlive() && !entity.isNoAi() && !entity.isCommandPlaying() && !entity.operations().active()
                && !entity.isInWaterOrBubble() && !entity.isOnFire()
                && (!entity.isPanicking() || firstVoyage.returningToParent() && entity.shelteredInHat());
    }
    public CompoundTag save() {
        var tag = new CompoundTag(); tag.putBoolean("Stick", !stick.isEmpty());
        if (!stick.isEmpty()) tag.put("StickItem", stick.save(entity.registryAccess()));
        tag.putBoolean("NightVoyage", nightVoyage); tag.putInt("Cooldown", cooldown);
        tag.putBoolean("HatCareDue",hatCareDue); tag.putBoolean("FamilyDelayed",familyDelayed); tag.putInt("FamilyWait",familyWait);
        tag.putBoolean("ShoreVisitRequested",shoreVisitRequested);
        tag.put("Care",care.save());
        tag.put("FirstVoyage",firstVoyage.save());
        tag.put("WaterFamily",familyLife.save()); tag.put("WaterDaily",daily.save());
        tag.put("FamilyPaddle",familyPaddle.save());
        tag.put("SelfRescue",selfRescue.save());
        tag.put("Shore",shore.save());
        if (!provisions.isEmpty()) tag.put("Provisions",provisions.save(entity.registryAccess()));
        var young = new ListTag();
        for (var id : expectedYoung) { var entry = new CompoundTag(); entry.putUUID("Id",id); young.add(entry); }
        tag.put("ExpectedYoung",young);
        if (rememberedBank != null) { tag.putLong("Bank", rememberedBank.asLong()); tag.putString("Dimension", entity.level().dimension().location().toString()); }
        return tag;
    }
    public void load(CompoundTag tag) {
        shoreForage.stop();
        if (tag.contains("StickItem", Tag.TAG_COMPOUND)) {
            var stored = ItemStack.parseOptional(entity.registryAccess(), tag.getCompound("StickItem"));
            stick = stored.is(Items.STICK) ? stored.copyWithCount(1) : ItemStack.EMPTY;
        } else stick = tag.getBoolean("Stick") ? new ItemStack(Items.STICK) : ItemStack.EMPTY;
        nightVoyage = tag.getBoolean("NightVoyage"); cooldown = Math.clamp(tag.getInt("Cooldown"), 0, 1200);
        hatCareDue = tag.getBoolean("HatCareDue"); hatCareTicks = 0; familyDelayed = tag.getBoolean("FamilyDelayed"); familyWait = Math.clamp(tag.getInt("FamilyWait"),0,201);
        care.load(tag.getCompound("Care")); provisions = ItemStack.parseOptional(entity.registryAccess(),tag.getCompound("Provisions"));
        firstVoyage.load(tag.getCompound("FirstVoyage"));
        familyLife.load(tag.getCompound("WaterFamily")); daily.load(tag.getCompound("WaterDaily"));
        familyPaddle.load(tag.getCompound("FamilyPaddle"));
        selfRescue.load(tag.getCompound("SelfRescue"));
        shore.load(tag.getCompound("Shore"));
        shoreVisitRequested=tag.getBoolean("ShoreVisitRequested");
        if (!provisions.isEmpty()) provisions.setCount(Math.min(4,provisions.getCount()));
        expectedYoung.clear(); restWater = null; restSearchTicks = 0;
        for (var raw : tag.getList("ExpectedYoung",Tag.TAG_COMPOUND))
            if (raw instanceof CompoundTag entry && entry.hasUUID("Id") && expectedYoung.size() < HatBoatEntity.MAX_YOUNG) expectedYoung.add(entry.getUUID("Id"));
        rememberedBank = tag.contains("Bank") && tag.getString("Dimension").equals(entity.level().dimension().location().toString()) ? BlockPos.of(tag.getLong("Bank")) : null;
    }
}
