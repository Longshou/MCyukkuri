package io.github.longshou.mcyukkuri.client;

import io.github.longshou.mcyukkuri.MCYukkuri;
import io.github.longshou.mcyukkuri.registry.ModEntities;
import io.github.longshou.mcyukkuri.registry.ModBlocks;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;

@EventBusSubscriber(modid = MCYukkuri.MOD_ID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class ClientEvents {
    @SubscribeEvent
    public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        for (var variant : io.github.longshou.mcyukkuri.entity.YukkuriVariant.values())
            event.registerEntityRenderer(ModEntities.forVariant(variant), YukkuriRenderer::new);
        event.registerEntityRenderer(net.minecraft.world.entity.EntityType.ITEM,HouseScrapRenderer::new);
        event.registerEntityRenderer(ModEntities.MATERIAL_DROP.get(),MaterialDropRenderer::new);
        event.registerEntityRenderer(ModEntities.REMAINS.get(),RemainsRenderer::new);
        event.registerBlockEntityRenderer(ModBlocks.CARE.get(), CareRenderer::new);
        event.registerBlockEntityRenderer(io.github.longshou.mcyukkuri.play.Playgrounds.ENTITY.get(),PlaygroundRenderer::new);
        event.registerBlockEntityRenderer(ModBlocks.SHELTER_ENTITY.get(), ShelterRenderer::new);
        event.registerBlockEntityRenderer(ModBlocks.HOUSE_NEST_ENTITY.get(),HouseNestRenderer::new);
        event.registerBlockEntityRenderer(ModBlocks.HOUSE_SUPPLIES_ENTITY.get(),SupplyPileRenderer::new);
        event.registerBlockEntityRenderer(ModBlocks.GRAVE_ENTITY.get(),GraveRenderer::new);
        event.registerBlockEntityRenderer(ModBlocks.DETACHED_STEM_ENTITY.get(),DetachedStemRenderer::new);
    }

    @SubscribeEvent
    public static void registerReloadListeners(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener(YukkuriModel.RELOAD);
        event.registerReloadListener(PropModels.RELOAD);
    }

    @SubscribeEvent
    public static void registerItemExtensions(net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent event) {
        var extension=new net.neoforged.neoforge.client.extensions.common.IClientItemExtensions(){
            private PropItemRenderer renderer;
            @Override public net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer getCustomRenderer(){if(renderer==null)renderer=new PropItemRenderer();return renderer;}
        };
        for(String name:new String[]{"food_bowl","yukkuri_nest","yukkuri_toilet","yukkuri_carrier","wash_cloth","teaching_whistle","yarn_ball","companion_cord","food_scrap","yukkuri_waste","leaked_filling","reimu_ribbon","marisa_hat","chen_hat","alice_headband","patchouli_bonnet","remilia_bonnet","flandre_bonnet","predator_nest","oil_grain_budget","oil_grain_regular","oil_grain_premium","orange_juice","mold_remover"})
            event.registerItem(extension,net.minecraft.core.registries.BuiltInRegistries.ITEM.get(MCYukkuri.id(name)));
        io.github.longshou.mcyukkuri.registry.ModItems.OPERATION_TOOLS.values().forEach(item->event.registerItem(extension,item.get()));
        event.registerItem(extension,io.github.longshou.mcyukkuri.registry.ModItems.TRIMMED_HAIR.get());
        event.registerItem(extension,io.github.longshou.mcyukkuri.registry.ModItems.REMAINS.get());
        event.registerItem(extension,io.github.longshou.mcyukkuri.registry.ModItems.YUKKURI_STEM.get());
        event.registerItem(extension,io.github.longshou.mcyukkuri.registry.ModItems.NEST_LINT.get());
        event.registerItem(extension,io.github.longshou.mcyukkuri.registry.ModItems.HOUSE_EGG.get());
        io.github.longshou.mcyukkuri.life.Facilities.ITEMS.values().forEach(item->event.registerItem(extension,item.get()));
        io.github.longshou.mcyukkuri.play.Playgrounds.ITEMS.values().forEach(item->event.registerItem(extension,item.get()));
        io.github.longshou.mcyukkuri.life.Consumables.ITEMS.values().forEach(item->event.registerItem(extension,item.get()));
        event.registerItem(extension,io.github.longshou.mcyukkuri.registry.ModItems.FOREST_BURROW.get());
        event.registerItem(extension,io.github.longshou.mcyukkuri.registry.ModItems.CARDBOARD_SHELTER.get());
        event.registerItem(extension,io.github.longshou.mcyukkuri.registry.ModItems.WILD_LATRINE.get());
    }

    @SubscribeEvent public static void registerScreens(net.neoforged.neoforge.client.event.RegisterMenuScreensEvent event){
        event.register(io.github.longshou.mcyukkuri.registry.ModMenus.CARE.get(),CareScreen::new);
    }

    private ClientEvents() {}
}
