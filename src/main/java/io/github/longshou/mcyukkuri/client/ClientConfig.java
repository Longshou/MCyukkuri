package io.github.longshou.mcyukkuri.client;

import net.neoforged.neoforge.common.ModConfigSpec;

/** Client-only switches; nothing here affects server state. */
public final class ClientConfig {
    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.BooleanValue WOUND_PERF_LOG;
    static {
        var builder = new ModConfigSpec.Builder();
        builder.push("render");
        WOUND_PERF_LOG = builder.comment("Log wound rendering cost ([render], INFO, about every 5 seconds). Default off.")
                .define("woundPerfLog", false);
        builder.pop();
        SPEC = builder.build();
    }
    static boolean woundPerfLog() { return SPEC.isLoaded() && WOUND_PERF_LOG.get(); }
    private ClientConfig() {}
}
