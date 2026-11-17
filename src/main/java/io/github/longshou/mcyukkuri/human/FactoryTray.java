package io.github.longshou.mcyukkuri.human;

import io.github.longshou.mcyukkuri.random.ModRandom;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.attributes.*;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.damagesource.DamageSource;

/** Old trays still load and put down their saved passengers. */
public final class FactoryTray extends PathfinderMob {
    public FactoryTray(EntityType<? extends FactoryTray> type, Level level) {
        super(type, level); ModRandom.construct(this); setPersistenceRequired();
    }
    public static AttributeSupplier.Builder attributes() {
        return Mob.createMobAttributes().add(Attributes.MAX_HEALTH, 20).add(Attributes.MOVEMENT_SPEED, .24)
                .add(Attributes.FOLLOW_RANGE, 40).add(Attributes.STEP_HEIGHT, .6);
    }
    @Override protected boolean canAddPassenger(Entity e) { return e instanceof YukkuriEntity && getPassengers().isEmpty(); }
    @Override public LivingEntity getControllingPassenger() { return null; }
    @Override protected Vec3 getPassengerAttachmentPoint(Entity rider, EntityDimensions dimensions, float scale) { return new Vec3(0, .25, 0); }
    @Override public boolean isPushable() { return false; }
    @Override public boolean removeWhenFarAway(double distance) { return false; }
    @Override public void aiStep() {
        super.aiStep();
        if (level() instanceof ServerLevel && isVehicle()) unload();
    }
    public void unload() {
        var passenger = getFirstPassenger();
        if (passenger == null) return;
        ejectPassengers();
        GameplayLog.event(this, "factory", "action=legacy_tray_unload passenger={}", passenger.getUUID());
    }
    @Override public void die(DamageSource source) {
        unload();
        super.die(source);
    }
}
