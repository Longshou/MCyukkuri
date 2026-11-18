package io.github.longshou.mcyukkuri.network;

import io.github.longshou.mcyukkuri.MCYukkuri;
import java.util.function.Consumer;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

public record ShopInfoPayload(int menuId, int entityId, int species, int stage, boolean oni, int requiredTier, int[] hints)
        implements CustomPacketPayload {
    private static final Type<ShopInfoPayload> TYPE = new Type<>(MCYukkuri.id("shop_info"));
    private static final StreamCodec<RegistryFriendlyByteBuf, ShopInfoPayload> CODEC = StreamCodec.of((buffer, payload) -> {
        buffer.writeVarInt(payload.menuId());
        buffer.writeVarInt(payload.entityId());
        buffer.writeVarInt(payload.species());
        buffer.writeVarInt(payload.stage());
        buffer.writeBoolean(payload.oni());
        buffer.writeVarInt(payload.requiredTier());
        buffer.writeVarIntArray(payload.hints());
    }, buffer -> new ShopInfoPayload(buffer.readVarInt(), buffer.readVarInt(), buffer.readVarInt(),
            buffer.readVarInt(), buffer.readBoolean(), buffer.readVarInt(), buffer.readVarIntArray(4)));

    private static Consumer<ShopInfoPayload> clientReceiver;

    public static void register(RegisterPayloadHandlersEvent event) {
        event.registrar("1").playToClient(TYPE, CODEC,
                (payload, context) -> context.enqueueWork(() -> clientReceiver.accept(payload)));
    }

    public static void installClientReceiver(Consumer<ShopInfoPayload> receiver) { clientReceiver = receiver; }

    @Override public Type<ShopInfoPayload> type() { return TYPE; }
}
