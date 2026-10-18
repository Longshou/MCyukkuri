package io.github.longshou.mcyukkuri.entity.component;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.ai.goal.Goal;

/** One declaration supplies construction, independent lifecycle orders, goals and sync slots.
 * Legacy adapters use getters as factories, so they reuse the original instance and RNG timing.
 */
public record ComponentSpec<C>(ComponentKey<C> key, Initialization initialization,
        Function<YukkuriEntity, C> factory, List<Tick<C>> ticks,
        List<Save<C>> saves, List<Load<C>> loads, List<GoalSpec<C>> goals,
        List<SyncSlot<?>> syncSlots) {
    public enum Initialization { EARLY, READY }
    public enum TickPhase { BEFORE_ALIVE_CHECK, AFTER_ALIVE_CHECK }
    public record Tick<C>(TickPhase phase, int order, Consumer<C> action) {}
    public record Save<C>(int order, BiConsumer<C, CompoundTag> action) {}
    public record Load<C>(int order, BiConsumer<C, LoadContext> action) {}
    public record GoalSpec<C>(int priority, EnumSet<Goal.Flag> flags, BiFunction<C, YukkuriEntity, Goal> factory) {
        public GoalSpec { flags = flags.clone(); }
        @Override public EnumSet<Goal.Flag> flags() { return flags.clone(); }
    }

    public ComponentSpec {
        ticks = List.copyOf(ticks);
        saves = List.copyOf(saves);
        loads = List.copyOf(loads);
        goals = List.copyOf(goals);
        syncSlots = List.copyOf(syncSlots);
    }

    public static <C> Builder<C> ready(ComponentKey<C> key, Function<YukkuriEntity, C> factory) {
        return new Builder<>(key, Initialization.READY, factory);
    }

    /** EARLY factories may only use data available inside Mob.registerGoals; never variant or READY peers. */
    public static <C> Builder<C> early(ComponentKey<C> key, Function<YukkuriEntity, C> factory) {
        return new Builder<>(key, Initialization.EARLY, factory);
    }

    public static final class Builder<C> {
        private final ComponentKey<C> key;
        private final Initialization initialization;
        private final Function<YukkuriEntity, C> factory;
        private final List<Tick<C>> ticks = new ArrayList<>();
        private final List<Save<C>> saves = new ArrayList<>();
        private final List<Load<C>> loads = new ArrayList<>();
        private final List<GoalSpec<C>> goals = new ArrayList<>();
        private final List<SyncSlot<?>> slots = new ArrayList<>();

        private Builder(ComponentKey<C> key, Initialization initialization, Function<YukkuriEntity, C> factory) {
            this.key = key;
            this.initialization = initialization;
            this.factory = factory;
        }

        public Builder<C> tick(int order, Consumer<C> action) {
            return tick(TickPhase.BEFORE_ALIVE_CHECK, order, action);
        }
        public Builder<C> tick(TickPhase phase, int order, Consumer<C> action) {
            ticks.add(new Tick<>(phase, order, action)); return this;
        }
        public Builder<C> save(int order, BiConsumer<C, CompoundTag> action) {
            saves.add(new Save<>(order, action)); return this;
        }
        public Builder<C> load(int order, BiConsumer<C, LoadContext> action) {
            loads.add(new Load<>(order, action)); return this;
        }
        public Builder<C> goal(int priority, EnumSet<Goal.Flag> flags, BiFunction<C, YukkuriEntity, Goal> factory) {
            goals.add(new GoalSpec<>(priority, flags, factory)); return this;
        }
        public Builder<C> sync(SyncSlot<?> slot) { slots.add(slot); return this; }
        public ComponentSpec<C> build() {
            return new ComponentSpec<>(key, initialization, factory, ticks, saves, loads, goals, slots);
        }
    }
}
