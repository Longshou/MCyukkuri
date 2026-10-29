package io.github.longshou.mcyukkuri.headwear;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.motor.geom.Disc;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * How a set-down hat behaves where it lies (refactor WP4a). Each tick the hat runs the behaviour of the first registered
 * {@link Provider} that applies to it, otherwise {@link #LAND}. A behaviour moves only the hat and writes its pose through
 * {@link PlacedHatEntity#pose}; the base never names who registered it (an add-on does, through the registration entry).
 * Each hat keeps one instance per provider, so its motion state survives a change of behaviour and a reload.
 */
@FunctionalInterface
public interface PlacedHatBehavior {
    /** Server tick: move the hull, separate what overlaps it (its one contact pass) and write its pose. */
    void tick(PlacedHatEntity hat);

    /** Whether bodies and players may push it (vanilla {@code isPushable}). */
    default boolean pushable(PlacedHatEntity hat) { return false; }

    /** A player's click with an empty hand or with something not stored: true when it rocked the hat (both sides answer;
     *  only the server moves it). */
    default boolean shake(PlacedHatEntity hat, Player by) { return false; }

    /** Puts {@code stack} into the hollow for {@code by} (a player's click; null: a scenario's starting stock): true when it is
     *  something this hat stores, taken from the stack or refused with its real reason; false leaves the click to the shake. */
    default boolean store(PlacedHatEntity hat, ItemStack stack, Player by) { return false; }

    /** What lies on the hollow's floor besides riders, as circles (hat units) no foot may overlap; they never move. */
    default List<Disc> obstacles(PlacedHatEntity hat) { return List.of(); }

    /** What it carries besides riders, in the ability's load units (spec 8.1 L): its riders' pace. */
    default double load(PlacedHatEntity hat) { return 0; }

    /** What it keeps belongs to the hat: when the hat leaves this behaviour, is taken back or is destroyed, it falls out where
     *  it lies as real items ({@code cause} says which). A chunk unload saves it instead. */
    default void release(PlacedHatEntity hat, String cause) {}

    /** {@code SurfaceHost.right}, {@code thrust} and {@code holds}: a hat lying here cannot be righted or paddled. */
    default boolean right(PlacedHatEntity hat, YukkuriEntity by, double progress, String cause) { return false; }
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

    /** The hull's physical readout ({@code hat.draft}). */
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
        /** A hat set down at {@code at} would be carried by this behaviour (a water surface for one that floats). */
        default boolean carries(Level level, Vec3 at) { return false; }
    }

    List<Provider> PROVIDERS = new CopyOnWriteArrayList<>();

    static void register(Provider provider) { PROVIDERS.add(provider); }

    /** Some registered behaviour would carry a hat set down at {@code at} (HatProp's water placement asks this). */
    static boolean carried(Level level, Vec3 at) {
        for (var provider : PROVIDERS) if (provider.carries(level, at)) return true;
        return false;
    }

    /** Resting on the ground: vanilla gravity and block collision, never pushed, its pose flat (or flat upside down). */
    PlacedHatBehavior LAND = hat -> {
        var fall = new Vec3(0, Math.max(-.2, hat.getDeltaMovement().y - .04), 0);
        hat.setDeltaMovement(fall);
        hat.move(MoverType.SELF, fall);
        hat.pose(hat.upsideDown() ? 180 : 0, 0);
    };
}
