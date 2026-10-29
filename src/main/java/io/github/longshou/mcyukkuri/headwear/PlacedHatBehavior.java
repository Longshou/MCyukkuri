package io.github.longshou.mcyukkuri.headwear;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.player.Player;
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

    /** A player's empty-hand click: true when it rocked the hat (both sides answer; only the server moves it). */
    default boolean shake(PlacedHatEntity hat, Player by) { return false; }

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
    }

    List<Provider> PROVIDERS = new CopyOnWriteArrayList<>();

    static void register(Provider provider) { PROVIDERS.add(provider); }

    /** Resting on the ground: vanilla gravity and block collision, never pushed, its pose flat (or flat upside down). */
    PlacedHatBehavior LAND = hat -> {
        var fall = new Vec3(0, Math.max(-.2, hat.getDeltaMovement().y - .04), 0);
        hat.setDeltaMovement(fall);
        hat.move(MoverType.SELF, fall);
        hat.pose(hat.upsideDown() ? 180 : 0, 0);
    };
}
