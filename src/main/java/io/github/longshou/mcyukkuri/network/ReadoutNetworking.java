package io.github.longshou.mcyukkuri.network;

import io.github.longshou.mcyukkuri.MCYukkuri;
import java.util.ArrayList;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BiConsumer;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/** Common schema registration; the client installs its receiver without a common-to-client class reference. */
@EventBusSubscriber(modid = MCYukkuri.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
public final class ReadoutNetworking {
    private static volatile BiConsumer<ReadoutPayload, Connection> clientReceiver = (payload, connection) -> {
        throw new IllegalStateException("Readout client receiver not installed");
    };

    @SubscribeEvent public static void register(RegisterPayloadHandlersEvent event) {
        event.registrar("1").playToClient(ReadoutPayload.TYPE, ReadoutPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> clientReceiver.accept(payload, context.connection())));
    }

    public static void installClientReceiver(BiConsumer<ReadoutPayload, Connection> receiver) {
        clientReceiver = Objects.requireNonNull(receiver);
    }

    /** Returns false before sending anything if the complete report does not fit the explicit transport bound. */
    public static boolean send(ServerPlayer player, String json) {
        if (json.isEmpty() || json.length() > ReadoutPayload.MAX_REPORT_CHARS) return false;
        var fragments = new ArrayList<String>();
        for (int start = 0; start < json.length();) {
            int end = Math.min(json.length(), start + ReadoutPayload.FRAGMENT_CHARS);
            // UTF-8 encoding of either half separately would corrupt a supplementary code point.
            if (end < json.length() && Character.isHighSurrogate(json.charAt(end - 1))
                    && Character.isLowSurrogate(json.charAt(end))) end--;
            fragments.add(json.substring(start, end));
            start = end;
        }
        if (fragments.size() > ReadoutPayload.MAX_FRAGMENTS) return false;
        UUID id = UUID.randomUUID();
        for (int i = 0; i < fragments.size(); i++)
            player.connection.send(new ClientboundCustomPayloadPacket(new ReadoutPayload(id, i, fragments.size(), fragments.get(i))));
        return true;
    }

    private ReadoutNetworking() {}
}
