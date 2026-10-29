package io.github.longshou.mcyukkuri.headwear;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * How a set-down hat behaves where it lies (refactor WP4a). Each tick the hat runs the behaviour of the first registered
 * {@link Provider} that applies to it, otherwise {@link #LAND}. A behaviour moves only the hat and writes its pose through
 * {@link PlacedHatEntity#pose}; the base never names who registered it (an add-on does, through the registration entry).
 */
@FunctionalInterface
public interface PlacedHatBehavior {
    /** Server tick: move the hull and write its pose. */
    void tick(PlacedHatEntity hat);

    /** Whether bodies and players may push it (vanilla {@code isPushable}). */
    default boolean pushable(PlacedHatEntity hat) { return false; }

    /** An entity overlapping it (vanilla push from a body's or player's tick). Server only. */
    default void push(PlacedHatEntity hat, Entity by) {}

    /** A player's empty-hand click: true when it rocked the hat (both sides answer; only the server moves it). */
    default boolean shake(PlacedHatEntity hat, Player by) { return false; }

    /** False while it shakes hard or turns over: no jump landing may be planned onto it. */
    default boolean steady(PlacedHatEntity hat) { return true; }

    /** Set down to catch young, a cradle; a hat afloat is its owner's boat instead and she may ride it. */
    default boolean catches(PlacedHatEntity hat) { return true; }

    /** This rider is kept out of the water by the hull ({@code SurfaceHost.shelters}). */
    default boolean shelters(PlacedHatEntity hat, YukkuriEntity rider) { return false; }

    /** The hull's physical readout ({@code hat.draft}). */
    default String detail(PlacedHatEntity hat) { return "on_ground"; }

    /** A source of behaviours: whether it runs this hat now, and a fresh behaviour (its own motion state) when it takes over. */
    interface Provider {
        String id();
        boolean applies(PlacedHatEntity hat);
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
