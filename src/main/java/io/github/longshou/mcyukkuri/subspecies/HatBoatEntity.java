package io.github.longshou.mcyukkuri.subspecies;

import io.github.longshou.mcyukkuri.subspecies.crew.CrewTransfer;
import io.github.longshou.mcyukkuri.subspecies.boat.HullFootprint;
import io.github.longshou.mcyukkuri.subspecies.boat.HullHazards;
import io.github.longshou.mcyukkuri.subspecies.boat.HullHelm;
import io.github.longshou.mcyukkuri.subspecies.crew.WaterIncidents;
import io.github.longshou.mcyukkuri.subspecies.boat.ShoreBerth;
import io.github.longshou.mcyukkuri.subspecies.crew.Admission;
import io.github.longshou.mcyukkuri.subspecies.crew.Body;
import io.github.longshou.mcyukkuri.subspecies.crew.DeckLayout;
import io.github.longshou.mcyukkuri.subspecies.crew.DeckLayout.Kind;
import io.github.longshou.mcyukkuri.subspecies.crew.HatDeck;
import io.github.longshou.mcyukkuri.subspecies.crew.Purpose;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import net.minecraft.world.item.ItemStack;
import io.github.longshou.mcyukkuri.mind.EmotionEvent;

import io.github.longshou.mcyukkuri.subspecies.crew.CrewState;
import io.github.longshou.mcyukkuri.subspecies.crew.CrewTransfers;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.family.FamilyData;
import io.github.longshou.mcyukkuri.family.FamilyLife;
import io.github.longshou.mcyukkuri.family.GrowthSize;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import java.util.Optional;
import java.util.List;
import java.util.UUID;
import io.github.longshou.mcyukkuri.life.SurfaceApproach;
import io.github.longshou.mcyukkuri.life.YukkuriLocomotion;
import net.neoforged.neoforge.common.CommonHooks;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import java.util.Locale;
import net.minecraft.nbt.ListTag;

/** One real hat (her own hat turned upside down, its soft crown pressed down: HatVessel) and one Marisa owner. Afloat it is
 *  the aquatic Marisa's boat: she rests in its opening at the stern and her young ride in its dry inside, close before her at night.
 *  Every seat on it is a {@link HatDeck} seat and every temporary need a deck lease; the larder holds food only in the crown.
 *  Unloading never means rescue or death. */
public final class HatBoatEntity extends Entity implements HatBoatView {
    private final CrewState crewState = new CrewState();
    /** Why the last care lift was refused, for failure reports. */
    String carryPlanDebug = "not planned";
    final HatStations stations = new HatStations(this);
    final HatHullMotion hullMotion = new HatHullMotion(this);
    private final HullHazards hazards = new HullHazards(this);
    // Keep protected synchronized data and physical-hat ownership behind entity-owned hooks.
    boolean hasServiceSeats() { return stations.hasServiceSeats(); }
    boolean hasReturningSeats() { return stations.hasReturningSeats(); }
    boolean ownerUsesToiletSeat() { return stations.ownerUsesToiletSeat(); }
    String serviceSeatIdsDebug() { return stations.serviceSeatIdsDebug(); }
    String returningSeatIdsDebug() { return stations.returningSeatIdsDebug(); }
    public CompoundTag motionData() { return entityData.get(MOTION); }
    public void syncMotion(CompoundTag data) {
        entityData.set(MOTION, data.copy());
    }
    CompoundTag dipData() { return entityData.get(DIP); }
    void syncDip(CompoundTag data) { entityData.set(DIP, data); }
    void markHullHurt() { markHurt(); }
    public void syncHullScale(float scale) { entityData.set(SCALE, scale); }
    public void syncOwner(Optional<UUID> owner) { entityData.set(OWNER, owner); }
    public UUID launchToken() { return accessoryToken; }
    public void launchToken(UUID token) { accessoryToken = token; }
    public void launchRole(HatVessel.Role next) { role = next; }
    void clearCrew() { crew.clear(); }

    public float previousYaw() { return yRotO; }
    public CompoundTag deckSnapshot() { return entityData.get(DECK).copy(); }
    public CompoundTag cargoSnapshot() { return entityData.get(CARGO).copy(); }
    public CompoundTag motionSnapshot() { return entityData.get(MOTION).copy(); }
    /** CRADLE: a Marisa's hat turned over on dry ground, its soft crown pressed flat under it, to catch young. MOORED: an
     *  aquatic Marisa's hat floated at a bank to catch them, held in place. Ordinals are synced: append only. */
    public enum State { AFLOAT, BEACHED, CAPSIZED, RESCUED, REMOVED, CRADLE, MOORED }
    private static final EntityDataAccessor<Integer> STATE = SynchedEntityData.defineId(HatBoatEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Optional<UUID>> OWNER = SynchedEntityData.defineId(HatBoatEntity.class, EntityDataSerializers.OPTIONAL_UUID);
    private static final EntityDataAccessor<Float> SCALE = SynchedEntityData.defineId(HatBoatEntity.class, EntityDataSerializers.FLOAT);
    /** The physical headwear identity stays with the hull while its owner is unloaded. */
    private static final EntityDataAccessor<Boolean> AQUATIC_HULL = SynchedEntityData.defineId(HatBoatEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Integer> ACCESSORY_STAGE = SynchedEntityData.defineId(HatBoatEntity.class, EntityDataSerializers.INT);
    /** The deployed hat's own wear (headwear damage 0 intact, 1 frayed, 2 torn), drawn on the hull like a worn hat. */
    private static final EntityDataAccessor<Integer> HULL_DAMAGE = SynchedEntityData.defineId(HatBoatEntity.class, EntityDataSerializers.INT);
    /** The deck snapshot: for each body aboard its seat id, seat kind and place (hat units), any walk in progress, the load,
     *  a lift in progress and the crowding display. */
    private static final EntityDataAccessor<CompoundTag> DECK = SynchedEntityData.defineId(HatBoatEntity.class, EntityDataSerializers.COMPOUND_TAG);
    /** Every transfer that touches this hull: the one CrewState record (id, mode, body, carrier, curve, clock, phase, contact). */
    private static final EntityDataAccessor<CompoundTag> CREW = SynchedEntityData.defineId(HatBoatEntity.class, EntityDataSerializers.COMPOUND_TAG);
    private static final EntityDataAccessor<CompoundTag> CARGO = SynchedEntityData.defineId(HatBoatEntity.class, EntityDataSerializers.COMPOUND_TAG);
    /** The hull's load in units (bodies, food, the stick): the client rides the waterline lower the fuller she is. */
    private static final EntityDataAccessor<Float> LOAD = SynchedEntityData.defineId(HatBoatEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Boolean> PADDLING = SynchedEntityData.defineId(HatBoatEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Long> STROKE_START = SynchedEntityData.defineId(HatBoatEntity.class, EntityDataSerializers.LONG);
    /** Where the stick is (Stick ordinal): the client draws it there and never infers it from a clip. */
    private static final EntityDataAccessor<Integer> STICK = SynchedEntityData.defineId(HatBoatEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> HELM_KIND = SynchedEntityData.defineId(HatBoatEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> HELM_STATUS = SynchedEntityData.defineId(HatBoatEntity.class, EntityDataSerializers.INT);
    /** The captain's voyage phase, read-only for clients and readouts. */
    private static final EntityDataAccessor<Integer> VOYAGE_PHASE = SynchedEntityData.defineId(HatBoatEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Float> CURRENT = SynchedEntityData.defineId(HatBoatEntity.class, EntityDataSerializers.FLOAT);
    /** One authoritative start time and direction; clients evaluate the same continuous curve. */
    private static final EntityDataAccessor<CompoundTag> MOTION = SynchedEntityData.defineId(HatBoatEntity.class, EntityDataSerializers.COMPOUND_TAG);
    /** Weight-shift dip toward one rim: From/To degrees, At game tick, Ticks ramp length and Yaw (see dipYaw). Never persisted. */
    private static final EntityDataAccessor<CompoundTag> DIP = SynchedEntityData.defineId(HatBoatEntity.class, EntityDataSerializers.COMPOUND_TAG);
    public static final int ROCK_TICKS=44, CAPSIZE_TICKS=16, CAPSIZE_RELEASE_TICK=9;
    public static final float MAX_DIP=5f;
    public static final int DIP_RENEW_TICKS=10;
    /** A captain this far into a live pregnancy sits on the high stern seat and leaves the cabin before her free for the birth
     *  (project design, aquatic ecology A5). */
    public static final float GRAVID_PROGRESS = .8f;
    /** A hypothetical or current tilt: lowered-rim direction yaw (hat-local, degrees) and its angle. */
    public record Dip(float degrees,float yaw){ public static final Dip NONE=new Dip(0,0); }
    private final HatCargo cargo = new HatCargo(this);
    private final HatDeck deck = new HatDeck(new DeckHost());
    private final HullHelm helm = new HullHelm(this);
    private UUID accessoryToken;
    /** Who set the hat down to catch: a helper beside it, or the mother before her own outlet. */
    private HatVessel.Role role = HatVessel.Role.HELPER;
    private double loadNow, sinkNow;
    private double lerpX, lerpY, lerpZ;
    private float lerpYaw, lerpPitch;
    private int lerpSteps;
    /** Young born into the hat (water birth); kept after a capsize so a rescue can bring them ashore. */
    private final LinkedHashSet<UUID> crew = new LinkedHashSet<>();
    private final HatBoatCrowding crowding=new HatBoatCrowding(this);
    /** The identity of an unborn birth-size probe used only for read-only deck questions. */
    private static final UUID NEWBORN_PROBE = new UUID(0L, 1L);
    /** The only two birth berths. A water birth must keep the chosen purpose through its real reservation. */
    public enum NewbornAdmission { NONE, BOARD, RESCUE }
    /** Last read-only planner outcome, for server failure reports; never persisted or used as state. */
    public String carePlanDebug(){ return stations.carePlanDebug(); }

    public HatBoatEntity(EntityType<? extends HatBoatEntity> type, Level level) { super(type, level); blocksBuilding = true; }
    @Override protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(STATE, State.AFLOAT.ordinal()); builder.define(OWNER, Optional.empty()); builder.define(SCALE, 1f);
        // Every pre-Water-15 hat boat is an aquatic Marisa vessel.  This preserves its identity until a loaded owner refreshes it.
        builder.define(AQUATIC_HULL, true); builder.define(ACCESSORY_STAGE, FamilyLife.Stage.ADULT.ordinal()); builder.define(DECK, new CompoundTag());
        builder.define(CARGO, new CompoundTag()); builder.define(CREW, new CompoundTag()); builder.define(PADDLING, false); builder.define(MOTION,new CompoundTag()); builder.define(DIP,new CompoundTag());
        builder.define(STROKE_START, 0L); builder.define(CURRENT, 0f); builder.define(HELM_KIND, 0); builder.define(HELM_STATUS, 0); builder.define(VOYAGE_PHASE, 0); builder.define(HULL_DAMAGE, 0);
        builder.define(LOAD, 0f); builder.define(STICK, Stick.NONE.ordinal());
    }
    @Override public void onSyncedDataUpdated(EntityDataAccessor<?> data) {
        super.onSyncedDataUpdated(data);
        if (CARGO.equals(data) && cargo != null && level().isClientSide) cargo.load(entityData.get(CARGO));
        if (SCALE.equals(data)) refreshDimensions();
    }
    @Override public EntityDimensions getDimensions(Pose pose) {
        return super.getDimensions(pose).scale(hullScale()/HatVessel.AQUATIC_SCALE);
    }

    // ---------------------------------------------------------------- the deck
    /** What the deck needs from this hull. */
    private final class DeckHost implements HatDeck.Host {
        @Override public DeckLayout layout() { return DeckLayout.of(mode()); }
        @Override public long now() { return level().getGameTime(); }
        @Override public boolean night() { return !catches() && level().isNight(); }
        @Override public boolean stable() { return (isAfloat() || state() == State.BEACHED || catches()) && !capsizing(); }
        @Override public double portions() { return cargo.count(); }
        @Override public boolean paddleAboard() { return cargo.paddle(); }
        @Override public boolean paddleStowed() { return cargo.paddle() && !paddling(); }
        @Override public double extraLoad() { return carriedPaddleLoad(); }
        @Override public HatDeck.Hop hop(UUID body,Vec3 from,Vec3 to){
            var rider=level() instanceof ServerLevel server?server.getEntity(body):null;
            if(!(rider instanceof YukkuriEntity y))return null;
            var plan=YukkuriLocomotion.hop(y,toWorld(from),toWorld(to),List.of(HatBoatEntity.this));
            return plan.ok()?new HatDeck.Hop(plan.ticks(),plan.points().stream().map(HatBoatEntity.this::toLocal).toList()):null;
        }
        @Override public void event(String message, Object... values) { GameplayLog.event(HatBoatEntity.this, "aquatic", message, values); }
        @Override public void limited(String key, int ticks, String message, Object... values) {
            GameplayLog.limited(HatBoatEntity.this, "aquatic", key, ticks, message, values);
        }
    }
    public HatCargo cargo() { return cargo; }
    public HatDeck deck() { return deck; }
    public DeckLayout deckLayout() { return deck.layout(); }
    /** Portions that still fit in the larder: the deck's load and the seats a fuller larder would press on. */
    public int cargoRoom(){
        int room = 0;
        for (int count = cargo.count() + 1; count <= HatCargo.LARDER_MAX; count++) {
            if (!deck.heapFits(count)) break;
            room++;
        }
        return room;
    }
    /** The stick weighs one unit; it may come aboard when the load has room. */
    boolean canCarryPaddle() { return deck.load() + deckLayout().paddleLoad <= deckLayout().maxLoad + 1e-9; }
    /** Where the larder's food lies (hat units): the measured zone of the current tier, else the middle of the crown. */
    Vec3 cargoSpillOrigin(){
        var tier = deckLayout().tier(Math.max(1, cargo.tier()));
        var zone = tier == null ? null : tier.zone();
        if (zone == null) {
            int larder = deckLayout().firstOfKind(Kind.LARDER);
            return larder < 0 ? new Vec3(0, HatVessel.top(mode()), 0) : deckLayout().slot(larder).pos();
        }
        return new Vec3(zone.x, Math.max(zone.y, HatVessel.top(mode()) + .2), zone.z);
    }
    public List<HatVessel.ProvisionZone> capsizeSpillZones(){
        var tier = deckLayout().tier(Math.max(1, cargo.tier()));
        if (tier == null || tier.zone() == null) return List.of();
        var z = tier.zone();
        return List.of(new HatVessel.ProvisionZone(new Vec3(z.x, z.y, z.z), 1, tier.height()));
    }
    public void syncCargo(CompoundTag data) { entityData.set(CARGO, data); }

    // ---------------------------------------------------------------- load and the waterline
    /** Total load in units: food, the stick, and every body aboard or promised a seat. */
    public double capacityUsed() { return deck.load(); }
    public double remainingCapacity() { return Math.max(0, deck.maxLoad() - deck.load()); }
    /** Synchronized load, for the client's waterline and the cargo readout. */
    public float hullLoad() { return entityData.get(LOAD); }
    /** The waterline drop at the current load, in blocks: fuller hulls ride lower, together with every seat. */
    /** Current load-derived waterline drop, shared by buoyancy and righting's floating-footprint check. */
    public double sinkBlocks() { return sinkNow; }
    /** Roll and flooding sensitivity at the current load, 1 when empty (project design, ecology A3). */
    public double rollFactor() { return deckLayout().rollFactor(loadNow); }
    private void tickLoad() {
        loadNow = deck.load();
        sinkNow = deckLayout().sinkUnits(loadNow) * unit();
        float synced = (float) loadNow;
        if (Math.abs(entityData.get(LOAD) - synced) > .01f) entityData.set(LOAD, synced);
    }
    /** A stick held out for hand-over is aboard before it reaches its recipient; the cargo-owned stick is counted separately. */
    private double carriedPaddleLoad() {
        var captain=owner();
        return captain!=null && captain.getVehicle()==this && captain.aquaticLife().familyPaddle().handingOver() ? deckLayout().paddleLoad : 0;
    }

    // ---------------------------------------------------------------- bodies, authority and admission
    /** The deck's view of one rider: its class from its current size, whether it jumps, whether this hull may carry it. */
    public Body bodyOf(YukkuriEntity y){
        return bodyOf(y, y.getUUID().equals(ownerYukkuri()) || admits(y));
    }
    Body bodyOf(YukkuriEntity y, boolean admitted){
        var layout = deckLayout();
        boolean captain = y.getUUID().equals(ownerYukkuri());
        double captainGrowth = hullScale() / layout.hullRatio;
        double ratio = captainGrowth <= 0 ? 1 : y.growthScale() / captainGrowth;
        boolean gravid = captain && y.livePregnancyProgress() >= GRAVID_PROGRESS && deck.hasGravidSeat();
        double extra = !captain && y.aquaticLife().hasPaddleForReturn() ? layout.paddleLoad : 0;
        return new Body(y.getUUID(), layout.classOf(ratio, captain, gravid), captain || y.childhood().canJump(), admitted, extra);
    }
    /** The owner's own young (as mother or father), not yet adult. */
    public boolean young(YukkuriEntity y) {
        return ownerYukkuri() != null && (ownerYukkuri().equals(y.family().mother()) || ownerYukkuri().equals(y.family().father())) && !y.family().adult();
    }
    /** Physical boarding authority: birth family, a boarded refuge ward, or a finite refuge lease. */
    public boolean admits(YukkuriEntity child) {
        if(child==null||child.family().adult())return false;
        if(young(child)||temporaryCaregiver(child))return true;
        return deck.refugeReserved(child.getUUID());
    }
    /** Immediate food and supported care-seat authority after a non-parent rescue; it grants no family relation. */
    public boolean careRecipient(YukkuriEntity child) { return child!=null&&(young(child)||temporaryCaregiver(child)); }
    public boolean temporaryCaregiver(YukkuriEntity child) {
        return child!=null&&ownerYukkuri()!=null&&ownerYukkuri().equals(deck.caregiverOf(child.getUUID()));
    }
    /** Reserve a refuge seat only for an actual visible swimmer selected by the caller's help policy. */
    public boolean requestTemporaryRefuge(YukkuriEntity child) {
        if(level().isClientSide||child==null||child.family().adult()||child.isPassenger()||!child.isInWaterOrBubble()
                ||!isAfloat()||unsteadyMotion()||flooding()>0||owner()==null||!owner().isAlive()
                ||child.distanceToSqr(this)>144||!child.hasLineOfSight(this))return false;
        if(temporaryCaregiver(child)||deck.refugeReserved(child.getUUID()))return true;
        var lease = deck.claimRefuge(bodyOf(child, true), ownerYukkuri(), toLocal(child.position()));
        if (lease == null) return false;
        syncSeats();
        return true;
    }
    /**
     * Keep a seat for a dependent who is still physically safe in an adjacent, ownerless hat. This does not dismount it:
     * the child must make the existing checked hull-to-hull transfer before this lease turns into care authority.
     */
    public boolean requestTemporaryRefugeTransfer(YukkuriEntity child,HatBoatEntity source) {
        if(level().isClientSide||child==null||source==null||source==this||child.family().adult()
                ||child.getVehicle()!=source||!source.isAfloat()||source.unsteadyMotion()||source.flooding()>0
                ||source.owner()!=null&&source.owner().isAlive()||!isAfloat()||unsteadyMotion()||flooding()>0
                ||owner()==null||!owner().isAlive()||child.distanceToSqr(this)>144||!child.hasLineOfSight(this))return false;
        if(temporaryCaregiver(child)||deck.refugeReserved(child.getUUID()))return true;
        var lease = deck.claimRefuge(bodyOf(child, true), ownerYukkuri(), toLocal(child.position()));
        if (lease == null) return false;
        syncSeats();
        return true;
    }
    /** Called after the ordinary shore landing has physically put the temporary rider on dry ground. */
    public void releaseTemporaryRefuge(YukkuriEntity child,String reason) {
        if(child==null)return;
        deck.dropRefuge(child.getUUID(), reason);
        syncSeats();
    }
    public int youngAboard() { return (int) getPassengers().stream().filter(p -> !p.getUUID().equals(ownerYukkuri())).count(); }
    public boolean hasIncoming() { return deck.anyIncoming(); }
    /** A cancelled airborne transfer releases only its incoming seat, not a separately retained voyage berth. */
    public void cancelIncoming(YukkuriEntity child){
        if(!level().isClientSide&&child!=null&&!hasPassenger(child)&&deck.incoming(child.getUUID()))deck.release(child.getUUID(),"cancelled");
    }
    /** Cancel a failed pre-boarding birth claim, including the rescue-only crowding flag created for that exact claim. */
    public void cancelBirthAdmission(YukkuriEntity child){
        if(level().isClientSide||child==null||hasPassenger(child))return;
        boolean rescue=deck.hasLease(child.getUUID(),Purpose.RESCUE);
        cancelIncoming(child);
        if(rescue)crowding.cancelEmergency(child.getUUID());
        syncSeats();
    }
    /** Whether the hull is in a state to take a body aboard at all. */
    private boolean boardable() { return isAfloat() || state() == State.BEACHED || catches(); }
    public double maxLoad() { return deck.maxLoad(); }
    public double paddleLoad() { return deckLayout().paddleLoad; }
    public String assignmentText() { return deck.assignmentText(); }
    public String lastDeckRefusal() { return deck.lastRefusal(); }
    /** Read-only twin of {@link #makeRoom}: could the decision layer seat this child by moving idle children, and (when allowed) a portion? */
    public boolean canMakeRoom(YukkuriEntity child, boolean jettison) {
        if(child==null||!admits(child)||!boardable())return false;
        if(hasPassenger(child)||deck.incoming(child.getUUID())||deck.hasLease(child.getUUID(),Purpose.RETAINED))return true;
        return canMakeRoom(bodyOf(child, true),jettison,toLocal(child.position()));
    }
    /** Read-only capacity preflight for this unborn child's actual birth scale, including birth kind and the Deibu multiplier. */
    public boolean canMakeRoomForNewborn(FamilyData.BirthKind kind,int subspeciesFlags,boolean jettison) {
        return boardable()&&canMakeRoom(newbornProbe(kind,subspeciesFlags),jettison,null);
    }
    public boolean canMakeRoomForNewborn(FamilyData.BirthKind kind,FamilyData.Child child,boolean jettison) {
        return boardable()&&canMakeRoom(newbornProbe(GrowthSize.birth(kind,child),child.subspeciesFlags),jettison,null);
    }
    public boolean canAdmitNewborn(FamilyData.BirthKind kind,FamilyData.Child child,boolean emergency) {
        return boardable()&&newbornAdmission(newbornProbe(GrowthSize.birth(kind,child),child.subspeciesFlags),emergency)!=NewbornAdmission.NONE;
    }
    /** Read-only real seat candidates, including the seat an allowed cargo relief would expose. */
    public List<Vec3> birthSeats(FamilyData.BirthKind kind,FamilyData.Child child,boolean emergency,Vec3 from) {
        if(!boardable()||unsteadyMotion())return List.of();
        var body=newbornProbe(GrowthSize.birth(kind,child),child.subspeciesFlags);
        var seats=new ArrayList<Vec3>();
        for(int slot:deck.freeSeats(body,Purpose.BOARD,toLocal(from)))seats.add(toWorld(deckLayout().slot(slot).pos()));
        if(seats.isEmpty()&&canMakeRoom(body,emergency,toLocal(from))){
            int slot=deck.firstSeatAfterRelievingLarder(body,Purpose.BOARD,toLocal(from),emergency);
            if(slot>=0)seats.add(toWorld(deckLayout().slot(slot).pos()));
        }
        return seats;
    }
    private boolean canMakeRoom(Body body,boolean jettison,Vec3 near) {
        var a = deck.canAdmit(body, Purpose.BOARD);
        if (a.ok()) return true;
        if (jettison && deck.canAdmit(body, Purpose.RESCUE).ok()) return true;
        switch (a.reason()) {
            case UNITS_FULL -> { return jettison && cargo.count() > 0 && a.loadAfter() - cargo.count() * deckLayout().foodLoad <= deck.maxLoad() + 1e-9; }
            case CONFLICT_WITH -> {
                if (a.detail().equals("larder")) return jettison && cargo.count() > 0;
                return deck.planRoom(body, Purpose.BOARD, near) != null;
            }
            default -> { return false; }
        }
    }
    /** The deck's answer for this child and purpose, with its reason; read only. */
    public Admission admission(YukkuriEntity child, Purpose purpose) { return deck.canAdmit(bodyOf(child), purpose); }
    /** One admission predicate: aboard already, or booked, or a legal seat is free for it and the load fits. */
    public boolean canBoard(YukkuriEntity child) {
        if(child==null||!admits(child)||!boardable())return false;
        if(hasPassenger(child)||deck.incoming(child.getUUID())||deck.hasLease(child.getUUID(),Purpose.RETAINED))return true;
        return deck.canAdmit(bodyOf(child, true), Purpose.BOARD).ok();
    }
    /** The deck class is relative to this hull's captain scale, exactly as {@link #bodyOf(YukkuriEntity, boolean)} computes it. */
    private Body newbornProbe(FamilyData.BirthKind kind,int subspeciesFlags){return newbornProbe(GrowthSize.birth(kind),subspeciesFlags);}
    private Body newbornProbe(float scale,int subspeciesFlags){
        var layout=deckLayout();double captainGrowth=hullScale()/layout.hullRatio;
        double birthScale=scale*((subspeciesFlags&SubspeciesLife.DEIBU_FLAG)!=0?1.5:1);
        double ratio=captainGrowth<=0?1:birthScale/captainGrowth;
        return new Body(NEWBORN_PROBE,layout.classOf(ratio,false,false),false,true,0);
    }
    /** The concrete newborn must be the same deck body as the unborn probe before it can consume the selected berth. */
    public NewbornAdmission newbornAdmission(YukkuriEntity child,boolean emergency) {
        if(child==null||!admits(child)||!boardable())return NewbornAdmission.NONE;
        var body=bodyOf(child,true);
        return newbornAdmission(body,emergency);
    }
    private NewbornAdmission newbornAdmission(Body body,boolean emergency) {
        if(deck.canAdmit(body,Purpose.BOARD).ok())return NewbornAdmission.BOARD;
        return emergency&&deck.canAdmit(body,Purpose.RESCUE).ok()?NewbornAdmission.RESCUE:NewbornAdmission.NONE;
    }
    /** Would a newborn (not yet an entity) have an ordinary safe seat and load room here. */
    public boolean canAdmitNewborn() { return canAdmitNewborn(FamilyData.BirthKind.LIVE,0,false); }
    /** Unborn overload: uses the child record's exact birth scale instead of assuming a live standard-scale parent. */
    public boolean canAdmitNewborn(FamilyData.BirthKind kind,int subspeciesFlags,boolean emergency) {
        return boardable()&&newbornAdmission(newbornProbe(kind,subspeciesFlags),emergency)!=NewbornAdmission.NONE;
    }
    /** Short text of why the last admission was refused, for failure reports. */
    public String roomDebug(YukkuriEntity child){
        var a = admission(child, Purpose.BOARD);
        return "admission=" + a.text() + " load=" + String.format(Locale.ROOT, "%.1f/%.1f", a.loadBefore(), deck.maxLoad())
                + " last=" + deck.lastRefusal() + " mode=" + mode() + " assignment=" + deck.assignmentText();
    }

    /**
     * The decision layer's explicit "make room" call. Admission never frees space on its own: this moves idle children aside,
     * has a hungry rider eat a portion, and only when {@code jettison} is allowed (a rescue, a birth) pushes the cheapest portion
     * overboard as a real, recollectable loss. Returns true when the child has a seat now; false while moves are under way.
     */
    public boolean makeRoom(YukkuriEntity child, boolean jettison) {
        if(level().isClientSide||child==null||!admits(child)||!boardable())return false;
        if(hasPassenger(child)||deck.incoming(child.getUUID())||deck.hasLease(child.getUUID(),Purpose.RETAINED))return true;
        var body = bodyOf(child, true);
        return makeRoom(body, jettison, toLocal(child.position()), "make_room");
    }
    /** A rescue also takes the rescue rim when no ordinary seat can be freed. */
    private boolean rescueSeatFree(Body body) { return deck.canAdmit(body, Purpose.RESCUE).ok(); }
    private boolean makeRoom(Body body, boolean jettison, Vec3 near, String reason) {
        for (int guard = 0; guard <= HatCargo.LARDER_MAX + 2; guard++) {
            var a = deck.canAdmit(body, Purpose.BOARD, near, DeckLayout.NONE);
            if (a.ok()) return true;
            if (jettison && rescueSeatFree(body)) return true;
            switch (a.reason()) {
                case UNITS_FULL -> { if (!relieveLoad(jettison, reason)) return false; }
                case CONFLICT_WITH -> {
                    if (a.detail().equals("larder")) { if (!relieveLoad(jettison, reason)) return false; continue; }
                    var plan = deck.planRoom(body, Purpose.BOARD, near);
                    if (plan != null && !plan.moves().isEmpty()) applyRoom(plan, null);
                    return false;
                }
                default -> { return false; }
            }
        }
        return false;
    }
    /** A rider that is hungry eats a portion; failing that, if allowed, the cheapest portion goes into the water. */
    private boolean relieveLoad(boolean jettison, String reason) {
        if (cargo.count() == 0) return false;
        var captain = owner();
        var eaters = new ArrayList<YukkuriEntity>();
        if (captain != null && captain.getVehicle() == this) eaters.add(captain);
        for (var p : getPassengers()) if (p instanceof YukkuriEntity y && y != captain) eaters.add(y);
        for (var y : eaters) if (y.life().nutrition() < 60 && cargo.feed(y)) return true;
        return jettison && cargo.jettisonCheapest(reason);
    }
    /** Start the walks or hops that a room plan asks of idle bodies; the named set receives who moved. */
    boolean applyRoom(HatDeck.RoomPlan plan, Set<UUID> moved) {
        boolean any = false;
        if (!(level() instanceof ServerLevel server)) return false;
        for (var entry : plan.moves().entrySet()) {
            if (!(server.getEntity(entry.getKey()) instanceof YukkuriEntity y)) continue;
            var body = bodyOf(y);
            var p = deck.get(y.getUUID());
            if (p == null || !p.aboard()) continue;
            boolean walk = p.slot() >= 0 && deckLayout().path(p.slot(), entry.getValue(), body.cls(), false, null) != null;
            if (deck.moveWithin(body, entry.getValue(), toLocal(y.position()), !walk && body.jumps(), Purpose.BOARD)) {
                any = true;
                if (moved != null) moved.add(y.getUUID());
            }
        }
        if (any) syncSeats();
        return any || plan.moves().isEmpty();
    }
    /**
     * Atomically claim exactly the purpose selected by the preflight. A rescue birth starts the existing crowding protection,
     * then uses the explicit rescue claim rather than letting a generic emergency boarding call substitute an ordinary seat.
     */
    public Vec3 reserveBirthAdmission(YukkuriEntity child,NewbornAdmission admission) {
        return reserveBirthAdmission(child,admission,child==null?null:child.position());
    }
    /** The recovered flight uses its measured contact point; initial delivery passes the real outlet position. */
    public Vec3 reserveBirthAdmission(YukkuriEntity child,NewbornAdmission admission,Vec3 actualFrom) {
        if(level().isClientSide||child==null||admission==NewbornAdmission.NONE||!isAfloat()||unsteadyMotion()
                ||!admits(child)||actualFrom==null)return null;
        return switch(admission){
            case BOARD -> {
                if(!canBoard(child))yield null;
                var seat=reserve(child,toLocal(actualFrom));
                if(seat==null||!deck.hasLease(child.getUUID(),Purpose.BOARD))yield null;
                syncSeats();yield seat;
            }
            case RESCUE -> {
                if(!requestEmergencyBerth(child,actualFrom))yield null;
                var seat=stations.reserveRescueBrim(child,actualFrom);
                if(seat==null||!deck.hasLease(child.getUUID(),Purpose.RESCUE)){
                    crowding.cancelEmergency(child.getUUID());yield null;
                }
                yield seat;
            }
            case NONE -> null;
        };
    }
    public boolean overcrowded(){return level().isClientSide?entityData.get(DECK).getBoolean("Overcrowded"):deck.anyOverflow();}
    /** Whether a close arrival can contest a rescue seat, without starting the crowding response. */
    public boolean canRequestEmergencyBerth(YukkuriEntity child){
        return canRequestEmergencyBerth(child,child==null?null:child.position());
    }
    private boolean canRequestEmergencyBerth(YukkuriEntity child,Vec3 actualFrom){
        if(level().isClientSide||child==null||actualFrom==null||!young(child)||!isAfloat()||capsizing()||!child.isAlive())return false;
        var local=toLocal(actualFrom);double reach=radiusOf(child)+.25/unit();
        boolean footReach=Double.isFinite(HatVessel.footprint(mode(),local.x,local.z,reach))
                &&Math.abs(local.y-HatVessel.freeboard(mode()))<=1.4/unit();
        if(!hasPassenger(child)&&(!(footReach||touchingOwnHat(child))||!child.hasLineOfSight(this)))return false;
        if(!hasPassenger(child)&&!deck.incoming(child.getUUID())&&!deck.canAdmit(bodyOf(child),Purpose.RESCUE).ok())return false;
        return true;
    }
    /** Close arrivals may take a rescue seat when the ordinary ones are gone; the deck still refuses a conflicting one. */
    public boolean requestEmergencyBerth(YukkuriEntity child){
        return requestEmergencyBerth(child,child==null?null:child.position());
    }
    private boolean requestEmergencyBerth(YukkuriEntity child,Vec3 actualFrom){
        if(!canRequestEmergencyBerth(child,actualFrom))return false;
        crowding.emergency(child);
        return true;
    }
    /** Compare actual oriented brim reaches, so a legitimate larger child's stern seat is not mistaken for remoteness. */
    private boolean touchingOwnHat(YukkuriEntity child){
        if(!(child.getVehicle() instanceof HatBoatEntity own)||own==this||!child.getUUID().equals(own.ownerYukkuri())
                ||!own.isAfloat()||!own.protects(child)||Math.abs(own.getY()-getY())>.15)return false;
        var delta=own.position().subtract(position()).multiply(1,0,1);
        if(delta.lengthSqr()<.01)return false;
        double ours=brimReachToward(delta),theirs=own.brimReachToward(delta.scale(-1));
        return delta.horizontalDistance()<=ours+theirs+.08;
    }
    public double brimReachToward(Vec3 direction){
        var local=toLocal(position().add(direction)).multiply(1,0,1).normalize();
        if(local.lengthSqr()<.01)return 0;
        return Math.max(0,HatVessel.extent(mode(),0,0,local.x,local.z,0)-.125)*unit();
    }
    /** A navigation destination just outside the measured brim, not a reserved seat or permission to displace anyone. */
    public Vec3 emergencyApproach(YukkuriEntity child){
        if(child==null||!young(child)||!isAfloat())return null;
        var own=child.getVehicle() instanceof HatBoatEntity hat&&hat!=this&&child.getUUID().equals(hat.ownerYukkuri())?hat:null;
        var toward=(own==null?child.position():own.position()).subtract(position()).multiply(1,0,1).normalize();
        if(toward.lengthSqr()<.01)toward=new Vec3(0,0,-1).yRot(turn());
        double gap=own==null?.04:own.brimReachToward(toward.scale(-1))+.015;
        return position().add(toward.scale(brimReachToward(toward)+gap));
    }
    public boolean requestInnerRefuge(YukkuriEntity child){
        if(child==null||!hasPassenger(child)||!young(child)||!isAfloat())return false;
        crowding.emergency(child);
        if(changingSeat(child)||transportingInHat(child))return true;
        var parent=owner();
        if(parent!=null&&parent.life().isSleeping())parent.life().wakeInHat();
        return stations.requestInnerRefuge(child);
    }
    public String crowdingState(Entity rider){return crowding.state(rider);}
    public long crowdingPhaseStart(Entity rider){return crowding.phaseStart(rider);}
    public float riderBalanceRoll(Entity rider,float partial){return transportingInHat(rider)?0:crowding.roll(rider,partial);}
    public float riderBalancePitch(Entity rider,float partial){return transportingInHat(rider)?0:crowding.pitch(rider,partial);}
    CompoundTag crowdingData(){return entityData.get(DECK).getCompound("Crowding");}
    void syncCrowding(){syncSeats();}
    private void syncCrew() {
        var tag = crewState.publish();
        if (!tag.equals(entityData.get(CREW))) entityData.set(CREW, tag);
    }
    double crowdRadius(Entity rider){return radiusOf(rider);}
    /** A seated rider that is not on an errand: it can be shaken off the hull. */
    boolean crowdMovable(YukkuriEntity rider){
        return rider.getVehicle()==this&&!transportingInHat(rider)&&!changingSeat(rider)&&!stations.isStationed(rider.getUUID());
    }
    /** A rider the deck could not seat (it grew, the hull is overloaded): only it is pressed and pushed by the crowd. */
    boolean crowdPressable(YukkuriEntity rider){
        var placed = deck.get(rider.getUUID());
        return crowdMovable(rider) && placed != null && placed.overflow();
    }
    boolean crowdPosition(YukkuriEntity rider,Vec3 local){
        var placed = deck.get(rider.getUUID());
        if (placed == null || !placed.overflow()) return true;
        var target=toWorld(local);
        rider.move(MoverType.SELF,target.subtract(rider.position()));
        var reached=toLocal(rider.position());
        deck.moveFree(rider.getUUID(), reached);
        return rider.position().distanceToSqr(target)<1e-6;
    }
    /** The crowd pushes a rider that has no seat over the rim: the fallback when the squeeze-out hop cannot be made. */
    public boolean squeezeFall(YukkuriEntity child){
        var local=toLocal(child.position());var outward=new Vec3(local.x,0,local.z);
        if(outward.lengthSqr()<1e-6)outward=new Vec3(0,0,-1);
        return crowdFall(child,local,outward,null,WaterIncidents.Cause.SQUEEZED);
    }
    boolean crowdFall(YukkuriEntity child,Vec3 local,Vec3 direction,UUID pusher){
        return crowdFall(child,local,direction,pusher,WaterIncidents.Cause.FALL);
    }
    boolean crowdFall(YukkuriEntity child,Vec3 local,Vec3 direction,UUID pusher,WaterIncidents.Cause cause){
        var out=direction.multiply(1,0,1).normalize().yRot(turn());
        if(!crowdPosition(child,local))return false;
        child.stopRiding();
        child.setDeltaMovement(getDeltaMovement().add(out.scale(.075)).add(0,.015,0));child.hasImpulse=true;
        child.waterDistress().plunge(getUUID(),pusher,cause);
        var parent=owner();if(parent!=null)parent.showReaction("surprise","wait_family",80);
        syncSeats();
        return true;
    }
    @Override public Vec3 getDismountLocationForPassenger(LivingEntity passenger){
        // Both the held child's lift and active rim boarding start at the actual feet.
        // Vanilla's relocation to the hull centre would invalidate the checked trajectory.
        if(passenger instanceof YukkuriEntity child&&child.family().crew().passesThrough(this))return child.position();
        return super.getDismountLocationForPassenger(passenger);
    }
    /** A newborn delivered in the hat stays aboard with its mother. */
    public boolean boardYoung(YukkuriEntity child) {
        if (level().isClientSide || unsteadyMotion()&&!crowding.emergency(child.getUUID()) || !admits(child) || !canBoard(child)) return false;
        boolean booked = deck.incoming(child.getUUID()) || deck.hasLease(child.getUUID(), Purpose.RETAINED) || hasPassenger(child);
        if (!booked && deck.claim(bodyOf(child), Purpose.BOARD, toLocal(child.position()), DeckLayout.NONE, HatDeck.BOARD_TTL) == null) return false;
        if (child.startRiding(this, true)) return true;
        if (!booked) deck.release(child.getUUID(), "mount_refused");
        return false;
    }
    @Override protected void addPassenger(Entity passenger) {
        super.addPassenger(passenger);
        if (level().isClientSide || !(passenger instanceof YukkuriEntity y)) return;
        deck.commit(bodyOf(y), toLocal(y.position()));
        if (young(y)) crew.add(y.getUUID());
        syncSeats();
    }
    @Override protected void removePassenger(Entity passenger) {
        super.removePassenger(passenger);
        if (level().isClientSide) return;
        stations.onLeft(passenger.getUUID());
        deck.leave(passenger.getUUID(), "removed");
        syncSeats();
    }

    // ---------------------------------------------------------------- seats and walks (server and client)
    /** The persisted hull identity, never the visibility of its owner, selects the authored cavity profile. */
    public HatVessel.Mode mode() { return isCradle() ? HatVessel.Mode.NEST : aquaticHull() ? HatVessel.Mode.AQUATIC_FLOAT : HatVessel.Mode.FLOAT; }
    /** Blocks per hat unit (the prop's units at this hat's size). */
    public double unit() { return hullScale()/16; }
    public float turn() { return (float) Math.toRadians(180 - getYRot()); }
    public Vec3 toWorld(Vec3 local) { return position().add(local.scale(unit()).yRot(turn())); }
    public Vec3 toLocal(Vec3 world) { return world.subtract(position()).yRot(-turn()).scale(1/unit()); }
    double radiusOf(Entity young) { return HatVessel.radius(young.getBbWidth())/unit(); }
    public List<Entity> youngPassengers() { return getPassengers().stream().filter(p -> !p.getUUID().equals(ownerYukkuri())).toList(); }
    /** The owner's stern seat (hull-local): the high stern seat from late pregnancy. */
    public Vec3 ownerSeat(YukkuriEntity owner) {
        int home = deck.homeFor(bodyOf(owner).cls());
        return home < 0 ? Vec3.ZERO : deckLayout().slot(home).pos();
    }
    public UUID ownerYukkuri() { return entityData.get(OWNER).orElse(null); }
    /** The synchronized deck entry of one rider (client side), or null. */
    private CompoundTag seatTag(Entity rider) {
        for (var raw : entityData.get(DECK).getList("S", Tag.TAG_COMPOUND))
            if (raw instanceof CompoundTag c && c.hasUUID("Id") && c.getUUID("Id").equals(rider.getUUID())) return c;
        return null;
    }
    /** The place the server gave this rider (where it sits, or is bound for), or null. */
    Vec3 seatOf(Entity rider) {
        if (!level().isClientSide) return deck.positionOf(rider.getUUID());
        var c = seatTag(rider);
        return c == null ? null : new Vec3(c.getDouble("X"), c.getDouble("Y"), c.getDouble("Z"));
    }
    /** The deck's seat kind of a rider (server: the deck; client: the synchronized snapshot), or null. */
    public DeckLayout.Kind seatKind(Entity rider) {
        if (!level().isClientSide) { var slot = deck.slotOf(rider.getUUID()); return slot == null ? null : slot.kind(); }
        var c = seatTag(rider);
        if (c == null) return null;
        try { return DeckLayout.Kind.valueOf(c.getString("Kind")); } catch (IllegalArgumentException invalid) { return null; }
    }
    private HatDeck.Move seatMove(Entity rider) {
        if (!level().isClientSide) return deck.moveOf(rider.getUUID());
        var c = seatTag(rider);
        if (c == null || !c.contains("At")) return null;
        return new HatDeck.Move(new Vec3(c.getDouble("FromX"), c.getDouble("FromY"), c.getDouble("FromZ")), c.getLong("At"), c.getInt("Ticks"),
                c.getBoolean("Arc"), loadRoute(c));
    }
    private static List<Vec3> loadRoute(CompoundTag tag) {
        var points = new ArrayList<Vec3>();
        for (var raw : tag.getList("Route", Tag.TAG_COMPOUND)) {
            var p = (CompoundTag) raw;
            points.add(new Vec3(p.getDouble("X"), p.getDouble("Y"), p.getDouble("Z")));
        }
        return points;
    }
    public boolean changingSeat(Entity rider){var m=seatMove(rider);return m!=null&&level().getGameTime()-m.start()<m.ticks();}
    /** On a rim seat (front brim, toilet rim, rescue rim): the deck's own seat kind, not a guess from height. */
    public boolean onBrim(Entity young){
        var kind = seatKind(young);
        return isAfloat() && kind != null && (kind == Kind.BRIM || kind == Kind.TOILET_RIM || kind == Kind.RESCUE_RIM) && !changingSeat(young);
    }
    public boolean walkingInHat(Entity rider){
        var move=seatMove(rider);
        return move!=null&&!move.arc()&&!move.route().isEmpty()&&level().getGameTime()>=move.start()&&level().getGameTime()<move.start()+move.ticks();
    }
    Vec3 walkingLocal(Entity rider,HatDeck.Move movement,double time){
        double gait=time-24*Math.sin(time*Math.PI/12)/(2*Math.PI);
        double total=movement.ticks()-24*Math.sin(movement.ticks()*Math.PI/12)/(2*Math.PI);
        Vec3 local = HatSurfaceRoute.sample(movement.route(),gait/Math.max(1,total));
        double support = HatVessel.travelSupport(mode(), local.x, local.z, radiusOf(rider));
        // Lift onto a short ledge without ever sinking the feet through its surface.
        return new Vec3(local.x, Math.max(local.y, support), local.z);
    }
    private Vec3 walkingPosition(Entity rider,HatDeck.Move movement,double time){
        Vec3 at=toWorld(walkingLocal(rider,movement,time));
        if(!level().isClientSide&&time>0&&time<movement.ticks()&&rider instanceof YukkuriEntity y&&at.subtract(rider.position()).horizontalDistanceSqr()>.00001) {
            SurfaceApproach.face(y,SurfaceApproach.yaw(rider.position(),at));
        }
        return at;
    }
    public static void point(CompoundTag tag,String key,Vec3 point) {
        tag.putDouble(key+"X",point.x);tag.putDouble(key+"Y",point.y);tag.putDouble(key+"Z",point.z);
    }
    public static Vec3 point(CompoundTag tag,String key) {
        return new Vec3(tag.getDouble(key+"X"),tag.getDouble(key+"Y"),tag.getDouble(key+"Z"));
    }
    public void syncSeats() {
        var tag = deck.snapshot(ownerYukkuri());
        tag.put("Crowding", crowding.save());
        tag.putBoolean("Overcrowded", deck.anyOverflow());
        entityData.set(DECK, tag);
    }

    // ---------------------------------------------------------------- stations (care, toilet, practice), rescue pair, departure berth
    public boolean prepareCareSeat(YukkuriEntity child, YukkuriEntity parent) { return stations.prepareCareSeat(child, parent); }
    public boolean prepareRearCareSeat(YukkuriEntity child, YukkuriEntity parent) { return stations.prepareRearCareSeat(child, parent); }
    boolean prepareServiceSeat(YukkuriEntity child, YukkuriEntity parent, boolean rearCare) { return stations.prepareServiceSeat(child, parent, rearCare); }
    boolean prepareOwnerToiletSeat(YukkuriEntity owner) { return stations.prepareOwnerToiletSeat(owner); }
    public boolean careSeatReady(YukkuriEntity child) { return stations.careSeatReady(child); }
    public boolean rearCareSeat(YukkuriEntity child) { return stations.rearCareSeat(child); }
    public boolean prepareToiletSeat(YukkuriEntity child, YukkuriEntity parent) { return stations.prepareToiletSeat(child, parent); }
    public boolean prepareToiletSeat(YukkuriEntity child) { return stations.prepareToiletSeat(child); }
    public boolean toiletSeatReady(YukkuriEntity child) { return stations.toiletSeatReady(child); }
    public void faceToiletOutward(YukkuriEntity child) { stations.faceToiletOutward(child); }
    public Vec3 toiletDropPosition(YukkuriEntity child) { return stations.toiletDropPosition(child); }
    public boolean toiletAssistanceValidated(YukkuriEntity child) { return stations.toiletAssistanceValidated(child); }
    public boolean releaseToiletSeat(YukkuriEntity child) { return stations.releaseToiletSeat(child); }
    public void releaseCareSeat(UUID childId) { stations.releaseCareSeat(childId); }
    public boolean beginPracticeMove(YukkuriEntity child, boolean hop) { return stations.beginPracticeMove(child, hop); }
    public boolean practisingMove(YukkuriEntity child) { return stations.practisingMove(child); }
    public boolean completePracticeMove(YukkuriEntity child) { return stations.completePracticeMove(child); }
    public boolean cancelPracticeMove(YukkuriEntity child) { return stations.cancelPracticeMove(child); }
    public void releaseRescueOwner(YukkuriEntity parent){ stations.releaseRescueOwner(parent); }
    public boolean keepVoyageBerth(YukkuriEntity child) { return stations.keepVoyageBerth(child); }
    /** The pupil takes the front brim berth for its departure; idle siblings in the way are moved first. */
    public boolean keepDepartureBerth(YukkuriEntity child) { return stations.keepDepartureBerth(child); }
    public String departureDebug(){ return stations.departureDebug(); }
    public boolean preparingDepartureBerth(YukkuriEntity child){ return stations.preparingDepartureBerth(child); }
    /** APPROACH waits for the siblings' real walk, then books the pupil's berth. */
    public boolean advanceDepartureBerth(YukkuriEntity child){ return stations.advanceDepartureBerth(child); }
    public void releaseVoyageBerth(UUID child) { stations.releaseVoyageBerth(child); }
    public Vec3 voyageBerth(YukkuriEntity child) { return stations.voyageBerth(child); }
    /** The retained front berth has been reached, rather than merely reserved. */
    public boolean onVoyageBerth(YukkuriEntity child) { return stations.onVoyageBerth(child); }

    /** Presentation follows the same synchronized preparation and release clock as physical custody. */
    /** The transfers touching this hull (the one synchronized CrewState record); the client reads the received tag. */
    public CrewState crew() { return crewState; }
    public List<CrewState.Entry> crewEntries() { return crewState.entries(entityData.get(CREW), !level().isClientSide); }
    /** A rider takes part in a transfer on this hull (as the body or as its carrier): not to be shaken, reflowed or sent home meanwhile. */
    public boolean transportingInHat(Entity rider) { return CrewState.entryFor(crewEntries(), rider.getUUID()) != null; }
    /** The rider is in a braid's or a mouth's hold inside a lift on this hull (the carrier or the carried). */
    public boolean carryingInHat(Entity rider) {
        var e = CrewState.entryFor(crewEntries(), rider.getUUID());
        return e != null && e.mode() == CrewTransfer.Mode.LIFT && e.phase().ordinal() >= CrewTransfer.Phase.CONTACT.ordinal();
    }
    /** The captain lifts a rider to another seat of this hull (a care station, a way home); false when the transfer is refused. */
    boolean beginCarry(YukkuriEntity child, YukkuriEntity parent, int toSlot, int captainSlot) {
        var result = CrewTransfers.carryWithin(child, parent, this, toSlot, captainSlot);
        carryPlanDebug = result.ok() ? "ready" : result.refusal() + ":" + result.reason();
        return result.ok();
    }
    public boolean hasCarryTrip() { return crewState.busy(); }
    public boolean crowdContesting() { return crowding.contesting(); }

    // ---------------------------------------------------------------- boarding from outside
    /** A place for this young (nearest prefer, hat units; null for the usual one) that it can reach from where it is, kept for it until it boards. */
    public Vec3 reserve(YukkuriEntity child, Vec3 prefer) { return stations.reserve(child, prefer); }
    /** The place a young would get next, without keeping it. */
    public Vec3 peekSeat(YukkuriEntity child) { return stations.peekSeat(child); }
    /** A physical braid lift reserves the seat nearest the entry the child comes up at. */
    public Vec3 reserveRescueBrim(YukkuriEntity child,Vec3 actualFrom){ return stations.reserveRescueBrim(child, actualFrom); }
    /** Reserve the seat chosen by a transfer preflight; a changed deck refuses instead of substituting another destination. */
    public Vec3 reserveRescueBrim(YukkuriEntity child,Vec3 actualFrom,int preferred){ return stations.reserveRescueBrim(child, actualFrom, preferred); }
    /** Choose a real close landing, instead of reusing a far retained berth after jump limits tighten. */
    public Vec3 reserveBoardingSeat(YukkuriEntity child,Vec3 actualFrom,boolean emergency){ return stations.reserveBoardingSeat(child, actualFrom, emergency); }
    /** Reserve the seat chosen by a transfer preflight; a changed deck refuses instead of substituting another destination. */
    public Vec3 reserveBoardingSeat(YukkuriEntity child,Vec3 actualFrom,boolean emergency,int preferred){ return stations.reserveBoardingSeat(child, actualFrom, emergency, preferred); }
    /** The place a returning pupil takes in this hull: the berth kept for it while its own hat was away, else a close place. */
    public Vec3 returnSeat(YukkuriEntity child,boolean emergency){ return stations.returnSeat(child, emergency); }
    /** Reclaim or reserve exactly the seat chosen by a transfer preflight. */
    public Vec3 returnSeat(YukkuriEntity child,boolean emergency,int preferred){ return stations.returnSeat(child, emergency, preferred); }
    /** A wet owner first reaches a nearby supported seat, then walks to the stern after settling. */
    public Vec3 reserveOwnBoardingSeat(YukkuriEntity actor,Vec3 actualFrom){ return reserveOwnBoardingSeat(actor,actualFrom,at->true); }
    public Vec3 reserveOwnBoardingSeat(YukkuriEntity actor,Vec3 actualFrom,Predicate<Vec3> reachable){ return stations.reserveOwnBoardingSeat(actor, actualFrom, reachable); }
    /** Recheck the preflighted owner seat's reach without selecting another seat. */
    public Vec3 reserveOwnBoardingSeat(YukkuriEntity actor,Vec3 actualFrom,Predicate<Vec3> reachable,int preferred){ return stations.reserveOwnBoardingSeat(actor, actualFrom, reachable, preferred); }
    public void releaseOwnBoardingSeat(YukkuriEntity actor){ stations.releaseOwnBoardingSeat(actor); }
    /** Move to the closest departure seat along the deck's walking edges. */
    public boolean approachBoardingEdge(YukkuriEntity rider,Vec3 towardWorld){ return stations.approachBoardingEdge(rider, towardWorld); }
    /** Ticket-bound departure movement; only the current HOP body in APPROACH can bypass the hull's transfer exclusion. */
    public boolean approachBoardingEdge(CrewTransfer ticket,YukkuriEntity rider,Vec3 towardWorld){ return stations.approachBoardingEdge(ticket, rider, towardWorld); }
    /** The {@code limit} nearest free seats (hull-local), nearest first; read-only. */
    public List<Vec3> brimSeatsToward(YukkuriEntity child,Vec3 towardWorld,int limit){ return stations.brimSeatsToward(child, towardWorld, limit); }

    // ---------------------------------------------------------------- hull motion
    public boolean paddling() { return entityData.get(PADDLING); }
    /** The tick the current stroke cycle started (32 ticks a stroke); the server sets the stroke clip's start from it. */
    public long strokeStartTick() { return entityData.get(STROKE_START); }
    public Stick stick() { return Stick.values()[Math.clamp(entityData.get(STICK), 0, Stick.values().length - 1)]; }
    /** The stick: in the captain's braid while she rows with it, in her mouth while she hands it over, else on the hull stow. */
    private void syncStick() {
        var captain = owner();
        boolean aboard = captain != null && captain.getVehicle() == this;
        Stick now = aboard && captain.aquaticLife().familyPaddle().handingOver() ? Stick.MOUTH
                : !cargo.paddle() ? Stick.NONE
                : aboard && paddling() && helm.kind() == HullHelm.Kind.ROUTE ? Stick.BRAID : Stick.HULL_STOW;
        if (entityData.get(STICK) != now.ordinal()) entityData.set(STICK, now.ordinal());
    }
    /** The physical paddle and its displayed water stroke share this server-started cycle. */
    public double paddleCycle(float partial) {
        double ticks = Math.max(0, level().getGameTime()-entityData.get(STROKE_START)+partial);
        return (ticks%32)/32;
    }
    public double paddlePower(float partial) {
        return paddling() && cargo.paddle() ? Math.max(0, Math.sin(paddleCycle(partial)*Math.PI*2)) : 0;
    }
    public float currentStrength() { return entityData.get(CURRENT); }
    public boolean drifting() { return hullMotion.drifting(); }
    /** Light rocking is separate from an actual capsize, and can never itself overturn the hat. */
    public boolean rocking(){ return hullMotion.rocking(); }
    public boolean capsizing(){ return hullMotion.capsizing(); }
    public boolean deploying(){ return hullMotion.deploying(); }
    public boolean selfRescueHeld(){ return hullMotion.selfRescueHeld(); }
    /** Held geometry and its braid share a tick position instead of the ordinary three-step entity lerp. */
    public Vec3 motionPosition(float partial){ return hullMotion.motionPosition(partial); }
    public float hullSquash(float partial){ return hullMotion.hullSquash(partial); }
    public boolean selfRighting(){ return hullMotion.selfRighting(); }
    public float selfRightingProgress(float partial){ return hullMotion.selfRightingProgress(partial); }
    /** The dip is only a bounded weight shift: it is deliberately not unsteady motion, so footing and stability stay valid. */
    public boolean unsteadyMotion(){ return hullMotion.unsteadyMotion(); }
    float dipValue(float partial){ return hullMotion.dipValue(partial); }
    /** Synced tilt toward the lowered near rim, in degrees (0 .. MAX_DIP). */
    public float dipDegrees(float partial){ return hullMotion.dipDegrees(partial); }
    /** Lowered-rim direction in Minecraft yaw (degrees) in the hat's own frame: local direction (-sin, 0, cos). */
    public float dipYaw(){ return hullMotion.dipYaw(); }
    public Dip dip(){ return hullMotion.dip(); }
    /** How far the near rim is lowered at this hat-local point, in blocks; zero on the far side and when level. */
    public double rimLift(Vec3 localPoint){ return hullMotion.rimLift(localPoint); }
    public static double rimLift(Vec3 localPoint,Dip dip,double unit){ return HatHullMotion.rimLift(localPoint, dip, unit); }
    /** The hat can take a small weight shift now: afloat, steady, not flooding, and its captain (if any) is aboard and idle. */
    public boolean dippable(YukkuriEntity actor){ return hullMotion.dippable(actor); }
    /** The tilt dipToward would settle at for this target, without changing anything; Dip.NONE if it cannot dip. */
    public Dip planDip(Vec3 worldTarget,float maxDegrees){ return hullMotion.planDip(worldTarget, maxDegrees); }
    /** Ask the hull to lean its near rim toward this point (the visitor's weight). Renew every few ticks; it lapses after DIP_RENEW_TICKS. */
    public boolean dipToward(Vec3 worldTarget,float maxDegrees,int rampTicks){ return hullMotion.dipToward(worldTarget, maxDegrees, rampTicks); }
    /** Level the hull again over this many ticks; safe to call when not dipped. */
    public void releaseDip(int ticks){ hullMotion.releaseDip(ticks); }
    void tickDip(){ hullMotion.tickDip(); }
    public long motionStartTick(){ return hullMotion.motionStartTick(); }
    public double motionAge(CompoundTag motion,String key,float partial){ return hullMotion.motionAge(motion, key, partial); }
    /** Signed local Z roll, in degrees, applied after the ordinary boat yaw. */
    public float motionRoll(float partial){ return hullMotion.motionRoll(partial); }
    /** Local X pitch, in degrees. Matrix order is yaw * roll(Z) * pitch(X). */
    public float motionPitch(float partial){ return hullMotion.motionPitch(partial); }
    public float rockingAmplitude(){ return hullMotion.rockingAmplitude(); }
    /** Shaking builds up: each shove within a few seconds adds agitation (decays one step per 2 s), up to MAX_AGITATION. */
    public static final int MAX_AGITATION=6;
    public int agitation(){ return hullMotion.agitation(); }
    /** A bounded shove, not additive damage: the sway amplitude is capped, repeated shoves only raise the agitation that
     *  decides how the riders react. From agitation 3 a rider may be shaken into the water (young on the open
     *  brim far more easily than one sitting in the well, the owner never alone); at 5 the whole hat may roll over.
     *  A full hull rolls more (the load's roll factor), and at 15 degrees or more the stowed stick may slide into the water.
     *  Project design, the probabilities below are balance values. A null source is a scenario shove with no person. */
    public boolean shake(Entity source,int strength){ return hullMotion.shake(source, strength); }
    /** Demo helper: a rider falls into the water this many ticks from now (the ordinary shaken-off fall). */
    public boolean dropRiderAfter(YukkuriEntity rider,int ticks){ return hullMotion.dropRiderAfter(rider, ticks); }
    void tickDelayedDrops(){ hullMotion.tickDelayedDrops(); }
    /** Planning only (no motion): the checked water course for the captain {@code actor} toward a point, or null. */
    public HullCourse courseFor(YukkuriEntity actor, Vec3 destination) {
        if (destination == null || actor.getVehicle() != this || !actor.getUUID().equals(ownerYukkuri())
                || !protects(actor) || !isAfloat() || unsteadyMotion() || flooding() > 0
                || landing() || helm.driving() || crewState.busy()
                || hasIncoming() || !cargo.paddle() || !(level() instanceof ServerLevel server)) return null;
        var path = new ArrayList<>(HatBoatRoute.find(this, destination,
                p -> actor.aquaticLife().familyLife().allowsDestination(this, p)));
        Vec3 last = path.isEmpty() ? position() : path.getLast();
        // Grid routes stop near a target. Validate the remaining physical segment before approaching contact.
        boolean clear = last.distanceToSqr(destination) <= 3.25;
        int steps = Math.max(1, (int)Math.ceil(last.distanceTo(destination) / .1));
        for (int i = 1; clear && i <= steps; i++) {
            Vec3 point = last.lerp(destination, (double)i / steps);
            BlockPos water = BlockPos.containing(point.x, point.y - .12, point.z);
            clear = HatBoatRoute.calmSurface(level(), water)
                    && Math.abs(HatBoatRoute.surface(level(), water).y - point.y) <= .08
                    && level().getWorldBorder().isWithinBounds(water)
                    && actor.aquaticLife().familyLife().allowsDestination(this, point)
                    && HullFootprint.fits(server, this, this, point, getYRot(), hullScale(), 0).ok();
        }
        if (clear && last.distanceToSqr(destination) > .0025) path.add(destination);
        return path.isEmpty() ? null : new HullCourse(path, clear ? .0025 : .12);
    }
    public record HullCourse(List<Vec3> path, double toleranceSquared) {}
    /** Helm-only hooks: the synchronized stroke flag and the read-only helm readout. */
    public void syncPaddling(boolean on, boolean restart) {
        if (restart) entityData.set(STROKE_START, level().getGameTime());
        if (entityData.get(PADDLING) != on) entityData.set(PADDLING, on);
    }
    public void syncHelm(int kind, int status) { entityData.set(HELM_KIND, kind); entityData.set(HELM_STATUS, status); }
    public void syncVoyage(int phase) { if (entityData.get(VOYAGE_PHASE) != phase) entityData.set(VOYAGE_PHASE, phase); }
    public io.github.longshou.mcyukkuri.subspecies.boat.VoyagePhase voyagePhase() { var all = io.github.longshou.mcyukkuri.subspecies.boat.VoyagePhase.values(); return all[Math.clamp(entityData.get(VOYAGE_PHASE), 0, all.length - 1)]; }
    public HullHelm.Kind helmKind() { return HullHelm.Kind.values()[Math.clamp(entityData.get(HELM_KIND), 0, HullHelm.Kind.values().length - 1)]; }
    public HullHelm.State helmStatus() { return HullHelm.State.values()[Math.clamp(entityData.get(HELM_STATUS), 0, HullHelm.State.values().length - 1)]; }
    public HullHelm helm() { return helm; }
    public boolean protects(Entity rider) {
        return hasPassenger(rider) && (isAfloat() || catches() || state() == State.BEACHED)
                && !level().getFluidState(BlockPos.containing(getX(), getY()+.25, getZ())).is(FluidTags.WATER);
    }
    /** The separate deeper aquatic hat, retained even when the owner cannot be resolved. */
    public boolean aquaticHull() { return entityData.get(AQUATIC_HULL); }
    /** The growth stage at which this physical hat was deployed, retained while its owner is unloaded. */
    public FamilyLife.Stage accessoryStage() {
        var stages=FamilyLife.Stage.values(); return stages[Math.clamp(entityData.get(ACCESSORY_STAGE),0,stages.length-1)];
    }
    public void syncAccessoryIdentity(YukkuriEntity owner) {
        entityData.set(AQUATIC_HULL,owner.subspecies().kind()==SubspeciesLife.Kind.AQUATIC_MARISA);
        entityData.set(ACCESSORY_STAGE,owner.family().stage().ordinal());
        entityData.set(HULL_DAMAGE,Math.clamp(owner.headwear().damage(),0,2));
    }
    public int hullDamage() { return entityData.get(HULL_DAMAGE); }
    public State state() { return State.values()[Math.clamp(entityData.get(STATE), 0, State.values().length-1)]; }
    public boolean isAfloat() { return state() == State.AFLOAT; }
    /** The hat's size: its owner's growth, larger for the aquatic Marisa. */
    public float hullScale() { return entityData.get(SCALE); }
    public HatVessel.Role role() { return role; }
    public int flooding() { return hazards.flooding(); }
    public HullHazards hazards() { return hazards; }
    public void state(State next) { entityData.set(STATE, next.ordinal()); }
    @Override public boolean isPickable() { return !isRemoved(); }
    /** The hull box is a thin slab at the waterline; a wider pick margin lets a player aim at the brim past the riders to shake it. */
    @Override public float getPickRadius() { return catches()&&isVehicle()?0:.4f; }
    public boolean isCradle() { return state() == State.CRADLE; }
    public boolean isMoored() { return state() == State.MOORED; }
    /** Set down by its owner to catch young, on the ground or floated at a bank. */
    public boolean catches() { return isCradle() || isMoored(); }
    // A catching hat never blocks a birth outlet or a walking parent.
    @Override public boolean canBeCollidedWith() { return !isRemoved() && state()!=State.RESCUED && !catches(); }
    @Override protected boolean canAddPassenger(Entity passenger) {
        if (state() == State.CAPSIZED || state() == State.RESCUED || !(passenger instanceof YukkuriEntity y)) return false;
        if (y.getUUID().equals(ownerYukkuri())) return !catches() && getPassengers().stream().noneMatch(p -> p.getUUID().equals(ownerYukkuri()));
        return admits(y) && canBoard(y);
    }

    /** Server: young aboard without a place (after loading) get one; afloat, a body whose seat became unlawful (it grew, night fell,
     *  the captain grew heavy) is moved, and every errand that ended sends its rider home. Nobody else is touched. */
    private void tendSeats() {
        stations.tendRescueOwner();
        var owner = owner();
        if (owner != null && !catches()) deck.setCaptain(bodyOf(owner));
        else if (ownerYukkuri() != null) deck.dropAway(ownerYukkuri());
        deck.pruneCaptains(owner == null || catches() ? null : owner.getUUID());
        if (crewState.busy() || stations.rescueOwnerHeld()) return;
        var aboard = new LinkedHashMap<UUID, Body>();
        var locals = new LinkedHashMap<UUID, Vec3>();
        for (var rider : getPassengers()) if (rider instanceof YukkuriEntity y) {
            aboard.put(y.getUUID(), bodyOf(y));
            locals.put(y.getUUID(), toLocal(y.position()));
        }
        deck.reconcile(aboard.keySet());
        var tend = deck.tend(aboard, locals);
        for (var lift : tend.lifts()) {
            var parent = owner();
            if (parent != null && level() instanceof ServerLevel server && server.getEntity(lift.id()) instanceof YukkuriEntity child && parent != child
                    && parent.getVehicle() == this && stations.liftAllowed(child.getUUID())) beginCarry(child, parent, lift.to(), DeckLayout.NONE);
        }
        stations.tendReturns();
        if (!tend.moved().isEmpty() || !tend.lifts().isEmpty() || !tend.stuck().isEmpty()) syncSeats();
        if(level().isNight()&&!catches()&&owner!=null&&getControllingPassenger() instanceof YukkuriEntity parent){
            for(var p:youngPassengers())if(p instanceof YukkuriEntity y&&y.life().isSleeping()&&!changingSeat(y)&&!stations.isStationed(y.getUUID())){
                float yaw=SurfaceApproach.yaw(parent.position(),y.position());
                SurfaceApproach.face(y,yaw);
            }
        }
    }

    /** Before the hat is taken back its young leave it: capable ones hop out in turn, the others are lifted out by {@code taker} one at a time
     *  (transfer tickets). True while any is still aboard or a transfer is under way, so the caller waits. */
    public boolean releaseYoung(YukkuriEntity taker) { return CrewTransfers.releaseYoung(this, taker); }
    /** Nearest standable block beside water within a few blocks, or null (see {@link ShoreBerth#nearestBank}). */
    public BlockPos nearestBank() { return ShoreBerth.nearestBank(level(), blockPosition()); }
    @Override public LivingEntity getControllingPassenger() {
        for (var p : getPassengers()) if (p instanceof YukkuriEntity y && y.getUUID().equals(ownerYukkuri())) return y;
        return null;
    }
    @Override protected void positionRider(Entity passenger, MoveFunction move) {
        if (!hasPassenger(passenger)) return;
        // A transfer on this hull places the body from its curve; the frame the curve is in decides who is the vehicle.
        var moving = CrewState.movingBody(crewEntries(), passenger.getUUID());
        if (moving != null) {
            var at = moving.position(level(), level().getGameTime());
            if(at!=null){
                // CrewTransfer.tick is the sole server executor; rider repositioning must not move ahead of its sweep.
                if(level().isClientSide)move.accept(passenger,at.x,at.y,at.z);
                return;
            }
        }
        // Each rider occupies its own deck seat, including the close but visible night gathering; a late sync waits.
        var local = seatOf(passenger);
        if (local == null) {
            if (passenger.getUUID().equals(ownerYukkuri()) && passenger instanceof YukkuriEntity y) local = ownerSeat(y);
            else return;
        }
        var at = toWorld(local); var movement = seatMove(passenger);
        if (movement != null) {
            double time = Math.clamp(level().getGameTime()-movement.start(), 0, movement.ticks());
            Vec3 from = toWorld(movement.from());
            at = movement.arc() ? toWorld(YukkuriLocomotion.sample(movement.route(),time/Math.max(1,movement.ticks()))) : movement.route().isEmpty() ? from : walkingPosition(passenger, movement, time);
        }
        if(!level().isClientSide&&movement!=null&&movement.arc()&&passenger instanceof YukkuriEntity y)
            YukkuriLocomotion.step(y,at,YukkuriLocomotion.limits(y).maxHorizSpeed());
        else move.accept(passenger, at.x, at.y, at.z);
    }
    public static HatBoatEntity find(YukkuriEntity y) { return HatBoatLaunch.find(y); }
    public static HatBoatEntity owned(YukkuriEntity y) { return HatBoatLaunch.owned(y); }
    /** Reclaim the same empty hat after its young is safe aboard a parent, including a rescued capsize. */
    public boolean retrieveAfloat(YukkuriEntity child, HatBoatEntity home) { return HatBoatLaunch.retrieveAfloat(this, child, home); }
    /** Compatibility overload; recovery still requires the same real rim or braid contact. */
    public boolean retrieveAfloat(YukkuriEntity child, HatBoatEntity home, boolean reachFallback) { return HatBoatLaunch.retrieveAfloat(this, child, home, reachFallback); }
    /** Same finite physical contact for a floating or stranded empty hat; ownership is not remote reach. */
    public Vec3 retrievalGrip(YukkuriEntity actor){ return HatBoatLaunch.retrievalGrip(this, actor); }
    /** Parent-centre docking target with actual hull/AABB clearance; it grants no remote retrieval. */
    public Vec3 retrievalApproach(HatBoatEntity parentHat,YukkuriEntity child){ return HatBoatLaunch.retrievalApproach(this, parentHat, child); }
    double boxReach(Vec3 toward){ return HatBoatLaunch.boxReach(this, toward); }
    /** Put the hat back on: the young climb out first, onto the bank beside a floated hat. */
    public boolean retrieve(YukkuriEntity owner) { return HatBoatLaunch.retrieve(this, owner); }
    public boolean board(YukkuriEntity owner) { return HatBoatLaunch.board(this, owner); }
    public boolean ownsPhysicalHat(YukkuriEntity actor){ return hazards.ownsPhysicalHat(actor); }
    public static final class GripRim {
        public static List<Vec3> points(HatVessel.Mode mode){
            double freeboard=HatVessel.freeboard(mode);
            var out=new ArrayList<Vec3>();
            for(int i=0;i<32;i++){
                double angle=i*Math.PI/16,x=Math.cos(angle),z=Math.sin(angle);
                double reach=Math.max(0,HatVessel.extent(mode,0,0,x,z,0)-.125);
                out.add(new Vec3(x*reach,freeboard,z*reach));
            }
            return List.copyOf(out);
        }
    }
    /** A rim point as it actually stands: tilted by a capsize or righting, and lowered where a weight shift dips the near rim. */
    Vec3 rimPoint(Vec3 local){ return hazards.rimPoint(local); }
    Vec3 nearestGrip(YukkuriEntity actor){ return hazards.nearestGrip(actor); }
    /** The actual visible rim point the mouth will take hold of; ordinary body reach and block sight are both required. */
    public Vec3 selfRescueGripLocal(YukkuriEntity actor){ return hazards.selfRescueGripLocal(actor); }
    public Vec3 selfRescueGrip(YukkuriEntity actor,Vec3 localGrip){ return hazards.selfRescueGrip(actor, localGrip); }
    /** The rim point nearest this body's mouth that a few body-lengths of nudging can take in the teeth. */
    public Vec3 selfRescueGrip(YukkuriEntity actor){ return hazards.selfRescueGrip(actor); }
    Vec3 reachableGrip(YukkuriEntity actor,Vec3 grip){ return hazards.reachableGrip(actor, grip); }
    /** A swimming destination outside the true tilted brim; it never moves or boards its caller. */
    public Vec3 ownGripApproach(YukkuriEntity actor){ return hazards.ownGripApproach(actor); }
    /** Empty, already-spilled capsizes can be physically righted without replacing their token or cargo. */
    public boolean setSelfRighting(YukkuriEntity actor,float progress,Vec3 localGrip){ return hazards.setSelfRighting(actor, progress, localGrip); }
    public String selfRightingRefusal(){ return hazards.selfRightingRefusal(); }
    public Vec3 selfRightingOffset(Vec3 local,float progress){ return hullMotion.selfRightingOffset(local, progress); }
    public boolean finishSelfRighting(YukkuriEntity actor){ return hazards.finishSelfRighting(actor); }
    void tickSelfRighting(){ hazards.tickSelfRighting(); }
    public record SelfRescueDeployment(Vec3 position,float yaw,SelfRescueHatGeometry.Plan path){}
    /** Plan before the worn hat moves, so rendering and the eventual real vessel share one endpoint. */
    public static SelfRescueDeployment planSelfRescueDeployment(YukkuriEntity actor){ return HullHazards.planSelfRescueDeployment(actor); }
    /** The spot in front of the face must be calm open water the hull can float in; nothing is reached for, so no braid length applies. */
    public static boolean selfRescueDeploymentReachable(YukkuriEntity actor,SelfRescueDeployment plan){ return HullHazards.selfRescueDeploymentReachable(actor, plan); }
    /** Revalidate exactly the planned endpoint. Failure leaves the one original hat on its owner. */
    public static HatBoatEntity deployForSelfRescue(YukkuriEntity actor,SelfRescueDeployment plan){ return HullHazards.deployForSelfRescue(actor, plan); }
    /** Custody moves to the real vessel once the hat has been shaken clear of the head. */
    public static HatBoatEntity deployForSelfRescue(YukkuriEntity actor,SelfRescueDeployment plan,SelfRescueHatGeometry.Pose heldPose,
            float elapsed){ return HullHazards.deployForSelfRescue(actor, plan, heldPose, elapsed); }
    /** The shaken-off hat follows the same world-space prop origin as the worn-hat geometry. */
    public boolean setSelfRescueCarryPose(YukkuriEntity actor,SelfRescueHatGeometry.Pose pose,float elapsed){ return hazards.setSelfRescueCarryPose(actor, pose, elapsed); }
    public void finishSelfRescueCarry(YukkuriEntity actor){ hazards.finishSelfRescueCarry(actor); }
    /** A failed grip leaves the same physical hat at its actual pose and spills only via the normal capsize lifecycle. */
    public void releaseSelfRescueCarry(){ hazards.releaseSelfRescueCarry(); }
    /** The hull turns over for a real cause (see {@link HullHazards}). */
    public void capsize(String why) { hazards.capsize(why); }
    void startCapsize(int side,String why){ hazards.startCapsize(side, why); }
    /** Same yaw * roll * pitch transform as the client, used for each real release point. */
    public Vec3 tiltedWorld(Vec3 local){ return hazards.tiltedWorld(local); }
    void tickCapsize(){ hazards.tickCapsize(); }
    public void clearStations() { stations.clearAll(); }
    public void freezeSeatsForCapsize() {
        // Freeze the actual current places, including a child being carried or partway through a hop.
        var actual = new LinkedHashMap<UUID, Vec3>();
        for(var p:getPassengers()){
            actual.put(p.getUUID(), toLocal(p.position()));
            if(p instanceof YukkuriEntity y){y.getNavigation().stop();if (!y.family().adult()) y.mind().felt(EmotionEvent.FRIGHTENED); y.showReaction(y.family()
                    .adult()?"surprise":"teary",y.family().adult()?"wait_family":"afraid",60);}
        }
        deck.freeze(actual);
        crewState.abortAll("capsize");
        stations.clearAll();syncSeats();
    }

    public YukkuriEntity captain() { return owner(); }
    public YukkuriEntity owner() {
        return ownerYukkuri() != null && level() instanceof ServerLevel server && server.getEntity(ownerYukkuri()) instanceof YukkuriEntity y ? y : null;
    }
    /** The captain's voyage is docking, unloading or putting the hat away: the hull is being handled, not sailed. */
    public boolean landing(){var y=owner();return y!=null&&y.aquaticLife().voyage().landing();}
    /** Return the one accessory only after everyone has left and the captain is on dry land at the hat; the hat then leaves the water. */
    public boolean finishLanding(YukkuriEntity owner,UUID rescuer){
        if(isVehicle()||hasIncoming()||owner.isPassenger()||!owner.isAlive()||!owner.getUUID().equals(ownerYukkuri())||state()!=State.AFLOAT
                ||!owner.life().returnAccessory(ownerYukkuri(),accessoryToken,rescuer))return false;
        crew.clear();cargo.unload(owner,accessoryToken);owner.aquaticLife().landed();
        accessoryToken=null;state(State.RESCUED);helm.stop("retired");return true;
    }
    @Override public InteractionResult interactAt(Player player,Vec3 localHit,InteractionHand hand){
        if(player.isSpectator()||player.distanceToSqr(this)>36)return InteractionResult.PASS;
        var hit=position().add(localHit);
        var target=HatHitResolver.resolve(this,player,hit);
        if(target==null)return interact(player,hand);
        var child=target.passenger();var relative=target.point().subtract(child.position());
        var result=CommonHooks.onInteractEntityAt(player,child,relative,hand);
        if(result==null){
            result=child.interactAt(player,relative,hand);
            if(!result.consumesAction())result=player.interactOn(child,hand);
        }
        if(!level().isClientSide)GameplayLog.limited(this,"interaction","hat_passenger."+player.getUUID(),20,
                "hat passenger use player={} hand={} passenger={} hit={} result={}",player.getUUID(),hand,child.getUUID(),hit,result);
        // The click selected this child; its PASS must not subsequently shake or feed the surrounding hull.
        return InteractionResult.sidedSuccess(level().isClientSide);
    }
    @Override public InteractionResult interact(Player player, InteractionHand hand) {
        GameplayLog.detail(this,"hat.use."+getUUID()+"."+player.getUUID()+"."+hand,20,
                "hat use received player="+player.getUUID()+" hand="+hand+" empty="+player.getItemInHand(hand).isEmpty()
                        +" state="+state()+" distanceSqr="+player.distanceToSqr(this)+" spectator="+player.isSpectator());
        if (hand != InteractionHand.MAIN_HAND)
            return playerInteractionResult(player,hand,"off_hand",false,InteractionResult.PASS);
        if (player.distanceToSqr(this) > 36)
            return playerInteractionResult(player,hand,"out_of_reach",false,InteractionResult.PASS);
        var held = player.getItemInHand(hand);
        if (catches()) {
            if (!held.isEmpty()) return playerInteractionResult(player,hand,"cradle_requires_empty_hand",false,InteractionResult.PASS);
            if (!level().isClientSide) {
                var keeper=owner();
                player.displayClientMessage(Component.literal("接生帽：幼体 "+youngPassengers().size()+"；"
                        +(keeper==null?"帽主未在加载范围内":keeper.family().keepsCradle()?"正在接生或安置幼体":"等待帽主接回")),true);
            }
            return playerInteractionResult(player,hand,"cradle_status",true,InteractionResult.sidedSuccess(level().isClientSide));
        }
        if (held.is(YukkuriEntity.FOOD)) {
            if (level().isClientSide) return InteractionResult.SUCCESS;
            boolean stored=storePlayerFood(player,held);
            return playerInteractionResult(player,hand,"store_food",stored,InteractionResult.CONSUME);
        }
        if (level().isClientSide) return InteractionResult.SUCCESS;
        // Empty hands and non-food items only shove the hull; they never issue a behavior request.
        boolean shaken=shake(player,1);
        return playerInteractionResult(player,hand,"shake",shaken,InteractionResult.CONSUME);
    }
    /** Record the executed branch and its real result, including consumed clicks that produced no physical change. */
    private InteractionResult playerInteractionResult(Player player,InteractionHand hand,String action,boolean applied,InteractionResult result){
        if(!level().isClientSide)GameplayLog.limited(this,"interaction","hat_use."+hand,20,
                "hat use result player={} hand={} action={} applied={} result={} state={} empty={} distanceSqr={} passengers={} incoming={} cargo={} rocking={}",
                player.getUUID(),hand,action,applied,result,state(),player.getItemInHand(hand).isEmpty(),player.distanceToSqr(this),
                getPassengers().size(),hasIncoming(),cargo.count(),rocking());
        return result;
    }
    private boolean storePlayerFood(Player player,ItemStack held){
        var y=owner();
        String reason=!isAfloat()?"not_afloat":y==null?"owner_unavailable":!y.life().acceptsFood(held)?"not_accepted":null;
        if(reason==null&&!cargo.canStore(held))reason=cargoRoom()<=0?"full":"slots";
        if(reason!=null){refusePlayerFood(player,held,reason);return false;}
        return cargo.store(held,player.isShiftKeyDown()?8:1,player.getAbilities().instabuild)>0;
    }
    private void refusePlayerFood(Player player,ItemStack held,String reason){
        var message="full".equals(reason)
                ?Component.translatable("interaction.mcyukkuri.hat_cargo.full",String.format(Locale.ROOT,"%.1f",deck.load()),String.format(Locale.ROOT,"%.0f",deck.maxLoad()))
                :Component.translatable("interaction.mcyukkuri.hat_cargo."+reason);
        player.displayClientMessage(message,true);
        GameplayLog.limited(this,"cargo","player_store."+reason,200,
                "player food storage refused player={} owner={} item={} reason={} load={}/{} cargoRoom={} larder={} paddle={} deck={}",
                player.getUUID(),ownerYukkuri(),held.getItem(),reason,String.format(Locale.ROOT,"%.1f",deck.load()),deck.maxLoad(),cargoRoom(),
                deck.heapRefusal(cargo.count()+1),cargo.paddle(),deck.lastRefusal());
    }
    @Override public boolean hurt(DamageSource source, float amount) {
        if (isInvulnerableTo(source) || level().isClientSide || amount <= 0 || isRemoved()) return false;
        if (hazards.fireHit(source, amount)) { markHurt(); return true; }
        if (catches()) return false;
        var attacker=source.getDirectEntity();
        // A bare-handed punch or stick poke shakes the hat harder than a hand shove; only a real blow turns it over.
        if(attacker instanceof Player puncher&&amount<4&&isAfloat()){shake(puncher,2);return true;}
        if(attacker!=null)startCapsize(toLocal(attacker.position()).x>=0?-1:1,"struck");else capsize("damaged");
        markHurt(); return true;
    }
    @Override public void remove(RemovalReason reason) {
        if (!level().isClientSide && crewState != null) crewState.abortAll("hull_removed");
        if (!level().isClientSide && reason == RemovalReason.KILLED && hazards != null) hazards.destroy("destroyed");
        super.remove(reason);
    }
    @Override public void tick() {
        super.tick();
        if (level().isClientSide) {
            if (lerpSteps > 0) {
                setPos(getX()+(lerpX-getX())/lerpSteps,getY()+(lerpY-getY())/lerpSteps,getZ()+(lerpZ-getZ())/lerpSteps);
                setYRot(getYRot()+Mth.wrapDegrees(lerpYaw-getYRot())/lerpSteps);setXRot(getXRot()+(lerpPitch-getXRot())/lerpSteps);
                lerpSteps--;
            }
            return;
        }
        tickServer();
    }
    private void tickServer() {
        hazards.tick();
        if (isRemoved()) return;
        var accessoryOwner=owner();
        if(accessoryOwner!=null)syncAccessoryIdentity(accessoryOwner);
        if (state() == State.RESCUED) { if (tickCount % 20 == 0) discard(); return; }
        tickDip();
        if(hullMotion.agitation>0&&tickCount%40==0)hullMotion.agitation--;
        tickDelayedDrops();
        if(selfRescueHeld()){
            var holder=owner();var held=entityData.get(MOTION);
            if(holder!=null&&holder.isAlive()&&(holder.aquaticLife().selfRescue().phase()==AquaticSelfRescue.Phase.REMOVE_HAT
                    ||holder.aquaticLife().selfRescue().phase()==AquaticSelfRescue.Phase.DEPLOY_HAT)
                    &&level().getGameTime()-held.getLong("HeldTouched")<=2){helm.stop("self_rescue_held");setDeltaMovement(Vec3.ZERO);return;}
            releaseSelfRescueCarry();
        }
        tickCapsize();
        tickSelfRighting();
        if(selfRighting()&&level().getGameTime()-entityData.get(MOTION).getLong("RightTouched")<=1){
            // The continuously held rim is the physical pivot. Buoyancy resumes as soon as grip is released.
            setDeltaMovement(Vec3.ZERO);helm.stop("self_righting");return;
        }
        helm.tick();
        deck.tick();
        crewState.reap(level().getGameTime());
        tickLoad();
        syncStick();
        if (tickCount % 20 == 0) stations.tickLeases();
        if(state()!=State.CAPSIZED){
            cargo.tick();
            crowding.tick();
            if (tickCount % 10 == 0&&!crowding.contesting()&&!landing()) tendSeats();
        }
        syncSeats();
        syncCrew();
        if (catches()) {
            if (isCradle()) {  // resting on the ground
                var fall = new Vec3(0, Math.max(-.2, getDeltaMovement().y - .04), 0); setDeltaMovement(fall); move(MoverType.SELF, fall);
            } else {  // floated at the bank: rides the surface where it was set, the braid keeping it from drifting
                var at = BlockPos.containing(getX(), getY()-.12, getZ()); var fluid = level().getFluidState(at);
                double lift = fluid.is(FluidTags.WATER) ? Math.clamp((at.getY()+fluid.getHeight(level(), at)+.025-sinkBlocks()-getY())*.35, -.12, .12) : -.04;
                var to = helm.mooringPull();
                var motion = new Vec3(to.x, lift, to.z); setDeltaMovement(motion); move(MoverType.SELF, motion);
            }
            for (var p : getPassengers()) if (p instanceof YukkuriEntity rider) { rider.getNavigation().stop(); rider.fallDistance = 0; }
            return;
        }
        var pos = BlockPos.containing(getX(),getY()-.12+sinkBlocks(),getZ());
        if (!level().hasChunkAt(pos)) return;
        var fluid = level().getFluidState(pos);
        boolean water = fluid.is(FluidTags.WATER);
        var motion = getDeltaMovement();
        if (water) {
            if (state() == State.BEACHED) {
                state(State.AFLOAT);
                GameplayLog.event(this, "aquatic", "hull refloated water={} owner={}", pos, ownerYukkuri());
            }
            double surface = pos.getY()+fluid.getHeight(level(),pos);
            var flow = fluid.getFlow(level(), pos);
            float current=(float)(Math.round(Math.min(1,flow.horizontalDistance())*16)/16.0);
            if(entityData.get(CURRENT)!=current)entityData.set(CURRENT,current);
            // A fuller hull rides lower: the whole boat, seats and riders with it, sinks by the load's waterline drop.
            double buoyancy = hazards.sinking() ? Math.max(-.045,motion.y-.006)
                    : Math.clamp((surface + .025 - sinkBlocks() - getY())*.35,-.12,.12);
            motion = new Vec3(motion.x*.88+flow.x*.016,buoyancy,motion.z*.88+flow.z*.016);
            motion = helm.steer(motion);
            // Water over the rim comes in faster the more the hull is loaded (the hazards own the counter and the capsize at its limit).
            hazards.rimWater(level().getFluidState(pos.above()).is(FluidTags.WATER) || flow.y<-.1);
        } else {
            entityData.set(CURRENT,0f);
            motion = new Vec3(motion.x*.6,Math.max(-.2,motion.y-.04),motion.z*.6);
            if (onGround() && state()!=State.CAPSIZED && state()!=State.BEACHED) {
                state(State.BEACHED);
                GameplayLog.event(this, "aquatic", "hull grounded surface={} block={} owner={}", pos,
                        level().getBlockState(pos), ownerYukkuri());
            }
        }
        setDeltaMovement(motion); move(MoverType.SELF,motion);
        if(entityData.get(MOTION).contains("LooseAt")&&motionAge(entityData.get(MOTION),"LooseAt",0)<14){
            var m=entityData.get(MOTION).copy();point(m,"PreviousCentre",m.contains("HeldCentreX")?point(m,"HeldCentre"):position());point(m,"HeldCentre",position());syncMotion(m);
        }
        for (var p : getPassengers()) if (p instanceof YukkuriEntity rider) { rider.getNavigation().stop(); rider.fallDistance = 0; }
        var y = owner();
        if (y!=null && y.getVehicle()==this) {
            entityData.set(SCALE,HatVessel.hatScale(y));
        }
    }
    /** Yaw and pitch ease over the same steps as the position, so riders do not judder on each packet. */
    @Override public void lerpTo(double x,double y,double z,float yaw,float pitch,int steps) {
        lerpX=x;lerpY=y;lerpZ=z;lerpYaw=yaw;lerpPitch=pitch;lerpSteps=Math.max(1,steps);
    }
    @Override protected void addAdditionalSaveData(CompoundTag tag) {
        hazards.save(tag);
        if(ownerYukkuri()!=null)tag.putUUID("OwnerYukkuri",ownerYukkuri());
        if(accessoryToken!=null)tag.putUUID("AccessoryToken",accessoryToken);
        tag.putString("State",state().name());tag.putFloat("HullScale",hullScale());
        tag.putBoolean("AquaticHull",aquaticHull());tag.putInt("AccessoryStage",accessoryStage().ordinal());
        var motion=entityData.get(MOTION);tag.putInt("MotionSide",motion.getInt("Side"));
        if(selfRescueHeld()){tag.putBoolean("SavedSelfHeld",true);tag.putFloat("SavedHeldPitch",motionPitch(1));tag.putFloat("SavedHeldSquash",hullSquash(1));}
        if(motion.contains("LooseAt")){tag.putInt("LooseAge",(int)Math.min(14,motionAge(motion,"LooseAt",0)));tag.putFloat("LoosePitch",
                motion.getFloat("LoosePitch"));tag.putFloat("LooseSquash",motion.getFloat("LooseSquash"));}
        if(deploying())tag.putInt("DeployAge",(int)motionAge(motion,"DeployAt",0));
        if(selfRighting())tag.putFloat("SelfRightProgress",motion.getFloat("RightProgress"));
        if(rocking()){
            tag.putInt("RockAge",(int)motionAge(motion,"RockAt",0));tag.putFloat("RockAmplitude",motion.getFloat("Amplitude"));
        }
        if(state()==State.CAPSIZED&&motion.contains("CapAt")){
            tag.putInt("CapsizeAge",Math.min(CAPSIZE_TICKS,(int)motionAge(motion,"CapAt",0)));
            tag.putBoolean("CapsizeReleased",hazards.capsizeReleased());
        }
        tag.putString("Role",role.name());if(helm.mooring()!=null){tag.putDouble("MooringX",helm.mooring().x);tag.putDouble("MooringZ",helm.mooring().z);}
        tag.put("Cargo", cargo.save());
        tag.put("Crowding",crowding.save());
        tag.put("Deck",deck.save());
        var young=new ListTag();for(var id:crew)young.add(NbtUtils.createUUID(id));tag.put("Crew",young);
    }
    @Override protected void readAdditionalSaveData(CompoundTag tag) {
        hazards.load(tag);
        stations.clearAll();
        crowding.load(tag.getCompound("Crowding"));
        entityData.set(OWNER,tag.hasUUID("OwnerYukkuri")?Optional.of(tag.getUUID("OwnerYukkuri")):Optional.empty());
        accessoryToken=tag.hasUUID("AccessoryToken")?tag.getUUID("AccessoryToken"):null;
        try{state(State.valueOf(tag.getString("State")));}catch(IllegalArgumentException invalid){state(State.BEACHED);}
        entityData.set(SCALE,Math.clamp(tag.getFloat("HullScale"),.1f,2.5f));
        entityData.set(AQUATIC_HULL,!tag.contains("AquaticHull")||tag.getBoolean("AquaticHull"));
        entityData.set(ACCESSORY_STAGE,Math.clamp(tag.getInt("AccessoryStage"),0,FamilyLife.Stage.values().length-1));
        role="SELF".equals(tag.getString("Role"))?HatVessel.Role.SELF:HatVessel.Role.HELPER;
        cargo.load(tag.getCompound("Cargo")); syncCargo(cargo.save());
        deck.load(tag.getCompound("Deck"));
        var motion=new CompoundTag();motion.putInt("Side",tag.getInt("MotionSide")<0?-1:1);
        hazards.capsizeReleased(state()==State.CAPSIZED);
        if(state()==State.CAPSIZED&&tag.contains("CapsizeAge")){
            int age=Math.clamp(tag.getInt("CapsizeAge"),0,CAPSIZE_TICKS);
            motion.putLong("CapAt",level().getGameTime()-age);hazards.capsizeReleased(tag.getBoolean("CapsizeReleased"));
        }else if(isAfloat()&&tag.contains("RockAge")){
            motion.putLong("RockAt",level().getGameTime()-Math.clamp(tag.getInt("RockAge"),0,ROCK_TICKS));
            motion.putFloat("Amplitude",Math.clamp(tag.getFloat("RockAmplitude"),0,18));
        }
        if(isAfloat()&&tag.contains("DeployAge"))motion.putLong("DeployAt",level().getGameTime()-Math.clamp(tag.getInt("DeployAge"),0,20));
        if(state()==State.CAPSIZED&&tag.contains("SelfRightProgress")){
            float progress=Math.clamp(tag.getFloat("SelfRightProgress"),0,1);
            motion.putFloat("RightPrevious",progress);motion.putFloat("RightProgress",progress);motion.putLong("RightTouched",level().getGameTime());
        }
        if(tag.getBoolean("SavedSelfHeld")){
            // Reload never invents a handhold or puts this token back on the owner. The actual vessel drops here.
            state(State.CAPSIZED);hazards.capsizeReleased(false);motion.putLong("CapAt",level().getGameTime());motion.putLong("LooseAt",level().getGameTime());
            motion.putFloat("LoosePitch",tag.getFloat("SavedHeldPitch"));motion.putFloat("LooseSquash",tag.getFloat("SavedHeldSquash"));
        }else if(tag.contains("LooseAge")){
            motion.putLong("LooseAt",level().getGameTime()-Math.clamp(tag.getInt("LooseAge"),0,14));
            motion.putFloat("LoosePitch",tag.getFloat("LoosePitch"));motion.putFloat("LooseSquash",tag.getFloat("LooseSquash"));
        }
        syncMotion(motion);syncSeats();
        helm.moor(tag.contains("MooringX")?new Vec3(tag.getDouble("MooringX"),0,tag.getDouble("MooringZ")):null);
        crew.clear();for(var raw:tag.getList("Crew",Tag.TAG_INT_ARRAY))crew.add(NbtUtils.loadUUID(raw));
    }
}
