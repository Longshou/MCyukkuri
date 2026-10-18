package io.github.longshou.mcyukkuri.network;

import io.github.longshou.mcyukkuri.MCYukkuri;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Bounded fragments of one immutable, JSON-encoded report, never a live entity or inventory. */
public record ReadoutPayload(UUID reportId, int index, int count, String fragment) implements CustomPacketPayload {
    public static final int FRAGMENT_CHARS = 8192;
    public static final int MAX_FRAGMENTS = 256;
    public static final int MAX_REPORT_CHARS = FRAGMENT_CHARS * MAX_FRAGMENTS;
    public static final Type<ReadoutPayload> TYPE = new Type<>(MCYukkuri.id("readout"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ReadoutPayload> STREAM_CODEC = StreamCodec.of(
            (buffer, payload) -> {
                buffer.writeUUID(payload.reportId());
                buffer.writeVarInt(payload.index());
                buffer.writeVarInt(payload.count());
                buffer.writeUtf(payload.fragment(), FRAGMENT_CHARS);
            },
            buffer -> new ReadoutPayload(buffer.readUUID(), buffer.readVarInt(), buffer.readVarInt(),
                    buffer.readUtf(FRAGMENT_CHARS)));

    public ReadoutPayload {
        if (reportId == null || count < 1 || count > MAX_FRAGMENTS || index < 0 || index >= count
                || fragment == null || fragment.isEmpty() || fragment.length() > FRAGMENT_CHARS)
            throw new IllegalArgumentException("Invalid readout fragment");
    }

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
