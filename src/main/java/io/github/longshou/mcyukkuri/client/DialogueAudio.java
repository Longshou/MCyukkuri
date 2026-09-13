package io.github.longshou.mcyukkuri.client;

import io.github.longshou.mcyukkuri.MCYukkuri;
import io.github.longshou.mcyukkuri.dialogue.NativeDialogue;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.resources.sounds.EntityBoundSoundInstance;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/** Audio follows synchronized speech sequences, never render calls. */
@EventBusSubscriber(modid=MCYukkuri.MOD_ID, value=Dist.CLIENT)
public final class DialogueAudio {
    private static final Map<UUID, Integer> SEEN = new HashMap<>();
    private static final Map<UUID, Voice> ACTIVE = new HashMap<>();
    private static ClientLevel previousLevel;
    @SubscribeEvent public static void tick(ClientTickEvent.Post event) {
        var mc = Minecraft.getInstance();
        if (mc.level != previousLevel) {
            ACTIVE.values().forEach(mc.getSoundManager()::stop);
            ACTIVE.clear(); SEEN.clear(); previousLevel = mc.level;
        }
        if (mc.level == null || mc.isPaused()) return;
        Set<UUID> present = new HashSet<>();
        for (var entity : mc.level.entitiesForRendering()) {
            if (!(entity instanceof YukkuriEntity y)) continue;
            UUID id = y.getUUID(); present.add(id);
            Integer prior = SEEN.put(id, y.speechSequence());
            if (prior != null && prior == y.speechSequence()) continue;
            Voice old = ACTIVE.remove(id);
            if (old != null) mc.getSoundManager().stop(old);
            long age = mc.level.getGameTime() - y.speechStart();
            var line = NativeDialogue.byKey(y.getDialogueKey());
            if (line == null || line.sound().isEmpty() || !y.isAlive() || y.isSilent() || age < 0 || (prior == null && age > 5)
                    || mc.level.getGameTime() >= y.speechEnd()) continue;
            Voice voice = new Voice(y, line.sound());
            ACTIVE.put(id, voice); mc.getSoundManager().play(voice);
        }
        SEEN.keySet().retainAll(present);
        ACTIVE.entrySet().removeIf(entry -> {
            if (present.contains(entry.getKey()) && !entry.getValue().expired()) return false;
            mc.getSoundManager().stop(entry.getValue()); return true;
        });
    }
    private static final class Voice extends EntityBoundSoundInstance {
        private final YukkuriEntity owner;
        private final int sequence;
        Voice(YukkuriEntity owner, String sound) {
            super(SoundEvent.createVariableRangeEvent(MCYukkuri.id(sound)), SoundSource.NEUTRAL, .75f, 1,
                    owner, owner.getRandom().nextLong());
            this.owner = owner; this.sequence = owner.speechSequence();
        }
        @Override public void tick() {
            super.tick();
            if (expired()) stop();
        }
        boolean expired() { return isStopped() || !owner.isAlive() || owner.isSilent() || owner.speechSequence()!=sequence
                || owner.level().getGameTime()>=owner.speechEnd(); }
    }
    private DialogueAudio() {}
}
