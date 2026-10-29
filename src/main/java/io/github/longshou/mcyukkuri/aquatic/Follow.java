package io.github.longshou.mcyukkuri.aquatic;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.headwear.PlacedHatEntity;
import io.github.longshou.mcyukkuri.intent.Intent;
import io.github.longshou.mcyukkuri.intent.Method;
import io.github.longshou.mcyukkuri.intent.Step;
import io.github.longshou.mcyukkuri.motor.action.Walk;
import io.github.longshou.mcyukkuri.motor.surface.Occupancy;
import java.util.List;
import net.minecraft.world.phys.Vec3;
import java.util.function.Supplier;

/**
 * A young going where its family's hat goes (refactor WP6a): {@code board_parent} hops from the bank into the hollow of its
 * parent's hat (walking first to the bank edge nearest it), {@code follow_ashore} hops off it onto the nearest dry stand in
 * reach, {@code nestle} walks into the hollow at dusk or in rain, toward its parent when she is aboard (the free spot nearest her,
 * so she leans over it). Only a young that can hop boards or leaves by itself; the others
 * are carried ({@link Ferry}).
 */
final class Follow implements Intent {
    private static final int DEADLINE = 400;

    private enum Kind { BOARD, ASHORE, NESTLE }

    private final YukkuriEntity young;
    private final PlacedHatEntity hat;
    private final Kind kind;
    private final long started;

    private Follow(YukkuriEntity young, PlacedHatEntity hat, Kind kind) {
        this.young = young;
        this.hat = hat;
        this.kind = kind;
        this.started = young.level().getGameTime();
    }

    static Follow board(YukkuriEntity young, PlacedHatEntity hat) { return new Follow(young, hat, Kind.BOARD); }
    static Follow ashore(YukkuriEntity young, PlacedHatEntity hat) { return new Follow(young, hat, Kind.ASHORE); }
    static Follow nestle(YukkuriEntity young, PlacedHatEntity hat) { return new Follow(young, hat, Kind.NESTLE); }

    @Override public String id() { return switch (kind) { case BOARD -> "board_parent"; case ASHORE -> "follow_ashore"; case NESTLE -> "nestle"; }; }
    @Override public long deadline() { return started + DEADLINE; }

    @Override public boolean satisfied() {
        return switch (kind) {
            case BOARD -> AquaticLife.aboard(young, hat);
            case ASHORE -> AquaticLife.ashore(young);
            case NESTLE -> !AquaticLife.aboard(young, hat) || Occupancy.of(young, hat.container()) == Occupancy.INSIDE_SUPPORTED;
        };
    }

    @Override public List<Method> methods() { return List.of(Plan.of(id(), this::derive)); }

    private List<Supplier<Step>> derive() {
        if (hat.isRemoved()) return null;
        return switch (kind) {
            case BOARD -> Ferry.hopAboard(young, hat);
            case ASHORE -> List.of(Ferry.hopAshore(young));
            case NESTLE -> {
                var parent = AquaticLife.parent(young);
                var spot = AquaticLife.spot(hat, young, true, parent != null && parent.getVehicle() == hat ? hat.frame().toLocal(parent.position()) : Vec3.ZERO);
                yield spot == null ? null : List.of(() -> new Step.Do(new Walk(hat, spot)));
            }
        };
    }
}
