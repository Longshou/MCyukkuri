package io.github.longshou.mcyukkuri.registry;

import com.google.gson.JsonParser;
import io.github.longshou.mcyukkuri.MCYukkuri;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.registries.Registries;
import net.minecraft.sounds.SoundEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Shared sound identities; only the client sound engine resolves resource-pack definitions. */
public final class ModSounds {
    public static final DeferredRegister<SoundEvent> SOUND_EVENTS =
            DeferredRegister.create(Registries.SOUND_EVENT, MCYukkuri.MOD_ID);
    private static final Map<String, DeferredHolder<SoundEvent, SoundEvent>> EVENTS = registerPackagedSounds();

    private static Map<String, DeferredHolder<SoundEvent, SoundEvent>> registerPackagedSounds() {
        Map<String, DeferredHolder<SoundEvent, SoundEvent>> events = new LinkedHashMap<>();
        // Read the mod's own fixed event set on both physical sides, never a client resource manager.
        try (var stream = ModSounds.class.getResourceAsStream("/assets/mcyukkuri/sounds.json")) {
            if (stream == null) throw new IllegalStateException("Missing packaged sound definitions");
            try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                var definitions = JsonParser.parseReader(reader).getAsJsonObject();
                for (String path : definitions.keySet().stream().sorted().toList()) {
                    var id = MCYukkuri.id(path);
                    events.put(path, SOUND_EVENTS.register(path, () -> SoundEvent.createVariableRangeEvent(id)));
                }
            }
            if (events.isEmpty()) throw new IllegalStateException("Empty packaged sound definitions");
        } catch (Exception error) {
            throw new ExceptionInInitializerError(error);
        }
        return Collections.unmodifiableMap(events);
    }

    public static SoundEvent event(String path) {
        var sound = EVENTS.get(path);
        if (sound == null) throw new IllegalArgumentException("Unknown packaged sound event: " + path);
        return sound.get();
    }

    public static Set<String> paths() { return EVENTS.keySet(); }

    private ModSounds() {}
}
