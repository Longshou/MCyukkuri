package io.github.longshou.mcyukkuri.network;

import io.github.longshou.mcyukkuri.MCYukkuri;
import io.github.longshou.mcyukkuri.item.CarrierRelease;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/** The basket's release screen: the server offers the occupants, the client answers with one row. */
public final class CarrierPayload {
    private CarrierPayload() {}

    /** One occupant as the screen shows it; the name is null when the individual has no custom name. */
    public record Row(java.util.UUID token, Component name, Component species, Component stage, int health) {}

    public record Menu(boolean offhand, CarrierRelease.Target target, BlockPos pos, List<Row> rows) implements CustomPacketPayload {
        private static final Type<Menu> TYPE = new Type<>(MCYukkuri.id("carrier_menu"));
        private static final StreamCodec<RegistryFriendlyByteBuf, Menu> CODEC = StreamCodec.of(
                (buffer, payload) -> {
                    buffer.writeBoolean(payload.offhand());
                    buffer.writeEnum(payload.target());
                    buffer.writeBlockPos(payload.pos());
                    buffer.writeVarInt(payload.rows().size());
                    for (Row row : payload.rows()) {
                        buffer.writeUUID(row.token());
                        ComponentSerialization.OPTIONAL_STREAM_CODEC.encode(buffer, Optional.ofNullable(row.name()));
                        ComponentSerialization.STREAM_CODEC.encode(buffer, row.species());
                        ComponentSerialization.STREAM_CODEC.encode(buffer, row.stage());
                        buffer.writeVarInt(row.health());
                    }
                },
                buffer -> {
                    boolean offhand = buffer.readBoolean();
                    var target = buffer.readEnum(CarrierRelease.Target.class);
                    var pos = buffer.readBlockPos();
                    var rows = new ArrayList<Row>();
                    for (int size = buffer.readVarInt(); size > 0; size--) rows.add(new Row(buffer.readUUID(),
                            ComponentSerialization.OPTIONAL_STREAM_CODEC.decode(buffer).orElse(null),
                            ComponentSerialization.STREAM_CODEC.decode(buffer),
                            ComponentSerialization.STREAM_CODEC.decode(buffer), buffer.readVarInt()));
                    return new Menu(offhand, target, pos, rows);
                });

        private static Consumer<Menu> clientReceiver;

        public static void installClientReceiver(Consumer<Menu> receiver) { clientReceiver = receiver; }

        @Override public Type<Menu> type() { return TYPE; }
    }

    /** The chosen row, revalidated on the server against the basket still in that hand. */
    public record Release(boolean offhand, java.util.UUID token, CarrierRelease.Target target, BlockPos pos) implements CustomPacketPayload {
        private static final Type<Release> TYPE = new Type<>(MCYukkuri.id("carrier_release"));
        private static final StreamCodec<RegistryFriendlyByteBuf, Release> CODEC = StreamCodec.of(
                (buffer, payload) -> {
                    buffer.writeBoolean(payload.offhand());
                    buffer.writeUUID(payload.token());
                    buffer.writeEnum(payload.target());
                    buffer.writeBlockPos(payload.pos());
                },
                buffer -> new Release(buffer.readBoolean(), buffer.readUUID(),
                        buffer.readEnum(CarrierRelease.Target.class), buffer.readBlockPos()));

        @Override public Type<Release> type() { return TYPE; }
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("1");
        registrar.playToClient(Menu.TYPE, Menu.CODEC,
                (payload, context) -> context.enqueueWork(() -> Menu.clientReceiver.accept(payload)));
        registrar.playToServer(Release.TYPE, Release.CODEC, (payload, context) -> context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) CarrierRelease.choose(player,
                    payload.offhand() ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND,
                    payload.token(), payload.target(), payload.pos());
        }));
    }
}
