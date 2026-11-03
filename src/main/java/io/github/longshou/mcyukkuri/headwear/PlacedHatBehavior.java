package io.github.longshou.mcyukkuri.headwear;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import io.github.longshou.mcyukkuri.motor.Motor;
import io.github.longshou.mcyukkuri.motor.surface.HostFrame;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.AABB;

/**
 * How a set-down hat behaves where it lies. Each tick the hat runs the behaviour of the first registered
 * {@link Provider} that applies to it, otherwise {@link #LAND}. A behaviour moves only the hat and writes its pose through
 * {@link PlacedHatEntity#pose}; the base never names who registered it (an add-on does, through the registration entry).
 * Each hat keeps one instance per provider, so its motion state survives a change of behaviour and a reload.
 */
@FunctionalInterface
public interface PlacedHatBehavior {
    /** Server tick: move the hull, separate what overlaps it (its one contact pass) and write its pose. */
    void tick(PlacedHatEntity hat);
    /** Undriven motion observed by actions preparing to leave or cross this surface. */
    default HostFrame frameAfter(PlacedHatEntity hat, int ticks) { return hat.frame(); }
    /** A real dry-to-water entry; vanilla excludes the first tick after creation or reload. */
    default void waterEntered(PlacedHatEntity hat, double speed) {}
    /** A real boarding, departure or body growth; loading a save does not replay these events. */
    default void loadChanged(PlacedHatEntity hat, YukkuriEntity body, String event) {}

    /** Whether this behaviour carries the real hull through water rather than lying with the land bounds. */
    default boolean afloat(PlacedHatEntity hat) { return false; }

    /** Whether bodies and players may push it (vanilla {@code isPushable}). */
    default boolean pushable(PlacedHatEntity hat) { return false; }

    /** A player's click with an empty hand or with something not stored: true when it rocked the hat (both sides answer;
     *  only the server moves it). */
    default boolean shake(PlacedHatEntity hat, Player by) { return false; }

    /** A player's hit that reached this hat's own cloth rather than a body aboard: true when the hat took the blow itself
     *  (an empty hull rocks and then drops as its item); false leaves the hit with no effect. */
    default boolean hit(PlacedHatEntity hat, Player by) { return false; }

    /** Puts {@code stack} into the hollow for {@code by} (a player's click): true when it is
     *  something this hat stores, taken from the stack or refused with its real reason; false leaves the click to the shake. */
    default boolean store(PlacedHatEntity hat, ItemStack stack, Player by) { return false; }

    /** Scene starting stock, returning the accepted count. */
    default int stock(PlacedHatEntity hat, ItemStack food, int count) { return 0; }

    /** What it keeps belongs to the hat: when the hat leaves this behaviour, is taken back or is destroyed, it falls out where
     *  it lies as real items ({@code cause} says which). A chunk unload saves it instead. */
    default void release(PlacedHatEntity hat, String cause) {}

    /** {@code SurfaceHost.right}, {@code thrust} and {@code holds}: a hat lying here cannot be righted or paddled. */
    default boolean right(PlacedHatEntity hat, YukkuriEntity by, double progress, String cause) { return false; }
    default void stopRight(PlacedHatEntity hat, YukkuriEntity by) {}
    default boolean thrust(PlacedHatEntity hat, YukkuriEntity by, Vec3 drive, float turn) { return false; }
    default boolean holds(PlacedHatEntity hat, Item item) { return false; }

    /** False while it shakes hard or turns over: no jump landing may be planned onto it. Synced facts only. */
    default boolean steady(PlacedHatEntity hat) { return true; }

    /** Set down to catch young, a cradle; a hat afloat is its owner's boat instead and she may ride it. */
    default boolean catches(PlacedHatEntity hat) { return true; }

    /** Its owner may put it back on now (PlacedHatEntity.retrieve adds reach and emptiness). */
    default boolean retrievable(PlacedHatEntity hat, YukkuriEntity owner) { return hat.catches(); }

    /** This rider is kept out of the water by the hull ({@code SurfaceHost.shelters}). */
    default boolean shelters(PlacedHatEntity hat, YukkuriEntity rider) { return false; }

    /** The hull's travel and presentation readout. */
    default String detail(PlacedHatEntity hat) { return "on_ground"; }

    /** The motion state to resume after a reload (the hat's NBT {@code Motion}); empty when there is none. */
    default CompoundTag save() { return new CompoundTag(); }

    default void load(CompoundTag tag) {}

    /** A source of behaviours: whether it runs this hat now ({@code current} is the behaviour running it, so a provider can
     *  hold on with some margin), and the hat's one instance of it (its motion state). */
    interface Provider {
        String id();
        boolean applies(PlacedHatEntity hat, PlacedHatBehavior current);
        PlacedHatBehavior create(PlacedHatEntity hat);
    }

    /** Whether water can carry a hull of this size; registered by the hull behaviour, which owns the draft rule. */
    interface Carrier { boolean floats(Level level, Vec3 at, float hullScale); }

    List<Provider> PROVIDERS = new CopyOnWriteArrayList<>();
    List<Carrier> CARRIERS = new CopyOnWriteArrayList<>();

    static void register(Provider provider) { PROVIDERS.add(provider); }

    static void carrier(Carrier rule) { CARRIERS.add(rule); }

    /** Every water placement, including flowing-water self rescue, asks the same surface check and the hull's own draft rule. */
    static boolean waterFits(Level level, Vec3 at, HullProfile form, float hullScale) {
        var spot = BlockPos.containing(at);
        if (!level.hasChunkAt(spot) || !level.getFluidState(spot).is(FluidTags.WATER)
                || !level.getFluidState(spot.above()).isEmpty()
                || !level.getBlockState(spot.above()).getCollisionShape(level, spot.above()).isEmpty()) return false;
        if (!form.seated()) return true;
        double scale = hullScale / HullBounds.SCALE, radius = HullBounds.RADIUS * scale;
        double top = spot.getY() + level.getFluidState(spot).getHeight(level, spot);
        var box = new AABB(at.x - radius, top + HullBounds.KEEL * scale, at.z - radius,
                at.x + radius, top + HullBounds.BRIM * scale, at.z + radius);
        return !level.getBlockCollisions(null, box).iterator().hasNext()
                && CARRIERS.stream().allMatch(carrier -> carrier.floats(level, at, hullScale));
    }

    /** Resting on the ground: vanilla gravity and block collision, never pushed, its pose flat (or flat upside down). */
    PlacedHatBehavior LAND = hat -> {
        var fall = new Vec3(0, hat.getDeltaMovement().y - .04, 0);
        Motor.velocity(hat, fall, "hat_gravity");
        Motor.move(hat, fall, "hat_gravity");
        Motor.hostPose(hat, hat.getYRot(), hat.upsideDown() ? 180 : 0, 0);
        // its one contact pass: nobody stays under it
        hat.clearUnder();
    };
}
