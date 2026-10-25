package io.github.longshou.mcyukkuri.subspecies;

import io.github.longshou.mcyukkuri.status.GameplayLog;
import io.github.longshou.mcyukkuri.subspecies.boat.AfloatAgenda;
import io.github.longshou.mcyukkuri.subspecies.boat.HullFactory;
import io.github.longshou.mcyukkuri.subspecies.boat.ShoreBerth;
import io.github.longshou.mcyukkuri.subspecies.boat.VoyageController;
import io.github.longshou.mcyukkuri.subspecies.boat.VoyagePhase;

import io.github.longshou.mcyukkuri.life.EcologyRoutine;
import io.github.longshou.mcyukkuri.life.EcologyRoutines;
import io.github.longshou.mcyukkuri.entity.Behaviors;
import io.github.longshou.mcyukkuri.social.Bonds;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import java.util.List;
import java.util.Locale;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.EnumMap;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SweetBerryBushBlock;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.EventHooks;
import io.github.longshou.mcyukkuri.event.WildEvents;
import io.github.longshou.mcyukkuri.life.SurfaceApproach;
import io.github.longshou.mcyukkuri.season.Season;
import io.github.longshou.mcyukkuri.season.Seasons;

/**
 * The aquatic captain's side of her life: provisions and paddle, the shore-side goals and the two goal wrappers around the rest.
 * The lifecycle is {@link VoyageController}'s stored phase machine; what a rider does while afloat is the {@link AfloatAgenda}.
 * This class is the surface the other domains know (shore requests, food, paddle) and the hat-care errand ashore.
 *
 * <p>The priority-3 goal runs the transactional phases, the shore-side responses and the urgent afloat activities (incidents,
 * threats, labor); the priority-8 goal runs the rest of the agenda while the captain is AFLOAT, or a passenger rides. Neither goal
 * tears the other's work down: an activity that stops being ticked simply lapses.
 */
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
    private final VoyageController voyage;
    private final AfloatAgenda agenda;
    private final AfloatActivities activities;
    private BlockPos rememberedBank, paddleStand;
    private int hatCareTicks;
    private boolean hatCareDue, nightSettled;
    public enum ShoreWork { SETTLEMENT, INDEPENDENCE, FORAGE, HUMAN, PLAY, THERMAL }
    /** Short-lived requests owned and renewed by the existing domain task, never a second task queue. */
    private final EnumMap<ShoreWork, Long> shoreWork = new EnumMap<>(ShoreWork.class);
    private ItemStack provisions = ItemStack.EMPTY;
    /** A real pre-launch paddle, persisted separately from the boat's cargo. */
    private ItemStack stick = ItemStack.EMPTY;
    private ItemEntity provisionSource;
    private String activity = Behaviors.IDLE;
    private long activityTick = Long.MIN_VALUE;
    /** An urgent activity that wants the turn but never takes it must not hold the priority-3 goal against the routine one. */
    private long urgentBlockedUntil;
    private int urgentMisses;
    // Observations only: neither persisted nor consulted by gameplay decisions.
    private String launchCheck = "not_evaluated";
    private long launchCheckTick = -1;
    private UUID observedOwnBoat;

    public AquaticLife(YukkuriEntity entity) {
        this.entity = entity; care = new HatFamilyCare(entity); firstVoyage = new FirstVoyage(entity); shore = new AquaticShore(entity); shoreForage = new HatShoreForage(entity);
        familyLife = new AquaticFamilyLife(entity); daily = new AquaticDailyLife(entity); familyPaddle = new HatFamilyPaddle(entity);
        selfRescue=new AquaticSelfRescue(entity);
        voyage = new VoyageController(entity, this);
        agenda = new AfloatAgenda(entity);
        activities = AfloatActivities.register(agenda, entity, this);
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK, Flag.JUMP));
    }
    private ServerLevel level() { return (ServerLevel) entity.level(); }
    /** What the presentation reads: the behaviour shown in the last few ticks, IDLE when none of her goals has run lately. */
    public String activity() { return level().getGameTime() - activityTick > 2 ? Behaviors.IDLE : activity; }
    public boolean active() { return !activity().equals(Behaviors.IDLE); }
    private void show(String behavior) { activity = behavior; activityTick = level().getGameTime(); }
    public VoyageController voyage() { return voyage; }
    public AfloatAgenda agenda() { return agenda; }
    public boolean hasStick() { return !stick.isEmpty(); }
    /** Count the same return paddle while it is held, stowed, or still aboard the child's own hull. */
    boolean hasPaddleForReturn() {
        if (hasStick() || firstVoyage.hasRetainedPaddle()) return true;
        UUID id=entity.life().custody().boat();
        if(id==null||!(entity.level() instanceof ServerLevel server))return false;
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
        String state="activity="+activity()+" firstVoyage="+firstVoyage.status()+" voyage="+voyage.phase()
                +" boardingBank="+rememberedBank+" shoreWork="+shoreWork.keySet()+" climate="+(entity.life().temperature().active()?entity.life().temperature().activity():"idle")
                +" food="+availableFood()+" stockTarget="+stockTarget()+" forage="+entity.forageTrip().phase();
        HatBoatEntity boat=entity.getVehicle() instanceof HatBoatEntity ridden ? ridden : null;
        if(boat==null)boat=HatBoatEntity.owned(entity);
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
    /** She rests (asleep or covering her sleeping young), which lets her young rest as well. */
    public boolean resting() { return nightSettled || Behaviors.BOAT_SLEEP.equals(activity()) || Behaviors.BOAT_LULLABY.equals(activity()); }
    void nightSettled(boolean settled) { nightSettled = settled; }
    public boolean awaiting(YukkuriEntity young) { return voyage.awaiting(young.getUUID()); }
    public BlockPos boardingBank() { return voyage.bank() != null ? voyage.bank() : rememberedBank; }
    public void rememberBank(BlockPos bank) { if (bank != null) rememberedBank = bank; }
    /** The hat is back: the voyage controller has closed the landing (see {@link VoyageController#landed}). */
    public void landed() { voyage.landed(); }
    /** Bookkeeping of a completed landing: the hat needs tending and the shore errands that asked for it are given their time. */
    public void afterLanding() {
        hatCareDue = true;
        long now = level().getGameTime();
        shoreWork.replaceAll((work, until) -> now + 200);
    }
    /** Whatever the trip prepared on the bank is dropped when the voyage returns to ASHORE. */
    public void voyageEnded() {
        provisionSource = null; paddleStand = null; nightSettled = false;
        activities.clear();
    }

    // ------------------------------------------------------------------------------------------------ shore requests
    /** Request real shore travel; the normal water route, landing contact and accessory rules still apply. */
    public boolean requestShoreVisit() { return voyage.requestShore("visit", level().getGameTime() + 1200) == VoyageController.Answer.ACCEPTED; }
    public boolean shoreVisitRequested() { return voyage.shoreVisit(); }
    public boolean requestShoreWork(ShoreWork work) {
        if (entity.level().isClientSide || !entity.profile().aquatic()) return false;
        if (!entity.isPassenger()) {
            if (!entity.onGround()) return false;
            holdShoreWork(work); return true;
        }
        if (voyage.requestShore(work.name().toLowerCase(Locale.ROOT), level().getGameTime() + 1200) != VoyageController.Answer.ACCEPTED) return false;
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
    /** Server-side voyage and water-birth readout ({@code water.launch}); it never plans, launches, or searches for water. */
    public String waterBirthDetail(){
        String spawn=!afloatSpawn?"idle":naturalAfloatSpawn?"natural_pending":"pending";
        return voyage.detail()+" spawn="+spawn+" spawnAge="+entity.tickCount+" required="+entity.family().waterBirthRequired()

                +" agenda="+agenda.currentId()+" activity="+activity()+" launchCheck="+launchCheck+" checkTick="+launchCheckTick
                +" heldPaddle="+hasStick()+" provisions="+provisions.getCount();
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
                || entity.life().custody().custodied() || !entity.life().hasAccessory() || !firstVoyage.mayLaunch()
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
    public void clock() { EcologyRoutines.tick(this); }
    // This clock serves ordinary passengers and hat owners as well as aquatic voyagers.
    @Override public boolean supportsPassiveTick() { return true; }
    @Override public void passiveTick() {
        if (!voyage.shoreVisit()) shoreWork.values().removeIf(until -> until <= level().getGameTime());
        // A real carried forage trip survives temporary hunger or sleep interruptions on shore.
        if (entity.profile().aquatic() && !entity.isPassenger() && entity.forageTrip().outing()
                && entity.tickCount % 40 == 0) holdShoreWork(ShoreWork.FORAGE);
        UUID ownBoat = entity.getVehicle() instanceof HatBoatEntity hat && entity.getUUID().equals(hat.ownerYukkuri())
                ? hat.getUUID() : null;
        if (!Objects.equals(ownBoat, observedOwnBoat)) {
            GameplayLog.event(entity, "aquatic", "own-hat passenger changed {} -> {}", observedOwnBoat, ownBoat);
            observedOwnBoat = ownBoat;
        }
        if (afloatSpawn && entity.tickCount >= 1 && entity.tickCount % 10 == 1 && !entity.level().isClientSide) attemptAfloatSpawn();
        if (!entity.level().isClientSide) voyage.clock();
        shore.tick(); care.clock(); firstVoyage.clock(); familyLife.clock(); daily.clock();
        selfRescue.clock();
        // A cancelled trip must not leave her starving while she still carries real provisions.
        if (!provisions.isEmpty() && available() && !entity.isPassenger() && !entity.life().isSleeping()
                && entity.life().nutrition() < 30 && entity.life().beginMeal(provisions.copyWithCount(1),null,true)) provisions.shrink(1);
    }
    /** Player hands a stick to an aquatic yukkuri (the owner aboard keeps it as the paddle). Accepted or refused, the click ends here. */
    public static void deliverStick(PlayerInteractEvent.EntityInteract event) {
        if(event.getHand()!=InteractionHand.MAIN_HAND || !(event.getTarget() instanceof YukkuriEntity y)
                || !event.getItemStack().is(Items.STICK) || event.getEntity().isShiftKeyDown() || !y.profile().aquatic())return;
        if(!event.getLevel().isClientSide){
            if(y.getVehicle() instanceof HatBoatEntity boat && y.getUUID().equals(boat.ownerYukkuri()))
                boat.cargo().givePaddle(event.getItemStack(),event.getEntity().getAbilities().instabuild);
            else y.aquaticLife().giveStick(event.getItemStack(),event.getEntity().getAbilities().instabuild);
        }
        event.setCancellationResult(InteractionResult.sidedSuccess(event.getLevel().isClientSide));event.setCanceled(true);
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
    /** The hull is set down for this captain: her paddle and provisions go into it, and the bank she left from is remembered. */
    public void equip(HatBoatEntity boat) {
        if (!stick.isEmpty() && boat.cargo().givePaddle(stick, false)) stick = ItemStack.EMPTY;
        if (!provisions.isEmpty()) boat.cargo().store(provisions, provisions.getCount(), false);
        rememberedBank = entity.getVehicle() instanceof HatBoatEntity home && home!=boat && home.getControllingPassenger() instanceof YukkuriEntity parent
                ? parent.aquaticLife().boardingBank() : entity.blockPosition();
        GameplayLog.event(entity,"aquatic","voyage equipped hat={} aboard={} cargo={} paddle={}",
                boat.getUUID(),entity.getVehicle()==boat,boat.cargo().count(),boat.cargo().paddle());
    }
    public void dropStick() {
        selfRescue.stop();
        familyPaddle.stop(); care.cancel(); shoreForage.stop(); agenda.halt("trip_ended");
        firstVoyage.dropStoredCargo();
        if (!stick.isEmpty()) {
            var dropped = entity.spawnAtLocation(stick); stick = ItemStack.EMPTY;
            if (dropped != null) familyPaddle.rememberOwnDroppedPaddle(dropped.getUUID());
        }
        if (!provisions.isEmpty()) { var food = provisions; provisions = ItemStack.EMPTY; entity.spawnAtLocation(food); }
    }
    /** The children of this captain who would go with her: alive, small, not yet sailing alone, within sixteen blocks. */
    public List<YukkuriEntity> nearbyYoung() {
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
    public boolean birthLaunchEligible() {
        return entity.family().needsWaterBirthBoat() && entity.onGround() && !entity.isPassenger() && !entity.isVehicle()
                && !entity.isPanicking() && !entity.life().isSleeping() && entity.life().mealTicks()==0
                && firstVoyage.mayLaunch() && entity.life().hasAccessory() && !entity.life().custody().custodied();
    }
    /** Keep the father's escort launch at the mother's shore when the confirmed pair is still together. */
    private BlockPos birthLaunchAnchor() {
        UUID partner=entity.family().partner();
        if(partner!=null&&level().getEntity(partner) instanceof YukkuriEntity mother&&mother.family().waterBirthRequired())return mother.blockPosition();
        return entity.blockPosition();
    }
    /** The launch bank and its water, or the real refusal. A birth launch lies at the mother's shore. */
    public ShoreBerth.Outcome planLaunch(boolean birth) {
        if (birth) {
            var query=new ShoreBerth.Query(entity,null,HatVessel.hatScale(entity),birthLaunchAnchor(),ShoreBerth.Purpose.LAUNCH,null,16,0,
                    p->true);
            var outcome=ShoreBerth.find(level(),query);
            if(outcome instanceof ShoreBerth.Refusal refusal)
                GameplayLog.limited(entity,"aquatic","birth.noberth",400,"birth launch found no berth reason={}",refusal.describe());
            return outcome;
        }
        int range=shore.coastal(entity.blockPosition())?16:32;
        var outcome = ShoreBerth.find(level(), new ShoreBerth.Query(entity, null, HatVessel.hatScale(entity), entity.blockPosition(),
                ShoreBerth.Purpose.LAUNCH, null, range, 0, p -> true));
        if (outcome instanceof ShoreBerth.Refusal refusal)
            GameplayLog.limited(entity, "aquatic", "shore.noberth", 600, "shore voyage found no berth reason={}", refusal.describe());
        return outcome;
    }
    public void launchCheck(String value) { launchCheck = value; launchCheckTick = level().getGameTime(); }
    public boolean availableForVoyage() { return available(); }
    private boolean available() {
        return entity.isAlive() && !entity.isNoAi() && !entity.isCommandPlaying() && !entity.operations().active()
                && !entity.isInWaterOrBubble() && !entity.isOnFire() && !entity.family().flying();
    }
    private boolean routineAfloat() {
        return available() && entity.getVehicle() instanceof HatBoatEntity boat && boat.protects(entity);
    }
    private HatBoatEntity ridden() { return entity.getVehicle() instanceof HatBoatEntity boat ? boat : null; }
    private boolean ownsRidden(HatBoatEntity boat) { return entity.getUUID().equals(boat.ownerYukkuri()); }
    /** An urgent thing is under way or wanted aboard (an incident, a threat, labor): the priority-3 goal takes the agenda. */
    private boolean urgentAboard() {
        if (!routineAfloat() || level().getGameTime() < urgentBlockedUntil) return false;
        var boat = ridden();
        boolean owner = ownsRidden(boat);
        if (owner && voyage.phase() != VoyagePhase.AFLOAT) return false;
        return agenda.wantsAtMost(boat, owner, AfloatAgenda.URGENT);
    }
    private boolean routineWanted() {
        if (!routineAfloat() || firstVoyage.active()) return false;
        var boat = ridden();
        if (ownsRidden(boat) && voyage.phase() != VoyagePhase.AFLOAT) return false;
        return !urgentAboard();
    }
    /** The routine afloat goal (priority 8): the rest of the agenda, a separate selector identity over the same agenda. */
    public Goal routineGoal() {
        return new Goal() {
            { setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK, Flag.JUMP)); }
            @Override public boolean canUse() { return routineWanted(); }
            @Override public boolean canContinueToUse() { return routineWanted(); }
            @Override public boolean requiresUpdateEveryTick() { return true; }
            @Override public void tick() { runAgenda(Double.MAX_VALUE); }
        };
    }
    private void runAgenda(double cap) {
        var boat = ridden();
        if (boat == null) return;
        boolean owner = ownsRidden(boat);
        entity.getNavigation().stop();
        if (owner && !boat.isAfloat() && !boat.unsteadyMotion()) { show(Behaviors.IDLE); return; }
        boolean took = agenda.tick(boat, owner, cap);
        if (cap < Double.MAX_VALUE) {
            if (took) urgentMisses = 0;
            else if (++urgentMisses >= 20) {
                urgentMisses = 0; urgentBlockedUntil = level().getGameTime() + 100;
                GameplayLog.limited(entity, "aquatic", "urgent.idle", 400, "urgent activity wanted the turn but never took it; routine goal resumes");
            }
        }
        String shown = agenda.shown(level().getGameTime());
        show(took ? (shown.equals(Behaviors.IDLE) ? Behaviors.BOAT_DRIFT : shown) : owner ? Behaviors.BOAT_DRIFT : Behaviors.IDLE);
    }

    // ------------------------------------------------------------------------------------------------ priority-3 goal
    @Override public boolean canUse() {
        if (voyage.phase().transactional()) return voyage.capable();
        if (familyLife.alarm().shoreResponse()) return true;
        if (firstVoyage.active() && eventAvailable()) return true;
        if (urgentAboard()) return true;
        if (!available()) return refuseLaunch("unavailable");
        if (firstVoyage.tryStart()) return true;
        if (entity.isPassenger()) return false;
        if (canTendHat()) { hatCareTicks = 0; return true; }
        if (!EcologyRoutines.aquaticVoyage(entity)) return false;
        return voyage.wantsLaunch();
    }
    /** Why this captain cannot start a launch now, or null. Birth launches have their own eligibility and are not asked here. */
    public String launchRefusal(int cooldown) {
        String why = null;
        if (!EcologyRoutines.aquaticVoyage(entity)) why = "not_aquatic";
        else if (entity.isPassenger()) why = "passenger";
        else if (level().isRaining()) why = "rain_shelter";
        else if (shoreWorkActive()) why = "shore_work";
        else if (entity.isPanicking()) why = "panicking";
        else if (!entity.onGround()) why = "not_grounded";
        else if (!firstVoyage.mayLaunch()) why = "first_voyage_ineligible";
        else if (!entity.life().hasAccessory()) why = "no_accessory";
        else if (entity.life().custody().custodied()) why = "existing_boat";
        else if (cooldown > 0) why = "cooldown";
        else if (entity.family().active()) why = "family_active";
        else if (entity.family().brood() != null) why = "brood";
        else if (entity.life().mealTicks() > 0) why = "eating";
        else if (needsBeforeLaunch()) why = "urgent_needs";
        else if (entity.life().isSleeping()) why = "sleeping";
        else if (WildEvents.reserved(entity)) why = "wild_event";
        if (why != null) refuseLaunch(why);
        return why;
    }
    /** Why a launch being prepared has to stop (the conditions that started it no longer hold), or null. */
    public String launchAbortReason(boolean birth) {
        if (!available()) return "unavailable";
        if (birth) return birthLaunchEligible() ? null : "birth_ineligible";
        if (level().isRaining() || shoreWorkActive()) return "rain_or_shore_work";
        if (entity.isPanicking()) return "panicking";
        if (entity.life().mealTicks() != 0) return "eating";
        if (entity.life().isSleeping()) return "sleeping";
        if (!entity.life().hasAccessory()) return "no_accessory";
        if (needsBeforeLaunch()) return "urgent_needs";
        if (entity.family().active()) return "family_active";
        return null;
    }
    private boolean refuseLaunch(String reason) {
        launchCheck = reason; launchCheckTick = level().getGameTime();
        if (entity.profile().aquatic()) GameplayLog.limited(entity, "aquatic", "shore.refused."+reason, 200,
                "shore voyage blocked reason={} cooldown={} alive={} noAI={} command={} operation={} inWater={} fire={} flying={} nutrition={} bladder={}",
                reason, voyage.cooldown(), entity.isAlive(), entity.isNoAi(), entity.isCommandPlaying(), entity.operations().active(),
                entity.isInWaterOrBubble(), entity.isOnFire(), entity.family().flying(), entity.life().nutrition(), entity.life().bladder());
        return false;
    }
    @Override public boolean canContinueToUse() {
        if (voyage.phase().transactional()) return voyage.capable();
        if (familyLife.alarm().shoreResponse()) return true;
        if (firstVoyage.active() && eventAvailable()) return true;
        if (urgentAboard()) return true;
        if (canTendHat()) return hatCareTicks < 48;
        return false;
    }
    @Override public boolean requiresUpdateEveryTick() { return true; }
    @Override public void start() { entity.getNavigation().stop(); }
    @Override public void tick() {
        if (voyage.phase().transactional()) { show(voyage.tick()); return; }
        if (familyLife.alarm().shoreResponse() && familyLife.alarm().tickResponse()) { show(familyLife.alarmActivity()); return; }
        if (urgentAboard()) { runAgenda(AfloatAgenda.URGENT); return; }
        if (firstVoyage.active() || firstVoyage.tryStart()) {
            firstVoyage.tick();
            show(familyLife.alarm().responding() ? familyLife.alarmActivity() : firstVoyage.activity());
            return;
        }
        if (canTendHat()) {
            show(Behaviors.AQUATIC_HAT_CARE); entity.getNavigation().stop();
            if (++hatCareTicks >= 48) { entity.life().applyEnvironment(-8,0,0); hatCareDue = false; }
        }
    }
    private boolean needsBeforeLaunch() {
        return entity.life().bladder()>=70&&!entity.life().constipated()
                ||entity.life().nutrition()<25&&provisions.isEmpty()
                ||entity.life().temperature().unsafeSleep();
    }
    /** Carry a small real food reserve to the bank; no provisions appear merely because a voyage starts. */
    public boolean prepareFood(int elapsed) {
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
    public void seekStick(int elapsed) {
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
    /** Walk a child to a free standing cell beside the launch bank (the captain's own cell is left to her). */
    public void gatherAtBank(YukkuriEntity young, BlockPos destination) {
        if (destination == null || !young.isAlive() || young.aquaticLife().firstVoyage().independent()
                || young.isPassenger() || young.family().flying() || young.family().crew().busy() || young.isNoAi() || young.isCommandPlaying()
                || young.operations().active() || young.isPanicking() || young.isInWaterOrBubble() || young.life().hasUrgentNeed()
                || young.life().isSleeping() || young.life().mealTicks() != 0) return;
        // The parent still occupies the launch cell before boarding. Gather beside it instead of routing through her body.
        var candidates=new java.util.ArrayList<BlockPos>();
        for(var spot:BlockPos.betweenClosed(destination.offset(-1,0,-1),destination.offset(1,0,1)))
            if(young.life().dryStand(spot))candidates.add(spot.immutable());
        candidates.sort(Comparator.comparingDouble(spot->spot.distSqr(destination)*4+young.distanceToSqr(Vec3.atBottomCenterOf(spot))));
        for(var stand:candidates){
            if(young.distanceToSqr(Vec3.atBottomCenterOf(stand))<=.36)return;
            if(young.life().navigate(stand,.85,false))return;
        }
        GameplayLog.limited(entity, "aquatic", "muster.bank_unreachable", 200,
                "muster child={} reason=bank_unreachable bank={}", young.getUUID(), destination);
    }
    @Override public void stop() {
        entity.childhood().stopHatPractice();
        entity.getNavigation().stop();
    }
    private boolean eventAvailable() {
        return entity.isAlive() && !entity.isNoAi() && !entity.isCommandPlaying() && !entity.operations().active()
                && !entity.isInWaterOrBubble() && !entity.isOnFire()
                && (!entity.isPanicking() || firstVoyage.returningToParent() && entity.shelteredInHat());
    }
    public CompoundTag save() {
        var tag = new CompoundTag(); tag.putBoolean("Stick", !stick.isEmpty());
        if (!stick.isEmpty()) tag.put("StickItem", stick.save(entity.registryAccess()));
        tag.putBoolean("HatCareDue",hatCareDue);
        tag.put("Voyage",voyage.save());
        tag.put("Care",care.save());
        tag.put("FirstVoyage",firstVoyage.save());
        tag.put("WaterFamily",familyLife.save()); tag.put("WaterDaily",daily.save());
        tag.put("FamilyPaddle",familyPaddle.save());
        tag.put("SelfRescue",selfRescue.save());
        tag.put("Shore",shore.save());
        if (!provisions.isEmpty()) tag.put("Provisions",provisions.save(entity.registryAccess()));
        if (rememberedBank != null) { tag.putLong("Bank", rememberedBank.asLong()); tag.putString("Dimension", entity.level().dimension().location().toString()); }
        return tag;
    }
    public void load(CompoundTag tag) {
        shoreForage.stop();
        if (tag.contains("StickItem", Tag.TAG_COMPOUND)) {
            var stored = ItemStack.parseOptional(entity.registryAccess(), tag.getCompound("StickItem"));
            stick = stored.is(Items.STICK) ? stored.copyWithCount(1) : ItemStack.EMPTY;
        } else stick = tag.getBoolean("Stick") ? new ItemStack(Items.STICK) : ItemStack.EMPTY;
        hatCareDue = tag.getBoolean("HatCareDue"); hatCareTicks = 0;
        voyage.load(tag.getCompound("Voyage"));
        care.load(tag.getCompound("Care")); provisions = ItemStack.parseOptional(entity.registryAccess(),tag.getCompound("Provisions"));
        firstVoyage.load(tag.getCompound("FirstVoyage"));
        familyLife.load(tag.getCompound("WaterFamily")); daily.load(tag.getCompound("WaterDaily"));
        familyPaddle.load(tag.getCompound("FamilyPaddle"));
        selfRescue.load(tag.getCompound("SelfRescue"));
        shore.load(tag.getCompound("Shore"));
        if (!provisions.isEmpty()) provisions.setCount(Math.min(8,provisions.getCount()));
        rememberedBank = tag.contains("Bank") && tag.getString("Dimension").equals(entity.level().dimension().location().toString()) ? BlockPos.of(tag.getLong("Bank")) : null;
    }
}
