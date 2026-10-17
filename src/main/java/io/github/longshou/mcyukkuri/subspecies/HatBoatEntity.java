package io.github.longshou.mcyukkuri.subspecies;

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
public final class HatBoatEntity extends Entity {
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
    /** One authoritative start time and direction; clients evaluate the same continuous curve. */
    private static final EntityDataAccessor<CompoundTag> MOTION = SynchedEntityData.defineId(HatBoatEntity.class, EntityDataSerializers.COMPOUND_TAG);
    /** Weight-shift dip toward one rim: From/To degrees, At game tick, Ticks ramp length and Yaw (see dipYaw). Never persisted. */
    private static final EntityDataAccessor<CompoundTag> DIP = SynchedEntityData.defineId(HatBoatEntity.class, EntityDataSerializers.COMPOUND_TAG);
    public static final int ROCK_TICKS=44, CAPSIZE_TICKS=16, CAPSIZE_RELEASE_TICK=9;
    public static final float MAX_DIP=5f;
    public static final int DIP_RENEW_TICKS=10;
    private long dipRenewed=Long.MIN_VALUE;
    /** A hypothetical or current tilt: lowered-rim direction yaw (hat-local, degrees) and its angle. */
    public record Dip(float degrees,float yaw){ public static final Dip NONE=new Dip(0,0); }
    private boolean capsizeReleased;
    private final HatBoatCargo cargo = new HatBoatCargo(this);
    private final java.util.ArrayDeque<Vec3> route = new java.util.ArrayDeque<>();
    private int heldTicks;
    private UUID accessoryToken;
    /** Who set the hat down to catch: a helper beside it, or the mother before her own outlet. */
    private HatVessel.Role role = HatVessel.Role.HELPER;
    private Vec3 mooring;
    private int flooding;
    private Vec3 pullTarget;
    private int pullTicks;
    private double lerpX, lerpY, lerpZ;
    private int lerpSteps;
    /** Young born into the hat (water birth); kept after a capsize so a rescue can bring them ashore. */
    private final java.util.LinkedHashSet<UUID> crew = new java.util.LinkedHashSet<>();
    /** Defensive search/save budget only. Actual room is measured from each child's body and the hat surfaces. */
    public static final int MAX_YOUNG = 16;
    private final HatBoatCrowding crowding=new HatBoatCrowding(this);
    private final java.util.Set<UUID> cramped=new java.util.HashSet<>();
    /** Server only: the places (hat units x, y, z and the young's radius) of the young aboard, and those kept for young
     *  on their way in (a newborn's flight, a bounce off the brim). */
    private final java.util.LinkedHashMap<UUID, double[]> seats = new java.util.LinkedHashMap<>(), incoming = new java.util.LinkedHashMap<>();
    private String seatRegion = "";
    /** A real short trip inside the hat.  Crawling uses a level path; hops retain the normal safe arc. */
    private record SeatMove(Vec3 from, long start, int ticks, boolean arc, java.util.List<Vec3> route) {
        SeatMove(Vec3 from,long start,int ticks,boolean arc){this(from,start,ticks,arc,java.util.List.of());}
    }
    private final java.util.Map<UUID,SeatMove> seatMoves = new java.util.HashMap<>();
    /** A care action may temporarily move a young and the seated owner, then restores the normal layout. */
    private record ServiceSeat(double[] original, String originalRegion, double[] service, Vec3 ownerService, boolean rearCare, boolean toiletReady) {}
    private final java.util.Map<UUID,ServiceSeat> serviceSeats = new java.util.HashMap<>();
    private final java.util.Map<UUID,ServiceSeat> returningSeats = new java.util.HashMap<>();
    /** 0 approach, 1 lift, 2 carry, 3 lower. The child remains a passenger with one explicit holder. */
    private static final class CarryTrip {
        final UUID child;
        final Vec3 from, destination, pickup, ownerDestination, offset;
        Vec3 facing;
        Vec3 lastAnchor;
        int phase, contact;
        long at;
        CarryTrip(UUID child, Vec3 from, Vec3 destination, Vec3 pickup, Vec3 ownerDestination, Vec3 offset, long at) {
            this.child=child; this.from=from; this.destination=destination; this.pickup=pickup;
            this.ownerDestination=ownerDestination; this.offset=offset; this.at=at;
            this.lastAnchor=from;
            this.facing=destination.subtract(ownerDestination).multiply(1,0,1).normalize();
        }
    }
    private CarryTrip carryTrip;
    private boolean recoverCarry;
    private Vec3 ownerServiceSeat;
    private boolean rescueOwnerHeld;
    private long boardingEdgeUntil;
    private SeatMove ownerSeatMove;
    /** The owner also uses a supported rear brim seat for ordinary on-water hygiene. */
    private boolean ownerToiletSeat;
    /** One pupil at a time; its berth remains occupied while its own hat is away. */
    private UUID voyageYoung;
    private double[] voyageBerth;
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
    /** One supported envelope per real stored portion, shared by storage display and interior routing. */
    public java.util.List<HatVessel.CargoBerth> cargoBerths() { return HatVessel.cargoBerths(mode(),cargo.count()); }
    /** Additional contiguous storage slots whose real envelopes are clear now, including reserved arrivals. */
    public int cargoRoom(){
        int room=0;
        var all=HatVessel.cargoBerths(mode(),HatBoatCargo.CAPACITY);
        for(int i=cargo.count();i<all.size();i++){
            var berth=all.get(i);var at=berth.position();boolean blocked=false;
            for(var rider:getPassengers()){
                var p=toLocal(rider.position());
                if(p.y<at.y+berth.radius()*2&&p.y+rider.getBbHeight()/unit()>at.y
                        &&p.subtract(at).horizontalDistance()<radiusOf(rider)+berth.radius()){blocked=true;break;}
            }
            if(!blocked)for(var held:incoming.values())if(held[1]<at.y+berth.radius()*2&&held[1]+held[3]*2>at.y
                    &&Math.hypot(held[0]-at.x,held[2]-at.z)<held[3]+berth.radius()){blocked=true;break;}
            if(blocked)break;room++;
        }
        return room;
    }
    void syncCargo(CompoundTag data) { entityData.set(CARGO, data); seatRegion = ""; }
    public boolean paddling() { return entityData.get(PADDLING); }
    /** Light rocking is separate from an actual capsize, and can never itself overturn the hat. */
    public boolean rocking(){var m=entityData.get(MOTION);return isAfloat()&&m.contains("RockAt")&&motionAge(m,"RockAt",0)<ROCK_TICKS;}
    public boolean capsizing(){var m=entityData.get(MOTION);return state()==State.CAPSIZED&&m.contains("CapAt")&&motionAge(m,"CapAt",0)<CAPSIZE_TICKS;}
    public boolean deploying(){var m=entityData.get(MOTION);return isAfloat()&&m.contains("DeployAt")&&motionAge(m,"DeployAt",0)<20;}
    public boolean selfRescueHeld(){return entityData.get(MOTION).getBoolean("SelfHeld");}
    /** Held geometry and its braid share a tick position instead of the ordinary three-step entity lerp. */
    public Vec3 motionPosition(float partial){
        var m=entityData.get(MOTION);
        return (selfRescueHeld()||m.contains("LooseAt")&&motionAge(m,"LooseAt",partial)<14)&&m.contains("HeldCentreX")
                ?point(m,"PreviousCentre").lerp(point(m,"HeldCentre"),partial):getPosition(partial);
    }
    public float hullSquash(float partial){
        var m=entityData.get(MOTION);
        if(selfRescueHeld())return net.minecraft.util.Mth.lerp(partial,m.getFloat("PreviousSquash"),m.getFloat("HeldSquash"));
        if(m.contains("LooseAt")){
            float t=(float)Math.clamp(motionAge(m,"LooseAt",partial)/14,0,1);t=t*t*(3-2*t);
            return net.minecraft.util.Mth.lerp(t,m.getFloat("LooseSquash"),HatVessel.SQUASH_FLOAT);
        }
        return isCradle()?HatVessel.SQUASH_NEST:HatVessel.SQUASH_FLOAT;
    }
    public boolean selfRighting(){return state()==State.CAPSIZED&&entityData.get(MOTION).contains("RightProgress");}
    public float selfRightingProgress(float partial){var m=entityData.get(MOTION);return net.minecraft.util.Mth.lerp(partial,m.getFloat("RightPrevious"),m.getFloat("RightProgress"));}
    /** The dip is only a bounded weight shift: it is deliberately not unsteady motion, so footing and stability stay valid. */
    public boolean unsteadyMotion(){return rocking()||capsizing()||deploying()||selfRighting()||selfRescueHeld();}
    private float dipValue(float partial){
        var m=entityData.get(DIP);if(m.isEmpty())return 0;
        double t=Math.clamp((level().getGameTime()-m.getLong("At")+partial)/Math.max(1,m.getInt("Ticks")),0,1);t=t*t*(3-2*t);
        return (float)(m.getFloat("From")+(m.getFloat("To")-m.getFloat("From"))*t);
    }
    /** Synced tilt toward the lowered near rim, in degrees (0 .. MAX_DIP). */
    public float dipDegrees(float partial){return dipValue(partial);}
    /** Lowered-rim direction in Minecraft yaw (degrees) in the hat's own frame: local direction (-sin, 0, cos). */
    public float dipYaw(){return entityData.get(DIP).getFloat("Yaw");}
    public Dip dip(){return new Dip(dipValue(0),dipYaw());}
    /** How far the near rim is lowered at this hat-local point, in blocks; zero on the far side and when level. */
    public double rimLift(Vec3 localPoint){return rimLift(localPoint,dip(),unit());}
    public static double rimLift(Vec3 localPoint,Dip dip,double unit){
        if(dip==null||dip.degrees()<=.01f)return 0;
        double yaw=Math.toRadians(dip.yaw()),along=-localPoint.x*Math.sin(yaw)+localPoint.z*Math.cos(yaw);
        return Math.max(0,along*Math.tan(Math.toRadians(dip.degrees()))*unit);
    }
    /** The hat can take a small weight shift now: afloat, steady, not towed or flooding, and its captain (if any) is aboard and idle. */
    public boolean dippable(YukkuriEntity actor){
        if(level().isClientSide||!isAfloat()||unsteadyMotion()||towed()||flooding>0)return false;
        var captain=owner();
        return captain==null||captain==actor||captain.isAlive()&&!captain.isNoAi()&&!captain.life().isSleeping()&&!captain.isCommandPlaying()&&captain.getVehicle()==this;
    }
    /** The tilt dipToward would settle at for this target, without changing anything; Dip.NONE if it cannot dip. */
    public Dip planDip(Vec3 worldTarget,float maxDegrees){
        if(worldTarget==null)return Dip.NONE;
        var local=toLocal(worldTarget);double length=Math.hypot(local.x,local.z);
        if(length<1e-6)return Dip.NONE;
        float yaw=(float)Math.toDegrees(Math.atan2(-local.x,local.z)),current=dipValue(0);
        if(current>.5f&&Math.abs(net.minecraft.util.Mth.wrapDegrees(yaw-dipYaw()))<=25f)yaw=dipYaw();
        double dx=-local.x/length,dz=local.z/length;
        double reach=Math.min(Math.abs(dx)<1e-6?Double.MAX_VALUE:HatVessel.BRIM[0]/Math.abs(dx),Math.abs(dz)<1e-6?Double.MAX_VALUE:HatVessel.BRIM[1]/Math.abs(dz));
        float cap=(float)Math.toDegrees(Math.atan(.6*HatVessel.FREEBOARD/reach));
        float degrees=Math.min(Math.min(maxDegrees,MAX_DIP),cap);
        return degrees<=.05f?Dip.NONE:new Dip(degrees,yaw);
    }
    /** Ask the hull to lean its near rim toward this point (the visitor's weight). Renew every few ticks; it lapses after DIP_RENEW_TICKS. */
    public boolean dipToward(Vec3 worldTarget,float maxDegrees,int rampTicks){
        if(level().isClientSide||!isAfloat()||unsteadyMotion()||towed()||flooding>0||catches())return false;
        var plan=planDip(worldTarget,maxDegrees);if(plan.degrees()<=0)return false;
        var m=entityData.get(DIP);boolean leaning=m.getFloat("To")>0;float held=m.getFloat("Yaw");
        // While tilted toward one side, a weight on the opposite side waits for the hull to level instead of snapping it across.
        if(dipValue(0)>.5f&&Math.abs(net.minecraft.util.Mth.wrapDegrees(plan.yaw()-held))>25f)return false;
        dipRenewed=level().getGameTime();
        if(leaning&&m.getFloat("To")==plan.degrees())return true;
        var next=new CompoundTag();next.putFloat("From",dipValue(0));next.putFloat("To",plan.degrees());
        next.putLong("At",level().getGameTime());next.putInt("Ticks",Math.max(1,rampTicks));next.putFloat("Yaw",leaning?held:plan.yaw());
        entityData.set(DIP,next);return true;
    }
    /** Level the hull again over this many ticks; safe to call when not dipped. */
    public void releaseDip(int ticks){
        if(level().isClientSide)return;
        dipRenewed=Long.MIN_VALUE;var m=entityData.get(DIP);
        if(m.isEmpty()||m.getFloat("To")<=0)return;
        float current=dipValue(0);
        if(current<.01f){entityData.set(DIP,new CompoundTag());return;}
        var next=new CompoundTag();next.putFloat("From",current);next.putFloat("To",0);next.putLong("At",level().getGameTime());
        next.putInt("Ticks",Math.max(1,ticks));next.putFloat("Yaw",m.getFloat("Yaw"));entityData.set(DIP,next);
    }
    private void tickDip(){
        var m=entityData.get(DIP);if(m.isEmpty()||m.getFloat("To")<=0)return;
        boolean unsettled=!isAfloat()||unsteadyMotion()||towed()||catches();
        if(unsettled)releaseDip(4);
        else if(level().getGameTime()-dipRenewed>DIP_RENEW_TICKS)releaseDip(14);
    }
    public long motionStartTick(){var m=entityData.get(MOTION);return m.contains("CapAt")?m.getLong("CapAt"):m.contains("RockAt")?m.getLong("RockAt"):0;}
    private double motionAge(CompoundTag motion,String key,float partial){return Math.max(0,level().getGameTime()-motion.getLong(key)+partial);}
    /** Signed local Z roll, in degrees, applied after the ordinary boat yaw. */
    public float motionRoll(float partial){
        var m=entityData.get(MOTION);int side=m.getInt("Side")<0?-1:1;
        if(selfRescueHeld())return 0;
        if(m.contains("LooseAt")){
            double t=Math.clamp(motionAge(m,"LooseAt",partial)/14,0,1);return (float)(side*110*t*t*(3-2*t));
        }
        if(deploying()||selfRighting()){
            double t=deploying()?Math.clamp(motionAge(m,"DeployAt",partial)/20,0,1):Math.clamp(selfRightingProgress(partial),0,1);
            return (float)(side*110*(1-t*t*(3-2*t)));
        }
        if(state()==State.CAPSIZED){
            double t=m.contains("CapAt")?Math.clamp(motionAge(m,"CapAt",partial)/CAPSIZE_TICKS,0,1):1;
            return (float)(side*110*t*t*(3-2*t));
        }
        if(!rocking())return 0;
        double age=motionAge(m,"RockAt",partial);
        return (float)(side*m.getFloat("Amplitude")*Math.exp(-age/14)*Math.sin(age*.62));
    }
    /** Local X pitch, in degrees. Matrix order is yaw * roll(Z) * pitch(X). */
    public float motionPitch(float partial){
        var m=entityData.get(MOTION);
        if(selfRescueHeld())return net.minecraft.util.Mth.lerp(partial,m.getFloat("PreviousPitch"),m.getFloat("HeldPitch"));
        if(m.contains("LooseAt")){
            double t=Math.clamp(motionAge(m,"LooseAt",partial)/14,0,1);return (float)(m.getFloat("LoosePitch")*(1-t*t*(3-2*t)));
        }
        if(deploying()||selfRighting()){
            double t=deploying()?Math.clamp(motionAge(m,"DeployAt",partial)/20,0,1):Math.clamp(selfRightingProgress(partial),0,1);
            return (float)(7*Math.sin(t*Math.PI));
        }
        if(state()==State.CAPSIZED){
            if(!m.contains("CapAt"))return 0;
            double t=Math.clamp(motionAge(m,"CapAt",partial)/CAPSIZE_TICKS,0,1);
            return (float)(7*Math.sin(t*Math.PI));
        }
        if(!rocking())return 0;
        double age=motionAge(m,"RockAt",partial);
        return (float)(m.getFloat("Amplitude")*.24*Math.exp(-age/14)*Math.sin(age*.44));
    }
    public float rockingAmplitude(){var m=entityData.get(MOTION);return rocking()?(float)(m.getFloat("Amplitude")*Math.exp(-motionAge(m,"RockAt",0)/14)):0;}
    /** A bounded shove, not additive damage. Repeated interaction cannot stack an unbounded oscillation. */
    private void rock(Player player){
        if(selfRescueHeld()){releaseSelfRescueCarry();return;}
        var before=entityData.get(MOTION);double age=before.contains("RockAt")?motionAge(before,"RockAt",0):ROCK_TICKS;
        if(age<10)return;
        var m=new CompoundTag();m.putLong("RockAt",level().getGameTime());
        m.putInt("Side",toLocal(player.position()).x>=0?-1:1);
        m.putFloat("Amplitude",Math.min(18,12+rockingAmplitude()*.5f));entityData.set(MOTION,m);
        stopPaddling();heldTicks=Math.max(heldTicks,12);markHurt();
        var captain=owner();if(captain!=null)captain.aquaticLife().familyLife().alarm().start(this,player,player.position());
        for(var p:getPassengers())if(p instanceof YukkuriEntity y)
            y.showReaction(y.family().adult()?"surprise":"teary",y.family().adult()?"wait_family":"afraid",ROCK_TICKS);
    }
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
        stopPaddling(); route.addAll(path);
        finalWaypointToleranceSquared = Double.isFinite(finalToleranceSquared) ? Math.clamp(finalToleranceSquared,.0004,.12) : .12;
        entityData.set(PADDLING, !route.isEmpty()); return !route.isEmpty();
    }
    /** The stick holds the boat briefly during boarding; sleeping boats still drift with the current. */
    public void holdPosition() { stopPaddling(); heldTicks = 5; }
    public boolean protects(Entity rider) {
        return hasPassenger(rider) && (isAfloat() || catches() || state() == State.BEACHED)
                && !level().getFluidState(BlockPos.containing(getX(), getY()+.25, getZ())).is(FluidTags.WATER);
    }
    public Vec3 ownerSeat(YukkuriEntity owner) {
        double rear = HatVessel.OPENING[3]-io.github.longshou.mcyukkuri.family.LiveBirthGeometry.back(owner)*owner.getScale()/unit();
        return new Vec3(0, HatVessel.OWNER_Y, Math.max(0, rear));
    }
    public UUID ownerYukkuri() { return entityData.get(OWNER).orElse(null); }
    public State state() { return State.values()[Math.clamp(entityData.get(STATE), 0, State.values().length-1)]; }
    public boolean isAfloat() { return state() == State.AFLOAT; }
    /** The hat's size: its owner's growth, larger for the aquatic Marisa. */
    public float hullScale() { return entityData.get(SCALE); }
    public HatVessel.Role role() { return role; }
    public int flooding() { return flooding; }
    private void state(State next) { entityData.set(STATE, next.ordinal()); }
    @Override public boolean isPickable() { return !isRemoved(); }
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
        return (isAfloat()||state()==State.BEACHED||catches())&&youngAboard()+incoming.size()<MAX_YOUNG
                &&HatVessel.place(mode(),newborn,region(),null,layoutTaken(null),null,Double.NaN,HatVessel.newbornSpace(mode(),hullScale(),unit()))!=null;
    }
    /** Room for this young: a free place, or the one kept for it. */
    public boolean hasRoomFor(YukkuriEntity child) {
        if(child==null||!young(child)||!(isAfloat()||state()==State.BEACHED||catches()))return false;
        if(hasPassenger(child)||incoming.containsKey(child.getUUID()))return true;
        if(youngAboard()+incoming.size()>=MAX_YOUNG)return false;
        return plannedSeat(child,null,false)!=null;
    }
    public boolean hasSafeRoomFor(YukkuriEntity child){
        return child!=null&&young(child)&&HatVessel.place(mode(),radiusOf(child),region(),null,
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
    private double brimReachToward(Vec3 direction){
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
        var inside=HatVessel.place(mode(),radiusOf(child),region(),null,layoutTaken(child.getUUID()),null,Double.NaN,HatVessel.bodySpace(child,hullScale(),unit()));
        var parent=owner();
        if(inside==null||parent==null||!serviceSeats.isEmpty()||!returningSeats.isEmpty())return false;
        if(parent.life().isSleeping())parent.life().wakeInHat();
        return moveCarePair(child,parent,new double[]{inside.x,inside.y,inside.z,radiusOf(child)},ownerSeat(parent));
    }
    public String crowdingState(Entity rider){return crowding.state(rider);}
    public long crowdingPhaseStart(Entity rider){return crowding.phaseStart(rider);}
    public UUID crowdingPusher(Entity rider){return crowding.pusher(rider);}
    public float riderBalanceRoll(Entity rider,float partial){return crowding.roll(rider,partial);}
    public float riderBalancePitch(Entity rider,float partial){return crowding.pitch(rider,partial);}
    CompoundTag crowdingData(){return entityData.get(SEATS).getCompound("Crowding");}
    void syncCrowding(){syncSeats();}
    double crowdRadius(Entity rider){return radiusOf(rider);}
    boolean crowdMovable(YukkuriEntity rider){
        return rider.getVehicle()==this&&!transportingInHat(rider)&&!changingSeat(rider)
                &&!serviceSeats.containsKey(rider.getUUID())&&!returningSeats.containsKey(rider.getUUID());
    }
    void crowdPosition(YukkuriEntity rider,Vec3 local){
        seats.put(rider.getUUID(),new double[]{local.x,local.y,local.z,radiusOf(rider)});
        seatMoves.remove(rider.getUUID());
        var at=toWorld(local);rider.setPos(at.x,at.y,at.z);
    }
    void crowdFall(YukkuriEntity child,Vec3 local,Vec3 direction,UUID pusher){
        var at=toWorld(local);var out=direction.multiply(1,0,1).normalize().yRot(turn());
        child.stopRiding();child.setPos(at.x,at.y,at.z);
        child.setDeltaMovement(getDeltaMovement().add(out.scale(.075)).add(0,.015,0));child.hasImpulse=true;
        child.waterDistress().plunge(getUUID(),pusher);
        var parent=owner();if(parent!=null)parent.showReaction("surprise","wait_family",80);
        syncSeats();
    }
    /** A newborn delivered in the hat stays aboard with its mother. */
    public boolean boardYoung(YukkuriEntity child) {
        if (level().isClientSide || unsteadyMotion()&&!crowding.emergency(child.getUUID()) || !young(child) || !hasRoomFor(child)) return false;
        var seat = incoming.remove(child.getUUID());
        if (seat == null) { double r = radiusOf(child); var s = plannedSeat(child,null,false); if(s==null)return false;seat = new double[]{s.x, s.y, s.z, r}; }
        if (!child.startRiding(this, true)) return false;
        crew.add(child.getUUID());
        seats.put(child.getUUID(), seat); syncSeats(); return true;
    }
    private Vec3 plannedSeat(YukkuriEntity child,Vec3 prefer,boolean emergency){
        double r=radiusOf(child);var taken=layoutTaken(child.getUUID());
        var inside=HatVessel.place(mode(),r,region(),prefer,taken,null,Double.NaN,HatVessel.bodySpace(child,hullScale(),unit()));
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
    private float turn() { return (float) Math.toRadians(180 - getYRot()); }
    public Vec3 toWorld(Vec3 local) { return position().add(local.scale(unit()).yRot(turn())); }
    public Vec3 toLocal(Vec3 world) { return world.subtract(position()).yRot(-turn()).scale(1/unit()); }
    private String region() { return catches() ? "catch" : !isMoored() && level().isNight() ? "night" : "day"; }
    private double radiusOf(Entity young) { return HatVessel.radius(young.getBbWidth())/unit(); }
    private java.util.List<Entity> youngPassengers() { return getPassengers().stream().filter(p -> !p.getUUID().equals(ownerYukkuri())).toList(); }
    private java.util.List<double[]> taken() {
        return takenExcept(null);
    }
    private void addCargoFootprints(java.util.List<double[]> out) {
        for(var berth:cargoBerths()){
            var p=berth.position();
            out.add(new double[]{p.x,p.y,p.z,berth.radius(),berth.radius()*2,2});
        }
    }
    /** Ordinary sleeping and daytime berths stay outside the parent's body projection, even on a lower layer. */
    private java.util.List<double[]> layoutTaken(UUID excluded) {
        var out=takenExcept(excluded);
        if(!catches()&&getControllingPassenger() instanceof YukkuriEntity parent){
            var at=ownerSeat(parent);
            out.add(new double[]{at.x,at.y,at.z,radiusOf(parent),parent.getBbHeight()/unit(),1});
        }
        return out;
    }
    public boolean keepVoyageBerth(YukkuriEntity child) {
        if (level().isClientSide || unsteadyMotion() || !young(child) || !onBrim(child) || !protects(child) || hasIncoming()
                || ownerSeatBusy() || voyageYoung != null && !voyageYoung.equals(child.getUUID())) return false;
        var others=new java.util.ArrayList<double[]>();
        for(var e:seats.entrySet())if(!e.getKey().equals(child.getUUID()))others.add(e.getValue());
        double r=radiusOf(child);var at=HatVessel.brimPlace(r,others,true);if(at==null)return false;
        voyageYoung = child.getUUID(); voyageBerth = new double[]{at.x,at.y,at.z,r};
        var from=toLocal(child.position());
        if(from.distanceToSqr(at)>.01)seatMoves.put(voyageYoung,new SeatMove(from,level().getGameTime(),
                HatVessel.solve(child.position(),toWorld(at),this,HatVessel.radius(child.getBbWidth()),HatVessel.HOP_TMIN),true));
        seats.put(voyageYoung,voyageBerth.clone());syncSeats();return true;
    }

    /**
     * A subadult's departure berth uses the same retained slot as the ordinary first voyage, but
     * supports the feet on the front rim rather than requiring its now-larger whole body to fit on
     * the narrow juvenile standing strip.  It is still a real, flat in-hat SeatMove.
     */
    public boolean keepDepartureBerth(YukkuriEntity child) {
        if (level().isClientSide || unsteadyMotion() || !young(child) || !protects(child) || hasIncoming() || !serviceSeats.isEmpty()
                || ownerSeatBusy() || voyageYoung != null && !voyageYoung.equals(child.getUUID())) return false;
        var others = new java.util.ArrayList<double[]>();
        for (var entry : seats.entrySet()) if (!entry.getKey().equals(child.getUUID())) others.add(entry.getValue());
        double r = radiusOf(child); Vec3 at = frontEdgePlace(child, others, false);
        if (at == null) return false;
        var berth = new double[]{at.x, at.y, at.z, r};
        if (!moveYoungSeat(child, berth, false)) return false;
        voyageYoung = child.getUUID(); voyageBerth = berth;
        syncSeats();
        return true;
    }
    public void releaseVoyageBerth(UUID child) {
        if (child.equals(voyageYoung)) { voyageYoung = null; voyageBerth = null; seatRegion = ""; }
    }
    public Vec3 voyageBerth(YukkuriEntity child) {
        return child.getUUID().equals(voyageYoung) && voyageBerth != null ? new Vec3(voyageBerth[0],voyageBerth[1],voyageBerth[2]) : null;
    }

    /** The retained front support has been reached, rather than merely reserved in the seat map. */
    public boolean onVoyageBerth(YukkuriEntity child) {
        if (child == null) return false;
        var berth = voyageBerth(child); var seat = child == null ? null : seatOf(child);
        return berth != null && seat != null && !changingSeat(child) && Math.abs(seat.y-HatVessel.FREEBOARD) < 1e-5
                && seat.distanceToSqr(berth) < 1e-5 && Double.isFinite(HatVessel.surface(HatVessel.Mode.FLOAT, seat.x, seat.z));
    }
    private void syncSeats() {
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
    private Vec3 seatOf(Entity young) {
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
                || !isAfloat() || towed() || flooding != 0 || hasIncoming() || ownerToiletSeat || child.getUUID().equals(voyageYoung)) return false;
        var held = serviceSeats.get(child.getUUID());
        if (held != null && held.ownerService() != null) return held.rearCare() == rearCare;
        if (carryTrip != null) return false;
        if (serviceSeats.keySet().stream().anyMatch(id -> !id.equals(child.getUUID()))) return false;
        if (held == null) held = returningSeats.get(child.getUUID());
        // Feeding takes precedence over a child's voluntary movement.  Keep that movement's original
        // berth, but replace its destination from the child's current in-hat position.
        var original = held != null ? held.original() : seats.get(child.getUUID());
        if (original == null) return false;
        ServiceSeat best = serviceSeatFor(child, parent, original, rearCare);
        if (best == null) return false;
        if (!moveCarePair(child, parent, best.service(), best.ownerService())) return false;
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
        if (brim == null) return false;
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
        if (brim == null) return false;
        var destination = new double[]{brim.x, brim.y, brim.z, radiusOf(child)};
        Vec3 escort=escortPlace(parent,child,brim);
        if(escort==null)return false;
        if (!moveCarePair(child, parent, destination, escort)) return false;
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
        if (service != null) returningSeats.put(childId, service);
        returnCareSeats();
        syncSeats();
    }

    private void returnCareSeats() {
        if (carryTrip != null) return;
        for (var entry : java.util.List.copyOf(returningSeats.entrySet())) {
            if (!(level() instanceof ServerLevel server) || !(server.getEntity(entry.getKey()) instanceof YukkuriEntity child)
                    || child.getVehicle() != this) { returningSeats.remove(entry.getKey()); continue; }
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
                if (beginCarry(child,parent,destination,ownerSeat(parent))) break;
                continue;
            }
            if (!moveYoungSeat(child, destination, false)) continue;
            if (atSeat(child, new Vec3(destination[0], destination[1], destination[2]))) returningSeats.remove(entry.getKey());
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
        if (level().isClientSide || unsteadyMotion() || child == null || child.getVehicle() != this || !young(child) || !protects(child)
                || !isAfloat() || towed() || flooding != 0 || hasIncoming() || child.getUUID().equals(voyageYoung)
                || !serviceSeats.isEmpty() || changingSeat(child) || ownerSeatBusy()) return false;
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
    private Vec3 frontEdgePlace(YukkuriEntity rider, java.util.List<double[]> taken, boolean needsRearClear) {
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
        double r=radiusOf(child); Vec3 from=toLocal(child.position()), parentFrom=toLocal(parent.position()), base=ownerSeat(parent);
        var childPlaces=new java.util.ArrayList<Vec3>();
        // Enumerate measured contact patches once. A parent's station is not restricted to a few stern offsets.
        for(double x=HatVessel.OPENING[0]+r;x<=HatVessel.OPENING[1]-r;x+=.25)
            for(double z=HatVessel.OPENING[2]+r;z<=Math.min(.5,HatVessel.OPENING[3]-r);z+=.25){
                double y=HatVessel.footprint(mode(),x,z,r);
                if(Double.isFinite(y)&&Double.isFinite(HatVessel.surface(mode(),x,z)))childPlaces.add(new Vec3(x,y,z));
            }
        record CarePose(Vec3 ownerAt,Vec3 childAt,double cost) {}
        var candidates=new java.util.ArrayList<CarePose>();
        double min=FamilyFeedingContact.minimumMouthDistance(parent,child)/unit(), max=FamilyFeedingContact.mouthDistance(parent,child)/unit();
        for(double x=HatVessel.OPENING[0];x<=HatVessel.OPENING[1];x+=.5)
            for(double z=HatVessel.OPENING[2];z<=HatVessel.OPENING[3];z+=.5){
                double y=HatVessel.footprint(mode(),x,z,radiusOf(parent));
                if(!Double.isFinite(y)||!Double.isFinite(HatVessel.surface(mode(),x,z)))continue;
                Vec3 ownerAt=new Vec3(x,y,z);
                for(var candidate:childPlaces){
                    if(Math.abs(candidate.y-ownerAt.y)>.001)continue;
                    double dx=candidate.x-x,dz=candidate.z-z,distance=dx*dx+dz*dz;
                    if(distance<min*min || distance>max*max || !serviceGeometryFits(parent,child,ownerAt,candidate))continue;
                    double cost=candidate.distanceToSqr(from)+ownerAt.distanceToSqr(parentFrom)*.5+ownerAt.distanceToSqr(base)*.25;
                    candidates.add(new CarePose(ownerAt,candidate,cost));
                }
            }
        candidates.sort(java.util.Comparator.comparingDouble(CarePose::cost));
        // Cheap geometry and travel costs precede routing: stop at the first fully reachable pair.
        for(var pose:candidates){
            Vec3 ownerAt=pose.ownerAt(), candidate=pose.childAt();
            var path=walkingRoute(child,candidate);
            boolean assisted=!child.childhood().canJump()&&(hasStep(path)||Math.abs(candidate.y-from.y)>.01);
            if(assisted){if(carryPickup(child,parent,from,candidate,ownerAt)==null)continue;}
            else{
                if(from.distanceToSqr(candidate)>.01&&path.isEmpty())continue;
                var obstacles=takenExcept(child.getUUID());
                obstacles.add(new double[]{candidate.x,candidate.y,candidate.z,r,child.getBbHeight()/unit()});
                if(parentFrom.distanceToSqr(ownerAt)>.01&&HatSurfaceRoute.find(mode(),parentFrom,ownerAt,
                        radiusOf(parent),obstacles,parent.getBbHeight()/unit()).isEmpty())continue;
            }
            return new ServiceSeat(original.clone(),region(),new double[]{candidate.x,candidate.y,candidate.z,r},ownerAt,rearCare,false);
        }
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

    private java.util.List<double[]> takenExcept(UUID excluded) {
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
            var at=toLocal(p.position());out.add(new double[]{at.x,at.y,at.z,radiusOf(p),p.getBbHeight()/unit()});
        }
        if (voyageBerth != null && (voyageYoung == null || !voyageYoung.equals(excluded)) && !seats.containsKey(voyageYoung) && !incoming.containsKey(voyageYoung)) out.add(voyageBerth);
        addCargoFootprints(out);
        return out;
    }

    private double[] normalSeat(YukkuriEntity child, UUID excluded) {
        double r = radiusOf(child); Vec3 next = HatVessel.place(mode(), r, region(), null, layoutTaken(excluded), toLocal(child.position()),Double.NaN,HatVessel.bodySpace(child,hullScale(),unit()));
        if (next == null) return null;
        return new double[]{next.x, next.y, next.z, r};
    }

    private boolean moveYoungSeat(YukkuriEntity child, double[] destination, boolean arc) {
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

    private boolean ownerSeatBusy() {
        var parent = owner();
        return ownerToiletSeat || changingOwnerSeat() || parent != null && ownerServiceSeat != null && !ownerServiceSeat.equals(ownerSeat(parent));
    }

    private boolean moveOwnerSeat(YukkuriEntity parent, Vec3 destination, boolean arc) {
        Vec3 from = toLocal(parent.position());
        if (destination.equals(ownerServiceSeat)) return true;
        var route=arc||from.distanceToSqr(destination)<=.01?java.util.List.<Vec3>of():walkingRoute(parent,destination);
        if(!arc&&from.distanceToSqr(destination)>.01&&route.isEmpty())return false;
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
                double y=HatVessel.footprint(mode(),x,z,radius);if(!Double.isFinite(y))continue;
                var at=new Vec3(x,y,z);if(HatVessel.fits(mode(),at,radius,obstacles,body))result.add(at);
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
                ||youngSeat==null||parentStand==null||!isAfloat()||unsteadyMotion()||towed()||flooding>0)return false;
        if(Math.abs(HatVessel.surface(mode(),youngSeat.x,youngSeat.z)-youngSeat.y)>1e-4)return false;
        var obstacles=takenExcept(child.getUUID());
        var childObstacles=new java.util.ArrayList<>(obstacles);childObstacles.add(rescueBody(parent,parentStand));
        var ownerObstacles=new java.util.ArrayList<>(obstacles);ownerObstacles.add(rescueBody(child,youngSeat));
        return HatVessel.fits(mode(),youngSeat,radiusOf(child),childObstacles,HatVessel.bodySpace(child,hullScale(),unit()))
                &&HatVessel.fits(mode(),parentStand,radiusOf(parent),ownerObstacles,HatVessel.bodySpace(parent,hullScale(),unit()));
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
        if(from.distanceToSqr(parentStand)>.01&&HatSurfaceRoute.find(mode(),from,parentStand,radiusOf(parent),obstacles,parent.getBbHeight()/unit()).isEmpty())return false;
        incoming.put(child.getUUID(),new double[]{youngSeat.x,youngSeat.y,youngSeat.z,radiusOf(child)});
        syncSeats();return true;
    }
    /** A real supported walk, never a jump or an interpolated extension of the braid. */
    public boolean moveRescueOwner(YukkuriEntity parent,Vec3 localSupported){
        if(level().isClientSide||parent!=owner()||parent.getVehicle()!=this||!isAfloat()||unsteadyMotion()||towed()
                ||localSupported==null||carryTrip!=null||ownerToiletSeat||!serviceSeats.isEmpty()||!returningSeats.isEmpty())return false;
        if(!HatVessel.fits(mode(),localSupported,radiusOf(parent),takenExcept(parent.getUUID()),HatVessel.bodySpace(parent,hullScale(),unit())))return false;
        if(!moveOwnerSeat(parent,localSupported,false))return false;
        rescueOwnerHeld=true;holdPosition();syncSeats();return true;
    }
    /** Read-only LOCAL walking points for the braid controller's swept two-body custody plan. */
    public java.util.List<Vec3> rescueOwnerRoute(YukkuriEntity parent,Vec3 localStand,YukkuriEntity child){
        return rescueOwnerRoute(parent,localStand,child,null);
    }
    public java.util.List<Vec3> rescueOwnerRoute(YukkuriEntity parent,Vec3 localStand,YukkuriEntity child,Vec3 youngSeat){
        if(parent!=owner()||parent.getVehicle()!=this||localStand==null||!isAfloat()||unsteadyMotion())return java.util.List.of();
        var obstacles=takenExcept(child!=null?child.getUUID():parent.getUUID());
        if(child!=null&&youngSeat!=null)obstacles.add(rescueBody(child,youngSeat));
        if(!HatVessel.fits(mode(),localStand,radiusOf(parent),obstacles,HatVessel.bodySpace(parent,hullScale(),unit())))return java.util.List.of();
        var from=toLocal(parent.position());
        if(from.distanceToSqr(localStand)<.0001)return java.util.List.of(from);
        return HatSurfaceRoute.find(mode(),from,localStand,radiusOf(parent),obstacles,parent.getBbHeight()/unit());
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
                obstacles.add(new double[]{at.x, at.y, at.z, radiusOf(parent),parent.getBbHeight()/unit()});
            }
        }
        return HatSurfaceRoute.find(mode(), toLocal(rider.position()), destination, radiusOf(rider), obstacles,rider.getBbHeight()/unit());
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
    private static boolean hasStep(java.util.List<Vec3> path) {
        for (int i=1;i<path.size();i++) if (Math.abs(path.get(i).y-path.get(i-1).y)>.01) return true;
        return false;
    }

    /** A service pose is usable only when the parent can also collect and transport the real child. */
    private Vec3 carryPickup(YukkuriEntity child,YukkuriEntity parent,Vec3 from,Vec3 to,Vec3 ownerDestination) {
        Vec3 direction=to.subtract(ownerDestination).multiply(1,0,1).normalize();
        Vec3 offset=HatCareAnchor.offset(parent,child,direction,unit());if(offset==null)return null;
        Vec3 xz=from.subtract(offset);
        Vec3 pickup=new Vec3(xz.x,HatVessel.footprint(mode(),xz.x,xz.z,radiusOf(parent)),xz.z);
        if (!Double.isFinite(pickup.y) || !serviceGeometryFits(parent,child,pickup,from)) return null;
        if (toLocal(parent.position()).distanceToSqr(pickup)>.01 && walkingRoute(parent,pickup).isEmpty()) return null;
        var path=HatSurfaceRoute.find(mode(),pickup,ownerDestination,radiusOf(parent),takenExcept(child.getUUID()),
                offset,radiusOf(child),parent.getBbHeight()/unit(),child.getBbHeight()/unit());
        return path.isEmpty() && pickup.distanceToSqr(ownerDestination)>.01?null:pickup;
    }

    private boolean beginCarry(YukkuriEntity child,YukkuriEntity parent,double[] destination,Vec3 ownerDestination) {
        if (carryTrip != null) return carryTrip.child.equals(child.getUUID());
        Vec3 from=toLocal(child.position()), to=new Vec3(destination[0],destination[1],destination[2]);
        Vec3 offset=to.subtract(ownerDestination).multiply(1,0,1);
        double wanted=(FamilyFeedingContact.minimumMouthDistance(parent,child)+FamilyFeedingContact.mouthDistance(parent,child))*.5/unit();
        if (offset.horizontalDistance()*unit()<FamilyFeedingContact.minimumMouthDistance(parent,child)
                || offset.horizontalDistance()*unit()>FamilyFeedingContact.mouthDistance(parent,child)) {
            Vec3 direction=offset.lengthSqr()<.01?new Vec3(0,0,-1):offset.normalize();
            offset=direction.scale(wanted);
            Vec3 at=to.subtract(offset);
            ownerDestination=new Vec3(at.x,HatVessel.footprint(mode(),at.x,at.z,radiusOf(parent)),at.z);
        }
        if (!Double.isFinite(ownerDestination.y)) return false;
        Vec3 pickup=carryPickup(child,parent,from,to,ownerDestination);
        if (pickup==null) return false;
        if (!moveOwnerSeat(parent,pickup,false)) return false;
        seatMoves.remove(child.getUUID());
        seats.put(child.getUUID(),new double[]{from.x,from.y,from.z,radiusOf(child)});
        Vec3 carryOffset=HatCareAnchor.offset(parent,child,to.subtract(ownerDestination).multiply(1,0,1).normalize(),unit());
        carryTrip=new CarryTrip(child.getUUID(),from,to,pickup,ownerDestination,
                carryOffset,level().getGameTime());
        syncSeats(); return true;
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
        if (recoverCarry) {
            Vec3 target=trip.phase==0?trip.pickup:trip.ownerDestination;
            var path=trip.phase==0?walkingRoute(parent,target):HatSurfaceRoute.find(mode(),toLocal(parent.position()),target,radiusOf(parent),
                    takenExcept(child.getUUID()),trip.offset,radiusOf(child),parent.getBbHeight()/unit(),child.getBbHeight()/unit());
            if (path.isEmpty() && toLocal(parent.position()).distanceToSqr(target)>.01) return;
            recoverCarry=false; ownerServiceSeat=target;
            ownerSeatMove=path.isEmpty()?null:new SeatMove(toLocal(parent.position()),level().getGameTime(),
                    Math.max(16,(int)Math.ceil(HatSurfaceRoute.length(path)/.20)),false,path);
            syncSeats();
        }
        if (trip.phase==0) {
            if (atSeat(parent,trip.pickup)) {
                FamilyFeedingContact.faceEachOther(parent,child);
                if (FamilyFeedingContact.mouthsMeet(parent,child)) trip.contact++; else trip.contact=0;
                if (trip.contact>=12) { trip.phase=1; trip.at=level().getGameTime(); syncSeats(); }
            }
            return;
        }
        float carryYaw=io.github.longshou.mcyukkuri.life.SurfaceApproach.yaw(Vec3.ZERO,trip.facing.yRot(turn()));
        io.github.longshou.mcyukkuri.life.SurfaceApproach.face(parent,carryYaw);
        io.github.longshou.mcyukkuri.life.SurfaceApproach.face(child,carryYaw+180);
        if (trip.phase==1 && level().getGameTime()-trip.at>=12) {
            var path=HatSurfaceRoute.find(mode(),toLocal(parent.position()),trip.ownerDestination,radiusOf(parent),
                    takenExcept(child.getUUID()),trip.offset,radiusOf(child),parent.getBbHeight()/unit(),child.getBbHeight()/unit());
            if (path.isEmpty() && toLocal(parent.position()).distanceToSqr(trip.ownerDestination)>.01) {
                // Nothing was handed off: lower at the pickup patch and let the retained return retry.
                carryTrip=new CarryTrip(trip.child,trip.from,trip.from,trip.pickup,trip.pickup,trip.offset,level().getGameTime());
                carryTrip.facing=trip.facing;
                carryTrip.phase=3; syncSeats(); return;
            }
            ownerServiceSeat=trip.ownerDestination;
            int ticks=Math.max(16,(int)Math.ceil(HatSurfaceRoute.length(path)/.20));
            ownerSeatMove=path.isEmpty()?null:new SeatMove(toLocal(parent.position()),level().getGameTime(),ticks,false,path);
            trip.phase=2; trip.at=level().getGameTime(); syncSeats();
        } else if (trip.phase==2 && atSeat(parent,trip.ownerDestination)) {
            trip.phase=3; trip.at=level().getGameTime(); syncSeats();
        } else if (trip.phase==3 && level().getGameTime()-trip.at>=12) {
            seats.put(child.getUUID(),new double[]{trip.destination.x,trip.destination.y,trip.destination.z,radiusOf(child)});
            carryTrip=null;
            if(serviceSeats.isEmpty()&&returningSeats.isEmpty())moveOwnerSeat(parent,ownerSeat(parent),false);
            syncSeats();
        }
    }

    public boolean carryingInHat(Entity rider) {
        var trip=carryPose(); return trip!=null && trip.phase>0 && (rider.getUUID().equals(trip.child)||rider.getUUID().equals(ownerYukkuri()));
    }
    public boolean transportingInHat(Entity rider) {
        var trip=carryPose(); return trip!=null && (rider.getUUID().equals(trip.child)||rider.getUUID().equals(ownerYukkuri()));
    }
    public UUID hatCarrier(YukkuriEntity child) {
        var trip=carryPose(); return trip!=null && trip.phase>0 && trip.child.equals(child.getUUID())?ownerYukkuri():null;
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
        if (trip.phase==0) return trip.from;
        var parent=getPassengers().stream().filter(p->p.getUUID().equals(ownerYukkuri())&&p instanceof YukkuriEntity)
                .map(p->(YukkuriEntity)p).findFirst().orElse(null);
        if (parent==null) return trip.lastAnchor;
        Vec3 anchor=ownerCarryLocal(parent).add(trip.offset);
        double q=Math.clamp((level().getGameTime()-trip.at)/12.0,0,1); q=q*q*(3-2*q);
        trip.lastAnchor=trip.phase==1?trip.from.lerp(anchor,q):trip.phase==3?anchor.lerp(trip.destination,q):anchor;
        return trip.lastAnchor;
    }
    private static void point(CompoundTag tag,String key,Vec3 point) {
        tag.putDouble(key+"X",point.x);tag.putDouble(key+"Y",point.y);tag.putDouble(key+"Z",point.z);
    }
    private static Vec3 point(CompoundTag tag,String key) {
        return new Vec3(tag.getDouble(key+"X"),tag.getDouble(key+"Y"),tag.getDouble(key+"Z"));
    }
    private CompoundTag saveCarry(CarryTrip trip) {
        var tag=new CompoundTag();tag.putUUID("Child",trip.child);if(ownerYukkuri()!=null)tag.putUUID("Holder",ownerYukkuri());
        tag.putInt("Phase",trip.phase);tag.putLong("At",trip.at); point(tag,"From",trip.from);point(tag,"To",trip.destination);
        point(tag,"Pickup",trip.pickup);point(tag,"OwnerTo",trip.ownerDestination);point(tag,"Offset",trip.offset);point(tag,"Facing",trip.facing);
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
            double y=HatVessel.footprint(mode(),x,z,radiusOf(parent));
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
        double support = HatVessel.footprint(mode(), local.x, local.z, radiusOf(rider));
        // Lift onto a short ledge without ever sinking the feet through its surface.
        return new Vec3(local.x, Math.max(local.y, support), local.z);
    }
    private Vec3 walkingPosition(Entity rider,SeatMove movement,double time){
        Vec3 at=toWorld(walkingLocal(rider,movement,time));
        if(!level().isClientSide&&time>0&&time<movement.ticks()&&rider instanceof YukkuriEntity y&&at.subtract(rider.position()).horizontalDistanceSqr()>.00001) {
            float yaw=carryTrip!=null && carryTrip.phase>0 && rider.getUUID().equals(ownerYukkuri())
                    ?io.github.longshou.mcyukkuri.life.SurfaceApproach.yaw(Vec3.ZERO,carryTrip.facing.yRot(turn()))
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
                &&(y.childhood().canJump()||y.childhood().landed()&&y.childhood().firstToiletDone())
                && isAfloat() && flooding==0 && !towed() && !hasIncoming() && !level().isNight() && !level().isRainingAt(blockPosition())
                && captain!=null && !captain.isPanicking() && !captain.family().laboring() && !captain.life().isSleeping()
                && !captain.aquaticLife().care().active() && !y.isPanicking() && !y.life().isSleeping()
                && !y.isNoAi() && !y.isCommandPlaying() && !y.operations().active() && !crowding.emergency(y.getUUID())
                && y.life().mealTicks()==0 && y.life().energy()>25 && y.life().nutrition()>=60;
    }
    /** Server: young aboard without a place (after loading) get one; afloat, at dusk and dawn they all move (before the
     *  owner by day, gathered near her lower front at night). */
    private void tendSeats() {
        if(rescueOwnerHeld){
            var parent=owner();
            if(parent==null||parent.getVehicle()!=this){
                rescueOwnerHeld=false;ownerServiceSeat=null;ownerSeatMove=null;syncSeats();
            }else if(!parent.isAlive()||!parent.family().braidRescueActive())releaseRescueOwner(parent);
        }
        if (carryTrip != null||rescueOwnerHeld) return;
        // Large juveniles need the scarce wide spaces first. Small siblings then fit around them or inside.
        var young = youngPassengers().stream().sorted(java.util.Comparator.comparingDouble(this::radiusOf).reversed()).toList();
        boolean resized = young.stream().anyMatch(p -> seats.containsKey(p.getUUID()) && Math.abs(seats.get(p.getUUID())[3]-radiusOf(p))>.001);
        if (resized && !serviceSeats.isEmpty()) {
            for (var id : java.util.List.copyOf(serviceSeats.keySet())) releaseCareSeat(id);
        }
        serviceSeats.keySet().removeIf(id -> young.stream().noneMatch(p -> p.getUUID().equals(id)));
        returnCareSeats();
        var captain=getControllingPassenger();
        String wanted=region()+":"+hullScale()+":"+cargo.count()+":"+(captain==null?"none":radiusOf(captain)+":"+captain.getBbHeight())
                +young.stream().map(p->p.getUUID()+":"+brimSafe(p)+":"+(p instanceof YukkuriEntity y?HatVessel.bodySpace(y,hullScale(),unit()):p.getBbHeight())).toList();
        boolean moved = resized || !wanted.equals(seatRegion) && !catches(); seatRegion = wanted;
        var old=new java.util.HashMap<UUID,double[]>(seats);
        // Growth may replan a destination, but never discard a live movement midway through it.
        if (moved) seats.clear();
        if (voyageBerth != null && young.stream().anyMatch(p -> p.getUUID().equals(voyageYoung))) seats.put(voyageYoung,voyageBerth.clone());
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
                    var parent=owner();
                    if(!y.childhood().canJump()&&parent!=null&&serviceSeats.isEmpty()&&returningSeats.isEmpty()){
                        // The carry planner must still see siblings whose new day/night berth has not
                        // been chosen yet. They stay in their real old seats throughout this transfer.
                        var restored=new java.util.ArrayList<UUID>();
                        for(var entry:old.entrySet())if(!seats.containsKey(entry.getKey())){
                            seats.put(entry.getKey(),entry.getValue());restored.add(entry.getKey());
                        }
                        if(beginCarry(y,parent,destination,ownerSeat(parent))){seatRegion="";return;}
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
        var have = incoming.get(child.getUUID());
        if (have == null && child.getUUID().equals(voyageYoung) && voyageBerth != null) incoming.put(child.getUUID(),have=voyageBerth.clone());
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
                    var from = child.position(); child.stopRiding(); crew.remove(child.getUUID());
                    if (l.assisted()) child.family().assistedClimbOut(this, from, to); else child.family().hop(this, from, to);
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
        if (carrying != null && passenger.getUUID().equals(carrying.child)) {
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
            int seat = youngPassengers().indexOf(passenger); var packed = youngSeats();
            if (seat < 0 || seat >= packed.size()) return;
            local = packed.get(seat);
        }
        var at = toWorld(local); var movement=seatMove(passenger);
        if(movement!=null){double time=Math.clamp(level().getGameTime()-movement.start(),0,movement.ticks());
            Vec3 from=toWorld(movement.from());at=movement.arc()?HatVessel.arc(from,at,movement.ticks(),time):movement.route().isEmpty()?from:walkingPosition(passenger,movement,time);}
        move.accept(passenger, at.x, at.y, at.z);
    }
    public static HatBoatEntity find(YukkuriEntity y) {
        if (y.getVehicle() instanceof HatBoatEntity boat) return boat;
        return owned(y);
    }
    public static HatBoatEntity owned(YukkuriEntity y) {
        UUID id = y.subspecies().boatId();
        return id != null && y.level() instanceof ServerLevel server && server.getEntity(id) instanceof HatBoatEntity boat
                && y.getUUID().equals(boat.ownerYukkuri()) ? boat : null;
    }
    public static HatBoatEntity createFor(YukkuriEntity owner, BlockPos water) {
        return createFor(owner, water, false);
    }
    public static HatBoatEntity launchFromBank(YukkuriEntity owner, BlockPos water) {
        if (owner.level().isClientSide || !HatBoatRoute.calmSurface(owner.level(), water)) return null;
        var delta=HatBoatRoute.surface(owner.level(),water).subtract(owner.position());
        float yaw=(float)(Math.toDegrees(Math.atan2(delta.z,delta.x))-90);
        owner.setYRot(yaw); owner.yBodyRot=yaw; owner.setYHeadRot(yaw);
        return createFor(owner, water, true);
    }
    private static HatBoatEntity createFor(YukkuriEntity owner, BlockPos water, boolean animated) {
        return createFor(owner,water,animated,null);
    }
    public static HatBoatEntity launchFromFather(YukkuriEntity child, HatBoatEntity home, BlockPos water) {
        if (child.getVehicle()!=home || !home.protects(child) || !home.onVoyageBerth(child) || home.voyageBerth(child)==null
                || !(home.ownerYukkuri().equals(child.family().father()) || home.ownerYukkuri().equals(child.family().mother()))
                || !child.aquaticLife().firstVoyage().launchingFrom(home)) return null;
        return createFor(child,water,true,home);
    }
    private static HatBoatEntity createFor(YukkuriEntity owner, BlockPos water, boolean animated, HatBoatEntity home) {
        // Only the aquatic Marisa sails her hat; land Marisa never take to the water (user correction 2026-10-14).
        if (!(owner.level() instanceof ServerLevel level) || owner.getVariant() != YukkuriVariant.MARISA
                || owner.subspecies().kind() != SubspeciesLife.Kind.AQUATIC_MARISA || !owner.aquaticLife().firstVoyage().mayLaunch()
                || owner.subspecies().boatId() != null
                || !owner.isAlive() || owner.isPassenger() && home==null || owner.isVehicle() || !owner.life().hasAccessory() || owner.isInWaterOrBubble()
                || home==null && !level.getFluidState(owner.blockPosition()).isEmpty() || owner.operations().active()
                || owner.distanceToSqr(Vec3.atCenterOf(water)) > 9 || !level.hasChunkAt(water)) return null;
        var fluid = level.getFluidState(water);
        if (!HatBoatRoute.calmSurface(level, water)) return null;
        var boat = ModEntities.HAT_BOAT.get().create(level);
        if (boat == null) return null;
        boat.entityData.set(SCALE, HatVessel.hatScale(owner));
        boat.setPos(water.getX()+.5, water.getY()+fluid.getHeight(level, water)+.025, water.getZ()+.5);
        var away=boat.position().subtract(owner.position()).multiply(1,0,1).normalize().scale(.10);
        boat.setPos(boat.position().add(away));
        float heading=owner.yBodyRot;
        if (animated) {
            // Meet the authored hat-off clip exactly in X/Z and orientation before the owner's hop starts.
            var placement=HatVessel.placement(true,HatVessel.Role.SELF);
            var offset=placement.offset().scale(owner.growthScale()*owner.getScale()/16).yRot((float)Math.toRadians(180-owner.yBodyRot));
            boat.setPos(owner.getX()+offset.x,boat.getY(),owner.getZ()+offset.z);
            if (!HatBoatRoute.calmSurface(level,BlockPos.containing(boat.getX(),water.getY(),boat.getZ()))) return null;
            heading+=placement.yaw();
        }
        if (!level.noCollision(boat, boat.getBoundingBox())) return null;
        boat.entityData.set(OWNER, Optional.of(owner.getUUID())); boat.entityData.set(SCALE, HatVessel.hatScale(owner)); boat.setYRot(heading);
        if (!level.addFreshEntity(boat)) return null;
        var retainedToken=owner.aquaticLife().firstVoyage().retainedHatToken();
        boat.accessoryToken = retainedToken==null?owner.life().stowAccessory():owner.life().stowAccessory(retainedToken);
        if (boat.accessoryToken == null || !animated && !owner.startRiding(boat, true)) {
            if (boat.accessoryToken != null) owner.life().returnAccessory(owner.getUUID(), boat.accessoryToken, null);
            boat.discard(); return null;
        }
        owner.subspecies().boatId(boat.getUUID()); owner.setPersistenceRequired(); owner.getNavigation().stop();
        owner.aquaticLife().firstVoyage().restoreHatCargo(boat);
        owner.aquaticLife().equip(boat); boat.heldTicks = 60;
        if (home != null) {
            // During the real hat-off launch the child still rides the parent and can hand over provisions.
            // Restored or carried provisions are used first; a parent never loses its final serving.
            if(boat.cargo().count()==0&&home.protects(owner)&&owner.aquaticLife().firstVoyage().launchingFrom(home))
                home.cargo().transferProvisionsTo(boat.cargo(),owner,2);
            if(!owner.family().transferHat(home,boat)) {
                // The real hat was set down, but the inter-hat flight could not begin: reclaim that same hat.
                boat.retrieveAfloat(owner,home);return null;
            }
        } else if (animated) owner.family().embark(boat);
        return boat;
    }
    /** Reclaim the same empty hat after its young is safe aboard a parent, including a rescued capsize. */
    public boolean retrieveAfloat(YukkuriEntity child, HatBoatEntity home) {
        if (level().isClientSide || capsizing() || !(isAfloat() || state()==State.CAPSIZED || state()==State.BEACHED)
                || isVehicle() || hasIncoming() || !child.getUUID().equals(ownerYukkuri())
                || !home.protects(child) || !home.young(child) || retrievalGrip(child)==null
                || !child.life().returnAccessory(ownerYukkuri(),accessoryToken,null)) return false;
        child.aquaticLife().firstVoyage().retainHat(accessoryToken,cargo.save());
        cargo.load(new CompoundTag());syncCargo(cargo.save());accessoryToken=null;
        child.subspecies().boatId(null);
        state(State.RESCUED); stopPaddling(); pullTicks=0; return true;
    }
    /** Same finite physical contact for a floating or stranded empty hat; ownership is not remote reach. */
    public Vec3 retrievalGrip(YukkuriEntity actor){
        if(actor==null||!ownsPhysicalHat(actor)||isVehicle()||hasIncoming()||capsizing())return null;
        Vec3 nearest=null;double distance=Double.POSITIVE_INFINITY;
        for(var local:GripRim.POINTS){var at=tiltedWorld(local);double d=actor.getBoundingBox().getCenter().distanceToSqr(at);if(d<distance){distance=d;nearest=at;}}
        return reachableGrip(actor,nearest);
    }
    /** Parent-centre docking target with actual hull/AABB clearance; it grants no remote retrieval. */
    public Vec3 retrievalApproach(HatBoatEntity parentHat,YukkuriEntity child){
        if(parentHat==null||parentHat==this||child==null||!ownsPhysicalHat(child)||!parentHat.protects(child)
                ||!parentHat.young(child)||isVehicle()||hasIncoming()||capsizing()||selfRescueHeld())return null;
        var toward=parentHat.position().subtract(position()).multiply(1,0,1).normalize();
        if(toward.lengthSqr()<.01)toward=child.position().subtract(position()).multiply(1,0,1).normalize();
        if(toward.lengthSqr()<.01)return null;
        double theirs=Math.max(brimReachToward(toward),boxReach(toward));
        double ours=Math.max(parentHat.brimReachToward(toward.scale(-1)),parentHat.boxReach(toward.scale(-1)));
        var at=position().add(toward.scale(theirs+ours+.035));
        return new Vec3(at.x,parentHat.getY(),at.z);
    }
    private double boxReach(Vec3 toward){
        double x=getBoundingBox().getXsize()*.5,z=getBoundingBox().getZsize()*.5;
        return Math.min(Math.abs(toward.x)<1e-8?Double.POSITIVE_INFINITY:x/Math.abs(toward.x),
                Math.abs(toward.z)<1e-8?Double.POSITIVE_INFINITY:z/Math.abs(toward.z));
    }
    public static HatBoatEntity tryLaunchNearby(YukkuriEntity owner) {
        for (var p : BlockPos.betweenClosed(owner.blockPosition().offset(-2,-1,-2), owner.blockPosition().offset(2,0,2))) {
            HatBoatEntity boat = launchFromBank(owner, p); if (boat != null) return boat;
        }
        return null;
    }
    /** A Marisa's braid sets her hat down upside down to catch young: pressed on dry ground, or floated at a bank (aquatic). */
    public static HatBoatEntity setDown(YukkuriEntity owner, Vec3 at, float yaw, HatVessel.Mode mode, HatVessel.Role role) {
        if (!(owner.level() instanceof ServerLevel level) || owner.getVariant() != YukkuriVariant.MARISA || !owner.isAlive()
                || owner.isPassenger() || !owner.life().hasAccessory() || owner.subspecies().boatId() != null) return null;
        var spot = BlockPos.containing(at);
        if (!level.hasChunkAt(spot)) return null;
        double y;
        if (mode == HatVessel.Mode.FLOAT) {
            // The water surface below the bank she stands on.
            BlockPos surface = null;
            for (int down = 0; down < 3 && surface == null; down++)
                if (level.getFluidState(spot.below(down)).is(FluidTags.WATER) && level.getFluidState(spot.below(down).above()).isEmpty()) surface = spot.below(down);
            if (surface == null) return null;
            y = surface.getY()+level.getFluidState(surface).getHeight(level, surface)+.025;
        } else {
            if (!level.getFluidState(spot).isEmpty() || !level.getBlockState(spot.below()).isFaceSturdy(level, spot.below(), net.minecraft.core.Direction.UP)) return null;
            y = spot.getY();
        }
        var hat = ModEntities.HAT_BOAT.get().create(level);
        if (hat == null) return null;
        hat.setPos(at.x, y, at.z); hat.setYRot(yaw); hat.role = role; hat.mooring = hat.position();
        hat.entityData.set(OWNER, Optional.of(owner.getUUID())); hat.entityData.set(SCALE, HatVessel.hatScale(owner));
        hat.state(mode == HatVessel.Mode.FLOAT ? State.MOORED : State.CRADLE);
        if (!level.addFreshEntity(hat)) return null;
        hat.accessoryToken = owner.life().takeAccessory(null);
        if (hat.accessoryToken == null) { hat.discard(); return null; }
        return hat;
    }
    /** Put the hat back on: the young climb out first, onto the bank beside a floated hat. */
    public boolean retrieve(YukkuriEntity owner) {
        if (level().isClientSide || !catches() || !owner.getUUID().equals(ownerYukkuri()) || owner.distanceToSqr(this) > 16) return false;
        if (hasIncoming()) return false;
        if (isMoored()) { var spots=landingSpots(owner.blockPosition(), owner); if (spots==null) return false; landCrew(spots); }
        for (var p : java.util.List.copyOf(getPassengers())) p.stopRiding();
        crew.clear();
        if (!owner.life().returnAccessory(ownerYukkuri(), accessoryToken, null)) return false;
        state(State.RESCUED); return true;
    }
    public boolean board(YukkuriEntity owner) {
        if (level().isClientSide || unsteadyMotion() || !owner.getUUID().equals(ownerYukkuri()) || owner.distanceToSqr(this) > 4
                || !isAfloat() || !owner.life().matchesAccessory(ownerYukkuri(), accessoryToken)) return false;
        if (!owner.startRiding(this, true)) return false;
        owner.setYRot(getYRot()); owner.yBodyRot=getYRot(); owner.setYHeadRot(getYRot()); return true;
    }
    public boolean ownsPhysicalHat(YukkuriEntity actor){return actor!=null&&actor.getUUID().equals(ownerYukkuri())
            &&actor.life().matchesAccessory(ownerYukkuri(),accessoryToken);}
    private static final class GripRim {
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
    private Vec3 rimPoint(Vec3 local){return tiltedWorld(local).subtract(0,rimLift(local),0);}
    private Vec3 nearestGrip(YukkuriEntity actor){
        if(actor==null||!(ownsPhysicalHat(actor)||young(actor))||!(isAfloat()||state()==State.CAPSIZED)||capsizing()||deploying())return null;
        Vec3 best=null;double score=Double.POSITIVE_INFINITY;
        for(var local:GripRim.POINTS){
            var point=rimPoint(local);double gap=actor.getBoundingBox().getCenter().distanceToSqr(point);
            if(gap<score){score=gap;best=point;}
        }
        return best;
    }
    /** The actual visible rim point the mouth will take hold of; ordinary body reach and block sight are both required. */
    public Vec3 selfRescueGripLocal(YukkuriEntity actor){
        var world=selfRescueGrip(actor);if(world==null)return null;
        for(var local:GripRim.POINTS)if(rimPoint(local).distanceToSqr(world)<1e-8)return local;
        return null;
    }
    public Vec3 selfRescueGrip(YukkuriEntity actor,Vec3 localGrip){
        if(localGrip==null||GripRim.POINTS.stream().noneMatch(p->p.distanceToSqr(localGrip)<1e-8)
                ||!(ownsPhysicalHat(actor)||young(actor))||capsizing()||deploying())return null;
        return reachableGrip(actor,rimPoint(localGrip));
    }
    /** The rim point nearest this body's mouth that a few body-lengths of nudging can take in the teeth. */
    public Vec3 selfRescueGrip(YukkuriEntity actor){
        if(nearestGrip(actor)==null)return null;
        Vec3 best=null;double score=Double.POSITIVE_INFINITY;
        var mouth=io.github.longshou.mcyukkuri.family.BraidRescueGeometry.mouthPoint(actor,actor.position(),actor.yBodyRot);
        for(var local:GripRim.POINTS){
            var grip=rimPoint(local);
            if(reachableGrip(actor,grip)==null)continue;
            double distance=mouth.distanceToSqr(grip);if(distance<score){best=grip;score=distance;}
        }
        return best;
    }
    private Vec3 reachableGrip(YukkuriEntity actor,Vec3 grip){
        if(grip==null||!actor.getBoundingBox().inflate(.12).contains(grip))return null;
        var hit=level().clip(new net.minecraft.world.level.ClipContext(actor.getEyePosition(),grip,
                net.minecraft.world.level.ClipContext.Block.COLLIDER,net.minecraft.world.level.ClipContext.Fluid.NONE,actor));
        return hit.getType()==net.minecraft.world.phys.HitResult.Type.MISS?grip:null;
    }
    // Boarding's changing root pose has its own full two-segment reach solver; do not substitute the neutral removal root.
    public Vec3 boardingGrip(YukkuriEntity actor){return reachableGrip(actor,nearestGrip(actor));}
    /** A wet owner first reaches a nearby supported patch, then walks to the stern after settling. */
    public Vec3 reserveOwnBoardingSeat(YukkuriEntity actor,Vec3 actualFrom){
        if(level().isClientSide||!ownsPhysicalHat(actor)||actor.isPassenger()||!isAfloat()||unsteadyMotion()||actualFrom==null)return null;
        var obstacles=takenExcept(actor.getUUID());double radius=radiusOf(actor);var body=HatVessel.bodySpace(actor,hullScale(),unit());
        Vec3 best=null;double cost=Double.POSITIVE_INFINITY;
        for(double x=-HatVessel.BRIM[0]+radius;x<=HatVessel.BRIM[0]-radius;x+=.25)
            for(double z=-HatVessel.BRIM[1]+radius;z<=HatVessel.BRIM[1]-radius;z+=.25){
                double y=HatVessel.footprint(mode(),x,z,radius);if(!Double.isFinite(y))continue;
                var at=new Vec3(x,y,z);if(!HatVessel.fits(mode(),at,radius,obstacles,body))continue;
                double distance=toWorld(at).distanceToSqr(actualFrom);if(distance<cost){cost=distance;best=at;}
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
    /** Choose a real close landing, instead of reusing a far retained berth after jump limits tighten. */
    public Vec3 reserveBoardingSeat(YukkuriEntity child,Vec3 actualFrom,boolean emergency){
        if(level().isClientSide||child==null||actualFrom==null||!young(child)||!isAfloat()||unsteadyMotion()
                ||!incoming.containsKey(child.getUUID())&&youngAboard()+incoming.size()>=MAX_YOUNG)return null;
        if(emergency&&!requestEmergencyBerth(child))return null;
        var obstacles=takenExcept(child.getUUID());var parent=owner();
        if(parent!=null&&parent.getVehicle()==this)obstacles.add(rescueBody(parent,toLocal(parent.position())));
        var body=HatVessel.bodySpace(child,hullScale(),unit());double radius=radiusOf(child);Vec3 best=null;double cost=Double.POSITIVE_INFINITY;
        for(double x=-HatVessel.BRIM[0]+radius;x<=HatVessel.BRIM[0]-radius;x+=.25)
            for(double z=-HatVessel.BRIM[1]+radius;z<=HatVessel.BRIM[1]-radius;z+=.25){
                double y=HatVessel.footprint(mode(),x,z,radius);if(!Double.isFinite(y)||Math.abs(HatVessel.surface(mode(),x,z)-y)>1e-4)continue;
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
    /** A swimming destination outside the true tilted brim; it never moves or boards its caller. */
    public Vec3 ownGripApproach(YukkuriEntity actor){
        var grip=nearestGrip(actor);if(grip==null)return null;
        var out=actor.position().subtract(grip).multiply(1,0,1).normalize();
        if(out.horizontalDistanceSqr()<.01)out=grip.subtract(position()).multiply(1,0,1).normalize();
        return new Vec3(grip.x+out.x*(actor.getBbWidth()*.5+.03),actor.getY(),grip.z+out.z*(actor.getBbWidth()*.5+.03));
    }
    /** Empty, already-spilled capsizes can be physically righted without replacing their token or cargo. */
    public boolean setSelfRighting(YukkuriEntity actor,float progress,Vec3 localGrip){
        if(level().isClientSide||!ownsPhysicalHat(actor)||state()!=State.CAPSIZED||!capsizeReleased||isVehicle()||hasIncoming()
                ||selfRescueGrip(actor,localGrip)==null||!HatBoatRoute.calmSurface(level(),BlockPos.containing(position().add(0,-.12,0))))return false;
        var m=entityData.get(MOTION).copy();float previous=m.getFloat("RightProgress");
        if(!m.contains("RightGripX")){
            point(m,"RightGrip",localGrip);point(m,"RightAnchor",tiltedWorld(localGrip));
            // A previously dropped hat has finished its free tumble before a new real grip is accepted.
            m.remove("LooseAt");m.remove("LoosePitch");m.remove("LooseSquash");
        }else if(point(m,"RightGrip").distanceToSqr(localGrip)>1e-8)return false;
        m.putFloat("RightPrevious",previous);m.putFloat("RightProgress",Math.clamp(progress,0,1));m.putLong("RightTouched",level().getGameTime());
        var before=entityData.get(MOTION);entityData.set(MOTION,m);
        var offset=tiltedWorld(localGrip).subtract(position());var centre=point(m,"RightAnchor").subtract(offset);
        var movement=centre.subtract(position());
        if(movement.lengthSqr()>.12*.12||!level().noCollision(this,getBoundingBox().move(movement))){entityData.set(MOTION,before);return false;}
        // Rotating around the actually held rim translates the same boat centre. Clients apply
        // motionRoll/Pitch at that synced centre, yielding exactly this world-space grasp pivot.
        setPos(centre);setDeltaMovement(Vec3.ZERO);stopPaddling();return true;
    }
    public boolean finishSelfRighting(YukkuriEntity actor){
        if(level().isClientSide||!ownsPhysicalHat(actor)||!selfRighting()||entityData.get(MOTION).getFloat("RightProgress")<1
                ||selfRescueGrip(actor,point(entityData.get(MOTION),"RightGrip"))==null)return false;
        state(State.AFLOAT);flooding=0;entityData.set(MOTION,new CompoundTag());capsizeReleased=true;stopPaddling();return true;
    }
    private void tickSelfRighting(){
        var m=entityData.get(MOTION);if(!selfRighting()||level().getGameTime()-m.getLong("RightTouched")<=1)return;
        var copy=m.copy();float progress=m.getFloat("RightProgress");copy.putFloat("RightPrevious",progress);
        copy.putFloat("RightProgress",Math.max(0,progress-.04f));
        if(progress<=.04){
            copy.remove("RightProgress");copy.remove("RightPrevious");copy.remove("RightTouched");
            for(var axis:new String[]{"X","Y","Z"}){copy.remove("RightGrip"+axis);copy.remove("RightAnchor"+axis);}
        }
        entityData.set(MOTION,copy);
    }
    public record SelfRescueDeployment(Vec3 position,float yaw,SelfRescueHatGeometry.Plan path){}
    /** Plan before the worn hat moves, so rendering and the eventual real vessel share one endpoint. */
    public static SelfRescueDeployment planSelfRescueDeployment(YukkuriEntity actor){
        if(!(actor.level() instanceof ServerLevel level)||!HatVessel.aquatic(actor)||!actor.isAlive()||!actor.isInWaterOrBubble()
                ||actor.isPassenger()||actor.subspecies().boatId()!=null||!actor.life().hasAccessory())return null;
        var water=BlockPos.containing(actor.position());
        if(!HatBoatRoute.calmSurface(level,water))return null;
        var path=SelfRescueHatGeometry.plan(actor,HatBoatRoute.surface(level,water).y,
                candidate->selfRescueDeploymentReachable(actor,new SelfRescueDeployment(candidate.finalCentre(),candidate.boatYaw(),candidate)));
        return path==null?null:new SelfRescueDeployment(path.finalCentre(),path.boatYaw(),path);
    }
    /** The spot in front of the face must be calm open water the hull can float in; nothing is reached for, so no braid length applies. */
    public static boolean selfRescueDeploymentReachable(YukkuriEntity actor,SelfRescueDeployment plan){
        if(plan==null||!(actor.level() instanceof ServerLevel level))return false;
        if(plan.path()==null)return false;
        var boat=ModEntities.HAT_BOAT.get().create(level);if(boat==null)return false;
        boat.entityData.set(SCALE,HatVessel.hatScale(actor)*actor.getScale());boat.setPos(plan.position());boat.setYRot(plan.yaw());
        var water=BlockPos.containing(plan.position().add(0,-.12,0));
        return HatBoatRoute.calmSurface(level,water)&&level.getWorldBorder().isWithinBounds(water)
                &&Math.abs(HatBoatRoute.surface(level,water).y-plan.position().y)<.08
                &&level.noCollision(boat,boat.getBoundingBox());
    }
    /** Revalidate exactly the planned endpoint. Failure leaves the one original hat on its owner. */
    public static HatBoatEntity deployForSelfRescue(YukkuriEntity actor,SelfRescueDeployment plan){
        return deployForSelfRescue(actor,plan,null,SelfRescueHatGeometry.REMOVE_TICKS);
    }
    /** Custody moves to the real vessel once the hat has been shaken clear of the head. */
    public static HatBoatEntity deployForSelfRescue(YukkuriEntity actor,SelfRescueDeployment plan,SelfRescueHatGeometry.Pose heldPose,float elapsed){
        if(!(actor.level() instanceof ServerLevel level)||!HatVessel.aquatic(actor)||!actor.isAlive()||!actor.isInWaterOrBubble()
                ||actor.isPassenger()||actor.subspecies().boatId()!=null||!actor.life().hasAccessory()||!selfRescueDeploymentReachable(actor,plan))return null;
        var water=BlockPos.containing(plan.position().add(0,-.12,0));
        if(!HatBoatRoute.calmSurface(level,water)||!level.getWorldBorder().isWithinBounds(water)
                ||Math.abs(HatBoatRoute.surface(level,water).y-plan.position().y)>.08)return null;
        var boat=ModEntities.HAT_BOAT.get().create(level);if(boat==null)return null;
        boat.entityData.set(SCALE,HatVessel.hatScale(actor)*actor.getScale());boat.role=HatVessel.Role.SELF;
        boat.setPos(plan.position());boat.setYRot(plan.yaw());
        if(!level.noCollision(boat,boat.getBoundingBox()))return null;
        boat.entityData.set(OWNER,Optional.of(actor.getUUID()));
        if(!level.addFreshEntity(boat))return null;
        var retained=actor.aquaticLife().firstVoyage().retainedHatToken();boat.accessoryToken=actor.life().stowAccessory(retained);
        if(boat.accessoryToken==null){boat.discard();return null;}
        actor.subspecies().boatId(boat.getUUID());actor.setPersistenceRequired();
        actor.aquaticLife().firstVoyage().restoreHatCargo(boat);actor.aquaticLife().equip(boat);
        // The held head hat has already turned and lowered to this exact upright pose. Never flip it a second time.
        boat.entityData.set(MOTION,new CompoundTag());
        boat.setDeltaMovement(actor.getDeltaMovement().multiply(.65,0,.65));
        if(heldPose!=null&&!boat.setSelfRescueCarryPose(actor,heldPose,elapsed))boat.releaseSelfRescueCarry();
        return boat;
    }
    /** The shaken-off hat follows the same world-space prop origin as the worn-hat geometry. */
    public boolean setSelfRescueCarryPose(YukkuriEntity actor,SelfRescueHatGeometry.Pose pose,float elapsed){
        if(level().isClientSide||!ownsPhysicalHat(actor)||isVehicle()||hasIncoming()||pose==null)return false;
        double q=Math.clamp((elapsed-SelfRescueHatGeometry.SHAKE)/SelfRescueHatGeometry.TURN,0,1);q=q*q*(3-2*q);
        var offset=pose.rotation().transform(new org.joml.Vector3f(0,(float)(HatVessel.FREEBOARD*unit()),0));
        var centre=pose.origin().subtract(new Vec3(offset.x,offset.y,offset.z));
        var m=entityData.get(MOTION).copy();
        m.putFloat("PreviousPitch",m.getBoolean("SelfHeld")?m.getFloat("HeldPitch"):(float)(-180*(1-q)));
        m.putFloat("PreviousSquash",m.getBoolean("SelfHeld")?m.getFloat("HeldSquash"):pose.squash());
        m.putBoolean("SelfHeld",true);m.putFloat("HeldPitch",(float)(-180*(1-q)));m.putFloat("HeldSquash",pose.squash());m.putLong("HeldTouched",level().getGameTime());
        point(m,"PreviousCentre",m.contains("HeldCentreX")?point(m,"HeldCentre"):centre);point(m,"HeldCentre",centre);
        setPos(centre);entityData.set(MOTION,m);setDeltaMovement(Vec3.ZERO);stopPaddling();return true;
    }
    public void finishSelfRescueCarry(YukkuriEntity actor){
        if(level().isClientSide||!ownsPhysicalHat(actor)||!selfRescueHeld())return;
        entityData.set(MOTION,new CompoundTag());heldTicks=0;setDeltaMovement(actor.getDeltaMovement().multiply(.65,0,.65));
    }
    /** A failed grip leaves the same physical hat at its actual pose and spills only via the normal capsize lifecycle. */
    public void releaseSelfRescueCarry(){
        if(level().isClientSide||!selfRescueHeld())return;
        float pitch=motionPitch(1),squash=hullSquash(1);entityData.set(MOTION,new CompoundTag());
        startCapsize(1);var m=entityData.get(MOTION).copy();m.putLong("LooseAt",level().getGameTime());
        point(m,"HeldCentre",position());point(m,"PreviousCentre",position());
        m.putFloat("LoosePitch",pitch);m.putFloat("LooseSquash",squash);entityData.set(MOTION,m);
    }
    public void capsize() {
        int side=entityData.get(MOTION).contains("Side")?entityData.get(MOTION).getInt("Side"):((getId()&1)==0?1:-1);
        startCapsize(side);
    }
    private void startCapsize(int side){
        if(level().isClientSide||state()==State.CAPSIZED||state()==State.RESCUED||state()==State.REMOVED||catches())return;
        boolean wasHeld=selfRescueHeld();float heldPitch=wasHeld?motionPitch(1):0,heldSquash=wasHeld?hullSquash(1):HatVessel.SQUASH_FLOAT;
        // Freeze the actual current poses, including a child being carried or partway through a hop.
        for(var p:getPassengers()){
            var at=toLocal(p.position());
            if(p.getUUID().equals(ownerYukkuri()))ownerServiceSeat=at;
            else seats.put(p.getUUID(),new double[]{at.x,at.y,at.z,radiusOf(p)});
            if(p instanceof YukkuriEntity y){y.getNavigation().stop();y.showReaction(y.family().adult()?"surprise":"teary",y.family().adult()?"wait_family":"afraid",60);}
        }
        seatMoves.clear();ownerSeatMove=null;carryTrip=null;recoverCarry=false;
        serviceSeats.clear();returningSeats.clear();ownerToiletSeat=false;leaving.clear();syncSeats();
        var m=new CompoundTag();m.putLong("CapAt",level().getGameTime());m.putInt("Side",side<0?-1:1);
        if(wasHeld){
            m.putLong("LooseAt",level().getGameTime());m.putFloat("LoosePitch",heldPitch);m.putFloat("LooseSquash",heldSquash);
            point(m,"HeldCentre",position());point(m,"PreviousCentre",position());
        }
        entityData.set(MOTION,m);capsizeReleased=false;rescueOwnerHeld=false;
        state(State.CAPSIZED);flooding=100;stopPaddling();heldTicks=0;pullTicks=0;pullTarget=null;
        setDeltaMovement(getDeltaMovement().scale(.25));
    }
    /** Same yaw * roll * pitch transform as the client, used for each real release point. */
    private Vec3 tiltedWorld(Vec3 local){
        float current=selfRighting()?1:0;
        double roll=Math.toRadians(motionRoll(current)),pitch=Math.toRadians(motionPitch(current));
        var p=local.scale(unit());
        double py=p.y*Math.cos(pitch)-p.z*Math.sin(pitch),pz=p.y*Math.sin(pitch)+p.z*Math.cos(pitch);
        return position().add(new Vec3(p.x*Math.cos(roll)-py*Math.sin(roll),p.x*Math.sin(roll)+py*Math.cos(roll),pz).yRot(turn()));
    }
    private void tickCapsize(){
        if(state()!=State.CAPSIZED||capsizeReleased||!entityData.get(MOTION).contains("CapAt")
                ||motionAge(entityData.get(MOTION),"CapAt",0)<CAPSIZE_RELEASE_TICK)return;
        // Mark before mutating/spawning: re-entry and subsequent damage cannot spill these items twice.
        capsizeReleased=true;
        int side=entityData.get(MOTION).getInt("Side")<0?-1:1;
        var downhill=new Vec3(-side,0,0).yRot(turn());var along=new Vec3(0,0,1).yRot(turn());
        var passengers=java.util.List.copyOf(getPassengers());var positions=new java.util.ArrayList<Vec3>();
        for(var p:passengers)positions.add(tiltedWorld(toLocal(p.position())));
        for(int i=0;i<passengers.size();i++){
            var p=passengers.get(i);var at=positions.get(i).add(downhill.scale(.035+i*.008));
            p.stopRiding();p.setPos(at.x,at.y,at.z);
            p.setDeltaMovement(getDeltaMovement().add(downhill.scale(.07+i*.013))
                    .add(along.scale((i-(passengers.size()-1)*.5)*.018)).add(0,.025-i*.006,0));
            p.hasImpulse=true;
            if(p instanceof YukkuriEntity y)y.waterDistress().plunge(getUUID(),null);
            if(level() instanceof ServerLevel server)server.sendParticles(net.minecraft.core.particles.ParticleTypes.SPLASH,
                    at.x,at.y,at.z,Math.max(4,(int)(p.getBbWidth()*12)),p.getBbWidth()*.3,.04,p.getBbWidth()*.3,.08);
        }
        var berths=cargoBerths();var drops=new java.util.ArrayList<net.minecraft.world.item.ItemStack>(cargo.portions());
        if(cargo.paddle())drops.add(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.STICK));
        cargo.load(new CompoundTag());syncCargo(cargo.save());
        for(int i=0;i<drops.size();i++){
            var local=i<berths.size()?berths.get(i).position():new Vec3(0,HatVessel.OWNER_Y,HatVessel.OPENING[3]*.5);
            var at=tiltedWorld(local).add(downhill.scale(.025));
            var item=new net.minecraft.world.entity.item.ItemEntity(level(),at.x,at.y,at.z,drops.get(i));
            item.setDeltaMovement(getDeltaMovement().add(downhill.scale(.08+i*.008))
                    .add(along.scale((i-(drops.size()-1)*.5)*.014)).add(0,.03,0));
            item.setDefaultPickUpDelay();level().addFreshEntity(item);
        }
    }
    private YukkuriEntity owner() {
        return ownerYukkuri() != null && level() instanceof ServerLevel server && server.getEntity(ownerYukkuri()) instanceof YukkuriEntity y ? y : null;
    }
    public boolean rescue(BlockPos dryStand, UUID rescuer) {
        var y = owner();
        if (level().isClientSide || capsizing() || y == null || !y.isAlive() || state() == State.RESCUED || state() == State.REMOVED
                || y.distanceToSqr(this)>36 || distanceToSqr(Vec3.atBottomCenterOf(dryStand))>36
                || !level().hasChunkAt(dryStand) || !level().getFluidState(dryStand).isEmpty()
                || !level().getBlockState(dryStand.below()).isFaceSturdy(level(),dryStand.below(),net.minecraft.core.Direction.UP)) return false;
        var target = Vec3.atBottomCenterOf(dryStand);
        if (!level().noCollision(y,y.getBoundingBox().move(target.subtract(y.position())))) return false;
        if (hasIncoming()) return false;
        var spots = landingSpots(dryStand, y); if (spots == null) return false;
        if (!y.life().returnAccessory(ownerYukkuri(), accessoryToken, rescuer)) return false;
        y.life().wakeInHat(); y.stopRiding(); y.teleportTo(target.x,target.y,target.z); y.setDeltaMovement(Vec3.ZERO);
        landCrew(spots);
        cargo.unload(y); y.aquaticLife().landed();
        y.subspecies().boatId(null); state(State.RESCUED); stopPaddling(); pullTicks = 0; return true;
    }
    /** Reserve all dry places before returning the token or moving anybody. */
    private java.util.Map<UUID,Vec3> landingSpots(BlockPos stand, Entity owner) {
        if (!(level() instanceof ServerLevel server)) return null;
        // Waking may grow a juvenile. Reserve using the resulting dimensions, not yesterday's smaller body.
        if (owner instanceof YukkuriEntity y) y.life().wakeInHat();
        for (var id : crew) if (server.getEntity(id) instanceof YukkuriEntity child && child.isAlive() && child.distanceToSqr(this)<=64)
            child.life().wakeInHat();
        var result=new java.util.LinkedHashMap<UUID,Vec3>();
        var used=new java.util.ArrayList<net.minecraft.world.phys.AABB>();
        used.add(owner.getBoundingBox().move(Vec3.atBottomCenterOf(stand).subtract(owner.position())).inflate(.04));
        for (var id : crew) {
            if (!(server.getEntity(id) instanceof YukkuriEntity child) || !child.isAlive() || child.distanceToSqr(this)>64
                    || child.isPassenger() && child.getVehicle()!=this) continue;
            for (var spot : BlockPos.betweenClosed(stand.offset(-2,0,-2),stand.offset(2,1,2))) {
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
    /** Bring the young ashore with their mother, each on its own dry spot around the stand. */
    private void landCrew(java.util.Map<UUID,Vec3> spots) {
        if (!(level() instanceof ServerLevel server)) return;
        for (var id : java.util.List.copyOf(crew)) {
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
            return InteractionResult.PASS;
        }
        if (level().isClientSide) return InteractionResult.SUCCESS;
        if (catches()) return recoverOrphans(player) ? InteractionResult.CONSUME : InteractionResult.PASS;
        if(isAfloat()&&!player.isShiftKeyDown()){rock(player);return InteractionResult.CONSUME;}
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
        if (getTags().contains("pm_scene_hat")) {
            // Scripted museum hulls use real passenger geometry without running wild boat ecology.
            var actor = owner();
            if (actor == null || !actor.isAlive() || !actor.isNoAi() || !actor.getTags().contains("pm_actor")) discard();
            else setDeltaMovement(Vec3.ZERO);
            return;
        }
        if (getTags().contains(io.github.longshou.mcyukkuri.command.BirthScene.HAT_TAG)) {
            // Temporary museum prop has no accessory token or autonomous ecology.
            var actor = owner();
            if (actor == null || actor.birthScene() == null) discard();
            else setDeltaMovement(Vec3.ZERO);
            return;
        }
        tickDip();
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
        if (tickCount>60 && tickCount%20==0 && voyageYoung!=null && level() instanceof ServerLevel server
                && (!(server.getEntity(voyageYoung) instanceof YukkuriEntity y) || !y.isAlive() || !y.aquaticLife().firstVoyage().keepsBerth(this))) releaseVoyageBerth(voyageYoung);
        if(state()!=State.CAPSIZED){
            tickCarry();
            crowding.tick();
            if (tickCount % 10 == 0&&!crowding.contesting()) tendSeats();
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
                        double stroke = .004+.008*Math.max(0, Math.sin((level().getGameTime()-captain.getAnimationStartTick())*Math.PI/16));
                        motion = motion.add(forward.scale(stroke));
                        double speed = motion.horizontalDistance(); if (speed > .08) motion = new Vec3(motion.x*.08/speed, motion.y, motion.z*.08/speed);
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
            if (state()!=State.CAPSIZED && (level().getFluidState(pos.above()).is(FluidTags.WATER) || flow.y<-.1)) {
                if (++flooding>=60) capsize();
            } else if (state()!=State.CAPSIZED) flooding=Math.max(0,flooding-1);
        } else {
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
            boolean interiorBusy=carryTrip!=null || rescueOwnerHeld || !serviceSeats.isEmpty() || !returningSeats.isEmpty()
                    || ownerToiletSeat || changingOwnerSeat() || y.aquaticLife().care().active()
                    || youngPassengers().stream().anyMatch(this::changingSeat);
            if (!paddling() && !unsteadyMotion() && heldTicks==0 && voyageYoung==null && !interiorBusy) setYRot(y.yBodyRot);
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
            tag.putBoolean("CapsizeReleased",capsizeReleased);
            if(!capsizeReleased){
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
        if(voyageYoung!=null&&voyageBerth!=null){
            tag.putUUID("VoyageYoung",voyageYoung);var berth=new CompoundTag();
            berth.putDouble("X",voyageBerth[0]);berth.putDouble("Y",voyageBerth[1]);berth.putDouble("Z",voyageBerth[2]);berth.putDouble("R",voyageBerth[3]);tag.put("VoyageBerth",berth);
        }
        var young=new net.minecraft.nbt.ListTag();for(var id:crew)young.add(net.minecraft.nbt.NbtUtils.createUUID(id));tag.put("Crew",young);
    }
    @Override protected void readAdditionalSaveData(CompoundTag tag) {
        serviceSeats.clear(); returningSeats.clear(); seatMoves.clear(); seats.clear(); incoming.clear();
        carryTrip = null; recoverCarry=false;
        rescueOwnerHeld=false;
        ownerServiceSeat = null; ownerSeatMove = null; ownerToiletSeat = false; seatRegion = "";
        cramped.clear();crowding.load(tag.getCompound("Crowding"));
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
        capsizeReleased=state()==State.CAPSIZED;
        if(state()==State.CAPSIZED&&tag.contains("CapsizeAge")){
            int age=Math.clamp(tag.getInt("CapsizeAge"),0,CAPSIZE_TICKS);
            motion.putLong("CapAt",level().getGameTime()-age);capsizeReleased=tag.getBoolean("CapsizeReleased");
            if(!capsizeReleased)for(var raw:tag.getList("CapsizeSeats",net.minecraft.nbt.Tag.TAG_COMPOUND)){
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
            state(State.CAPSIZED);capsizeReleased=false;motion.putLong("CapAt",level().getGameTime());motion.putLong("LooseAt",level().getGameTime());
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
                int phase=Math.clamp(c.getInt("Phase"),0,3);
                Vec3 offset=phase==3?now.subtract(ownerNow):point(c,"Offset");
                carryTrip=new CarryTrip(c.getUUID("Child"),phase==1?now:original,point(c,"To"),point(c,"Pickup"),point(c,"OwnerTo"),offset,level().getGameTime());
                carryTrip.lastAnchor=now;
                if(c.contains("FacingX"))carryTrip.facing=point(c,"Facing");
                carryTrip.phase=phase; recoverCarry=phase==0 || phase==2;
                ownerServiceSeat=ownerNow;
                for(var raw:tag.getList("CarryPassengerSeats",net.minecraft.nbt.Tag.TAG_COMPOUND)) {
                    var saved=(CompoundTag)raw;
                    if(saved.hasUUID("Id")) {
                        Vec3 seat=point(saved,"Seat");
                        seats.put(saved.getUUID("Id"),new double[]{seat.x,seat.y,seat.z,saved.getDouble("Radius")});
                    }
                }
                // Loaded interactions earn no care rewards. Safely finish the owned transport, then restore normal seating.
                returningSeats.put(carryTrip.child,new ServiceSeat(new double[]{original.x,original.y,original.z,0},"",
                        new double[]{carryTrip.destination.x,carryTrip.destination.y,carryTrip.destination.z,0},null,false,false));
                syncSeats();
            }
        }
        voyageYoung=tag.hasUUID("VoyageYoung")?tag.getUUID("VoyageYoung"):null;voyageBerth=null;
        if(voyageYoung!=null&&tag.contains("VoyageBerth")){
            var berth=tag.getCompound("VoyageBerth");voyageBerth=new double[]{berth.getDouble("X"),berth.getDouble("Y"),berth.getDouble("Z"),berth.getDouble("R")};
        }else voyageYoung=null;
        mooring=tag.contains("MooringX")?new Vec3(tag.getDouble("MooringX"),0,tag.getDouble("MooringZ")):null;
        crew.clear();for(var raw:tag.getList("Crew",net.minecraft.nbt.Tag.TAG_INT_ARRAY))crew.add(net.minecraft.nbt.NbtUtils.loadUUID(raw));
    }
}
