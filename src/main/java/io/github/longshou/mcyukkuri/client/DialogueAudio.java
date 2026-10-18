package io.github.longshou.mcyukkuri.client;

import io.github.longshou.mcyukkuri.dialogue.NativeDialogue;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.entity.YukkuriView;
import io.github.longshou.mcyukkuri.registry.ModSounds;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.resources.sounds.EntityBoundSoundInstance;
import net.minecraft.sounds.SoundSource;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/** Audio follows synchronized speech sequences, never render calls. */
public final class DialogueAudio {
    private static final Map<UUID, Integer> SEEN = new HashMap<>();
    private static final Map<UUID, Voice> ACTIVE = new HashMap<>();
    private static ClientLevel previousLevel;
    public static void tick(ClientTickEvent.Post event) {
        var mc = Minecraft.getInstance();
        if (mc.level != previousLevel) {
            ACTIVE.values().forEach(mc.getSoundManager()::stop);
            ACTIVE.clear(); SEEN.clear(); previousLevel = mc.level;
        }
        if (mc.level == null || mc.isPaused()) return;
        Set<UUID> present = new HashSet<>();
        for (var entity : mc.level.entitiesForRendering()) {
            if (!(entity instanceof YukkuriEntity source)) continue;
            YukkuriView y=source;
            UUID id = y.getUUID(); present.add(id);
            Integer prior = SEEN.put(id, y.speechSequence());
            if (prior != null && prior == y.speechSequence()) continue;
            Voice old = ACTIVE.remove(id);
            if (old != null) mc.getSoundManager().stop(old);
            long age = mc.level.getGameTime() - y.speechStart();
            var line = NativeDialogue.byKey(y.getDialogueKey());
            if (line == null || line.sound().isEmpty() || !y.isAlive() || y.isSilent() || age < 0 || (prior == null && age > 5)
                    || mc.level.getGameTime() >= y.speechEnd()) continue;
            Voice voice = new Voice(source, line.sound());
            ACTIVE.put(id, voice); mc.getSoundManager().play(voice);
        }
        SEEN.keySet().retainAll(present);
        ACTIVE.entrySet().removeIf(entry -> {
            if (present.contains(entry.getKey()) && !entry.getValue().expired()) return false;
            mc.getSoundManager().stop(entry.getValue()); return true;
        });
    }
    private static final class Voice extends EntityBoundSoundInstance {
        private final YukkuriView owner;
        private final int sequence;
        Voice(YukkuriEntity source, String sound) {
            super(ModSounds.event(sound), SoundSource.NEUTRAL, .75f, 1,
                    source, source.getRandom().nextLong());
            this.owner = source; this.sequence = owner.speechSequence();
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
