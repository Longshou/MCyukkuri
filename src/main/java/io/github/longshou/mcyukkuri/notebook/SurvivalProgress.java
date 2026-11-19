package io.github.longshou.mcyukkuri.notebook;

import io.github.longshou.mcyukkuri.MCYukkuri;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

/** Called only after a real successful action; vanilla persists criterion completion once. */
public final class SurvivalProgress {
    public static boolean award(Player player, String event) {
        if (!(player instanceof ServerPlayer server)) return false;
        var advancement = server.server.getAdvancements().get(MCYukkuri.id("survival/" + event));
        return advancement != null && server.getAdvancements().award(advancement, "performed");
    }
    private SurvivalProgress() {}
}
