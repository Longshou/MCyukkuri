package io.github.longshou.mcyukkuri.subspecies;

import io.github.longshou.mcyukkuri.family.FamilyLife;
import io.github.longshou.mcyukkuri.subspecies.boat.HullHelm;
import io.github.longshou.mcyukkuri.subspecies.crew.CrewState;
import io.github.longshou.mcyukkuri.subspecies.crew.DeckLayout;
import java.util.List;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
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
    /** Synchronized headwear damage of the deployed hat (0..2), drawn as on a worn hat. */
    int hullDamage();
    boolean paddling();
    HullHelm.Kind helmKind();
    HullHelm.State helmStatus();
    /** The captain's voyage phase, read-only. */
    io.github.longshou.mcyukkuri.subspecies.boat.VoyagePhase voyagePhase();
    /** Where the stick is, as the server placed it: on the hull stow, in the captain's braid, in her mouth, or not aboard. */
    enum Stick { NONE, HULL_STOW, BRAID, MOUTH }
    Stick stick();
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
    /** The deck: every body aboard with its seat id and kind, any walk, the load, a lift and the crowding display. */
    CompoundTag deckSnapshot();
    /** Synchronized load in units; the waterline rides lower with it. */
    float hullLoad();
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
            if (!stack.isEmpty() && count < HatCargo.LARDER_MAX)
                count += Math.min(stack.getCount(), HatCargo.LARDER_MAX - count);
        }
        return count;
    }
    /** How full the crown larder looks: 0 empty, 1 a little, 2 half full, 3 full. */
    default int visibleCargoTier() {
        return HatCargo.tierFor(cargoCount(cargoSnapshot()));
    }
    /** The hull's mode, to look up its deck layout. */
    default HatVessel.Mode visibleMode() {
        return isCradle() ? HatVessel.Mode.NEST : aquaticHull() ? HatVessel.Mode.AQUATIC_FLOAT : HatVessel.Mode.FLOAT;
    }
    /** How far the load sinks the hull below its empty waterline, in blocks. */
    default float visibleSink() {
        return (float) (DeckLayout.of(visibleMode()).sinkUnits(hullLoad()) * hullScale() / 16);
    }
    private CompoundTag seat(UUID rider) {
        for (var raw : deckSnapshot().getList("S", Tag.TAG_COMPOUND))
            if (raw instanceof CompoundTag tag && tag.hasUUID("Id") && rider.equals(tag.getUUID("Id"))) return tag;
        return null;
    }
    default Vec3 visibleSeat(UUID rider) {
        var tag = seat(rider);
        return tag == null ? null : new Vec3(tag.getDouble("X"), tag.getDouble("Y"), tag.getDouble("Z"));
    }
    /** The seat kind the server gave this rider (the deck's own kind, never guessed from height), or null. */
    default DeckLayout.Kind visibleSeatKind(UUID rider) {
        var tag = seat(rider);
        if (tag == null) return null;
        try { return DeckLayout.Kind.valueOf(tag.getString("Kind")); } catch (IllegalArgumentException free) { return null; }
    }
    /** The rider is walking or hopping between seats right now (its place is the entity's own, not the seat). */
    default boolean visibleSeatMoving(UUID rider) {
        var tag = seat(rider);
        return tag != null && tag.contains("At") && level().getGameTime() - tag.getLong("At") < tag.getInt("Ticks");
    }
    /** The transfers touching this hull as the server synchronized them (the one CrewState record). */
    List<CrewState.Entry> crewEntries();
    /** Crowding display of one rider (Mode 0..4, Contacts, PushX) as synchronized, or null. */
    default CompoundTag visibleCrowding(UUID rider) {
        for (var raw : deckSnapshot().getCompound("Crowding").getList("R", Tag.TAG_COMPOUND))
            if (raw instanceof CompoundTag tag && tag.hasUUID("Id") && rider.equals(tag.getUUID("Id"))) return tag;
        return null;
    }
}
