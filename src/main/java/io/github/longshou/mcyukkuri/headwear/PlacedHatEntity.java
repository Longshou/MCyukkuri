package io.github.longshou.mcyukkuri.headwear;

import io.github.longshou.mcyukkuri.motor.Attach;
import io.github.longshou.mcyukkuri.motor.AttachState;
import io.github.longshou.mcyukkuri.motor.BodyAbility;
import io.github.longshou.mcyukkuri.motor.Motor;
import io.github.longshou.mcyukkuri.motor.action.Grab;
import io.github.longshou.mcyukkuri.motor.geom.Buoyancy;
import io.github.longshou.mcyukkuri.motor.geom.Disc;
import io.github.longshou.mcyukkuri.motor.geom.FloorShape;
import io.github.longshou.mcyukkuri.motor.geom.BodyFootprint;
import io.github.longshou.mcyukkuri.subspecies.SubspeciesLife;
import io.github.longshou.mcyukkuri.motor.geom.HeightGrid;
import io.github.longshou.mcyukkuri.motor.geom.Squeeze;
import io.github.longshou.mcyukkuri.motor.surface.Container;
import io.github.longshou.mcyukkuri.motor.surface.HostContainer;
import io.github.longshou.mcyukkuri.motor.surface.HostFrame;
import io.github.longshou.mcyukkuri.motor.surface.Occupancy;
import io.github.longshou.mcyukkuri.motor.surface.Riders;
import io.github.longshou.mcyukkuri.motor.surface.SurfaceHost;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import io.github.longshou.mcyukkuri.intent.HostIntent;
import io.github.longshou.mcyukkuri.intent.Intent;
import io.github.longshou.mcyukkuri.intent.IntentRunner;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.entity.YukkuriVariant;
import io.github.longshou.mcyukkuri.family.FamilyData;
import io.github.longshou.mcyukkuri.family.LiveBirthGeometry;
import io.github.longshou.mcyukkuri.registry.ModEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.phys.Vec3;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.UUID;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.function.BiPredicate;
import net.minecraft.nbt.Tag;
import net.minecraft.util.Mth;

/** One real hat (her own hat turned upside down, its soft crown pressed down: HatGeometry) set down by its Marisa owner: on
 *  the ground to catch her young, or on water where it floats ({@link PlacedHatBehavior}). Every body aboard keeps its own
 *  supported spot in the {@link Riders} table; there are no seats and no leases. A cradle taken back leaves the world. */
public final class PlacedHatEntity extends Entity implements PlacedHatView, SurfaceHost {
    /** CRADLE: a Marisa's hat turned over on dry ground, its soft crown pressed flat under it, to catch young.
     *  RESCUED: taken back onto its owner's head. Ordinals are synced: append only. */
    public enum State { CRADLE, RESCUED }
    private static final EntityDataAccessor<String> FORM = SynchedEntityData.defineId(PlacedHatEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<Integer> STATE = SynchedEntityData.defineId(PlacedHatEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Optional<UUID>> OWNER = SynchedEntityData.defineId(PlacedHatEntity.class, EntityDataSerializers.OPTIONAL_UUID);
    private static final EntityDataAccessor<Float> SCALE = SynchedEntityData.defineId(PlacedHatEntity.class, EntityDataSerializers.FLOAT);
    /** The deployed hat's own wear (headwear damage 0 intact, 1 frayed, 2 torn), drawn on the hull like a worn hat. */
    private static final EntityDataAccessor<Integer> HULL_DAMAGE = SynchedEntityData.defineId(PlacedHatEntity.class, EntityDataSerializers.INT);
    /** Every rider's local spot (hat units) keyed by rider entity id: the client's {@code positionRider} input, written once a tick when it changed. */
    private static final EntityDataAccessor<CompoundTag> RIDERS = SynchedEntityData.defineId(PlacedHatEntity.class, EntityDataSerializers.COMPOUND_TAG);
    /** The hull's tilt in degrees (HostFrame roll and pitch), written by its behaviour: riders and the drawn hull share it. */
    private static final EntityDataAccessor<Float> ROLL = SynchedEntityData.defineId(PlacedHatEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> PITCH = SynchedEntityData.defineId(PlacedHatEntity.class, EntityDataSerializers.FLOAT);
    /** What its behaviour keeps in the hollow besides riders (an add-on's stores), written by that behaviour, for drawing only. */
    private static final EntityDataAccessor<CompoundTag> CONTENTS = SynchedEntityData.defineId(PlacedHatEntity.class, EntityDataSerializers.COMPOUND_TAG);
    /** The one rider table of this host: local spot and yaw per body. */
    private final Riders riders = new Riders();
    /** Cached cavity of {@link #container()}; rebuilt when the hat size changes. */
    private Container container;
    private double containerUnit;
    /** {@link #unloadRequested()} cached for one game tick: the answer is derived from the running intents, never stored. */
    private long unloadTick = Long.MIN_VALUE;
    private boolean unloadWanted, attendWanted;
    private UUID accessoryToken;
    /** Who set the hat down to catch: a helper beside it, or the mother before her own outlet. */
    private HatGeometry.Role role = HatGeometry.Role.HELPER;
    private double lerpX, lerpY, lerpZ;
    private float lerpYaw, lerpPitch;
    private int lerpSteps;
    /** The behaviour running this hat and its provider (null: on the ground), picked once a tick; this hat's one instance of
     *  each provider's behaviour; the saved motion until the instance it belongs to exists; the tilt one tick ago. */
    private PlacedHatBehavior behavior = PlacedHatBehavior.LAND;
    private PlacedHatBehavior.Provider provider, lastProvider;
    private final Map<PlacedHatBehavior.Provider, PlacedHatBehavior> instances = new LinkedHashMap<>();
    private CompoundTag savedMotion;
    private int behaviorTick = -1;
    private boolean behaviorPicked;
    private float rollO, pitchO;
    /** The walk grids of this floor by foot radius and height, and the floor they were sampled from (size, turned over, what it
     *  holds, tilt). */
    private final Map<List<Double>, HeightGrid> grids = new HashMap<>();
    private List<Object> gridFloor = List.of();
    /** The behaviour that ran last tick and its provider's id: what it keeps is released when another takes over. */
    private PlacedHatBehavior ran;
    private String ranId = "ground";
    /** Riders pressed on by others, and for how many ticks running; bodies squeezed off, and the tick from which contact may
     *  seat them here again. Transient: not saved, bounded by the riders and the cooldown. */
    private final Map<UUID, Integer> crowding = new HashMap<>();
    private final Map<UUID, Long> reseat = new HashMap<>();
    /** The stick this hat keeps for its owner (HatCustody's kit while worn); one place at a time. */
    private ItemStack kit = ItemStack.EMPTY;

    public PlacedHatEntity(EntityType<? extends PlacedHatEntity> type, Level level) { super(type, level); blocksBuilding = true; }
    @Override protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(STATE, State.CRADLE.ordinal()); builder.define(OWNER, Optional.empty()); builder.define(SCALE, 1f);
        builder.define(RIDERS, new CompoundTag()); builder.define(HULL_DAMAGE, 0); builder.define(ROLL, 0f); builder.define(PITCH, 0f);
        builder.define(CONTENTS, new CompoundTag()); builder.define(FORM, HullProfile.CURRENT);
    }
    @Override public void onSyncedDataUpdated(EntityDataAccessor<?> data) {
        super.onSyncedDataUpdated(data);
        if (SCALE.equals(data) || FORM.equals(data)) { container = null; refreshDimensions(); }
    }
    @Override public EntityDimensions getDimensions(Pose pose) {
        return super.getDimensions(pose).scale(hullScale());
    }

    @Override public String formId() { return entityData.get(FORM); }
    public void formId(String id) { entityData.set(FORM, HullProfile.of(id).id()); }

    public void syncHullScale(float scale) { entityData.set(SCALE, scale); }
    public void syncOwner(Optional<UUID> owner) { entityData.set(OWNER, owner); }
    public UUID launchToken() { return accessoryToken; }
    public void launchToken(UUID token) { accessoryToken = token; }
    public void launchRole(HatGeometry.Role next) { role = next; }
    public HatGeometry.Role role() { return role; }
    public float previousYaw() { return yRotO; }

    // ---------------------------------------------------------------- the host (SurfaceHost)
    /** How much two foot circles must overlap before they conflict. */
    private static final double ROOM_SLACK = .02;

    /** Blocks per hat unit (the prop's units at this hat's size). */
    public double unit() { return hullScale()/16; }
    public float turn() { return (float) Math.toRadians(180 - getYRot()); }
    public Vec3 toWorld(Vec3 local) { return frame().toWorld(local); }
    public Vec3 toLocal(Vec3 world) { return frame().toLocal(world); }

    @Override public Entity entity() { return this; }
    @Override public HostFrame frame() { return new HostFrame(position(), unit(), turn(), roll(), pitch()); }
    public float roll() { return entityData.get(ROLL); }
    public float pitch() { return entityData.get(PITCH); }
    @Override public float roll(float partial) { return Mth.rotLerp(partial, rollO, roll()); }
    @Override public float pitch(float partial) { return Mth.rotLerp(partial, pitchO, pitch()); }
    /** The behaviour's pose for this tick (degrees); written only when it changed. */
    public void pose(float roll, float pitch) {
        if (roll != roll()) entityData.set(ROLL, roll);
        if (pitch != pitch()) entityData.set(PITCH, pitch);
    }
    /** Its behaviour's synced stores: drawing only. */
    @Override public CompoundTag contents() { return entityData.get(CONTENTS); }
    public void contents(CompoundTag tag) { entityData.set(CONTENTS, tag); }
    /** Turned crown up: the hollow faces the water and nothing stands in it. */
    public boolean upsideDown() { return Buoyancy.up(roll(), pitch()) < 0; }
    /** The behaviour running this hat now: the first registered provider that applies, else the land one (once a tick). */
    public PlacedHatBehavior behavior() {
        if (behaviorTick == tickCount) return behavior;
        behaviorTick = tickCount;
        PlacedHatBehavior.Provider next = null;
        for (var candidate : PlacedHatBehavior.PROVIDERS) if (candidate.applies(this, behavior)) { next = candidate; break; }
        if (next != provider || !behaviorPicked) {
            if (behaviorPicked) GameplayLog.event(this, "hat", "entered {} from {} riders={} owner={} roll={} pitch={}",
                    next == null ? "ground" : next.id(), provider == null ? "ground" : provider.id(), getPassengers().size(), ownerYukkuri(),
                    roll(), pitch());
            behavior = next == null ? PlacedHatBehavior.LAND : instances.computeIfAbsent(next, created -> {
                var made = created.create(this);
                if (savedMotion != null && created.id().equals(savedMotion.getString("Kind"))) { made.load(savedMotion); savedMotion = null; }
                return made;
            });
            provider = next;
            if (next != null) lastProvider = next;
            behaviorPicked = true;
        }
        return behavior;
    }
    /** The steepest change of tilt over the last tick (degrees), from the synced pose alone: both sides read the same. */
    public float tiltRate() { return Math.max(Math.abs(Mth.wrapDegrees(roll() - rollO)), Math.abs(Mth.wrapDegrees(pitch() - pitchO))); }
    /** All support, room and movement use the same body-only columns in hat units. */
    private BodyFootprint shape(YukkuriEntity body) {
        return BodyFootprint.of(body.assetId(),body.getBbWidth()/unit(),tallOf(body),Math.toRadians(body.yBodyRot-getYRot()));
    }
    @Override public OptionalDouble support(Vec3 local, double r) {
        double y=upsideDown()?Double.NaN:profile().footprint(local.x,local.z,r/unit());
        return Double.isFinite(y)?OptionalDouble.of(y):OptionalDouble.empty();
    }
    @Override public OptionalDouble support(Vec3 local, YukkuriEntity body) { return support(local, shape(body)); }
    private OptionalDouble support(Vec3 local, BodyFootprint body) {
        if (upsideDown()) return OptionalDouble.empty();
        double height = profile().support(local.x, local.z, body);
        return Double.isFinite(height) ? OptionalDouble.of(height) : OptionalDouble.empty();
    }
    @Override public boolean room(YukkuriEntity body, Vec3 local, double top) {
        var shape = shape(body);
        return clear(shape, local, body, Math.max(0, top-local.y-tallOf(body)));
    }
    private boolean clear(BodyFootprint body, Vec3 at, Entity except, double rise) {
        for (var other : getPassengers()) if (other != except && other instanceof YukkuriEntity y) {
            var p = riders.local(y.getUUID());
            if (body.overlaps(at.x, at.y, at.z, shape(y), p.x, p.y, p.z, ROOM_SLACK, rise)) return false;
        }
        for (var held : behavior().obstacles(this)) if (body.overlaps(at.x, at.y, at.z, held, ROOM_SLACK, rise)) return false;
        return true;
    }
    /** Squeezed off this hat a moment ago: it does not bear it again yet, so no contact seats it here. */
    private boolean cooling(YukkuriEntity body) {
        Long until = reseat.get(body.getUUID());
        if (until == null) return false;
        if (level().getGameTime() < until) return true;
        reseat.remove(body.getUUID());
        return false;
    }
    /** No rider's footprint but {@code except}'s, nor anything the behaviour keeps on the floor, overlaps this one (hat units,
     *  circles and heights). */
    public boolean clear(Disc foot, Entity except) {
        for (var other : getPassengers()) if (other != except && other instanceof YukkuriEntity y) {
            var p = riders.local(y.getUUID());
            if (shape(y).overlaps(p.x,p.y,p.z,foot,ROOM_SLACK)) return false;
        }
        for (var held : behavior().obstacles(this)) if (foot.overlaps(held, ROOM_SLACK)) return false;
        return true;
    }
    @Override public double load() { return behavior().load(this); }
    /** The tilt the walk grid's heights are taken at, in steps of this many degrees (part of its cache key). */
    private static final float GRID_TILT = 2;
    /** Spec 6's walk grid, centred on the hat over its brim's reach; the floor-only grid is kept per foot radius until the floor
     *  it was sampled from changed (size, turned over, what it holds, tilt step). */
    @Override public HeightGrid walkGrid(YukkuriEntity walker, boolean aroundRiders) {
        double foot = Occupancy.foot(walker.getBbWidth()), tall = tallOf(walker);
        var held = behavior().obstacles(this);
        float roll = Math.round(roll() / GRID_TILT) * GRID_TILT, pitch = Math.round(pitch() / GRID_TILT) * GRID_TILT;
        if (aroundRiders) return sampleGrid(foot, held, roll, pitch, walker, true);
        List<Object> floor = List.of(formId(), unit(), upsideDown(), held, roll, pitch, walker.assetId(), BodyFootprint.yawBucket(Math.toRadians(walker.yBodyRot-getYRot())));
        if (!floor.equals(gridFloor) || grids.size() > 8) { grids.clear(); gridFloor = floor; }
        return grids.computeIfAbsent(List.of(Math.round(foot * 1000) / 1000.0, Math.round(tall * 100) / 100.0),
                key -> sampleGrid(key.get(0), held, roll, pitch, walker, false));
    }
    /** Cells of min(GRID_CELL, the foot radius) in hat units and at least 8 across the opening; each cell's height is the floor
     *  under that foot raised to world-up at this tilt, so a slope counts toward the step a walker can take. Cells where the
     *  walker's footprint (its {@code tall} height up from that floor) would overlap what the floor holds (or, around riders,
     *  another rider than the walker) are unsupported. */
    private HeightGrid sampleGrid(double foot, List<Disc> held, float roll, float pitch, YukkuriEntity walker, boolean aroundRiders) {
        float[] o = profile().opening(), brim = profile().brim();
        double feet = foot / unit(), cell = Math.min(Math.min(GRID_CELL / unit(), feet), Math.min(o[1] - o[0], o[3] - o[2]) / 8);
        var tilt = new HostFrame(Vec3.ZERO, 1, 0, roll, pitch);
        int nx = (int) Math.ceil(2 * brim[0] / cell), nz = (int) Math.ceil(2 * brim[1] / cell);
        double x0 = -nx * cell / 2, z0 = -nz * cell / 2;
        return new HeightGrid(x0, z0, x0 + (nx - .5) * cell, z0 + (nz - .5) * cell, cell, (x, z) -> {
            var height = support(new Vec3(x, 0, z), walker);
            if (height.isEmpty()) return Double.NaN;
            if (aroundRiders ? !room(walker, new Vec3(x, height.getAsDouble(), z))
                    : held.stream().anyMatch(thing -> shape(walker).overlaps(x, height.getAsDouble(), z, thing, ROOM_SLACK))) return Double.NaN;
            return tilt.toWorld(new Vec3(x, height.getAsDouble(), z)).y;
        });
    }
    /** The brim's top edge in 24 directions, through the current frame so a placed yaw is included. */
    private HullProfile rimForm;
    private final Map<HostFrame,List<Vec3>> rims=new LinkedHashMap<>(4,.75f,true) {
        @Override protected boolean removeEldestEntry(Map.Entry<HostFrame,List<Vec3>> entry){return size()>4;}
    };
    @Override public List<Vec3> rim() { return rim(frame()); }
    public List<Vec3> rim(HostFrame pose) {
        if(rimForm!=profile()){rims.clear();rimForm=profile();}
        return rims.computeIfAbsent(pose,p->profile().rimPoints().stream().map(v->p.toWorld(new Vec3(v.x(),v.y(),v.z()))).toList());
    }
    /** Named places, hat units: the middle of the cavity, and the brim edge on the owner's side. Targets only. */
    @Override public Map<String, Vec3> spots() {
        var out = new LinkedHashMap<String, Vec3>();
        out.put("centre", new Vec3(0, profile().surface(0, 0), 0));
        double dx = 0, dz = -1;
        var owner = owner();
        if (owner != null) {
            var local = toLocal(owner.position());
            double length = Math.hypot(local.x, local.z);
            if (length > 1e-6) { dx = local.x / length; dz = local.z / length; }
        }
        double reach = Math.max(0, profile().reach(Math.atan2(dz, dx)) - .125);
        out.put("exit", new Vec3(dx * reach, profile().surface(dx * reach,dz * reach), dz * reach));
        return Map.copyOf(out);
    }
    /** The cavity's own floor and brim heights (hat units) and, as its radius, the authored brim extent. */
    @Override public Container container() {
        if (container == null || containerUnit != unit()) {
            double radius=Math.min(profile().reach(0),profile().reach(Math.PI/2))*unit();
            container = new HostContainer(this, new FloorShape.Circle(radius), profile().floor(), profile().rimY());
            containerUnit = unit();
        }
        return container;
    }
    @Override public Riders riders() { return riders; }
    @Override public boolean steady() { return state() != State.RESCUED && behavior().steady(this); }
    /** A rider the hull keeps dry (the behaviour decides: afloat, upright, its brim above the water). */
    @Override public boolean shelters(YukkuriEntity body) { return hasPassenger(body) && !upsideDown() && behavior().shelters(this, body); }
    @Override public boolean capsized() { return upsideDown(); }
    @Override public boolean right(YukkuriEntity by, double progress, String cause) { return behavior().right(this, by, progress, cause); }
    @Override public boolean thrust(YukkuriEntity by, Vec3 drive, float turn) { return behavior().thrust(this, by, drive, turn); }
    @Override public boolean holds(Item item) { return kit.is(item) || behavior().holds(this, item); }
    /** The stick it keeps, or empty. */
    public ItemStack kit() { return kit; }
    /** Keeps one stick (picked up by its owner): true when it had none. */
    public boolean stow(ItemStack one) {
        if (!kit.isEmpty() || one.isEmpty() || level().isClientSide) return false;
        kit = one.copyWithCount(1);
        GameplayLog.event(this, "hat", "kit stowed item={} owner={}", kit.getItem(), ownerYukkuri());
        return true;
    }
    /** Turned over or destroyed: the stick falls out where it lies, a real floating item. */
    public void dropKit(Vec3 at, Vec3 v, String cause) {
        if (kit.isEmpty() || level().isClientSide) return;
        var drop = new ItemEntity(level(), at.x, at.y, at.z, kit);
        drop.setDeltaMovement(v);
        drop.setDefaultPickUpDelay();
        kit = ItemStack.EMPTY;
        GameplayLog.event(this, "hat", "kit dropped cause={} at={} spawned={}", cause, GameplayLog.at(at), level().addFreshEntity(drop));
    }
    @Override public boolean isPushable() { return !isRemoved() && behavior().pushable(this); }
    /** No vanilla soft push: afloat the behaviour's own contact pass is the one place bodies and the hull are separated. */
    @Override public void push(Entity other) {}
    /** How far (blocks) a running unload is looked for when {@link #unloadRequested()} derives its answer. */
    private static final double UNLOAD_RANGE = 16;
    @Override public boolean unloadRequested() { asked(); return unloadWanted; }
    @Override public boolean attended() { asked(); return attendWanted; }
    /** What the yukkuri this near run on this hat now, outer or innermost {@code HostIntent}: an unload ask, or only attending
     *  its young; read once a tick (derived, never stored). Only the cradle's young are asked by intent. */
    private void asked() {
        long now = level().getGameTime();
        if (unloadTick == now) return;
        unloadTick = now;
        unloadWanted = attendWanted = false;
        if (level().isClientSide) return;
        for (var body : level().getEntitiesOfClass(YukkuriEntity.class, getBoundingBox().inflate(UNLOAD_RANGE))) {
            if (!(body.motor().owner() instanceof IntentRunner runner) || !runner.active()) continue;
            for (var intent : new Intent[]{runner.intent(), runner.innermost().intent()})
                if (intent instanceof HostIntent onHost && onHost.host().entity() == this) {
                    if (onHost.unloads()) unloadWanted = true; else attendWanted = true;
                }
        }
    }
    /** The local spot with its height set to the hat surface under these feet, when there is one. */
    private Vec3 snapped(Vec3 local, YukkuriEntity body) {
        var height = support(local, body);
        return height.isPresent() ? new Vec3(local.x, height.getAsDouble(), local.z) : local;
    }
    private double footOf(YukkuriEntity body) { return Occupancy.foot(body.getBbWidth()) / unit(); }
    /** The body's height in hat units. */
    private double tallOf(Entity body) { return body.getBbHeight() / unit(); }
    /** The spot this rider stands on (hat units), or null when there is none: the server table, the client sync slot. */
    private Vec3 riderPlace(Entity passenger) {
        if (!level().isClientSide) {
            var spot = riders.spot(passenger.getUUID());
            return spot == null ? null : spot.local();
        }
        var spot = Riders.readSync(entityData.get(RIDERS)).get(passenger.getId());
        return spot == null ? null : spot.local();
    }
    /** Writes the rider table's sync slot at most once a tick, and only when a spot changed. */
    private void syncRiders() {
        if (!riders.dirty()) return;
        var tag = riders.syncTag(this::riderEntityId);
        entityData.set(RIDERS, tag);
        // A spot whose body is not attached yet stays dirty, so it is sent once the passenger link exists.
        if (tag.getAllKeys().size() == riders.size()) riders.clearDirty();
    }
    private Integer riderEntityId(UUID id) {
        for (var p : getPassengers()) if (p.getUUID().equals(id)) return p.getId();
        return null;
    }
    /** The supported, free spot near {@code prefer} for a body of this foot radius and height (hat units); nothing is kept
     *  (no second store). */
    private Vec3 nestPlace(BodyFootprint body, Vec3 prefer) {
        if (prefer == null) return null;
        var height = support(prefer,body);
        if(height.isEmpty())return null;
        var spot = new Vec3(prefer.x,height.getAsDouble(),prefer.z);
        return clear(body,spot,null,0) ? spot : null;
    }
    /** The first free supported place on the hat, or null; a place is asked for, never kept (no seats, no leases). */
    private Vec3 nestSpace(double footRadius, BodyFootprint body) {
        var centre = spots().get("centre");
        if (centre == null) return null;
        var middle = nestPlace(body, centre);
        if (middle != null) return middle;
        // Rings out from the middle, a foot radius apart: a body standing over the middle leaves the nearest free floor or brim spot.
        double reach = Math.hypot(profile().brim()[0], profile().brim()[1]);
        for (double ring = footRadius; ring <= reach; ring += footRadius) {
            int around = Math.max(8, (int) Math.ceil(2 * Math.PI * ring / footRadius));
            for (int i = 0; i < around; i++) {
                double angle = i * 2 * Math.PI / around;
                var place = nestPlace(body, new Vec3(centre.x + Math.cos(angle) * ring, centre.y, centre.z + Math.sin(angle) * ring));
                if (place != null) return place;
            }
        }
        return null;
    }
    /** How far ahead of the outlet (blocks, along the push) a birth into a boat aims its hollow place, so the drawn push carries
     *  a newborn short of it, onto it, or past the brim (project design). */
    public static final double BIRTH_LEAD = .2;
    /** The place this young would get next, without keeping it: the aim of its drawn launch. A cradle fills from its middle; a
     *  boat its mother rides takes it at the free hollow place nearest {@link #BIRTH_LEAD} ahead of where it comes out. */
    public Vec3 birthPlace(YukkuriEntity child) {
        if (child == null) return null;
        if (catches()) return nestSpace(footOf(child), shape(child));
        var near = toLocal(child.position().add(Vec3.directionFromRotation(0, child.getYRot()).multiply(1, 0, 1).normalize().scale(BIRTH_LEAD)));
        return hollow(footOf(child), shape(child), child.getBbWidth()).stream()
                .min(Comparator.comparingDouble(p -> Math.hypot(p.x - near.x, p.z - near.z))).orElse(null);
    }
    /** Every free supported place inside the hollow (world) for an unborn child of this size: where a birth into the boat its
     *  mother rides may aim; asked for, never kept. */
    public List<Vec3> hollowPlaces(FamilyData.BirthKind kind, FamilyData.Child child) {
        if (level().isClientSide) return List.of();
        var size = LiveBirthGeometry.unbornDimensions(kind, child);
        return hollow(Occupancy.foot(size.width()) / unit(), BodyFootprint.of(SubspeciesLife.assetId(child.variant,child.subspeciesFlags),size.width()/unit(),size.height()/unit(),0), size.width()).stream().map(this::toWorld).toList();
    }
    /** The free places (hat units) inside the hollow, INSIDE_SUPPORTED and clear at their height, a foot apart. */
    private List<Vec3> hollow(double foot, BodyFootprint body, double width) {
        float[] o = profile().opening();
        double step = Math.max(.5, foot);
        var out = new ArrayList<Vec3>();
        for (double x = o[0] + foot; x <= o[1] - foot; x += step)
            for (double z = o[2] + foot; z <= o[3] - foot; z += step) {
                var place = nestPlace(body, new Vec3(x, 0, z));
                if (place != null && Occupancy.at(container(), frame().toWorld(place), width, true) == Occupancy.INSIDE_SUPPORTED) out.add(place);
            }
        return out;
    }
    /** I2: the rider table's keys are this host's passengers. A mismatch is reported, never "fixed" by moving a body. */
    private void checkRiders(YukkuriEntity subject) {
        if (level().isClientSide) return;
        var keys = riders.keys();
        for (var p : getPassengers()) if (!keys.contains(p.getUUID())) {
            if (p instanceof YukkuriEntity y) Motor.invariant(y, "I2", "rider spot missing for passenger " + p.getUUID());
            return;
        }
        for (var id : keys) if (getPassengers().stream().noneMatch(p -> p.getUUID().equals(id))) {
            Motor.invariant(subject, "I2", "rider spot without passenger " + id);
            return;
        }
    }

    // ---------------------------------------------------------------- bodies, authority and admission
    /** The owner's own young (as mother or father), not yet adult. */
    public boolean young(YukkuriEntity y) {
        return ownerYukkuri() != null && (ownerYukkuri().equals(y.family().mother()) || ownerYukkuri().equals(y.family().father())) && !y.family().adult();
    }
    /** Physical boarding authority: the birth family of the owner. A cradle grants no other relation. */
    public boolean admits(YukkuriEntity child) { return child != null && !child.family().adult() && young(child); }
    public int youngAboard() { return (int) getPassengers().stream().filter(p -> !p.getUUID().equals(ownerYukkuri())).count(); }
    /** The supported free place an unborn child of this size (foot radius and height) would get now (world), or null: an aim,
     *  never kept. */
    public Vec3 birthPlace(FamilyData.BirthKind kind,FamilyData.Child child) {
        var size = LiveBirthGeometry.unbornDimensions(kind,child);
        var place = catches() && !level().isClientSide ? nestSpace(Occupancy.foot(size.width())/unit(), BodyFootprint.of(SubspeciesLife.assetId(child.variant,child.subspeciesFlags),size.width()/unit(),size.height()/unit(),0)) : null;
        return place == null ? null : toWorld(place);
    }
    @Override protected void addPassenger(Entity passenger) {
        super.addPassenger(passenger);
        if (level().isClientSide || !(passenger instanceof YukkuriEntity y)) return;
        // The riders table is the only place a body has aboard; a spot loaded from NBT is kept as saved.
        // A spot already in the table is a rider read back from the save, not one that just boarded.
        boolean boarded = !riders.contains(y.getUUID());
        if (boarded)
            riders.put(y.getUUID(), snapped(frame().toLocal(y.position()), y), Mth.wrapDegrees(y.getYRot() - getYRot()));
        checkRiders(y);
        if (boarded) GameplayLog.event(this, "hat", "boarded rider={} own={} riders={} owner={}", y.getUUID(), y.getUUID().equals(ownerYukkuri()),
                getPassengers().size(), ownerYukkuri());
    }
    @Override protected void removePassenger(Entity passenger) {
        super.removePassenger(passenger);
        if (level().isClientSide) return;
        riders.remove(passenger.getUUID());
        checkRiders(passenger instanceof YukkuriEntity y ? y : null);
        if (!isRemoved() && !passenger.isRemoved()) GameplayLog.event(this, "hat", "left rider={} at={} riders={} owner={}", passenger.getUUID(),
                GameplayLog.at(passenger.position()), getPassengers().size(), ownerYukkuri());
    }

    // ---------------------------------------------------------------- riders and the surface
    public List<Entity> youngPassengers() { return getPassengers().stream().filter(p -> !p.getUUID().equals(ownerYukkuri())).toList(); }
    public UUID ownerYukkuri() { return entityData.get(OWNER).orElse(null); }
    @Override public Vec3 getDismountLocationForPassenger(LivingEntity passenger){
        // Motor.dismount keeps the body where its feet actually are; vanilla's relocation to the hull centre would teleport it.
        if (Motor.DISMOUNTING.get()) return passenger.position();
        return super.getDismountLocationForPassenger(passenger);
    }
    @Override public boolean isPickable() { return !isRemoved(); }
    /** The thin hull box is a narrow aim target; a full body aboard hides it from a click. */
    @Override public float getPickRadius() { return catches()&&isVehicle()?0:.4f; }
    public boolean isCradle() { return state() == State.CRADLE; }
    /** Set down by its owner to catch young, on the ground; afloat it is her boat (PlacedHatBehavior.catches). */
    public boolean catches() { return isCradle() && behavior().catches(this); }
    @Override protected boolean canAddPassenger(Entity passenger) { return passenger instanceof YukkuriEntity y && bears(y); }
    /** One admission predicate: its owner's own young, and the owner herself on her boat (not on the catch she sets down, not
     *  while it lies crown up), none squeezed off it a moment ago. Where it stands and whether that spot is free is the
     *  contact's, the climb's or the squeeze's. */
    @Override public boolean bears(YukkuriEntity y) {
        if (state() == State.RESCUED || y == null || cooling(y)) return false;
        return y.getUUID().equals(ownerYukkuri()) ? !catches() && !upsideDown() : admits(y);
    }
    @Override public LivingEntity getControllingPassenger() {
        for (var p : getPassengers()) if (p instanceof YukkuriEntity y && y.getUUID().equals(ownerYukkuri())) return y;
        return null;
    }
    @Override protected void positionRider(Entity passenger, MoveFunction move) {
        // The riders table is the only place. No seat, no walk, no curve; a missing spot does not move the body.
        if (!hasPassenger(passenger)) return;
        var place = riderPlace(passenger);
        if (place == null) return;
        var at = frame().toWorld(place);
        move.accept(passenger, at.x, at.y, at.z);
    }

    // ---------------------------------------------------------------- custody
    /** The placed hat this owner rides or has set down; a pure read of her {@code HatCustody}. */
    public static PlacedHatEntity find(YukkuriEntity y) {
        if (y.getVehicle() instanceof PlacedHatEntity placed && placed.ownsPhysicalHat(y)) return placed;
        return owned(y);
    }
    /** The hat her custody names while it is set down, if it is loaded; no scan, no side effect. */
    private static PlacedHatEntity owned(YukkuriEntity y) {
        if (!(y.level() instanceof ServerLevel server)) return null;
        UUID id = y.life().custody().boat();
        return id != null && server.getEntity(id) instanceof PlacedHatEntity placed && placed.ownsPhysicalHat(y) ? placed : null;
    }
    public boolean ownsPhysicalHat(YukkuriEntity actor){
        return actor != null && actor.getUUID().equals(ownerYukkuri()) && actor.life().matchesAccessory(ownerYukkuri(),accessoryToken);
    }
    /** Put the hat back on: it must be empty and its owner beside it. */
    public boolean retrieve(YukkuriEntity owner) {
        if (level().isClientSide || !owner.getUUID().equals(ownerYukkuri()) || !behavior().retrievable(this, owner) || owner.distanceToSqr(this) > 16)
            return false;
        if (!getPassengers().isEmpty()) return false;
        if (!owner.life().returnAccessory(ownerYukkuri(), accessoryToken, null)) return false;
        // the stick goes back into the worn hat; a hat already keeping one drops this one at her feet
        if (!kit.isEmpty() && !owner.life().custody().stow(kit)) dropKit(owner.position(), Vec3.ZERO, "retrieve_full");
        kit = ItemStack.EMPTY;
        release("retrieved");
        state(State.RESCUED); return true;
    }
    /** What every behaviour keeps for this hat falls out where it lies, as real items (taken back, or destroyed). */
    private void release(String cause) {
        for (var held : instances.values()) held.release(this, cause);
    }
    /** Destroyed (killed or discarded, a taken-back hat included) it drops what it held; unloaded to its chunk it keeps it saved. */
    @Override public void remove(RemovalReason reason) {
        if (!level().isClientSide && reason.shouldDestroy()) { release("removed"); dropKit(position(), getDeltaMovement(), "removed"); }
        super.remove(reason);
    }

    /**
     * The one way a catching hat comes into the world: an empty upside-down hat set down by its Marisa owner's braid on dry
     * ground. Every failure is logged with its real reason and leaves the hat on her head, so the delivery goes on without it.
     */
    public static PlacedHatEntity setDown(YukkuriEntity owner, Vec3 at, float yaw, HatGeometry.Role role) {
        if (!(owner.level() instanceof ServerLevel level)) return null;
        if (owner.getVariant() != YukkuriVariant.MARISA) return refusePlacement(owner, at, "not_marisa", "");
        if (!owner.isAlive()) return refusePlacement(owner, at, "not_alive", "");
        if (owner.isPassenger()) return refusePlacement(owner, at, "passenger", "");
        if (!owner.life().hasAccessory()) return refusePlacement(owner, at, "no_accessory", "");
        if (owner.life().custody().custodied()) return refusePlacement(owner, at, "existing_hat", "");
        var spot = BlockPos.containing(at);
        if (!level.hasChunkAt(spot)) return refusePlacement(owner, at, "spot_unloaded", spot.toShortString());
        // Open water carries a hat set down on it (it floats, PlacedHatBehavior); a catch on land asks HatProp.fits for dry ground.
        boolean water = level.getFluidState(spot).is(FluidTags.WATER) && level.getFluidState(spot.above()).isEmpty();
        if (!water && !level.getFluidState(spot).isEmpty()) return refusePlacement(owner, at, "spot_in_fluid", spot.toShortString());
        if (!water && !level.getBlockState(spot.below()).isFaceSturdy(level, spot.below(), Direction.UP))
            return refusePlacement(owner, at, "no_sturdy_floor", spot.below().toShortString() + " " + level.getBlockState(spot.below()).getBlock());
        var hat = ModEntities.PLACED_HAT.get().create(level);
        if (hat == null) return refusePlacement(owner, at, "entity_creation_failed", "");
        hat.setPos(at.x, water ? spot.getY() + level.getFluidState(spot).getHeight(level, spot) : spot.getY(), at.z);
        hat.setYRot(yaw);
        hat.launchRole(role);
        hat.formId(HatGeometry.wornForm(owner).id());
        hat.syncOwner(Optional.of(owner.getUUID()));
        hat.syncAccessoryIdentity(owner);
        hat.state(State.CRADLE);
        if (!level.addFreshEntity(hat)) return refusePlacement(owner, at, "entity_add_failed", "");
        var kit = owner.life().custody().takeKit();
        hat.launchToken(owner.life().stowAccessory(hat.getUUID()));
        if (hat.launchToken() == null) {
            owner.life().returnAccessory(owner.getUUID(), null, null);
            owner.life().custody().stow(kit);
            hat.discard();
            return refusePlacement(owner, at, "accessory_transfer_failed", "");
        }
        hat.kit = kit;
        GameplayLog.event(owner, "hat", "placed owner={} hat={} at={} yaw={} scale={} form={} role={} water={} kit={}",
                owner.getUUID(), hat.getUUID(), hat.position(), yaw, hat.hullScale(), hat.formId(), role, water, hat.kit.getItem());
        return hat;
    }
    private static PlacedHatEntity refusePlacement(YukkuriEntity owner, Vec3 at, String reason, String detail) {
        GameplayLog.limited(owner, "headwear", "hat.refused." + reason, 200,
                "hat refused reason={} detail={} at={} distanceSquared={} accessory={} custody={}",
                reason, detail, at, owner.distanceToSqr(at), owner.life().hasAccessory(), owner.life().custody().state());
        return null;
    }

    // ---------------------------------------------------------------- identity and wear
    public void syncAccessoryIdentity(YukkuriEntity owner) {
        entityData.set(SCALE,HatScale.of(owner));
        entityData.set(HULL_DAMAGE,Math.clamp(owner.headwear().damage(),0,2));
    }
    public int hullDamage() { return entityData.get(HULL_DAMAGE); }
    public State state() { return State.values()[Mth.clamp(entityData.get(STATE), 0, State.values().length-1)]; }
    public void state(State next) { entityData.set(STATE, next.ordinal()); }
    /** The hat's size, the same in every form (HatScale). */
    public float hullScale() { return entityData.get(SCALE); }
    public YukkuriEntity owner() {
        return ownerYukkuri() != null && level() instanceof ServerLevel server && server.getEntity(ownerYukkuri()) instanceof YukkuriEntity y ? y : null;
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
        // Food goes into the hollow (or is refused with its reason); empty hands and anything else rock a hat that rocks.
        if (!held.isEmpty() && behavior().store(this, held, player))
            return playerInteractionResult(player,hand,"store",true,InteractionResult.sidedSuccess(level().isClientSide));
        if (behavior().shake(this, player))
            return playerInteractionResult(player,hand,"shake",true,InteractionResult.sidedSuccess(level().isClientSide));
        if (!held.isEmpty())
            return playerInteractionResult(player,hand,"cradle_requires_empty_hand",false,InteractionResult.PASS);
        if (!level().isClientSide) {
            var keeper=owner();
            player.displayClientMessage(Component.literal("接生帽：幼体 "+youngPassengers().size()+"；"
                    +(keeper==null?"帽主未在加载范围内":keeper.family().birthDuty()?"正在接生或安置幼体":"等待帽主接回")),true);
        }
        return playerInteractionResult(player,hand,"cradle_status",true,InteractionResult.sidedSuccess(level().isClientSide));
    }
    /** Record the executed branch and its real result, including consumed clicks that produced no physical change. */
    private InteractionResult playerInteractionResult(Player player,InteractionHand hand,String action,boolean applied,InteractionResult result){
        if(!level().isClientSide)GameplayLog.limited(this,"interaction","hat_use."+hand,20,
                "hat use result player={} hand={} action={} applied={} result={} state={} empty={} distanceSqr={} passengers={}",
                player.getUUID(),hand,action,applied,result,state(),player.getItemInHand(hand).isEmpty(),player.distanceToSqr(this),
                getPassengers().size());
        return result;
    }

    @Override public void tick() {
        rollO = roll(); pitchO = pitch();
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
        if (isRemoved()) return;
        var accessoryOwner=owner();
        if(accessoryOwner!=null)syncAccessoryIdentity(accessoryOwner);
        if (state() == State.RESCUED) { if (tickCount % 20 == 0) discard(); return; }
        syncRiders();
        var now = behavior();
        // Another behaviour took over (the hat left the water): what the last one kept for the hat falls out where it lies.
        if (ran != null && ran != now) ran.release(this, "left_" + ranId);
        ran = now;
        ranId = provider == null ? "ground" : provider.id();
        now.tick(this);
        for (var p : getPassengers()) if (p instanceof YukkuriEntity rider) { rider.getNavigation().stop(); rider.fallDistance = 0; }
        squeeze();
    }
    /** Ticks a rider must have been pressed on, already at the rim, before the press pushes it off; ticks before contact may
     *  seat a body squeezed off here again; the least speed (blocks a tick) it leaves with, so it clears the brim's edge; how
     *  deep (share of its foot radius) an overlap must be to count as pressing on it. */
    private static final int SQUEEZE_OFF_TICKS = 20, RESEAT_TICKS = 40;
    private static final double CLEAR_SPEED = .1, PRESSED = .1;
    /**
     * Q6: riders whose footprints overlap at a shared height (one landed among the others, they grew; a newborn low in the hollow
     * under the owner standing over it does not) ease apart in the hat's own plane through
     * {@link Squeeze}: a quarter of the overlap a tick at most, the lighter one, and on a tilted floor the lower one, yielding
     * more (the owner too); what the hollow holds never moves; each moves at most its walk pace, its height the floor under it.
     * Only bodies standing on this hat take part (not one being lifted out by a grab). A rider pressed toward the rim stops at
     * it and the others part from it as from a wall; only one pressed {@link #SQUEEZE_OFF_TICKS} ticks running while at the rim
     * is pushed off: it leaves where its foot crosses the rim, moving outward ({@code Motor.dismount}), and contact may not seat
     * it here again for {@link #RESEAT_TICKS}. Only rider spots are written.
     */
    private void squeeze() {
        var held = behavior().obstacles(this);
        if (upsideDown() || !crowded(held)) { if (!crowding.isEmpty()) crowding.clear(); return; }
        var lifted = new HashSet<YukkuriEntity>();
        for (var carrier : level().getEntitiesOfClass(YukkuriEntity.class, getBoundingBox().inflate(2)))
            if (carrier.motor().current() instanceof Grab grab) lifted.add(grab.target());
        var bodies = new ArrayList<YukkuriEntity>();
        var feet = new ArrayList<Squeeze.Body>();
        double load = load(), unit = unit();
        for (var p : getPassengers()) if (p instanceof YukkuriEntity y && !lifted.contains(y) && riders.contains(y.getUUID())
                && Attach.of(y) instanceof AttachState.OnHost on && on.host() == this) {
            var at = riders.local(y.getUUID());
            bodies.add(y);
            feet.add(new Squeeze.Body(at.x, at.z, Math.max(footOf(y),shape(y).radius()), y.getBbWidth() * y.getBbWidth() * y.getBbHeight(),
                    BodyAbility.of(y, load).walkSpeed() / unit, at.y, at.y + tallOf(y)));
        }
        var footprints = new HashMap<Disc,BodyFootprint>();
        for(int i=0;i<feet.size();i++)footprints.put(Squeeze.disc(feet.get(i)),shape(bodies.get(i)));
        BiPredicate<Disc,Disc> contact = (a,b) -> {
            var body=footprints.get(a); var other=footprints.get(b);
            return other==null ? body.overlaps(a.x(),a.bottom(),a.z(),b,ROOM_SLACK)
                    : body.overlaps(a.x(),a.bottom(),a.z(),other,b.x(),b.bottom(),b.z(),ROOM_SLACK);
        };
        var pose = frame();
        var down = pose.toLocal(position().add(0, -1, 0)).scale(unit);
        var moves = Squeeze.separate(feet, held, ROOM_SLACK, down.x, down.z, contact);
        boolean[] atRim = new boolean[bodies.size()];
        var walls = new ArrayList<>(held);
        for (int i = 0; i < bodies.size(); i++) if (toRim(feet.get(i), moves[i], bodies.get(i))) {
            atRim[i] = true;
            var f = feet.get(i);
            walls.add(new Disc(f.x(), f.z(), f.r(), f.bottom(), f.top()));
        }
        if (walls.size() > held.size()) {
            // The ones held at the rim stay; the others part from them as from a wall.
            var free = new ArrayList<Squeeze.Body>();
            for (int i = 0; i < bodies.size(); i++) if (!atRim[i]) free.add(feet.get(i));
            var again = Squeeze.separate(free, walls, ROOM_SLACK, down.x, down.z, contact);
            for (int i = 0, k = 0; i < bodies.size(); i++) if (!atRim[i]) {
                moves[i][0] = again[k][0];
                moves[i][1] = again[k++][1];
                if (toRim(feet.get(i), moves[i], bodies.get(i))) moves[i][0] = moves[i][1] = 0;
            }
        }
        var pressed = new HashSet<UUID>();
        YukkuriEntity most = null;
        double mostStep = 0;
        for (int i = 0; i < bodies.size(); i++) {
            var y = bodies.get(i);
            var spot = riders.spot(y.getUUID());
            var from = spot.local();
            int ticks = 0;
            if (moves[i][2] > PRESSED * feet.get(i).r()) { ticks = crowding.merge(y.getUUID(), 1, Integer::sum); pressed.add(y.getUUID()); }
            if (atRim[i]) {
                if (ticks >= SQUEEZE_OFF_TICKS) squeezeOff(y, pose, from, rimCrossing(from, feet.get(i), moves[i], y), ticks, held.size());
                continue;
            }
            double step = Math.hypot(moves[i][0], moves[i][1]);
            if (step < 1e-6) continue;
            if (step > mostStep) { mostStep = step; most = y; }
            riders.put(y.getUUID(), snapped(new Vec3(from.x + moves[i][0], from.y, from.z + moves[i][1]), y), spot.yaw());
        }
        crowding.keySet().retainAll(pressed);
        if (most != null || !pressed.isEmpty()) GameplayLog.limited(this, "hat", "squeeze", 100,
                "squeeze pressed={} most={} step={} riders={} held={} tilt={}/{}", pressed.size(), most == null ? "none" : most.getUUID(),
                String.format(Locale.ROOT, "%.3f", mostStep), bodies.size(), held.size(), roll(), pitch());
    }
    /** A move that would take this foot off the hat: shortened to the farthest point its foot still touches it (false), or, with
     *  no way left toward it, the body is at the rim (true). Moves that stay on the hat are left alone (false). */
    private boolean toRim(Squeeze.Body body, double[] move, YukkuriEntity rider) {
        double step = Math.hypot(move[0], move[1]);
        if (step < 1e-6 || support(new Vec3(body.x() + move[0], 0, body.z() + move[1]), rider).isPresent()) return false;
        double inside = rimShare(body, move, rider)[0];
        if (inside * step <= 1e-3) return true;
        move[0] *= inside;
        move[1] *= inside;
        return false;
    }
    /** Along a move off the hat: the share of it its foot still touches the hat, and the share at which it no longer does. */
    private double[] rimShare(Squeeze.Body body, double[] move, YukkuriEntity rider) {
        double inside = 0, outside = 1;
        for (int k = 0; k < 12; k++) {
            double mid = (inside + outside) / 2;
            if (support(new Vec3(body.x() + move[0] * mid, 0, body.z() + move[1] * mid), rider).isPresent()) inside = mid; else outside = mid;
        }
        return new double[]{inside, outside};
    }
    /** Where the press would carry a body held at the rim off the hat: its foot's crossing along the first pass's move. */
    private Vec3 rimCrossing(Vec3 from, Squeeze.Body body, double[] move, YukkuriEntity rider) {
        double outside = rimShare(body, move, rider)[1];
        return new Vec3(from.x + move[0] * outside, from.y, from.z + move[1] * outside);
    }
    /** Pressed at the rim long enough: it leaves where its foot crosses the rim, moving outward at least {@link #CLEAR_SPEED}. */
    private void squeezeOff(YukkuriEntity y, HostFrame pose, Vec3 from, Vec3 crossing, int ticks, int held) {
        var at = pose.toWorld(crossing);
        var outward = at.subtract(pose.toWorld(from)).multiply(1, 0, 1);
        double speed = Math.max(outward.length(), CLEAR_SPEED);
        var slide = (outward.lengthSqr() < 1e-12 ? at.subtract(position()).multiply(1, 0, 1) : outward).normalize().scale(speed)
                .add(getDeltaMovement());
        crowding.remove(y.getUUID());
        long now = level().getGameTime();
        reseat.values().removeIf(until -> until <= now);
        reseat.put(y.getUUID(), now + RESEAT_TICKS);
        Motor.dismount(y, at, slide, "squeezed");
        GameplayLog.event(this, "hat", "squeezed off rider={} at={} slide={} pressed_ticks={} riders={} held={} tilt={}/{}", y.getUUID(),
                GameplayLog.at(at), GameplayLog.at(slide), ticks, getPassengers().size(), held, roll(), pitch());
    }
    /** How far (blocks) under a hat's surface feet must be to count as under the hat. */
    private static final double UNDER = .05;
    /**
     * Nobody stays under a hat lying on the ground (it has no collision): a free body whose footprint ({@link Occupancy#foot})
     * overlaps the brim ({@link HullProfile#clearance}, the rule the stands beside a hat keep their margin from) with its feet
     * under the hat's top is eased out along the line from the hat's middle until its footprint clears the brim, by
     * {@link Squeeze}'s rule with the brim as a fixed circle (a quarter of the way a tick, at most its walk pace), through Motor.
     * A mother holding her birth place is moved only once her middle is over the brim (a stem hat's near brim may lie under her
     * front), and one in flight is left alone (its contact is the flight's). The land behaviour's contact pass; afloat the
     * hull's own pass keeps bodies out.
     */
    public void clearUnder() {
        if (level().isClientSide) return;
        var pose = frame();
        double unit = unit(), reach = Math.hypot(profile().brim()[0], profile().brim()[1]) * unit;
        for (var y : level().getEntitiesOfClass(YukkuriEntity.class, getBoundingBox().inflate(reach, 1, reach))) {
            if (y.isPassenger() || !y.isAlive() || Attach.of(y) instanceof AttachState.Airborne) continue;
            var local = pose.toLocal(y.position());
            double foot = footOf(y), clearance = profile().clearance(local.x, local.z);
            if (clearance >= foot || local.y >= profile().rimY() - UNDER / unit) continue;
            if (clearance > 0 && y.family().holdsBirthStation()) continue;
            double d = Math.hypot(local.x, local.z), dx = d > 1e-6 ? local.x / d : 1, dz = d > 1e-6 ? local.z / d : 0;
            // where its footprint clears the brim in its direction, a little beyond, so the quarter steps carry it past
            double edge = profile().clear(dx, dz, foot) + .125;
            var move = Squeeze.separate(List.of(new Squeeze.Body(local.x, local.z, foot, 1, BodyAbility.of(y).walkSpeed() / unit)),
                    List.of(new Disc(0, 0, edge - foot + ROOM_SLACK)), ROOM_SLACK, 0, 0)[0];
            var step = pose.toWorld(new Vec3(local.x + move[0], local.y, local.z + move[1])).subtract(y.position()).multiply(1, 0, 1);
            var moved = Motor.move(y, step, "hat_under");
            GameplayLog.limited(this, "hat", "under." + y.getUUID(), 100, "eased out from under body={} at={} step={} moved={} owner={}",
                    y.getUUID(), GameplayLog.at(y.position()), GameplayLog.at(step), GameplayLog.at(moved), ownerYukkuri());
        }
    }
    /** The same column contact as room() and squeeze(), excluding each rider itself. */
    private boolean crowded(List<Disc> held) {
        for(var other:getPassengers())if(other instanceof YukkuriEntity y && riders.contains(y.getUUID())
                && !clear(shape(y),riders.local(y.getUUID()),y,0))return true;
        return false;
    }
    /** Yaw and pitch ease over the same steps as the position, so riders do not judder on each packet. */
    @Override public void lerpTo(double x,double y,double z,float yaw,float pitch,int steps) {
        lerpX=x;lerpY=y;lerpZ=z;lerpYaw=yaw;lerpPitch=pitch;lerpSteps=Math.max(1,steps);
    }
    @Override protected void addAdditionalSaveData(CompoundTag tag) {
        if(ownerYukkuri()!=null)tag.putUUID("OwnerYukkuri",ownerYukkuri());
        if(accessoryToken!=null)tag.putUUID("AccessoryToken",accessoryToken);
        tag.putString("State",state().name());tag.putFloat("HullScale",hullScale());
        tag.putString("Role",role.name());tag.putString("HullForm",formId());
        tag.put("Riders",riders.save());
        tag.putFloat("Roll",roll());tag.putFloat("Pitch",pitch());
        if(!kit.isEmpty())tag.put("Kit",kit.save(registryAccess()));
        // The behaviour it last floated with keeps its state (stores included) while it lies on the ground.
        if(lastProvider!=null){var motion=instances.get(lastProvider).save();motion.putString("Kind",lastProvider.id());tag.put("Motion",motion);}
        else if(savedMotion!=null)tag.put("Motion",savedMotion.copy());
    }
    @Override protected void readAdditionalSaveData(CompoundTag tag) {
        entityData.set(OWNER,tag.hasUUID("OwnerYukkuri")?Optional.of(tag.getUUID("OwnerYukkuri")):Optional.empty());
        accessoryToken=tag.hasUUID("AccessoryToken")?tag.getUUID("AccessoryToken"):null;
        try{state(State.valueOf(tag.getString("State")));}catch(IllegalArgumentException invalid){state(State.CRADLE);}
        entityData.set(SCALE,Math.clamp(tag.getFloat("HullScale"),.1f,2.5f));
        role="SELF".equals(tag.getString("Role"))?HatGeometry.Role.SELF:HatGeometry.Role.HELPER;
        String form=tag.contains("HullForm",Tag.TAG_STRING)?tag.getString("HullForm"):HullProfile.CURRENT;
        if(!HullProfile.known(form))GameplayLog.event(this,"hat","unknown saved form={} uuid={}",form,getUUID());
        formId(form);
        riders.load(tag.getList("Riders",Tag.TAG_COMPOUND));
        pose(Mth.wrapDegrees(tag.getFloat("Roll")),Mth.wrapDegrees(tag.getFloat("Pitch")));
        rollO=roll();pitchO=pitch();
        kit=ItemStack.parseOptional(registryAccess(),tag.getCompound("Kit"));
        savedMotion=tag.contains("Motion",Tag.TAG_COMPOUND)?tag.getCompound("Motion"):null;
    }
}
