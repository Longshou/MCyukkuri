package io.github.longshou.mcyukkuri.subspecies;

import io.github.longshou.mcyukkuri.entity.Behaviors;
import io.github.longshou.mcyukkuri.entity.YukkuriView;
import io.github.longshou.mcyukkuri.family.FamilyLife;
import io.github.longshou.mcyukkuri.subspecies.boat.HullHelm;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/** Synchronized vessel presentation. Snapshot tags are copies; no cargo or crowding component escapes. */
public interface HatBoatView {
    int getId();
    UUID getUUID();
    Level level();
    Vec3 position();
    Vec3 getPosition(float partial);
    float getYRot();
    float previousYaw();
    UUID ownerYukkuri();
    boolean aquaticHull();
    FamilyLife.Stage accessoryStage();
    HatBoatEntity.State state();
    boolean isAfloat();
    boolean isMoored();
    boolean isCradle();
    float hullScale();
    boolean paddling();
    HullHelm.Kind helmKind();
    HullHelm.State helmStatus();
    double paddleCycle(float partial);
    float currentStrength();
    boolean rocking();
    boolean capsizing();
    boolean deploying();
    boolean selfRescueHeld();
    boolean selfRighting();
    float selfRightingProgress(float partial);
    Vec3 motionPosition(float partial);
    float hullSquash(float partial);
    float dipDegrees(float partial);
    float dipYaw();
    long motionStartTick();
    float rockingAmplitude();
    CompoundTag seatsSnapshot();
    CompoundTag cargoSnapshot();
    CompoundTag motionSnapshot();

    default boolean visibleDrifting() { return isAfloat() && !paddling() && currentStrength() > .08f; }
    /** The cargo owns the actual stack even while the captain poses with it. */
    default ItemStack visiblePaddleItem() {
        var cargo=cargoSnapshot();
        if(cargo.contains("PaddleItem",Tag.TAG_COMPOUND)) {
            var stack=ItemStack.parseOptional(level().registryAccess(),cargo.getCompound("PaddleItem"));
            return stack.is(Items.STICK) ? stack.copyWithCount(1) : ItemStack.EMPTY;
        }
        return cargo.getBoolean("Paddle") ? new ItemStack(Items.STICK) : ItemStack.EMPTY;
    }
    default boolean visiblePaddle() { return !visiblePaddleItem().isEmpty(); }
    private int cargoCount(CompoundTag tag) {
        int count = 0;
        for (var raw : tag.getList("Food", Tag.TAG_COMPOUND)) {
            var stack = ItemStack.parseOptional(level().registryAccess(), (CompoundTag) raw);
            if (!stack.isEmpty() && count < HatBoatCargo.CAPACITY)
                count += Math.min(stack.getCount(), HatBoatCargo.CAPACITY - count);
        }
        return count;
    }
    private int asideCount(CompoundTag tag, int count) {
        return tag.getInt("Version") < 3 ? count : Math.clamp(tag.getInt("AsideCount"), 0, count);
    }
    default int visibleCargoTier() {
        var tag = cargoSnapshot(); int count = cargoCount(tag);
        return HatBoatCargo.tierFor(count - asideCount(tag, count));
    }
    default int visibleAsideTier() {
        var tag = cargoSnapshot(); return HatBoatCargo.tierFor(asideCount(tag, cargoCount(tag)));
    }
    private CompoundTag seat(UUID rider) {
        for (var raw : seatsSnapshot().getList("S", Tag.TAG_COMPOUND))
            if (raw instanceof CompoundTag tag && tag.hasUUID("Id") && rider.equals(tag.getUUID("Id"))) return tag;
        return null;
    }
    default Vec3 visibleSeat(UUID rider) {
        var tag = seat(rider);
        return tag == null ? null : new Vec3(tag.getDouble("X"), tag.getDouble("Y"), tag.getDouble("Z"));
    }
    default boolean visibleOnBrim(UUID rider) {
        var tag = seat(rider);
        var mode=aquaticHull()?HatVessel.Mode.AQUATIC_FLOAT:HatVessel.Mode.FLOAT;
        return isAfloat() && tag != null && Math.abs(tag.getDouble("Y") - HatVessel.freeboard(mode)) < 1e-5
                && !(tag.contains("At") && level().getGameTime() - tag.getLong("At") < tag.getInt("Ticks"));
    }
    default boolean visibleTransporting(UUID rider) {
        var trip = seatsSnapshot().getCompound("Carry");
        return trip.hasUUID("Child") && (rider.equals(trip.getUUID("Child")) || rider.equals(ownerYukkuri()));
    }
    default String visibleCarryActivity(UUID rider) {
        var trip = seatsSnapshot().getCompound("Carry");
        if (!trip.hasUUID("Child")) return Behaviors.IDLE;
        if (rider.equals(ownerYukkuri())) return Behaviors.BOAT_CARRY_YOUNG;
        if (!rider.equals(trip.getUUID("Child"))) return Behaviors.IDLE;
        int phase = trip.getInt("Phase");
        return phase == -2 ? Behaviors.HAT_WALK : phase == -1 ? Behaviors.BOAT_PREPARE_CARRY : phase == 5 ? Behaviors.BOAT_RELEASE_CARRY : Behaviors.BOAT_BE_CARRIED;
    }
    default float visibleCarrySeconds(float partial) {
        var trip = seatsSnapshot().getCompound("Carry");
        return !trip.hasUUID("Child") ? 0 : (float) Math.max(0, (level().getGameTime() - trip.getLong("At") + partial) / 20.0);
    }
    private CompoundTag balance(UUID rider) {
        for (var raw : seatsSnapshot().getCompound("Crowding").getList("R", Tag.TAG_COMPOUND)) {
            var tag = (CompoundTag) raw;
            if (tag.hasUUID("Id") && rider.equals(tag.getUUID("Id"))) return tag;
        }
        return null;
    }
    default String visibleCrowdingState(YukkuriView rider) {
        var tag = balance(rider.getUUID());
        if (rider.getUUID().equals(ownerYukkuri()))
            return seatsSnapshot().getCompound("Crowding").getBoolean("Contesting") ? Behaviors.BOAT_CROWD_GUARD : Behaviors.IDLE;
        int mode = tag == null ? 0 : Math.clamp(tag.getInt("Mode"), 0, 4);
        if (mode == 3) return Behaviors.HAT_SLIPPING;
        if (mode == 2) return Behaviors.HAT_JOSTLE;
        if (mode == 4) return Behaviors.HAT_SCRAMBLE;
        return rider.vehicleId() == getId() && (rocking() || visibleDrifting()) ? Behaviors.HAT_STUMBLE : Behaviors.IDLE;
    }
    default long visibleCrowdingPhaseStart(UUID rider) {
        if (rider.equals(ownerYukkuri())) return seatsSnapshot().getCompound("Crowding").getLong("GuardAt");
        var tag = balance(rider);
        return (tag == null ? motionStartTick() : tag.getLong("Since")) - Math.floorMod(rider.hashCode(), 13);
    }
    private double motionAge(CompoundTag motion, String key, float partial) {
        return Math.max(0, level().getGameTime() - motion.getLong(key) + partial);
    }
    default float visibleMotionRoll(float partial) {
        var m = motionSnapshot(); int side = m.getInt("Side") < 0 ? -1 : 1;
        if (selfRescueHeld()) return 0;
        if (m.contains("LooseAt")) {
            double t = Math.clamp(motionAge(m, "LooseAt", partial) / 14, 0, 1);
            return (float) (side * 110 * t * t * (3 - 2 * t));
        }
        if (deploying() || selfRighting()) {
            double t = deploying() ? Math.clamp(motionAge(m, "DeployAt", partial) / 20, 0, 1) : Math.clamp(selfRightingProgress(partial), 0, 1);
            return (float) (side * 110 * (1 - t * t * (3 - 2 * t)));
        }
        if (state() == HatBoatEntity.State.CAPSIZED) {
            double t = m.contains("CapAt") ? Math.clamp(motionAge(m, "CapAt", partial) / HatBoatEntity.CAPSIZE_TICKS, 0, 1) : 1;
            return (float) (side * 110 * t * t * (3 - 2 * t));
        }
        if (!rocking()) return visibleDrifting() ? (float) (Math.sin((level().getGameTime() + partial) * .19) * currentStrength() * 2.5) : 0;
        double age = motionAge(m, "RockAt", partial);
        return (float) (side * m.getFloat("Amplitude") * Math.exp(-age / 14) * Math.sin(age * .62));
    }
    default float visibleMotionPitch(float partial) {
        var m = motionSnapshot();
        if (selfRescueHeld()) return Mth.lerp(partial, m.getFloat("PreviousPitch"), m.getFloat("HeldPitch"));
        if (m.contains("LooseAt")) {
            double t = Math.clamp(motionAge(m, "LooseAt", partial) / 14, 0, 1);
            return (float) (m.getFloat("LoosePitch") * (1 - t * t * (3 - 2 * t)));
        }
        if (deploying() || selfRighting()) {
            double t = deploying() ? Math.clamp(motionAge(m, "DeployAt", partial) / 20, 0, 1) : Math.clamp(selfRightingProgress(partial), 0, 1);
            return (float) (7 * Math.sin(t * Math.PI));
        }
        if (state() == HatBoatEntity.State.CAPSIZED) {
            if (!m.contains("CapAt")) return 0;
            double t = Math.clamp(motionAge(m, "CapAt", partial) / HatBoatEntity.CAPSIZE_TICKS, 0, 1);
            return (float) (7 * Math.sin(t * Math.PI));
        }
        if (!rocking()) return visibleDrifting() ? (float) (Math.sin((level().getGameTime() + partial) * .13) * currentStrength()) : 0;
        double age = motionAge(m, "RockAt", partial);
        return (float) (m.getFloat("Amplitude") * .24 * Math.exp(-age / 14) * Math.sin(age * .44));
    }
    default float visibleBalanceRoll(YukkuriView rider, float partial) {
        UUID id = rider.getUUID();
        if (visibleTransporting(id)) return 0;
        if (id.equals(ownerYukkuri())) return visibleDrifting() ? -visibleMotionRoll(partial) * .7f : 0;
        var b = balance(id); double age = level().getGameTime() + partial - visibleCrowdingPhaseStart(id);
        double phase = Math.floorMod(id.hashCode(), 97) * Math.PI * 2 / 97;
        double value = rider.vehicleId() == getId() && rocking() ? -1.3 * visibleMotionRoll(partial)
                + Math.sin(age * .72 + phase) * (visibleOnBrim(id) ? 4 : 2) * rockingAmplitude() / 12 : 0;
        if (visibleDrifting()) value -= visibleMotionRoll(partial) * (visibleOnBrim(id) ? 1.3 : .8);
        if (b != null && Math.clamp(b.getInt("Mode"), 0, 4) >= 2) value += Math.copySign(Math.min(16,
                Math.clamp(b.getInt("Contacts"), 0, 200) * .7), b.getDouble("PushX") == 0 ? 1 : b.getDouble("PushX")) + Math.sin(age * .9 + phase) * 2;
        return (float) Math.clamp(value, -24, 24);
    }
    default float visibleBalancePitch(YukkuriView rider, float partial) {
        UUID id = rider.getUUID();
        if (visibleTransporting(id)) return 0;
        if (id.equals(ownerYukkuri())) return visibleDrifting() ? -visibleMotionPitch(partial) * .7f : 0;
        var b = balance(id); double age = level().getGameTime() + partial - visibleCrowdingPhaseStart(id);
        double phase = Math.floorMod(id.hashCode(), 71) * Math.PI * 2 / 71;
        double value = rider.vehicleId() == getId() && rocking() ? -visibleMotionPitch(partial) + Math.sin(age * .65 + phase) * 2 : 0;
        if (visibleDrifting()) value -= visibleMotionPitch(partial);
        if (b != null && Math.clamp(b.getInt("Mode"), 0, 4) >= 2) value += -4 + Math.sin(age * .8 + phase) * 2;
        return (float) Math.clamp(value, -12, 12);
    }
}
