package io.github.longshou.mcyukkuri.subspecies;

import io.github.longshou.mcyukkuri.subspecies.crew.CrewTransfers;
import io.github.longshou.mcyukkuri.subspecies.boat.HullHelm.Purpose;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import io.github.longshou.mcyukkuri.subspecies.boat.HullFactory;
import io.github.longshou.mcyukkuri.subspecies.boat.ShoreBerth;

import io.github.longshou.mcyukkuri.life.EcologyRoutine;
import io.github.longshou.mcyukkuri.life.EcologyRoutines;
import io.github.longshou.mcyukkuri.entity.Behaviors;
import io.github.longshou.mcyukkuri.social.Bonds;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.family.FamilyLife;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.EnumMap;
import java.util.List;
import java.util.Objects;
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
import io.github.longshou.mcyukkuri.season.Season;
import io.github.longshou.mcyukkuri.season.Seasons;
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
    private BlockPos restWater, failedBirthWater;
    private int cooldown, elapsed, launchTicks, gatherTicks, provisionTicks, familyWait, hatCareTicks, restSearchTicks;
    private boolean nightVoyage, hatCareDue, familyDelayed, birthLaunch, courtshipShoreVisit;
    private boolean shoreVisitRequested;
    public enum ShoreWork { SETTLEMENT, INDEPENDENCE, FORAGE, HUMAN, PLAY, THERMAL }
    /** Short-lived requests owned and renewed by the existing domain task, never a second task queue. */
    private final EnumMap<ShoreWork, Long> shoreWork = new EnumMap<>(ShoreWork.class);
    /** Ticks a shore trip or a stranded hull has gone without finishing; not saved, so a reload simply starts the clock again. */
    private int shoreTripTicks, beachedTicks;
    private ItemStack provisions = ItemStack.EMPTY;
    /** A real pre-launch paddle, persisted separately from the boat's cargo. */
    private ItemStack stick = ItemStack.EMPTY;
    private ItemEntity provisionSource, floatingSource;
    private long nextFoodSearch;
    private final LinkedHashSet<UUID> expectedYoung = new LinkedHashSet<>();
    private String activity = Behaviors.IDLE;
    // Observations only: neither persisted nor consulted by gameplay decisions.
    private String launchCheck = "not_evaluated";
    private long launchCheckTick = -1;
    private UUID observedOwnBoat;
    private long nextPlan, nextCourtshipSearch, retryBirthWaterAt;

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
    public int stockTarget() {
        return Seasons.on(level(), entity.blockPosition(), Season.WINTER) ? 8 : 4;
    }
    public int availableFood() {
        var home = entity.habitat().waterHome();
        return provisions.getCount() + firstVoyage.retainedFoodCount() + (home == null ? 0 : home.cargo().count());
    }
    /** Transfer real carried food; the remaining source stays with its current domain owner. */
    public int receiveProvisions(ItemStack source, int limit) {
        if (source.isEmpty() || !entity.life().acceptsFood(source)
                || !provisions.isEmpty() && !ItemStack.isSameItemSameComponents(provisions, source)) return 0;
        int moved = Math.min(Math.min(limit, source.getCount()), Math.max(0, 8 - provisions.getCount()));
        if (moved <= 0) return 0;
        int before = provisions.getCount();
        if (provisions.isEmpty()) provisions = source.split(moved);
        else { provisions.grow(moved); source.shrink(moved); }
        GameplayLog.event(entity, "forage", "provisions received count={} -> {} sourceRemaining={}", before, provisions.getCount(), source.getCount());
        return moved;
    }
    /** Server-side cargo/custody readout; it only observes the current physical hull and its existing ledgers. */
    public String cargoDetail(){
        String state="activity="+activity+" firstVoyage="+firstVoyage.status()+" waitingYoung="+expectedYoung.size()+" familyDelayed="+familyDelayed
                +" boardingBank="+rememberedBank+" shoreWork="+shoreWork.keySet()+" climate="+(entity.life().temperature().active()?entity.life().temperature().activity():"idle")
                +" food="+availableFood()+" stockTarget="+stockTarget()+" forage="+entity.forageTrip().phase();
        HatBoatEntity boat=entity.getVehicle() instanceof HatBoatEntity ridden ? ridden : null;
        if(boat==null&&entity.level() instanceof ServerLevel server){
            UUID id=entity.life().custodyBoat();
            if(id==null)id=entity.subspecies().boatId();
            if(id!=null&&server.getEntity(id) instanceof HatBoatEntity own&&own.ownsPhysicalHat(entity))boat=own;
        }
        if(boat==null)return state+" boat=none heldPaddle="+hasStick()
                +" familyPaddle="+familyPaddle.activity()+" handoff="+familyPaddle.handingOver();
        var cargo=boat.cargo();
        return state+" boat=#"+boat.getId()+" load="+String.format(Locale.ROOT,"%.1f/%.0f",boat.capacityUsed(),boat.maxLoad())
                +" remaining="+String.format(Locale.ROOT,"%.1f",boat.remainingCapacity())
                +" young="+boat.youngAboard()+" incoming="+boat.hasIncoming()+" food="+cargo.count()+" larderRoom="+cargo.remainingCapacity()
                +" paddle="+cargo.paddle()+" heldPaddle="+hasStick()
                +" assignment=["+boat.assignmentText()+"] lastRefusal="+boat.lastDeckRefusal()
                +" familyPaddle="+familyPaddle.activity()+" handoff="+familyPaddle.handingOver();
    }
    public boolean resting() { return Behaviors.BOAT_SLEEP.equals(activity) || Behaviors.BOAT_LULLABY.equals(activity); }
    public boolean awaiting(YukkuriEntity young) { return expectedYoung.contains(young.getUUID()); }
    public BlockPos boardingBank() { return bank != null ? bank : rememberedBank; }
    public void landed() {
        shoreVisitRequested = false; cooldown = courtshipShoreVisit ? 240 : 80; courtshipShoreVisit=false;
        nightVoyage = false; hatCareDue = true; expectedYoung.clear(); restWater = null; yieldAllBut(BoatTask.TRIP_ENDED);
        long now = level().getGameTime();
        shoreWork.replaceAll((work, until) -> now + 200);
        GameplayLog.event(entity,"aquatic","voyage landed cooldown={} hatCareDue={}",cooldown,hatCareDue);
    }
    /** Request real shore travel; the normal water route, landing contact and accessory rules still apply. */
    public boolean requestShoreVisit() { return requestShoreVisit(false); }
    private boolean requestShoreVisit(boolean courtship) {
        if (entity.level().isClientSide || !EcologyRoutines.aquaticVoyage(entity) || !available()
                || !(entity.getVehicle() instanceof HatBoatEntity boat) || !entity.getUUID().equals(boat.ownerYukkuri())
                || !boat.isAfloat() || boat.nearestBank()==null) return false;
        if(!shoreVisitRequested||courtshipShoreVisit!=courtship)GameplayLog.event(entity,"aquatic","shore visit requested hat={} courtship={}",boat.getUUID(),courtship);
        shoreVisitRequested=true; courtshipShoreVisit=courtship; entity.life().wakeInHat(); return true;
    }
    public boolean shoreVisitRequested() { return shoreVisitRequested; }
    public boolean requestShoreWork(ShoreWork work) {
        if (entity.level().isClientSide || !entity.profile().aquatic()) return false;
        if (!entity.isPassenger()) {
            if (!entity.onGround()) return false;
            holdShoreWork(work); return true;
        }
        if (!requestShoreVisit()) return false;
        holdShoreWork(work); return true;
    }
    public void holdShoreWork(ShoreWork work) {
        if (entity.level().isClientSide || !entity.profile().aquatic()) return;
        long now = level().getGameTime();
        if (!shoreWork.containsKey(work) || shoreWork.get(work) <= now)
            GameplayLog.event(entity, "aquatic", "shore work requested kind={} aboard={}", work, entity.isPassenger());
        shoreWork.put(work, now + 200);
    }
    public void releaseShoreWork(ShoreWork work) {
        if (shoreWork.remove(work) != null) GameplayLog.event(entity, "aquatic", "shore work released kind={}", work);
    }
    public boolean shoreWorkActive() {
        long now = level().getGameTime();
        return shoreWork.values().stream().anyMatch(until -> until > now);
    }
    /** Server-side water-birth/return-visit readout; it never plans, launches, or searches for water. */
    public String waterBirthDetail(){
        long now=level().getGameTime();
        String spawn=!afloatSpawn?"idle":naturalAfloatSpawn?"natural_pending":"pending";
        long retry=failedBirthWater==null?0:Math.max(0,retryBirthWaterAt-now);
        return "spawn="+spawn+" spawnAge="+entity.tickCount+" required="+entity.family().waterBirthRequired()
                +" needsBoat="+entity.family().needsWaterBirthBoat()+" birthLaunch="+birthLaunch+" launchTicks="+launchTicks
                +" bank="+(bank==null?"none":bank.toShortString())+" water="+(water==null?"none":water.toShortString())
                +" retryWater="+(failedBirthWater==null?"none":failedBirthWater.toShortString())+" retry="+retry
                +" shoreVisit="+shoreVisitRequested+" courtshipVisit="+courtshipShoreVisit
                +" activity="+activity+" launchCheck="+launchCheck+" checkTick="+launchCheckTick
                +" elapsed="+elapsed+" cooldown="+cooldown+" heldPaddle="+hasStick()+" provisions="+provisions.getCount();
    }
    private boolean afloatSpawn, naturalAfloatSpawn;
    /** A newly created aquatic joins the world aboard her own real hull; the pending flag is spent by {@link #attemptAfloatSpawn}. */
    public boolean requestAfloatSpawn() {
        if(!EcologyRoutines.aquaticVoyage(entity))return false;
        afloatSpawn=true;return true;
    }
    /** Natural and settlement generation alone receive their finite one-time starting stores after the boat exists. */
    public boolean requestNaturalAfloatSpawn() {
        if(!requestAfloatSpawn())return false;
        naturalAfloatSpawn=true;return true;
    }
    /**
     * Spawn directly into one real water-surface hull: the berth is chosen where she stands at creation
     * ({@link ShoreBerth.Purpose#SPAWN}) and she is aboard before she ever acts. Equipment stays finite; this path creates neither
     * a paddle nor food beyond the natural start. Spent once; only a chunk still loading is retried, within the first 40 ticks.
     */
    public boolean attemptAfloatSpawn() {
        if (!afloatSpawn || level().isClientSide) return false;
        boolean natural = naturalAfloatSpawn;
        if (!EcologyRoutines.aquaticVoyage(entity) || !afloatSpawnAvailable() || entity.isPassenger() || entity.isVehicle()
                || entity.subspecies().boatId() != null || !entity.life().hasAccessory() || !firstVoyage.mayLaunch()
                || entity.life().isSleeping() || entity.family().active() || entity.family().brood() != null) {
            afloatSpawn = false; naturalAfloatSpawn = false;
            GameplayLog.event(entity,"aquatic","afloat spawn skipped: spawn conditions not met");
            return false;
        }
        var outcome = ShoreBerth.find(level(), entity, HatVessel.hatScale(entity), entity.blockPosition(), ShoreBerth.Purpose.SPAWN, null);
        if (outcome instanceof ShoreBerth.Refusal refusal) {
            if (!refusal.reason().equals("no_calm_water") && refusal.counts().containsKey("hull_unloaded") && entity.tickCount <= 40) return false;
            afloatSpawn = false; naturalAfloatSpawn = false;
            GameplayLog.event(entity,"aquatic","afloat spawn refused berth={}",refusal.describe());
            return false;
        }
        var result = HullFactory.deployOnBerth(entity, HullFactory.Origin.SPAWN, (ShoreBerth.Berth) outcome);
        afloatSpawn = false; naturalAfloatSpawn = false;
        var boat = HullFactory.boatOf(result);
        if (boat == null) return false;
        if (natural) equipNaturalAfloat(boat);
        GameplayLog.event(entity,"aquatic","afloat spawn boarded hat={} natural={} cargo={} paddle={}",
                boat.getUUID(),natural,boat.cargo().count(),boat.cargo().paddle());
        return true;
    }
    private void equipNaturalAfloat(HatBoatEntity boat) {
        if(!boat.cargo().paddle()&&stick.isEmpty())boat.cargo().givePaddle(new ItemStack(Items.STICK),false);
        if(boat.cargo().count()==0&&provisions.isEmpty())boat.cargo().store(new ItemStack(Items.APPLE,2),2,false);
    }
    /** Direct water-surface creation is allowed for a newly spawned swimmer; ordinary shore goals still require dry feet. */
    private boolean afloatSpawnAvailable() {
        return entity.isAlive()&&!entity.isNoAi()&&!entity.isCommandPlaying()&&!entity.operations().active()
                &&!entity.isOnFire()&&!entity.family().flying();
    }
    /**
     * Decisions the hull used to take in its own tick, kept on the captain's side until the voyage controller replaces them
     * (P5): the helm only moves the hull when asked, so whether she can paddle right now, landing a beached hull and a set-down
     * hat being fetched are decided here.
     */
    private void helmShims() {
        if (entity.getVehicle() instanceof HatBoatEntity boat && entity.getUUID().equals(boat.ownerYukkuri())) {
            // A trip ends while the captain is busy with something that takes her hands, her attention or the whole hull.
            if (boat.helm().travelling() && (entity.isCommandPlaying() || entity.operations().active()
                    || entity.family().crew().busy() || entity.family().flying() || boat.crowdContesting() || boat.landing()
                    || entity.isPanicking() && !familyLife.alarm().active() || entity.life().isSleeping()))
                boat.helm().cancelTravel("captain_busy");
            // A beached hull with its captain aboard starts the landing onto any dry block beside it.
            if (boat.state() == HatBoatEntity.State.BEACHED && entity.tickCount % 20 == 0)
                for (var dry : BlockPos.betweenClosed(boat.blockPosition().offset(-1, 0, -1), boat.blockPosition().offset(1, 1, 1)))
                    if (boat.rescue(dry, null)) break;
        } else if (entity.tickCount % 20 == 0 && entity.isAlive() && !entity.isPassenger() && entity.life().custodyBoat() != null
                && entity.level() instanceof ServerLevel server && server.getEntity(entity.life().custodyBoat()) instanceof HatBoatEntity hat
                && hat.isCradle() && hat.ownsPhysicalHat(entity) && !entity.family().keepsCradle() && entity.distanceToSqr(hat) <= 48 * 48)
            // The set-down cradle is no longer needed: its owner comes back for it.
            entity.family().fetchHat(hat);
    }
    public void clock() { EcologyRoutines.tick(this); }
    // This clock serves ordinary passengers and hat owners as well as aquatic voyagers.
    @Override public boolean supportsPassiveTick() { return true; }
    @Override public void passiveTick() {
        if (!shoreVisitRequested) shoreWork.values().removeIf(until -> until <= level().getGameTime());
        // A real carried forage trip survives temporary hunger or sleep interruptions on shore.
        if (entity.profile().aquatic() && !entity.isPassenger() && entity.forageTrip().outing()
                && entity.tickCount % 40 == 0) holdShoreWork(ShoreWork.FORAGE);
        UUID ownBoat = entity.getVehicle() instanceof HatBoatEntity hat && entity.getUUID().equals(hat.ownerYukkuri())
                ? hat.getUUID() : null;
        if (!Objects.equals(ownBoat, observedOwnBoat)) {
            GameplayLog.event(entity, "aquatic", "own-hat passenger changed {} -> {}", observedOwnBoat, ownBoat);
            observedOwnBoat = ownBoat;
        }
        if (!entity.level().isClientSide) helmShims();
        if (afloatSpawn && entity.tickCount >= 1 && entity.tickCount % 10 == 1 && !entity.level().isClientSide) attemptAfloatSpawn();
        if (cooldown > 0) cooldown--; shore.tick(); care.clock(); firstVoyage.clock(); familyLife.clock(); daily.clock();
        selfRescue.clock();
        // A cancelled trip must not leave her starving while she still carries real provisions.
        if (!provisions.isEmpty() && available() && !entity.isPassenger() && !entity.life().isSleeping()
                && entity.life().nutrition() < 30 && entity.life().beginMeal(provisions.copyWithCount(1),null,true)) provisions.shrink(1);
    }
    public boolean giveStick(ItemStack stack, boolean creative) {
        if (!EcologyRoutines.aquaticVoyage(entity) || hasPaddleForReturn() || !stack.is(Items.STICK)
                || entity.family().flying() || entity.family().crew().busy() || entity.waterDistress().receivingWaterRescue()) return false;
        if(entity.getVehicle() instanceof HatBoatEntity boat&&!entity.getUUID().equals(boat.ownerYukkuri())
                &&boat.remainingCapacity()<boat.paddleLoad())return false;
        int before=stack.getCount();
        stick = creative ? stack.copyWithCount(1) : stack.split(1);
        GameplayLog.event(entity,"aquatic","paddle received sourceCount={} -> {} held={} creative={}",
                before,stack.getCount(),stick.getCount(),creative);
        return true;
    }
    /** A family paddle handoff is the same already-accounted held stick, never an external player/item insertion. */
    boolean receiveFamilyPaddle(HatFamilyPaddle source,ItemStack stack){
        if(!stick.isEmpty()||!(entity.getVehicle() instanceof HatBoatEntity boat)||boat.owner()==null
                ||source!=boat.owner().aquaticLife().familyPaddle()||!source.handingOverTo(entity,stack))return false;
        stick=stack.split(1);
        GameplayLog.event(entity,"aquatic","family paddle received parent={} hat={} held={} sourceRemaining={}",
                boat.ownerYukkuri(),boat.getUUID(),stick,stack.getCount());return true;
    }
    public void equip(HatBoatEntity boat) {
        if (!stick.isEmpty() && boat.cargo().givePaddle(stick, false)) stick = ItemStack.EMPTY;
        if (!provisions.isEmpty()) boat.cargo().store(provisions, provisions.getCount(), false);
        rememberedBank = entity.getVehicle() instanceof HatBoatEntity home && home!=boat && home.getControllingPassenger() instanceof YukkuriEntity parent
                ? parent.aquaticLife().boardingBank() : entity.blockPosition(); nightVoyage = level().isNight();
        expectedYoung.clear(); nearbyYoung().forEach(y -> expectedYoung.add(y.getUUID()));
        familyWait = restSearchTicks = 0; familyDelayed = false; restWater = null;
        GameplayLog.event(entity,"aquatic","voyage equipped hat={} aboard={} cargo={} paddle={} expectedYoung={}",
                boat.getUUID(),entity.getVehicle()==boat,boat.cargo().count(),boat.cargo().paddle(),expectedYoung.size());
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
        return level().getEntitiesOfClass(YukkuriEntity.class, entity.getBoundingBox().inflate(16,4,16),
                y -> y.isAlive() && !y.family().adult() && !y.isPassenger() && !y.aquaticLife().firstVoyage().independent()
                        && (Bonds.parentOf(entity, y)));
    }
    private boolean canTendHat() {
        return hatCareDue && EcologyRoutines.aquaticVoyage(entity) && entity.onGround() && !entity.isPassenger() && !entity.isPanicking()
                && entity.life().hasAccessory() && entity.life().mealTicks() == 0 && !entity.life().isSleeping()
                && entity.life().nutrition() >= 30 && !entity.family().active() && !entity.dailyLife().active();
    }
    /** The late-pregnancy route is a real shore launch for each parent, never a delivery-side teleport into a hat. */
    private boolean birthLaunchEligible() {
        return entity.family().needsWaterBirthBoat() && entity.onGround() && !entity.isPassenger() && !entity.isVehicle()
                && !entity.isPanicking() && !entity.life().isSleeping() && entity.life().mealTicks()==0
                && firstVoyage.mayLaunch() && entity.life().hasAccessory() && entity.subspecies().boatId() == null;
    }
    /** Keep the father's escort launch at the mother's shore when the confirmed pair is still together. */
    private BlockPos birthLaunchAnchor() {
        UUID partner=entity.family().partner();
        if(partner!=null&&level().getEntity(partner) instanceof YukkuriEntity mother&&mother.family().waterBirthRequired())return mother.blockPosition();
        return entity.blockPosition();
    }
    private boolean planBirthLaunch() {
        bank = water = null;
        long now=level().getGameTime();
        var query=new ShoreBerth.Query(entity,null,HatVessel.hatScale(entity),birthLaunchAnchor(),ShoreBerth.Purpose.LAUNCH,null,16,0,
                p->!(p.equals(failedBirthWater)&&now<retryBirthWaterAt));
        var outcome=ShoreBerth.find(level(),query);
        if(!(outcome instanceof ShoreBerth.Berth berth)){
            GameplayLog.limited(entity,"aquatic","birth.noberth",400,"birth launch found no berth reason={}",((ShoreBerth.Refusal)outcome).describe());
            return false;
        }
        bank=berth.bank();water=berth.water();
        elapsed=launchTicks=provisionTicks=familyWait=0;return true;
    }
    /** Nearby loaded candidates are only a reason to ask the ordinary landing controller for shore; FamilyLife later owns courtship. */
    private boolean seeksShoreCourtship(HatBoatEntity boat) {
        long now=level().getGameTime();
        if(now<nextCourtshipSearch)return false;
        nextCourtshipSearch=now+100;
        UUID partner=entity.family().partner();
        if(partner!=null&&level().getEntity(partner) instanceof YukkuriEntity other
                &&entity.family().seeksShoreCourtshipWith(other))return true;
        return level().getEntitiesOfClass(YukkuriEntity.class,boat.getBoundingBox().inflate(8,3,8),
                other->entity.family().seeksShoreCourtshipWith(other)).stream().findFirst().isPresent();
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
    /** An ordinary voyage yields the selector to real domain goals; custody and urgent physiology do not. */
    private boolean priorityAtSea() {
        if (!(entity.getVehicle() instanceof HatBoatEntity boat) || !boat.protects(entity)) return true;
        if (!boat.isAfloat() || boat.unsteadyMotion() || boat.flooding() > 0
                || boat.landing() || boat.transportingInHat(entity)
                || boat.hasIncoming() || !expectedYoung.isEmpty()
                || shoreVisitRequested || firstVoyage.active() || care.active() || familyPaddle.active()
                || shoreForage.active() || entity.family().laboring() || entity.family().crew().busy()
                || familyLife.alarm().preventsOuting() || entity.isPanicking()) return true;
        return entity.life().mealTicks() > 0 || entity.life().nutrition() < 25
                || entity.life().energy() <= 20 || entity.life().dryness() >= 40
                || entity.life().bladder() >= 70 && !entity.life().constipated()
                || entity.life().isSleeping() || entity.life().temperature().active()
                || entity.life().temperature().unsafeSleep();
    }
    private boolean routineAfloat() {
        return available() && entity.getVehicle() instanceof HatBoatEntity boat && boat.protects(entity);
    }
    /** A separate selector identity sharing this state machine, never a second passive clock. */
    public Goal routineGoal() {
        return new Goal() {
            { setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK, Flag.JUMP)); }
            @Override public boolean canUse() {
                if (!routineAfloat() || priorityAtSea()) return false;
                // Starting a real lesson/departure promotes this same voyage to the priority-3 goal.
                return !firstVoyage.tryStart();
            }
            @Override public boolean canContinueToUse() { return canUse(); }
            @Override public boolean requiresUpdateEveryTick() { return true; }
            @Override public void start() { AquaticLife.this.start(); }
            @Override public void tick() { AquaticLife.this.tick(); }
            @Override public void stop() {
                // A meal/care/boarding started by the routine is handed to priority 3 intact.
                // Other interruptions cancel through the same original domain teardown.
                if (routineAfloat() && priorityAtSea() && !entity.isPanicking()) return;
                AquaticLife.this.stop();
            }
        };
    }
    @Override public boolean canUse() {
        if (routineAfloat() && !priorityAtSea()) return false;
        if (reactingToBoatMotion()) return true;
        if (respondingToFamilyAlarm()) return true;
        if (familyLife.alarm().shoreResponse()) return true;
        if (firstVoyage.active() && eventAvailable()) return true;
        if (landingFamilyHat()!=null) return true;
        if (selfRescue.shoreRecoveryAvailable()) return true;
        if (waitingOwnHat()!=null) return true;
        if (!available()) return refuseLaunch("unavailable");
        if(birthLaunchEligible()) {
            long now=level().getGameTime();if(cooldown>0||now<nextPlan)return false;
            nextPlan=now+40;birthLaunch=planBirthLaunch();return birthLaunch;
        }
        birthLaunch=false;
        if (firstVoyage.tryStart()) return true;
        if (entity.getVehicle() instanceof HatBoatEntity boat) return boat.protects(entity);
        if (canTendHat()) { hatCareTicks = 0; return true; }
        if (!EcologyRoutines.aquaticVoyage(entity)) return false;
        if (entity.isPassenger()) return refuseLaunch("passenger");
        if (level().isRaining()) return refuseLaunch("rain_shelter");
        if (shoreWorkActive()) return refuseLaunch("shore_work");
        if (entity.isPanicking()) return refuseLaunch("panicking");
        if (!entity.onGround()) return refuseLaunch("not_grounded");
        if (!firstVoyage.mayLaunch()) return refuseLaunch("first_voyage_ineligible");
        if (!entity.life().hasAccessory()) return refuseLaunch("no_accessory");
        if (entity.subspecies().boatId() != null) return refuseLaunch("existing_boat");
        if (cooldown > 0) return refuseLaunch("cooldown");
        if (entity.family().active()) return refuseLaunch("family_active");
        if (entity.family().brood() != null) return refuseLaunch("brood");
        if (entity.life().mealTicks() > 0) return refuseLaunch("eating");
        if (needsBeforeLaunch()) return refuseLaunch("urgent_needs");
        if (entity.life().isSleeping()) return refuseLaunch("sleeping");
        if (WildEvents.reserved(entity)) return refuseLaunch("wild_event");
        long now = level().getGameTime(); if (now < nextPlan) return false;
        nextPlan = now+40;
        // Her home is afloat. Shore visits end as soon as actual food/toilet/hat-care needs allow.
        bank = null; water = null;
        int range=shore.coastal(entity.blockPosition())?16:32;
        var outcome = ShoreBerth.find(level(), new ShoreBerth.Query(entity, null, HatVessel.hatScale(entity), entity.blockPosition(),
                ShoreBerth.Purpose.LAUNCH, null, range, 0, p -> true));
        String refusal = null;
        if (outcome instanceof ShoreBerth.Berth berth) { bank = berth.bank(); water = berth.water(); }
        else {
            refusal = ((ShoreBerth.Refusal) outcome).describe();
            GameplayLog.limited(entity, "aquatic", "shore.noberth", 600, "shore voyage found no berth reason={}", refusal);
        }
        elapsed = launchTicks = provisionTicks = familyWait = 0;
        launchCheck = bank == null ? "no_berth:" + refusal : "bank_selected";
        launchCheckTick = now;
        if (bank != null) GameplayLog.event(entity, "aquatic", "shore voyage planned bank={} water={} paddle={} provisions={}",
                bank, water, hasStick(), provisions.getCount());
        return bank != null;
    }
    private boolean refuseLaunch(String reason) {
        launchCheck = reason; launchCheckTick = level().getGameTime();
        if (entity.profile().aquatic()) GameplayLog.limited(entity, "aquatic", "shore.refused."+reason, 200,
                "shore voyage blocked reason={} cooldown={} alive={} noAI={} command={} operation={} inWater={} fire={} flying={} nutrition={} bladder={}",
                reason, cooldown, entity.isAlive(), entity.isNoAi(), entity.isCommandPlaying(), entity.operations().active(),
                entity.isInWaterOrBubble(), entity.isOnFire(), entity.family().flying(), entity.life().nutrition(), entity.life().bladder());
        return false;
    }
    @Override public boolean canContinueToUse() {
        if (routineAfloat() && !priorityAtSea()) return false;
        if (reactingToBoatMotion()) return true;
        if (respondingToFamilyAlarm()) return true;
        if (familyLife.alarm().shoreResponse()) return true;
        if (firstVoyage.active() && eventAvailable()) return true;
        if (landingFamilyHat()!=null) return true;
        if (selfRescue.shoreRecoveryAvailable()) return true;
        if (waitingOwnHat()!=null) return true;
        if (!available()) return false;
        if(birthLaunch)return birthLaunchEligible()&&bank!=null&&water!=null&&elapsed<720
                &&HatBoatRoute.calmSurface(level(),water)&&entity.life().dryStand(bank);
        if (entity.getVehicle() instanceof HatBoatEntity boat) return boat.protects(entity);
        if (canTendHat()) return hatCareTicks < 48;
        if (bank == null || water == null) return false;
        if (level().isRaining() || shoreWorkActive()) return false;
        if (elapsed >= 720) return refuseLaunch("attempt_timeout");
        if (entity.isPanicking()) return refuseLaunch("panicking");
        if (entity.life().mealTicks() != 0) return refuseLaunch("eating");
        if (entity.life().isSleeping()) return refuseLaunch("sleeping");
        if (!entity.life().hasAccessory()) return refuseLaunch("no_accessory");
        if (needsBeforeLaunch()) return refuseLaunch("urgent_needs");
        if (entity.family().active()) return refuseLaunch("family_active");
        if (!HatBoatRoute.calmSurface(level(), water)) return refuseLaunch("water_changed");
        if (!entity.life().dryStand(bank)) return refuseLaunch("bank_changed");
        return true;
    }
    @Override public boolean requiresUpdateEveryTick() { return true; }
    @Override public void start() { entity.getNavigation().stop(); }
    @Override public void tick() {
        String before = activity;
        boolean ashore = !entity.isPassenger();
        try {
            tickActivity();
        } finally {
            if (ashore && !before.equals(activity)) GameplayLog.limited(entity, "aquatic", "shore.phase."+activity, 100,
                    "shore phase {} -> {} bank={} water={} paddle={} provisions={}", before, activity, bank, water, hasStick(), provisions.getCount());
        }
    }
    private void tickActivity() {
        elapsed++;
        var landingBoat=landingFamilyHat();
        if(landingBoat!=null&&!entity.family().crew().busy()&&!familyLife.alarm().preventsOuting()){
            yieldAllBut(BoatTask.LANDING);
            // The boat controller handles one crossing at a time; landed siblings wait on dry ground.
            if(!entity.getUUID().equals(landingBoat.ownerYukkuri()))entity.getNavigation().stop();
            activity=Behaviors.BOAT_LAND;return;
        }
        if(selfRescue.shoreRecoveryTick()){
            activity=selfRescue.shoreRecoveryActivity();return;
        }
        var waiting=waitingOwnHat();
        if(waiting!=null){
            activity=Behaviors.BOAT_LAUNCH;entity.getNavigation().stop();waiting.helm().hold(entity, Purpose.CARE);
            if(elapsed%10==1)CrewTransfers.embark(entity,waiting);
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
        var launchStand = Vec3.atBottomCenterOf(bank);
        if (!SurfaceApproach.arrived(entity, launchStand)) {
            launchTicks = 0;
            if (entity.distanceToSqr(launchStand) > 2.25 && elapsed%20 == 1 && !entity.life().navigate(bank, .9, false)) {
                refuseLaunch("bank_navigation_failed"); stop();
            } else SurfaceApproach.walk(entity, launchStand, .9);
            return;
        }
        SurfaceApproach.halt(entity);
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
            if (elapsed%20 == 0) for (var child : young)
                if (child.distanceToSqr(entity) > 6.25) gatherAtBank(child, bank);
            if (elapsed%100 == 1) entity.speak("wait_family");
            if (++familyWait > 200) { cooldown = 400; stop(); }
            return;
        }
        var delta = HatBoatRoute.surface(level(), water).subtract(entity.position());
        float yaw = (float) (Math.toDegrees(Math.atan2(delta.z, delta.x))-90);
        entity.setYRot(yaw); entity.yBodyRot = yaw; entity.setYHeadRot(yaw);
        activity = Behaviors.BOAT_LAUNCH;
        if (++launchTicks >= 36) {
            var boat = HullFactory.launchFromBank(entity, bank, water);
            launchCheck = boat == null ? "hull_creation_failed" : "hull_created_boarding_pending";
            launchCheckTick = level().getGameTime();
            if(boat==null&&birthLaunch){failedBirthWater=water;retryBirthWaterAt=level().getGameTime()+400;}
            bank = water = null; launchTicks = 0; birthLaunch=false;
            if (boat == null) { cooldown = 200; activity = Behaviors.IDLE; }
        }
    }
    private boolean needsBeforeLaunch() {
        return entity.life().bladder()>=70&&!entity.life().constipated()
                ||entity.life().nutrition()<25&&provisions.isEmpty()
                ||entity.life().temperature().unsafeSleep();
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
                int before = provisions.getCount();
                var source = item.getUUID();
                int n = Math.min(4-provisions.getCount(),item.getItem().getCount());
                if (provisions.isEmpty()) provisions = item.getItem().split(n); else { provisions.grow(n); item.getItem().shrink(n); }
                if (item.getItem().isEmpty()) item.discard(); provisionSource = null;
                entity.setPersistenceRequired(); entity.getNavigation().stop(); entity.speak("take_food");
                GameplayLog.event(entity, "aquatic", "provisions picked up source={} count={} -> {} item={}", source, before, provisions.getCount(), provisions.getItem());
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
                int before = provisions.getCount();
                if (provisions.isEmpty()) provisions=new ItemStack(Items.SWEET_BERRIES,amount); else provisions.grow(amount);
                GameplayLog.event(entity, "aquatic", "provisions harvested source={} count={} -> {} bushAge={} -> 1", p, before, provisions.getCount(), state.getValue(SweetBerryBushBlock.AGE));
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
            if (entity.hasLineOfSight(item) && giveStick(item.getItem(), false)) {
                GameplayLog.event(entity, "aquatic", "paddle picked up source={} remaining={}", item.getUUID(), item.getItem().getCount());
                if (item.getItem().isEmpty()) item.discard(); return true;
            }
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
            if (level().setBlock(p, Blocks.AIR.defaultBlockState(), 3)) {
                stick = new ItemStack(Items.STICK);
                GameplayLog.event(entity, "aquatic", "paddle harvested source={} block={} count=1", p, state.getBlock());
                return true;
            }
        }
        return false;
    }
    private void aboard(HatBoatEntity boat) {
        entity.getNavigation().stop();
        boolean owner = entity.getUUID().equals(boat.ownerYukkuri());
        activity = Behaviors.BOAT_DRIFT;
        if (boat.unsteadyMotion()) {
            yieldAllBut(BoatTask.UNSTEADY); entity.childhood().stopHatPractice();
            entity.life().wakeInHat(); boat.helm().drift(entity, Purpose.ROUTINE);
            activity = boat.capsizing() ? Behaviors.BOAT_CAPSIZING : owner ? Behaviors.BOAT_STEADY : Behaviors.BOAT_ROCKED;
            return;
        }
        if (familyPaddle.active() && (!owner || !boat.isAfloat() || boat.flooding()>0
                || !boat.cargo().paddle() || care.active() || entity.life().mealTicks()>0 || entity.life().isSleeping()
                || entity.life().nutrition()<60 || entity.life().energy()<40 || entity.life().dryness()>=40
                || entity.isPanicking() || entity.family().laboring() || boat.hasIncoming() || !expectedYoung.isEmpty()
                || level().isNight() || level().isRainingAt(boat.blockPosition())
                || boat.getPassengers().stream().anyMatch(p->p instanceof YukkuriEntity y && (y.isPanicking()||y.life().hasUrgentNeed())))) yieldAllBut(BoatTask.PADDLE_UNAVAILABLE);
        if (shoreForage.active() && (boat.transportingInHat(entity) || care.active() || !boat.isAfloat()
 || boat.flooding()>0 || !boat.cargo().paddle() || boat.cargo().count()>=stockTarget()
                || entity.life().mealTicks()>0 || entity.life().isSleeping() || entity.life().dryness()>=40
                || entity.isPanicking() || entity.family().laboring() || boat.hasIncoming() || !expectedYoung.isEmpty()
                || boat.getPassengers().stream().anyMatch(p->p instanceof YukkuriEntity y && y.isPanicking()))) yieldAllBut(BoatTask.FORAGE_UNAVAILABLE);
        if (boat.transportingInHat(entity)) {
            yieldAllBut(BoatTask.TRANSPORT);
            boat.helm().drift(entity, Purpose.ROUTINE);
            activity = Behaviors.BOAT_CARRY_YOUNG;
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
                    || entity.life().wantsHatNap() || entity.life().energy() <= (entity.family().needsMash() ? 65 : 35)) entity.life().restInHat(boat);
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
        if (entity.family().crew().asCarrier()) {
            yieldAllBut(BoatTask.RESCUE); boat.helm().hold(entity, Purpose.RESCUE); activity=Behaviors.BOAT_RESCUE_YOUNG; return;
        }
        if (entity.family().laboring()) {
            // In labour she cannot leave the delivery (a missed catch is a drowning child); only in the rest between two births
            // may she lift a newborn that is floating at her rim, and the next push waits for it (FamilyLife.advanceLive).
            yieldAllBut(BoatTask.LABOR); boat.helm().drift(entity, Purpose.CARE);
            if (entity.family().deliveryGap() && familyLife.gapRescue(boat)) activity = Behaviors.BOAT_RESCUE_YOUNG;
            return;
        }
        if (entity.life().mealTicks() > 0) { yieldAllBut(BoatTask.EATING); boat.helm().drift(entity, Purpose.ROUTINE); activity = Behaviors.BOAT_EAT; return; }
        if (entity.life().dryness() >= 40 && !care.active()) {
            yieldAllBut(BoatTask.DRINKING);
            boat.helm().drift(entity, Purpose.ROUTINE); entity.life().wakeInHat(); activity = Behaviors.BOAT_DRINK;
            if (elapsed%40 == 0) entity.life().drink(30);
            return;
        }
        if (entity.life().nutrition() < 25 && boat.cargo().count() > 0) {
            yieldAllBut(BoatTask.URGENT_FOOD); entity.life().wakeInHat(); boat.helm().drift(entity, Purpose.ROUTINE);
            if (boat.cargo().feed(entity)) { activity = Behaviors.BOAT_EAT; return; }
        }
        if (level().isRainingAt(boat.blockPosition()) && !shoreVisitRequested && !boat.landing()) requestShoreVisit(false);
        var climate = entity.life().temperature();
        if (!shoreVisitRequested && !boat.landing() && (climate.active() || climate.unsafeSleep() && climate.plan())) {
            yieldAllBut(BoatTask.DISTRESS); entity.life().wakeInHat();
            if (climate.tickGoal()) { activity = climate.activity(); return; }
        }
        if (!shoreVisitRequested && (climate.needsShoreRefuge()
                || !climate.unsafeSleep() && boat.youngPassengers().stream().anyMatch(y -> y instanceof YukkuriEntity young && young.life().temperature().unsafeSleep())))
            requestShoreWork(ShoreWork.THERMAL);
        if ((boat.landing() || shoreVisitRequested) && !familyLife.alarm().preventsOuting() && !entity.family().crew().busy()) {
            yieldAllBut(BoatTask.LANDING); land(boat); return;
        }
        if (EcologyRoutines.aquaticVoyage(entity)) shoreTripTicks = 0;
        // Independent boat use starts in childhood; protection and regrouping remain a family duty.
        // Basic needs above still run while the flotilla waits for a tired or frightened child.
        if (familyLife.tickOwner(boat)) {
            yieldAllBut(BoatTask.FAMILY_ROUTE);
            activity=familyLife.activity();
            yieldAllBut(BoatTask.FAMILY_CARE_YIELD);
            return;
        }
        if (boat.overcrowded()) {
            yieldAllBut(BoatTask.CROWD_GUARD); boat.helm().hold(entity, Purpose.SAFETY);
            activity=Behaviors.BOAT_CROWD_GUARD;
            if(care.tend(boat,false))activity=care.activity();
            return;
        }
        if (familyLife.reactInHat(boat)) {
            yieldAllBut(BoatTask.FAMILY_REACTION); activity=familyLife.activity(); return;
        }
        if (entity.isPanicking() || boat.flooding() > 0) {
            yieldAllBut(BoatTask.DISTRESS); boat.helm().drift(entity, Purpose.SAFETY); activity = Behaviors.BOAT_STEADY;
            if (elapsed%100 == 1) entity.speak("afraid"); return;
        }
        // Rendezvous follows rescue and distress, and retains the route only while both real parents and hulls remain available.
        if (entity.family().tickWaterPartner()) {
            yieldAllBut(BoatTask.FAMILY_ROUTE);
            activity=boat.paddling()?Behaviors.BOAT_PADDLE:Behaviors.BOAT_WAIT_YOUNG;
            return;
        }
        if (elapsed%20==1) collect(boat);
        // Remember who set out together; wait and care for the family without an automatic shore trip.
        expectedYoung.removeIf(id -> {
            if (!(level().getEntity(id) instanceof YukkuriEntity y)
                    || y.isAlive() && y.getVehicle() != boat && !y.aquaticLife().firstVoyage().independent()) return false;
            GameplayLog.event(entity, "aquatic", "boarding wait resolved child={} reason={} hat={}", id,
                    !y.isAlive() ? "dead" : y.getVehicle() == boat ? "aboard" : "independent", boat.getUUID());
            return true;
        });
        if (boat.hasIncoming() || !expectedYoung.isEmpty()) {
            yieldAllBut(BoatTask.FAMILY_WAIT);
            boat.helm().hold(entity, Purpose.CARE); activity = Behaviors.BOAT_WAIT_YOUNG;
            gatherFamily(); boardYoung(boat);
            if (++familyWait > 200) familyDelayed = true;
            if (!boat.hasIncoming() && !entity.family().crew().busy()) {
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
            yieldAllBut(BoatTask.DISTRESS); boat.helm().hold(entity, Purpose.SAFETY); activity = Behaviors.BOAT_STEADY; return;
        }
        if (!care.active() && entity.life().toiletInHat(boat)) { yieldAllBut(BoatTask.TOILET); boat.helm().hold(entity, Purpose.CARE); activity = boat.walkingInHat(entity)
                ? Behaviors.HAT_WALK : Behaviors.BOAT_TOILET; return; }
        if (!shoreForage.active() && hungryCrew && boat.cargo().count()==0 && care.serving().isEmpty() && seekFloatingFood(boat)) {
            yieldAllBut(BoatTask.FLOATING_FOOD);
            activity=Behaviors.BOAT_PADDLE; return;
        }
        boolean returnToBank = !EcologyRoutines.aquaticVoyage(entity);
        if (returnToBank) { land(boat); return; }
        if (seeksShoreCourtship(boat) && requestShoreVisit(true)) { land(boat); return; }
        if (!boat.cargo().paddle()) {
            yieldAllBut(BoatTask.NO_PADDLE);
            if(familyPaddle.recoverOwnPaddle(boat)){activity=Behaviors.BOAT_GATHER;return;}
            boat.helm().drift(entity, Purpose.ROUTINE); activity=boat.drifting()?Behaviors.BOAT_STEADY:Behaviors.BOAT_CALL;
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
            boat.helm().drift(entity, Purpose.ROUTINE); activity = Behaviors.BOAT_EAT; return;
        }
        // Small captains replenish a real reserve while still well fed; parent refuge is the fallback when it runs out.
        boolean lowStores = boat.cargo().count() < Math.min(stockTarget(), boat.cargo().count() + boat.cargo().remainingCapacity())
                && (Seasons.on(level(), entity.blockPosition(), Season.WINTER)
                    || familyLife.followsParent() || boat.getPassengers().stream().anyMatch(p -> p instanceof YukkuriEntity y && y.life().nutrition() < 60));
        if ((shoreForage.active() || lowStores) && shoreForage.tick(boat)) { yieldAllBut(BoatTask.SHORE_FORAGE); activity=shoreForage.activity(); return; }
        if (lowStores && floatingSource == null && !shoreForage.active() && entity.family().adult()
                && elapsed % 80 == 1 && !seekFloatingFood(boat) && requestShoreWork(ShoreWork.FORAGE)) {
            yieldAllBut(BoatTask.LANDING); land(boat); return;
        }
        if (gatherTicks > 0) { yieldAllBut(BoatTask.GATHERING); gatherTicks--; boat.helm().drift(entity, Purpose.ROUTINE); activity = Behaviors.BOAT_GATHER; return; }
        if ((level().isNight() || HatBoatRoute.exposedWeather(level(), boat.blockPosition())) && !entity.life().isSleeping()) {
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
                    boat.helm().drift(entity, Purpose.ROUTINE);activity=Behaviors.BOAT_STEADY;
                    if(level().isNight()&&!HatBoatRoute.exposedWeather(level(), boat.blockPosition())
                            &&HatBoatRoute.calmSurface(level(),BlockPos.containing(boat.getX(),boat.getY()-.12,boat.getZ()))){
                        if(care.tend(boat,true))activity=care.activity();
                        else{entity.life().restInHat(boat);activity=Behaviors.BOAT_SLEEP;}
                    }
                    return;
                }
                if (restWater != null && elapsed%20 == 1) paddleWithinFamily(boat,HatBoatRoute.surface(level(),restWater));
                else if (restWater == null) boat.helm().drift(entity, Purpose.ROUTINE);
                return;
            }
            boat.helm().drift(entity, Purpose.ROUTINE);
            // Let the parent finish the lullaby before starting the physiological sleep clock.
            if (care.tend(boat,level().isNight())) { activity = care.active() ? care.activity() : Behaviors.BOAT_DRIFT; return; }
        }
        if (level().isNight() || entity.life().energy() <= 20 || entity.life().isSleeping() || entity.life().wantsHatNap()) {
            yieldAllBut(BoatTask.REST);
            boat.helm().drift(entity, Purpose.ROUTINE); entity.life().restInHat(boat); activity = Behaviors.BOAT_SLEEP; return;
        }
        restWater = null; restSearchTicks = 0;
        if ((lowStores || floatingSource != null) && seekFloatingFood(boat)) {
            yieldAllBut(BoatTask.FLOATING_FOOD); activity = Behaviors.BOAT_PADDLE; return;
        }
        if (familyPaddle.tick(boat)) { yieldAllBut(BoatTask.PADDLE); activity = familyPaddle.activity(); return; }
        if (daily.tick(boat)) { activity = daily.activity(); return; }
        if (elapsed%40 == 1 && boat.cargo().remainingCapacity() > 0) seekFloatingFood(boat);
        if (!boat.paddling() && elapsed%160 == 1) {
            var around = boat.position().add(entity.getRandom().nextInt(21)-10, 0, entity.getRandom().nextInt(21)-10);
            paddleWithinFamily(boat,around);
        }
        activity = boat.paddling() ? Behaviors.BOAT_PADDLE : Behaviors.BOAT_DRIFT;
    }
    private boolean seekFloatingFood(HatBoatEntity boat) {
        if(!boat.cargo().paddle()||!EventHooks.canEntityGrief(level(),entity))return false;
        if(floatingSource!=null&&(!floatingSource.isAlive()||floatingSource.hasPickUpDelay()||!floatingSource.isInWater()
                ||floatingSource.distanceToSqr(boat)>576||!boat.cargo().canStore(floatingSource.getItem())
                ||!familyLife.allowsDestination(boat,floatingSource.position())))floatingSource=null;
        long now=level().getGameTime();
        if(floatingSource==null&&now>=nextFoodSearch){
            nextFoodSearch=now+40;
            var food=level().getEntitiesOfClass(ItemEntity.class,boat.getBoundingBox().inflate(20,2,20),i->i.isAlive()
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
        return !path.isEmpty() && path.stream().allMatch(p -> familyLife.allowsDestination(boat,p)) && boat.helm().route(entity, Purpose.ROUTINE, path);
    }
    /** A shore trip that cannot finish within this many ticks is completed directly, or given up on, so nobody stays stuck aboard. */
    private static final int SHORE_TRIP_LIMIT = 1200;
    private void land(HatBoatEntity boat) {
        yieldAllBut(BoatTask.LAND); entity.life().wakeInHat(); activity = Behaviors.BOAT_LAND;
        if(shoreTripTicks==0)GameplayLog.limited(entity,"aquatic","shore.start",200,"shore trip started hat={} passengers={} cargo={} paddle={}",
                boat.getUUID(),boat.getPassengers().size(),boat.cargo().count(),boat.cargo().paddle());
        if (++shoreTripTicks > SHORE_TRIP_LIMIT) {
            shoreTripTicks = 0;
            // No usable bank was reached in time: stay afloat in the normal routine and try again later. Nobody is moved.
            AquaDiag.log(entity, "shore.giveup", 0, "no landing within " + SHORE_TRIP_LIMIT + " ticks");
            GameplayLog.event(entity,"aquatic","shore trip gave up hat={} reason=no_landing timeout={} cooldown=600",boat.getUUID(),SHORE_TRIP_LIMIT);
            shoreVisitRequested = false; courtshipShoreVisit=false; cooldown = 600; boat.helm().drift(entity, Purpose.LANDING); return;
        }
        if(boat.landing()){boat.helm().hold(entity, Purpose.LANDING);return;}
        var shore = boat.nearestBank();
        // Close enough to dock: the landing presses the hull against the bank itself.
        if (shore != null && boat.distanceToSqr(Vec3.atBottomCenterOf(shore)) <= 49 && boat.rescue(shore,null)) return;
        // Without a stick the same trip is paddled slowly with the braid (HandPaddle); nothing tows the hull.
        if (elapsed%40 == 1) boat.helm().travel(entity, Purpose.LANDING, shore == null ? rememberedBank == null ? null : Vec3.atBottomCenterOf(rememberedBank) : Vec3.atBottomCenterOf(shore));
        if (!boat.paddling()) { activity = Behaviors.BOAT_CALL; if (elapsed%120 == 1) entity.speak("wait_family"); }
    }
    private void gatherFamily() {
        if (elapsed%20 != 0 || rememberedBank == null) return;
        for (var id : expectedYoung) if (level().getEntity(id) instanceof YukkuriEntity young) gatherAtBank(young, rememberedBank);
    }
    private void gatherAtBank(YukkuriEntity young, BlockPos destination) {
        if (destination == null || !young.isAlive() || young.aquaticLife().firstVoyage().independent()
                || young.isPassenger() || young.family().flying() || young.family().crew().busy() || young.isNoAi() || young.isCommandPlaying()
                || young.operations().active() || young.isPanicking() || young.isInWaterOrBubble() || young.life().hasUrgentNeed()
                || young.life().isSleeping() || young.life().mealTicks() != 0) return;
        // The parent still occupies the launch cell before boarding. Gather beside it instead of routing through her body.
        var candidates=new ArrayList<BlockPos>();
        for(var spot:BlockPos.betweenClosed(destination.offset(-1,0,-1),destination.offset(1,0,1)))
            if(young.life().dryStand(spot))candidates.add(spot.immutable());
        candidates.sort(Comparator.comparingDouble(spot->spot.distSqr(destination)*4+young.distanceToSqr(Vec3.atBottomCenterOf(spot))));
        for(var stand:candidates){
            if(young.distanceToSqr(Vec3.atBottomCenterOf(stand))<=.36)return;
            if(young.life().navigate(stand,.85,false))return;
        }
        GameplayLog.limited(entity, "aquatic", "boarding.bank_unreachable", 200,
                "boarding wait child={} reason=bank_unreachable bank={}", young.getUUID(), destination);
    }
    private List<YukkuriEntity> youngOnBank(HatBoatEntity boat) {
        return level().getEntitiesOfClass(YukkuriEntity.class, boat.getBoundingBox().inflate(4,2,4),
                y -> boat.young(y) && y.isAlive() && !y.isPassenger() && !y.family().flying() && !y.aquaticLife().firstVoyage().independent()
                        && !y.isNoAi() && !y.isCommandPlaying() && !y.operations().active() && !y.isInWaterOrBubble());
    }
    private void boardYoung(HatBoatEntity boat) {
        if (entity.family().crew().busy() || boat.hasIncoming()) return;
        if (elapsed%10 != 0) return;
        for (var young : youngOnBank(boat)) {
            if (expectedYoung.contains(young.getUUID()) && !young.isPanicking() && young.life().mealTicks() == 0
                    && !young.life().hasUrgentNeed() && !young.life().isSleeping()
                    && young.distanceToSqr(boat) <= 6.25 && young.hasLineOfSight(boat) && young.onGround()) {
                // Making room is an explicit decision: an idle sibling may be moved, food never is.
                if(!boat.makeRoom(young,false)){
                    GameplayLog.limited(entity, "aquatic", "boarding.no_room", 200,
                            "boarding wait child={} reason=no_room hat={} {}", young.getUUID(), boat.getUUID(), boat.roomDebug(young));
                    continue;
                }
                if (young.childhood().canJump() && young.family().stage() != FamilyLife.Stage.BABY) {
                    CrewTransfers.boardFromShore(young, boat, false);
                } else if (CrewTransfers.liftAboard(young, boat).ok()) {
                    GameplayLog.event(entity, "aquatic", "dry child boarding lift started child={} hat={}", young.getUUID(), boat.getUUID());
                } else if (!familyLife.alarm().approachRescue(boat, young)) {
                    GameplayLog.limited(entity, "aquatic", "boarding.no_contact_route", 200,
                            "boarding wait child={} reason=no_contact_route hat={} childPos={}", young.getUUID(), boat.getUUID(), young.position());
                }
                break;
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
            case FAMILY_ROUTE, CROWD_GUARD, FAMILY_REACTION -> {
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
        if (bank != null || water != null) GameplayLog.limited(entity, "aquatic", "shore.stopped", 200,
                "shore voyage stopped phase={} elapsed={} bank={} water={} lastCheck={} checkTick={}",
                activity, elapsed, bank, water, launchCheck, launchCheckTick);
        entity.childhood().stopHatPractice();
        yieldAllBut(BoatTask.STOP);
        bank = water = paddleStand = null; provisionSource = floatingSource = null; nextFoodSearch=0; launchTicks = 0; birthLaunch=false; activity = Behaviors.IDLE; entity.getNavigation().stop();
        if (entity.getVehicle() instanceof HatBoatEntity boat && entity.getUUID().equals(boat.ownerYukkuri())) boat.helm().drift(entity, Purpose.ROUTINE);
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
        if (!provisions.isEmpty()) provisions.setCount(Math.min(8,provisions.getCount()));
        expectedYoung.clear(); restWater = null; restSearchTicks = 0;
        for (var raw : tag.getList("ExpectedYoung",Tag.TAG_COMPOUND))
            if (raw instanceof CompoundTag entry && entry.hasUUID("Id") && expectedYoung.size() < 16) expectedYoung.add(entry.getUUID("Id"));
        rememberedBank = tag.contains("Bank") && tag.getString("Dimension").equals(entity.level().dimension().location().toString()) ? BlockPos.of(tag.getLong("Bank")) : null;
    }
}
