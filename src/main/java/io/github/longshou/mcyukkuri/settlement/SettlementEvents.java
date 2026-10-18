package io.github.longshou.mcyukkuri.settlement;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import java.util.Objects;
import net.minecraft.server.level.ServerLevel;

/** Synchronous event decisions needed by settlement logic, installed before worlds run. */
public final class SettlementEvents {
    public interface Port {
        boolean reserved(YukkuriEntity entity);
        boolean homeClaimHeld(YukkuriEntity entity);
        boolean unowned(YukkuriEntity entity);
        boolean shelterBarred(YukkuriEntity entity);
        boolean tryCollapse(ServerLevel level, ShelterBlockEntity shelter);
    }

    private static volatile Port port;

    public static synchronized void install(Port implementation) {
        Objects.requireNonNull(implementation, "Settlement event port");
        if (port != null) throw new IllegalStateException("Settlement event port already installed");
        port = implementation;
    }

    private static Port port() {
        Port installed = port;
        if (installed == null) throw new IllegalStateException("Settlement event port not installed");
        return installed;
    }

    public static boolean reserved(YukkuriEntity entity) { return port().reserved(entity); }
    public static boolean homeClaimHeld(YukkuriEntity entity) { return port().homeClaimHeld(entity); }
    public static boolean unowned(YukkuriEntity entity) { return port().unowned(entity); }
    public static boolean shelterBarred(YukkuriEntity entity) { return port().shelterBarred(entity); }
    public static boolean tryCollapse(ServerLevel level, ShelterBlockEntity shelter) { return port().tryCollapse(level, shelter); }

    private SettlementEvents() {}
}
