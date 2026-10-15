package io.github.longshou.mcyukkuri.subspecies;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.entity.YukkuriVariant;
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
    private record SeatMove(Vec3 from, long start, int ticks) {}
    private final java.util.Map<UUID,SeatMove> seatMoves = new java.util.HashMap<>();
    /** One pupil at a time; its berth remains occupied while its own hat is away. */
    private UUID voyageYoung;
    private double[] voyageBerth;
    /** Young hopping out in turn before the hat is taken back: who, to which spot (world), from which game tick. */
    private record Leaving(UUID young, Vec3 spot, long at) {}
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
        if (level().isClientSide || !isAfloat() || !cargo.paddle() || towed()) return false;
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
        if (!level().isClientSide) { seatMoves.remove(passenger.getUUID()); if (seats.remove(passenger.getUUID()) != null) syncSeats(); }
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
                || voyageYoung != null && !voyageYoung.equals(child.getUUID())) return false;
        var others=new java.util.ArrayList<double[]>();
        for(var e:seats.entrySet())if(!e.getKey().equals(child.getUUID()))others.add(e.getValue());
        double r=radiusOf(child);var at=HatVessel.brimPlace(r,others,true);if(at==null)return false;
        voyageYoung = child.getUUID(); voyageBerth = new double[]{at.x,at.y,at.z,r};
        var from=toLocal(child.position());
        if(from.distanceToSqr(at)>.01)seatMoves.put(voyageYoung,new SeatMove(from,level().getGameTime(),
                HatVessel.solve(child.position(),toWorld(at),this,HatVessel.radius(child.getBbWidth()),HatVessel.HOP_TMIN)));
        seats.put(voyageYoung,voyageBerth.clone());syncSeats();return true;
    }
    public void releaseVoyageBerth(UUID child) {
        if (child.equals(voyageYoung)) { voyageYoung = null; voyageBerth = null; seatRegion = ""; }
    }
    public Vec3 voyageBerth(YukkuriEntity child) {
        return child.getUUID().equals(voyageYoung) && voyageBerth != null ? new Vec3(voyageBerth[0],voyageBerth[1],voyageBerth[2]) : null;
    }
    private void syncSeats() {
        var list = new net.minecraft.nbt.ListTag();
        for (var e : seats.entrySet()) {
            var c = new CompoundTag(); c.putUUID("Id", e.getKey()); c.putDouble("X", e.getValue()[0]); c.putDouble("Y", e.getValue()[1]); c.putDouble("Z", e.getValue()[2]);
            var m=seatMoves.get(e.getKey());
            if(m!=null){c.putDouble("FromX",m.from().x);c.putDouble("FromY",m.from().y);c.putDouble("FromZ",m.from().z);c.putLong("At",m.start());c.putInt("Ticks",m.ticks());}
            list.add(c);
        }
        var tag = new CompoundTag(); tag.put("S", list); entityData.set(SEATS, tag);
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
                return new SeatMove(new Vec3(c.getDouble("FromX"),c.getDouble("FromY"),c.getDouble("FromZ")),c.getLong("At"),c.getInt("Ticks"));
        return null;
    }
    public boolean changingSeat(Entity young){var m=seatMove(young);return m!=null&&level().getGameTime()-m.start()<m.ticks();}
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
        // Large juveniles need the scarce wide spaces first. Small siblings then fit around them or inside.
        var young = youngPassengers().stream().sorted(java.util.Comparator.comparingDouble(this::radiusOf).reversed()).toList();
        boolean resized = young.stream().anyMatch(p -> seats.containsKey(p.getUUID()) && Math.abs(seats.get(p.getUUID())[3]-radiusOf(p))>.001);
        String wanted=region()+young.stream().map(p->p.getUUID()+":"+brimSafe(p)).toList();
        boolean moved = resized || !wanted.equals(seatRegion) && !catches(); seatRegion = wanted;
        var old=new java.util.HashMap<UUID,double[]>(seats);
        if(resized)seatMoves.clear();
        if (moved) seats.clear();
        if (voyageBerth != null && young.stream().anyMatch(p -> p.getUUID().equals(voyageYoung))) seats.put(voyageYoung,voyageBerth.clone());
        boolean changed = moved;
        for (var p : young) if (!seats.containsKey(p.getUUID())) {
            double r = radiusOf(p); var s = brimSafe(p)?HatVessel.brimPlace(r,taken()):null;
            if(s==null)s=HatVessel.place(mode(), r, region(), null, taken(), null);
            var before=old.get(p.getUUID());
            if(before!=null&&!resized){
                var from=toLocal(p.position());
                if(from.distanceToSqr(s)>.01){
                    int ticks=HatVessel.solve(toWorld(from),toWorld(s),this,HatVessel.radius(p.getBbWidth()),HatVessel.HOP_TMIN);
                    seatMoves.put(p.getUUID(),new SeatMove(from,level().getGameTime(),ticks));
                }
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
        for (int k = 0; k < turn.size(); k++) { int i = turn.get(k); leaving.add(new Leaving(young.get(i).getUUID(), spots.get(order[i]), now+4L*k)); }
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
                if (to != null) { var from = child.position(); child.stopRiding(); crew.remove(child.getUUID()); child.family().hop(this, from, to); }
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
        float k = hullScale()/16, turn = (float) Math.toRadians(180 - getYRot());
        if (passenger.getUUID().equals(ownerYukkuri())) {
            // In the opening at the stern, on the ledge, her back to its rear edge: the inside before her stays open.
            var at = passenger instanceof YukkuriEntity y ? ownerSeat(y).scale(k).yRot(turn) : Vec3.ZERO;
            move.accept(passenger, getX()+at.x, getY()+at.y, getZ()+at.z); return;
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
            at=HatVessel.arc(toWorld(movement.from()),at,movement.ticks(),time);}
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
        if (child.getVehicle()!=home || !home.protects(child) || !home.onBrim(child) || home.voyageBerth(child)==null
                || !home.ownerYukkuri().equals(child.family().father()) || !child.aquaticLife().firstVoyage().launchingFrom(home)) return null;
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
            entityData.set(SCALE,HatVessel.hatScale(y)); if (!paddling() && voyageYoung==null) setYRot(y.yBodyRot);
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
        if(voyageYoung!=null&&voyageBerth!=null){
            tag.putUUID("VoyageYoung",voyageYoung);var berth=new CompoundTag();
            berth.putDouble("X",voyageBerth[0]);berth.putDouble("Y",voyageBerth[1]);berth.putDouble("Z",voyageBerth[2]);berth.putDouble("R",voyageBerth[3]);tag.put("VoyageBerth",berth);
        }
        var young=new net.minecraft.nbt.ListTag();for(var id:crew)young.add(net.minecraft.nbt.NbtUtils.createUUID(id));tag.put("Crew",young);
    }
    @Override protected void readAdditionalSaveData(CompoundTag tag) {
        entityData.set(OWNER,tag.hasUUID("OwnerYukkuri")?Optional.of(tag.getUUID("OwnerYukkuri")):Optional.empty());
        accessoryToken=tag.hasUUID("AccessoryToken")?tag.getUUID("AccessoryToken"):null;
        try{state(State.valueOf(tag.getString("State")));}catch(IllegalArgumentException invalid){state(State.BEACHED);}
        flooding=Math.clamp(tag.getInt("Flooding"),0,100);entityData.set(SCALE,Math.clamp(tag.getFloat("HullScale"),.1f,2.5f));
        role="SELF".equals(tag.getString("Role"))?HatVessel.Role.SELF:HatVessel.Role.HELPER;
        cargo.load(tag.getCompound("Cargo")); syncCargo(cargo.save());
        voyageYoung=tag.hasUUID("VoyageYoung")?tag.getUUID("VoyageYoung"):null;voyageBerth=null;
        if(voyageYoung!=null&&tag.contains("VoyageBerth")){
            var berth=tag.getCompound("VoyageBerth");voyageBerth=new double[]{berth.getDouble("X"),berth.getDouble("Y"),berth.getDouble("Z"),berth.getDouble("R")};
        }else voyageYoung=null;
        mooring=tag.contains("MooringX")?new Vec3(tag.getDouble("MooringX"),0,tag.getDouble("MooringZ")):null;
        crew.clear();for(var raw:tag.getList("Crew",net.minecraft.nbt.Tag.TAG_INT_ARRAY))crew.add(net.minecraft.nbt.NbtUtils.loadUUID(raw));
        while(crew.size()>MAX_YOUNG)crew.remove(crew.iterator().next());
    }
}
