package io.github.longshou.mcyukkuri.registry;

import io.github.longshou.mcyukkuri.MCYukkuri;
import io.github.longshou.mcyukkuri.body.WoundSurface;
import io.github.longshou.mcyukkuri.entity.YukkuriVariant;
import io.github.longshou.mcyukkuri.entity.presentation.ExpressionClips;
import io.github.longshou.mcyukkuri.family.LiveBirthGeometry;
import io.github.longshou.mcyukkuri.motor.geom.BodyFootprint;
import io.github.longshou.mcyukkuri.subspecies.SubspeciesLife;
import java.util.LinkedHashSet;
import net.neoforged.neoforge.event.server.ServerStartingEvent;

/** Reads the per-model tables (wound mesh, face spans, footprint shapes, birth poses) once off the tick thread,
 *  so no body's first tick pays for them. Every table is a keyed cache, so a tick that needs one first waits for it. */
public final class ResourceWarmup {
    private static volatile boolean started;
    private ResourceWarmup() {}

    public static void started(ServerStartingEvent event) {
        if (started) return;
        started = true;
        var thread = new Thread(ResourceWarmup::run, "mcyukkuri-warmup");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        thread.start();
    }

    private static void run() {
        long begin = System.nanoTime();
        var assets = new LinkedHashSet<String>();
        for (var variant : YukkuriVariant.values())
            for (int kind = 0; kind < 16; kind++)
                for (int shape = 0; shape < 4; shape++)
                    for (int deibu = 0; deibu < 2; deibu++)
                        assets.add(SubspeciesLife.assetId(variant, kind | shape << 4 | deibu * SubspeciesLife.DEIBU_FLAG));
        int loaded = 0;
        for (var asset : assets) {
            try {
                WoundSurface.get(asset);
                ExpressionClips.warm(asset);
                BodyFootprint.warm(asset);
                loaded++;
            } catch (RuntimeException error) {
                // An id with no art of its own (a combination no body can take) has nothing to warm.
            }
        }
        try { LiveBirthGeometry.warm(); } catch (RuntimeException | LinkageError error) {
            MCYukkuri.LOGGER.warn("[species] birth tables not warmed reason={}", error.toString());
        }
        MCYukkuri.LOGGER.info("[species] resources warmed models={} ms={}", loaded, (System.nanoTime() - begin) / 1_000_000);
    }
}
