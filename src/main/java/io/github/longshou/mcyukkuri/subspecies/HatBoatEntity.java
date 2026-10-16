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
 *  the aquatic Marisa's boat: she rests in its opening at the stern and her young ride in its dry inside, under her at night.
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
    public static final int MAX_YOUNG = 5;
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
        Vec3 lastAnchor;
        int phase, contact;
        long at;
        CarryTrip(UUID child, Vec3 from, Vec3 destination, Vec3 pickup, Vec3 ownerDestination, Vec3 offset, long at) {
            this.child=child; this.from=from; this.destination=destination; this.pickup=pickup;
            this.ownerDestination=ownerDestination; this.offset=offset; this.at=at;
            this.lastAnchor=from;
        }
    }
    private CarryTrip carryTrip;
    private boolean recoverCarry;
    private Vec3 ownerServiceSeat;
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
        builder.define(CARGO, new CompoundTag()); builder.define(PADDLING, false);
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
    void syncCargo(CompoundTag data) { entityData.set(CARGO, data); }
    public boolean paddling() { return entityData.get(PADDLING); }
    public void stopPaddling() { route.clear(); entityData.set(PADDLING, false); }
    public boolean paddleTo(Vec3 destination) {
        if (level().isClientSide || !isAfloat() || !cargo.paddle() || towed() || carryTrip != null) return false;
        return paddleAlong(destination == null ? java.util.List.of() : HatBoatRoute.find(this, destination));
    }
    /** A route already checked by the supervised first-voyage planner. */
    boolean paddleAlong(java.util.List<Vec3> path) {
        if (level().isClientSide || !isAfloat() || !cargo.paddle() || towed()) return false;
        stopPaddling(); route.addAll(path);
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
    public boolean hasRoomForYoung() {
        int away = voyageYoung != null && !seats.containsKey(voyageYoung) && !incoming.containsKey(voyageYoung) ? 1 : 0;
        return (isAfloat() || state() == State.BEACHED || catches()) && youngAboard()+incoming.size()+away < MAX_YOUNG;
    }
    /** Room for this young: a free place, or the one kept for it. */
    public boolean hasRoomFor(YukkuriEntity child) { return child.getUUID().equals(voyageYoung) || incoming.containsKey(child.getUUID()) || hasRoomForYoung(); }
    /** A newborn delivered in the hat stays aboard with its mother. */
    public boolean boardYoung(YukkuriEntity child) {
        if (level().isClientSide || !young(child) || !hasRoomFor(child)) return false;
        var seat = incoming.remove(child.getUUID());
        if (seat == null) { double r = radiusOf(child); var s = HatVessel.place(mode(), r, region(), null, taken(), null); seat = new double[]{s.x, s.y, s.z, r}; }
        if (!child.startRiding(this, true)) return false;
        crew.add(child.getUUID());
        seats.put(child.getUUID(), seat); syncSeats(); return true;
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
        var out = new java.util.ArrayList<double[]>(seats.values()); out.addAll(incoming.values());
        if (voyageBerth != null && !seats.containsKey(voyageYoung) && !incoming.containsKey(voyageYoung)) out.add(voyageBerth);
        return out;
    }
    public boolean keepVoyageBerth(YukkuriEntity child) {
        if (level().isClientSide || !young(child) || !onBrim(child) || !protects(child) || hasIncoming()
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
        if (level().isClientSide || !young(child) || !protects(child) || hasIncoming() || !serviceSeats.isEmpty()
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
    public boolean changingSeat(Entity young){var m=seatMove(young);return m!=null&&level().getGameTime()-m.start()<m.ticks();}
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
        if (level().isClientSide || child == null || parent == null || parent != owner() || child.getVehicle() != this
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
            double[] destination = service.originalRegion().equals(region()) ? service.original().clone() : normalSeat(child, child.getUUID());
            if (destination == null) continue;
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
        if (carryTrip == null && serviceSeats.isEmpty() && !ownerToiletSeat) {
            var parent = owner();
            if (parent != null && parent.getVehicle() == this) moveOwnerSeat(parent, ownerSeat(parent), false);
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
        if (level().isClientSide || child == null || child.getVehicle() != this || !young(child) || !protects(child)
                || !isAfloat() || towed() || flooding != 0 || hasIncoming() || child.getUUID().equals(voyageYoung)
                || !serviceSeats.isEmpty() || changingSeat(child) || ownerSeatBusy()) return false;
        var original = seats.get(child.getUUID());
        if (original == null) return false;
        double r = radiusOf(child); Vec3 from = toLocal(child.position()); Vec3 chosen = null;
        for (int i = 0; i < 8 && chosen == null; i++) {
            double angle = i * Math.PI / 4.0;
            Vec3 prefer = from.add(Math.cos(angle) * Math.max(.6, r * 2.5), 0, Math.sin(angle) * Math.max(.6, r * 2.5));
            Vec3 candidate = HatVessel.place(mode(), r, region(), prefer, takenExcept(child.getUUID()), from);
            if (candidate != null && candidate.distanceToSqr(from) > .16 && Math.abs(candidate.y-from.y) < 1e-4
                    && HatVessel.footprint(mode(), candidate.x, candidate.z, r) == candidate.y) chosen = candidate;
        }
        if (chosen == null) return false;
        var destination = new double[]{chosen.x, chosen.y, chosen.z, r};
        if (!moveYoungSeat(child, destination, hop)) return false;
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
            out.add(new double[]{seat[0],seat[1],seat[2],seat[3],occupant==null?seat[3]*2:occupant.getBbHeight()/unit()});
        }
        for (var entry : incoming.entrySet()) if (!entry.getKey().equals(excluded)) out.add(entry.getValue());
        if (voyageBerth != null && (voyageYoung == null || !voyageYoung.equals(excluded)) && !seats.containsKey(voyageYoung) && !incoming.containsKey(voyageYoung)) out.add(voyageBerth);
        return out;
    }

    private double[] normalSeat(YukkuriEntity child, UUID excluded) {
        double r = radiusOf(child); Vec3 next = HatVessel.place(mode(), r, region(), null, takenExcept(excluded), toLocal(child.position()));
        if (next == null) return null;
        return new double[]{next.x, next.y, next.z, r};
    }

    private boolean moveYoungSeat(YukkuriEntity child, double[] destination, boolean arc) {
        Vec3 from = toLocal(child.position()); Vec3 to = new Vec3(destination[0], destination[1], destination[2]);
        var existing = seats.get(child.getUUID());
        if (existing != null && java.util.Arrays.equals(existing, destination)) return true;
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
        Vec3 offset=to.subtract(ownerDestination).multiply(1,0,1), xz=from.subtract(offset);
        Vec3 pickup=new Vec3(xz.x,HatVessel.footprint(mode(),xz.x,xz.z,radiusOf(parent)),xz.z);
        if (!Double.isFinite(pickup.y) || !serviceGeometryFits(parent,child,pickup,from)) return null;
        if (toLocal(parent.position()).distanceToSqr(pickup)>.01 && walkingRoute(parent,pickup).isEmpty()) return null;
        var path=HatSurfaceRoute.find(mode(),pickup,ownerDestination,radiusOf(parent),takenExcept(child.getUUID()),
                offset.add(0,parent.getBbHeight()*.35/unit(),0),radiusOf(child),parent.getBbHeight()/unit(),child.getBbHeight()/unit());
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
        carryTrip=new CarryTrip(child.getUUID(),from,to,pickup,ownerDestination,
                new Vec3(offset.x,parent.getBbHeight()*.35/unit(),offset.z),level().getGameTime());
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
        Vec3 held=toWorld(ownerCarryLocal(parent).add(trip.offset));
        io.github.longshou.mcyukkuri.life.SurfaceApproach.face(parent,io.github.longshou.mcyukkuri.life.SurfaceApproach.yaw(parent.position(),held));
        io.github.longshou.mcyukkuri.life.SurfaceApproach.face(child,io.github.longshou.mcyukkuri.life.SurfaceApproach.yaw(held,parent.position()));
        if (trip.phase==1 && level().getGameTime()-trip.at>=12) {
            var path=HatSurfaceRoute.find(mode(),toLocal(parent.position()),trip.ownerDestination,radiusOf(parent),
                    takenExcept(child.getUUID()),trip.offset,radiusOf(child),parent.getBbHeight()/unit(),child.getBbHeight()/unit());
            if (path.isEmpty() && toLocal(parent.position()).distanceToSqr(trip.ownerDestination)>.01) {
                // Nothing was handed off: lower at the pickup patch and let the retained return retry.
                carryTrip=new CarryTrip(trip.child,trip.from,trip.from,trip.pickup,trip.pickup,trip.offset,level().getGameTime());
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
            carryTrip=null; syncSeats();
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
        point(tag,"Pickup",trip.pickup);point(tag,"OwnerTo",trip.ownerDestination);point(tag,"Offset",trip.offset);
        var parent=getPassengers().stream().filter(p->p.getUUID().equals(ownerYukkuri())&&p instanceof YukkuriEntity).map(p->(YukkuriEntity)p).findFirst().orElse(null);
        if (parent!=null) point(tag,"OwnerNow",ownerCarryLocal(parent));
        point(tag,"ChildNow",carriedLocal(trip)); return tag;
    }
    private CarryTrip carryPose() {
        if (!level().isClientSide) return carryTrip;
        var tag=entityData.get(SEATS).getCompound("Carry"); if (!tag.hasUUID("Child")) return null;
        var trip=new CarryTrip(tag.getUUID("Child"),point(tag,"From"),point(tag,"To"),point(tag,"Pickup"),point(tag,"OwnerTo"),point(tag,"Offset"),tag.getLong("At"));
        trip.phase=tag.getInt("Phase");trip.lastAnchor=point(tag,"ChildNow");return trip;
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
            Vec3 facing=carryTrip!=null && carryTrip.phase>0 && rider.getUUID().equals(ownerYukkuri())?toWorld(walkingLocal(rider,movement,time).add(carryTrip.offset)):at;
            io.github.longshou.mcyukkuri.life.SurfaceApproach.face(y,io.github.longshou.mcyukkuri.life.SurfaceApproach.yaw(rider.position(),facing));
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
        return rider instanceof YukkuriEntity y && y.family().stage()==io.github.longshou.mcyukkuri.family.FamilyLife.Stage.JUVENILE
                && isAfloat() && flooding==0 && !towed() && !hasIncoming() && !level().isNight() && !level().isRainingAt(blockPosition())
                && captain!=null && !captain.isPanicking() && !captain.family().laboring() && !captain.life().isSleeping()
                && !captain.aquaticLife().care().active() && !y.isPanicking() && !y.life().isSleeping()
                && !y.isNoAi() && !y.isCommandPlaying() && !y.operations().active()
                && y.life().mealTicks()==0 && y.life().energy()>25 && y.life().nutrition()>=60;
    }
    /** Server: young aboard without a place (after loading) get one; afloat, at dusk and dawn they all move (before the
     *  owner by day, under her at night). */
    private void tendSeats() {
        if (carryTrip != null) return;
        // Large juveniles need the scarce wide spaces first. Small siblings then fit around them or inside.
        var young = youngPassengers().stream().sorted(java.util.Comparator.comparingDouble(this::radiusOf).reversed()).toList();
        boolean resized = young.stream().anyMatch(p -> seats.containsKey(p.getUUID()) && Math.abs(seats.get(p.getUUID())[3]-radiusOf(p))>.001);
        if (resized && !serviceSeats.isEmpty()) {
            for (var id : java.util.List.copyOf(serviceSeats.keySet())) releaseCareSeat(id);
        }
        serviceSeats.keySet().removeIf(id -> young.stream().noneMatch(p -> p.getUUID().equals(id)));
        returnCareSeats();
        String wanted=region()+young.stream().map(p->p.getUUID()+":"+brimSafe(p)).toList();
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
            double r = radiusOf(p); var s = brimSafe(p)?HatVessel.brimPlace(r,taken()):null;
            if(s==null)s=HatVessel.place(mode(), r, region(), null, taken(), null);
            if (s == null) continue;
            var before=old.get(p.getUUID());
            if(before!=null&&p instanceof YukkuriEntity y){
                seats.put(p.getUUID(),before);
                if(!moveYoungSeat(y,new double[]{s.x,s.y,s.z,r},false)){seatRegion="";continue;}
            }
            seats.put(p.getUUID(), new double[]{s.x, s.y, s.z, r}); changed = true;
        }
        if (changed) syncSeats();
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
        var have = incoming.get(child.getUUID());
        if (have == null && child.getUUID().equals(voyageYoung) && voyageBerth != null) incoming.put(child.getUUID(),have=voyageBerth.clone());
        if (have == null) { double r = radiusOf(child); var s = HatVessel.place(mode(), r, region(), prefer, taken(), toLocal(child.position())); incoming.put(child.getUUID(), have = new double[]{s.x, s.y, s.z, r}); }
        return new Vec3(have[0], have[1], have[2]);
    }
    /** The place a young would get next, without keeping it. */
    public Vec3 peekSeat(YukkuriEntity child) {
        var have = incoming.get(child.getUUID());
        return have != null ? new Vec3(have[0], have[1], have[2]) : HatVessel.place(mode(), radiusOf(child), region(), null, taken(), toLocal(child.position()));
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
        // Each young in its own place inside: where it landed, or packed by size (afloat before the owner by day, under her at night).
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
        boat.accessoryToken = owner.life().stowAccessory();
        if (boat.accessoryToken == null || !animated && !owner.startRiding(boat, true)) {
            if (boat.accessoryToken != null) owner.life().returnAccessory(owner.getUUID(), boat.accessoryToken, null);
            boat.discard(); return null;
        }
        owner.subspecies().boatId(boat.getUUID()); owner.setPersistenceRequired(); owner.getNavigation().stop();
        owner.aquaticLife().equip(boat); boat.heldTicks = 60;
        if (home != null) owner.family().transferHat(home,boat);
        else if (animated) owner.family().embark(boat);
        return boat;
    }
    /** Reclaim the same hat only after the pupil is safely back on its father's boat. */
    public boolean retrieveAfloat(YukkuriEntity child, HatBoatEntity home) {
        if (level().isClientSide || !isAfloat() || isVehicle() || !child.getUUID().equals(ownerYukkuri())
                || !home.protects(child) || !home.young(child) || distanceToSqr(home)>9
                || !child.life().returnAccessory(ownerYukkuri(),accessoryToken,null)) return false;
        cargo.returnToFamily(child,home); child.subspecies().boatId(null);
        state(State.RESCUED); stopPaddling(); return true;
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
        if (level().isClientSide || !owner.getUUID().equals(ownerYukkuri()) || owner.distanceToSqr(this) > 4
                || !isAfloat() || !owner.life().matchesAccessory(ownerYukkuri(), accessoryToken)) return false;
        if (!owner.startRiding(this, true)) return false;
        owner.setYRot(getYRot()); owner.yBodyRot=getYRot(); owner.setYHeadRot(getYRot()); return true;
    }
    public void capsize() {
        if (level().isClientSide || state() == State.RESCUED || state() == State.REMOVED) return;
        var passengers=java.util.List.copyOf(getPassengers());
        state(State.CAPSIZED); flooding = 100; stopPaddling(); heldTicks = 0; cargo.spill(); ejectPassengers(); setDeltaMovement(getDeltaMovement().scale(.25));
        for(int i=0;i<passengers.size();i++){
            // Spread the crew a little so they do not land stacked on one point.
            double angle=i*2.4, spread=i==0?0:.35;
            passengers.get(i).setPos(getX()+Math.cos(angle)*spread,getY()-.16,getZ()+Math.sin(angle)*spread);passengers.get(i).setDeltaMovement(0,-.03,0);
        }
    }
    private YukkuriEntity owner() {
        return ownerYukkuri() != null && level() instanceof ServerLevel server && server.getEntity(ownerYukkuri()) instanceof YukkuriEntity y ? y : null;
    }
    public boolean rescue(BlockPos dryStand, UUID rescuer) {
        var y = owner();
        if (level().isClientSide || y == null || !y.isAlive() || state() == State.RESCUED || state() == State.REMOVED
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
        if (rescue(player.blockPosition(), player.getUUID())) return InteractionResult.CONSUME;
        if (recoverOrphans(player)) return InteractionResult.CONSUME;
        pullTarget = player.position(); pullTicks = 100; return InteractionResult.CONSUME;
    }
    /** A confirmed dead owner leaves one recoverable hat. An unloaded/missing owner is never presumed dead. */
    private boolean recoverOrphans(Player player) {
        if (!(level() instanceof ServerLevel server) || accessoryToken == null || ownerYukkuri() == null
                || state() == State.RESCUED || state() == State.REMOVED || hasIncoming()) return false;
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
        capsize(); markHurt(); return true;
    }
    @Override public void tick() {
        super.tick();
        if (level().isClientSide) {
            if (lerpSteps > 0) { setPos(getX()+(lerpX-getX())/lerpSteps,getY()+(lerpY-getY())/lerpSteps,getZ()+(lerpZ-getZ())/lerpSteps); lerpSteps--; }
            return;
        }
        if (state() == State.RESCUED) { if (tickCount % 20 == 0) discard(); return; }
        if (heldTicks > 0) heldTicks--;
        if (tickCount % 20 == 0 && level() instanceof ServerLevel server)  // a place kept for a young no longer coming
            incoming.keySet().removeIf(id -> !(server.getEntity(id) instanceof YukkuriEntity y) || !y.isAlive() || y.isPassenger() || !y.family().flying() || y.distanceToSqr(this) > 64);
        if (tickCount>60 && tickCount%20==0 && voyageYoung!=null && level() instanceof ServerLevel server
                && (!(server.getEntity(voyageYoung) instanceof YukkuriEntity y) || !y.isAlive() || !y.aquaticLife().firstVoyage().keepsBerth(this))) releaseVoyageBerth(voyageYoung);
        tickCarry();
        if (tickCount % 10 == 0) tendSeats();
        tendLeaving();
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
                    || captain.isCommandPlaying() || captain.isPanicking() || captain.life().isSleeping() || !cargo.paddle()) stopPaddling();
            if (isAfloat() && !route.isEmpty()) {
                var next = route.peekFirst();
                if (position().subtract(next).horizontalDistanceSqr() < .12) route.removeFirst();
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
        for (var p : getPassengers()) if (p instanceof YukkuriEntity rider) { rider.getNavigation().stop(); rider.fallDistance = 0; }
        var y = owner();
        if (y!=null && y.getVehicle()==this) {
            entityData.set(SCALE,HatVessel.hatScale(y));
            // Looking at a child must not rotate the floor beneath that same child every tick.
            // A held boat also owns its heading while its stern is being aligned with a shore plant.
            boolean interiorBusy=carryTrip!=null || !serviceSeats.isEmpty() || !returningSeats.isEmpty()
                    || ownerToiletSeat || changingOwnerSeat() || y.aquaticLife().care().active()
                    || youngPassengers().stream().anyMatch(this::changingSeat);
            if (!paddling() && heldTicks==0 && voyageYoung==null && !interiorBusy) setYRot(y.yBodyRot);
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
        tag.putString("Role",role.name());if(mooring!=null){tag.putDouble("MooringX",mooring.x);tag.putDouble("MooringZ",mooring.z);}
        tag.put("Cargo", cargo.save());
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
        ownerServiceSeat = null; ownerSeatMove = null; ownerToiletSeat = false; seatRegion = "";
        entityData.set(OWNER,tag.hasUUID("OwnerYukkuri")?Optional.of(tag.getUUID("OwnerYukkuri")):Optional.empty());
        accessoryToken=tag.hasUUID("AccessoryToken")?tag.getUUID("AccessoryToken"):null;
        try{state(State.valueOf(tag.getString("State")));}catch(IllegalArgumentException invalid){state(State.BEACHED);}
        flooding=Math.clamp(tag.getInt("Flooding"),0,100);entityData.set(SCALE,Math.clamp(tag.getFloat("HullScale"),.1f,2.5f));
        role="SELF".equals(tag.getString("Role"))?HatVessel.Role.SELF:HatVessel.Role.HELPER;
        cargo.load(tag.getCompound("Cargo")); syncCargo(cargo.save());
        if (tag.contains("CarryTrip")) {
            var c=tag.getCompound("CarryTrip");
            if (c.hasUUID("Child") && c.hasUUID("Holder") && c.getUUID("Holder").equals(ownerYukkuri())) {
                Vec3 original=point(c,"From"), now=point(c,"ChildNow"), ownerNow=point(c,"OwnerNow");
                int phase=Math.clamp(c.getInt("Phase"),0,3);
                Vec3 offset=phase==3?now.subtract(ownerNow):point(c,"Offset");
                carryTrip=new CarryTrip(c.getUUID("Child"),phase==1?now:original,point(c,"To"),point(c,"Pickup"),point(c,"OwnerTo"),offset,level().getGameTime());
                carryTrip.lastAnchor=now;
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
        while(crew.size()>MAX_YOUNG)crew.remove(crew.iterator().next());
    }
}
