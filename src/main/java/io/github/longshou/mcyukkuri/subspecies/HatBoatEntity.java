package io.github.longshou.mcyukkuri.subspecies;

import io.github.longshou.mcyukkuri.entity.Behaviors;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.entity.YukkuriVariant;
import io.github.longshou.mcyukkuri.family.FamilyFeedingContact;
import io.github.longshou.mcyukkuri.registry.ModEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
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
import java.util.UUID;

/** One real hat (her own hat turned upside down, its soft crown pressed down: HatVessel) and one Marisa owner. Afloat it is
 *  the aquatic Marisa's boat: she rests in its opening at the stern and her young ride in its dry inside, close before her at night.
 *  Unloading never means rescue or death. */
public final class HatBoatEntity extends Entity implements HatBoatView {
    final HatHullMotion hullMotion = new HatHullMotion(this);
    final HatDepartureBerth departure = new HatDepartureBerth(this);
    final HatBoatCapsize capsizeMotion = new HatBoatCapsize(this);
    CompoundTag motionData() { return entityData.get(MOTION); }
    void syncMotion(CompoundTag data) { entityData.set(MOTION, data); }
    CompoundTag dipData() { return entityData.get(DIP); }
    void syncDip(CompoundTag data) { entityData.set(DIP, data); }
    void markHullHurt() { markHurt(); }
    void syncHullScale(float scale) { entityData.set(SCALE, scale); }
    void syncOwner(Optional<UUID> owner) { entityData.set(OWNER, owner); }

    public float previousYaw() { return yRotO; }
    public CompoundTag seatsSnapshot() { return entityData.get(SEATS).copy(); }
    public CompoundTag cargoSnapshot() { return entityData.get(CARGO).copy(); }
    public CompoundTag motionSnapshot() { return entityData.get(MOTION).copy(); }
    /** CRADLE: a Marisa's hat turned over on dry ground, its soft crown pressed flat under it, to catch young. MOORED: an
     *  aquatic Marisa's hat floated at a bank to catch them, held in place. Ordinals are synced: append only. */
    public enum State { AFLOAT, BEACHED, CAPSIZED, RESCUED, REMOVED, CRADLE, MOORED }
    private static final EntityDataAccessor<Integer> STATE = SynchedEntityData.defineId(HatBoatEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Optional<UUID>> OWNER = SynchedEntityData.defineId(HatBoatEntity.class, EntityDataSerializers.OPTIONAL_UUID);
    private static final EntityDataAccessor<Float> SCALE = SynchedEntityData.defineId(HatBoatEntity.class, EntityDataSerializers.FLOAT);
    /** Where each young aboard sits (hat units), as the server gave it: where it landed, or the next free place. */
    private static final EntityDataAccessor<CompoundTag> SEATS = SynchedEntityData.defineId(HatBoatEntity.class, EntityDataSerializers.COMPOUND_TAG);
    private static final EntityDataAccessor<CompoundTag> CARGO = SynchedEntityData.defineId(HatBoatEntity.class, EntityDataSerializers.COMPOUND_TAG);
    private static final EntityDataAccessor<Boolean> PADDLING = SynchedEntityData.defineId(HatBoatEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Long> STROKE_START = SynchedEntityData.defineId(HatBoatEntity.class, EntityDataSerializers.LONG);
    private static final EntityDataAccessor<Float> CURRENT = SynchedEntityData.defineId(HatBoatEntity.class, EntityDataSerializers.FLOAT);
    /** One authoritative start time and direction; clients evaluate the same continuous curve. */
    private static final EntityDataAccessor<CompoundTag> MOTION = SynchedEntityData.defineId(HatBoatEntity.class, EntityDataSerializers.COMPOUND_TAG);
    /** Weight-shift dip toward one rim: From/To degrees, At game tick, Ticks ramp length and Yaw (see dipYaw). Never persisted. */
    private static final EntityDataAccessor<CompoundTag> DIP = SynchedEntityData.defineId(HatBoatEntity.class, EntityDataSerializers.COMPOUND_TAG);
    public static final int ROCK_TICKS=44, CAPSIZE_TICKS=16, CAPSIZE_RELEASE_TICK=9;
    public static final float MAX_DIP=5f;
    public static final int DIP_RENEW_TICKS=10;
    /** A hypothetical or current tilt: lowered-rim direction yaw (hat-local, degrees) and its angle. */
    public record Dip(float degrees,float yaw){ public static final Dip NONE=new Dip(0,0); }
    final HatBoatCargo cargo = new HatBoatCargo(this);
    final HatInteriorLayout interior = new HatInteriorLayout(this);
    private final java.util.ArrayDeque<Vec3> route = new java.util.ArrayDeque<>();
    int heldTicks;
    UUID accessoryToken;
    /** Who set the hat down to catch: a helper beside it, or the mother before her own outlet. */
    HatVessel.Role role = HatVessel.Role.HELPER;
    Vec3 mooring;
    int flooding;
    Vec3 pullTarget;
    int pullTicks;
    private double lerpX, lerpY, lerpZ;
    private int lerpSteps;
    /** Young born into the hat (water birth); kept after a capsize so a rescue can bring them ashore. */
    final java.util.LinkedHashSet<UUID> crew = new java.util.LinkedHashSet<>();
    /** Defensive search/save budget only. Actual room is measured from each child's body and the hat surfaces. */
    public static final int MAX_YOUNG = 16;
    private final HatBoatCrowding crowding=new HatBoatCrowding(this);
    private final AquaticLanding landing=new AquaticLanding(this);
    private final java.util.Set<UUID> cramped=new java.util.HashSet<>();
    /** Server only: the places (hat units x, y, z and the young's radius) of the young aboard, and those kept for young
     *  on their way in (a newborn's flight, a bounce off the brim). */
    final java.util.LinkedHashMap<UUID, double[]> seats = new java.util.LinkedHashMap<>(), incoming = new java.util.LinkedHashMap<>();
    String seatRegion = "";
    /** A real short trip inside the hat.  Crawling uses a level path; hops retain the normal safe arc. */
    record SeatMove(Vec3 from, long start, int ticks, boolean arc, java.util.List<Vec3> route) {
        SeatMove(Vec3 from,long start,int ticks,boolean arc){this(from,start,ticks,arc,java.util.List.of());}
    }
    final java.util.Map<UUID,SeatMove> seatMoves = new java.util.HashMap<>();
    /** A care action may temporarily move a young and the seated owner, then restores the normal layout. */
    private record ServiceSeat(double[] original, String originalRegion, double[] service, Vec3 ownerService, boolean rearCare, boolean toiletReady) {}
    final java.util.Map<UUID,ServiceSeat> serviceSeats = new java.util.HashMap<>();
    final java.util.Map<UUID,ServiceSeat> returningSeats = new java.util.HashMap<>();
    private record ReturnRetry(long geometryStamp,long at) {}
    private final java.util.Map<UUID,ReturnRetry> returnRetries = new java.util.HashMap<>();
    private String carePlanDebug="not planned";
    private String carryPlanDebug="not planned";
    private String carryTransferDebug="not checked";
    /** Last read-only planner outcome, for server failure reports; never persisted or used as state. */
    public String carePlanDebug(){return carePlanDebug+"; carry="+carryPlanDebug
            +"; returning="+returningSeats.size()+" service="+serviceSeats.size()
            +" phase="+(carryTrip==null?"none":carryTrip.phase);}
    /** -2 crawl on one tread, -1 tuck, 0 approach, 1 lift, 2 carry, 3 lower, 4 retreat, 5 unfold. */
    private static final class CarryTrip {
        final UUID child;
        final Vec3 from, destination, pickup, ownerDestination, offset;
        Vec3 facing;
        Vec3 lastAnchor;
        int phase, contact;
        long at;
        HatCareGeometry.Context geometry;
        CarryTrip(UUID child, Vec3 from, Vec3 destination, Vec3 pickup, Vec3 ownerDestination, Vec3 offset, long at) {
            this.child=child; this.from=from; this.destination=destination; this.pickup=pickup;
            this.ownerDestination=ownerDestination; this.offset=offset; this.at=at;
            this.lastAnchor=from;
            this.facing=offset.multiply(1,0,1).normalize();
        }
    }
    CarryTrip carryTrip;
    private boolean recoverCarry;
    private Vec3 ownerServiceSeat;
    boolean rescueOwnerHeld;
    private long boardingEdgeUntil;
    private SeatMove ownerSeatMove;
    /** The owner also uses a supported rear brim seat for ordinary on-water hygiene. */
    private boolean ownerToiletSeat;
    /** One pupil at a time; its berth remains occupied while its own hat is away. */
    /** Young hopping out in turn before the hat is taken back: who, to which spot (world), from which game tick. */
    /** A child who cannot jump is lowered over the brim by its parent instead of initiating a hop itself. */
    private record Leaving(UUID young, Vec3 spot, long at, boolean assisted) {}
    private final java.util.ArrayList<Leaving> leaving = new java.util.ArrayList<>();
    private java.util.List<Vec3> seatCache = java.util.List.of();
    private String seatKey = "";

    public HatBoatEntity(EntityType<? extends HatBoatEntity> type, Level level) { super(type, level); blocksBuilding = true; }
    @Override protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(STATE, State.AFLOAT.ordinal()); builder.define(OWNER, Optional.empty()); builder.define(SCALE, 1f); builder.define(SEATS, new CompoundTag());
        builder.define(CARGO, new CompoundTag()); builder.define(PADDLING, false); builder.define(MOTION,new CompoundTag()); builder.define(DIP,new CompoundTag());
        builder.define(STROKE_START, 0L); builder.define(CURRENT, 0f);
    }
    @Override public void onSyncedDataUpdated(EntityDataAccessor<?> data) {
        super.onSyncedDataUpdated(data);
        if (CARGO.equals(data) && cargo != null && level().isClientSide) cargo.load(entityData.get(CARGO));
        if (SCALE.equals(data)) refreshDimensions();
    }
    @Override public net.minecraft.world.entity.EntityDimensions getDimensions(net.minecraft.world.entity.Pose pose) {
        return super.getDimensions(pose).scale(hullScale()/HatVessel.AQUATIC_SCALE);
    }
    public HatBoatCargo cargo() { return cargo; }
    /** A prospective heap must leave every current body and retained destination clear. */
    public int cargoRoom(){ return interior.storageRoom(); }
    public java.util.List<HatVessel.ProvisionZone> cargoZones(){
        var zones=interior.zones(cargo.innerCount(),cargo.asideCount());
        if(cargo.handling())zones.addAll(interior.zones(cargo.count()-cargo.targetAsideCount(),cargo.targetAsideCount()));
        return zones;
    }
    void addCargoFootprints(java.util.List<double[]> out) {
        for(var zone:cargoZones()){
            var p=zone.position();
            out.add(new double[]{p.x,p.y,p.z,zone.radius(),zone.height(),2});
        }
    }
    void syncCargo(CompoundTag data) { entityData.set(CARGO, data); seatRegion = ""; }
    boolean cargoFits(int inner,int aside){return interior.cargoFits(inner,aside);}
    boolean cargoLayoutBusy(){
        return unsteadyMotion()||towed()||carryTrip!=null||rescueOwnerHeld||departure.departureYielding!=null
                ||!serviceSeats.isEmpty()||!returningSeats.isEmpty()||hasIncoming()||ownerToiletSeat
                ||changingOwnerSeat()||youngPassengers().stream().anyMatch(this::changingSeat);
    }
    boolean canHandleCargo(){
        var parent=owner();
        return (isAfloat()||catches())&&!cargoLayoutBusy()&&parent!=null&&parent.isAlive()
                &&!parent.life().isSleeping()&&!parent.family().braidRescueActive()
                &&(parent.getVehicle()==this||catches()&&parent.distanceToSqr(this)<=9);
    }
    boolean canRestoreCargo(){
        var parent=owner();
        return canHandleCargo()&&!landing()&&departure.voyageYoung==null&&parent!=null
                &&!parent.family().laboring()&&!parent.aquaticLife().care().active()
                &&!parent.aquaticLife().firstVoyage().active()&&!parent.aquaticLife().familyLife().alarm().active();
    }
    Vec3 cargoSpillOrigin(){
        var zones=interior.zones(cargo.innerCount(),cargo.asideCount());
        var at=zones.isEmpty()?new Vec3(0,HatVessel.top(mode()),-3):zones.getFirst().position();
        return new Vec3(at.x,Math.max(at.y,HatVessel.top(mode())+.2),at.z);
    }

    /** A read-only admission probe, including a possible real temporary brim placement. */
    public boolean canPrepareCargoSpace(YukkuriEntity child){
        if(child==null||!young(child)||!(isAfloat()||catches()||state()==State.BEACHED))return false;
        if(retainedCargoSeat(child)!=null)return true;
        if(plannedSeat(child,null,false)!=null)return true;
        for(int aside=cargo.asideCount()+1;aside<=cargo.count();aside++)
            if(interior.cargoFits(cargo.count()-aside,aside)&&plannedSeat(child,null,false,cargo.count()-aside,aside)!=null)return true;
        return false;
    }
    /** Called only for a chosen, physically approaching child, never from broad entity-search predicates. */
    public boolean prepareCargoSpace(YukkuriEntity child,boolean emergency){
        if(level().isClientSide||child==null||!young(child)||!(isAfloat()||catches()||state()==State.BEACHED)
                ||!hasPassenger(child)&&!incoming.containsKey(child.getUUID())&&youngAboard()+incoming.size()>=MAX_YOUNG)return false;
        if(emergency){
            if(cargo.count()==0)return retainedCargoSeat(child)!=null||plannedSeat(child,null,true)!=null;
            var parent=owner();
            if(parent==null||!parent.isAlive()||parent.life().isSleeping()||parent.distanceToSqr(child)>64)return false;
            if(cargo.count()>0&&plannedSeat(child,null,true,0,0)!=null)cargo.jettison();
            return plannedSeat(child,null,true)!=null;
        }
        if(cargo.handling()){cargo.keepAside();return false;}
        if(retainedCargoSeat(child)!=null){if(cargo.asideCount()>0)cargo.keepAside();return true;}
        if(plannedSeat(child,null,false)!=null){if(cargo.asideCount()>0)cargo.keepAside();return true;}
        for(int aside=cargo.asideCount()+1;aside<=cargo.count();aside++)
            if(interior.cargoFits(cargo.count()-aside,aside)&&plannedSeat(child,null,false,cargo.count()-aside,aside)!=null){
                var parent=owner();if(parent!=null)parent.life().wakeInHat();
                cargo.moveAside(aside);return false;
            }
        return false;
    }
    /** Care routes may need the inner well even when the child's current seat itself is free. */
    public boolean prepareCareCargoSpace(YukkuriEntity child){
        if(level().isClientSide||child==null||!young(child))return false;
        if(cargo.handling()){cargo.keepAside();return false;}
        if(cargo.innerCount()==0||serviceSeats.containsKey(child.getUUID())||returningSeats.containsKey(child.getUUID())){
            if(cargo.asideCount()>0)cargo.keepAside();return true;
        }
        var parent=owner();var original=seats.get(child.getUUID());
        if(parent!=null&&original!=null&&serviceSeatFor(child,parent,original,false)!=null)return true;
        cargo.moveAside(cargo.count());return false;
    }
    /** A failed lift/landing route needs actual space, not an unrelated feeding-station probe. */
    public boolean prepareManipulationCargoSpace(YukkuriEntity child){
        if(level().isClientSide||child==null||!young(child))return false;
        if(cargo.handling()){cargo.keepAside();return false;}
        if(cargo.innerCount()==0){cargo.keepAside();return true;}
        var parent=owner();if(parent!=null)parent.life().wakeInHat();
        cargo.moveAside(cargo.count());return false;
    }
    /** Called before releasing a newborn: ordinary birth clears food to the side, not into the water. */
    public boolean prepareBirthCargoSpace(){
        if(level().isClientSide||!(isAfloat()||catches())||youngAboard()+incoming.size()>=MAX_YOUNG)return false;
        if(cargo.handling()){cargo.keepAside();return false;}
        if(cargo.innerCount()==0){if(cargo.asideCount()>0)cargo.keepAside();return true;}
        if(hasRoomForYoung()){if(cargo.asideCount()>0)cargo.keepAside();return true;}
        // The generic newborn envelope is deliberately conservative; the real newborn gets the final catch check.
        var parent=owner();if(parent!=null)parent.life().wakeInHat();
        cargo.moveAside(cargo.count());
        return false;
    }
    private Vec3 retainedCargoSeat(YukkuriEntity child){
        var seat=incoming.get(child.getUUID());
        if(seat==null&&child.getUUID().equals(departure.voyageYoung))seat=departure.voyageBerth;
        if(seat==null)return null;
        var at=new Vec3(seat[0],seat[1],seat[2]);
        // The boarding controller already owns support/contact validation, including emergency compression.
        // Cargo may invalidate its volume, but must not replace that controller's admission rules.
        return cargoSeatClear(child,at)?at:null;
    }
    boolean cargoSeatClear(YukkuriEntity child,Vec3 at){
        if(!child.getUUID().equals(ownerYukkuri())&&cargo.tier()>=3&&at.y<HatVessel.top(mode())-.001)return false;
        var shape=rescueBody(child,at);
        for(var zone:cargoZones())if(HatInteriorLayout.overlaps(zone,shape))return false;
        return true;
    }
    boolean hasInnerCargoReservations(){
        double top=HatVessel.top(mode())-.001;
        for(var map:java.util.List.of(seats,incoming))for(var seat:map.values())if(seat[1]<top)return true;
        if(departure.voyageBerth!=null&&departure.voyageBerth[1]<top)return true;
        for(var map:java.util.List.of(serviceSeats,returningSeats))for(var seat:map.values())
            if(seat.original()[1]<top||seat.service()[1]<top)return true;
        return youngPassengers().stream().anyMatch(p->toLocal(p.position()).y<top);
    }
    public boolean paddling() { return entityData.get(PADDLING); }
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
    /** The hat can take a small weight shift now: afloat, steady, not towed or flooding, and its captain (if any) is aboard and idle. */
    public boolean dippable(YukkuriEntity actor){ return hullMotion.dippable(actor); }
    /** The tilt dipToward would settle at for this target, without changing anything; Dip.NONE if it cannot dip. */
    public Dip planDip(Vec3 worldTarget,float maxDegrees){ return hullMotion.planDip(worldTarget, maxDegrees); }
    /** Ask the hull to lean its near rim toward this point (the visitor's weight). Renew every few ticks; it lapses after DIP_RENEW_TICKS. */
    public boolean dipToward(Vec3 worldTarget,float maxDegrees,int rampTicks){ return hullMotion.dipToward(worldTarget, maxDegrees, rampTicks); }
    /** Level the hull again over this many ticks; safe to call when not dipped. */
    public void releaseDip(int ticks){ hullMotion.releaseDip(ticks); }
    void tickDip(){ hullMotion.tickDip(); }
    public long motionStartTick(){ return hullMotion.motionStartTick(); }
    double motionAge(CompoundTag motion,String key,float partial){ return hullMotion.motionAge(motion, key, partial); }
    /** Signed local Z roll, in degrees, applied after the ordinary boat yaw. */
    public float motionRoll(float partial){ return hullMotion.motionRoll(partial); }
    /** Local X pitch, in degrees. Matrix order is yaw * roll(Z) * pitch(X). */
    public float motionPitch(float partial){ return hullMotion.motionPitch(partial); }
    public float rockingAmplitude(){ return hullMotion.rockingAmplitude(); }
    /** Shaking builds up: each shove within a few seconds adds agitation (decays one step per 2 s), up to MAX_AGITATION. */
    public static final int MAX_AGITATION=6;
    public int agitation(){ return hullMotion.agitation(); }
    /** A bounded shove, not additive damage: the sway amplitude is capped (12 degrees), repeated shoves only raise the
     *  agitation that decides how the riders react. From agitation 3 a rider may be shaken into the water (young on the open
     *  brim far more easily than one sitting in the well, the owner never alone); at 5 the whole hat may roll over.
     *  Project design, the probabilities below are balance values. A null source is a scenario shove with no person. */
    public boolean shake(Entity source,int strength){ return hullMotion.shake(source, strength); }
    /** Demo helper: a rider falls into the water this many ticks from now (the ordinary shaken-off fall). */
    public boolean dropRiderAfter(YukkuriEntity rider,int ticks){ return hullMotion.dropRiderAfter(rider, ticks); }
    void tickDelayedDrops(){ hullMotion.tickDelayedDrops(); }
    private double finalWaypointToleranceSquared = .12;
    public void stopPaddling() { route.clear(); finalWaypointToleranceSquared = .12; entityData.set(PADDLING, false); }
    public boolean paddleTo(Vec3 destination) {
        if (level().isClientSide || !isAfloat() || unsteadyMotion() || !cargo.paddle() || towed() || carryTrip != null) return false;
        return paddleAlong(destination == null ? java.util.List.of() : HatBoatRoute.find(this, destination));
    }
    /** A route already checked by the supervised first-voyage planner. */
    boolean paddleAlong(java.util.List<Vec3> path) {
        return paddleAlong(path,.12);
    }
    /** Shore gathering may need a precise final berth; ordinary travel and first voyages retain their usual tolerance. */
    boolean paddleAlong(java.util.List<Vec3> path,double finalToleranceSquared) {
        if (level().isClientSide || !isAfloat() || unsteadyMotion() || !cargo.paddle() || towed()) return false;
        boolean continuing = paddling();
        route.clear(); route.addAll(path);
        if (!continuing && !route.isEmpty()) entityData.set(STROKE_START, level().getGameTime());
        finalWaypointToleranceSquared = Double.isFinite(finalToleranceSquared) ? Math.clamp(finalToleranceSquared,.0004,.12) : .12;
        entityData.set(PADDLING, !route.isEmpty()); return !route.isEmpty();
    }
    /** The stick holds the boat briefly during boarding; sleeping boats still drift with the current. */
    public void holdPosition() { stopPaddling(); heldTicks = cargo.paddle() ? 5 : 0; }
    public boolean protects(Entity rider) {
        return hasPassenger(rider) && (isAfloat() || catches() || state() == State.BEACHED)
                && !level().getFluidState(BlockPos.containing(getX(), getY()+.25, getZ())).is(FluidTags.WATER);
    }
    public Vec3 ownerSeat(YukkuriEntity owner) {
        // Use the same complete collision body as the walking planner. The authored rear shell is
        // shorter than Minecraft's square body; using it put the captain through the stern wall.
        double back = Math.max(owner.getBbWidth()*.5/unit(),
                io.github.longshou.mcyukkuri.family.LiveBirthGeometry.back(owner)*owner.getScale()/unit());
        double rear = Math.max(0, HatVessel.OPENING[3]-back-.05);
        return new Vec3(0, HatVessel.travelSupport(mode(),0,rear,radiusOf(owner)),rear);
    }
    public UUID ownerYukkuri() { return entityData.get(OWNER).orElse(null); }
    public State state() { return State.values()[Math.clamp(entityData.get(STATE), 0, State.values().length-1)]; }
    public boolean isAfloat() { return state() == State.AFLOAT; }
    /** The hat's size: its owner's growth, larger for the aquatic Marisa. */
    public float hullScale() { return entityData.get(SCALE); }
    public HatVessel.Role role() { return role; }
    public int flooding() { return flooding; }
    void state(State next) { entityData.set(STATE, next.ordinal()); }
    @Override public boolean isPickable() { return !isRemoved(); }
    /** The hull box is a thin slab at the waterline; a wider pick margin lets a player aim at the brim past the riders to shake it. */
    @Override public float getPickRadius() { return .4f; }
    public boolean isCradle() { return state() == State.CRADLE; }
    public boolean isMoored() { return state() == State.MOORED; }
    /** Set down by its owner to catch young, on the ground or floated at a bank. */
    public boolean catches() { return isCradle() || isMoored(); }
    // A catching hat never blocks a birth outlet or a walking parent.
    @Override public boolean canBeCollidedWith() { return !isRemoved() && state()!=State.CAPSIZED && state()!=State.RESCUED && !catches(); }
    @Override protected boolean canAddPassenger(Entity passenger) {
        if (state() == State.CAPSIZED || state() == State.RESCUED || !(passenger instanceof YukkuriEntity y)) return false;
        if (y.getUUID().equals(ownerYukkuri())) return !catches() && getPassengers().stream().noneMatch(p -> p.getUUID().equals(ownerYukkuri()));
        return young(y) && hasRoomFor(y);
    }
    /** The owner's own young (as mother or father), not yet adult. */
    public boolean young(YukkuriEntity y) {
        return ownerYukkuri() != null && (ownerYukkuri().equals(y.family().mother()) || ownerYukkuri().equals(y.family().father())) && !y.family().adult();
    }
    public int youngAboard() { return (int) getPassengers().stream().filter(p -> !p.getUUID().equals(ownerYukkuri())).count(); }
    public boolean hasIncoming() { return !incoming.isEmpty(); }
    /** A cancelled airborne transfer releases only its incoming slot, not a separately retained voyage berth. */
    public void cancelIncoming(YukkuriEntity child){
        if(!level().isClientSide&&child!=null&&!hasPassenger(child))incoming.remove(child.getUUID());
    }
    public boolean hasRoomForYoung() {
        double newborn=HatVessel.radius(.8*io.github.longshou.mcyukkuri.family.LiveBirthGeometry.BABY_SCALE)/unit();
        return cargo.tier()<3&&(isAfloat()||state()==State.BEACHED||catches())&&youngAboard()+incoming.size()<MAX_YOUNG
                &&HatVessel.place(mode(),newborn,region(),null,layoutTaken(null),null,Double.NaN,HatVessel.newbornSpace(mode(),hullScale(),unit()))!=null;
    }
    /** Room for this young: a free place, or the one kept for it. */
    public boolean hasRoomFor(YukkuriEntity child) {
        if(child==null||!young(child)||!(isAfloat()||state()==State.BEACHED||catches()))return false;
        if(hasPassenger(child)||retainedCargoSeat(child)!=null)return true;
        if(youngAboard()+incoming.size()>=MAX_YOUNG)return false;
        return plannedSeat(child,null,false)!=null;
    }
    public String roomDebug(YukkuriEntity child){
        var taken=layoutTaken(child.getUUID());
        var body=HatVessel.bodySpace(child,hullScale(),unit());
        var free=HatVessel.place(mode(),radiusOf(child),region(),null,taken,null,Double.NaN,body);
        var plain=HatVessel.place(mode(),radiusOf(child),region(),null,taken,null);
        return "region="+region()+" r="+radiusOf(child)+" taken="+taken.size()+" body(h="+body.height()+",hat="+body.hatX()+"x"+body.hatZ()+") place="+free+" placeNoBody="+plain
                +" young="+young(child)+" mode="+mode();
    }
    public boolean hasSafeRoomFor(YukkuriEntity child){
        return cargo.tier()<3&&child!=null&&young(child)&&HatVessel.place(mode(),radiusOf(child),region(),null,
                layoutTaken(child.getUUID()),null,Double.NaN,HatVessel.bodySpace(child,hullScale(),unit()))!=null;
    }
    public boolean overcrowded(){return level().isClientSide?entityData.get(SEATS).getBoolean("Overcrowded"):!cramped.isEmpty();}
    public boolean needsOwnHat(YukkuriEntity child){
        return child!=null&&child.getVehicle()==this&&(cramped.contains(child.getUUID())||overcrowded())
                &&child.aquaticLife().firstVoyage().completed()&&child.aquaticLife().firstVoyage().readySize()
                &&!child.aquaticLife().firstVoyage().independent()&&child.subspecies().boatId()==null;
    }
    /** Close arrivals may claim a real, briefly compressed refuge patch. This never ejects a remote occupant. */
    public boolean requestEmergencyBerth(YukkuriEntity child){
        if(level().isClientSide||child==null||!young(child)||!isAfloat()||capsizing()
                ||!child.isAlive()||!hasPassenger(child)&&!incoming.containsKey(child.getUUID())&&youngAboard()+incoming.size()>=MAX_YOUNG)return false;
        var local=toLocal(child.position());double reach=radiusOf(child)+.25/unit();
        boolean footReach=Double.isFinite(HatVessel.footprint(mode(),local.x,local.z,reach))
                &&Math.abs(local.y-HatVessel.FREEBOARD)<=1.4/unit();
        if(!hasPassenger(child)&&(!(footReach||touchingOwnHat(child))||!child.hasLineOfSight(this)))return false;
        var have=incoming.get(child.getUUID());
        var place=have!=null?new Vec3(have[0],have[1],have[2]):plannedSeat(child,null,true);
        if(place==null)return false;
        crowding.emergency(child);
        if(!hasPassenger(child))incoming.put(child.getUUID(),new double[]{place.x,place.y,place.z,radiusOf(child)});
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
    double brimReachToward(Vec3 direction){
        var local=toLocal(position().add(direction)).multiply(1,0,1).normalize();
        if(local.lengthSqr()<.01)return 0;
        return Math.max(0,HatVessel.extent(mode(),0,0,local.x,local.z,0)-.125)*unit();
    }
    public Vec3 reserveEmergency(YukkuriEntity child){
        if(!requestEmergencyBerth(child))return null;
        return reserve(child,null);
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
        if(!prepareCargoSpace(child,true)||cargo.tier()>=3)return false;
        var inside=HatVessel.place(mode(),radiusOf(child),region(),null,layoutTaken(child.getUUID()),null,Double.NaN,HatVessel.bodySpace(child,hullScale(),unit()));
        var parent=owner();
        if(inside==null||parent==null||!serviceSeats.isEmpty()||!returningSeats.isEmpty())return false;
        if(parent.life().isSleeping())parent.life().wakeInHat();
        return moveCarePair(child,parent,new double[]{inside.x,inside.y,inside.z,radiusOf(child)},ownerSeat(parent));
    }
    public String crowdingState(Entity rider){return crowding.state(rider);}
    public long crowdingPhaseStart(Entity rider){return crowding.phaseStart(rider);}
    public UUID crowdingPusher(Entity rider){return crowding.pusher(rider);}
    public float riderBalanceRoll(Entity rider,float partial){return transportingInHat(rider)?0:crowding.roll(rider,partial);}
    public float riderBalancePitch(Entity rider,float partial){return transportingInHat(rider)?0:crowding.pitch(rider,partial);}
    CompoundTag crowdingData(){return entityData.get(SEATS).getCompound("Crowding");}
    void syncCrowding(){syncSeats();}
    double crowdRadius(Entity rider){return radiusOf(rider);}
    boolean crowdMovable(YukkuriEntity rider){
        return rider.getVehicle()==this&&!transportingInHat(rider)&&!changingSeat(rider)
                &&!serviceSeats.containsKey(rider.getUUID())&&!returningSeats.containsKey(rider.getUUID());
    }
    boolean crowdPosition(YukkuriEntity rider,Vec3 local){
        if(!cargoSeatClear(rider,local))return false;
        var target=toWorld(local);
        rider.move(MoverType.SELF,target.subtract(rider.position()));
        var reached=toLocal(rider.position());
        seats.put(rider.getUUID(),new double[]{reached.x,reached.y,reached.z,radiusOf(rider)});
        seatMoves.remove(rider.getUUID());
        return rider.position().distanceToSqr(target)<1e-6;
    }
    boolean crowdFall(YukkuriEntity child,Vec3 local,Vec3 direction,UUID pusher){
        var out=direction.multiply(1,0,1).normalize().yRot(turn());
        if(!crowdPosition(child,local))return false;
        child.stopRiding();
        child.setDeltaMovement(getDeltaMovement().add(out.scale(.075)).add(0,.015,0));child.hasImpulse=true;
        child.waterDistress().plunge(getUUID(),pusher);
        var parent=owner();if(parent!=null)parent.showReaction("surprise","wait_family",80);
        syncSeats();
        return true;
    }
    @Override public Vec3 getDismountLocationForPassenger(net.minecraft.world.entity.LivingEntity passenger){
        // Both the held child's lift and active rim boarding start at the actual feet.
        // Vanilla's relocation to the hull centre would invalidate the checked trajectory.
        if(passenger instanceof YukkuriEntity child&&(child.family().cradleHeld()
                ||child.family().boardingDismountInPlace(this)))return child.position();
        return super.getDismountLocationForPassenger(passenger);
    }
    /** A newborn delivered in the hat stays aboard with its mother. */
    public boolean boardYoung(YukkuriEntity child) {
        if (level().isClientSide || cargo.handling() || unsteadyMotion()&&!crowding.emergency(child.getUUID()) || !young(child) || !hasRoomFor(child)) return false;
        var seat = incoming.remove(child.getUUID());
        if(seat!=null&&!cargoSeatClear(child,new Vec3(seat[0],seat[1],seat[2])))seat=null;
        if (seat == null) { double r = radiusOf(child); var s = plannedSeat(child,null,false); if(s==null)return false;seat = new double[]{s.x, s.y, s.z, r}; }
        if (!child.startRiding(this, true)) return false;
        crew.add(child.getUUID());
        seats.put(child.getUUID(), seat); syncSeats(); return true;
    }
    private Vec3 plannedSeat(YukkuriEntity child,Vec3 prefer,boolean emergency){
        return plannedSeat(child,prefer,emergency,cargo.innerCount(),cargo.asideCount());
    }
    private Vec3 plannedSeat(YukkuriEntity child,Vec3 prefer,boolean emergency,int inner,int aside){
        double r=radiusOf(child);var taken=layoutTaken(child.getUUID(),inner,aside);
        var inside=HatBoatCargo.tierFor(inner)>=3?null:HatVessel.place(mode(),r,region(),prefer,taken,null,Double.NaN,HatVessel.bodySpace(child,hullScale(),unit()));
        if(emergency&&inside!=null)return inside;
        if(brimSafe(child)){
            long infants=youngPassengers().stream().filter(p->p!=child&&p instanceof YukkuriEntity y&&!y.childhood().canJump()&&onBrim(p)).count();
            if(child.childhood().canJump()||infants<2){
                var brim=child.childhood().canJump()?HatVessel.brimPlace(r,taken):HatVessel.refugeBrimPlace(r,taken,null,false);
                if(brim!=null)return brim;
            }
        }
        if(inside!=null)return inside;
        return emergency?HatVessel.refugeBrimPlace(r,taken,toLocal(child.position()),true):null;
    }
    @Override protected void removePassenger(Entity passenger) {
        super.removePassenger(passenger);
        if (!level().isClientSide) {
            returnRetries.remove(passenger.getUUID());
            boolean ownerLeft = passenger.getUUID().equals(ownerYukkuri());
            if (ownerLeft) {
                ownerToiletSeat = false;
                ownerServiceSeat = null;
                ownerSeatMove = null;
            }
            seatMoves.remove(passenger.getUUID());
            returningSeats.remove(passenger.getUUID());
            boolean hadService = serviceSeats.remove(passenger.getUUID()) != null;
            if (hadService && serviceSeats.isEmpty()) {
                var parent = owner();
                if (parent != null && parent.getVehicle() == this) moveOwnerSeat(parent, ownerSeat(parent), false);
            }
            if (ownerLeft || seats.remove(passenger.getUUID()) != null || hadService) syncSeats();
        }
    }
    // ---------------------------------------------------------------- the hat's frame and the young's places
    public HatVessel.Mode mode() { return isCradle() ? HatVessel.Mode.NEST : HatVessel.Mode.FLOAT; }
    /** Blocks per hat unit (the prop's units at this hat's size). */
    public double unit() { return hullScale()/16; }
    float turn() { return (float) Math.toRadians(180 - getYRot()); }
    public Vec3 toWorld(Vec3 local) { return position().add(local.scale(unit()).yRot(turn())); }
    public Vec3 toLocal(Vec3 world) { return world.subtract(position()).yRot(-turn()).scale(1/unit()); }
    private String region() { return catches() ? "catch" : !isMoored() && level().isNight() ? "night" : "day"; }
    double radiusOf(Entity young) { return HatVessel.radius(young.getBbWidth())/unit(); }
    java.util.List<Entity> youngPassengers() { return getPassengers().stream().filter(p -> !p.getUUID().equals(ownerYukkuri())).toList(); }
    private java.util.List<double[]> taken() {
        return takenExcept(null);
    }
    /** Ordinary sleeping and daytime berths stay outside the parent's body projection, even on a lower layer. */
    private java.util.List<double[]> layoutTaken(UUID excluded) {
        var out=takenExcept(excluded);
        addOwnerProjection(out);
        return out;
    }
    private java.util.List<double[]> layoutTaken(UUID excluded,int inner,int aside){
        var out=interior.taken(excluded,inner,aside);
        addOwnerProjection(out);
        return out;
    }
    private void addOwnerProjection(java.util.List<double[]> out){
        if(!catches()&&getControllingPassenger() instanceof YukkuriEntity parent){
            var at=ownerSeat(parent);
            out.add(new double[]{at.x,at.y,at.z,radiusOf(parent),parent.getBbHeight()/unit(),1});
        }
    }
    public boolean keepVoyageBerth(YukkuriEntity child) { return departure.keepVoyageBerth(child); }

    /**
     * A subadult's departure berth uses the same retained slot as the ordinary first voyage, but
     * supports the feet on the front rim rather than requiring its now-larger whole body to fit on
     * the narrow juvenile standing strip.  It is still a real, flat in-hat SeatMove.
     */
    public boolean keepDepartureBerth(YukkuriEntity child) { return departure.keepDepartureBerth(child); }
    public String departureDebug(){ return departure.departureDebug(); }
    public boolean preparingDepartureBerth(YukkuriEntity child){ return departure.preparingDepartureBerth(child); }
    /** APPROACH waits for the sibling's real walk, then rechecks actual bodies before reserving the pupil's feet. */
    public boolean advanceDepartureBerth(YukkuriEntity child){ return departure.advanceDepartureBerth(child); }
    boolean yieldDepartureSpace(YukkuriEntity child){ return departure.yieldDepartureSpace(child); }
    HatVessel.BodySpace departureBody(YukkuriEntity rider){ return departure.departureBody(rider); }
    java.util.List<Vec3> departureYieldRoute(YukkuriEntity rider,Vec3 destination){ return departure.departureYieldRoute(rider, destination); }
    boolean departureEnvelopesMeet(YukkuriEntity a,Vec3 at,float yaw,YukkuriEntity b,Vec3 bt,float byaw){ return departure.departureEnvelopesMeet(a, at, yaw, b, bt, byaw); }
    boolean departureEnvelopeClear(YukkuriEntity rider,Vec3 at,float yaw,YukkuriEntity ignored){ return departure.departureEnvelopeClear(rider, at, yaw, ignored); }
    public void releaseVoyageBerth(UUID child) { departure.releaseVoyageBerth(child); }
    public Vec3 voyageBerth(YukkuriEntity child) { return departure.voyageBerth(child); }

    /** The retained front support has been reached, rather than merely reserved in the seat map. */
    public boolean onVoyageBerth(YukkuriEntity child) { return departure.onVoyageBerth(child); }
    void syncSeats() {
        var list = new net.minecraft.nbt.ListTag();
        for (var e : seats.entrySet()) {
            var c = new CompoundTag(); c.putUUID("Id", e.getKey()); c.putDouble("X", e.getValue()[0]); c.putDouble("Y", e.getValue()[1]); c.putDouble("Z", e.getValue()[2]);
            var m=seatMoves.get(e.getKey());
            if(m!=null){c.putDouble("FromX",m.from().x);c.putDouble("FromY",m.from().y);c.putDouble("FromZ",m.from().z);c.putLong("At",m.start());c.putInt("Ticks",m.ticks());c.putBoolean("Arc",m.arc());HatSurfaceRoute.save(c,m.route());}
            list.add(c);
        }
        if (ownerServiceSeat != null && ownerYukkuri() != null) {
            var c = new CompoundTag(); c.putUUID("Id", ownerYukkuri()); c.putBoolean("OwnerService", true);
            c.putDouble("X", ownerServiceSeat.x); c.putDouble("Y", ownerServiceSeat.y); c.putDouble("Z", ownerServiceSeat.z);
            if (ownerSeatMove != null) { c.putDouble("FromX", ownerSeatMove.from().x); c.putDouble("FromY", ownerSeatMove.from().y); c.putDouble("FromZ", ownerSeatMove.from().z); c.putLong("At", ownerSeatMove.start()); c.putInt("Ticks", ownerSeatMove.ticks()); c.putBoolean("Arc", ownerSeatMove.arc());HatSurfaceRoute.save(c,ownerSeatMove.route()); }
            list.add(c);
        }
        var tag = new CompoundTag(); tag.put("S", list);
        if (carryTrip != null) tag.put("Carry", saveCarry(carryTrip));
        tag.put("Crowding",crowding.save());
        tag.putBoolean("Overcrowded",!cramped.isEmpty());
        entityData.set(SEATS, tag);
    }
    /** The place the server gave this young aboard, or null. */
    Vec3 seatOf(Entity young) {
        if (!level().isClientSide) { var s = seats.get(young.getUUID()); return s == null ? null : new Vec3(s[0], s[1], s[2]); }
        for (var raw : entityData.get(SEATS).getList("S", net.minecraft.nbt.Tag.TAG_COMPOUND))
            if (raw instanceof CompoundTag c && c.hasUUID("Id") && c.getUUID("Id").equals(young.getUUID())) return new Vec3(c.getDouble("X"), c.getDouble("Y"), c.getDouble("Z"));
        return null;
    }
    private SeatMove seatMove(Entity young) {
        if(!level().isClientSide)return seatMoves.get(young.getUUID());
        for(var raw:entityData.get(SEATS).getList("S",net.minecraft.nbt.Tag.TAG_COMPOUND))
            if(raw instanceof CompoundTag c&&c.hasUUID("Id")&&c.getUUID("Id").equals(young.getUUID())&&c.contains("At"))
                return new SeatMove(new Vec3(c.getDouble("FromX"),c.getDouble("FromY"),c.getDouble("FromZ")),c.getLong("At"),c.getInt("Ticks"),c.getBoolean("Arc"),HatSurfaceRoute.load(c));
        return null;
    }
    public boolean changingSeat(Entity young){var m=young instanceof YukkuriEntity y&&young.getUUID().equals(ownerYukkuri())?ownerServiceMove(y):seatMove(young);return m!=null&&level().getGameTime()-m.start()<m.ticks();}
    private boolean changingOwnerSeat() { return ownerSeatMove != null && level().getGameTime()-ownerSeatMove.start() < ownerSeatMove.ticks(); }

    /**
     * Moves both members of a care pair inside the same protected hat.  The child gets a supported
     * temporary berth and the captain makes a small real SeatMove from her normal stern berth;
     * callers must still wait for {@link #careSeatReady(YukkuriEntity)} and test their own contact.
     */
    public boolean prepareCareSeat(YukkuriEntity child, YukkuriEntity parent) {
        return prepareServiceSeat(child, parent, false);
    }

    /** Same temporary berth machinery for a parent standing behind the child during first-toilet care. */
    public boolean prepareRearCareSeat(YukkuriEntity child, YukkuriEntity parent) {
        return prepareServiceSeat(child, parent, true);
    }

    private boolean prepareServiceSeat(YukkuriEntity child, YukkuriEntity parent, boolean rearCare) {
        if (level().isClientSide || unsteadyMotion() || child == null || parent == null || parent != owner() || child.getVehicle() != this
                || parent.getVehicle() != this || !young(child) || !protects(parent) || !protects(child)
                || !isAfloat() || towed() || flooding != 0 || hasIncoming() || ownerToiletSeat || departure.departureYielding!=null || child.getUUID().equals(departure.voyageYoung)) return false;
        var held = serviceSeats.get(child.getUUID());
        if (held != null && held.ownerService() != null) {
            if (held.rearCare() != rearCare) return false;
            // Unfolding may require a real withdrawal; restore the reserved care station afterwards.
            if (carryTrip == null && !atSeat(parent, held.ownerService())) {
                if (!moveOwnerSeat(parent, held.ownerService(), false)) {
                    releaseCareSeat(child.getUUID());
                    return false;
                }
                syncSeats();
            }
            return true;
        }
        if (carryTrip != null) return false;
        if (serviceSeats.keySet().stream().anyMatch(id -> !id.equals(child.getUUID()))) return false;
        if (held == null) held = returningSeats.get(child.getUUID());
        // Feeding takes precedence over a child's voluntary movement.  Keep that movement's original
        // berth, but replace its destination from the child's current in-hat position.
        var original = held != null ? held.original() : seats.get(child.getUUID());
        if (original == null) return false;
        ServiceSeat best = serviceSeatFor(child, parent, original, rearCare);
        if (best == null) { if(cargo.innerCount()>0)cargo.moveAside(cargo.count()); return false; }
        if (!moveCarePair(child, parent, best.service(), best.ownerService())) {
            carePlanDebug+=" commit blocked";if(cargo.innerCount()>0)cargo.moveAside(cargo.count());return false;
        }
        serviceSeats.put(child.getUUID(), best);
        returningSeats.remove(child.getUUID());
        child.childhood().stopHatPractice();
        syncSeats();
        return true;
    }

    /** Move the captain to a rear brim patch with the body supported but its rear clear of the hat. */
    private boolean prepareOwnerToiletSeat(YukkuriEntity owner) {
        if (owner != owner()) return false;
        if (ownerToiletSeat) return true;
        if (!serviceSeats.isEmpty() || ownerSeatBusy()) return false;
        Vec3 brim = rearBrimPlace(owner, taken());
        if (brim == null) {carePlanDebug="toilet: no supported outer brim";return false;}
        if(!moveOwnerSeat(owner, brim, false))return false;
        ownerToiletSeat = true;
        syncSeats();
        return true;
    }

    /** The final service geometry has arrived; it remains valid through night-seat reflow. */
    public boolean careSeatReady(YukkuriEntity child) {
        var service = child == null ? null : serviceSeats.get(child.getUUID());
        var parent = owner();
        return service != null && service.ownerService() != null && parent != null
                && carryTrip == null
                && atSeat(child, new Vec3(service.service()[0], service.service()[1], service.service()[2]))
                && atSeat(parent, service.ownerService());
    }

    /** True when the active temporary berth asks the parent to face the child's back. */
    public boolean rearCareSeat(YukkuriEntity child) {
        var service = child == null ? null : serviceSeats.get(child.getUUID());
        return service != null && service.rearCare();
    }

    /**
     * After rear-care contact has completed, support the child on the front brim with its rear over
     * open water.  The accepted rear-care contact remains recorded in this temporary service seat;
     * the child is never dropped or teleported outside the hat.
     */
    public boolean prepareToiletSeat(YukkuriEntity child, YukkuriEntity parent) {
        if (level().isClientSide || child == null || parent == null || parent != owner() || child.getVehicle() != this
                || parent.getVehicle() != this || !protects(child) || !protects(parent) || !isAfloat()) return false;
        var service = serviceSeats.get(child.getUUID());
        if (service == null || service.ownerService() == null || !service.rearCare()) return false;
        if (service.toiletReady()) return true;
        if (!careSeatReady(child)) return false;
        Vec3 brim = frontEdgePlace(child, takenExcept(child.getUUID()), true);
        if (brim == null) {carePlanDebug="toilet: no supported outer brim";return false;}
        var destination = new double[]{brim.x, brim.y, brim.z, radiusOf(child)};
        Vec3 escort=escortPlace(parent,child,brim);
        if(escort==null){carePlanDebug="toilet: no escort position";return false;}
        if (!moveCarePair(child, parent, destination, escort)) {carePlanDebug="toilet: transfer blocked to "+brim+" escort="+escort;return false;}
        serviceSeats.put(child.getUUID(), new ServiceSeat(service.original(), service.originalRegion(), destination, escort, true, true));
        syncSeats();
        return true;
    }

    /**
     * Normal hat hygiene uses the same supported front-brim position, without claiming that a
     * first-toilet rear-care action occurred.  It cannot displace feeding or another child's care.
     */
    public boolean prepareToiletSeat(YukkuriEntity child) {
        if (level().isClientSide || child == null || child.getVehicle() != this
                || !protects(child) || !isAfloat() || towed() || flooding != 0 || hasIncoming()) return false;
        if (child.getUUID().equals(ownerYukkuri())) return prepareOwnerToiletSeat(child);
        var service = serviceSeats.get(child.getUUID());
        if (service != null && service.toiletReady()) return true;
        if (service != null && service.ownerService() != null) return false;
        if (serviceSeats.keySet().stream().anyMatch(id -> !id.equals(child.getUUID())) || !returningSeats.isEmpty() || ownerSeatBusy()) return false;
        var original = service != null ? service.original() : seats.get(child.getUUID());
        if (original == null) return false;
        Vec3 brim = frontEdgePlace(child, takenExcept(child.getUUID()), true);
        if (brim == null) return false;
        var destination = new double[]{brim.x, brim.y, brim.z, radiusOf(child)};
        if (!moveYoungSeat(child, destination, false)) return false;
        serviceSeats.put(child.getUUID(), new ServiceSeat(original.clone(), service == null ? region() : service.originalRegion(), destination, null, false, true));
        syncSeats();
        return true;
    }

    /** A completed rear-care action may release only when the child is actually supported at the brim. */
    public boolean toiletSeatReady(YukkuriEntity child) {
        if (child != null && child.getUUID().equals(ownerYukkuri())) return ownerToiletSeat && ownerServiceSeat != null && atSeat(child, ownerServiceSeat);
        var service = child == null ? null : serviceSeats.get(child.getUUID());
        return carryTrip == null && service != null && service.toiletReady()
                && atSeat(child, new Vec3(service.service()[0], service.service()[1], service.service()[2]))
                && (service.ownerService() == null || owner() != null && atSeat(owner(), service.ownerService()));
    }

    /** Make the child face inward so the rear is beyond the front brim, toward the surrounding water. */
    public void faceToiletOutward(YukkuriEntity child) {
        if (child == null) return;
        boolean owner = child.getUUID().equals(ownerYukkuri());
        var local = owner ? ownerServiceSeat : seatOf(child); if (local == null) return;
        Vec3 inside = toWorld(local.add(0, 0, owner ? -1 : 1)); Vec3 delta = inside.subtract(child.position());
        float yaw = (float)(Math.toDegrees(Math.atan2(delta.z, delta.x)) - 90);
        io.github.longshou.mcyukkuri.life.SurfaceApproach.face(child,yaw);
    }

    /** A real body-height point directly behind a supported brim rider, or null when no water is below it. */
    public Vec3 toiletDropPosition(YukkuriEntity child) {
        if (!toiletSeatReady(child)) return null;
        boolean owner = child.getUUID().equals(ownerYukkuri());
        var local = owner ? ownerServiceSeat : seatOf(child); if (local == null) return null;
        Vec3 at = toWorld(local), outside = toWorld(local.add(0, 0, owner ? 1 : -1)).subtract(at).normalize();
        Vec3 raw = at.add(outside.scale(child.getBbWidth() * .55 + .06));
        // A water block hidden under the brim does not make this a valid drop.  The rear point must
        // already be beyond the actual hat outline, then have an unobstructed short fall into water.
        Vec3 rawLocal = toLocal(raw);
        if (Double.isFinite(HatVessel.surface(HatVessel.Mode.FLOAT, rawLocal.x, rawLocal.z))) return null;
        BlockPos below = BlockPos.containing(raw.add(0, -.04, 0));
        for (int down = 0; down <= 2; down++) {
            BlockPos atBelow = below.below(down);
            if (level().getFluidState(atBelow).is(FluidTags.WATER)) return raw.add(0, .04, 0);
            if (!level().getBlockState(atBelow).isAir()) return null;
        }
        return null;
    }

    /** Lets the completed-care caller prove that a real rear-contact phase preceded the brim release. */
    public boolean toiletAssistanceValidated(YukkuriEntity child) {
        var service = child == null ? null : serviceSeats.get(child.getUUID());
        return service != null && service.toiletReady() && service.rearCare();
    }

    /** Release only a temporary hygiene berth; active feeding/rear-care and childhood practice remain untouched. */
    public boolean releaseToiletSeat(YukkuriEntity child) {
        if (child != null && child.getUUID().equals(ownerYukkuri())) {
            if (!ownerToiletSeat) return false;
            ownerToiletSeat = false;
            moveOwnerSeat(child, ownerSeat(child), false);
            syncSeats();
            return true;
        }
        var service = child == null ? null : serviceSeats.get(child.getUUID());
        if (service == null || !service.toiletReady()) return false;
        releaseCareSeat(child.getUUID());
        return true;
    }

    /** Keep a return reservation until the child physically arrives; failed paths can be retried. */
    public void releaseCareSeat(UUID childId) {
        var service = serviceSeats.remove(childId);
        if (service != null) returningSeats.putIfAbsent(childId, service);
        returnCareSeats();
        syncSeats();
    }

    private void returnCareSeats() {
        if (carryTrip != null) return;
        for (var entry : java.util.List.copyOf(returningSeats.entrySet())) {
            if (!(level() instanceof ServerLevel server) || !(server.getEntity(entry.getKey()) instanceof YukkuriEntity child)
                    || child.getVehicle() != this) { returningSeats.remove(entry.getKey()); continue; }
            if(serviceSeats.containsKey(entry.getKey()))continue;
            var service = entry.getValue();
            var original=new Vec3(service.original()[0],service.original()[1],service.original()[2]);
            double[] destination = service.originalRegion().equals(region())&&HatVessel.fits(mode(),original,radiusOf(child),
                    layoutTaken(child.getUUID()),HatVessel.bodySpace(child,hullScale(),unit()))
                    ?new double[]{original.x,original.y,original.z,radiusOf(child)}:normalSeat(child,child.getUUID());
            if (destination == null) {
                if(changingSeat(child))continue;
                cramped.add(child.getUUID());returningSeats.remove(entry.getKey());
                var at=toLocal(child.position());seats.put(child.getUUID(),new double[]{at.x,at.y,at.z,radiusOf(child)});
                continue;
            }
            Vec3 target = new Vec3(destination[0],destination[1],destination[2]);
            var parent = owner();
            var path = walkingRoute(child,target);
            if (!child.childhood().canJump() && (hasStep(path) || Math.abs(toLocal(child.position()).y-target.y)>.01) && parent != null && parent.getVehicle() == this) {
                if (beginReturnCarry(child,parent,target)) break;
                continue;
            }
            if (toLocal(child.position()).distanceToSqr(target)>1e-6
                    &&toLocal(child.position()).distanceToSqr(target)<=.01) {
                if (!changingSeat(child)&&!movePreparationCrawl(child,target)) continue;
            } else if (!moveYoungSeat(child, destination, false)) continue;
            if (!changingSeat(child)&&toLocal(child.position()).distanceToSqr(target)<1e-6) returningSeats.remove(entry.getKey());
        }
        if (carryTrip == null && serviceSeats.isEmpty() && !ownerToiletSeat&&level().getGameTime()>=boardingEdgeUntil) {
            var parent = owner();
            if (parent != null && parent.getVehicle() == this&&!parent.family().selfClimbActive()) moveOwnerSeat(parent, ownerSeat(parent), false);
        }
        syncSeats();
    }

    /** Wait for the live rider-position update, rather than accepting only an expired timer. */
    private boolean atSeat(YukkuriEntity rider, Vec3 local) {
        boolean moving = rider.getUUID().equals(ownerYukkuri()) ? changingOwnerSeat() : changingSeat(rider);
        return rider.getVehicle() == this && !moving && rider.position().distanceToSqr(toWorld(local)) < .0004;
    }

    /** Start a small supported childhood movement inside the hat; {@code hop=false} stays level. */
    public boolean beginPracticeMove(YukkuriEntity child, boolean hop) {
        if (level().isClientSide || unsteadyMotion() || cargo.handling() || cargo.tier()>=3 || child == null || child.getVehicle() != this || !young(child) || !protects(child)
                || !isAfloat() || towed() || flooding != 0 || hasIncoming() || departure.departureYielding!=null || child.getUUID().equals(departure.voyageYoung)
                || !serviceSeats.isEmpty() || !returningSeats.isEmpty() || carryTrip!=null || changingSeat(child) || ownerSeatBusy()) return false;
        var original = seats.get(child.getUUID());
        if (original == null) return false;
        double r = radiusOf(child); Vec3 from = toLocal(child.position()); Vec3 chosen = null;
        for (int i = 0; i < 8 && chosen == null; i++) {
            double angle = i * Math.PI / 4.0;
            Vec3 prefer = from.add(Math.cos(angle) * Math.max(.6, r * 2.5), 0, Math.sin(angle) * Math.max(.6, r * 2.5));
            Vec3 candidate = HatVessel.place(mode(), r, region(), prefer, layoutTaken(child.getUUID()), from,Double.NaN,HatVessel.bodySpace(child,hullScale(),unit()));
            if (candidate != null && candidate.distanceToSqr(from) > .16 && Math.abs(candidate.y-from.y) < 1e-4
                    && HatVessel.footprint(mode(), candidate.x, candidate.z, r) == candidate.y) chosen = candidate;
        }
        if (chosen == null) return false;
        var destination = new double[]{chosen.x, chosen.y, chosen.z, r};
        if (!moveYoungSeat(child, destination, hop)) return false;
        var movement=seatMoves.get(child.getUUID());
        if(movement==null||movement.arc()!=hop)return false;
        serviceSeats.put(child.getUUID(), new ServiceSeat(original.clone(), region(), destination.clone(), null, false, false));
        syncSeats();
        return true;
    }

    public boolean practisingMove(YukkuriEntity child) {
        var service = child == null ? null : serviceSeats.get(child.getUUID());
        return service != null && service.ownerService() == null && !service.toiletReady() && changingSeat(child);
    }

    /** Consume a completed childhood move and send the child back to its normal berth. */
    public boolean completePracticeMove(YukkuriEntity child) {
        var service = child == null ? null : serviceSeats.get(child.getUUID());
        if (service == null || service.ownerService() != null || service.toiletReady() || changingSeat(child)) return false;
        releaseCareSeat(child.getUUID());
        return true;
    }

    /** Cancel only a child's voluntary in-hat practice; active feeding and toilet care cannot be cancelled here. */
    public boolean cancelPracticeMove(YukkuriEntity child) {
        var service = child == null ? null : serviceSeats.get(child.getUUID());
        if (service == null || service.ownerService() != null || service.toiletReady()) return false;
        releaseCareSeat(child.getUUID());
        return true;
    }

    /**
     * The captain is larger than a newborn, so a rear toilet seat needs a real contact patch rather
     * than a whole-body circle inside the front juvenile brim.  The centre and forward feet remain
     * supported; the rear drop point is checked separately beyond the hull.
     */
    private Vec3 rearBrimPlace(YukkuriEntity rider, java.util.List<double[]> taken) {
        double support = Math.min(1.10, Math.max(.45, radiusOf(rider) * .25));
        double rear = (rider.getBbWidth() * .55 + .06) / unit();
        Vec3 best = null; double bestCost = Double.MAX_VALUE;
        for (double x = -4.75; x <= 4.75; x += .25) for (double z = HatVessel.OPENING[3] + .20; z <= HatVessel.BRIM[1] - .20; z += .25) {
            if (!brimSupport(x, z) || !brimSupport(x, z-support) || !brimSupport(x+support*.45, z-support*.15)
                    || !brimSupport(x-support*.45, z-support*.15)
                    || Double.isFinite(HatVessel.surface(HatVessel.Mode.FLOAT, x, z+rear))) continue;
            boolean clear = true;
            for (var seat : taken) if (Math.hypot(x-seat[0], z-seat[2]) < Math.max(.9, (radiusOf(rider)+seat[3])*.32)) { clear=false; break; }
            if (!clear) continue;
            double cost = Math.abs(x) + Math.abs(z-(HatVessel.OPENING[3]+support));
            if (cost < bestCost) { bestCost = cost; best = new Vec3(x, HatVessel.FREEBOARD, z); }
        }
        return best;
    }

    /**
     * A front edge for large pupils: their feet and inner body stay supported while the forward
     * body may extend beyond the narrow juvenile brim.  Toileting additionally requires the rear
     * body point to clear the actual hull outline before the water check can pass.
     */
    Vec3 frontEdgePlace(YukkuriEntity rider, java.util.List<double[]> taken, boolean needsRearClear) {
        return frontEdgePlace(rider,taken,needsRearClear,null);
    }
    Vec3 frontEdgePlace(YukkuriEntity rider, java.util.List<double[]> taken, boolean needsRearClear,YukkuriEntity ignored) {
        double support = Math.min(1.10, Math.max(.45, radiusOf(rider) * .25));
        double rear = (rider.getBbWidth() * .55 + .06) / unit();
        Vec3 best = null; double bestCost = Double.MAX_VALUE;
        for (double x = -4.75; x <= 4.75; x += .25) for (double z = -HatVessel.BRIM[1] + .20; z <= HatVessel.OPENING[2] - .20; z += .25) {
            if (!brimSupport(x, z) || !brimSupport(x, z+support) || !brimSupport(x+support*.45, z+support*.15)
                    || !brimSupport(x-support*.45, z+support*.15)) continue;
            if (needsRearClear && Double.isFinite(HatVessel.surface(HatVessel.Mode.FLOAT, x, z-rear))) continue;
            boolean clear = true;
            for (var seat : taken) if (Math.hypot(x-seat[0], z-seat[2]) < Math.max(.9, (radiusOf(rider)+seat[3])*.32)) { clear=false; break; }
            if (!clear) continue;
            if(!needsRearClear&&!departureEnvelopeClear(rider,new Vec3(x,HatVessel.FREEBOARD,z),getYRot(),ignored))continue;
            // Prefer the real outer front edge, leaving clearance to lower the pupil's own hat.
            double cost = Math.abs(x)*.2 + z;
            if (cost < bestCost) { bestCost = cost; best = new Vec3(x, HatVessel.FREEBOARD, z); }
        }
        return best;
    }

    private boolean brimSupport(double x, double z) {
        return Math.abs(HatVessel.surface(HatVessel.Mode.FLOAT, x, z)-HatVessel.FREEBOARD) < 1e-6;
    }

    private ServiceSeat serviceSeatFor(YukkuriEntity child, YukkuriEntity parent, double[] original, boolean rearCare) {
        if(cargo.tier()>=3||cargo.handling())return null;
        double r=radiusOf(child); Vec3 from=toLocal(child.position()), parentFrom=toLocal(parent.position()), base=ownerSeat(parent);
        var childBody=HatVessel.bodySpace(child,hullScale(),unit());
        var parentBody=HatVessel.bodySpace(parent,hullScale(),unit());
        var taken=takenExcept(child.getUUID());
        var childPlaces=new java.util.ArrayList<Vec3>();
        // Enumerate measured contact patches once. A parent's station is not restricted to a few stern offsets.
        for(double x=HatVessel.OPENING[0]+r;x<=HatVessel.OPENING[1]-r;x+=.25)
            for(double z=-HatVessel.BRIM[1]+r+.15;z<=Math.min(.5,HatVessel.OPENING[3]-r);z+=.25){
                double y=HatVessel.footprint(mode(),x,z,r);
                var at=new Vec3(x,y,z);
                if(Double.isFinite(y)&&HatVessel.fits(mode(),at,r,taken,childBody))childPlaces.add(at);
            }
        record CarePose(Vec3 ownerAt,Vec3 childAt,double cost) {}
        var candidates=new java.util.ArrayList<CarePose>();
        double min=FamilyFeedingContact.minimumMouthDistance(parent,child)/unit(), max=FamilyFeedingContact.mouthDistance(parent,child)/unit();
        // The adult fits through the centre of this measured opening, but that strip is narrower
        // than the search grid. Always include the exact centre instead of aliasing it away.
        var ownerXs=new java.util.ArrayList<Double>();ownerXs.add(0.0);
        for(double x=HatVessel.OPENING[0];x<=HatVessel.OPENING[1];x+=.5)ownerXs.add(x);
        for(double x:ownerXs)
            for(double z=HatVessel.OPENING[2];z<=HatVessel.OPENING[3];z+=.5){
                double y=HatVessel.travelSupport(mode(),x,z,radiusOf(parent));
                if(!Double.isFinite(y)||!Double.isFinite(HatVessel.surface(mode(),x,z)))continue;
                Vec3 ownerAt=new Vec3(x,y,z);
                if(!HatVessel.fitsSupported(mode(),ownerAt,radiusOf(parent),taken,parentBody))continue;
                for(var candidate:childPlaces){
                    // Adjacent real treads need not have equal elevations. The shared mouth and
                    // rear-contact envelopes, rather than an obsolete two-tier floor, bound care.
                    if(rearCare&&Math.abs(candidate.y-ownerAt.y)*unit()>.18)continue;
                    double dx=candidate.x-x,dz=candidate.z-z,distance=dx*dx+dz*dz;
                    if(distance<min*min || distance>max*max || !serviceGeometryFits(parent,child,ownerAt,candidate))continue;
                    if(!HatVessel.fits(mode(),candidate,r,java.util.List.of(rescueBody(parent,ownerAt)),childBody)
                            ||!HatVessel.fitsSupported(mode(),ownerAt,radiusOf(parent),java.util.List.of(rescueBody(child,candidate)),parentBody))continue;
                    double cost=candidate.distanceToSqr(from)+ownerAt.distanceToSqr(parentFrom)*.5+ownerAt.distanceToSqr(base)*.25;
                    candidates.add(new CarePose(ownerAt,candidate,cost));
                }
            }
        candidates.sort(java.util.Comparator.comparingDouble(CarePose::cost));
        int carryBlocked=0,childBlocked=0,ownerBlocked=0;
        var childRoutes=new java.util.HashMap<Vec3,java.util.List<Vec3>>();
        var carryGeometry=new CarrySearch();
        // Cheap geometry and travel costs precede routing: stop at the first fully reachable pair.
        for(var pose:candidates){
            Vec3 ownerAt=pose.ownerAt(), candidate=pose.childAt();
            var path=childRoutes.computeIfAbsent(candidate,at->walkingRoute(child,at));
            boolean assisted=!child.childhood().canJump()&&(hasStep(path)||Math.abs(candidate.y-from.y)>.01);
            if(assisted){if(carryPickup(child,parent,from,candidate,ownerAt,carryGeometry)==null){carryBlocked++;continue;}}
            else{
                if(from.distanceToSqr(candidate)>.01&&path.isEmpty()){childBlocked++;continue;}
                var obstacles=takenExcept(child.getUUID());
                obstacles.add(rescueBody(child,candidate));
                if(parentFrom.distanceToSqr(ownerAt)>.01&&HatSurfaceRoute.find(mode(),parentFrom,ownerAt,
                        radiusOf(parent),obstacles,parentBody).isEmpty()){ownerBlocked++;continue;}
            }
            carePlanDebug="ready child="+candidate+" owner="+ownerAt+" assisted="+assisted;
            return new ServiceSeat(original.clone(),region(),new double[]{candidate.x,candidate.y,candidate.z,r},ownerAt,rearCare,false);
        }
        carePlanDebug="no route childPatches="+childPlaces.size()+" pairs="+candidates.size()
                +" carryBlocked="+carryBlocked+" childBlocked="+childBlocked+" ownerBlocked="+ownerBlocked;
        return null;
    }

    private boolean serviceGeometryFits(YukkuriEntity parent, YukkuriEntity child, Vec3 ownerAt, Vec3 childAt) {
        Vec3 parentWorld = toWorld(ownerAt), childWorld = toWorld(childAt);
        double horizontal = parentWorld.subtract(childWorld).multiply(1, 0, 1).length();
        return horizontal >= FamilyFeedingContact.minimumMouthDistance(parent, child)
                && horizontal <= FamilyFeedingContact.mouthDistance(parent, child)
                && Math.abs(parentWorld.y + parent.getBbHeight() * .5 - (childWorld.y + child.getBbHeight() * .5))
                        <= FamilyFeedingContact.MAX_MOUTH_HEIGHT_DELTA;
    }

    java.util.List<double[]> takenExcept(UUID excluded) {
        var out=occupantsExcept(excluded);addCargoFootprints(out);return out;
    }
    java.util.List<double[]> occupantsExcept(UUID excluded) {
        var out = new java.util.ArrayList<double[]>();
        for (var entry : seats.entrySet()) if (!entry.getKey().equals(excluded)) {
            var seat=entry.getValue();
            var occupant=level() instanceof ServerLevel server?server.getEntity(entry.getKey()):null;
            if(occupant instanceof YukkuriEntity y){
                var shape=HatVessel.bodySpace(y,hullScale(),unit());
                out.add(new double[]{seat[0],seat[1],seat[2],radiusOf(y),shape.height(),0,shape.hatX(),shape.hatZ(),shape.hatBase(),shape.hatHeight()});
            }else out.add(new double[]{seat[0],seat[1],seat[2],seat[3],occupant==null?seat[3]*2:occupant.getBbHeight()/unit()});
        }
        for (var entry : incoming.entrySet()) if (!entry.getKey().equals(excluded)) {
            var seat=entry.getValue();var occupant=level() instanceof ServerLevel server?server.getEntity(entry.getKey()):null;
            if(occupant instanceof YukkuriEntity y){
                var shape=HatVessel.bodySpace(y,hullScale(),unit());
                out.add(new double[]{seat[0],seat[1],seat[2],radiusOf(y),shape.height(),0,shape.hatX(),shape.hatZ(),shape.hatBase(),shape.hatHeight()});
            }else out.add(seat);
        }
        // A layout replan may temporarily remove destinations. Their actual riders still occupy the hat.
        for(var p:youngPassengers())if(!p.getUUID().equals(excluded)&&!seats.containsKey(p.getUUID())){
            var at=toLocal(p.position());
            out.add(p instanceof YukkuriEntity y?rescueBody(y,at):new double[]{at.x,at.y,at.z,radiusOf(p),p.getBbHeight()/unit()});
        }
        if (departure.voyageBerth != null && (departure.voyageYoung == null || !departure.voyageYoung.equals(excluded)) && !seats.containsKey(departure.voyageYoung) && !incoming.containsKey(departure.voyageYoung)) out.add(departure.voyageBerth);
        return out;
    }
    /** Inventory changes must respect body positions, temporary stations and promised return positions together. */
    java.util.List<double[]> cargoOccupants(){
        var out=occupantsExcept(null);
        for(var p:getPassengers())out.add(p instanceof YukkuriEntity y?rescueBody(y,toLocal(p.position())):
                new double[]{toLocal(p.position()).x,toLocal(p.position()).y,toLocal(p.position()).z,radiusOf(p),p.getBbHeight()/unit()});
        var parent=owner();
        if(parent!=null&&!catches()){
            out.add(rescueBody(parent,ownerSeat(parent)));
            if(ownerServiceSeat!=null)out.add(rescueBody(parent,ownerServiceSeat));
        }
        for(var map:java.util.List.of(serviceSeats,returningSeats))for(var e:map.entrySet()){
            var rider=level() instanceof ServerLevel server?server.getEntity(e.getKey()):null;
            for(var seat:java.util.List.of(e.getValue().original(),e.getValue().service()))
                out.add(rider instanceof YukkuriEntity y?rescueBody(y,new Vec3(seat[0],seat[1],seat[2])):seat);
            if(parent!=null&&e.getValue().ownerService()!=null)out.add(rescueBody(parent,e.getValue().ownerService()));
        }
        return out;
    }

    private double[] normalSeat(YukkuriEntity child, UUID excluded) {
        double r = radiusOf(child); Vec3 next = plannedSeat(child,null,false);
        if (next == null) return null;
        return new double[]{next.x, next.y, next.z, r};
    }

    boolean moveYoungSeat(YukkuriEntity child, double[] destination, boolean arc) {
        if(cargo.handling()||!cargoSeatClear(child,new Vec3(destination[0],destination[1],destination[2])))return false;
        Vec3 from = toLocal(child.position()); Vec3 to = new Vec3(destination[0], destination[1], destination[2]);
        var existing = seats.get(child.getUUID());
        // An idempotent walking reservation is fine; a new practice hop must really launch a fresh arc.
        if (!arc && existing != null && java.util.Arrays.equals(existing, destination)) return true;
        if (arc && from.distanceToSqr(to)<=.01) return false;
        var route=arc||from.distanceToSqr(to)<=.01?java.util.List.<Vec3>of():walkingRoute(child,to);
        if(!arc&&from.distanceToSqr(to)>.01&&route.isEmpty())return false;
        if (!arc && !child.childhood().canJump() && hasStep(route)) return false;
        seats.put(child.getUUID(), destination.clone());
        if (from.distanceToSqr(to) > .01) {
            int ticks = arc ? HatVessel.solve(child.position(), toWorld(to), this, HatVessel.radius(child.getBbWidth()), HatVessel.HOP_TMIN)
                    : Math.max(16, (int)Math.ceil(HatSurfaceRoute.length(route) / .20));
            seatMoves.put(child.getUUID(), new SeatMove(from, walkStart(child), ticks, arc,route));
        } else seatMoves.remove(child.getUUID());
        return true;
    }

    /** Even a few millimetres of preparation clearance is traversed, never snapped into place. */
    private boolean movePreparationCrawl(YukkuriEntity child, Vec3 destination) {
        return moveCheckedSeatRoute(child,destination,walkingRoute(child,destination),false);
    }
    boolean moveCheckedSeatRoute(YukkuriEntity child,Vec3 destination,java.util.List<Vec3> route,boolean allowSteps){
        if(cargo.handling()||!cargoSeatClear(child,destination))return false;
        var from = toLocal(child.position());
        if (route.size()<2 || !allowSteps&&hasStep(route)) return false;
        seats.put(child.getUUID(), new double[]{destination.x,destination.y,destination.z,radiusOf(child)});
        seatMoves.put(child.getUUID(), new SeatMove(from, level().getGameTime(),
                Math.max(16,(int)Math.ceil(HatSurfaceRoute.length(route)/.20)), false, route));
        return true;
    }

    boolean ownerSeatBusy() {
        var parent = owner();
        return ownerToiletSeat || changingOwnerSeat() || parent != null && ownerServiceSeat != null && !ownerServiceSeat.equals(ownerSeat(parent));
    }

    private boolean moveOwnerSeat(YukkuriEntity parent, Vec3 destination, boolean arc) {
        if(cargo.handling())return false;
        Vec3 from = toLocal(parent.position());
        if (destination.equals(ownerServiceSeat)) return true;
        var route=arc||from.distanceToSqr(destination)<=.01?java.util.List.<Vec3>of():walkingRoute(parent,destination);
        if(!arc&&from.distanceToSqr(destination)>.01&&route.isEmpty()){
            if(cargo.innerCount()>0)cargo.moveAside(cargo.count());return false;
        }
        ownerServiceSeat = destination;
        if (from.distanceToSqr(destination) > .01) {
            int ticks = arc ? HatVessel.solve(parent.position(), toWorld(destination), this, HatVessel.radius(parent.getBbWidth()), HatVessel.HOP_TMIN)
                    : Math.max(16, (int)Math.ceil(HatSurfaceRoute.length(route) / .20));
            ownerSeatMove = new SeatMove(from, walkStart(parent), ticks, arc,route);
        } else ownerSeatMove = null;
        return true;
    }

    /** Supported candidates only; the braid controller chooses one from its authored root/reach geometry. */
    public java.util.List<Vec3> rescueOwnerCandidates(YukkuriEntity parent){
        if(parent==null)return java.util.List.of();
        return supportedRescueOwnerCandidates(parent,takenExcept(parent.getUUID()));
    }
    /** Joint replanning ignores this rescued child's previous incoming berth, never another occupant. */
    public java.util.List<Vec3> rescueOwnerCandidates(YukkuriEntity parent,YukkuriEntity child){
        if(parent==null)return java.util.List.of();
        return supportedRescueOwnerCandidates(parent,takenExcept(child!=null&&!hasPassenger(child)?child.getUUID():parent.getUUID()));
    }
    private java.util.List<Vec3> supportedRescueOwnerCandidates(YukkuriEntity parent,java.util.List<double[]> obstacles){
        if(parent!=owner()||parent.getVehicle()!=this||!isAfloat()||unsteadyMotion())return java.util.List.of();
        var result=new java.util.ArrayList<Vec3>();
        var body=HatVessel.bodySpace(parent,hullScale(),unit());double radius=radiusOf(parent);
        for(double x=-HatVessel.BRIM[0];x<=HatVessel.BRIM[0];x+=.4)
            for(double z=-HatVessel.BRIM[1];z<=HatVessel.BRIM[1];z+=.4){
                double y=HatVessel.travelSupport(mode(),x,z,radius);if(!Double.isFinite(y))continue;
                var at=new Vec3(x,y,z);if(HatVessel.fitsSupported(mode(),at,radius,obstacles,body))result.add(at);
            }
        return result;
    }
    /** Read-only supported well and inner-ledge seats. The parent is placed jointly instead of frozen at her stern. */
    public java.util.List<Vec3> rescueYoungCandidates(YukkuriEntity child){
        if(child==null||!young(child)||child.isPassenger()||!isAfloat()||unsteadyMotion())return java.util.List.of();
        var result=new java.util.ArrayList<Vec3>();var obstacles=takenExcept(child.getUUID());
        var body=HatVessel.bodySpace(child,hullScale(),unit());double radius=radiusOf(child);
        var preferred=HatVessel.place(mode(),radius,region(),null,obstacles,null,Double.NaN,body);
        if(preferred!=null)result.add(preferred);
        var previous=incoming.get(child.getUUID());
        if(previous!=null){
            var at=new Vec3(previous[0],previous[1],previous[2]);
            if(HatVessel.fits(mode(),at,radius,obstacles,body))result.add(at);
        }
        // Include the delivery seat lattice as well as the walking lattice; a half-unit side shift
        // can put a finite braid within reach even when the nearest default seat is unreachable.
        for(double step:new double[]{.5,.4})for(double x=HatVessel.OPENING[0]+radius;x<=HatVessel.OPENING[1]-radius+1e-6;x+=step)
            for(double z=HatVessel.OPENING[2]+radius;z<=HatVessel.OPENING[3]-radius+1e-6;z+=step){
                double y=HatVessel.footprint(mode(),x,z,radius);
                if(!Double.isFinite(y)||Math.abs(HatVessel.surface(mode(),x,z)-y)>1e-4)continue;
                var at=new Vec3(x,y,z);if(HatVessel.fits(mode(),at,radius,obstacles,body))result.add(at);
            }
        // A braid may use the child's own side before a later supported walk into the cabin.
        // These existing inner-brim patches require the complete footprint and its safety margin.
        for(double x=-HatVessel.BRIM[0]+radius+.18;x<=HatVessel.BRIM[0]-radius-.18;x+=.25)
            for(double z=-HatVessel.BRIM[1]+radius+.18;z<=HatVessel.BRIM[1]-radius-.18;z+=.25){
                if(x>HatVessel.OPENING[0]&&x<HatVessel.OPENING[1]&&z>HatVessel.OPENING[2]&&z<HatVessel.OPENING[3])continue;
                double y=HatVessel.top(mode());
                if(!HatVessel.supported(mode(),x,z,radius+.18,y))continue;
                var at=new Vec3(x,y,z);if(HatVessel.fits(mode(),at,radius,obstacles,body))result.add(at);
            }
        // This is only a cheap preference. Reachable fixed-length braid geometry chooses the final pair.
        result.removeIf(at->!cargoSeatClear(child,at));
        if(preferred!=null)result.sort(java.util.Comparator.comparingDouble(at->at.distanceToSqr(preferred)));
        return result;
    }
    private double[] rescueBody(YukkuriEntity rider,Vec3 at){
        var shape=HatVessel.bodySpace(rider,hullScale(),unit());
        return new double[]{at.x,at.y,at.z,radiusOf(rider),shape.height(),0,shape.hatX(),shape.hatZ(),shape.hatBase(),shape.hatHeight()};
    }
    /** Validate the actual two-body destination without changing a reservation or walking either actor. */
    public boolean canPlaceRescuePair(YukkuriEntity child,YukkuriEntity parent,Vec3 youngSeat,Vec3 parentStand){
        if(child==null||parent==null||parent!=owner()||parent.getVehicle()!=this||!young(child)||child.isPassenger()
                ||youngSeat==null||parentStand==null||!isAfloat()||unsteadyMotion()||towed()||flooding>0||cargo.handling())return false;
        if(!cargoSeatClear(child,youngSeat)||Math.abs(HatVessel.surface(mode(),youngSeat.x,youngSeat.z)-youngSeat.y)>1e-4)return false;
        var obstacles=takenExcept(child.getUUID());
        var childObstacles=new java.util.ArrayList<>(obstacles);childObstacles.add(rescueBody(parent,parentStand));
        var ownerObstacles=new java.util.ArrayList<>(obstacles);ownerObstacles.add(rescueBody(child,youngSeat));
        return HatVessel.fits(mode(),youngSeat,radiusOf(child),childObstacles,HatVessel.bodySpace(child,hullScale(),unit()))
                &&HatVessel.fitsSupported(mode(),parentStand,radiusOf(parent),ownerObstacles,HatVessel.bodySpace(parent,hullScale(),unit()));
    }
    /** Atomically keep the chosen child berth only after both bodies and the parent's real walk are viable.
     *  The braid controller still walks to pickup first and to parentStand only after it has lifted the child. */
    public boolean reserveRescuePair(YukkuriEntity child,YukkuriEntity parent,Vec3 youngSeat,Vec3 parentStand){
        if(level().isClientSide||!canPlaceRescuePair(child,parent,youngSeat,parentStand)||carryTrip!=null||ownerToiletSeat
                ||!serviceSeats.isEmpty()||!returningSeats.isEmpty()
                ||parent.family().rescueTarget()!=null&&!child.getUUID().equals(parent.family().rescueTarget())
                ||!incoming.containsKey(child.getUUID())&&youngAboard()+incoming.size()>=MAX_YOUNG)return false;
        var obstacles=takenExcept(child.getUUID());obstacles.add(rescueBody(child,youngSeat));
        var from=toLocal(parent.position());
        if(from.distanceToSqr(parentStand)>.01&&HatSurfaceRoute.find(mode(),from,parentStand,radiusOf(parent),obstacles,HatVessel.bodySpace(parent,hullScale(),unit())).isEmpty())return false;
        incoming.put(child.getUUID(),new double[]{youngSeat.x,youngSeat.y,youngSeat.z,radiusOf(child)});
        syncSeats();return true;
    }
    /** A real supported walk, never a jump or an interpolated extension of the braid. */
    public boolean moveRescueOwner(YukkuriEntity parent,Vec3 localSupported){
        if(level().isClientSide||parent!=owner()||parent.getVehicle()!=this||!isAfloat()||unsteadyMotion()||towed()
                ||localSupported==null||carryTrip!=null||ownerToiletSeat||!serviceSeats.isEmpty()||!returningSeats.isEmpty())return false;
        if(!HatVessel.fitsSupported(mode(),localSupported,radiusOf(parent),takenExcept(parent.getUUID()),HatVessel.bodySpace(parent,hullScale(),unit())))return false;
        if(!moveOwnerSeat(parent,localSupported,false))return false;
        rescueOwnerHeld=true;holdPosition();syncSeats();return true;
    }
    /** Diagnostics only: why moveRescueOwner would refuse this stand (null when it would not). */
    public String rescueOwnerRefusal(YukkuriEntity parent,Vec3 localSupported){
        if(parent!=owner()||parent.getVehicle()!=this)return "not the captain aboard";
        if(!isAfloat()||unsteadyMotion()||towed())return "hull not steady";
        if(carryTrip!=null)return "carry trip";if(ownerToiletSeat)return "toilet seat";
        if(!serviceSeats.isEmpty())return "service seats "+serviceSeats.keySet();if(!returningSeats.isEmpty())return "returning seats "+returningSeats.keySet();
        if(!HatVessel.fitsSupported(mode(),localSupported,radiusOf(parent),takenExcept(parent.getUUID()),HatVessel.bodySpace(parent,hullScale(),unit())))return "stand does not fit";
        var from=toLocal(parent.position());
        if(from.distanceToSqr(localSupported)>.01&&walkingRoute(parent,localSupported).isEmpty())return "no walking route from "+from;
        return null;
    }
    /** Read-only LOCAL walking points for the braid controller's swept two-body custody plan. */
    public java.util.List<Vec3> rescueOwnerRoute(YukkuriEntity parent,Vec3 localStand,YukkuriEntity child){
        return rescueOwnerRoute(parent,localStand,child,null);
    }
    public java.util.List<Vec3> rescueOwnerRoute(YukkuriEntity parent,Vec3 localStand,YukkuriEntity child,Vec3 youngSeat){
        if(parent!=owner()||parent.getVehicle()!=this||localStand==null||!isAfloat()||unsteadyMotion())return java.util.List.of();
        var obstacles=takenExcept(child!=null?child.getUUID():parent.getUUID());
        if(child!=null&&youngSeat!=null)obstacles.add(rescueBody(child,youngSeat));
        if(!HatVessel.fitsSupported(mode(),localStand,radiusOf(parent),obstacles,HatVessel.bodySpace(parent,hullScale(),unit())))return java.util.List.of();
        var from=toLocal(parent.position());
        if(from.distanceToSqr(localStand)<.0001)return java.util.List.of(from);
        return HatSurfaceRoute.find(mode(),from,localStand,radiusOf(parent),obstacles,HatVessel.bodySpace(parent,hullScale(),unit()));
    }
    public boolean rescueOwnerReady(YukkuriEntity parent){return rescueOwnerHeld&&parent==owner()&&ownerServiceSeat!=null&&atSeat(parent,ownerServiceSeat);}
    public void releaseRescueOwner(YukkuriEntity parent){
        if(level().isClientSide||parent!=owner())return;
        rescueOwnerHeld=false;if(parent.getVehicle()==this)moveOwnerSeat(parent,ownerSeat(parent),false);syncSeats();
    }

    private long walkStart(Entity rider){
        long start=level().getGameTime();
        for(var entry:seatMoves.entrySet())if(!entry.getKey().equals(rider.getUUID()))start=Math.max(start,entry.getValue().start()+entry.getValue().ticks());
        if(!rider.getUUID().equals(ownerYukkuri())&&ownerSeatMove!=null)start=Math.max(start,ownerSeatMove.start()+ownerSeatMove.ticks());
        return start;
    }
    private java.util.List<Vec3> walkingRoute(Entity rider, Vec3 destination) {
        var obstacles = takenExcept(rider.getUUID());
        if (!rider.getUUID().equals(ownerYukkuri())) {
            var parent = owner();
            if (parent != null && parent.getVehicle() == this) {
                Vec3 at = ownerServiceSeat != null ? ownerServiceSeat : toLocal(parent.position());
                obstacles.add(rescueBody(parent,at));
            }
        }
        return HatSurfaceRoute.find(mode(), toLocal(rider.position()), destination, radiusOf(rider), obstacles,rider instanceof YukkuriEntity y
                ?HatVessel.bodySpace(y,hullScale(),unit()):new HatVessel.BodySpace(rider.getBbHeight()/unit(),0,0,0,0));
    }

    /** Commit both reservations together; a failed parent route must not strand the child. */
    private boolean moveCarePair(YukkuriEntity child, YukkuriEntity parent, double[] destination, Vec3 ownerDestination) {
        if (carryTrip != null) return carryTrip.child.equals(child.getUUID());
        Vec3 target = new Vec3(destination[0],destination[1],destination[2]);
        var path = walkingRoute(child,target);
        if (!child.childhood().canJump() && (hasStep(path) || Math.abs(toLocal(child.position()).y-target.y)>.01)) return beginCarry(child,parent,destination,ownerDestination);
        var oldSeat = seats.get(child.getUUID());
        var oldMove = seatMoves.get(child.getUUID());
        if (!moveYoungSeat(child, destination, false)) return false;
        if (moveOwnerSeat(parent, ownerDestination, false)) return true;
        if (oldSeat == null) seats.remove(child.getUUID()); else seats.put(child.getUUID(), oldSeat);
        if (oldMove == null) seatMoves.remove(child.getUUID()); else seatMoves.put(child.getUUID(), oldMove);
        return false;
    }
    static boolean hasStep(java.util.List<Vec3> path) {
        for (int i=1;i<path.size();i++) if (Math.abs(path.get(i).y-path.get(i-1).y)>.01) return true;
        return false;
    }

    private record CarryPickup(Vec3 childAt,Vec3 at,Vec3 offset,Vec3 delivery,HatCareGeometry.Context geometry) {}
    private record PickupKey(Vec3 from,Vec3 direction) {}
    private record DeliveryKey(Vec3 to,Vec3 parentAt,Vec3 direction) {}
    private static final class CarrySearch {
        final java.util.Map<Vec3,HatCareGeometry.Context> geometry=new java.util.HashMap<>();
        final java.util.Map<PickupKey,CarryPickup> pickups=new java.util.HashMap<>();
        final java.util.Map<DeliveryKey,String> deliveries=new java.util.HashMap<>();
        java.util.List<Vec3> preparePlaces;
    }

    /** Only this named contact target uses the measured tucked pose; all other riders retain full obstacles. */
    private java.util.List<Vec3> pickupRoute(YukkuriEntity parent,YukkuriEntity child,Vec3 target,Vec3 childAt,HatCareGeometry.Context geometry){
        var obstacles=takenExcept(child.getUUID());var from=toLocal(parent.position());
        var path=HatSurfaceRoute.find(mode(),from,target,radiusOf(parent),obstacles,Vec3.ZERO,0,
                HatVessel.bodySpace(parent,hullScale(),unit()),null,at->geometry.pickupClear(at,childAt));
        return posedRouteClear(path,parent,childAt,null,geometry,obstacles)?path:java.util.List.of();
    }
    private boolean posedRouteClear(java.util.List<Vec3> path,YukkuriEntity parent,Vec3 childAt,Vec3 offset,
                                    HatCareGeometry.Context geometry,java.util.List<double[]> obstacles){
        if(path.isEmpty())return false;
        for(int segment=1;segment<path.size();segment++){
            var a=path.get(segment-1);var b=path.get(segment);int count=Math.max(1,(int)Math.ceil(a.distanceTo(b)/.05));
            for(int i=0;i<=count;i++){
                var at=a.lerp(b,(double)i/count);double support=HatVessel.travelSupport(mode(),at.x,at.z,radiusOf(parent));
                at=new Vec3(at.x,Math.max(at.y,support),at.z);
                if(!geometry.clear(at,offset==null?childAt:at.add(offset),mode(),obstacles))return false;
            }
        }
        return true;
    }
    private boolean carryTransferClear(YukkuriEntity child,Vec3 from,Vec3 to,Vec3 parentAt,HatCareGeometry.Context geometry,java.util.List<double[]> obstacles){
        if(!HatSurfaceRoute.transferClear(mode(),from,to,radiusOf(child),obstacles,HatVessel.bodySpace(child,hullScale(),unit()))){
            carryTransferDebug="upright body/hat envelope";return false;
        }
        int count=Math.max(1,(int)Math.ceil(from.distanceTo(to)/.05));
        for(int i=0;i<=count;i++){
            var at=from.lerp(to,(double)i/count);
            if(!geometry.clear(parentAt,at,mode(),obstacles)){carryTransferDebug="posed "+geometry.debug()+" at="+at;return false;}
        }
        carryTransferDebug="clear";
        return true;
    }
    private java.util.List<Vec3> carriedRoute(YukkuriEntity parent,YukkuriEntity child,Vec3 from,Vec3 to,Vec3 offset,HatCareGeometry.Context geometry){
        var obstacles=takenExcept(child.getUUID());
        var path=HatSurfaceRoute.find(mode(),from,to,radiusOf(parent),obstacles,offset,radiusOf(child),
                HatVessel.bodySpace(parent,hullScale(),unit()),HatVessel.bodySpace(child,hullScale(),unit()));
        if(posedRouteClear(path,parent,null,offset,geometry,obstacles))return path;
        // A tucked hat may need the adult to climb the side tread before advancing past a front riser.
        // These two finite alternatives use the same support, full envelopes and exact posed checks.
        for(double x:new double[]{-.12,.12}){
            double startY=HatVessel.travelSupport(mode(),x,from.z,radiusOf(parent));
            double endY=HatVessel.travelSupport(mode(),x,to.z,radiusOf(parent));
            if(!Double.isFinite(startY)||!Double.isFinite(endY))continue;
            var route=new java.util.ArrayList<Vec3>();route.add(from);boolean clear=true;
            for(var target:java.util.List.of(new Vec3(x,startY,from.z),new Vec3(x,endY,to.z),to)){
                var segment=HatSurfaceRoute.find(mode(),route.getLast(),target,radiusOf(parent),obstacles,offset,radiusOf(child),
                        HatVessel.bodySpace(parent,hullScale(),unit()),HatVessel.bodySpace(child,hullScale(),unit()));
                if(!posedRouteClear(segment,parent,null,offset,geometry,obstacles)){clear=false;break;}
                route.addAll(segment.subList(1,segment.size()));
            }
            if(clear)return java.util.List.copyOf(route);
        }
        return java.util.List.of();
    }
    /** Find a supported pickup orientation, including the narrow centre aisle, before committing custody. */
    private CarryPickup carryPickup(YukkuriEntity child,YukkuriEntity parent,Vec3 from,Vec3 to,Vec3 ownerDestination,
                                    CarrySearch cache) {
        if(cache.preparePlaces==null){
        var preparePlaces=new java.util.ArrayList<Vec3>();preparePlaces.add(from);
        // A dependent child can crawl along its current tread before tucking; it never crosses a riser unheld.
        for(double dz:new double[]{.03,.032,.034,.08,.16,.24,.36,.48,-.08,-.16}){
            var at=from.add(0,0,dz);
            if(Math.abs(HatVessel.surface(mode(),at.x,at.z)-from.y)>1e-5)continue;
            if(!HatVessel.fits(mode(),at,radiusOf(child),takenExcept(child.getUUID()),HatVessel.bodySpace(child,hullScale(),unit())))continue;
            var path=walkingRoute(child,at);if(!path.isEmpty()&&!hasStep(path))preparePlaces.add(at);
        }
        cache.preparePlaces=java.util.List.copyOf(preparePlaces);
        }
        for(var at:cache.preparePlaces){var plan=carryPickupAt(child,parent,at,to,ownerDestination,cache);if(plan!=null)return plan;}
        return null;
    }
    private CarryPickup carryPickupAt(YukkuriEntity child,YukkuriEntity parent,Vec3 from,Vec3 to,Vec3 ownerDestination,
                                      CarrySearch cache){
        var straight=HatCareAnchor.offset(parent,child,new Vec3(0,0,-1),unit());
        if(straight==null){carryPlanDebug="missing authored mouth anchor";return null;}
        var directions=new java.util.ArrayList<Vec3>();double reach=straight.horizontalDistance();
        // A small yaw lets a child beside the centre aisle meet an adult whose whole body fits it.
        for(double x:new double[]{0,-.12,.12,toLocal(parent.position()).x,from.x}){
            double dx=from.x-x;if(Math.abs(dx)<reach)directions.add(new Vec3(dx,0,-Math.sqrt(reach*reach-dx*dx)).normalize());
        }
        var obstacles=takenExcept(child.getUUID());
        for(var direction:directions){
            var offset=HatCareAnchor.offset(parent,child,direction,unit());var xz=from.subtract(offset);
            var pickup=new Vec3(xz.x,HatVessel.travelSupport(mode(),xz.x,xz.z,radiusOf(parent)),xz.z);
            if(!Double.isFinite(pickup.y)||!serviceGeometryFits(parent,child,pickup,from)){carryPlanDebug="pickup contact out of range "+pickup;continue;}
            var geometry=cache.geometry.computeIfAbsent(direction,key->HatCareGeometry.plan(parent,child,unit(),key));
            if(geometry==null)continue;
            // Reject an impossible delivery before building preparation samples or routing the pickup.
            var delivery=ownerDestination;
            if(delivery==null){
                var at=to.subtract(offset);
                double support=HatVessel.travelSupport(mode(),at.x,at.z,radiusOf(parent));
                if(!Double.isFinite(support))continue;
                delivery=new Vec3(at.x,support,at.z);
            }
            var deliveryKey=new DeliveryKey(to,delivery,direction);
            String lowerFailure=cache.deliveries.get(deliveryKey);
            if(lowerFailure==null){
                lowerFailure=carryTransferClear(child,delivery.add(offset),to,delivery,geometry,obstacles)?"":carryTransferDebug;
                cache.deliveries.put(deliveryKey,lowerFailure);
            }
            if(!lowerFailure.isEmpty()){
                carryPlanDebug="lower to="+to+" from="+delivery.add(offset)+" blocked "+lowerFailure;continue;
            }
            var key=new PickupKey(from,direction);
            if(cache.pickups.containsKey(key)){if(cache.pickups.get(key)==null)continue;}
            else{
                cache.pickups.put(key,null);
                if(!geometry.clear(pickup,from,mode(),obstacles)){carryPlanDebug="posed pickup endpoint blocked "+geometry.debug();continue;}
                var prepareObstacles=new java.util.ArrayList<double[]>(obstacles);prepareObstacles.add(rescueBody(parent,toLocal(parent.position())));
                if(!geometry.prepareClear(from,mode(),prepareObstacles)){carryPlanDebug="supported tuck blocked "+geometry.debug();continue;}
                if(pickupRoute(parent,child,pickup,from,geometry).isEmpty()){carryPlanDebug="posed pickup route blocked "+pickup+" "+geometry.debug();continue;}
                if(!carryTransferClear(child,from,pickup.add(offset),pickup,geometry,obstacles)){carryPlanDebug="lift blocked "+carryTransferDebug;continue;}
                cache.pickups.put(key,new CarryPickup(from,pickup,offset,ownerDestination,geometry));
            }
            if(carriedRoute(parent,child,pickup,delivery,offset,geometry).isEmpty()){carryPlanDebug="posed carried pair route blocked";continue;}
            carryPlanDebug="ready pickup="+pickup+" offset="+offset;
            return new CarryPickup(from,pickup,offset,delivery,geometry);
        }
        return null;
    }

    private boolean beginCarry(YukkuriEntity child,YukkuriEntity parent,double[] destination,Vec3 ownerDestination) {
        return beginCarry(child,parent,destination,ownerDestination,new CarrySearch());
    }
    private boolean beginCarry(YukkuriEntity child,YukkuriEntity parent,double[] destination,Vec3 ownerDestination,CarrySearch search) {
        if(carryTrip!=null)return carryTrip.child.equals(child.getUUID());
        Vec3 from=toLocal(child.position()),to=new Vec3(destination[0],destination[1],destination[2]);
        var plan=carryPickup(child,parent,from,to,ownerDestination,search);if(plan==null)return false;
        boolean crawl=from.distanceToSqr(plan.childAt())>1e-6;
        if(crawl&&!movePreparationCrawl(child,plan.childAt()))return false;
        ownerServiceSeat=toLocal(parent.position());ownerSeatMove=null;
        if(!crawl){seatMoves.remove(child.getUUID());seats.put(child.getUUID(),new double[]{from.x,from.y,from.z,radiusOf(child)});}
        carryTrip=new CarryTrip(child.getUUID(),plan.childAt(),to,plan.at(),plan.delivery(),plan.offset(),level().getGameTime());
        carryTrip.phase=crawl?-2:-1;carryTrip.geometry=plan.geometry();carryTrip.lastAnchor=from;
        syncSeats();return true;
    }
    private boolean beginReturnCarry(YukkuriEntity child,YukkuriEntity parent,Vec3 target){
        // A tucked hat may need more of the current tread before it unfolds. Keep the
        // original reservation: returnCareSeats subsequently crawls the last distance upright.
        long stamp=mode().ordinal();
        for(var point:java.util.List.of(toLocal(child.position()),toLocal(parent.position()),target))
            for(double value:new double[]{point.x,point.y,point.z})stamp=stamp*31+Math.round(value*1000);
        for(double value:new double[]{child.getBbWidth(),parent.getBbWidth(),child.growthScale(),parent.growthScale(),unit()})stamp=stamp*31+Math.round(value*100000);
        for(var obstacle:takenExcept(child.getUUID()))for(double value:obstacle)stamp=stamp*31+Math.round(value*1000);
        var retry=returnRetries.get(child.getUUID());long now=level().getGameTime();
        if(retry!=null&&retry.geometryStamp()==stamp&&now<retry.at())return false;
        var search=new CarrySearch();var failures=new StringBuilder();
        for(double dz:new double[]{0,.03,.08,.16,.24,.36,.48,.60,.72,.84,-.08,-.24}){
            var at=target.add(0,0,dz);
            if(Math.abs(HatVessel.surface(mode(),at.x,at.z)-target.y)>1e-5)continue;
            if(!HatVessel.fits(mode(),at,radiusOf(child),layoutTaken(child.getUUID()),HatVessel.bodySpace(child,hullScale(),unit())))continue;
            var destination=new double[]{at.x,at.y,at.z,radiusOf(child)};
            if(beginCarry(child,parent,destination,null,search)){
                returnRetries.remove(child.getUUID());
                returningSeats.putIfAbsent(child.getUUID(),new ServiceSeat(
                        new double[]{target.x,target.y,target.z,radiusOf(child)},region(),destination,null,false,false));
                return true;
            }
            if(failures.length()<1800)failures.append(" dz=").append(dz).append(' ').append(carryPlanDebug).append(';');
        }
        carryPlanDebug="return original="+target+failures;
        returnRetries.put(child.getUUID(),new ReturnRetry(stamp,now+80));
        return false;
    }
    private void setCarryWalk(YukkuriEntity parent,Vec3 target,java.util.List<Vec3> path){
        ownerServiceSeat=target;
        ownerSeatMove=new SeatMove(toLocal(parent.position()),level().getGameTime(),Math.max(16,(int)Math.ceil(HatSurfaceRoute.length(path)/.20)),false,path);
    }

    private void tickCarry() {
        if (carryTrip==null) return;
        var trip=carryTrip;
        var parent=owner();
        var child=level() instanceof ServerLevel server && server.getEntity(trip.child) instanceof YukkuriEntity y?y:null;
        if (parent==null || child==null) return;
        if (!parent.isAlive() || parent.getVehicle()!=this || child.getVehicle()!=this || !isAfloat()) {
            // Losing a holder releases a real short fall onto the measured patch below the child.
            Vec3 at=toLocal(child.position()); double h=HatVessel.footprint(mode(),at.x,at.z,radiusOf(child));
            if (child.getVehicle()==this && Double.isFinite(h)) {
                var target=new Vec3(at.x,h,at.z); seats.put(child.getUUID(),new double[]{target.x,target.y,target.z,radiusOf(child)});
                seatMoves.put(child.getUUID(),new SeatMove(at,level().getGameTime(),12,true));
            }
            carryTrip=null; syncSeats(); return;
        }
        stopPaddling();
        if(trip.phase==-2){
            if(recoverCarry){
                if(!movePreparationCrawl(child,trip.from))return;
                recoverCarry=false;
            }
            if(atSeat(child,trip.from)){seatMoves.remove(child.getUUID());trip.phase=-1;trip.at=level().getGameTime();syncSeats();}
            return;
        }
        if(trip.geometry==null)trip.geometry=HatCareGeometry.plan(parent,child,unit(),trip.facing);
        if(trip.geometry==null)return;
        float carryYaw=io.github.longshou.mcyukkuri.life.SurfaceApproach.yaw(Vec3.ZERO,trip.facing.yRot(turn()));
        io.github.longshou.mcyukkuri.life.SurfaceApproach.face(parent,carryYaw);
        io.github.longshou.mcyukkuri.life.SurfaceApproach.face(child,carryYaw+180);
        var obstacles=takenExcept(child.getUUID());
        if(trip.phase==-1){
            var preparation=new java.util.ArrayList<double[]>(obstacles);preparation.add(rescueBody(parent,toLocal(parent.position())));
            if(!trip.geometry.preparationClear(trip.from,mode(),preparation,Math.min(.6,(level().getGameTime()-trip.at)/20.0),false))return;
            if(level().getGameTime()-trip.at<12)return;
            var path=pickupRoute(parent,child,trip.pickup,trip.from,trip.geometry);
            if(path.isEmpty())return;
            setCarryWalk(parent,trip.pickup,path);trip.phase=0;trip.at=level().getGameTime();recoverCarry=false;
            trip.geometry.discardPreparationSamples();syncSeats();return;
        }
        if(recoverCarry){
            recoverCarry=false;
            if(trip.phase==0||trip.phase==2){
                var path=trip.phase==0?pickupRoute(parent,child,trip.pickup,trip.from,trip.geometry)
                        :carriedRoute(parent,child,toLocal(parent.position()),trip.ownerDestination,trip.offset,trip.geometry);
                if(path.isEmpty()){recoverCarry=true;return;}
                setCarryWalk(parent,trip.phase==0?trip.pickup:trip.ownerDestination,path);syncSeats();
            }
        }
        if(trip.phase==0){
            if(atSeat(parent,trip.pickup)){
                if(FamilyFeedingContact.mouthsMeet(parent,child))trip.contact++;else trip.contact=0;
                if(trip.contact>=12){trip.phase=1;trip.at=level().getGameTime();syncSeats();}
            }
            return;
        }
        if(trip.phase==1&&level().getGameTime()-trip.at>=12){
            var path=carriedRoute(parent,child,toLocal(parent.position()),trip.ownerDestination,trip.offset,trip.geometry);
            if(path.isEmpty()){
                carryTrip=new CarryTrip(trip.child,trip.from,trip.from,trip.pickup,trip.pickup,trip.offset,level().getGameTime());
                carryTrip.geometry=trip.geometry;carryTrip.phase=3;syncSeats();return;
            }
            setCarryWalk(parent,trip.ownerDestination,path);trip.phase=2;trip.at=level().getGameTime();syncSeats();
        }else if(trip.phase==2&&atSeat(parent,trip.ownerDestination)){
            trip.phase=3;trip.at=level().getGameTime();syncSeats();
        }else if(trip.phase==3&&level().getGameTime()-trip.at>=12){
            seats.put(child.getUUID(),new double[]{trip.destination.x,trip.destination.y,trip.destination.z,radiusOf(child)});
            trip.phase=4;trip.at=level().getGameTime();syncSeats();
        }else if(trip.phase==4){
            var releaseObstacles=new java.util.ArrayList<double[]>(obstacles);releaseObstacles.add(rescueBody(parent,toLocal(parent.position())));
            boolean clear=true;
            for(int tick=0;tick<=12&&clear;tick++)clear=trip.geometry.preparationClear(trip.destination,mode(),releaseObstacles,tick/20.0,true);
            if(clear){trip.phase=5;trip.at=level().getGameTime();syncSeats();return;}
            // A failed/recovered transfer keeps the child supported and tucked until the adult really withdraws.
            if(!changingOwnerSeat()&&level().getGameTime()%20==0){
                var retreat=ownerSeat(parent);var path=pickupRoute(parent,child,retreat,trip.destination,trip.geometry);
                if(!path.isEmpty())setCarryWalk(parent,retreat,path);
            }
        }else if(trip.phase==5&&level().getGameTime()-trip.at>=12){
            carryTrip=null;
            var service=serviceSeats.get(child.getUUID());
            if(service!=null&&service.ownerService()!=null){
                if(!moveOwnerSeat(parent,service.ownerService(),false))releaseCareSeat(child.getUUID());
            }
            else if(serviceSeats.isEmpty()&&returningSeats.isEmpty())moveOwnerSeat(parent,ownerSeat(parent),false);
            syncSeats();
        }
    }

    /** Presentation follows the same synchronized preparation and release clock as physical custody. */
    public String carryActivity(Entity rider){
        var trip=carryPose();if(trip==null)return Behaviors.IDLE;
        if(rider.getUUID().equals(ownerYukkuri()))return Behaviors.BOAT_CARRY_YOUNG;
        if(!rider.getUUID().equals(trip.child))return Behaviors.IDLE;
        return trip.phase==-2?Behaviors.HAT_WALK:trip.phase==-1?Behaviors.BOAT_PREPARE_CARRY:trip.phase==5?Behaviors.BOAT_RELEASE_CARRY:Behaviors.BOAT_BE_CARRIED;
    }
    public float carrySeconds(float partial){
        var trip=carryPose();return trip==null?0:(float)Math.max(0,(level().getGameTime()-trip.at+partial)/20.0);
    }
    public boolean carryingInHat(Entity rider) {
        var trip=carryPose(); return trip!=null && trip.phase>0 && trip.phase<=3 && (rider.getUUID().equals(trip.child)||rider.getUUID().equals(ownerYukkuri()));
    }
    public boolean transportingInHat(Entity rider) {
        var trip=carryPose(); return trip!=null && (rider.getUUID().equals(trip.child)||rider.getUUID().equals(ownerYukkuri()));
    }
    public UUID hatCarrier(YukkuriEntity child) {
        var trip=carryPose(); return trip!=null && trip.phase>0 && trip.phase<=3 && trip.child.equals(child.getUUID())?ownerYukkuri():null;
    }
    public Vec3 hatCarryAnchor(YukkuriEntity child) {
        var trip=carryPose();
        if(trip==null || !trip.child.equals(child.getUUID()))return null;
        var parent=getPassengers().stream().filter(p->p.getUUID().equals(ownerYukkuri())).findFirst().orElse(null);
        return trip.phase==2 && parent!=null?parent.position().add(trip.offset.scale(unit()).yRot(turn())):toWorld(carriedLocal(trip));
    }
    private Vec3 ownerCarryLocal(YukkuriEntity parent) {
        Vec3 target=ownerServiceSeat(parent); if (target==null) target=ownerSeat(parent);
        var movement=ownerServiceMove(parent);
        if (movement==null) return target;
        double time=Math.clamp(level().getGameTime()-movement.start(),0,movement.ticks());
        return movement.route().isEmpty()?movement.from():walkingLocal(parent,movement,time);
    }
    private Vec3 carriedLocal(CarryTrip trip) {
        if(trip.phase==-2){
            var child=getPassengers().stream().filter(p->p.getUUID().equals(trip.child)).findFirst().orElse(null);
            return child==null?trip.lastAnchor:toLocal(child.position());
        }
        if (trip.phase<=0) return trip.from;
        if (trip.phase>=4) return trip.destination;
        var parent=getPassengers().stream().filter(p->p.getUUID().equals(ownerYukkuri())&&p instanceof YukkuriEntity)
                .map(p->(YukkuriEntity)p).findFirst().orElse(null);
        if (parent==null) return trip.lastAnchor;
        Vec3 anchor=ownerCarryLocal(parent).add(trip.offset);
        double q=Math.clamp((level().getGameTime()-trip.at)/12.0,0,1); q=q*q*(3-2*q);
        trip.lastAnchor=trip.phase==1?trip.from.lerp(anchor,q):trip.phase==3?anchor.lerp(trip.destination,q):anchor;
        return trip.lastAnchor;
    }
    static void point(CompoundTag tag,String key,Vec3 point) {
        tag.putDouble(key+"X",point.x);tag.putDouble(key+"Y",point.y);tag.putDouble(key+"Z",point.z);
    }
    static Vec3 point(CompoundTag tag,String key) {
        return new Vec3(tag.getDouble(key+"X"),tag.getDouble(key+"Y"),tag.getDouble(key+"Z"));
    }
    private CompoundTag saveCarry(CarryTrip trip) {
        var tag=new CompoundTag();tag.putUUID("Child",trip.child);if(ownerYukkuri()!=null)tag.putUUID("Holder",ownerYukkuri());
        tag.putInt("Phase",trip.phase);tag.putLong("At",trip.at);tag.putLong("Elapsed",Math.max(0,level().getGameTime()-trip.at)); point(tag,"From",trip.from);point(tag,"To",trip.destination);
        point(tag,"Pickup",trip.pickup);point(tag,"OwnerTo",trip.ownerDestination);point(tag,"Offset",trip.offset);point(tag,"Facing",trip.facing);
        // The pickup/preparation point can be a temporary care station, not the child's real bed.
        var reservation=returningSeats.get(trip.child);
        if(reservation==null)reservation=serviceSeats.get(trip.child);
        if(reservation!=null){
            var original=reservation.original();
            point(tag,"Return",new Vec3(original[0],original[1],original[2]));
            tag.putDouble("ReturnRadius",original[3]);tag.putString("ReturnRegion",reservation.originalRegion());
        }
        var parent=getPassengers().stream().filter(p->p.getUUID().equals(ownerYukkuri())&&p instanceof YukkuriEntity).map(p->(YukkuriEntity)p).findFirst().orElse(null);
        if (parent!=null) point(tag,"OwnerNow",ownerCarryLocal(parent));
        point(tag,"ChildNow",carriedLocal(trip)); return tag;
    }
    private CarryTrip carryPose() {
        if (!level().isClientSide) return carryTrip;
        var tag=entityData.get(SEATS).getCompound("Carry"); if (!tag.hasUUID("Child")) return null;
        var trip=new CarryTrip(tag.getUUID("Child"),point(tag,"From"),point(tag,"To"),point(tag,"Pickup"),point(tag,"OwnerTo"),point(tag,"Offset"),tag.getLong("At"));
        trip.phase=tag.getInt("Phase");trip.lastAnchor=point(tag,"ChildNow");if(tag.contains("FacingX"))trip.facing=point(tag,"Facing");return trip;
    }

    private Vec3 escortPlace(YukkuriEntity parent,YukkuriEntity child,Vec3 brim){
        double distance=(parent.getBbWidth()+child.getBbWidth())*.55/unit();
        for(double side:new double[]{0,.8,-.8,1.6,-1.6}){
            double x=brim.x+side,z=brim.z+distance;
            double y=HatVessel.travelSupport(mode(),x,z,radiusOf(parent));
            var at=new Vec3(x,y,z);
            if(Double.isFinite(y))return at;
        }
        return null;
    }
    public boolean walkingInHat(Entity rider){
        var move=rider.getUUID().equals(ownerYukkuri())&&rider instanceof YukkuriEntity y?ownerServiceMove(y):seatMove(rider);
        return move!=null&&!move.arc()&&!move.route().isEmpty()&&level().getGameTime()>=move.start()&&level().getGameTime()<move.start()+move.ticks();
    }
    private Vec3 walkingLocal(Entity rider,SeatMove movement,double time){
        double gait=time-24*Math.sin(time*Math.PI/12)/(2*Math.PI);
        double total=movement.ticks()-24*Math.sin(movement.ticks()*Math.PI/12)/(2*Math.PI);
        Vec3 local = HatSurfaceRoute.sample(movement.route(),gait/Math.max(1,total));
        double support = HatVessel.travelSupport(mode(), local.x, local.z, radiusOf(rider));
        // Lift onto a short ledge without ever sinking the feet through its surface.
        return new Vec3(local.x, Math.max(local.y, support), local.z);
    }
    private Vec3 walkingPosition(Entity rider,SeatMove movement,double time){
        Vec3 at=toWorld(walkingLocal(rider,movement,time));
        if(!level().isClientSide&&time>0&&time<movement.ticks()&&rider instanceof YukkuriEntity y&&at.subtract(rider.position()).horizontalDistanceSqr()>.00001) {
            float yaw=carryTrip!=null && carryTrip.phase>0 && rider.getUUID().equals(ownerYukkuri())
                    ?io.github.longshou.mcyukkuri.life.SurfaceApproach.yaw(Vec3.ZERO,carryTrip.facing.yRot(turn()))
                    :rider.getUUID().equals(departure.departureYielding)?departure.departureYieldYaw
                    :io.github.longshou.mcyukkuri.life.SurfaceApproach.yaw(rider.position(),at);
            io.github.longshou.mcyukkuri.life.SurfaceApproach.face(y,yaw);
        }
        return at;
    }

    private Vec3 ownerServiceSeat(YukkuriEntity owner) {
        if (!level().isClientSide) return ownerServiceSeat;
        UUID id = owner.getUUID();
        for (var raw : entityData.get(SEATS).getList("S", net.minecraft.nbt.Tag.TAG_COMPOUND)) if (raw instanceof CompoundTag c
                && c.getBoolean("OwnerService") && c.hasUUID("Id") && c.getUUID("Id").equals(id))
            return new Vec3(c.getDouble("X"), c.getDouble("Y"), c.getDouble("Z"));
        return null;
    }

    private SeatMove ownerServiceMove(YukkuriEntity owner) {
        if (!level().isClientSide) return ownerSeatMove;
        UUID id = owner.getUUID();
        for (var raw : entityData.get(SEATS).getList("S", net.minecraft.nbt.Tag.TAG_COMPOUND)) if (raw instanceof CompoundTag c
                && c.getBoolean("OwnerService") && c.hasUUID("Id") && c.getUUID("Id").equals(id) && c.contains("At"))
            return new SeatMove(new Vec3(c.getDouble("FromX"), c.getDouble("FromY"), c.getDouble("FromZ")), c.getLong("At"), c.getInt("Ticks"), c.getBoolean("Arc"),HatSurfaceRoute.load(c));
        return null;
    }
    public boolean onBrim(Entity young){var s=seatOf(young);return isAfloat()&&s!=null&&Math.abs(s.y-HatVessel.FREEBOARD)<1e-5&&!changingSeat(young);}
    private boolean brimSafe(Entity rider) {
        var captain=owner();
        return rider instanceof YukkuriEntity y && !y.family().adult()
                // Untrained newborns need a level cabin tread for real crawling and hop practice.
                &&y.childhood().canJump()
                && isAfloat() && flooding==0 && !towed() && !hasIncoming() && !level().isNight() && !level().isRainingAt(blockPosition())
                && captain!=null && !captain.isPanicking() && !captain.family().laboring() && !captain.life().isSleeping()
                && !captain.aquaticLife().care().active() && !y.isPanicking() && !y.life().isSleeping()
                && !y.isNoAi() && !y.isCommandPlaying() && !y.operations().active() && !crowding.emergency(y.getUUID())
                && y.life().mealTicks()==0 && y.life().energy()>25 && y.life().nutrition()>=60;
    }
    /** Server: young aboard without a place (after loading) get one; afloat, at dusk and dawn they all move (before the
     *  owner by day, gathered near her lower front at night). */
    private void tendSeats() {
        if(cargo.handling())return;
        if(rescueOwnerHeld){
            var parent=owner();
            if(parent==null||parent.getVehicle()!=this){
                rescueOwnerHeld=false;ownerServiceSeat=null;ownerSeatMove=null;syncSeats();
            }else if(!parent.isAlive()||!parent.family().braidRescueActive()&&!parent.family().cradlePickup().active())releaseRescueOwner(parent);
        }
        if (carryTrip != null||rescueOwnerHeld) return;
        // Large juveniles need the scarce wide spaces first. Small siblings then fit around them or inside.
        var young = youngPassengers().stream().sorted(java.util.Comparator.comparingDouble(this::radiusOf).reversed()).toList();
        // Night, fatigue and growth can make a previously safe brim berth unsuitable. Clear real food first.
        for(var rider:young)if(rider instanceof YukkuriEntity child&&plannedSeat(child,null,false)==null){
            prepareCargoSpace(child,false);if(cargo.handling())return;
        }
        boolean resized = young.stream().anyMatch(p -> seats.containsKey(p.getUUID()) && Math.abs(seats.get(p.getUUID())[3]-radiusOf(p))>.001);
        if (resized && !serviceSeats.isEmpty()) {
            for (var id : java.util.List.copyOf(serviceSeats.keySet())) releaseCareSeat(id);
        }
        serviceSeats.keySet().removeIf(id -> young.stream().noneMatch(p -> p.getUUID().equals(id)));
        returnCareSeats();
        if(carryTrip!=null)return;
        // A single departure yield owns its existing seats until both actual feet have settled.
        // Do not let routine layout/growth replanning replace that finite walk's destination.
        if(departure.departureYielding!=null)return;
        var captain=getControllingPassenger();
        String wanted=region()+":"+hullScale()+":"+cargo.tier()+":"+cargo.asideTier()+":"+(captain==null?"none":radiusOf(captain)+":"+captain.getBbHeight())
                +young.stream().map(p->p.getUUID()+":"+brimSafe(p)+":"+(p instanceof YukkuriEntity y?HatVessel.bodySpace(y,hullScale(),unit()):p.getBbHeight())).toList();
        boolean moved = resized || !wanted.equals(seatRegion) && !catches(); seatRegion = wanted;
        var old=new java.util.HashMap<UUID,double[]>(seats);
        // Growth may replan a destination, but never discard a live movement midway through it.
        if (moved) seats.clear();
        if (departure.voyageBerth != null && young.stream().anyMatch(p -> p.getUUID().equals(departure.voyageYoung)))
            seats.putIfAbsent(departure.voyageYoung,old.getOrDefault(departure.voyageYoung,departure.voyageBerth).clone());
        if (moved) for (var p : young) {
            var service = serviceSeats.get(p.getUUID());
            if (service != null) seats.put(p.getUUID(), service.service().clone());
            else if ((returningSeats.containsKey(p.getUUID()) || changingSeat(p)) && old.containsKey(p.getUUID())) seats.put(p.getUUID(), old.get(p.getUUID()));
        }
        boolean changed = moved;
        for (var p : young) if (!seats.containsKey(p.getUUID())) {
            double r = radiusOf(p); var s = p instanceof YukkuriEntity y?plannedSeat(y,null,false):null;
            if (s == null) {
                cramped.add(p.getUUID());var previous=old.get(p.getUUID());
                if(previous!=null)seats.put(p.getUUID(),previous);
                else {var at=toLocal(p.position());seats.put(p.getUUID(),new double[]{at.x,at.y,at.z,r});}
                continue;
            }
            cramped.remove(p.getUUID());
            var before=old.get(p.getUUID());
            if(before!=null&&p instanceof YukkuriEntity y){
                seats.put(p.getUUID(),before);
                double[] destination=new double[]{s.x,s.y,s.z,r};
                if(!moveYoungSeat(y,destination,false)){
                    if(cargo.innerCount()>0){cargo.moveAside(cargo.count());if(cargo.handling()){seatRegion="";return;}}
                    var parent=owner();
                    if(!y.childhood().canJump()&&parent!=null&&serviceSeats.isEmpty()&&returningSeats.isEmpty()){
                        // The carry planner must still see siblings whose new day/night berth has not
                        // been chosen yet. They stay in their real old seats throughout this transfer.
                        var restored=new java.util.ArrayList<UUID>();
                        for(var entry:old.entrySet())if(!seats.containsKey(entry.getKey())){
                            seats.put(entry.getKey(),entry.getValue());restored.add(entry.getKey());
                        }
                        if(beginReturnCarry(y,parent,new Vec3(destination[0],destination[1],destination[2]))){seatRegion="";return;}
                        for(var id:restored)seats.remove(id);
                    }
                    seatRegion="";continue;
                }
            }
            seats.put(p.getUUID(), new double[]{s.x, s.y, s.z, r}); changed = true;
        }
        if (changed) syncSeats();
        cramped.removeIf(id->young.stream().noneMatch(p->p.getUUID().equals(id)));
        if("night".equals(region())&&getControllingPassenger() instanceof YukkuriEntity parent){
            for(var p:young)if(p instanceof YukkuriEntity y&&y.life().isSleeping()&&!changingSeat(y)
                    &&!serviceSeats.containsKey(y.getUUID())&&!returningSeats.containsKey(y.getUUID())){
                float yaw=io.github.longshou.mcyukkuri.life.SurfaceApproach.yaw(parent.position(),y.position());
                io.github.longshou.mcyukkuri.life.SurfaceApproach.face(y,yaw);
            }
        }
    }
    /** Places of the young aboard taken in boarding order: only until the server's places arrive. */
    private java.util.List<Vec3> youngSeats() {
        var young = youngPassengers(); var key = new StringBuilder(region()).append(mode()).append(hullScale());
        for (var p : young) key.append(p.getId()).append(':').append(p.getBbWidth());
        if (!key.toString().equals(seatKey)) { seatKey = key.toString(); seatCache = HatVessel.pack(mode(), young.stream().mapToDouble(this::radiusOf).toArray(), region()); }
        return seatCache;
    }
    /** A place for this young (nearest prefer, hat units; null for the usual one) that it can reach from where it is
     *  without crossing the others, kept for it until it boards. */
    public Vec3 reserve(YukkuriEntity child, Vec3 prefer) {
        if(child==null||!young(child))return null;
        if(cargo.handling())return null;
        var have = incoming.get(child.getUUID());
        if(have!=null&&!cargoSeatClear(child,new Vec3(have[0],have[1],have[2]))){incoming.remove(child.getUUID());have=null;}
        if (have == null && child.getUUID().equals(departure.voyageYoung) && departure.voyageBerth != null
                &&cargoSeatClear(child,new Vec3(departure.voyageBerth[0],departure.voyageBerth[1],departure.voyageBerth[2]))) incoming.put(child.getUUID(),have=departure.voyageBerth.clone());
        if (have == null) { double r = radiusOf(child); var s = plannedSeat(child,prefer,false); if(s==null)return null;incoming.put(child.getUUID(), have = new double[]{s.x, s.y, s.z, r}); }
        return new Vec3(have[0], have[1], have[2]);
    }
    /** The place a young would get next, without keeping it. */
    public Vec3 peekSeat(YukkuriEntity child) {
        if(child==null||!young(child))return null;
        var have = incoming.get(child.getUUID());
        return have != null ? new Vec3(have[0], have[1], have[2]) : plannedSeat(child,null,false);
    }
    /** Before the hat is taken back the young hop out over the brim, side by side on the side clear of the adults there
     *  (onto the bank beside a floated hat): each to the spot that keeps their ways apart, the one nearest its spot first,
     *  4 ticks apart, so none hops over a sibling still sitting. True while any are aboard or still to go. */
    public boolean releaseYoung(YukkuriEntity taker) {
        if (level().isClientSide) return false;
        if(taker.family().cradlePickup().active())return true;
        if (!leaving.isEmpty()) return true;
        var young = youngPassengers().stream().filter(p -> p instanceof YukkuriEntity).map(p -> (YukkuriEntity) p).toList();
        if (young.isEmpty()) return false;
        var others = new java.util.ArrayList<double[]>();
        for (var adult : level().getEntitiesOfClass(YukkuriEntity.class, getBoundingBox().inflate(4), y -> y.family().adult()))
            others.add(new double[]{adult.getX(), adult.getZ(), adult.getBbWidth()*.5});
        if (others.stream().noneMatch(o -> o[0] == taker.getX() && o[1] == taker.getZ())) others.add(new double[]{taker.getX(), taker.getZ(), taker.getBbWidth()*.5});
        var bank = taker.position().subtract(position());
        var spots = HatVessel.outSpots(this, young.stream().mapToDouble(y -> HatVessel.radius(y.getBbWidth())).toArray(), others,
                isMoored() && bank.horizontalDistanceSqr() > 1e-6 ? new Vec3(bank.x, 0, bank.z).normalize() : null);
        var froms = young.stream().map(Entity::position).toList(); var order = HatVessel.match(froms, spots);
        var turn = new java.util.ArrayList<Integer>(); for (int i = 0; i < young.size(); i++) turn.add(i);
        turn.sort(java.util.Comparator.comparingDouble(i -> froms.get(i).distanceToSqr(spots.get(order[i]))));
        long now = level().getGameTime();
        for (int k = 0; k < turn.size(); k++) {
            int i = turn.get(k); var child = young.get(i);
            if(!child.childhood().canJump()){
                var to=ground(spots.get(order[i]),child);
                if(to!=null)taker.family().cradlePickup().start(this,child,to);
                // One dependent child at a time, kept in the real hat until the braid grips it.
                return true;
            }
            leaving.add(new Leaving(child.getUUID(), spots.get(order[i]), now+4L*k, !child.childhood().canJump()));
        }
        return true;
    }
    /** Server: the young whose turn it is hop out. */
    private void tendLeaving() {
        if (leaving.isEmpty() || !(level() instanceof ServerLevel server)) return;
        long now = level().getGameTime();
        leaving.removeIf(l -> {
            if (l.at() > now) return false;
            if (server.getEntity(l.young()) instanceof YukkuriEntity child && child.getVehicle() == this) {
                var to = ground(l.spot(), child);
                if (to != null) {
                    if(l.assisted()){
                        var parent=owner();
                        if(parent!=null&&!parent.family().cradlePickup().active())parent.family().cradlePickup().start(this,child,to);
                        return false;
                    }
                    var from = child.position(); child.stopRiding(); crew.remove(child.getUUID());
                    child.family().hop(this, from, to);
                }
            }
            return true;
        });
    }
    /** Standing room on the ground at a spot, or null. */
    private Vec3 ground(Vec3 spot, YukkuriEntity child) {
        var hit = level().clip(new net.minecraft.world.level.ClipContext(spot.add(0, 1.2, 0), spot.add(0, -1.5, 0),
                net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.ANY, child));
        if (hit.getType() != net.minecraft.world.phys.HitResult.Type.BLOCK || !level().getFluidState(hit.getBlockPos()).isEmpty()) return null;
        var at = hit.getLocation();
        return level().noCollision(child, child.getBoundingBox().move(at.subtract(child.position()))) ? at : null;
    }
    public boolean towed() { return pullTicks > 0; }
    /** Drift toward a bank point, as when towed (an aquatic mother coming ashore to give birth). */
    public void towTo(Vec3 target, int ticks) { if (isAfloat()) { stopPaddling(); pullTarget = target; pullTicks = Math.max(pullTicks, ticks); } }
    /** Nearest dry, standable block beside water within a few blocks, or null. */
    public BlockPos nearestBank() {
        BlockPos best = null; double bestDistance = Double.MAX_VALUE;
        for (var p : BlockPos.betweenClosed(blockPosition().offset(-8, -1, -8), blockPosition().offset(8, 2, 8))) {
            if (!level().hasChunkAt(p) || !level().getFluidState(p).isEmpty() || !level().getBlockState(p).isAir()
                    || !level().getBlockState(p.below()).isFaceSturdy(level(), p.below(), net.minecraft.core.Direction.UP)) continue;
            boolean shore = false;
            for (var side : net.minecraft.core.Direction.Plane.HORIZONTAL) if (level().getFluidState(p.relative(side).below()).is(FluidTags.WATER) || level().getFluidState(p.relative(side)).is(FluidTags.WATER)) shore = true;
            double d = p.distSqr(blockPosition());
            if (shore && d < bestDistance) { bestDistance = d; best = p.immutable(); }
        }
        return best;
    }
    @Override public net.minecraft.world.entity.LivingEntity getControllingPassenger() {
        for (var p : getPassengers()) if (p instanceof YukkuriEntity y && y.getUUID().equals(ownerYukkuri())) return y;
        return null;
    }
    @Override protected void positionRider(Entity passenger, MoveFunction move) {
        if (!hasPassenger(passenger)) return;
        var carrying = carryPose();
        if (carrying != null && carrying.phase!=-2 && passenger.getUUID().equals(carrying.child)) {
            Vec3 at = toWorld(carriedLocal(carrying));
            move.accept(passenger,at.x,at.y,at.z); return;
        }
        float k = hullScale()/16, turn = (float) Math.toRadians(180 - getYRot());
        if (passenger.getUUID().equals(ownerYukkuri())) {
            // In the opening at the stern, on the ledge, her back to its rear edge: the inside before her stays open.
            var local = passenger instanceof YukkuriEntity y && ownerServiceSeat(y) != null ? ownerServiceSeat(y)
                    : passenger instanceof YukkuriEntity y ? ownerSeat(y) : Vec3.ZERO;
            var at = toWorld(local); var movement = passenger instanceof YukkuriEntity y ? ownerServiceMove(y) : null;
            if (movement != null) {
                double time = Math.clamp(level().getGameTime()-movement.start(), 0, movement.ticks());
                Vec3 from = toWorld(movement.from()); at = movement.arc() ? HatVessel.arc(from, at, movement.ticks(), time) : movement.route().isEmpty()?from:walkingPosition(passenger,movement,time);
            }
            move.accept(passenger, at.x, at.y, at.z); return;
        }
        // Each real young occupies its own supported berth, including the close but visible night gathering.
        var local = seatOf(passenger);
        if (local == null) {
            // A late seat sync must not briefly manufacture an empty-hat berth inside the visible cargo.
            if(level().isClientSide&&cargo.count()>0)return;
            int seat = youngPassengers().indexOf(passenger); var packed = youngSeats();
            if (seat < 0 || seat >= packed.size()) return;
            local = packed.get(seat);
        }
        var at = toWorld(local); var movement=seatMove(passenger);
        if(movement!=null){double time=Math.clamp(level().getGameTime()-movement.start(),0,movement.ticks());
            Vec3 from=toWorld(movement.from());at=movement.arc()?HatVessel.arc(from,at,movement.ticks(),time):movement.route().isEmpty()?from:walkingPosition(passenger,movement,time);}
        move.accept(passenger, at.x, at.y, at.z);
    }
    public static HatBoatEntity find(YukkuriEntity y) { return HatBoatLaunch.find(y); }
    public static HatBoatEntity owned(YukkuriEntity y) { return HatBoatLaunch.owned(y); }
    /** Undo a deployment that could not be finished (spawn or scenario): everyone gets off, the same hat goes back on its
     *  owner's head with its provisions kept for it, and the boat disappears. Nothing is duplicated or lost. */
    public void abortDeployment(YukkuriEntity owner) { HatBoatLaunch.abortDeployment(this, owner); }
    public static HatBoatEntity createFor(YukkuriEntity owner, BlockPos water) { return HatBoatLaunch.createFor(owner, water); }
    public static HatBoatEntity launchFromBank(YukkuriEntity owner, BlockPos water) { return HatBoatLaunch.launchFromBank(owner, water); }
    static HatBoatEntity createFor(YukkuriEntity owner, BlockPos water, boolean animated) { return HatBoatLaunch.createFor(owner, water, animated); }
    public static HatBoatEntity launchFromFather(YukkuriEntity child, HatBoatEntity home, BlockPos water) { return HatBoatLaunch.launchFromFather(child, home, water); }
    static HatBoatEntity createFor(YukkuriEntity owner, BlockPos water, boolean animated, HatBoatEntity home) { return HatBoatLaunch.createFor(owner, water, animated, home); }
    /** Reclaim the same empty hat after its young is safe aboard a parent, including a rescued capsize. */
    public boolean retrieveAfloat(YukkuriEntity child, HatBoatEntity home) { return HatBoatLaunch.retrieveAfloat(this, child, home); }
    /** {@code reachFallback} lifts the rim-contact requirement for an empty hat still within a short, bounded distance. */
    public boolean retrieveAfloat(YukkuriEntity child, HatBoatEntity home, boolean reachFallback) { return HatBoatLaunch.retrieveAfloat(this, child, home, reachFallback); }
    /** Same finite physical contact for a floating or stranded empty hat; ownership is not remote reach. */
    public Vec3 retrievalGrip(YukkuriEntity actor){ return HatBoatLaunch.retrievalGrip(this, actor); }
    /** Parent-centre docking target with actual hull/AABB clearance; it grants no remote retrieval. */
    public Vec3 retrievalApproach(HatBoatEntity parentHat,YukkuriEntity child){ return HatBoatLaunch.retrievalApproach(this, parentHat, child); }
    double boxReach(Vec3 toward){ return HatBoatLaunch.boxReach(this, toward); }
    public static HatBoatEntity tryLaunchNearby(YukkuriEntity owner) { return HatBoatLaunch.tryLaunchNearby(owner); }
    /** A Marisa's braid sets her hat down upside down to catch young: pressed on dry ground, or floated at a bank (aquatic). */
    public static HatBoatEntity setDown(YukkuriEntity owner, Vec3 at, float yaw, HatVessel.Mode mode, HatVessel.Role role) { return HatBoatLaunch.setDown(owner, at, yaw, mode, role); }
    /** Put the hat back on: the young climb out first, onto the bank beside a floated hat. */
    public boolean retrieve(YukkuriEntity owner) { return HatBoatLaunch.retrieve(this, owner); }
    public boolean board(YukkuriEntity owner) { return HatBoatLaunch.board(this, owner); }
    public boolean ownsPhysicalHat(YukkuriEntity actor){ return capsizeMotion.ownsPhysicalHat(actor); }
    static final class GripRim {
        static final java.util.List<Vec3> POINTS=points();
        private static java.util.List<Vec3> points(){
            var out=new java.util.ArrayList<Vec3>();
            for(int i=0;i<32;i++){
                double angle=i*Math.PI/16,x=Math.cos(angle),z=Math.sin(angle);
                double reach=Math.max(0,HatVessel.extent(HatVessel.Mode.FLOAT,0,0,x,z,0)-.125);
                out.add(new Vec3(x*reach,HatVessel.FREEBOARD,z*reach));
            }
            return java.util.List.copyOf(out);
        }
    }
    /** A rim point as it actually stands: tilted by a capsize or righting, and lowered where a weight shift dips the near rim. */
    Vec3 rimPoint(Vec3 local){ return capsizeMotion.rimPoint(local); }
    Vec3 nearestGrip(YukkuriEntity actor){ return capsizeMotion.nearestGrip(actor); }
    /** The actual visible rim point the mouth will take hold of; ordinary body reach and block sight are both required. */
    public Vec3 selfRescueGripLocal(YukkuriEntity actor){ return capsizeMotion.selfRescueGripLocal(actor); }
    public Vec3 selfRescueGrip(YukkuriEntity actor,Vec3 localGrip){ return capsizeMotion.selfRescueGrip(actor, localGrip); }
    /** The rim point nearest this body's mouth that a few body-lengths of nudging can take in the teeth. */
    public Vec3 selfRescueGrip(YukkuriEntity actor){ return capsizeMotion.selfRescueGrip(actor); }
    Vec3 reachableGrip(YukkuriEntity actor,Vec3 grip){ return capsizeMotion.reachableGrip(actor, grip); }
    // Boarding's changing root pose has its own full two-segment reach solver; do not substitute the neutral removal root.
    public Vec3 boardingGrip(YukkuriEntity actor){
        var sampled=nearestGrip(actor);if(sampled==null)return null;
        var best=reachableGrip(actor,sampled);var local=toLocal(actor.position());
        double cost=best==null?Double.POSITIVE_INFINITY:actor.getBoundingBox().getCenter().distanceToSqr(best);
        // The coarse polar samples can put a small visitor's mouth far sideways from a straight rim.
        // Also project along each wall normal onto the actual measured edge, preserving its full reach checks.
        for(boolean alongZ:new boolean[]{true,false}){
            double fixed=alongZ?local.x:local.z,sign=Math.signum(alongZ?local.z:local.x);
            if(sign==0||Math.abs(fixed)>(alongZ?HatVessel.BRIM[0]:HatVessel.BRIM[1]))continue;
            double edge=HatVessel.extent(HatVessel.Mode.FLOAT,alongZ?fixed:0,alongZ?0:fixed,
                    alongZ?0:sign,alongZ?sign:0,0)-.125;
            if(edge<=0)continue;
            var at=new Vec3(alongZ?fixed:sign*edge,HatVessel.FREEBOARD,alongZ?sign*edge:fixed);
            double surface=HatVessel.surface(HatVessel.Mode.FLOAT,at.x,at.z);
            if(!Double.isFinite(surface)||Math.abs(surface-HatVessel.FREEBOARD)>1e-5)continue;
            var grip=reachableGrip(actor,rimPoint(at));if(grip==null)continue;
            double distance=actor.getBoundingBox().getCenter().distanceToSqr(grip);
            if(distance<cost){cost=distance;best=grip;}
        }
        return best;
    }
    /** A wet owner first reaches a nearby supported patch, then walks to the stern after settling. */
    public Vec3 reserveOwnBoardingSeat(YukkuriEntity actor,Vec3 actualFrom){
        return reserveOwnBoardingSeat(actor,actualFrom,at->true);
    }
    /** The departure hull may still occupy a locally valid landing; include its actual clearance before reserving. */
    public Vec3 reserveOwnBoardingSeat(YukkuriEntity actor,Vec3 actualFrom,java.util.function.Predicate<Vec3> reachable){
        if(level().isClientSide||!ownsPhysicalHat(actor)||actor.getVehicle()==this||!isAfloat()||unsteadyMotion()||actualFrom==null)return null;
        if(actor.isInWaterOrBubble()&&cargo.count()>0)cargo.jettison();
        var obstacles=takenExcept(actor.getUUID());double radius=radiusOf(actor);var body=HatVessel.bodySpace(actor,hullScale(),unit());
        Vec3 best=null;double cost=Double.POSITIVE_INFINITY;
        for(double x=-HatVessel.BRIM[0]+radius;x<=HatVessel.BRIM[0]-radius;x+=.25)
            for(double z=-HatVessel.BRIM[1]+radius;z<=HatVessel.BRIM[1]-radius;z+=.25){
                double y=HatVessel.footprint(mode(),x,z,radius);if(!Double.isFinite(y))continue;
                var at=new Vec3(x,y,z);if(!HatVessel.fits(mode(),at,radius,obstacles,body))continue;
                double distance=toWorld(at).distanceToSqr(actualFrom);if(distance<cost&&reachable.test(at)){cost=distance;best=at;}
            }
        if(best!=null){ownerServiceSeat=best;ownerSeatMove=null;boardingEdgeUntil=level().getGameTime()+80;syncSeats();}
        return best;
    }
    public void releaseOwnBoardingSeat(YukkuriEntity actor){
        if(level().isClientSide||!ownsPhysicalHat(actor))return;
        boardingEdgeUntil=0;
        if(actor.getVehicle()!=this){ownerServiceSeat=null;ownerSeatMove=null;}
        else moveOwnerSeat(actor,ownerSeat(actor),false);
        syncSeats();
    }
    /** The place a returning pupil takes in this hull: the berth kept for it while its own hat was away, else a close brim place. */
    public Vec3 returnSeat(YukkuriEntity child,boolean emergency){
        if(level().isClientSide||child==null||!young(child)||!isAfloat()||unsteadyMotion())return null;
        var berth=voyageBerth(child);
        if(berth!=null&&cargoSeatClear(child,berth)){
            if(!incoming.containsKey(child.getUUID()))incoming.put(child.getUUID(),new double[]{berth.x,berth.y,berth.z,radiusOf(child)});
            syncSeats();return berth;
        }
        return reserveBoardingSeat(child,child.position(),emergency);
    }
    /** Choose a real close landing, instead of reusing a far retained berth after jump limits tighten. */
    public Vec3 reserveBoardingSeat(YukkuriEntity child,Vec3 actualFrom,boolean emergency){
        if(level().isClientSide||cargo.handling()||landing()||child==null||actualFrom==null||!young(child)||!isAfloat()||unsteadyMotion()
                ||!incoming.containsKey(child.getUUID())&&youngAboard()+incoming.size()>=MAX_YOUNG)return null;
        if(emergency&&!requestEmergencyBerth(child))return null;
        var obstacles=takenExcept(child.getUUID());var parent=owner();
        if(parent!=null&&parent.getVehicle()==this)obstacles.add(rescueBody(parent,toLocal(parent.position())));
        var body=HatVessel.bodySpace(child,hullScale(),unit());double radius=radiusOf(child);Vec3 best=null;double cost=Double.POSITIVE_INFINITY;
        for(double x=-HatVessel.BRIM[0]+radius;x<=HatVessel.BRIM[0]-radius;x+=.25)
            for(double z=-HatVessel.BRIM[1]+radius;z<=HatVessel.BRIM[1]-radius;z+=.25){
                double y=HatVessel.footprint(mode(),x,z,radius);if(!Double.isFinite(y)||Math.abs(HatVessel.surface(mode(),x,z)-y)>1e-4
                        ||!cargoSeatClear(child,new Vec3(x,y,z)))continue;
                var at=new Vec3(x,y,z);if(!HatVessel.fits(mode(),at,radius,obstacles,body))continue;
                double distance=toWorld(at).distanceToSqr(actualFrom);if(distance<cost){cost=distance;best=at;}
            }
        if(best==null&&emergency)best=HatVessel.refugeBrimPlace(radius,obstacles,toLocal(actualFrom),true);
        if(best!=null){incoming.put(child.getUUID(),new double[]{best.x,best.y,best.z,radius});syncSeats();}
        return best;
    }
    /** Move to the closest usable departure patch along a checked supported walking route. */
    public boolean approachBoardingEdge(YukkuriEntity rider,Vec3 towardWorld){
        if(level().isClientSide||rider==null||towardWorld==null||rider.getVehicle()!=this||!protects(rider)||!isAfloat()
                ||unsteadyMotion()||transportingInHat(rider))return false;
        boardingEdgeUntil=level().getGameTime()+40;
        if(changingSeat(rider))return true;
        var obstacles=takenExcept(rider.getUUID());var captain=owner();boolean own=rider.getUUID().equals(ownerYukkuri());
        if(!own&&captain!=null)obstacles.add(rescueBody(captain,toLocal(captain.position())));
        var candidates=new java.util.ArrayList<Vec3>();double radius=radiusOf(rider);var body=HatVessel.bodySpace(rider,hullScale(),unit());
        for(double x=-HatVessel.BRIM[0]+radius;x<=HatVessel.BRIM[0]-radius;x+=.4)
            for(double z=-HatVessel.BRIM[1]+radius;z<=HatVessel.BRIM[1]-radius;z+=.4){
                double y=HatVessel.footprint(mode(),x,z,radius);if(!Double.isFinite(y))continue;
                var at=new Vec3(x,y,z);if(HatVessel.fits(mode(),at,radius,obstacles,body))candidates.add(at);
            }
        candidates.sort(java.util.Comparator.comparingDouble(at->toWorld(at).distanceToSqr(towardWorld)));
        for(var at:candidates.stream().limit(24).toList()){
            boolean moved=own?moveOwnerSeat(rider,at,false):moveYoungSeat(rider,new double[]{at.x,at.y,at.z,radius},false);
            if(moved){syncSeats();return true;}
        }
        return false;
    }
    /**
     * A flat, fully supported brim place for this young nearest a world point (not yet reserved: see holdIncoming). Used to carry an
     * infant (which cannot walk up the steps itself) to the near rim before it is lifted over to the bank. Hull-local, or null.
     */
    public Vec3 reserveBrimSeatToward(YukkuriEntity child,Vec3 towardWorld){
        var seats=brimSeatsToward(child,towardWorld,1);return seats.isEmpty()?null:seats.getFirst();
    }
    /** As reserveBrimSeatToward, the {@code limit} nearest free flat brim places (hull-local), nearest first; read-only. */
    public java.util.List<Vec3> brimSeatsToward(YukkuriEntity child,Vec3 towardWorld,int limit){
        if(level().isClientSide||child==null||towardWorld==null||!young(child)||!isAfloat()||unsteadyMotion()||!hasPassenger(child))return java.util.List.of();
        var obstacles=takenExcept(child.getUUID());var parent=owner();
        if(parent!=null&&parent.getVehicle()==this)obstacles.add(rescueBody(parent,toLocal(parent.position())));
        double radius=radiusOf(child);var body=HatVessel.bodySpace(child,hullScale(),unit());
        var found=new java.util.ArrayList<Vec3>();
        for(double x=-HatVessel.BRIM[0]+radius;x<=HatVessel.BRIM[0]-radius;x+=.25)
            for(double z=-HatVessel.BRIM[1]+radius;z<=HatVessel.BRIM[1]-radius;z+=.25){
                if(!HatVessel.supported(HatVessel.Mode.FLOAT,x,z,radius+.1,HatVessel.top(HatVessel.Mode.FLOAT)))continue;
                var at=new Vec3(x,HatVessel.top(HatVessel.Mode.FLOAT),z);
                if(HatVessel.fits(mode(),at,radius,obstacles,body))found.add(at);
            }
        found.sort(java.util.Comparator.comparingDouble(at->toWorld(at).distanceToSqr(towardWorld)));
        return found.size()>limit?java.util.List.copyOf(found.subList(0,limit)):found;
    }
    /** Keep a place for a young whose lift is under way (it is no longer a rider, but must find its place again). */
    public boolean holdIncoming(YukkuriEntity child,Vec3 seatLocal){
        if(level().isClientSide||child==null||seatLocal==null||!young(child)||!isAfloat())return false;
        if(!cargoSeatClear(child,seatLocal)||cargo.handling())return false;
        var obstacles=takenExcept(child.getUUID());var parent=owner();
        if(parent!=null&&parent.getVehicle()==this)obstacles.add(rescueBody(parent,toLocal(parent.position())));
        if(!HatVessel.fits(mode(),seatLocal,radiusOf(child),obstacles,HatVessel.bodySpace(child,hullScale(),unit())))return false;
        incoming.put(child.getUUID(),new double[]{seatLocal.x,seatLocal.y,seatLocal.z,radiusOf(child)});syncSeats();return true;
    }
    /** A swimming destination outside the true tilted brim; it never moves or boards its caller. */
    public Vec3 ownGripApproach(YukkuriEntity actor){ return capsizeMotion.ownGripApproach(actor); }
    /** Empty, already-spilled capsizes can be physically righted without replacing their token or cargo. */
    public boolean setSelfRighting(YukkuriEntity actor,float progress,Vec3 localGrip){ return capsizeMotion.setSelfRighting(actor, progress, localGrip); }
    public boolean finishSelfRighting(YukkuriEntity actor){ return capsizeMotion.finishSelfRighting(actor); }
    void tickSelfRighting(){ capsizeMotion.tickSelfRighting(); }
    public record SelfRescueDeployment(Vec3 position,float yaw,SelfRescueHatGeometry.Plan path){}
    /** Plan before the worn hat moves, so rendering and the eventual real vessel share one endpoint. */
    public static SelfRescueDeployment planSelfRescueDeployment(YukkuriEntity actor){ return HatBoatCapsize.planSelfRescueDeployment(actor); }
    /** The spot in front of the face must be calm open water the hull can float in; nothing is reached for, so no braid length applies. */
    public static boolean selfRescueDeploymentReachable(YukkuriEntity actor,SelfRescueDeployment plan){ return HatBoatCapsize.selfRescueDeploymentReachable(actor, plan); }
    /** Revalidate exactly the planned endpoint. Failure leaves the one original hat on its owner. */
    public static HatBoatEntity deployForSelfRescue(YukkuriEntity actor,SelfRescueDeployment plan){ return HatBoatCapsize.deployForSelfRescue(actor, plan); }
    /** Custody moves to the real vessel once the hat has been shaken clear of the head. */
    public static HatBoatEntity deployForSelfRescue(YukkuriEntity actor,SelfRescueDeployment plan,SelfRescueHatGeometry.Pose heldPose,float elapsed){ return HatBoatCapsize.deployForSelfRescue(actor, plan, heldPose, elapsed); }
    /** The shaken-off hat follows the same world-space prop origin as the worn-hat geometry. */
    public boolean setSelfRescueCarryPose(YukkuriEntity actor,SelfRescueHatGeometry.Pose pose,float elapsed){ return capsizeMotion.setSelfRescueCarryPose(actor, pose, elapsed); }
    public void finishSelfRescueCarry(YukkuriEntity actor){ capsizeMotion.finishSelfRescueCarry(actor); }
    /** A failed grip leaves the same physical hat at its actual pose and spills only via the normal capsize lifecycle. */
    public void releaseSelfRescueCarry(){ capsizeMotion.releaseSelfRescueCarry(); }
    public void capsize() { capsizeMotion.capsize(); }
    void startCapsize(int side){ capsizeMotion.startCapsize(side); }
    /** Same yaw * roll * pitch transform as the client, used for each real release point. */
    Vec3 tiltedWorld(Vec3 local){ return capsizeMotion.tiltedWorld(local); }
    void tickCapsize(){ capsizeMotion.tickCapsize(); }
    void freezeSeatsForCapsize() {
        // Freeze the actual current poses, including a child being carried or partway through a hop.
        for(var p:getPassengers()){
            var at=toLocal(p.position());
            if(p.getUUID().equals(ownerYukkuri()))ownerServiceSeat=at;
            else seats.put(p.getUUID(),new double[]{at.x,at.y,at.z,radiusOf(p)});
            if(p instanceof YukkuriEntity y){y.getNavigation().stop();y.showReaction(y.family().adult()?"surprise":"teary",y.family().adult()?"wait_family":"afraid",60);}
        }
        seatMoves.clear();ownerSeatMove=null;carryTrip=null;recoverCarry=false;
        serviceSeats.clear();returningSeats.clear();ownerToiletSeat=false;leaving.clear();syncSeats();
    }

    YukkuriEntity owner() {
        return ownerYukkuri() != null && level() instanceof ServerLevel server && server.getEntity(ownerYukkuri()) instanceof YukkuriEntity y ? y : null;
    }
    /** Start the shore landing of the whole crew; {@code hint} (may be null) only says which bank is preferred. */
    public boolean rescue(BlockPos hint, UUID rescuer) {
        var y = owner();
        if (level().isClientSide || capsizing() || y == null || !y.isAlive() || state() == State.RESCUED || state() == State.REMOVED
                || y.distanceToSqr(this)>36 || hint!=null&&distanceToSqr(Vec3.atBottomCenterOf(hint))>100) return false;
        if (hasIncoming()) return false;
        return landing.begin(y,hint,rescuer);
    }
    public boolean landing(){return landing.active();}
    /** A kinematic drive step owns the hull pose until this game tick (see {@link #driveToward}). */
    private long driveUntil=Long.MIN_VALUE;
    public boolean driving(){return level().getGameTime()<=driveUntil;}
    public enum Drive { MOVING, ARRIVED, BLOCKED }
    /**
     * Ease the floating hull toward a pose by bounded steps (never a teleport): the final approach of a docking, a hand-off
     * between two hulls or a rescue. Block collision still applies. Calling it every tick with the pose already reached
     * simply keeps the hull still, so callers use it for the whole time the hull has to stay put.
     */
    public Drive driveToward(Vec3 target,float yaw,double maxStep,float maxTurn){
        if(level().isClientSide||!isAfloat()&&state()!=State.BEACHED||unsteadyMotion()||capsizing())return Drive.BLOCKED;
        stopPaddling();heldTicks=Math.max(heldTicks,cargo.paddle()?5:0);driveUntil=level().getGameTime()+2;
        float diff=net.minecraft.util.Mth.wrapDegrees(yaw-getYRot());
        if(Math.abs(diff)>.05f)setYRot(getYRot()+net.minecraft.util.Mth.clamp(diff,-maxTurn,maxTurn));
        var gap=new Vec3(target.x-getX(),0,target.z-getZ());double distance=gap.horizontalDistance();
        boolean moved=distance<=1e-4;
        if(!moved){
            var before=position();
            move(MoverType.SELF,gap.scale(Math.min(distance,maxStep)/distance));
            moved=before.distanceToSqr(position())>1e-7;
        }
        var captain=owner();
        // A captain whose braid is lifting or rescuing a child keeps the heading that braid was planned for.
        // Afterwards she turns back with the hull at a body's pace rather than snapping round.
        if(captain!=null&&captain.getVehicle()==this&&!captain.family().cradlePickup().active()&&!captain.family().braidRescueActive()){
            float heading=net.minecraft.util.Mth.approachDegrees(captain.getYRot(),getYRot(),15f);
            captain.setYRot(heading);captain.yBodyRot=heading;captain.setYHeadRot(heading);
        }
        double remaining=new Vec3(target.x-getX(),0,target.z-getZ()).horizontalDistance();
        boolean turned=Math.abs(net.minecraft.util.Mth.wrapDegrees(yaw-getYRot()))<=.6f;
        if(remaining<=.04&&turned)return Drive.ARRIVED;
        return moved||!turned?Drive.MOVING:Drive.BLOCKED;
    }
    /** Return the one accessory only after everyone has left and the captain is on dry land at the hat; the hat then leaves the water. */
    boolean finishLanding(YukkuriEntity owner,UUID rescuer){
        if(isVehicle()||hasIncoming()||owner.isPassenger()||!owner.isAlive()||!owner.getUUID().equals(ownerYukkuri())||state()!=State.AFLOAT
                ||!owner.life().returnAccessory(ownerYukkuri(),accessoryToken,rescuer))return false;
        crew.clear();cargo.unload(owner,accessoryToken);owner.aquaticLife().landed();owner.subspecies().boatId(null);
        accessoryToken=null;state(State.RESCUED);stopPaddling();pullTicks=0;return true;
    }
    /** Reserve all dry places before returning the token or moving anybody. */
    java.util.Map<UUID,Vec3> landingSpots(BlockPos stand, Entity owner) {
        if (!(level() instanceof ServerLevel server)) return null;
        // Waking may grow a juvenile. Reserve using the resulting dimensions, not yesterday's smaller body.
        if (owner instanceof YukkuriEntity y) y.life().wakeInHat();
        var roster=landingRoster();
        for (var id : roster) if (server.getEntity(id) instanceof YukkuriEntity child && child.isAlive() && child.distanceToSqr(this)<=64)
            child.life().wakeInHat();
        var result=new java.util.LinkedHashMap<UUID,Vec3>();
        var used=new java.util.ArrayList<net.minecraft.world.phys.AABB>();
        used.add(owner.getBoundingBox().move(Vec3.atBottomCenterOf(stand).subtract(owner.position())).inflate(.04));
        for (var id : roster) {
            if (!(server.getEntity(id) instanceof YukkuriEntity child) || !child.isAlive() || child.distanceToSqr(this)>64
                    || child.isPassenger() && child.getVehicle()!=this) continue;
            var candidates=new java.util.ArrayList<BlockPos>();
            for(var spot:BlockPos.betweenClosed(stand.offset(-2,0,-2),stand.offset(2,1,2)))candidates.add(spot.immutable());
            candidates.sort(java.util.Comparator.comparingDouble(spot->distanceToSqr(Vec3.atBottomCenterOf(spot))));
            for (var spot : candidates) {
                if (!level().hasChunkAt(spot) || !level().getFluidState(spot).isEmpty()
                        || !level().getBlockState(spot.below()).isFaceSturdy(level(),spot.below(),net.minecraft.core.Direction.UP)) continue;
                var at=Vec3.atBottomCenterOf(spot); var box=child.getBoundingBox().move(at.subtract(child.position()));
                if (used.stream().anyMatch(box::intersects) || level().containsAnyLiquid(box) || !level().noCollision(child,box)) continue;
                result.put(id,at); used.add(box.inflate(.04)); break;
            }
            if (!result.containsKey(id)) return null;
        }
        return result;
    }
    /** Everyone actually aboard besides the captain, in boarding order. The remembered crew alone can miss riders and list ones long gone. */
    private java.util.Set<UUID> landingRoster() {
        var roster=new java.util.LinkedHashSet<UUID>();
        for (var p : getPassengers()) if (p instanceof YukkuriEntity y && !y.getUUID().equals(ownerYukkuri())) roster.add(y.getUUID());
        return roster;
    }
    /** Bring the young ashore with their mother, each on its own dry spot around the stand. */
    void landCrew(java.util.Map<UUID,Vec3> spots) {
        if (!(level() instanceof ServerLevel server)) return;
        for (var id : java.util.List.copyOf(spots.keySet())) {
            if (!(server.getEntity(id) instanceof YukkuriEntity child) || !child.isAlive() || child.distanceToSqr(this) > 64
                    || child.isPassenger() && child.getVehicle()!=this) continue;
            var at = spots.get(id); if (at == null) continue;
            child.life().wakeInHat(); child.stopRiding(); child.teleportTo(at.x, at.y, at.z); child.setDeltaMovement(Vec3.ZERO);
        }
        crew.clear();
    }
    @Override public InteractionResult interact(Player player, InteractionHand hand) {
        if (hand != InteractionHand.MAIN_HAND || player.distanceToSqr(this) > 36) return InteractionResult.PASS;
        var held = player.getItemInHand(hand);
        if (!held.isEmpty()) {
            if (!isAfloat()) return InteractionResult.PASS;
            if (level().isClientSide) return InteractionResult.SUCCESS;
            var y = owner();
            if (cargo.givePaddle(held, player.getAbilities().instabuild)
                    || y != null && y.life().acceptsFood(held) && cargo.store(held, player.isShiftKeyDown() ? 8 : 1, player.getAbilities().instabuild) > 0)
                return InteractionResult.CONSUME;
            // Anything the hat does not take (a tool, a block) still shoves the rim: the shake never needs an empty hand.
            if (!player.isShiftKeyDown()) { shake(player, 1); return InteractionResult.CONSUME; }
            return InteractionResult.PASS;
        }
        if (level().isClientSide) return InteractionResult.SUCCESS;
        if (catches()) return recoverOrphans(player) ? InteractionResult.CONSUME : InteractionResult.PASS;
        if(isAfloat()&&!player.isShiftKeyDown()){shake(player,1);return InteractionResult.CONSUME;}
        if(capsizing())return InteractionResult.CONSUME;
        if (rescue(player.blockPosition(), player.getUUID())) return InteractionResult.CONSUME;
        if (recoverOrphans(player)) return InteractionResult.CONSUME;
        pullTarget = player.position(); pullTicks = 100; return InteractionResult.CONSUME;
    }
    /** A confirmed dead owner leaves one recoverable hat. An unloaded/missing owner is never presumed dead. */
    private boolean recoverOrphans(Player player) {
        if (!(level() instanceof ServerLevel server) || accessoryToken == null || ownerYukkuri() == null
                || capsizing() || state() == State.RESCUED || state() == State.REMOVED || hasIncoming()) return false;
        var person=io.github.longshou.mcyukkuri.family.FamilyData.get(server).person(ownerYukkuri());
        if (person==null || person.status!=io.github.longshou.mcyukkuri.family.FamilyData.Status.DEAD) return false;
        var stand=player.blockPosition();
        if (!level().getFluidState(stand).isEmpty() || player.isInWaterOrBubble()
                || !level().getBlockState(stand.below()).isFaceSturdy(level(),stand.below(),net.minecraft.core.Direction.UP)) return false;
        var spots=landingSpots(stand,player); if (spots==null) return false;
        var hat=new net.minecraft.world.item.ItemStack(io.github.longshou.mcyukkuri.registry.ModItems.HAT.get());
        net.minecraft.world.item.component.CustomData.update(net.minecraft.core.component.DataComponents.CUSTOM_DATA,hat,tag->{
            tag.putUUID("Owner",ownerYukkuri());tag.putUUID("Token",accessoryToken);tag.putString("Variant","marisa");
            tag.putString("SourceModel","marisa");tag.putFloat("SourceScale",hullScale());
        });
        accessoryToken=null; landCrew(spots); cargo.spillAt(player.position()); state(State.RESCUED); stopPaddling();
        if (!player.getInventory().add(hat)) player.drop(hat,false);
        return true;
    }
    @Override public boolean hurt(net.minecraft.world.damagesource.DamageSource source, float amount) {
        if (isInvulnerableTo(source) || level().isClientSide || amount <= 0 || catches()) return false;
        var attacker=source.getDirectEntity();
        // A bare-handed punch or stick poke shakes the hat harder than a hand shove; only a real blow turns it over.
        if(attacker instanceof Player puncher&&amount<4&&isAfloat()){shake(puncher,2);return true;}
        if(attacker!=null)startCapsize(toLocal(attacker.position()).x>=0?-1:1);else capsize();
        markHurt(); return true;
    }
    @Override public void tick() {
        super.tick();
        if (level().isClientSide) {
            if (lerpSteps > 0) { setPos(getX()+(lerpX-getX())/lerpSteps,getY()+(lerpY-getY())/lerpSteps,getZ()+(lerpZ-getZ())/lerpSteps); lerpSteps--; }
            return;
        }
        if (state() == State.RESCUED) { if (tickCount % 20 == 0) discard(); return; }
        tickDip();
        if(hullMotion.agitation>0&&tickCount%40==0)hullMotion.agitation--;
        tickDelayedDrops();
        if(selfRescueHeld()){
            var holder=owner();var held=entityData.get(MOTION);
            if(holder!=null&&holder.isAlive()&&(holder.aquaticLife().selfRescue().phase()==AquaticSelfRescue.Phase.REMOVE_HAT
                    ||holder.aquaticLife().selfRescue().phase()==AquaticSelfRescue.Phase.DEPLOY_HAT)
                    &&level().getGameTime()-held.getLong("HeldTouched")<=2){stopPaddling();setDeltaMovement(Vec3.ZERO);return;}
            releaseSelfRescueCarry();
        }
        tickCapsize();
        tickSelfRighting();
        if(selfRighting()&&level().getGameTime()-entityData.get(MOTION).getLong("RightTouched")<=1){
            // The continuously held rim is the physical pivot. Buoyancy resumes as soon as grip is released.
            setDeltaMovement(Vec3.ZERO);stopPaddling();return;
        }
        if (heldTicks > 0) heldTicks--;
        if (tickCount % 20 == 0 && level() instanceof ServerLevel server)  // a place kept for a young no longer coming
            incoming.keySet().removeIf(id -> !(server.getEntity(id) instanceof YukkuriEntity y) || !y.isAlive() || y.isPassenger()
                    || !y.family().flying()&&!y.family().braidRescueActive()&&!y.family().selfClimbActive() || y.distanceToSqr(this) > 64);
        if (tickCount>60 && tickCount%20==0 && departure.voyageYoung!=null && level() instanceof ServerLevel server
                && (!(server.getEntity(departure.voyageYoung) instanceof YukkuriEntity y) || !y.isAlive() || !y.aquaticLife().firstVoyage().keepsBerth(this))) releaseVoyageBerth(departure.voyageYoung);
        if(state()!=State.CAPSIZED){
            cargo.tick();
            tickCarry();
            landing.tick();
            crowding.tick();
            if (tickCount % 10 == 0&&!crowding.contesting()&&!landing()) tendSeats();
            tendLeaving();
        }
        if (catches()) {
            if (isCradle()) {  // resting on the ground
                var fall = new Vec3(0, Math.max(-.2, getDeltaMovement().y - .04), 0); setDeltaMovement(fall); move(MoverType.SELF, fall);
            } else {  // floated at the bank: rides the surface where it was set, the braid keeping it from drifting
                var at = BlockPos.containing(getX(), getY()-.12, getZ()); var fluid = level().getFluidState(at);
                double lift = fluid.is(FluidTags.WATER) ? Math.clamp((at.getY()+fluid.getHeight(level(), at)+.025-getY())*.35, -.12, .12) : -.04;
                var to = mooring == null ? Vec3.ZERO : mooring.subtract(position()).multiply(.2, 0, .2);
                var motion = new Vec3(to.x, lift, to.z); setDeltaMovement(motion); move(MoverType.SELF, motion);
            }
            for (var p : getPassengers()) if (p instanceof YukkuriEntity rider) { rider.getNavigation().stop(); rider.fallDistance = 0; }
            // An owner who is gone for good leaves her hat where it lies; one nearby comes back for it.
            var y = owner();
            if (tickCount % 20 == 0 && y != null && y.isAlive() && !y.family().keepsCradle() && y.distanceToSqr(this) <= 64) y.family().fetchHat(this);
            return;
        }
        var pos = BlockPos.containing(getX(),getY()-.12,getZ());
        if (!level().hasChunkAt(pos)) return;
        var fluid = level().getFluidState(pos);
        boolean water = fluid.is(FluidTags.WATER);
        var motion = getDeltaMovement();
        if (water) {
            if (state() == State.BEACHED) state(State.AFLOAT);
            double surface = pos.getY()+fluid.getHeight(level(),pos);
            var flow = fluid.getFlow(level(), pos);
            float current=(float)(Math.round(Math.min(1,flow.horizontalDistance())*16)/16.0);
            if(entityData.get(CURRENT)!=current)entityData.set(CURRENT,current);
            double buoyancy = Math.clamp((surface + .025 - getY())*.35,-.12,.12);
            motion = new Vec3(motion.x*.88+flow.x*.016,buoyancy,motion.z*.88+flow.z*.016);
            if (pullTicks>0 && pullTarget!=null) {
                var toward=pullTarget.subtract(position()).multiply(1,0,1);
                if (toward.lengthSqr()>.1) motion=motion.add(toward.normalize().scale(.012));
                pullTicks--;
            }
            var captain = owner();
            if (captain == null || !captain.isAlive() || captain.getVehicle() != this || captain.isNoAi()
                    || captain.isCommandPlaying() || captain.operations().active()
                    || captain.family().braidRescueActive() || captain.family().flying() || crowding.contesting() || landing()
                    || captain.isPanicking()&&!captain.aquaticLife().familyLife().alarm().active()
                    || captain.life().isSleeping() || !cargo.paddle()) stopPaddling();
            if (isAfloat() && !route.isEmpty()) {
                var next = route.peekFirst();
                if (position().subtract(next).horizontalDistanceSqr() < (route.size()==1 ? finalWaypointToleranceSquared : .12)) route.removeFirst();
                if (route.isEmpty()) stopPaddling();
                else {
                    next = route.peekFirst(); var step = BlockPos.containing(next.x, next.y-.12, next.z);
                    if (!HatBoatRoute.calmSurface(level(), step) || !level().noCollision(this, getBoundingBox().move(next.subtract(position())))) stopPaddling();
                    else {
                        var forward = next.subtract(position()).multiply(1,0,1).normalize();
                        // The recovery half of the stroke supplies no thrust; existing momentum and current remain.
                        double stroke = .022*paddlePower(0);
                        motion = motion.add(forward.scale(stroke));
                        double speed = motion.horizontalDistance(); if (speed > .08) motion = new Vec3(motion.x*.08/speed, motion.y, motion.z*.08/speed);
                        if (route.size() == 1) {
                            // Ease into the last waypoint: unchecked momentum coasts roughly half a block past a tight docking tolerance.
                            double remaining = next.subtract(position()).horizontalDistance(), cap = Math.max(.004, remaining*.25), now = motion.horizontalDistance();
                            if (now > cap) motion = new Vec3(motion.x*cap/now, motion.y, motion.z*cap/now);
                        }
                        float heading = (float)(Math.toDegrees(Math.atan2(forward.z,forward.x))-90);
                        float turn = net.minecraft.util.Mth.clamp(net.minecraft.util.Mth.wrapDegrees(heading-getYRot()), -4, 4);
                        setYRot(getYRot()+turn); captain.setYRot(getYRot()); captain.yBodyRot=getYRot(); captain.setYHeadRot(getYRot());
                        if (tickCount%80 == 0) captain.life().spendEnergy(1);
                        if (tickCount%8 == 0 && level() instanceof ServerLevel server)
                            server.sendParticles(net.minecraft.core.particles.ParticleTypes.SPLASH,getX(),getY(),getZ(),2,.35,.01,.35,.01);
                    }
                }
            }
            if (heldTicks > 0 && isAfloat()) motion = new Vec3(motion.x*.2, motion.y, motion.z*.2);
            if (driving()) motion = new Vec3(0, motion.y, 0);
            if (state()!=State.CAPSIZED && (level().getFluidState(pos.above()).is(FluidTags.WATER) || flow.y<-.1)) {
                if (++flooding>=60) capsize();
            } else if (state()!=State.CAPSIZED) flooding=Math.max(0,flooding-1);
        } else {
            entityData.set(CURRENT,0f);
            motion = new Vec3(motion.x*.6,Math.max(-.2,motion.y-.04),motion.z*.6);
            if (onGround() && state()!=State.CAPSIZED) state(State.BEACHED);
        }
        setDeltaMovement(motion); move(MoverType.SELF,motion);
        if(entityData.get(MOTION).contains("LooseAt")&&motionAge(entityData.get(MOTION),"LooseAt",0)<14){
            var m=entityData.get(MOTION).copy();point(m,"PreviousCentre",m.contains("HeldCentreX")?point(m,"HeldCentre"):position());point(m,"HeldCentre",position());entityData.set(MOTION,m);
        }
        for (var p : getPassengers()) if (p instanceof YukkuriEntity rider) { rider.getNavigation().stop(); rider.fallDistance = 0; }
        var y = owner();
        if (y!=null && y.getVehicle()==this) {
            entityData.set(SCALE,HatVessel.hatScale(y));
            // Looking at a child must not rotate the floor beneath that same child every tick.
            // A held boat also owns its heading while its stern is being aligned with a shore plant.
            boolean interiorBusy=cargo.handling() || carryTrip!=null || rescueOwnerHeld || !serviceSeats.isEmpty() || !returningSeats.isEmpty()
                    || ownerToiletSeat || changingOwnerSeat() || y.aquaticLife().care().active()
                    || youngPassengers().stream().anyMatch(this::changingSeat);
            if (!paddling() && !unsteadyMotion() && heldTicks==0 && departure.voyageYoung==null && !interiorBusy && !driving()) setYRot(y.yBodyRot);
            if (pullTicks > 0 && pullTarget != null && position().subtract(pullTarget).horizontalDistanceSqr() < 3.25)
                rescue(BlockPos.containing(pullTarget), null);
            if (state()==State.BEACHED && tickCount%20==0) {
                for(var dry:BlockPos.betweenClosed(blockPosition().offset(-1,0,-1),blockPosition().offset(1,1,1))) if(rescue(dry,null)) break;
            }
        }
    }
    @Override public void lerpTo(double x,double y,double z,float yaw,float pitch,int steps) {
        lerpX=x;lerpY=y;lerpZ=z;lerpSteps=Math.max(1,steps);setYRot(yaw);setXRot(pitch);
    }
    @Override protected void addAdditionalSaveData(CompoundTag tag) {
        if(ownerYukkuri()!=null)tag.putUUID("OwnerYukkuri",ownerYukkuri());
        if(accessoryToken!=null)tag.putUUID("AccessoryToken",accessoryToken);
        tag.putString("State",state().name());tag.putInt("Flooding",flooding);tag.putFloat("HullScale",hullScale());
        var motion=entityData.get(MOTION);tag.putInt("MotionSide",motion.getInt("Side"));
        if(selfRescueHeld()){tag.putBoolean("SavedSelfHeld",true);tag.putFloat("SavedHeldPitch",motionPitch(1));tag.putFloat("SavedHeldSquash",hullSquash(1));}
        if(motion.contains("LooseAt")){tag.putInt("LooseAge",(int)Math.min(14,motionAge(motion,"LooseAt",0)));tag.putFloat("LoosePitch",motion.getFloat("LoosePitch"));tag.putFloat("LooseSquash",motion.getFloat("LooseSquash"));}
        if(deploying())tag.putInt("DeployAge",(int)motionAge(motion,"DeployAt",0));
        if(selfRighting())tag.putFloat("SelfRightProgress",motion.getFloat("RightProgress"));
        if(rocking()){
            tag.putInt("RockAge",(int)motionAge(motion,"RockAt",0));tag.putFloat("RockAmplitude",motion.getFloat("Amplitude"));
        }
        if(state()==State.CAPSIZED&&motion.contains("CapAt")){
            tag.putInt("CapsizeAge",Math.min(CAPSIZE_TICKS,(int)motionAge(motion,"CapAt",0)));
            tag.putBoolean("CapsizeReleased",capsizeMotion.capsizeReleased);
            if(!capsizeMotion.capsizeReleased){
                var frozen=new net.minecraft.nbt.ListTag();
                for(var p:getPassengers()){
                    var saved=new CompoundTag();saved.putUUID("Id",p.getUUID());point(saved,"Seat",toLocal(p.position()));
                    saved.putDouble("Radius",radiusOf(p));frozen.add(saved);
                }
                tag.put("CapsizeSeats",frozen);
            }
        }
        tag.putString("Role",role.name());if(mooring!=null){tag.putDouble("MooringX",mooring.x);tag.putDouble("MooringZ",mooring.z);}
        tag.put("Cargo", cargo.save());
        tag.put("Crowding",crowding.save());
        tag.put("ShoreLanding",landing.save());
        var residentSeats=new net.minecraft.nbt.ListTag();
        for(var p:youngPassengers()){
            var saved=new CompoundTag();saved.putUUID("Id",p.getUUID());point(saved,"Seat",toLocal(p.position()));
            saved.putDouble("Radius",radiusOf(p));residentSeats.add(saved);
        }
        tag.put("ResidentSeats",residentSeats);
        if (carryTrip!=null) {
            tag.put("CarryTrip",saveCarry(carryTrip));
            var places=new net.minecraft.nbt.ListTag();
            for(var passenger:youngPassengers()) {
                Vec3 at=toLocal(passenger.position());var saved=new CompoundTag();saved.putUUID("Id",passenger.getUUID());
                point(saved,"Seat",at);saved.putDouble("Radius",radiusOf(passenger));places.add(saved);
            }
            tag.put("CarryPassengerSeats",places);
        }
        if(departure.voyageYoung!=null&&departure.voyageBerth!=null){
            tag.putUUID("VoyageYoung",departure.voyageYoung);var berth=new CompoundTag();
            berth.putDouble("X",departure.voyageBerth[0]);berth.putDouble("Y",departure.voyageBerth[1]);berth.putDouble("Z",departure.voyageBerth[2]);berth.putDouble("R",departure.voyageBerth[3]);tag.put("VoyageBerth",berth);
        }
        var young=new net.minecraft.nbt.ListTag();for(var id:crew)young.add(net.minecraft.nbt.NbtUtils.createUUID(id));tag.put("Crew",young);
    }
    @Override protected void readAdditionalSaveData(CompoundTag tag) {
        serviceSeats.clear(); returningSeats.clear(); returnRetries.clear(); seatMoves.clear(); seats.clear(); incoming.clear();
        carryTrip = null; recoverCarry=false;
        rescueOwnerHeld=false;
        ownerServiceSeat = null; ownerSeatMove = null; ownerToiletSeat = false; seatRegion = "";
        cramped.clear();crowding.load(tag.getCompound("Crowding"));
        landing.load(tag.getCompound("ShoreLanding"));
        for(var raw:tag.getList("ResidentSeats",net.minecraft.nbt.Tag.TAG_COMPOUND)){
            var saved=(CompoundTag)raw;if(!saved.hasUUID("Id"))continue;
            var at=point(saved,"Seat");seats.put(saved.getUUID("Id"),new double[]{at.x,at.y,at.z,saved.getDouble("Radius")});
        }
        entityData.set(OWNER,tag.hasUUID("OwnerYukkuri")?Optional.of(tag.getUUID("OwnerYukkuri")):Optional.empty());
        accessoryToken=tag.hasUUID("AccessoryToken")?tag.getUUID("AccessoryToken"):null;
        try{state(State.valueOf(tag.getString("State")));}catch(IllegalArgumentException invalid){state(State.BEACHED);}
        flooding=Math.clamp(tag.getInt("Flooding"),0,100);entityData.set(SCALE,Math.clamp(tag.getFloat("HullScale"),.1f,2.5f));
        role="SELF".equals(tag.getString("Role"))?HatVessel.Role.SELF:HatVessel.Role.HELPER;
        cargo.load(tag.getCompound("Cargo")); syncCargo(cargo.save());
        var motion=new CompoundTag();motion.putInt("Side",tag.getInt("MotionSide")<0?-1:1);
        capsizeMotion.capsizeReleased=state()==State.CAPSIZED;
        if(state()==State.CAPSIZED&&tag.contains("CapsizeAge")){
            int age=Math.clamp(tag.getInt("CapsizeAge"),0,CAPSIZE_TICKS);
            motion.putLong("CapAt",level().getGameTime()-age);capsizeMotion.capsizeReleased=tag.getBoolean("CapsizeReleased");
            if(!capsizeMotion.capsizeReleased)for(var raw:tag.getList("CapsizeSeats",net.minecraft.nbt.Tag.TAG_COMPOUND)){
                var saved=(CompoundTag)raw;if(!saved.hasUUID("Id"))continue;
                var at=point(saved,"Seat");
                if(saved.getUUID("Id").equals(ownerYukkuri()))ownerServiceSeat=at;
                else seats.put(saved.getUUID("Id"),new double[]{at.x,at.y,at.z,saved.getDouble("Radius")});
            }
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
            state(State.CAPSIZED);capsizeMotion.capsizeReleased=false;motion.putLong("CapAt",level().getGameTime());motion.putLong("LooseAt",level().getGameTime());
            motion.putFloat("LoosePitch",tag.getFloat("SavedHeldPitch"));motion.putFloat("LooseSquash",tag.getFloat("SavedHeldSquash"));
        }else if(tag.contains("LooseAge")){
            motion.putLong("LooseAt",level().getGameTime()-Math.clamp(tag.getInt("LooseAge"),0,14));
            motion.putFloat("LoosePitch",tag.getFloat("LoosePitch"));motion.putFloat("LooseSquash",tag.getFloat("LooseSquash"));
        }
        entityData.set(MOTION,motion);syncSeats();
        if (tag.contains("CarryTrip")) {
            var c=tag.getCompound("CarryTrip");
            if (c.hasUUID("Child") && c.hasUUID("Holder") && c.getUUID("Holder").equals(ownerYukkuri())) {
                Vec3 original=point(c,"From"), now=point(c,"ChildNow"), ownerNow=point(c,"OwnerNow");
                int phase=Math.clamp(c.getInt("Phase"),-2,5);
                Vec3 offset=phase==3?now.subtract(ownerNow):point(c,"Offset");
                carryTrip=new CarryTrip(c.getUUID("Child"),phase==1?now:original,point(c,"To"),point(c,"Pickup"),point(c,"OwnerTo"),offset,level().getGameTime());
                carryTrip.lastAnchor=now;
                if(c.contains("FacingX"))carryTrip.facing=point(c,"Facing");
                carryTrip.phase=phase; recoverCarry=phase==-2 || phase==0 || phase==2;
                if(phase==-1||phase==5)carryTrip.at=level().getGameTime()-Math.clamp(c.getLong("Elapsed"),0,12);
                ownerServiceSeat=ownerNow;
                for(var raw:tag.getList("CarryPassengerSeats",net.minecraft.nbt.Tag.TAG_COMPOUND)) {
                    var saved=(CompoundTag)raw;
                    if(saved.hasUUID("Id")) {
                        Vec3 seat=point(saved,"Seat");
                        seats.put(saved.getUUID("Id"),new double[]{seat.x,seat.y,seat.z,saved.getDouble("Radius")});
                    }
                }
                // Loaded interactions earn no care rewards. Safely finish the owned transport, then restore normal seating.
                Vec3 returnTo=c.contains("ReturnX")?point(c,"Return"):original;
                returningSeats.put(carryTrip.child,new ServiceSeat(new double[]{returnTo.x,returnTo.y,returnTo.z,c.getDouble("ReturnRadius")},c.getString("ReturnRegion"),
                        new double[]{carryTrip.destination.x,carryTrip.destination.y,carryTrip.destination.z,0},null,false,false));
                syncSeats();
            }
        }
        departure.voyageYoung=tag.hasUUID("VoyageYoung")?tag.getUUID("VoyageYoung"):null;departure.voyageBerth=null;
        if(departure.voyageYoung!=null&&tag.contains("VoyageBerth")){
            var berth=tag.getCompound("VoyageBerth");departure.voyageBerth=new double[]{berth.getDouble("X"),berth.getDouble("Y"),berth.getDouble("Z"),berth.getDouble("R")};
        }else departure.voyageYoung=null;
        mooring=tag.contains("MooringX")?new Vec3(tag.getDouble("MooringX"),0,tag.getDouble("MooringZ")):null;
        crew.clear();for(var raw:tag.getList("Crew",net.minecraft.nbt.Tag.TAG_INT_ARRAY))crew.add(net.minecraft.nbt.NbtUtils.loadUUID(raw));
    }
}
