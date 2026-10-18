package io.github.longshou.mcyukkuri.human;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.attributes.*;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.damagesource.DamageSource;

/** One physical, persistent transfer tray per line. Unloaded trays are never replaced by a duplicate. */
public final class FactoryTray extends PathfinderMob {
    private UUID owner, ticket;
    private int stalled;
    private Vec3 lastPosition = Vec3.ZERO;
    public FactoryTray(EntityType<? extends FactoryTray> type, Level level) {
        super(type, level); setPersistenceRequired();
    }
    public static AttributeSupplier.Builder attributes() {
        return Mob.createMobAttributes().add(Attributes.MAX_HEALTH, 20).add(Attributes.MOVEMENT_SPEED, .24)
                .add(Attributes.FOLLOW_RANGE, 40).add(Attributes.STEP_HEIGHT, .6);
    }
    public UUID owner() { return owner; }
    public void assign(UUID id) { owner = id; }
    public boolean blocked() { return stalled >= 200; }
    public YukkuriEntity occupant() { return getFirstPassenger() instanceof YukkuriEntity y ? y : null; }
    public boolean docked(BlockPos pos) { return pos != null && distanceToSqr(Vec3.atBottomCenterOf(pos)) < .65; }
    @Override protected boolean canAddPassenger(Entity e) { return e instanceof YukkuriEntity && getPassengers().isEmpty(); }
    @Override public LivingEntity getControllingPassenger() { return null; }
    @Override protected Vec3 getPassengerAttachmentPoint(Entity rider, EntityDimensions dimensions, float scale) { return new Vec3(0, .25, 0); }
    @Override public boolean isPushable() { return false; }
    @Override public boolean removeWhenFarAway(double distance) { return false; }
    @Override public void aiStep() {
        super.aiStep();
        if (!(level() instanceof ServerLevel level) || isNoAi()) return;
        if (owner == null || !(level.getEntity(owner) instanceof HumanNpc worker)) { getNavigation().stop(); return; }
        var flow = worker.production().pipeline();
        if (!worker.isAlive()) { ejectPassengers(); getNavigation().stop(); return; }
        if (worker.resting() || worker.isTrading() || !flow.operating()) { getNavigation().stop(); return; }
        var animal = occupant();
        if (animal == null) {
            animal = ticket != null && level.getEntity(ticket) instanceof YukkuriEntity y && flow.transportable(y) ? y : flow.nextTransport();
            if (animal == null) { ticket = null; stalled = 0; getNavigation().stop(); return; }
            ticket = animal.getUUID();
            if (distanceToSqr(animal) < 1.5 && hasLineOfSight(animal) && !animal.operations().active() && !animal.isPassenger()) {
                animal.getNavigation().stop(); animal.startRiding(this, true);
            } else { approach(animal.blockPosition()); return; }
        }
        if (!HumanCommerce.ownedBy(animal, owner) || animal.operations().active()) { unload(); return; }
        BlockPos destination = flow.destination(animal);
        if (destination == null) { getNavigation().stop(); stalled = 200; return; }
        if (docked(destination)) {
            getNavigation().stop(); stalled = 0;
            flow.arrived(animal, this);
        } else approach(destination);
    }
    private void approach(BlockPos pos) {
        // Default coordinate navigation accepts a node one block short, which cannot dock a tray.
        if (tickCount % 20 == 0) getNavigation().moveTo(getNavigation().createPath(pos,0), 1);
        if (position().distanceToSqr(lastPosition) < .0001) stalled++; else stalled = 0;
        lastPosition = position();
    }
    public void unload() { ejectPassengers(); ticket = null; getNavigation().stop(); }
    @Override public void die(DamageSource source) {
        unload();
        if (level() instanceof ServerLevel l && owner != null && l.getEntity(owner) instanceof HumanNpc n) n.production().pipeline().trayDestroyed(getUUID());
        super.die(source);
    }
    @Override public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag); if (owner != null) tag.putUUID("FactoryOwner", owner); if (ticket != null) tag.putUUID("Ticket", ticket);
    }
    @Override public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag); owner = tag.hasUUID("FactoryOwner") ? tag.getUUID("FactoryOwner") : null;
        ticket = tag.hasUUID("Ticket") ? tag.getUUID("Ticket") : null;
    }
}
