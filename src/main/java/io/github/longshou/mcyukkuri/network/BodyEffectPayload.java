package io.github.longshou.mcyukkuri.network;

import io.github.longshou.mcyukkuri.MCYukkuri;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** One already-settled, client-only body effect. It never represents an item or resource balance. */
public record BodyEffectPayload(int sourceId, String reason, float amount,
                                double x, double y, double z, float directionX, float directionY, float directionZ,
                                long startTick, float scale)
        implements CustomPacketPayload {
    public static final int MAX_REASON_CHARS = 32;
    public static final Type<BodyEffectPayload> TYPE = new Type<>(MCYukkuri.id("body_effect"));
    public static final StreamCodec<RegistryFriendlyByteBuf, BodyEffectPayload> STREAM_CODEC = StreamCodec.of(
            (buffer, payload) -> {
                buffer.writeVarInt(payload.sourceId());
                buffer.writeUtf(payload.reason(), MAX_REASON_CHARS);
                buffer.writeFloat(payload.amount());
                buffer.writeDouble(payload.x()); buffer.writeDouble(payload.y()); buffer.writeDouble(payload.z());
                buffer.writeFloat(payload.directionX()); buffer.writeFloat(payload.directionY()); buffer.writeFloat(payload.directionZ());
                buffer.writeVarLong(payload.startTick()); buffer.writeFloat(payload.scale());
            },
            buffer -> new BodyEffectPayload(buffer.readVarInt(), buffer.readUtf(MAX_REASON_CHARS), buffer.readFloat(),
                    buffer.readDouble(), buffer.readDouble(), buffer.readDouble(),
                    buffer.readFloat(), buffer.readFloat(), buffer.readFloat(), buffer.readVarLong(), buffer.readFloat()));

    public BodyEffectPayload {
        if (sourceId < 0 || reason == null || reason.isBlank() || reason.length() > MAX_REASON_CHARS
                || !reason.chars().allMatch(c -> Character.isLetterOrDigit(c) || c == '_' || c == '-' || c == '.')
                || !Float.isFinite(amount) || amount <= 0 || amount > 1024
                || !Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                || !Float.isFinite(directionX) || !Float.isFinite(directionY) || !Float.isFinite(directionZ)
                || !Float.isFinite(scale) || scale <= 0)
            throw new IllegalArgumentException("Invalid body effect payload");
    }

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
