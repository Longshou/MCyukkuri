package io.github.longshou.mcyukkuri.entity.component;

import java.util.EnumSet;
import java.util.Objects;
import net.minecraft.world.entity.ai.goal.Goal;

/** Registered during the parent constructor, bound once after its component is ready.
 * Only goals declared by a component use this bridge; unmigrated legacy goals retain their identity,
 * flags and insertion order.
 */
public final class ComponentGoal extends Goal {
    private Goal delegate;

    public ComponentGoal(EnumSet<Flag> flags) { setFlags(flags); }

    public void bind(Goal goal) {
        if (delegate != null) throw new IllegalStateException("Goal already bound");
        Objects.requireNonNull(goal);
        if (!getFlags().equals(goal.getFlags())) throw new IllegalArgumentException("Component goal flags differ from declaration");
        delegate = Objects.requireNonNull(goal);
    }

    @Override public boolean canUse() { return delegate != null && delegate.canUse(); }
    @Override public boolean canContinueToUse() { return delegate != null && delegate.canContinueToUse(); }
    @Override public boolean isInterruptable() { return delegate == null || delegate.isInterruptable(); }
    @Override public boolean requiresUpdateEveryTick() { return delegate != null && delegate.requiresUpdateEveryTick(); }
    @Override public void start() { delegate.start(); }
    @Override public void stop() { delegate.stop(); }
    @Override public void tick() { delegate.tick(); }
    @Override public void setFlags(EnumSet<Flag> flags) {
        super.setFlags(flags);
        if (delegate != null) delegate.setFlags(flags);
    }
    @Override public EnumSet<Flag> getFlags() { return delegate == null ? super.getFlags() : delegate.getFlags(); }
    @Override public String toString() { return delegate == null ? super.toString() : delegate.toString(); }
}
