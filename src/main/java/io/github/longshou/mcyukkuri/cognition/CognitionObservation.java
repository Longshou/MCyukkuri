package io.github.longshou.mcyukkuri.cognition;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import net.minecraft.network.chat.Component;

public final class CognitionObservation {
    private CognitionObservation() {}
    public static Component page(YukkuriEntity entity) {
        var value = Cognition.get(entity);
        return Component.translatable("notebook.mcyukkuri.cognition", value.innate(), value.learned(), value.ability());
    }
}
