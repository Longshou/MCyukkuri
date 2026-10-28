package io.github.longshou.mcyukkuri.headwear;

import io.github.longshou.mcyukkuri.motor.Motor;
import io.github.longshou.mcyukkuri.motor.geom.Disc;
import io.github.longshou.mcyukkuri.motor.geom.FloorShape;
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
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.UUID;
import java.util.LinkedHashMap;
import net.minecraft.nbt.Tag;
import net.minecraft.util.Mth;

/** One real hat (her own hat turned upside down, its soft crown pressed down: HatGeometry) set down on the ground by its Marisa
 *  owner to catch her young. Every body aboard keeps its own supported spot in the {@link Riders} table; there are no seats,
 *  no leases and no load. A cradle that has been taken back leaves the world. */
public final class PlacedHatEntity extends Entity implements PlacedHatView, SurfaceHost {
    /** CRADLE: a Marisa's hat turned over on dry ground, its soft crown pressed flat under it, to catch young.
     *  RESCUED: taken back onto its owner's head. Ordinals are synced: append only. */
    public enum State { CRADLE, RESCUED }
    private static final EntityDataAccessor<Integer> STATE = SynchedEntityData.defineId(PlacedHatEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Optional<UUID>> OWNER = SynchedEntityData.defineId(PlacedHatEntity.class, EntityDataSerializers.OPTIONAL_UUID);
    private static final EntityDataAccessor<Float> SCALE = SynchedEntityData.defineId(PlacedHatEntity.class, EntityDataSerializers.FLOAT);
    /** The deployed hat's own wear (headwear damage 0 intact, 1 frayed, 2 torn), drawn on the hull like a worn hat. */
    private static final EntityDataAccessor<Integer> HULL_DAMAGE = SynchedEntityData.defineId(PlacedHatEntity.class, EntityDataSerializers.INT);
    /** Every rider's local spot (hat units) keyed by rider entity id: the client's {@code positionRider} input, written once a tick when it changed. */
    private static final EntityDataAccessor<CompoundTag> RIDERS = SynchedEntityData.defineId(PlacedHatEntity.class, EntityDataSerializers.COMPOUND_TAG);
    /** The one rider table of this host: local spot and yaw per body (spec 5.1, 6). */
    private final Riders riders = new Riders();
    /** Cached cavity of {@link #container()}; rebuilt when the hat size changes. */
    private Container container;
    private double containerUnit;
    /** {@link #unloadRequested()} cached for one game tick: the answer is derived from the running intents, never stored. */
    private long unloadTick = Long.MIN_VALUE;
    private boolean unloadWanted;
    private UUID accessoryToken;
    /** Who set the hat down to catch: a helper beside it, or the mother before her own outlet. */
    private HatGeometry.Role role = HatGeometry.Role.HELPER;
    private double lerpX, lerpY, lerpZ;
    private float lerpYaw, lerpPitch;
    private int lerpSteps;
    /** The identity of an unborn birth-size probe used only for read-only room questions. */
    private static final UUID NEWBORN_PROBE = new UUID(0L, 1L);
    /** The one birth berth a cradle grants: an ordinary supported place in the cavity. */
    public enum NewbornAdmission { NONE, BOARD }

    public PlacedHatEntity(EntityType<? extends PlacedHatEntity> type, Level level) { super(type, level); blocksBuilding = true; }
    @Override protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(STATE, State.CRADLE.ordinal()); builder.define(OWNER, Optional.empty()); builder.define(SCALE, 1f);
        builder.define(RIDERS, new CompoundTag()); builder.define(HULL_DAMAGE, 0);
    }
    @Override public void onSyncedDataUpdated(EntityDataAccessor<?> data) {
        super.onSyncedDataUpdated(data);
        if (SCALE.equals(data)) refreshDimensions();
    }
    @Override public EntityDimensions getDimensions(Pose pose) {
        return super.getDimensions(pose).scale(hullScale()/HatGeometry.AQUATIC_SCALE);
    }

    public void syncHullScale(float scale) { entityData.set(SCALE, scale); }
    public void syncOwner(Optional<UUID> owner) { entityData.set(OWNER, owner); }
    public UUID launchToken() { return accessoryToken; }
    public void launchToken(UUID token) { accessoryToken = token; }
    public void launchRole(HatGeometry.Role next) { role = next; }
    public HatGeometry.Role role() { return role; }
    public float previousYaw() { return yRotO; }

    // ---------------------------------------------------------------- the host (SurfaceHost, spec 6)
    private static final int RIM_POINTS = 24;
    /** How much two foot circles must overlap before they conflict (spec 6). */
    private static final double ROOM_SLACK = .02;

    /** Blocks per hat unit (the prop's units at this hat's size). */
    public double unit() { return hullScale()/16; }
    public float turn() { return (float) Math.toRadians(180 - getYRot()); }
    public Vec3 toWorld(Vec3 local) { return position().add(local.scale(unit()).yRot(turn())); }
    public Vec3 toLocal(Vec3 world) { return world.subtract(position()).yRot(-turn()).scale(1/unit()); }

    @Override public Entity entity() { return this; }
    @Override public HostFrame frame() { return new HostFrame(position(), unit(), turn(), 0, 0); }
    /** Hat-surface height (hat units) under a foot radius {@code r} in blocks; empty off the hat. */
    @Override public OptionalDouble support(Vec3 local, double r) {
        if (local == null) return OptionalDouble.empty();
        double height = HatGeometry.travelSupport(local.x, local.z, r / unit());
        return Double.isFinite(height) ? OptionalDouble.of(height) : OptionalDouble.empty();
    }
    /** No other rider's foot circle overlaps this one: radius 0.42 of the body width, slack 0.02 (spec 6). */
    @Override public boolean room(YukkuriEntity body, Vec3 local) {
        if (body == null || local == null) return false;
        var foot = new Disc(local.x, local.z, Occupancy.FOOT * body.getBbWidth() / unit());
        for (var other : getPassengers()) {
            if (other == body || !(other instanceof YukkuriEntity y)) continue;
            var spot = riders.spot(y.getUUID());
            var at = spot != null ? spot.local() : toLocal(y.position());
            if (foot.overlaps(new Disc(at.x, at.z, Occupancy.FOOT * y.getBbWidth() / unit()), ROOM_SLACK)) return false;
        }
        return true;
    }
    /** The brim's top edge in 24 directions, through the current frame so a placed yaw is included. */
    @Override public List<Vec3> rim() {
        var pose = frame();
        double top = HatGeometry.top();
        var out = new ArrayList<Vec3>(RIM_POINTS);
        for (int i = 0; i < RIM_POINTS; i++) {
            double angle = i * 2 * Math.PI / RIM_POINTS, dx = Math.cos(angle), dz = Math.sin(angle);
            double reach = HatGeometry.extent(0, 0, dx, dz, 0);
            out.add(pose.toWorld(new Vec3(dx * reach, top, dz * reach)));
        }
        return List.copyOf(out);
    }
    /** Named places, hat units: the middle of the cavity, and the brim edge on the owner's side. Targets only. */
    @Override public Map<String, Vec3> spots() {
        var out = new LinkedHashMap<String, Vec3>();
        double middle = HatGeometry.surface(0, 0);
        out.put("centre", new Vec3(0, Double.isFinite(middle) ? middle : HatGeometry.floor(), 0));
        double dx = 0, dz = -1;
        var owner = owner();
        if (owner != null) {
            var local = toLocal(owner.position());
            double length = Math.hypot(local.x, local.z);
            if (length > 1e-6) { dx = local.x / length; dz = local.z / length; }
        }
        double reach = Math.max(0, HatGeometry.extent(0, 0, dx, dz, 0) - .125);
        out.put("exit", new Vec3(dx * reach, HatGeometry.top(), dz * reach));
        return Map.copyOf(out);
    }
    /** The cavity's own floor and brim heights (hat units) and, as its radius, the authored brim extent (spec 7). */
    @Override public Container container() {
        if (container == null || containerUnit != unit()) {
            double radius = Math.min(HatGeometry.extent(0, 0, 1, 0, 0), HatGeometry.extent(0, 0, 0, 1, 0)) * unit();
            container = new HostContainer(this, new FloorShape.Circle(radius), HatGeometry.floor(), HatGeometry.top());
            containerUnit = unit();
        }
        return container;
    }
    @Override public Riders riders() { return riders; }
    @Override public boolean steady() { return state() != State.RESCUED; }
    /** How far (blocks) a running unload is looked for when {@link #unloadRequested()} derives its answer. */
    private static final double UNLOAD_RANGE = 16;
    @Override public boolean unloadRequested() {
        // Only the cradle's young are asked to leave by intent (spec 14 WP1).
        if (level().isClientSide) return false;
        long now = level().getGameTime();
        if (unloadTick != now) { unloadTick = now; unloadWanted = unloading(); }
        return unloadWanted;
    }
    /** Some yukkuri this near runs a {@code HostIntent} for this hat, outer or innermost (spec 6: derived, never stored). */
    private boolean unloading() {
        for (var body : level().getEntitiesOfClass(YukkuriEntity.class, getBoundingBox().inflate(UNLOAD_RANGE))) {
            if (!(body.motor().owner() instanceof IntentRunner runner) || !runner.active()) continue;
            if (runsOn(runner.intent()) || runsOn(runner.innermost().intent())) return true;
        }
        return false;
    }
    private boolean runsOn(Intent intent) {
        return intent instanceof HostIntent onHost && onHost.host().entity() == this;
    }
    /** The local spot with its height set to the hat surface under these feet, when there is one. */
    private Vec3 snapped(Vec3 local, double footRadius) {
        var height = support(local, footRadius);
        return height.isPresent() ? new Vec3(local.x, height.getAsDouble(), local.z) : local;
    }
    private double footOf(YukkuriEntity body) { return Occupancy.FOOT * body.getBbWidth() / unit(); }
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
    /** The supported, free spot near {@code prefer} for a body of this foot radius; nothing is kept (spec 5.1: no second store). */
    private Vec3 nestPlace(double footRadius, Vec3 prefer) {
        if (prefer == null) return null;
        if (support(prefer, footRadius * unit()).isEmpty()) return null;
        var spot = snapped(prefer, footRadius * unit());
        return room(footRadius, spot) ? spot : null;
    }
    /** No other rider's foot circle overlaps a body of this foot radius (hat units). */
    private boolean room(double footRadius, Vec3 local) {
        if (local == null) return false;
        var foot = new Disc(local.x, local.z, footRadius);
        for (var other : getPassengers()) {
            if (!(other instanceof YukkuriEntity y)) continue;
            var spot = riders.spot(y.getUUID());
            var at = spot != null ? spot.local() : toLocal(y.position());
            if (foot.overlaps(new Disc(at.x, at.z, footOf(y)), ROOM_SLACK)) return false;
        }
        return true;
    }
    /** The supported, free spot near {@code prefer} this young may take now; nothing is kept (spec 5.1: no second store). */
    private Vec3 nestPlace(YukkuriEntity child, Vec3 prefer) {
        return child == null || !admits(child) ? null : nestPlace(footOf(child), prefer != null ? prefer : frame().toLocal(child.position()));
    }
    /** The first free supported place on the hat, or null; a place is asked for, never kept (no seats, no leases). */
    private Vec3 nestSpace(double footRadius) {
        var centre = spots().get("centre");
        if (centre == null) return null;
        var middle = nestPlace(footRadius, centre);
        if (middle != null) return middle;
        double step = footRadius;
        for (int i = 0; i < 4; i++) {
            double angle = i * Math.PI / 2;
            var place = nestPlace(footRadius, new Vec3(centre.x + Math.cos(angle) * step, centre.y, centre.z + Math.sin(angle) * step));
            if (place != null) return place;
        }
        return null;
    }
    private Vec3 nestSpace(YukkuriEntity child) { return child == null ? null : nestSpace(footOf(child)); }
    /** The place this young would get next, without keeping it: the aim of its drawn launch. */
    public Vec3 birthPlace(YukkuriEntity child) { return nestSpace(child); }
    private boolean nestHasSpace(YukkuriEntity child) { return nestSpace(child) != null; }
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
    /** The foot radius (hat units) of an unborn body of this birth size. */
    private double newbornFoot(FamilyData.BirthKind kind,FamilyData.Child child) {
        return Occupancy.FOOT*LiveBirthGeometry.unbornDimensions(kind,child).width()/unit();
    }
    public record BirthSeat(Vec3 position, int slot, NewbornAdmission admission) {}
    public List<BirthSeat> birthSeatChoices(FamilyData.BirthKind kind,FamilyData.Child child,Vec3 from) {
        if (!catches() || level().isClientSide) return List.of();
        var foot = newbornFoot(kind, child);
        var seats = new ArrayList<BirthSeat>();
        var first = nestSpace(foot);
        if (first != null) seats.add(new BirthSeat(toWorld(first), -1, NewbornAdmission.BOARD));
        return seats;
    }
    /** Prepare exactly the selected birth place: a cradle keeps no seat, so this only answers whether a supported place exists. */
    public boolean makeRoom(YukkuriEntity child, NewbornAdmission admission) {
        return !level().isClientSide && admission != NewbornAdmission.NONE && nestHasSpace(child);
    }
    @Override protected void addPassenger(Entity passenger) {
        super.addPassenger(passenger);
        if (level().isClientSide || !(passenger instanceof YukkuriEntity y)) return;
        // The riders table is the only place a body has aboard (spec 5.1); a spot loaded from NBT is kept as saved.
        if (!riders.contains(y.getUUID()))
            riders.put(y.getUUID(), snapped(frame().toLocal(y.position()), footOf(y) * unit()), Mth.wrapDegrees(y.getYRot() - getYRot()));
        checkRiders(y);
    }
    @Override protected void removePassenger(Entity passenger) {
        super.removePassenger(passenger);
        if (level().isClientSide) return;
        riders.remove(passenger.getUUID());
        checkRiders(passenger instanceof YukkuriEntity y ? y : null);
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
    /** Set down by its owner to catch young, on the ground. */
    public boolean catches() { return isCradle(); }
    @Override protected boolean canAddPassenger(Entity passenger) {
        if (state() == State.RESCUED || !(passenger instanceof YukkuriEntity y)) return false;
        if (y.getUUID().equals(ownerYukkuri())) return !catches() && getPassengers().stream().noneMatch(p -> p.getUUID().equals(ownerYukkuri()));
        return admits(y) && canBoard(y);
    }
    /** One admission predicate: aboard already, or a legal supported place is free for it. */
    public boolean canBoard(YukkuriEntity child) {
        return child != null && admits(child) && (hasPassenger(child) || nestPlace(child, null) != null || nestHasSpace(child));
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
    public boolean retrieve(YukkuriEntity owner) {        if (level().isClientSide || !catches() || !owner.getUUID().equals(ownerYukkuri()) || owner.distanceToSqr(this) > 16) return false;
        if (!getPassengers().isEmpty()) return false;
        if (!owner.life().returnAccessory(ownerYukkuri(), accessoryToken, null)) return false;
        state(State.RESCUED); return true;
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
        if (!level.getFluidState(spot).isEmpty()) return refusePlacement(owner, at, "spot_in_fluid", spot.toShortString());
        if (!level.getBlockState(spot.below()).isFaceSturdy(level, spot.below(), Direction.UP))
            return refusePlacement(owner, at, "no_sturdy_floor", spot.below().toShortString() + " " + level.getBlockState(spot.below()).getBlock());
        var hat = ModEntities.PLACED_HAT.get().create(level);
        if (hat == null) return refusePlacement(owner, at, "entity_creation_failed", "");
        hat.setPos(at.x, spot.getY(), at.z);
        hat.setYRot(yaw);
        hat.launchRole(role);
        hat.syncOwner(Optional.of(owner.getUUID()));
        hat.syncAccessoryIdentity(owner);
        hat.state(State.CRADLE);
        if (!level.addFreshEntity(hat)) return refusePlacement(owner, at, "entity_add_failed", "");
        hat.launchToken(owner.life().stowAccessory(hat.getUUID()));
        if (hat.launchToken() == null) {
            owner.life().returnAccessory(owner.getUUID(), null, null);
            hat.discard();
            return refusePlacement(owner, at, "accessory_transfer_failed", "");
        }
        GameplayLog.event(owner, "headwear", "hat placed owner={} hat={} at={} yaw={} scale={} role={}",
                owner.getUUID(), hat.getUUID(), hat.position(), yaw, hat.hullScale(), role);
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
        entityData.set(SCALE,HatGeometry.hatScale(owner));
        entityData.set(HULL_DAMAGE,Math.clamp(owner.headwear().damage(),0,2));
    }
    public int hullDamage() { return entityData.get(HULL_DAMAGE); }
    public State state() { return State.values()[Mth.clamp(entityData.get(STATE), 0, State.values().length-1)]; }
    public void state(State next) { entityData.set(STATE, next.ordinal()); }
    /** The hat's size: its owner's growth, larger for the aquatic Marisa. */
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
        if (!player.getItemInHand(hand).isEmpty())
            return playerInteractionResult(player,hand,"cradle_requires_empty_hand",false,InteractionResult.PASS);
        if (!level().isClientSide) {
            var keeper=owner();
            player.displayClientMessage(Component.literal("接生帽：幼体 "+youngPassengers().size()+"；"
                    +(keeper==null?"帽主未在加载范围内":keeper.family().keepsCradle()?"正在接生或安置幼体":"等待帽主接回")),true);
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
        // Resting on the ground: the hat stays where it was set down.
        var fall = new Vec3(0, Math.max(-.2, getDeltaMovement().y - .04), 0); setDeltaMovement(fall); move(MoverType.SELF, fall);
        for (var p : getPassengers()) if (p instanceof YukkuriEntity rider) { rider.getNavigation().stop(); rider.fallDistance = 0; }
    }
    /** Yaw and pitch ease over the same steps as the position, so riders do not judder on each packet. */
    @Override public void lerpTo(double x,double y,double z,float yaw,float pitch,int steps) {
        lerpX=x;lerpY=y;lerpZ=z;lerpYaw=yaw;lerpPitch=pitch;lerpSteps=Math.max(1,steps);
    }
    @Override protected void addAdditionalSaveData(CompoundTag tag) {
        if(ownerYukkuri()!=null)tag.putUUID("OwnerYukkuri",ownerYukkuri());
        if(accessoryToken!=null)tag.putUUID("AccessoryToken",accessoryToken);
        tag.putString("State",state().name());tag.putFloat("HullScale",hullScale());
        tag.putString("Role",role.name());
        tag.put("Riders",riders.save());
    }
    @Override protected void readAdditionalSaveData(CompoundTag tag) {
        entityData.set(OWNER,tag.hasUUID("OwnerYukkuri")?Optional.of(tag.getUUID("OwnerYukkuri")):Optional.empty());
        accessoryToken=tag.hasUUID("AccessoryToken")?tag.getUUID("AccessoryToken"):null;
        try{state(State.valueOf(tag.getString("State")));}catch(IllegalArgumentException invalid){state(State.CRADLE);}
        entityData.set(SCALE,Math.clamp(tag.getFloat("HullScale"),.1f,2.5f));
        role="SELF".equals(tag.getString("Role"))?HatGeometry.Role.SELF:HatGeometry.Role.HELPER;
        riders.load(tag.getList("Riders",Tag.TAG_COMPOUND));
    }
}
