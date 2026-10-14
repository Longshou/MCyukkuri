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

/** One real hat and one aquatic Marisa owner, who may carry her own young born aboard. Unloading never means rescue or death. */
public final class HatBoatEntity extends Entity {
    /** CRADLE: a Marisa's hat turned over on dry ground to catch her young or let them sleep in it. Ordinals are synced: append only. */
    public enum State { AFLOAT, BEACHED, CAPSIZED, RESCUED, REMOVED, CRADLE }
    private static final EntityDataAccessor<Integer> STATE = SynchedEntityData.defineId(HatBoatEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Optional<UUID>> OWNER = SynchedEntityData.defineId(HatBoatEntity.class, EntityDataSerializers.OPTIONAL_UUID);
    private static final EntityDataAccessor<Float> SCALE = SynchedEntityData.defineId(HatBoatEntity.class, EntityDataSerializers.FLOAT);
    private UUID accessoryToken;
    private int flooding;
    private Vec3 pullTarget;
    private int pullTicks;
    private double lerpX, lerpY, lerpZ;
    private int lerpSteps;
    /** Young born into the hat (water birth); kept after a capsize so a rescue can bring them ashore. */
    private final java.util.LinkedHashSet<UUID> crew = new java.util.LinkedHashSet<>();
    public static final int MAX_YOUNG = 5;

    public HatBoatEntity(EntityType<? extends HatBoatEntity> type, Level level) { super(type, level); blocksBuilding = true; }
    @Override protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(STATE, State.AFLOAT.ordinal()); builder.define(OWNER, Optional.empty()); builder.define(SCALE, 1f);
    }
    public UUID ownerYukkuri() { return entityData.get(OWNER).orElse(null); }
    public State state() { return State.values()[Math.clamp(entityData.get(STATE), 0, State.values().length-1)]; }
    public boolean isAfloat() { return state() == State.AFLOAT; }
    public float hullScale() { return entityData.get(SCALE); }
    public int flooding() { return flooding; }
    private void state(State next) { entityData.set(STATE, next.ordinal()); }
    @Override public boolean isPickable() { return !isRemoved(); }
    public boolean isCradle() { return state() == State.CRADLE; }
    // A cradle never blocks a birth outlet or a walking parent.
    @Override public boolean canBeCollidedWith() { return !isRemoved() && state()!=State.CAPSIZED && state()!=State.RESCUED && state()!=State.CRADLE; }
    @Override protected boolean canAddPassenger(Entity passenger) {
        if (state() == State.CAPSIZED || state() == State.RESCUED || !(passenger instanceof YukkuriEntity y)) return false;
        if (y.getUUID().equals(ownerYukkuri())) return state() != State.CRADLE && getPassengers().stream().noneMatch(p -> p.getUUID().equals(ownerYukkuri()));
        return young(y) && youngAboard() < MAX_YOUNG;
    }
    /** The owner's own young (as mother or father), not yet adult. */
    public boolean young(YukkuriEntity y) {
        return ownerYukkuri() != null && (ownerYukkuri().equals(y.family().mother()) || ownerYukkuri().equals(y.family().father())) && !y.family().adult();
    }
    private int youngAboard() { return (int) getPassengers().stream().filter(p -> !p.getUUID().equals(ownerYukkuri())).count(); }
    public boolean hasRoomForYoung() { return (isAfloat() || state() == State.BEACHED || state() == State.CRADLE) && youngAboard() < MAX_YOUNG; }
    /** A newborn delivered in the hat stays aboard with its mother. */
    public boolean boardYoung(YukkuriEntity child) {
        if (level().isClientSide || !young(child) || !hasRoomForYoung()) return false;
        crew.add(child.getUUID());
        return child.startRiding(this, true);
    }
    public boolean towed() { return pullTicks > 0; }
    /** Drift toward a bank point, as when towed (an aquatic mother coming ashore to give birth). */
    public void towTo(Vec3 target, int ticks) { if (isAfloat()) { pullTarget = target; pullTicks = Math.max(pullTicks, ticks); } }
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
        if (passenger.getUUID().equals(ownerYukkuri())) { move.accept(passenger, getX(), getY() + .055, getZ()); return; }
        int seat = 0;
        for (var p : getPassengers()) { if (p == passenger) break; if (!p.getUUID().equals(ownerYukkuri())) seat++; }
        if (state() == State.CRADLE) {
            // In a cradle (drawn resting on its crown) the young sit inside, heads just over the brim.
            double angle = seat * 2.4, radius = seat == 0 ? 0 : .2 * hullScale();
            move.accept(passenger, getX() + Math.cos(angle) * radius, getY() + .24 * hullScale(), getZ() + Math.sin(angle) * radius);
            return;
        }
        // Afloat the young sit on the back of the brim, behind their mother, in an arc that follows her facing.
        float facing = getControllingPassenger() instanceof YukkuriEntity owner ? owner.yBodyRot : getYRot();
        double angle = Math.toRadians(facing + 180 + (seat - 2) * 20), radius = .74 * hullScale();
        move.accept(passenger, getX() - Math.sin(angle) * radius, getY() + .06, getZ() + Math.cos(angle) * radius);
    }
    public static HatBoatEntity find(YukkuriEntity y) {
        if (y.getVehicle() instanceof HatBoatEntity boat) return boat;
        UUID id = y.subspecies().boatId();
        return id != null && y.level() instanceof ServerLevel server && server.getEntity(id) instanceof HatBoatEntity boat ? boat : null;
    }
    public static HatBoatEntity createFor(YukkuriEntity owner, BlockPos water) {
        // Only the aquatic Marisa sails her hat; land Marisa never take to the water (user correction 2026-10-14).
        if (!(owner.level() instanceof ServerLevel level) || owner.getVariant() != YukkuriVariant.MARISA
                || owner.subspecies().kind() != SubspeciesLife.Kind.AQUATIC_MARISA || owner.family().stage() == io.github.longshou.mcyukkuri.family.FamilyLife.Stage.BABY
                || owner.subspecies().boatId() != null
                || !owner.isAlive() || owner.isPassenger() || owner.isVehicle() || !owner.life().hasAccessory() || owner.isInWaterOrBubble()
                || !level.getFluidState(owner.blockPosition()).isEmpty() || owner.operations().active()
                || owner.distanceToSqr(Vec3.atCenterOf(water)) > 9 || !level.hasChunkAt(water)) return null;
        var fluid = level.getFluidState(water);
        if (!fluid.is(FluidTags.WATER) || !level.getFluidState(water.above()).isEmpty()) return null;
        var boat = ModEntities.HAT_BOAT.get().create(level);
        if (boat == null) return null;
        boat.setPos(water.getX()+.5, water.getY()+fluid.getHeight(level, water)+.025, water.getZ()+.5);
        var away=boat.position().subtract(owner.position()).multiply(1,0,1).normalize().scale(.10);
        boat.setPos(boat.position().add(away));
        if (!level.noCollision(boat, boat.getBoundingBox())) return null;
        boat.entityData.set(OWNER, Optional.of(owner.getUUID())); boat.entityData.set(SCALE, owner.growthScale());
        if (!level.addFreshEntity(boat)) return null;
        boat.accessoryToken = owner.life().takeAccessory(null);
        if (boat.accessoryToken == null || !owner.startRiding(boat, true)) {
            if (boat.accessoryToken != null) owner.life().returnAccessory(owner.getUUID(), boat.accessoryToken, null);
            boat.discard(); return null;
        }
        owner.subspecies().boatId(boat.getUUID()); owner.setPersistenceRequired(); owner.getNavigation().stop();
        return boat;
    }
    public static HatBoatEntity tryLaunchNearby(YukkuriEntity owner) {
        for (var p : BlockPos.betweenClosed(owner.blockPosition().offset(-2,-1,-2), owner.blockPosition().offset(2,0,2))) {
            HatBoatEntity boat = createFor(owner, p); if (boat != null) return boat;
        }
        return null;
    }
    /** A Marisa turns her hat over on dry ground beside her (midwifery, or her young's bed at night). */
    public static HatBoatEntity placeCradle(YukkuriEntity owner, Vec3 at, float yaw) {
        if (!(owner.level() instanceof ServerLevel level) || owner.getVariant() != YukkuriVariant.MARISA || !owner.isAlive()
                || owner.isPassenger() || !owner.life().hasAccessory() || owner.subspecies().boatId() != null) return null;
        var spot = BlockPos.containing(at);
        if (!level.hasChunkAt(spot) || !level.getFluidState(spot).isEmpty()
                || !level.getBlockState(spot.below()).isFaceSturdy(level, spot.below(), net.minecraft.core.Direction.UP)) return null;
        var cradle = ModEntities.HAT_BOAT.get().create(level);
        if (cradle == null) return null;
        cradle.setPos(at.x, spot.getY(), at.z); cradle.setYRot(yaw);
        cradle.entityData.set(OWNER, Optional.of(owner.getUUID())); cradle.entityData.set(SCALE, owner.growthScale()); cradle.state(State.CRADLE);
        if (!level.addFreshEntity(cradle)) return null;
        cradle.accessoryToken = owner.life().takeAccessory(null);
        if (cradle.accessoryToken == null) { cradle.discard(); return null; }
        return cradle;
    }
    /** Put the hat back on: the young climb out beside it first. */
    public boolean retrieve(YukkuriEntity owner) {
        if (level().isClientSide || state() != State.CRADLE || !owner.getUUID().equals(ownerYukkuri()) || owner.distanceToSqr(this) > 16) return false;
        for (var p : java.util.List.copyOf(getPassengers())) p.stopRiding();
        crew.clear();
        if (!owner.life().returnAccessory(ownerYukkuri(), accessoryToken, null)) return false;
        state(State.RESCUED); return true;
    }
    public boolean board(YukkuriEntity owner) {
        if (level().isClientSide || !owner.getUUID().equals(ownerYukkuri()) || owner.distanceToSqr(this) > 4
                || !owner.life().matchesAccessory(ownerYukkuri(), accessoryToken)) return false;
        return owner.startRiding(this, true);
    }
    public void capsize() {
        if (level().isClientSide || state() == State.RESCUED || state() == State.REMOVED) return;
        var passengers=java.util.List.copyOf(getPassengers());
        state(State.CAPSIZED); flooding = 100; ejectPassengers(); setDeltaMovement(getDeltaMovement().scale(.25));
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
        if (!y.life().returnAccessory(ownerYukkuri(), accessoryToken, rescuer)) return false;
        y.stopRiding(); y.teleportTo(target.x,target.y,target.z); y.setDeltaMovement(Vec3.ZERO);
        landCrew(dryStand);
        y.subspecies().boatId(null); state(State.RESCUED); pullTicks = 0; return true;
    }
    /** Bring the young ashore with their mother, each on its own dry spot around the stand. */
    private void landCrew(BlockPos dryStand) {
        if (!(level() instanceof ServerLevel server)) return;
        for (var id : java.util.List.copyOf(crew)) {
            if (!(server.getEntity(id) instanceof YukkuriEntity child) || !child.isAlive() || child.distanceToSqr(this) > 64) continue;
            child.stopRiding();
            for (var spot : BlockPos.betweenClosed(dryStand.offset(-1, 0, -1), dryStand.offset(1, 1, 1))) {
                if (!level().getFluidState(spot).isEmpty()
                        || !level().getBlockState(spot.below()).isFaceSturdy(level(), spot.below(), net.minecraft.core.Direction.UP)) continue;
                var at = Vec3.atBottomCenterOf(spot);
                if (!level().noCollision(child, child.getBoundingBox().move(at.subtract(child.position())))) continue;
                child.teleportTo(at.x, at.y, at.z); child.setDeltaMovement(Vec3.ZERO); break;
            }
        }
        crew.clear();
    }
    @Override public InteractionResult interact(Player player, InteractionHand hand) {
        if (hand != InteractionHand.MAIN_HAND || !player.getItemInHand(hand).isEmpty()) return InteractionResult.PASS;
        if (level().isClientSide) return InteractionResult.SUCCESS;
        if (player.distanceToSqr(this)>36 || state() == State.CRADLE) return InteractionResult.PASS;
        if (rescue(player.blockPosition(), player.getUUID())) return InteractionResult.CONSUME;
        pullTarget = player.position(); pullTicks = 100; return InteractionResult.CONSUME;
    }
    @Override public boolean hurt(net.minecraft.world.damagesource.DamageSource source, float amount) {
        if (isInvulnerableTo(source) || level().isClientSide || amount <= 0 || state() == State.CRADLE) return false;
        capsize(); markHurt(); return true;
    }
    @Override public void tick() {
        super.tick();
        if (level().isClientSide) {
            if (lerpSteps > 0) { setPos(getX()+(lerpX-getX())/lerpSteps,getY()+(lerpY-getY())/lerpSteps,getZ()+(lerpZ-getZ())/lerpSteps); lerpSteps--; }
            return;
        }
        if (state() == State.RESCUED) { if (tickCount % 20 == 0) discard(); return; }
        if (state() == State.CRADLE) {
            // Resting on the ground; an owner who is gone for good leaves her hat where it lies.
            var fall = new Vec3(0, Math.max(-.2, getDeltaMovement().y - .04), 0); setDeltaMovement(fall); move(MoverType.SELF, fall);
            for (var p : getPassengers()) if (p instanceof YukkuriEntity rider) { rider.getNavigation().stop(); rider.fallDistance = 0; }
            var y = owner();
            if (tickCount % 20 == 0 && y != null && y.isAlive() && !y.family().keepsCradle() && y.distanceToSqr(this) <= 16 && retrieve(y)) y.family().hatBack();
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
            entityData.set(SCALE,y.growthScale());
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
        var young=new net.minecraft.nbt.ListTag();for(var id:crew)young.add(net.minecraft.nbt.NbtUtils.createUUID(id));tag.put("Crew",young);
    }
    @Override protected void readAdditionalSaveData(CompoundTag tag) {
        entityData.set(OWNER,tag.hasUUID("OwnerYukkuri")?Optional.of(tag.getUUID("OwnerYukkuri")):Optional.empty());
        accessoryToken=tag.hasUUID("AccessoryToken")?tag.getUUID("AccessoryToken"):null;
        try{state(State.valueOf(tag.getString("State")));}catch(IllegalArgumentException invalid){state(State.BEACHED);}
        flooding=Math.clamp(tag.getInt("Flooding"),0,100);entityData.set(SCALE,Math.clamp(tag.getFloat("HullScale"),.1f,1.5f));
        crew.clear();for(var raw:tag.getList("Crew",net.minecraft.nbt.Tag.TAG_INT_ARRAY))crew.add(net.minecraft.nbt.NbtUtils.loadUUID(raw));
        while(crew.size()>MAX_YOUNG)crew.remove(crew.iterator().next());
    }
}
