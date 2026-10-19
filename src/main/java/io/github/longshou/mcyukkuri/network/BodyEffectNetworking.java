package io.github.longshou.mcyukkuri.network;

import java.util.Objects;
import java.util.function.Consumer;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/** Common transport for visual-only body effects, kept separate from report transfer. */
public final class BodyEffectNetworking {
    private static volatile Consumer<BodyEffectPayload> clientReceiver = payload -> {
        throw new IllegalStateException("Body effect client receiver not installed");
    };

    public static void register(RegisterPayloadHandlersEvent event) {
        event.registrar("1").playToClient(BodyEffectPayload.TYPE, BodyEffectPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> clientReceiver.accept(payload)));
    }

    public static void installClientReceiver(Consumer<BodyEffectPayload> receiver) {
        clientReceiver = Objects.requireNonNull(receiver);
    }

    /** Sends only to nearby observers; an absent observer cannot change a settled server result. */
    public static void send(ServerLevel level, BodyEffectPayload payload, Vec3 outlet) {
        for (var player : level.players()) if (player.distanceToSqr(outlet) <= 64 * 64)
            player.connection.send(new ClientboundCustomPayloadPacket(payload));
    }

    private BodyEffectNetworking() {}
}
