package io.github.longshou.mcyukkuri.intent;

import io.github.longshou.mcyukkuri.motor.surface.SurfaceHost;

/** An intent that works on one {@link SurfaceHost}. The host derives {@code unloadRequested()} (and {@code attended()}) from the intent a body near it is running, so these accessors are the whole contract. */
public interface HostIntent extends Intent {
    SurfaceHost host();

    /** It asks the host's young off (unload, take back); false for one that only attends them (they gather at its side). */
    default boolean unloads() { return true; }
}
