package io.github.longshou.mcyukkuri.network;

import io.github.longshou.mcyukkuri.MCYukkuri;
import io.github.longshou.mcyukkuri.command.MuseumMenuHandler;
import java.util.function.Consumer;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

public record MuseumMenuPayload(Action action, String target) implements CustomPacketPayload {
    public enum Action { JUMP_SCENE, RERUN_SCENE, RUN_UTIL, RUN_EXTRA, READ_STATE }

    private static final Type<MuseumMenuPayload> TYPE = new Type<>(MCYukkuri.id("museum_menu"));
    private static final StreamCodec<RegistryFriendlyByteBuf, MuseumMenuPayload> CODEC = StreamCodec.of(
            (buf, payload) -> { buf.writeEnum(payload.action()); buf.writeUtf(payload.target(), 80); },
            buf -> new MuseumMenuPayload(buf.readEnum(Action.class), buf.readUtf(80)));

    public static void register(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("1");
        registrar.playToServer(TYPE, CODEC, (payload, context) -> context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) MuseumMenuHandler.handle(player, payload);
        }));
        registrar.playToClient(State.TYPE, State.CODEC,
                (payload, context) -> context.enqueueWork(() -> State.receiver.accept(payload)));
    }

    @Override public Type<MuseumMenuPayload> type() { return TYPE; }

    public record State(String scene, String field) implements CustomPacketPayload {
        private static final Type<State> TYPE = new Type<>(MCYukkuri.id("museum_menu_state"));
        private static final StreamCodec<RegistryFriendlyByteBuf, State> CODEC = StreamCodec.of(
                (buf, state) -> { buf.writeUtf(state.scene(), 80); buf.writeUtf(state.field(), 80); },
                buf -> new State(buf.readUtf(80), buf.readUtf(80)));
        private static Consumer<State> receiver;

        public static void installClientReceiver(Consumer<State> consumer) { receiver = consumer; }

        @Override public Type<State> type() { return TYPE; }
    }
}
