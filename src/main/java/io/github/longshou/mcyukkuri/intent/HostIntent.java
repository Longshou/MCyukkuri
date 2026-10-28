package io.github.longshou.mcyukkuri.intent;

import io.github.longshou.mcyukkuri.motor.surface.SurfaceHost;

/**
 * An intent that works on one {@link SurfaceHost}. The host derives {@code unloadRequested()} from the intent the owner
 * is running (spec 6), so this one accessor is the whole contract; nothing stores "unloading" as a flag.
 */
public interface HostIntent extends Intent {
    SurfaceHost host();
}
