package io.github.longshou.mcyukkuri.client;

import io.github.longshou.mcyukkuri.MCYukkuri;
import io.github.longshou.mcyukkuri.client.aquatic.HullLarderRenderer;
import io.github.longshou.mcyukkuri.client.aquatic.HullWaterMask;
import io.github.longshou.mcyukkuri.client.capture.MuseumCapture;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.CalculatePlayerTurnEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.RenderHandEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.LevelEvent;

/** Client-only entry point; never referenced by common startup. */
@Mod(value = MCYukkuri.MOD_ID, dist = Dist.CLIENT)
public final class ClientRegistration {
    public ClientRegistration(IEventBus modBus, ModContainer container) {
        if (Boolean.getBoolean("mcyukkuri.capture")) {
            NeoForge.EVENT_BUS.addListener(RegisterClientCommandsEvent.class, MuseumCapture::commands);
            NeoForge.EVENT_BUS.addListener(RenderFrameEvent.Post.class, MuseumCapture::frame);
        }
        container.registerConfig(ModConfig.Type.CLIENT, ClientConfig.SPEC, "mcyukkuri-client.toml");
        modBus.addListener(EventPriority.NORMAL, false, EntityRenderersEvent.RegisterRenderers.class, ClientEvents::registerRenderers);
        modBus.addListener(EventPriority.NORMAL, false, EntityRenderersEvent.RegisterRenderers.class, PlacedHatRenderer::register);
        modBus.addListener(EventPriority.NORMAL, false, EntityRenderersEvent.RegisterRenderers.class, HumanRenderer.Events::renderers);

        modBus.addListener(EventPriority.NORMAL, false, RegisterClientReloadListenersEvent.class, ClientEvents::registerReloadListeners);

        modBus.addListener(EventPriority.NORMAL, false, RegisterClientExtensionsEvent.class, ClientEvents::registerItemExtensions);
        modBus.addListener(EventPriority.NORMAL, false, RegisterClientExtensionsEvent.class, PlacedHatRenderer::items);

        modBus.addListener(EventPriority.NORMAL, false, RegisterMenuScreensEvent.class, ClientEvents::registerScreens);
        modBus.addListener(EventPriority.NORMAL, false, RegisterMenuScreensEvent.class, HumanRenderer.Events::screens);

        modBus.addListener(EventPriority.NORMAL, false, EntityRenderersEvent.RegisterLayerDefinitions.class, HumanRenderer.Events::layers);

        modBus.addListener(EventPriority.NORMAL, false, FMLClientSetupEvent.class, ReadoutClientEvents::setup);
        modBus.addListener(EventPriority.NORMAL, false, FMLClientSetupEvent.class, BodyEffectClientEvents::setup);
        modBus.addListener(EventPriority.NORMAL, false, FMLClientSetupEvent.class, ClientEvents::registerItemProperties);

        modBus.addListener(EventPriority.NORMAL, false, RegisterGuiLayersEvent.class, MagnifierClient::registerHud);

        NeoForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, ViewportEvent.ComputeFov.class, MagnifierClient::fov);
        NeoForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, CalculatePlayerTurnEvent.class, MagnifierClient::mouse);
        NeoForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, RenderHandEvent.class, MagnifierClient::renderHand);
        NeoForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, ClientTickEvent.Post.class, MagnifierClient::tick);
        NeoForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, ClientTickEvent.Post.class, DialogueAudio::tick);
        NeoForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, ClientTickEvent.Post.class, EcologyAudio::tick);
        NeoForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, ClientTickEvent.Post.class, BodyEffectsRenderer::tick);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, true, InputEvent.InteractionKeyMappingTriggered.class, HatInteractionTrace::trace);

        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, false, RenderLevelStageEvent.class, WoundLod::stage);
        NeoForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, RenderLevelStageEvent.class, BodyEffectsRenderer::renderStage);
        // The aquatic add-on's client part: the water kept out of a floating hat.
        NeoForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, RenderLevelStageEvent.class, HullWaterMask::renderStage);
        // ... and the food lying in its hollow, drawn with the hat.
        PlacedHatRenderer.layer(HullLarderRenderer::render);

        NeoForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, LevelEvent.Unload.class, BodyEffectsRenderer::unload);
    }
}
