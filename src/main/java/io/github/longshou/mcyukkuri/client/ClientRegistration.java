package io.github.longshou.mcyukkuri.client;

import io.github.longshou.mcyukkuri.MCYukkuri;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.LevelEvent;

/** Client-only entry point; never referenced by common startup. */
@Mod(value = MCYukkuri.MOD_ID, dist = Dist.CLIENT)
public final class ClientRegistration {
    public ClientRegistration(IEventBus modBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.CLIENT, ClientConfig.SPEC, "mcyukkuri-client.toml");
        modBus.addListener(EventPriority.NORMAL, false, EntityRenderersEvent.RegisterRenderers.class, ClientEvents::registerRenderers);
        modBus.addListener(EventPriority.NORMAL, false, EntityRenderersEvent.RegisterRenderers.class, HatBoatRenderer::register);
        modBus.addListener(EventPriority.NORMAL, false, EntityRenderersEvent.RegisterRenderers.class, HumanRenderer.Events::renderers);

        modBus.addListener(EventPriority.NORMAL, false, RegisterClientReloadListenersEvent.class, ClientEvents::registerReloadListeners);

        modBus.addListener(EventPriority.NORMAL, false, RegisterClientExtensionsEvent.class, ClientEvents::registerItemExtensions);
        modBus.addListener(EventPriority.NORMAL, false, RegisterClientExtensionsEvent.class, HatBoatRenderer::items);

        modBus.addListener(EventPriority.NORMAL, false, RegisterMenuScreensEvent.class, ClientEvents::registerScreens);
        modBus.addListener(EventPriority.NORMAL, false, RegisterMenuScreensEvent.class, HumanRenderer.Events::screens);

        modBus.addListener(EventPriority.NORMAL, false, EntityRenderersEvent.RegisterLayerDefinitions.class, HumanRenderer.Events::layers);

        modBus.addListener(EventPriority.NORMAL, false, FMLClientSetupEvent.class, ReadoutClientEvents::setup);
        modBus.addListener(EventPriority.NORMAL, false, FMLClientSetupEvent.class, BodyEffectClientEvents::setup);
        modBus.addListener(EventPriority.NORMAL, false, FMLClientSetupEvent.class, ClientEvents::registerItemProperties);

        NeoForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, ClientTickEvent.Post.class, DialogueAudio::tick);
        NeoForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, ClientTickEvent.Post.class, EcologyAudio::tick);
        NeoForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, ClientTickEvent.Post.class, BodyEffectsRenderer::tick);

        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, false, RenderLevelStageEvent.class, WoundLod::stage);
        NeoForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, RenderLevelStageEvent.class, HatWaterMaskPass::renderStage);
        NeoForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, RenderLevelStageEvent.class, BodyEffectsRenderer::renderStage);

        NeoForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, LevelEvent.Unload.class, HatWaterMaskPass::unload);
        NeoForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, LevelEvent.Unload.class, BodyEffectsRenderer::unload);
    }
}
