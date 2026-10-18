package io.github.longshou.mcyukkuri.event;

import io.github.longshou.mcyukkuri.MCYukkuri;
import io.github.longshou.mcyukkuri.coordination.LeaseTable;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

/** Derived admission index. Both SavedData records remain authoritative for progress and persistence. */
public final class EventReservations {
    enum Source { HOME, WILD }
    record Token(Source source, UUID id) {}
    private static final Map<MinecraftServer, LeaseTable<UUID, Token>> SERVERS = new IdentityHashMap<>();

    private static LeaseTable<UUID, Token> table(MinecraftServer server) {
        var existing = SERVERS.get(server);
        if (existing != null) return existing;
        // get/load only binds the server; neither loader calls this index. Publish after both are loaded.
        var homes = HomeClaimData.get(server.overworld());
        var wild = WildEventData.get(server.overworld());
        var rebuilt = new LeaseTable<UUID, Token>();
        for (var claim : homes.active())
            for (var member : claim.members) rebuilt.restore(member, new Token(Source.HOME, claim.id), LeaseTable.INDEFINITE);
        for (var scene : wild.scenes()) if (scene.active())
            for (var member : scene.members) rebuilt.restore(member, new Token(Source.WILD, scene.id), LeaseTable.INDEFINITE);
        var members = new java.util.HashSet<UUID>();
        homes.active().forEach(c -> members.addAll(c.members));
        wild.scenes().stream().filter(WildEventData.Scene::active).forEach(s -> members.addAll(s.members));
        long conflicts = members.stream().filter(id -> rebuilt.owners(id, 0).size() > 1).count();
        if (conflicts > 0) MCYukkuri.LOGGER.warn("Retaining {} legacy event reservation conflicts; new admission is blocked", conflicts);
        SERVERS.put(server, rebuilt);
        return rebuilt;
    }

    static Set<Token> owners(MinecraftServer server, UUID member) { return table(server).owners(member, server.overworld().getGameTime()); }
    static boolean free(MinecraftServer server, List<UUID> members) { return table(server).free(members, server.overworld().getGameTime()); }
    static boolean acquire(MinecraftServer server, List<UUID> members, Token token) {
        var table = table(server); long now = server.overworld().getGameTime();
        return table.free(members, now) && table.acquireAll(members, token, LeaseTable.INDEFINITE, now);
    }
    static void release(MinecraftServer server, List<UUID> members, Token token) { table(server).releaseAll(members, token); }

    public static void stopped(ServerStoppedEvent event) { SERVERS.remove(event.getServer()); }
    private EventReservations() {}
}
