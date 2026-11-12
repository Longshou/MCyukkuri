package io.github.longshou.mcyukkuri.network;

import io.github.longshou.mcyukkuri.MCYukkuri;
import io.github.longshou.mcyukkuri.shop.ShopMenu;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

public record ShopRowPayload(int menuId, int button, int entityId) implements CustomPacketPayload {
    private static final Type<ShopRowPayload> TYPE = new Type<>(MCYukkuri.id("shop_row"));
    private static final StreamCodec<RegistryFriendlyByteBuf, ShopRowPayload> CODEC = StreamCodec.of(
            (buffer, payload) -> {
                buffer.writeVarInt(payload.menuId());
                buffer.writeVarInt(payload.button());
                buffer.writeVarInt(payload.entityId());
            }, buffer -> new ShopRowPayload(buffer.readVarInt(), buffer.readVarInt(), buffer.readVarInt()));

    public static void register(RegisterPayloadHandlersEvent event) {
        event.registrar("1").playToServer(TYPE, CODEC, (payload, context) -> context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player && player.containerMenu instanceof ShopMenu menu
                    && menu.containerId == payload.menuId()) menu.clickLive(player, payload.button(), payload.entityId());
        }));
    }

    @Override public Type<ShopRowPayload> type() { return TYPE; }
}
