package io.github.longshou.mcyukkuri.house;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.control.JumpControl;

/** Vanilla jump control whose pending request can be withdrawn within the same tick. */
public final class YukkuriJumpControl extends JumpControl {
    public YukkuriJumpControl(Mob mob) {
        super(mob);
    }

    public void cancel() {
        jump = false;
    }
}
