package io.github.longshou.mcyukkuri.pet.rules;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import java.util.EnumSet;
import net.minecraft.world.entity.ai.goal.Goal;

/** Calling at the yard and responding to a real invitation outrank ordinary foraging. */
public final class TrystGoal extends Goal {
    public static final int PRIORITY = 2;
    private final YukkuriEntity body;

    public TrystGoal(YukkuriEntity body) {
        this.body = body;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    private Elopement departure() { return body.pet().conscience().departure; }
    @Override public boolean canUse() { return departure().choose(); }
    @Override public boolean canContinueToUse() { return departure().continues(); }
    @Override public boolean requiresUpdateEveryTick() { return true; }
    @Override public void start() { departure().start(); }
    @Override public void tick() { departure().tick(); }
    @Override public void stop() { departure().stop(); }
}
