package io.github.longshou.mcyukkuri.aquatic;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.headwear.PlacedHatEntity;
import io.github.longshou.mcyukkuri.intent.Intent;
import io.github.longshou.mcyukkuri.intent.Method;
import io.github.longshou.mcyukkuri.intent.Step;
import java.util.List;
import java.util.function.Supplier;

/**
 * A young going where its family's hat goes: {@code board_parent} hops from the bank into the hollow of its
 * parent's hat (walking first to the bank edge nearest it), {@code follow_ashore} hops off it onto the nearest dry stand in
 * reach. Only a young that can hop boards or leaves by itself; the others
 * are carried ({@link Ferry}).
 */
final class Follow implements Intent {
    private static final int DEADLINE = 400;

    private enum Kind { BOARD, ASHORE }

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

    @Override public String id() { return switch (kind) { case BOARD -> "board_parent"; case ASHORE -> "follow_ashore"; }; }
    @Override public long deadline() { return started + DEADLINE; }

    @Override public boolean satisfied() {
        return switch (kind) {
            case BOARD -> AquaticLife.aboard(young, hat);
            case ASHORE -> AquaticLife.ashore(young);
        };
    }

    @Override public List<Method> methods() { return List.of(Plan.of(id(), this::derive)); }

    private List<Supplier<Step>> derive() {
        if (hat.isRemoved()) return null;
        return switch (kind) {
            case BOARD -> Ferry.hopAboard(young, hat, null);
            case ASHORE -> List.of(Ferry.hopAshore(young));
        };
    }
}
