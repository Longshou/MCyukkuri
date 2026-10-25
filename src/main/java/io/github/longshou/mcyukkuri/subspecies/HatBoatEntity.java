package io.github.longshou.mcyukkuri.subspecies;

import io.github.longshou.mcyukkuri.subspecies.boat.HullFootprint;
import io.github.longshou.mcyukkuri.subspecies.boat.HullHelm;
import io.github.longshou.mcyukkuri.subspecies.boat.ShoreBerth;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import net.minecraft.world.item.ItemStack;
import io.github.longshou.mcyukkuri.mind.EmotionEvent;

import io.github.longshou.mcyukkuri.subspecies.HatBoatCarry.CarryTrip;
import io.github.longshou.mcyukkuri.subspecies.HatBoatCarry.CarryPickup;
import io.github.longshou.mcyukkuri.subspecies.HatBoatCarry.CarrySearch;
import io.github.longshou.mcyukkuri.subspecies.HatServiceSeats.ServiceSeat;
import io.github.longshou.mcyukkuri.entity.Behaviors;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.family.FamilyFeedingContact;
import io.github.longshou.mcyukkuri.family.FamilyLife;
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
import io.github.longshou.mcyukkuri.family.LiveBirthGeometry;
import io.github.longshou.mcyukkuri.life.SurfaceApproach;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;

/** One real hat (her own hat turned upside down, its soft crown pressed down: HatVessel) and one Marisa owner. Afloat it is
 *  the aquatic Marisa's boat: she rests in its opening at the stern and her young ride in its dry inside, close before her at night.
 *  Unloading never means rescue or death. */
public final class HatBoatEntity extends Entity implements HatBoatView {
    final HatBoatCarry carry = new HatBoatCarry(this);
    final HatServiceSeats careSeats = new HatServiceSeats(this);
    final HatHullMotion hullMotion = new HatHullMotion(this);
    final HatDepartureBerth departure = new HatDepartureBerth(this);
    final HatBoatCapsize capsizeMotion = new HatBoatCapsize(this);
    private final HatBoatLoss loss = new HatBoatLoss(this);
    final HatRescuePair rescuePair = new HatRescuePair(this);
    // Keep protected synchronized data and physical-hat ownership behind entity-owned hooks.
    CompoundTag seatsData() { return entityData.get(SEATS); }
    boolean hasServiceSeats() { return !careSeats.serviceSeats.isEmpty(); }
    boolean hasReturningSeats() { return !careSeats.returningSeats.isEmpty(); }
    boolean ownerUsesToiletSeat() { return careSeats.ownerToiletSeat; }
    String serviceSeatIdsDebug() { return careSeats.serviceSeats.keySet().toString(); }
    String returningSeatIdsDebug() { return careSeats.returningSeats.keySet().toString(); }
    CompoundTag motionData() { return entityData.get(MOTION); }
    void syncMotion(CompoundTag data) {
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
    List<HatVessel.ProvisionZone> capsizeSpillZones() { return interior.zones(cargo.innerCount(), cargo.asideCount()); }

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
    /** The physical headwear identity stays with the hull while its owner is unloaded. */
    private static final EntityDataAccessor<Boolean> AQUATIC_HULL = SynchedEntityData.defineId(HatBoatEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Integer> ACCESSORY_STAGE = SynchedEntityData.defineId(HatBoatEntity.class, EntityDataSerializers.INT);
    /** The deployed hat's own wear (headwear damage 0 intact, 1 frayed, 2 torn), drawn on the hull like a worn hat. */
    private static final EntityDataAccessor<Integer> HULL_DAMAGE = SynchedEntityData.defineId(HatBoatEntity.class, EntityDataSerializers.INT);
    /** Where each young aboard sits (hat units), as the server gave it: where it landed, or the next free place. */
    private static final EntityDataAccessor<CompoundTag> SEATS = SynchedEntityData.defineId(HatBoatEntity.class, EntityDataSerializers.COMPOUND_TAG);
    private static final EntityDataAccessor<CompoundTag> CARGO = SynchedEntityData.defineId(HatBoatEntity.class, EntityDataSerializers.COMPOUND_TAG);
    private static final EntityDataAccessor<Boolean> PADDLING = SynchedEntityData.defineId(HatBoatEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Long> STROKE_START = SynchedEntityData.defineId(HatBoatEntity.class, EntityDataSerializers.LONG);
    private static final EntityDataAccessor<Integer> HELM_KIND = SynchedEntityData.defineId(HatBoatEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> HELM_STATUS = SynchedEntityData.defineId(HatBoatEntity.class, EntityDataSerializers.INT);
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
    private final HatBoatCargo cargo = new HatBoatCargo(this);
    private final HatInteriorLayout interior = new HatInteriorLayout(this);
    private final HullHelm helm = new HullHelm(this);
    private UUID accessoryToken;
    /** Who set the hat down to catch: a helper beside it, or the mother before her own outlet. */
    private HatVessel.Role role = HatVessel.Role.HELPER;
    int flooding;
    private double lerpX, lerpY, lerpZ;
    private float lerpYaw, lerpPitch;
    private int lerpSteps;
    /** Young born into the hat (water birth); kept after a capsize so a rescue can bring them ashore. */
    private final LinkedHashSet<UUID> crew = new LinkedHashSet<>();
    /** A real boarded orphan may use this one captain's food and care seats until a shore hand-off. It never changes ancestry or crew. */
    private final Map<UUID,UUID> temporaryCaregivers = new HashMap<>();
    /** A brief water-rescue reservation is deliberately transient; only an actual boarded care recipient is saved. */
    private final Map<UUID,Long> temporaryRefugeReservations = new HashMap<>();
    private static final int TEMPORARY_REFUGE_RESERVATION_TICKS = 240;
    /** Legacy search/save budget only. Hat load is always measured in the twelve-unit capacity below. */
    public static final int MAX_YOUNG = 16;
    private static final int BABY_LOAD = 2, JUVENILE_LOAD = 4, SUBADULT_LOAD = 8;
    private final HatBoatCrowding crowding=new HatBoatCrowding(this);
    private final AquaticLanding landing=new AquaticLanding(this);
    final Set<UUID> cramped=new HashSet<>();
    /** Server only: places aboard, retained arrivals, movements, and the temporary owner stand. */
    final HatSeatState seatState = new HatSeatState();
    String seatRegion = "";
    /** A real short trip inside the hat.  Crawling uses a level path; hops retain the normal safe arc. */
    record SeatMove(Vec3 from, long start, int ticks, boolean arc, List<Vec3> route) {
        SeatMove { route = List.copyOf(route); }
        SeatMove(Vec3 from,long start,int ticks,boolean arc){this(from,start,ticks,arc,List.of());}
    }
    /** Last read-only planner outcome, for server failure reports; never persisted or used as state. */
    public String carePlanDebug(){ return careSeats.carePlanDebug(); }
    long boardingEdgeUntil;
    /** Young hopping out in turn before the hat is taken back: who, to which spot (world), from which game tick. */
    /** A child who cannot jump is lowered over the brim by its parent instead of initiating a hop itself. */
    private record Leaving(UUID young, Vec3 spot, long at, boolean assisted) {}
    private final ArrayList<Leaving> leaving = new ArrayList<>();
    private List<Vec3> seatCache = List.of();
    private String seatKey = "";

    public HatBoatEntity(EntityType<? extends HatBoatEntity> type, Level level) { super(type, level); blocksBuilding = true; }
    @Override protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(STATE, State.AFLOAT.ordinal()); builder.define(OWNER, Optional.empty()); builder.define(SCALE, 1f);
        // Every pre-Water-15 hat boat is an aquatic Marisa vessel.  This preserves its identity until a loaded owner refreshes it.
        builder.define(AQUATIC_HULL, true); builder.define(ACCESSORY_STAGE, FamilyLife.Stage.ADULT.ordinal()); builder.define(SEATS, new CompoundTag());
        builder.define(CARGO, new CompoundTag()); builder.define(PADDLING, false); builder.define(MOTION,new CompoundTag()); builder.define(DIP,new CompoundTag());
        builder.define(STROKE_START, 0L); builder.define(CURRENT, 0f); builder.define(HELM_KIND, 0); builder.define(HELM_STATUS, 0); builder.define(HULL_DAMAGE, 0);
    }
    @Override public void onSyncedDataUpdated(EntityDataAccessor<?> data) {
        super.onSyncedDataUpdated(data);
        if (CARGO.equals(data) && cargo != null && level().isClientSide) cargo.load(entityData.get(CARGO));
        if (SCALE.equals(data)) refreshDimensions();
    }
    @Override public EntityDimensions getDimensions(Pose pose) {
        return super.getDimensions(pose).scale(hullScale()/HatVessel.AQUATIC_SCALE);
    }
    public HatBoatCargo cargo() { return cargo; }
    /** A prospective heap must leave every current body and retained destination clear. */
    public int cargoRoom(){ return interior.storageRoom(); }
    public List<HatVessel.ProvisionZone> cargoZones(){
        var zones=interior.zones(cargo.innerCount(),cargo.asideCount());
        if(cargo.handling())zones.addAll(interior.zones(cargo.count()-cargo.targetAsideCount(),cargo.targetAsideCount()));
        return zones;
    }
    void addCargoFootprints(List<double[]> out) {
        for(var zone:cargoZones()){
            var p=zone.position();
            out.add(new double[]{p.x,p.y,p.z,zone.radius(),zone.height(),2});
        }
    }
    void syncCargo(CompoundTag data) { entityData.set(CARGO, data); seatRegion = ""; }
    boolean cargoFits(int inner,int aside){return interior.cargoFits(inner,aside);}
    boolean cargoLayoutBusy(){
        return unsteadyMotion()||carry.carryTrip!=null||rescuePair.rescueOwnerHeld||departure.departureYielding!=null
                ||!careSeats.serviceSeats.isEmpty()||!careSeats.returningSeats.isEmpty()||hasIncoming()||careSeats.ownerToiletSeat
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
        if(child==null||!admits(child)||!hasCapacityFor(child)||!(isAfloat()||catches()||state()==State.BEACHED))return false;
        if(retainedCargoSeat(child)!=null)return true;
        if(plannedSeat(child,null,false)!=null)return true;
        for(int aside=cargo.asideCount()+1;aside<=cargo.count();aside++)
            if(interior.cargoFits(cargo.count()-aside,aside)&&plannedSeat(child,null,false,cargo.count()-aside,aside)!=null)return true;
        return false;
    }
    /** Called only for a chosen, physically approaching child, never from broad entity-search predicates. */
    public boolean prepareCargoSpace(YukkuriEntity child,boolean emergency){
        if(level().isClientSide||child==null||!admits(child)||!(isAfloat()||catches()||state()==State.BEACHED)
                ||!hasCapacityFor(child)&&(!emergency||!canFitAfterFoodJettison(child)))return false;
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
        if(level().isClientSide||child==null||!careRecipient(child))return false;
        if(cargo.handling()){cargo.keepAside();return false;}
        if(cargo.innerCount()==0||careSeats.serviceSeats.containsKey(child.getUUID())||careSeats.returningSeats.containsKey(child.getUUID())){
            if(cargo.asideCount()>0)cargo.keepAside();return true;
        }
        var parent=owner();var original=seatState.residentSeat(child.getUUID());
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
        if(level().isClientSide||!(isAfloat()||catches())||capacityUsed()+BABY_LOAD>HatBoatCargo.CAPACITY)return false;
        if(cargo.handling()){cargo.keepAside();return false;}
        if(cargo.innerCount()==0){if(cargo.asideCount()>0)cargo.keepAside();return true;}
        if(hasRoomForYoung()){if(cargo.asideCount()>0)cargo.keepAside();return true;}
        // The generic newborn envelope is deliberately conservative; the real newborn gets the final catch check.
        var parent=owner();if(parent!=null)parent.life().wakeInHat();
        cargo.moveAside(cargo.count());
        return false;
    }
    private Vec3 retainedCargoSeat(YukkuriEntity child){
        var seat=seatState.incomingSeat(child.getUUID());
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
        if(seatState.hasSeatBelow(top))return true;
        if(departure.voyageBerth!=null&&departure.voyageBerth[1]<top)return true;
        for(var map:List.of(careSeats.serviceSeats,careSeats.returningSeats))for(var seat:map.values())
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
    /** Planning only (no motion): the checked water course for the captain {@code actor} toward a point, or null. */
    public HullCourse courseFor(YukkuriEntity actor, Vec3 destination) {
        if (destination == null || actor.getVehicle() != this || !actor.getUUID().equals(ownerYukkuri())
                || !protects(actor) || !isAfloat() || unsteadyMotion() || flooding() > 0
                || landing() || helm.driving() || carry.carryTrip != null || cargo.handling()
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
    public HullHelm.Kind helmKind() { return HullHelm.Kind.values()[Math.clamp(entityData.get(HELM_KIND), 0, HullHelm.Kind.values().length - 1)]; }
    public HullHelm.State helmStatus() { return HullHelm.State.values()[Math.clamp(entityData.get(HELM_STATUS), 0, HullHelm.State.values().length - 1)]; }
    public HullHelm helm() { return helm; }
    public boolean crowdContesting() { return crowding.contesting(); }
    public boolean hasCarryTrip() { return carry.carryTrip != null; }
    public boolean protects(Entity rider) {
        return hasPassenger(rider) && (isAfloat() || catches() || state() == State.BEACHED)
                && !level().getFluidState(BlockPos.containing(getX(), getY()+.25, getZ())).is(FluidTags.WATER);
    }
    public Vec3 ownerSeat(YukkuriEntity owner) {
        // Use the same complete collision body as the walking planner. The authored rear shell is
        // shorter than Minecraft's square body; using it put the captain through the stern wall.
        double back = Math.max(owner.getBbWidth()*.5/unit(),
                LiveBirthGeometry.back(owner)*owner.getScale()/unit());
        double rear = Math.max(0, HatVessel.opening(mode())[3]-back-.05);
        return new Vec3(0, HatVessel.travelSupport(mode(),0,rear,radiusOf(owner)),rear);
    }
    public UUID ownerYukkuri() { return entityData.get(OWNER).orElse(null); }
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
    public int flooding() { return flooding; }
    public void state(State next) { entityData.set(STATE, next.ordinal()); }
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
        return admits(y) && hasRoomFor(y);
    }
    /** The owner's own young (as mother or father), not yet adult. */
    public boolean young(YukkuriEntity y) {
        return ownerYukkuri() != null && (ownerYukkuri().equals(y.family().mother()) || ownerYukkuri().equals(y.family().father())) && !y.family().adult();
    }
    /** Physical boarding authority: birth family, a saved boarded refuge, or a nearby finite rescue reservation. */
    public boolean admits(YukkuriEntity child) {
        if(child==null||child.family().adult())return false;
        if(young(child)||temporaryCaregiver(child))return true;
        return temporaryRefugeReservations.getOrDefault(child.getUUID(),Long.MIN_VALUE)>level().getGameTime();
    }
    /** Immediate food and supported care-seat authority after a non-parent rescue; it grants no family relation. */
    public boolean careRecipient(YukkuriEntity child) { return child!=null&&(young(child)||temporaryCaregiver(child)); }
    public boolean temporaryCaregiver(YukkuriEntity child) {
        return child!=null&&ownerYukkuri()!=null&&ownerYukkuri().equals(temporaryCaregivers.get(child.getUUID()));
    }
    /** Reserve capacity only for an actual visible swimmer selected by the caller's help policy. */
    public boolean requestTemporaryRefuge(YukkuriEntity child) {
        if(level().isClientSide||child==null||child.family().adult()||child.isPassenger()||!child.isInWaterOrBubble()
                ||!isAfloat()||unsteadyMotion()||flooding>0||owner()==null||!owner().isAlive()
                ||child.distanceToSqr(this)>144||!child.hasLineOfSight(this))return false;
        if(temporaryCaregiver(child))return true;
        long now=level().getGameTime();
        if(temporaryRefugeReservations.getOrDefault(child.getUUID(),Long.MIN_VALUE)>now)return true;
        if(capacityUsed()+youngLoad(child)>HatBoatCargo.CAPACITY)return false;
        temporaryRefugeReservations.put(child.getUUID(),now+TEMPORARY_REFUGE_RESERVATION_TICKS);
        GameplayLog.event(this,"aquatic","temporary refuge reserved child={} caregiver={} until={} capacity={}/{}",
                child.getUUID(),ownerYukkuri(),now+TEMPORARY_REFUGE_RESERVATION_TICKS,capacityUsed(),HatBoatCargo.CAPACITY);
        return true;
    }
    /**
     * Keep one berth for a dependent who is still physically safe in an adjacent, ownerless
     * hat.  This does not dismount it: the child must make the existing checked hull-to-hull
     * transfer before this reservation turns into care authority.
     */
    public boolean requestTemporaryRefugeTransfer(YukkuriEntity child,HatBoatEntity source) {
        if(level().isClientSide||child==null||source==null||source==this||child.family().adult()
                ||child.getVehicle()!=source||!source.isAfloat()||source.unsteadyMotion()||source.flooding()>0
                ||source.owner()!=null&&source.owner().isAlive()||!isAfloat()||unsteadyMotion()||flooding>0
                ||owner()==null||!owner().isAlive()||child.distanceToSqr(this)>144||!child.hasLineOfSight(this))return false;
        if(temporaryCaregiver(child))return true;
        long now=level().getGameTime();
        if(temporaryRefugeReservations.getOrDefault(child.getUUID(),Long.MIN_VALUE)>now)return true;
        if(capacityUsed()+youngLoad(child)>HatBoatCargo.CAPACITY)return false;
        temporaryRefugeReservations.put(child.getUUID(),now+TEMPORARY_REFUGE_RESERVATION_TICKS);
        GameplayLog.event(this,"aquatic","temporary refuge transfer reserved child={} sourceHat={} caregiver={} until={} capacity={}/{}",
                child.getUUID(),source.getUUID(),ownerYukkuri(),now+TEMPORARY_REFUGE_RESERVATION_TICKS,capacityUsed(),HatBoatCargo.CAPACITY);
        return true;
    }
    /** Called after the ordinary shore landing has physically put the temporary rider on dry ground. */
    void releaseTemporaryRefuge(YukkuriEntity child,String reason) {
        if(child==null)return;
        boolean boarded=temporaryCaregivers.remove(child.getUUID())!=null;
        boolean reserved=temporaryRefugeReservations.remove(child.getUUID())!=null;
        if(boarded||reserved)GameplayLog.event(this,"aquatic","temporary refuge released child={} reason={} boarded={}",child.getUUID(),reason,boarded);
    }
    /** Promote only a completed mount. A reservation alone never grants ongoing care or changes the family ledger. */
    private void confirmTemporaryRefuge(YukkuriEntity child) {
        if(child==null||young(child))return;
        long until=temporaryRefugeReservations.getOrDefault(child.getUUID(),Long.MIN_VALUE);
        if(until<=level().getGameTime())return;
        temporaryRefugeReservations.remove(child.getUUID());temporaryCaregivers.put(child.getUUID(),ownerYukkuri());
        GameplayLog.event(this,"aquatic","temporary refuge boarded child={} caregiver={} capacity={}/{}",
                child.getUUID(),ownerYukkuri(),capacityUsed(),HatBoatCargo.CAPACITY);
    }
    public int youngAboard() { return (int) getPassengers().stream().filter(p -> !p.getUUID().equals(ownerYukkuri())).count(); }
    /** All provisions, the real paddle and every distinct young aboard or promised a berth share this budget. */
    public int capacityUsed() { return cargo.usedCapacity() + passengerCapacityUsed() + carriedPaddleLoad(); }
    public int remainingCapacity() { return Math.max(0, HatBoatCargo.CAPACITY - capacityUsed()); }
    /** Cargo's part of the total load after living bodies and held return berths have been reserved. */
    int cargoCapacity() { return Math.max(0, HatBoatCargo.CAPACITY - passengerCapacityUsed() - carriedPaddleLoad()); }
    private int passengerCapacityUsed() {
        var occupants = new LinkedHashSet<UUID>();
        for (var passenger : getPassengers()) if (passenger instanceof YukkuriEntity y && careRecipient(y)) occupants.add(y.getUUID());
        seatState.forEachIncoming((id, seat) -> occupants.add(id));
        if (departure.voyageYoung != null) occupants.add(departure.voyageYoung);
        occupants.addAll(temporaryRefugeReservations.keySet());
        return occupants.stream().mapToInt(this::reservedYoungLoad).sum();
    }
    /** A handoff stick is aboard before it reaches its recipient; the cargo-owned paddle is counted separately above. */
    private int carriedPaddleLoad() {
        var captain=owner();
        return captain!=null && captain.getVehicle()==this && captain.aquaticLife().familyPaddle().handingOver() ? 1 : 0;
    }
    private int reservedYoungLoad(UUID id) {
        if (level() instanceof ServerLevel server && server.getEntity(id) instanceof YukkuriEntity young && admits(young)) return youngLoad(young);
        for (var passenger : getPassengers()) if (passenger.getUUID().equals(id) && passenger instanceof YukkuriEntity young && admits(young)) return youngLoad(young);
        // A saved, currently unloaded incoming child keeps the largest possible berth until its record is released.
        return SUBADULT_LOAD + HatBoatCargo.PADDLE_UNITS;
    }
    private int youngLoad(YukkuriEntity child) {
        int body=child.family().stage() == FamilyLife.Stage.BABY ? BABY_LOAD
                : child.childhood().canLiveIndependently() ? SUBADULT_LOAD : JUVENILE_LOAD;
        return body + (child.aquaticLife().hasPaddleForReturn() ? HatBoatCargo.PADDLE_UNITS : 0);
    }
    private boolean capacityHeld(YukkuriEntity child) {
        return hasPassenger(child) || seatState.hasIncoming(child.getUUID()) || child.getUUID().equals(departure.voyageYoung)
                || temporaryRefugeReservations.containsKey(child.getUUID()) || temporaryCaregiver(child);
    }
    boolean hasCapacityFor(YukkuriEntity child) {
        return child != null && admits(child) && (capacityHeld(child) || capacityUsed() + youngLoad(child) <= HatBoatCargo.CAPACITY);
    }
    private boolean canFitAfterFoodJettison(YukkuriEntity child) {
        return child != null && young(child) && passengerCapacityUsed() + carriedPaddleLoad() + (cargo.paddle() ? HatBoatCargo.PADDLE_UNITS : 0)
                + (capacityHeld(child) ? 0 : youngLoad(child)) <= HatBoatCargo.CAPACITY;
    }
    public boolean hasIncoming() { return seatState.hasIncoming(); }
    /** A cancelled airborne transfer releases only its incoming slot, not a separately retained voyage berth. */
    public void cancelIncoming(YukkuriEntity child){
        if(!level().isClientSide&&child!=null&&!hasPassenger(child))seatState.releaseIncoming(child.getUUID());
    }
    public boolean hasRoomForYoung() {
        double newborn=HatVessel.radius(.8*LiveBirthGeometry.BABY_SCALE)/unit();
        return cargo.tier()<3&&(isAfloat()||state()==State.BEACHED||catches())&&capacityUsed()+BABY_LOAD<=HatBoatCargo.CAPACITY
                &&HatVessel.place(mode(),newborn,region(),null,layoutTaken(null),null,Double.NaN,HatVessel.newbornSpace(mode(),hullScale(),unit()))!=null;
    }
    /** Room for this young: a free place, or the one kept for it. */
    public boolean hasRoomFor(YukkuriEntity child) {
        if(child==null||!admits(child)||!(isAfloat()||state()==State.BEACHED||catches()))return false;
        if(hasPassenger(child)||retainedCargoSeat(child)!=null)return true;
        if(!hasCapacityFor(child))return false;
        return plannedSeat(child,null,false)!=null;
    }
    /** Atomically keep an ordinary safe seat for a newborn after it has received its real identity. */
    public Vec3 reserveBirthAdmission(YukkuriEntity child) {
        if (level().isClientSide || !isAfloat() || unsteadyMotion() || !hasRoomFor(child)) return null;
        var seat=reserve(child,null);
        if (seat != null) syncSeats();
        return seat;
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
        return cargo.tier()<3&&hasCapacityFor(child)&&HatVessel.place(mode(),radiusOf(child),region(),null,
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
                ||!child.isAlive()||!hasCapacityFor(child))return false;
        var local=toLocal(child.position());double reach=radiusOf(child)+.25/unit();
        boolean footReach=Double.isFinite(HatVessel.footprint(mode(),local.x,local.z,reach))
                &&Math.abs(local.y-HatVessel.freeboard(mode()))<=1.4/unit();
        if(!hasPassenger(child)&&(!(footReach||touchingOwnHat(child))||!child.hasLineOfSight(this)))return false;
        var have=seatState.incomingSeat(child.getUUID());
        var place=have!=null?new Vec3(have[0],have[1],have[2]):plannedSeat(child,null,true);
        if(place==null)return false;
        crowding.emergency(child);
        if(!hasPassenger(child))seatState.reserveIncoming(child.getUUID(),new double[]{place.x,place.y,place.z,radiusOf(child)});
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
        if(inside==null||parent==null||!careSeats.serviceSeats.isEmpty()||!careSeats.returningSeats.isEmpty())return false;
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
                &&!careSeats.serviceSeats.containsKey(rider.getUUID())&&!careSeats.returningSeats.containsKey(rider.getUUID());
    }
    boolean crowdPosition(YukkuriEntity rider,Vec3 local){
        if(!cargoSeatClear(rider,local))return false;
        var target=toWorld(local);
        rider.move(MoverType.SELF,target.subtract(rider.position()));
        var reached=toLocal(rider.position());
        seatState.putResident(rider.getUUID(),new double[]{reached.x,reached.y,reached.z,radiusOf(rider)});
        seatState.clearMove(rider.getUUID());
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
    @Override public Vec3 getDismountLocationForPassenger(LivingEntity passenger){
        // Both the held child's lift and active rim boarding start at the actual feet.
        // Vanilla's relocation to the hull centre would invalidate the checked trajectory.
        if(passenger instanceof YukkuriEntity child&&(child.family().cradleHeld()
                ||child.family().boardingDismountInPlace(this)))return child.position();
        return super.getDismountLocationForPassenger(passenger);
    }
    /** Finish a physical water lift on exactly its reserved, supported brim patch; never select a fallback interior seat. */
    public boolean boardRescuedYoung(YukkuriEntity child,Vec3 expected) {
        if(level().isClientSide||expected==null||!isAfloat()||unsteadyMotion()||cargo.handling()||!admits(child)||!hasRoomFor(child))return false;
        var reserved=seatState.incomingSeat(child.getUUID());
        if(reserved==null)return false;
        var at=new Vec3(reserved[0],reserved[1],reserved[2]);
        if(at.distanceToSqr(expected)>1e-8||child.position().distanceToSqr(toWorld(at))>.035*.035
                ||!cargoSeatClear(child,at)||!HatVessel.supported(mode(),at.x,at.z,radiusOf(child),HatVessel.top(mode())))return false;
        if(!child.startRiding(this,true))return false;
        seatState.takeIncoming(child.getUUID());confirmTemporaryRefuge(child);if(young(child))crew.add(child.getUUID());
        seatState.putResident(child.getUUID(),reserved);syncSeats();return true;
    }
    /** A newborn delivered in the hat stays aboard with its mother. */
    public boolean boardYoung(YukkuriEntity child) {
        if (level().isClientSide || cargo.handling() || unsteadyMotion()&&!crowding.emergency(child.getUUID()) || !admits(child) || !hasRoomFor(child)) return false;
        var seat = seatState.takeIncoming(child.getUUID());
        if(seat!=null&&!cargoSeatClear(child,new Vec3(seat[0],seat[1],seat[2])))seat=null;
        if (seat == null) { double r = radiusOf(child); var s = plannedSeat(child,null,false); if(s==null)return false;seat = new double[]{s.x, s.y, s.z, r}; }
        if (!child.startRiding(this, true)) return false;
        confirmTemporaryRefuge(child);if(young(child))crew.add(child.getUUID());
        seatState.putResident(child.getUUID(), seat); syncSeats(); return true;
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
                var brim=child.childhood().canJump()?HatVessel.brimPlace(mode(),r,taken,false):HatVessel.refugeBrimPlace(mode(),r,taken,null,false);
                if(brim!=null)return brim;
            }
        }
        if(inside!=null)return inside;
        return emergency?HatVessel.refugeBrimPlace(mode(),r,taken,toLocal(child.position()),true):null;
    }
    @Override protected void removePassenger(Entity passenger) {
        super.removePassenger(passenger);
        if (!level().isClientSide) {
            careSeats.returnRetries.remove(passenger.getUUID());
            boolean ownerLeft = passenger.getUUID().equals(ownerYukkuri());
            if (ownerLeft) {
                careSeats.ownerToiletSeat = false;
                seatState.clearOwnerServiceSeat();
                seatState.clearOwnerSeatMove();
            }
            seatState.clearMove(passenger.getUUID());
            careSeats.returningSeats.remove(passenger.getUUID());
            boolean hadService = careSeats.serviceSeats.remove(passenger.getUUID()) != null;
            if (hadService && careSeats.serviceSeats.isEmpty()) {
                var parent = owner();
                if (parent != null && parent.getVehicle() == this) moveOwnerSeat(parent, ownerSeat(parent), false);
            }
            if (ownerLeft || seatState.removeResident(passenger.getUUID()) || hadService) syncSeats();
        }
    }
    // ---------------------------------------------------------------- the hat's frame and the young's places
    /** The persisted hull identity, never the visibility of its owner, selects the authored cavity profile. */
    public HatVessel.Mode mode() { return isCradle() ? HatVessel.Mode.NEST : aquaticHull() ? HatVessel.Mode.AQUATIC_FLOAT : HatVessel.Mode.FLOAT; }
    /** Blocks per hat unit (the prop's units at this hat's size). */
    public double unit() { return hullScale()/16; }
    float turn() { return (float) Math.toRadians(180 - getYRot()); }
    public Vec3 toWorld(Vec3 local) { return position().add(local.scale(unit()).yRot(turn())); }
    public Vec3 toLocal(Vec3 world) { return world.subtract(position()).yRot(-turn()).scale(1/unit()); }
    String region() { return catches() ? "catch" : !isMoored() && level().isNight() ? "night" : "day"; }
    double radiusOf(Entity young) { return HatVessel.radius(young.getBbWidth())/unit(); }
    List<Entity> youngPassengers() { return getPassengers().stream().filter(p -> !p.getUUID().equals(ownerYukkuri())).toList(); }
    List<double[]> taken() {
        return takenExcept(null);
    }
    /** Ordinary sleeping and daytime berths stay outside the parent's body projection, even on a lower layer. */
    List<double[]> layoutTaken(UUID excluded) {
        var out=takenExcept(excluded);
        addOwnerProjection(out);
        return out;
    }
    List<double[]> layoutTaken(UUID excluded,int inner,int aside){
        var out=interior.taken(excluded,inner,aside);
        addOwnerProjection(out);
        return out;
    }
    private void addOwnerProjection(List<double[]> out){
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
    List<Vec3> departureYieldRoute(YukkuriEntity rider,Vec3 destination){ return departure.departureYieldRoute(rider, destination); }
    boolean departureEnvelopesMeet(YukkuriEntity a,Vec3 at,float yaw,YukkuriEntity b,Vec3 bt,float byaw){ return departure.departureEnvelopesMeet(a, at, yaw, b, bt, byaw); }
    boolean departureEnvelopeClear(YukkuriEntity rider,Vec3 at,float yaw,YukkuriEntity ignored){ return departure.departureEnvelopeClear(rider, at, yaw, ignored); }
    public void releaseVoyageBerth(UUID child) { departure.releaseVoyageBerth(child); }
    public Vec3 voyageBerth(YukkuriEntity child) { return departure.voyageBerth(child); }

    /** The retained front support has been reached, rather than merely reserved in the seat map. */
    public boolean onVoyageBerth(YukkuriEntity child) { return departure.onVoyageBerth(child); }
    void syncSeats() {
        entityData.set(SEATS, seatState.presentationSnapshot(ownerYukkuri(),
                carry.carryTrip == null ? null : saveCarry(carry.carryTrip), crowding.save(), !cramped.isEmpty()));
    }
    /** The place the server gave this young aboard, or null. */
    Vec3 seatOf(Entity young) {
        if (!level().isClientSide) { var s = seatState.residentSeat(young.getUUID()); return s == null ? null : new Vec3(s[0], s[1], s[2]); }
        for (var raw : entityData.get(SEATS).getList("S", Tag.TAG_COMPOUND))
            if (raw instanceof CompoundTag c && c.hasUUID("Id") && c.getUUID("Id").equals(young.getUUID())) return new Vec3(c.getDouble("X"), c.getDouble("Y"), c.getDouble("Z"));
        return null;
    }
    private SeatMove seatMove(Entity young) {
        if(!level().isClientSide)return seatState.move(young.getUUID());
        for(var raw:entityData.get(SEATS).getList("S",Tag.TAG_COMPOUND))
            if(raw instanceof CompoundTag c&&c.hasUUID("Id")&&c.getUUID("Id").equals(young.getUUID())&&c.contains("At"))
                return new SeatMove(new Vec3(c.getDouble("FromX"),c.getDouble("FromY"),c.getDouble("FromZ")),c.getLong("At"),c.getInt("Ticks"),c.getBoolean("Arc"),HatSurfaceRoute.load(c));
        return null;
    }
    public boolean changingSeat(Entity young){var m=young instanceof YukkuriEntity y&&
            young.getUUID().equals(ownerYukkuri())?ownerServiceMove(y):seatMove(young);return m!=null&&level().getGameTime()-m.start()<m.ticks();}
    boolean changingOwnerSeat() { var move=seatState.ownerSeatMove(); return move != null && level().getGameTime()-move.start() < move.ticks(); }

    /**
     * Moves both members of a care pair inside the same protected hat.  The child gets a supported
     * temporary berth and the captain makes a small real SeatMove from her normal stern berth;
     * callers must still wait for {@link #careSeatReady(YukkuriEntity)} and test their own contact.
     */
    public boolean prepareCareSeat(YukkuriEntity child, YukkuriEntity parent) { return careSeats.prepareCareSeat(child, parent); }

    /** Same temporary berth machinery for a parent standing behind the child during first-toilet care. */
    public boolean prepareRearCareSeat(YukkuriEntity child, YukkuriEntity parent) { return careSeats.prepareRearCareSeat(child, parent); }

    boolean prepareServiceSeat(YukkuriEntity child, YukkuriEntity parent, boolean rearCare) { return careSeats.prepareServiceSeat(child, parent, rearCare); }

    /** Move the captain to a rear brim patch with the body supported but its rear clear of the hat. */
    boolean prepareOwnerToiletSeat(YukkuriEntity owner) { return careSeats.prepareOwnerToiletSeat(owner); }

    /** The final service geometry has arrived; it remains valid through night-seat reflow. */
    public boolean careSeatReady(YukkuriEntity child) { return careSeats.careSeatReady(child); }

    /** True when the active temporary berth asks the parent to face the child's back. */
    public boolean rearCareSeat(YukkuriEntity child) { return careSeats.rearCareSeat(child); }

    /**
     * After rear-care contact has completed, support the child on the front brim with its rear over
     * open water.  The accepted rear-care contact remains recorded in this temporary service seat;
     * the child is never dropped or teleported outside the hat.
     */
    public boolean prepareToiletSeat(YukkuriEntity child, YukkuriEntity parent) { return careSeats.prepareToiletSeat(child, parent); }

    /**
     * Normal hat hygiene uses the same supported front-brim position, without claiming that a
     * first-toilet rear-care action occurred.  It cannot displace feeding or another child's care.
     */
    public boolean prepareToiletSeat(YukkuriEntity child) { return careSeats.prepareToiletSeat(child); }

    /** A completed rear-care action may release only when the child is actually supported at the brim. */
    public boolean toiletSeatReady(YukkuriEntity child) { return careSeats.toiletSeatReady(child); }

    /** Make the child face inward so the rear is beyond the front brim, toward the surrounding water. */
    public void faceToiletOutward(YukkuriEntity child) { careSeats.faceToiletOutward(child); }

    /** A real body-height point directly behind a supported brim rider, or null when no water is below it. */
    public Vec3 toiletDropPosition(YukkuriEntity child) { return careSeats.toiletDropPosition(child); }

    /** Lets the completed-care caller prove that a real rear-contact phase preceded the brim release. */
    public boolean toiletAssistanceValidated(YukkuriEntity child) { return careSeats.toiletAssistanceValidated(child); }

    /** Release only a temporary hygiene berth; active feeding/rear-care and childhood practice remain untouched. */
    public boolean releaseToiletSeat(YukkuriEntity child) { return careSeats.releaseToiletSeat(child); }

    /** Keep a return reservation until the child physically arrives; failed paths can be retried. */
    public void releaseCareSeat(UUID childId) { careSeats.releaseCareSeat(childId); }

    void returnCareSeats() { careSeats.returnCareSeats(); }

    /** Wait for the live rider-position update, rather than accepting only an expired timer. */
    boolean atSeat(YukkuriEntity rider, Vec3 local) {
        boolean moving = rider.getUUID().equals(ownerYukkuri()) ? changingOwnerSeat() : changingSeat(rider);
        return rider.getVehicle() == this && !moving && rider.position().distanceToSqr(toWorld(local)) < .0004;
    }

    /** Start a small supported childhood movement inside the hat; {@code hop=false} stays level. */
    public boolean beginPracticeMove(YukkuriEntity child, boolean hop) { return careSeats.beginPracticeMove(child, hop); }

    public boolean practisingMove(YukkuriEntity child) { return careSeats.practisingMove(child); }

    /** Consume a completed childhood move and send the child back to its normal berth. */
    public boolean completePracticeMove(YukkuriEntity child) { return careSeats.completePracticeMove(child); }

    /** Cancel only a child's voluntary in-hat practice; active feeding and toilet care cannot be cancelled here. */
    public boolean cancelPracticeMove(YukkuriEntity child) { return careSeats.cancelPracticeMove(child); }

    /**
     * The captain is larger than a newborn, so a rear toilet seat needs a real contact patch rather
     * than a whole-body circle inside the front juvenile brim.  The centre and forward feet remain
     * supported; the rear drop point is checked separately beyond the hull.
     */
    Vec3 rearBrimPlace(YukkuriEntity rider, List<double[]> taken) {
        double support = Math.min(1.10, Math.max(.45, radiusOf(rider) * .25));
        double rear = (rider.getBbWidth() * .55 + .06) / unit();
        var mode=mode();var opening=HatVessel.opening(mode);var brim=HatVessel.brim(mode);double freeboard=HatVessel.freeboard(mode);
        Vec3 best = null; double bestCost = Double.MAX_VALUE;
        for (double x = -Math.min(4.75,brim[0]-.2); x <= Math.min(4.75,brim[0]-.2); x += .25) for (double z = opening[3] + .20; z <= brim[1] - .20; z += .25) {
            if (!brimSupport(x, z) || !brimSupport(x, z-support) || !brimSupport(x+support*.45, z-support*.15)
                    || !brimSupport(x-support*.45, z-support*.15)
                    || Double.isFinite(HatVessel.surface(mode, x, z+rear))) continue;
            boolean clear = true;
            for (var seat : taken) if (Math.hypot(x-seat[0], z-seat[2]) < Math.max(.9, (radiusOf(rider)+seat[3])*.32)) { clear=false; break; }
            if (!clear) continue;
            double cost = Math.abs(x) + Math.abs(z-(opening[3]+support));
            if (cost < bestCost) { bestCost = cost; best = new Vec3(x, freeboard, z); }
        }
        return best;
    }

    /**
     * A front edge for large pupils: their feet and inner body stay supported while the forward
     * body may extend beyond the narrow juvenile brim.  Toileting additionally requires the rear
     * body point to clear the actual hull outline before the water check can pass.
     */
    Vec3 frontEdgePlace(YukkuriEntity rider, List<double[]> taken, boolean needsRearClear) {
        return frontEdgePlace(rider,taken,needsRearClear,null);
    }
    Vec3 frontEdgePlace(YukkuriEntity rider, List<double[]> taken, boolean needsRearClear,YukkuriEntity ignored) {
        double support = Math.min(1.10, Math.max(.45, radiusOf(rider) * .25));
        double rear = (rider.getBbWidth() * .55 + .06) / unit();
        var mode=mode();var opening=HatVessel.opening(mode);var brim=HatVessel.brim(mode);double freeboard=HatVessel.freeboard(mode);
        Vec3 best = null; double bestCost = Double.MAX_VALUE;
        for (double x = -Math.min(4.75,brim[0]-.2); x <= Math.min(4.75,brim[0]-.2); x += .25) for (double z = -brim[1] + .20; z <= opening[2] - .20; z += .25) {
            if (!brimSupport(x, z) || !brimSupport(x, z+support) || !brimSupport(x+support*.45, z+support*.15)
                    || !brimSupport(x-support*.45, z+support*.15)) continue;
            if (needsRearClear && Double.isFinite(HatVessel.surface(mode, x, z-rear))) continue;
            boolean clear = true;
            for (var seat : taken) if (Math.hypot(x-seat[0], z-seat[2]) < Math.max(.9, (radiusOf(rider)+seat[3])*.32)) { clear=false; break; }
            if (!clear) continue;
            if(!needsRearClear&&!departureEnvelopeClear(rider,new Vec3(x,freeboard,z),getYRot(),ignored))continue;
            // Prefer the real outer front edge, leaving clearance to lower the pupil's own hat.
            double cost = Math.abs(x)*.2 + z;
            if (cost < bestCost) { bestCost = cost; best = new Vec3(x, freeboard, z); }
        }
        return best;
    }

    boolean brimSupport(double x, double z) { return careSeats.brimSupport(x, z); }

    ServiceSeat serviceSeatFor(YukkuriEntity child, YukkuriEntity parent, double[] original, boolean rearCare) { return careSeats.serviceSeatFor(child, parent, original, rearCare); }

    boolean serviceGeometryFits(YukkuriEntity parent, YukkuriEntity child, Vec3 ownerAt, Vec3 childAt) { return careSeats.serviceGeometryFits(parent, child, ownerAt, childAt); }

    List<double[]> takenExcept(UUID excluded) {
        var out=occupantsExcept(excluded);addCargoFootprints(out);return out;
    }
    List<double[]> occupantsExcept(UUID excluded) {
        var out = new ArrayList<double[]>();
        seatState.forEachResident((id, seat) -> { if (!id.equals(excluded)) {
            var occupant=level() instanceof ServerLevel server?server.getEntity(id):null;
            if(occupant instanceof YukkuriEntity y){
                var shape=HatVessel.bodySpace(y,hullScale(),unit());
                out.add(new double[]{seat[0],seat[1],seat[2],radiusOf(y),shape.height(),0,shape.hatX(),shape.hatZ(),shape.hatBase(),shape.hatHeight()});
            }else out.add(new double[]{seat[0],seat[1],seat[2],seat[3],occupant==null?seat[3]*2:occupant.getBbHeight()/unit()});
        }});
        seatState.forEachIncoming((id, seat) -> { if (!id.equals(excluded)) {
            var occupant=level() instanceof ServerLevel server?server.getEntity(id):null;
            if(occupant instanceof YukkuriEntity y){
                var shape=HatVessel.bodySpace(y,hullScale(),unit());
                out.add(new double[]{seat[0],seat[1],seat[2],radiusOf(y),shape.height(),0,shape.hatX(),shape.hatZ(),shape.hatBase(),shape.hatHeight()});
            }else out.add(seat);
        }});
        // A layout replan may temporarily remove destinations. Their actual riders still occupy the hat.
        for(var p:youngPassengers())if(!p.getUUID().equals(excluded)&&!seatState.hasResident(p.getUUID())){
            var at=toLocal(p.position());
            out.add(p instanceof YukkuriEntity y?rescueBody(y,at):new double[]{at.x,at.y,at.z,radiusOf(p),p.getBbHeight()/unit()});
        }
        if (departure.voyageBerth != null && (departure.voyageYoung == null || !departure.voyageYoung.equals(excluded)) &&
                !seatState.hasResident(departure.voyageYoung) && !seatState.hasIncoming(departure.voyageYoung)) out.add(departure.voyageBerth);
        return out;
    }
    /** Inventory changes must respect body positions, temporary stations and promised return positions together. */
    List<double[]> cargoOccupants(){
        var out=occupantsExcept(null);
        for(var p:getPassengers())out.add(p instanceof YukkuriEntity y?rescueBody(y,toLocal(p.position())):
                new double[]{toLocal(p.position()).x,toLocal(p.position()).y,toLocal(p.position()).z,radiusOf(p),p.getBbHeight()/unit()});
        var parent=owner();
        if(parent!=null&&!catches()){
            out.add(rescueBody(parent,ownerSeat(parent)));
            if(seatState.ownerServiceSeat()!=null)out.add(rescueBody(parent,seatState.ownerServiceSeat()));
        }
        for(var map:List.of(careSeats.serviceSeats,careSeats.returningSeats))for(var e:map.entrySet()){
            var rider=level() instanceof ServerLevel server?server.getEntity(e.getKey()):null;
            for(var seat:List.of(e.getValue().original(),e.getValue().service()))
                out.add(rider instanceof YukkuriEntity y?rescueBody(y,new Vec3(seat[0],seat[1],seat[2])):seat);
            if(parent!=null&&e.getValue().ownerService()!=null)out.add(rescueBody(parent,e.getValue().ownerService()));
        }
        return out;
    }

    double[] normalSeat(YukkuriEntity child, UUID excluded) {
        double r = radiusOf(child); Vec3 next = plannedSeat(child,null,false);
        if (next == null) return null;
        return new double[]{next.x, next.y, next.z, r};
    }

    boolean moveYoungSeat(YukkuriEntity child, double[] destination, boolean arc) {
        if(cargo.handling()||!cargoSeatClear(child,new Vec3(destination[0],destination[1],destination[2])))return false;
        Vec3 from = toLocal(child.position()); Vec3 to = new Vec3(destination[0], destination[1], destination[2]);
        var existing = seatState.residentSeat(child.getUUID());
        // An idempotent walking reservation is fine; a new practice hop must really launch a fresh arc.
        if (!arc && existing != null && Arrays.equals(existing, destination)) return true;
        if (arc && from.distanceToSqr(to)<=.01) return false;
        var route=arc||from.distanceToSqr(to)<=.01?List.<Vec3>of():walkingRoute(child,to);
        if(!arc&&from.distanceToSqr(to)>.01&&route.isEmpty())return false;
        if (!arc && !child.childhood().canJump() && hasStep(route)) return false;
        seatState.putResident(child.getUUID(), destination);
        if (from.distanceToSqr(to) > .01) {
            int ticks = arc ? HatVessel.solve(child.position(), toWorld(to), this, HatVessel.radius(child.getBbWidth()), HatVessel.HOP_TMIN)
                    : Math.max(16, (int)Math.ceil(HatSurfaceRoute.length(route) / .20));
            seatState.setMove(child.getUUID(), new SeatMove(from, walkStart(child), ticks, arc,route));
        } else seatState.clearMove(child.getUUID());
        return true;
    }

    /** Even a few millimetres of preparation clearance is traversed, never snapped into place. */
    boolean movePreparationCrawl(YukkuriEntity child, Vec3 destination) {
        return moveCheckedSeatRoute(child,destination,walkingRoute(child,destination),false);
    }
    boolean moveCheckedSeatRoute(YukkuriEntity child,Vec3 destination,List<Vec3> route,boolean allowSteps){
        if(cargo.handling()||!cargoSeatClear(child,destination))return false;
        var from = toLocal(child.position());
        if (route.size()<2 || !allowSteps&&hasStep(route)) return false;
        seatState.putResident(child.getUUID(), new double[]{destination.x,destination.y,destination.z,radiusOf(child)});
        seatState.setMove(child.getUUID(), new SeatMove(from, level().getGameTime(),
                Math.max(16,(int)Math.ceil(HatSurfaceRoute.length(route)/.20)), false, route));
        return true;
    }

    boolean ownerSeatBusy() {
        var parent = owner();
        var serviceSeat=seatState.ownerServiceSeat();
        return careSeats.ownerToiletSeat || changingOwnerSeat() || parent != null && serviceSeat != null && !serviceSeat.equals(ownerSeat(parent));
    }

    boolean moveOwnerSeat(YukkuriEntity parent, Vec3 destination, boolean arc) {
        if(cargo.handling())return false;
        Vec3 from = toLocal(parent.position());
        if (destination.equals(seatState.ownerServiceSeat())) return true;
        var route=arc||from.distanceToSqr(destination)<=.01?List.<Vec3>of():walkingRoute(parent,destination);
        if(!arc&&from.distanceToSqr(destination)>.01&&route.isEmpty()){
            if(cargo.innerCount()>0)cargo.moveAside(cargo.count());return false;
        }
        seatState.ownerServiceSeat(destination);
        if (from.distanceToSqr(destination) > .01) {
            int ticks = arc ? HatVessel.solve(parent.position(), toWorld(destination), this, HatVessel.radius(parent.getBbWidth()), HatVessel.HOP_TMIN)
                    : Math.max(16, (int)Math.ceil(HatSurfaceRoute.length(route) / .20));
            seatState.ownerSeatMove(new SeatMove(from, walkStart(parent), ticks, arc,route));
        } else seatState.clearOwnerSeatMove();
        return true;
    }

    /** Supported candidates only; the braid controller chooses one from its authored root/reach geometry. */
    public List<Vec3> rescueOwnerCandidates(YukkuriEntity parent){ return rescuePair.rescueOwnerCandidates(parent); }
    /** Joint replanning ignores this rescued child's previous incoming berth, never another occupant. */
    public List<Vec3> rescueOwnerCandidates(YukkuriEntity parent,YukkuriEntity child){ return rescuePair.rescueOwnerCandidates(parent, child); }
    List<Vec3> supportedRescueOwnerCandidates(YukkuriEntity parent,List<double[]> obstacles){ return rescuePair.supportedRescueOwnerCandidates(parent, obstacles); }
    /** Read-only supported well and inner-ledge seats. The parent is placed jointly instead of frozen at her stern. */
    public List<Vec3> rescueYoungCandidates(YukkuriEntity child){ return rescuePair.rescueYoungCandidates(child); }
    double[] rescueBody(YukkuriEntity rider,Vec3 at){
        var shape=HatVessel.bodySpace(rider,hullScale(),unit());
        return new double[]{at.x,at.y,at.z,radiusOf(rider),shape.height(),0,shape.hatX(),shape.hatZ(),shape.hatBase(),shape.hatHeight()};
    }
    /** Validate the actual two-body destination without changing a reservation or walking either actor. */
    public boolean canPlaceRescuePair(YukkuriEntity child,YukkuriEntity parent,Vec3 youngSeat,Vec3 parentStand){ return rescuePair.canPlaceRescuePair(child, parent, youngSeat, parentStand); }
    /** Atomically keep the chosen child berth only after both bodies and the parent's real walk are viable.
     *  The braid controller still walks to pickup first and to parentStand only after it has lifted the child. */
    public boolean reserveRescuePair(YukkuriEntity child,YukkuriEntity parent,Vec3 youngSeat,Vec3 parentStand){ return rescuePair.reserveRescuePair(child, parent, youngSeat, parentStand); }
    /** A real supported walk, never a jump or an interpolated extension of the braid. */
    public boolean moveRescueOwner(YukkuriEntity parent,Vec3 localSupported){ return rescuePair.moveRescueOwner(parent, localSupported); }
    /** Diagnostics only: why moveRescueOwner would refuse this stand (null when it would not). */
    public String rescueOwnerRefusal(YukkuriEntity parent,Vec3 localSupported){ return rescuePair.rescueOwnerRefusal(parent, localSupported); }
    /** Read-only LOCAL walking points for the braid controller's swept two-body custody plan. */
    public List<Vec3> rescueOwnerRoute(YukkuriEntity parent,Vec3 localStand,YukkuriEntity child){ return rescuePair.rescueOwnerRoute(parent, localStand, child); }
    public List<Vec3> rescueOwnerRoute(YukkuriEntity parent,Vec3 localStand,YukkuriEntity child,Vec3 youngSeat){ return rescuePair.rescueOwnerRoute(parent, localStand, child, youngSeat); }
    public boolean rescueOwnerReady(YukkuriEntity parent){ return rescuePair.rescueOwnerReady(parent); }
    public void releaseRescueOwner(YukkuriEntity parent){ rescuePair.releaseRescueOwner(parent); }

    private long walkStart(Entity rider){
        long start=level().getGameTime();
        start=Math.max(start,seatState.latestMoveEndExcept(rider.getUUID()));
        var ownerMove=seatState.ownerSeatMove();
        if(!rider.getUUID().equals(ownerYukkuri())&&ownerMove!=null)start=Math.max(start,ownerMove.start()+ownerMove.ticks());
        return start;
    }
    List<Vec3> walkingRoute(Entity rider, Vec3 destination) {
        var obstacles = takenExcept(rider.getUUID());
        if (!rider.getUUID().equals(ownerYukkuri())) {
            var parent = owner();
            if (parent != null && parent.getVehicle() == this) {
                Vec3 at = seatState.ownerServiceSeat() != null ? seatState.ownerServiceSeat() : toLocal(parent.position());
                obstacles.add(rescueBody(parent,at));
            }
        }
        return HatSurfaceRoute.find(mode(), toLocal(rider.position()), destination, radiusOf(rider), obstacles,rider instanceof YukkuriEntity y
                ?HatVessel.bodySpace(y,hullScale(),unit()):new HatVessel.BodySpace(rider.getBbHeight()/unit(),0,0,0,0));
    }

    /** Commit both reservations together; a failed parent route must not strand the child. */
    boolean moveCarePair(YukkuriEntity child, YukkuriEntity parent, double[] destination, Vec3 ownerDestination) { return careSeats.moveCarePair(child, parent, destination, ownerDestination); }
    static boolean hasStep(List<Vec3> path) {
        for (int i=1;i<path.size();i++) if (Math.abs(path.get(i).y-path.get(i-1).y)>.01) return true;
        return false;
    }






    /** Only this named contact target uses the measured tucked pose; all other riders retain full obstacles. */
    List<Vec3> pickupRoute(YukkuriEntity parent,YukkuriEntity child,Vec3 target,Vec3 childAt,HatCareGeometry.Context geometry){ return carry.pickupRoute(parent, child, target, childAt, geometry); }
    boolean posedRouteClear(List<Vec3> path,YukkuriEntity parent,Vec3 childAt,Vec3 offset,
                                    HatCareGeometry.Context geometry,List<double[]> obstacles){ return carry.posedRouteClear(path, parent, childAt, offset, geometry, obstacles); }
    boolean carryTransferClear(YukkuriEntity child,Vec3 from,Vec3 to,Vec3 parentAt,HatCareGeometry.Context geometry,
            List<double[]> obstacles){ return carry.carryTransferClear(child, from, to, parentAt, geometry, obstacles); }
    List<Vec3> carriedRoute(YukkuriEntity parent,YukkuriEntity child,Vec3 from,Vec3 to,Vec3 offset,
            HatCareGeometry.Context geometry){ return carry.carriedRoute(parent, child, from, to, offset, geometry); }
    /** Find a supported pickup orientation, including the narrow centre aisle, before committing custody. */
    CarryPickup carryPickup(YukkuriEntity child,YukkuriEntity parent,Vec3 from,Vec3 to,Vec3 ownerDestination,
                                    CarrySearch cache) { return carry.carryPickup(child, parent, from, to, ownerDestination, cache); }
    CarryPickup carryPickupAt(YukkuriEntity child,YukkuriEntity parent,Vec3 from,Vec3 to,Vec3 ownerDestination,
                                      CarrySearch cache){ return carry.carryPickupAt(child, parent, from, to, ownerDestination, cache); }

    boolean beginCarry(YukkuriEntity child,YukkuriEntity parent,double[] destination,Vec3 ownerDestination) { return carry.beginCarry(child, parent, destination, ownerDestination); }
    boolean beginCarry(YukkuriEntity child,YukkuriEntity parent,double[] destination,Vec3 ownerDestination,CarrySearch search) { return carry.beginCarry(child,
            parent, destination, ownerDestination, search); }
    boolean beginReturnCarry(YukkuriEntity child,YukkuriEntity parent,Vec3 target){ return carry.beginReturnCarry(child, parent, target); }
    void setCarryWalk(YukkuriEntity parent,Vec3 target,List<Vec3> path){ carry.setCarryWalk(parent, target, path); }

    void tickCarry() { carry.tickCarry(); }

    /** Presentation follows the same synchronized preparation and release clock as physical custody. */
    public String carryActivity(Entity rider){ return carry.carryActivity(rider); }
    public float carrySeconds(float partial){ return carry.carrySeconds(partial); }
    public boolean carryingInHat(Entity rider) { return carry.carryingInHat(rider); }
    public boolean transportingInHat(Entity rider) { return carry.transportingInHat(rider); }
    public UUID hatCarrier(YukkuriEntity child) { return carry.hatCarrier(child); }
    public Vec3 hatCarryAnchor(YukkuriEntity child) { return carry.hatCarryAnchor(child); }
    Vec3 ownerCarryLocal(YukkuriEntity parent) { return carry.ownerCarryLocal(parent); }
    Vec3 carriedLocal(CarryTrip trip) { return carry.carriedLocal(trip); }
    static void point(CompoundTag tag,String key,Vec3 point) {
        tag.putDouble(key+"X",point.x);tag.putDouble(key+"Y",point.y);tag.putDouble(key+"Z",point.z);
    }
    static Vec3 point(CompoundTag tag,String key) {
        return new Vec3(tag.getDouble(key+"X"),tag.getDouble(key+"Y"),tag.getDouble(key+"Z"));
    }
    CompoundTag saveCarry(CarryTrip trip) { return carry.saveCarry(trip); }
    CarryTrip carryPose() { return carry.carryPose(); }

    Vec3 escortPlace(YukkuriEntity parent,YukkuriEntity child,Vec3 brim){ return carry.escortPlace(parent, child, brim); }
    public boolean walkingInHat(Entity rider){
        var move=rider.getUUID().equals(ownerYukkuri())&&rider instanceof YukkuriEntity y?ownerServiceMove(y):seatMove(rider);
        return move!=null&&!move.arc()&&!move.route().isEmpty()&&level().getGameTime()>=move.start()&&level().getGameTime()<move.start()+move.ticks();
    }
    Vec3 walkingLocal(Entity rider,SeatMove movement,double time){
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
            float yaw=carry.carryTrip!=null && carry.carryTrip.phase>0 && rider.getUUID().equals(ownerYukkuri())
                    ?SurfaceApproach.yaw(Vec3.ZERO,carry.carryTrip.facing.yRot(turn()))
                    :rider.getUUID().equals(departure.departureYielding)?departure.departureYieldYaw
                    :SurfaceApproach.yaw(rider.position(),at);
            SurfaceApproach.face(y,yaw);
        }
        return at;
    }

    Vec3 ownerServiceSeat(YukkuriEntity owner) {
        if (!level().isClientSide) return seatState.ownerServiceSeat();
        UUID id = owner.getUUID();
        for (var raw : entityData.get(SEATS).getList("S", Tag.TAG_COMPOUND)) if (raw instanceof CompoundTag c
                && c.getBoolean("OwnerService") && c.hasUUID("Id") && c.getUUID("Id").equals(id))
            return new Vec3(c.getDouble("X"), c.getDouble("Y"), c.getDouble("Z"));
        return null;
    }

    SeatMove ownerServiceMove(YukkuriEntity owner) {
        if (!level().isClientSide) return seatState.ownerSeatMove();
        UUID id = owner.getUUID();
        for (var raw : entityData.get(SEATS).getList("S", Tag.TAG_COMPOUND)) if (raw instanceof CompoundTag c
                && c.getBoolean("OwnerService") && c.hasUUID("Id") && c.getUUID("Id").equals(id) && c.contains("At"))
            return new SeatMove(new Vec3(c.getDouble("FromX"), c.getDouble("FromY"), c.getDouble("FromZ")), c.getLong("At"), c.getInt("Ticks"), c.getBoolean("Arc"),HatSurfaceRoute.load(c));
        return null;
    }
    public boolean onBrim(Entity young){var s=seatOf(young);return isAfloat()&&s!=null&&Math.abs(s.y-HatVessel.freeboard(mode()))<1e-5&&!changingSeat(young);}
    private boolean brimSafe(Entity rider) {
        var captain=owner();
        return rider instanceof YukkuriEntity y && !y.family().adult()
                // Untrained newborns need a level cabin tread for real crawling and hop practice.
                &&y.childhood().canJump()
                && isAfloat() && flooding==0  && !hasIncoming() && !level().isNight() && !level().isRainingAt(blockPosition())
                && captain!=null && !captain.isPanicking() && !captain.family().laboring() && !captain.life().isSleeping()
                && !captain.aquaticLife().care().active() && !y.isPanicking() && !y.life().isSleeping()
                && !y.isNoAi() && !y.isCommandPlaying() && !y.operations().active() && !crowding.emergency(y.getUUID())
                && y.life().mealTicks()==0 && y.life().energy()>25 && y.life().nutrition()>=60;
    }
    /** Server: young aboard without a place (after loading) get one; afloat, at dusk and dawn they all move (before the
     *  owner by day, gathered near her lower front at night). */
    private void tendSeats() {
        if(cargo.handling())return;
        if(rescuePair.rescueOwnerHeld){
            var parent=owner();
            if(parent==null||parent.getVehicle()!=this){
                rescuePair.rescueOwnerHeld=false;seatState.clearOwnerServiceSeat();seatState.clearOwnerSeatMove();syncSeats();
            }else if(!parent.isAlive()||!parent.family().braidRescueActive()&&!parent.family().cradlePickup().active())releaseRescueOwner(parent);
        }
        if (carry.carryTrip != null||rescuePair.rescueOwnerHeld) return;
        // Large juveniles need the scarce wide spaces first. Small siblings then fit around them or inside.
        var young = youngPassengers().stream().sorted(Comparator.comparingDouble(this::radiusOf).reversed()).toList();
        // Night, fatigue and growth can make a previously safe brim berth unsuitable. Clear real food first.
        for(var rider:young)if(rider instanceof YukkuriEntity child&&plannedSeat(child,null,false)==null){
            prepareCargoSpace(child,false);if(cargo.handling())return;
        }
        boolean resized = young.stream().anyMatch(p -> { var seat=seatState.residentSeat(p.getUUID()); return seat != null && Math.abs(seat[3]-radiusOf(p))>.001; });
        if (resized && !careSeats.serviceSeats.isEmpty()) {
            for (var id : List.copyOf(careSeats.serviceSeats.keySet())) releaseCareSeat(id);
        }
        careSeats.serviceSeats.keySet().removeIf(id -> young.stream().noneMatch(p -> p.getUUID().equals(id)));
        returnCareSeats();
        if(carry.carryTrip!=null)return;
        // A single departure yield owns its existing seats until both actual feet have settled.
        // Do not let routine layout/growth replanning replace that finite walk's destination.
        if(departure.departureYielding!=null)return;
        var captain=getControllingPassenger();
        String wanted=region()+":"+hullScale()+":"+cargo.tier()+":"+cargo.asideTier()+":"+(captain==null?"none":radiusOf(captain)+":"+captain.getBbHeight())
                +young.stream().map(p->p.getUUID()+":"+brimSafe(p)+":"+(p instanceof YukkuriEntity y?HatVessel.bodySpace(y,hullScale(),unit()):p.getBbHeight())).toList();
        boolean moved = resized || !wanted.equals(seatRegion) && !catches(); seatRegion = wanted;
        var old=seatState.residentSnapshot();
        // Growth may replan a destination, but never discard a live movement midway through it.
        if (moved) seatState.clearResidents();
        if (departure.voyageBerth != null && young.stream().anyMatch(p -> p.getUUID().equals(departure.voyageYoung)))
            seatState.putResidentIfAbsent(departure.voyageYoung,old.getOrDefault(departure.voyageYoung,departure.voyageBerth));
        if (moved) for (var p : young) {
            var service = careSeats.serviceSeats.get(p.getUUID());
            if (service != null) seatState.putResident(p.getUUID(), service.service());
            else if ((careSeats.returningSeats.containsKey(p.getUUID()) || changingSeat(p)) && old.containsKey(p.getUUID())) seatState.putResident(p.getUUID(), old.get(p.getUUID()));
        }
        boolean changed = moved;
        for (var p : young) if (!seatState.hasResident(p.getUUID())) {
            double r = radiusOf(p); var s = p instanceof YukkuriEntity y?plannedSeat(y,null,false):null;
            if (s == null) {
                cramped.add(p.getUUID());var previous=old.get(p.getUUID());
                if(previous!=null)seatState.putResident(p.getUUID(),previous);
                else {var at=toLocal(p.position());seatState.putResident(p.getUUID(),new double[]{at.x,at.y,at.z,r});}
                continue;
            }
            cramped.remove(p.getUUID());
            var before=old.get(p.getUUID());
            if(before!=null&&p instanceof YukkuriEntity y){
                seatState.putResident(p.getUUID(),before);
                double[] destination=new double[]{s.x,s.y,s.z,r};
                if(!moveYoungSeat(y,destination,false)){
                    if(cargo.innerCount()>0){cargo.moveAside(cargo.count());if(cargo.handling()){seatRegion="";return;}}
                    var parent=owner();
                    if(!y.childhood().canJump()&&parent!=null&&careSeats.serviceSeats.isEmpty()&&careSeats.returningSeats.isEmpty()){
                        // The carry planner must still see siblings whose new day/night berth has not
                        // been chosen yet. They stay in their real old seats throughout this transfer.
                        var restored=new ArrayList<UUID>();
                        for(var entry:old.entrySet())if(!seatState.hasResident(entry.getKey())){
                            seatState.putResident(entry.getKey(),entry.getValue());restored.add(entry.getKey());
                        }
                        if(beginReturnCarry(y,parent,new Vec3(destination[0],destination[1],destination[2]))){seatRegion="";return;}
                        for(var id:restored)seatState.removeResident(id);
                    }
                    seatRegion="";continue;
                }
            }
            seatState.putResident(p.getUUID(), new double[]{s.x, s.y, s.z, r}); changed = true;
        }
        if (changed) syncSeats();
        cramped.removeIf(id->young.stream().noneMatch(p->p.getUUID().equals(id)));
        if("night".equals(region())&&getControllingPassenger() instanceof YukkuriEntity parent){
            for(var p:young)if(p instanceof YukkuriEntity y&&y.life().isSleeping()&&!changingSeat(y)
                    &&!careSeats.serviceSeats.containsKey(y.getUUID())&&!careSeats.returningSeats.containsKey(y.getUUID())){
                float yaw=SurfaceApproach.yaw(parent.position(),y.position());
                SurfaceApproach.face(y,yaw);
            }
        }
    }
    /** Places of the young aboard taken in boarding order: only until the server's places arrive. */
    private List<Vec3> youngSeats() {
        var young = youngPassengers(); var key = new StringBuilder(region()).append(mode()).append(hullScale());
        for (var p : young) key.append(p.getId()).append(':').append(p.getBbWidth());
        if (!key.toString().equals(seatKey)) { seatKey = key.toString(); seatCache = HatVessel.pack(mode(), young.stream().mapToDouble(this::radiusOf).toArray(), region()); }
        return seatCache;
    }
    /** A place for this young (nearest prefer, hat units; null for the usual one) that it can reach from where it is
     *  without crossing the others, kept for it until it boards. */
    public Vec3 reserve(YukkuriEntity child, Vec3 prefer) {
        if(child==null||!young(child)||!hasCapacityFor(child))return null;
        if(cargo.handling())return null;
        var have = seatState.incomingSeat(child.getUUID());
        if(have!=null&&!cargoSeatClear(child,new Vec3(have[0],have[1],have[2]))){seatState.releaseIncoming(child.getUUID());have=null;}
        if (have == null && child.getUUID().equals(departure.voyageYoung) && departure.voyageBerth != null
                &&cargoSeatClear(child,new Vec3(departure.voyageBerth[0],departure.voyageBerth[1],departure.voyageBerth[2]))) { have=departure.voyageBerth.clone();seatState.reserveIncoming(child.getUUID(),have); }
        if (have == null) { double r = radiusOf(child); var s = plannedSeat(child,prefer,false); if(s==null)return null;have = new double[]{s.x, s.y, s.z, r};seatState.reserveIncoming(child.getUUID(),have); }
        return new Vec3(have[0], have[1], have[2]);
    }
    /** The place a young would get next, without keeping it. */
    public Vec3 peekSeat(YukkuriEntity child) {
        if(child==null||!young(child))return null;
        var have = seatState.incomingSeat(child.getUUID());
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
        var others = new ArrayList<double[]>();
        for (var adult : level().getEntitiesOfClass(YukkuriEntity.class, getBoundingBox().inflate(4), y -> y.family().adult()))
            others.add(new double[]{adult.getX(), adult.getZ(), adult.getBbWidth()*.5});
        if (others.stream().noneMatch(o -> o[0] == taker.getX() && o[1] == taker.getZ())) others.add(new double[]{taker.getX(), taker.getZ(), taker.getBbWidth()*.5});
        var bank = taker.position().subtract(position());
        var spots = HatVessel.outSpots(this, young.stream().mapToDouble(y -> HatVessel.radius(y.getBbWidth())).toArray(), others,
                isMoored() && bank.horizontalDistanceSqr() > 1e-6 ? new Vec3(bank.x, 0, bank.z).normalize() : null);
        var froms = young.stream().map(Entity::position).toList(); var order = HatVessel.match(froms, spots);
        var turn = new ArrayList<Integer>(); for (int i = 0; i < young.size(); i++) turn.add(i);
        turn.sort(Comparator.comparingDouble(i -> froms.get(i).distanceToSqr(spots.get(order[i]))));
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
        var hit = level().clip(new ClipContext(spot.add(0, 1.2, 0), spot.add(0, -1.5, 0),
                ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, child));
        if (hit.getType() != HitResult.Type.BLOCK || !level().getFluidState(hit.getBlockPos()).isEmpty()) return null;
        var at = hit.getLocation();
        return level().noCollision(child, child.getBoundingBox().move(at.subtract(child.position()))) ? at : null;
    }
    /** Nearest standable block beside water within a few blocks, or null (see {@link ShoreBerth#nearestBank}). */
    public BlockPos nearestBank() { return ShoreBerth.nearestBank(level(), blockPosition()); }
    @Override public LivingEntity getControllingPassenger() {
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
    /** Reclaim the same empty hat after its young is safe aboard a parent, including a rescued capsize. */
    public boolean retrieveAfloat(YukkuriEntity child, HatBoatEntity home) { return HatBoatLaunch.retrieveAfloat(this, child, home); }
    /** {@code reachFallback} lifts the rim-contact requirement for an empty hat still within a short, bounded distance. */
    public boolean retrieveAfloat(YukkuriEntity child, HatBoatEntity home, boolean reachFallback) { return HatBoatLaunch.retrieveAfloat(this, child, home, reachFallback); }
    /** Same finite physical contact for a floating or stranded empty hat; ownership is not remote reach. */
    public Vec3 retrievalGrip(YukkuriEntity actor){ return HatBoatLaunch.retrievalGrip(this, actor); }
    /** Parent-centre docking target with actual hull/AABB clearance; it grants no remote retrieval. */
    public Vec3 retrievalApproach(HatBoatEntity parentHat,YukkuriEntity child){ return HatBoatLaunch.retrievalApproach(this, parentHat, child); }
    double boxReach(Vec3 toward){ return HatBoatLaunch.boxReach(this, toward); }
    /** Put the hat back on: the young climb out first, onto the bank beside a floated hat. */
    public boolean retrieve(YukkuriEntity owner) { return HatBoatLaunch.retrieve(this, owner); }
    public boolean board(YukkuriEntity owner) { return HatBoatLaunch.board(this, owner); }
    public boolean ownsPhysicalHat(YukkuriEntity actor){ return capsizeMotion.ownsPhysicalHat(actor); }
    static final class GripRim {
        static List<Vec3> points(HatVessel.Mode mode){
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
            var mode=mode();var brim=HatVessel.brim(mode);double freeboard=HatVessel.freeboard(mode);
            if(sign==0||Math.abs(fixed)>(alongZ?brim[0]:brim[1]))continue;
            double edge=HatVessel.extent(mode,alongZ?fixed:0,alongZ?0:fixed,
                    alongZ?0:sign,alongZ?sign:0,0)-.125;
            if(edge<=0)continue;
            var at=new Vec3(alongZ?fixed:sign*edge,freeboard,alongZ?sign*edge:fixed);
            double surface=HatVessel.surface(mode,at.x,at.z);
            if(!Double.isFinite(surface)||Math.abs(surface-freeboard)>1e-5)continue;
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
    public Vec3 reserveOwnBoardingSeat(YukkuriEntity actor,Vec3 actualFrom,Predicate<Vec3> reachable){
        if(level().isClientSide||!ownsPhysicalHat(actor)||actor.getVehicle()==this||!isAfloat()||unsteadyMotion()||actualFrom==null)return null;
        if(actor.isInWaterOrBubble()&&cargo.count()>0)cargo.jettison();
        var obstacles=takenExcept(actor.getUUID());double radius=radiusOf(actor);var body=HatVessel.bodySpace(actor,hullScale(),unit());
        var brim=HatVessel.brim(mode());
        Vec3 best=null;double cost=Double.POSITIVE_INFINITY;
        for(double x=-brim[0]+radius;x<=brim[0]-radius;x+=.25)
            for(double z=-brim[1]+radius;z<=brim[1]-radius;z+=.25){
                double y=HatVessel.footprint(mode(),x,z,radius);if(!Double.isFinite(y))continue;
                var at=new Vec3(x,y,z);if(!HatVessel.fits(mode(),at,radius,obstacles,body))continue;
                double distance=toWorld(at).distanceToSqr(actualFrom);if(distance<cost&&reachable.test(at)){cost=distance;best=at;}
            }
        if(best!=null){seatState.ownerServiceSeat(best);seatState.clearOwnerSeatMove();boardingEdgeUntil=level().getGameTime()+80;syncSeats();}
        return best;
    }
    public void releaseOwnBoardingSeat(YukkuriEntity actor){
        if(level().isClientSide||!ownsPhysicalHat(actor))return;
        boardingEdgeUntil=0;
        if(actor.getVehicle()!=this){seatState.clearOwnerServiceSeat();seatState.clearOwnerSeatMove();}
        else moveOwnerSeat(actor,ownerSeat(actor),false);
        syncSeats();
    }
    /** The place a returning pupil takes in this hull: the berth kept for it while its own hat was away, else a close brim place. */
    public Vec3 returnSeat(YukkuriEntity child,boolean emergency){
        if(level().isClientSide||child==null||!young(child)||!hasCapacityFor(child)||!isAfloat()||unsteadyMotion())return null;
        var berth=voyageBerth(child);
        if(berth!=null&&cargoSeatClear(child,berth)){
            if(!seatState.hasIncoming(child.getUUID()))seatState.reserveIncoming(child.getUUID(),new double[]{berth.x,berth.y,berth.z,radiusOf(child)});
            syncSeats();return berth;
        }
        return reserveBoardingSeat(child,child.position(),emergency);
    }
    /** Choose a real close landing, instead of reusing a far retained berth after jump limits tighten. */
    public Vec3 reserveBoardingSeat(YukkuriEntity child,Vec3 actualFrom,boolean emergency){
        return reserveBoardingSeat(child,actualFrom,emergency,false);
    }
    /** A physical braid lift must reserve the same fully supported top brim accepted by boardRescuedYoung. */
    public Vec3 reserveRescueBrim(YukkuriEntity child,Vec3 actualFrom){
        return reserveBoardingSeat(child,actualFrom,false,true);
    }
    private Vec3 reserveBoardingSeat(YukkuriEntity child,Vec3 actualFrom,boolean emergency,boolean topBrim){
        if(level().isClientSide||cargo.handling()||landing()||child==null||actualFrom==null||!admits(child)||!isAfloat()||unsteadyMotion()
                ||!hasCapacityFor(child))return null;
        if(emergency&&young(child)&&!requestEmergencyBerth(child))return null;
        var obstacles=takenExcept(child.getUUID());var parent=owner();
        if(parent!=null&&parent.getVehicle()==this)obstacles.add(rescueBody(parent,toLocal(parent.position())));
        var body=HatVessel.bodySpace(child,hullScale(),unit());double radius=radiusOf(child);Vec3 best=null;double cost=Double.POSITIVE_INFINITY;
        var brim=HatVessel.brim(mode());
        for(double x=-brim[0]+radius;x<=brim[0]-radius;x+=.25)
            for(double z=-brim[1]+radius;z<=brim[1]-radius;z+=.25){
                double y=HatVessel.footprint(mode(),x,z,radius);if(!Double.isFinite(y)||Math.abs(HatVessel.surface(mode(),x,z)-y)>1e-4
                        ||!cargoSeatClear(child,new Vec3(x,y,z)))continue;
                if(topBrim&&!HatVessel.supported(mode(),x,z,radius,HatVessel.top(mode())))continue;
                var at=new Vec3(x,y,z);if(!HatVessel.fits(mode(),at,radius,obstacles,body))continue;
                double distance=toWorld(at).distanceToSqr(actualFrom);if(distance<cost){cost=distance;best=at;}
            }
        if(best==null&&emergency)best=HatVessel.refugeBrimPlace(mode(),radius,obstacles,toLocal(actualFrom),true);
        if(best!=null){seatState.reserveIncoming(child.getUUID(),new double[]{best.x,best.y,best.z,radius});syncSeats();}
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
        var candidates=new ArrayList<Vec3>();double radius=radiusOf(rider);var body=HatVessel.bodySpace(rider,hullScale(),unit());
        var brim=HatVessel.brim(mode());
        for(double x=-brim[0]+radius;x<=brim[0]-radius;x+=.4)
            for(double z=-brim[1]+radius;z<=brim[1]-radius;z+=.4){
                double y=HatVessel.footprint(mode(),x,z,radius);if(!Double.isFinite(y))continue;
                var at=new Vec3(x,y,z);if(HatVessel.fits(mode(),at,radius,obstacles,body))candidates.add(at);
            }
        candidates.sort(Comparator.comparingDouble(at->toWorld(at).distanceToSqr(towardWorld)));
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
    public List<Vec3> brimSeatsToward(YukkuriEntity child,Vec3 towardWorld,int limit){
        if(level().isClientSide||child==null||towardWorld==null||!young(child)||!isAfloat()||unsteadyMotion()||!hasPassenger(child))return List.of();
        var obstacles=takenExcept(child.getUUID());var parent=owner();
        if(parent!=null&&parent.getVehicle()==this)obstacles.add(rescueBody(parent,toLocal(parent.position())));
        double radius=radiusOf(child);var body=HatVessel.bodySpace(child,hullScale(),unit());
        var mode=mode();var brim=HatVessel.brim(mode);double top=HatVessel.top(mode);
        var found=new ArrayList<Vec3>();
        for(double x=-brim[0]+radius;x<=brim[0]-radius;x+=.25)
            for(double z=-brim[1]+radius;z<=brim[1]-radius;z+=.25){
                if(!HatVessel.supported(mode,x,z,radius+.1,top))continue;
                var at=new Vec3(x,top,z);
                if(HatVessel.fits(mode(),at,radius,obstacles,body))found.add(at);
            }
        found.sort(Comparator.comparingDouble(at->toWorld(at).distanceToSqr(towardWorld)));
        return found.size()>limit?List.copyOf(found.subList(0,limit)):found;
    }
    /** Keep a place for a young whose lift is under way (it is no longer a rider, but must find its place again). */
    public boolean holdIncoming(YukkuriEntity child,Vec3 seatLocal){
        if(level().isClientSide||child==null||seatLocal==null||!admits(child)||!hasCapacityFor(child)||!isAfloat())return false;
        if(!cargoSeatClear(child,seatLocal)||cargo.handling())return false;
        var obstacles=takenExcept(child.getUUID());var parent=owner();
        if(parent!=null&&parent.getVehicle()==this)obstacles.add(rescueBody(parent,toLocal(parent.position())));
        if(!HatVessel.fits(mode(),seatLocal,radiusOf(child),obstacles,HatVessel.bodySpace(child,hullScale(),unit())))return false;
        seatState.reserveIncoming(child.getUUID(),new double[]{seatLocal.x,seatLocal.y,seatLocal.z,radiusOf(child)});syncSeats();return true;
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
    public static HatBoatEntity deployForSelfRescue(YukkuriEntity actor,SelfRescueDeployment plan,SelfRescueHatGeometry.Pose heldPose,
            float elapsed){ return HatBoatCapsize.deployForSelfRescue(actor, plan, heldPose, elapsed); }
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
            if(p.getUUID().equals(ownerYukkuri()))seatState.ownerServiceSeat(at);
            else seatState.putResident(p.getUUID(),new double[]{at.x,at.y,at.z,radiusOf(p)});
            if(p instanceof YukkuriEntity y){y.getNavigation().stop();if (!y.family().adult()) y.mind().felt(EmotionEvent.FRIGHTENED); y.showReaction(y.family()
                    .adult()?"surprise":"teary",y.family().adult()?"wait_family":"afraid",60);}
        }
        seatState.clearMoves();seatState.clearOwnerSeatMove();carry.carryTrip=null;carry.recoverCarry=false;
        careSeats.serviceSeats.clear();careSeats.returningSeats.clear();careSeats.ownerToiletSeat=false;leaving.clear();syncSeats();
    }

    public YukkuriEntity captain() { return owner(); }
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
    /** Return the one accessory only after everyone has left and the captain is on dry land at the hat; the hat then leaves the water. */
    boolean finishLanding(YukkuriEntity owner,UUID rescuer){
        if(isVehicle()||hasIncoming()||owner.isPassenger()||!owner.isAlive()||!owner.getUUID().equals(ownerYukkuri())||state()!=State.AFLOAT
                ||!owner.life().returnAccessory(ownerYukkuri(),accessoryToken,rescuer))return false;
        crew.clear();cargo.unload(owner,accessoryToken);owner.aquaticLife().landed();owner.subspecies().boatId(null);
        accessoryToken=null;state(State.RESCUED);helm.stop("retired");return true;
    }
    @Override public InteractionResult interact(Player player, InteractionHand hand) {
        if (hand != InteractionHand.MAIN_HAND || player.distanceToSqr(this) > 36) return InteractionResult.PASS;
        var held = player.getItemInHand(hand);
        if (catches()) {
            if (!held.isEmpty()) return InteractionResult.PASS;
            if (!level().isClientSide) {
                var keeper=owner();
                player.displayClientMessage(Component.literal("接生帽：幼体 "+youngPassengers().size()+"；"
                        +(keeper==null?"帽主未在加载范围内":keeper.family().keepsCradle()?"正在接生或安置幼体":"等待帽主接回")),true);
            }
            return InteractionResult.sidedSuccess(level().isClientSide);
        }
        if (held.is(YukkuriEntity.FOOD)) {
            if (!level().isClientSide) storePlayerFood(player,held);
            return InteractionResult.sidedSuccess(level().isClientSide);
        }
        if (level().isClientSide) return InteractionResult.SUCCESS;
        // Empty hands and non-food items only shove the hull; they never issue a behavior request.
        shake(player,1);
        return InteractionResult.CONSUME;
    }
    private void storePlayerFood(Player player,ItemStack held){
        var y=owner();
        String reason=!isAfloat()?"not_afloat":y==null?"owner_unavailable":!y.life().acceptsFood(held)?"not_accepted":null;
        if(reason==null&&!cargo.canStore(held))reason=cargo.remainingCapacity()<=0||cargoRoom()<=0?"full":"slots";
        if(reason!=null){refusePlayerFood(player,held,reason);return;}
        cargo.store(held,player.isShiftKeyDown()?8:1,player.getAbilities().instabuild);
    }
    private void refusePlayerFood(Player player,ItemStack held,String reason){
        var message="full".equals(reason)
                ?Component.translatable("interaction.mcyukkuri.hat_cargo.full",capacityUsed(),HatBoatCargo.CAPACITY)
                :Component.translatable("interaction.mcyukkuri.hat_cargo."+reason);
        player.displayClientMessage(message,true);
        GameplayLog.limited(this,"cargo","player_store."+reason,200,
                "player food storage refused player={} owner={} item={} reason={} used={}/{} cargoRoom={} paddle={}",
                player.getUUID(),ownerYukkuri(),held.getItem(),reason,capacityUsed(),HatBoatCargo.CAPACITY,cargoRoom(),cargo.paddle());
    }
    @Override public boolean hurt(DamageSource source, float amount) {
        if (isInvulnerableTo(source) || level().isClientSide || amount <= 0 || isRemoved()) return false;
        if (loss.fireHit(source, amount)) { markHurt(); return true; }
        if (catches()) return false;
        var attacker=source.getDirectEntity();
        // A bare-handed punch or stick poke shakes the hat harder than a hand shove; only a real blow turns it over.
        if(attacker instanceof Player puncher&&amount<4&&isAfloat()){shake(puncher,2);return true;}
        if(attacker!=null)startCapsize(toLocal(attacker.position()).x>=0?-1:1);else capsize();
        markHurt(); return true;
    }
    @Override public void remove(RemovalReason reason) {
        if (!level().isClientSide && reason == RemovalReason.KILLED && loss != null) loss.destroy("destroyed");
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
        loss.tick();
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
        if (tickCount % 20 == 0 && level() instanceof ServerLevel server)  // a place kept for a young no longer coming
            seatState.purgeIncoming(id -> {
                if(!(server.getEntity(id) instanceof YukkuriEntity y)||!y.isAlive())return true;
                boolean crossing=y.family().crossingToHat(this);
                return y.isPassenger()&&!crossing||!crossing&&!y.family().flying()&&!y.family().braidRescueActive()&&!y.family().selfClimbActive()
                        &&!y.waterDistress().heldByWaterRescue()||y.distanceToSqr(this)>64;
            });
        if(tickCount%20==0&&level() instanceof ServerLevel server){
            long now=level().getGameTime();
            temporaryRefugeReservations.entrySet().removeIf(entry->entry.getValue()<=now);
            var caregiver=owner();
            temporaryCaregivers.entrySet().removeIf(entry->{
                var child=server.getEntity(entry.getKey()) instanceof YukkuriEntity y?y:null;
                // A wet child can still take the already granted berth after a fall, but the
                // authority never survives a dead/departed captain or a real shore hand-off.
                return caregiver==null||!caregiver.isAlive()||caregiver.getVehicle()!=this
                        ||child!=null&&(!child.isAlive()||child.getVehicle()!=this&&child.onGround()&&!child.isInWaterOrBubble());
            });
        }
        if (tickCount>60 && tickCount%20==0 && departure.voyageYoung!=null && level() instanceof ServerLevel server
                && (!(server.getEntity(departure.voyageYoung) instanceof YukkuriEntity y) || !y.isAlive() ||
                        !y.aquaticLife().firstVoyage().keepsBerth(this))) releaseVoyageBerth(departure.voyageYoung);
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
                var to = helm.mooringPull();
                var motion = new Vec3(to.x, lift, to.z); setDeltaMovement(motion); move(MoverType.SELF, motion);
            }
            for (var p : getPassengers()) if (p instanceof YukkuriEntity rider) { rider.getNavigation().stop(); rider.fallDistance = 0; }
            return;
        }
        var pos = BlockPos.containing(getX(),getY()-.12,getZ());
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
            double buoyancy = loss.sinking() ? Math.max(-.045,motion.y-.006)
                    : Math.clamp((surface + .025 - getY())*.35,-.12,.12);
            motion = new Vec3(motion.x*.88+flow.x*.016,buoyancy,motion.z*.88+flow.z*.016);
            motion = helm.steer(motion);
            if (state()!=State.CAPSIZED && (level().getFluidState(pos.above()).is(FluidTags.WATER) || flow.y<-.1)) {
                if (++flooding>=60) capsize();
            } else if (state()!=State.CAPSIZED) flooding=Math.max(0,flooding-1);
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
        loss.save(tag);
        if(ownerYukkuri()!=null)tag.putUUID("OwnerYukkuri",ownerYukkuri());
        if(accessoryToken!=null)tag.putUUID("AccessoryToken",accessoryToken);
        tag.putString("State",state().name());tag.putInt("Flooding",flooding);tag.putFloat("HullScale",hullScale());
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
            tag.putBoolean("CapsizeReleased",capsizeMotion.capsizeReleased);
            if(!capsizeMotion.capsizeReleased){
                var frozen=new ListTag();
                for(var p:getPassengers()){
                    var saved=new CompoundTag();saved.putUUID("Id",p.getUUID());point(saved,"Seat",toLocal(p.position()));
                    saved.putDouble("Radius",radiusOf(p));frozen.add(saved);
                }
                tag.put("CapsizeSeats",frozen);
            }
        }
        tag.putString("Role",role.name());if(helm.mooring()!=null){tag.putDouble("MooringX",helm.mooring().x);tag.putDouble("MooringZ",helm.mooring().z);}
        tag.put("Cargo", cargo.save());
        tag.put("Crowding",crowding.save());
        tag.put("ShoreLanding",landing.save());
        var residentSeats=new ListTag();
        for(var p:youngPassengers()){
            var saved=new CompoundTag();saved.putUUID("Id",p.getUUID());point(saved,"Seat",toLocal(p.position()));
            saved.putDouble("Radius",radiusOf(p));residentSeats.add(saved);
        }
        tag.put("ResidentSeats",residentSeats);
        if (carry.carryTrip!=null) {
            tag.put("CarryTrip",saveCarry(carry.carryTrip));
            var places=new ListTag();
            for(var passenger:youngPassengers()) {
                Vec3 at=toLocal(passenger.position());var saved=new CompoundTag();saved.putUUID("Id",passenger.getUUID());
                point(saved,"Seat",at);saved.putDouble("Radius",radiusOf(passenger));places.add(saved);
            }
            tag.put("CarryPassengerSeats",places);
        }
        if(departure.voyageYoung!=null&&departure.voyageBerth!=null){
            tag.putUUID("VoyageYoung",departure.voyageYoung);var berth=new CompoundTag();
            berth.putDouble("X",departure.voyageBerth[0]);berth.putDouble("Y",departure.voyageBerth[1]);berth.putDouble("Z",
                    departure.voyageBerth[2]);berth.putDouble("R",departure.voyageBerth[3]);tag.put("VoyageBerth",berth);
        }
        var young=new ListTag();for(var id:crew)young.add(NbtUtils.createUUID(id));tag.put("Crew",young);
        var temporaryCare=new ListTag();
        for(var entry:temporaryCaregivers.entrySet()){
            var saved=new CompoundTag();saved.putUUID("Child",entry.getKey());saved.putUUID("Caregiver",entry.getValue());temporaryCare.add(saved);
        }
        tag.put("TemporaryCare",temporaryCare);
    }
    @Override protected void readAdditionalSaveData(CompoundTag tag) {
        loss.load(tag);
        careSeats.serviceSeats.clear(); careSeats.returningSeats.clear(); careSeats.returnRetries.clear(); seatState.clearAll();
        temporaryCaregivers.clear();temporaryRefugeReservations.clear();
        carry.carryTrip = null; carry.recoverCarry=false;
        rescuePair.rescueOwnerHeld=false;
        careSeats.ownerToiletSeat = false; seatRegion = "";
        cramped.clear();crowding.load(tag.getCompound("Crowding"));
        landing.load(tag.getCompound("ShoreLanding"));
        for(var raw:tag.getList("ResidentSeats",Tag.TAG_COMPOUND)){
            var saved=(CompoundTag)raw;if(!saved.hasUUID("Id"))continue;
            var at=point(saved,"Seat");seatState.putResident(saved.getUUID("Id"),new double[]{at.x,at.y,at.z,saved.getDouble("Radius")});
        }
        entityData.set(OWNER,tag.hasUUID("OwnerYukkuri")?Optional.of(tag.getUUID("OwnerYukkuri")):Optional.empty());
        for(var raw:tag.getList("TemporaryCare",Tag.TAG_COMPOUND)){
            var saved=(CompoundTag)raw;
            if(saved.hasUUID("Child")&&saved.hasUUID("Caregiver")&&saved.getUUID("Caregiver").equals(ownerYukkuri()))
                temporaryCaregivers.put(saved.getUUID("Child"),saved.getUUID("Caregiver"));
        }
        accessoryToken=tag.hasUUID("AccessoryToken")?tag.getUUID("AccessoryToken"):null;
        try{state(State.valueOf(tag.getString("State")));}catch(IllegalArgumentException invalid){state(State.BEACHED);}
        flooding=Math.clamp(tag.getInt("Flooding"),0,100);entityData.set(SCALE,Math.clamp(tag.getFloat("HullScale"),.1f,2.5f));
        entityData.set(AQUATIC_HULL,!tag.contains("AquaticHull")||tag.getBoolean("AquaticHull"));
        entityData.set(ACCESSORY_STAGE,Math.clamp(tag.getInt("AccessoryStage"),0,FamilyLife.Stage.values().length-1));
        role="SELF".equals(tag.getString("Role"))?HatVessel.Role.SELF:HatVessel.Role.HELPER;
        cargo.load(tag.getCompound("Cargo")); syncCargo(cargo.save());
        var motion=new CompoundTag();motion.putInt("Side",tag.getInt("MotionSide")<0?-1:1);
        capsizeMotion.capsizeReleased=state()==State.CAPSIZED;
        if(state()==State.CAPSIZED&&tag.contains("CapsizeAge")){
            int age=Math.clamp(tag.getInt("CapsizeAge"),0,CAPSIZE_TICKS);
            motion.putLong("CapAt",level().getGameTime()-age);capsizeMotion.capsizeReleased=tag.getBoolean("CapsizeReleased");
            if(!capsizeMotion.capsizeReleased)for(var raw:tag.getList("CapsizeSeats",Tag.TAG_COMPOUND)){
                var saved=(CompoundTag)raw;if(!saved.hasUUID("Id"))continue;
                var at=point(saved,"Seat");
                if(saved.getUUID("Id").equals(ownerYukkuri()))seatState.ownerServiceSeat(at);
                else seatState.putResident(saved.getUUID("Id"),new double[]{at.x,at.y,at.z,saved.getDouble("Radius")});
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
        syncMotion(motion);syncSeats();
        if (tag.contains("CarryTrip")) {
            var c=tag.getCompound("CarryTrip");
            if (c.hasUUID("Child") && c.hasUUID("Holder") && c.getUUID("Holder").equals(ownerYukkuri())) {
                Vec3 original=point(c,"From"), now=point(c,"ChildNow"), ownerNow=point(c,"OwnerNow");
                int phase=Math.clamp(c.getInt("Phase"),-2,5);
                Vec3 offset=phase==3?now.subtract(ownerNow):point(c,"Offset");
                carry.carryTrip=new CarryTrip(c.getUUID("Child"),phase==1?now:original,point(c,"To"),point(c,"Pickup"),point(c,"OwnerTo"),offset,level().getGameTime());
                carry.carryTrip.lastAnchor=now;
                if(c.contains("FacingX"))carry.carryTrip.facing=point(c,"Facing");
                carry.carryTrip.phase=phase; carry.recoverCarry=phase==-2 || phase==0 || phase==2;
                if(phase==-1||phase==5)carry.carryTrip.at=level().getGameTime()-Math.clamp(c.getLong("Elapsed"),0,12);
                seatState.ownerServiceSeat(ownerNow);
                for(var raw:tag.getList("CarryPassengerSeats",Tag.TAG_COMPOUND)) {
                    var saved=(CompoundTag)raw;
                    if(saved.hasUUID("Id")) {
                        Vec3 seat=point(saved,"Seat");
                        seatState.putResident(saved.getUUID("Id"),new double[]{seat.x,seat.y,seat.z,saved.getDouble("Radius")});
                    }
                }
                // Loaded interactions earn no care rewards. Safely finish the owned transport, then restore normal seating.
                Vec3 returnTo=c.contains("ReturnX")?point(c,"Return"):original;
                careSeats.returningSeats.put(carry.carryTrip.child,new ServiceSeat(new double[]{returnTo.x,returnTo.y,returnTo.z,c.getDouble("ReturnRadius")},c.getString("ReturnRegion"),
                        new double[]{carry.carryTrip.destination.x,carry.carryTrip.destination.y,carry.carryTrip.destination.z,0},null,false,false));
                syncSeats();
            }
        }
        departure.voyageYoung=tag.hasUUID("VoyageYoung")?tag.getUUID("VoyageYoung"):null;departure.voyageBerth=null;
        if(departure.voyageYoung!=null&&tag.contains("VoyageBerth")){
            var berth=tag.getCompound("VoyageBerth");departure.voyageBerth=new double[]{berth.getDouble("X"),berth.getDouble("Y"),berth.getDouble("Z"),berth.getDouble("R")};
        }else departure.voyageYoung=null;
        helm.moor(tag.contains("MooringX")?new Vec3(tag.getDouble("MooringX"),0,tag.getDouble("MooringZ")):null);
        crew.clear();for(var raw:tag.getList("Crew",Tag.TAG_INT_ARRAY))crew.add(NbtUtils.loadUUID(raw));
    }
}
