package io.github.longshou.mcyukkuri.client;

import io.github.longshou.mcyukkuri.MCYukkuri;
import io.github.longshou.mcyukkuri.entity.YukkuriVariant;
import io.github.longshou.mcyukkuri.life.Consumables;
import io.github.longshou.mcyukkuri.life.Facilities;
import io.github.longshou.mcyukkuri.play.Playgrounds;
import io.github.longshou.mcyukkuri.registry.ModBlocks;
import io.github.longshou.mcyukkuri.registry.ModEntities;
import io.github.longshou.mcyukkuri.registry.ModItems;
import io.github.longshou.mcyukkuri.registry.ModMenus;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.EntityType;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions;
import net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent;

public final class ClientEvents {

    public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        for (var variant : YukkuriVariant.values())
            event.registerEntityRenderer(ModEntities.forVariant(variant), YukkuriRenderer::new);
        event.registerEntityRenderer(EntityType.ITEM,HouseScrapRenderer::new);
        event.registerEntityRenderer(ModEntities.MATERIAL_DROP.get(),MaterialDropRenderer::new);
        event.registerEntityRenderer(ModEntities.REMAINS.get(),RemainsRenderer::new);
        event.registerBlockEntityRenderer(ModBlocks.CARE.get(), CareRenderer::new);
        event.registerBlockEntityRenderer(Playgrounds.ENTITY.get(),PlaygroundRenderer::new);
        event.registerBlockEntityRenderer(ModBlocks.SHELTER_ENTITY.get(), ShelterRenderer::new);
        event.registerBlockEntityRenderer(ModBlocks.FORAGE_PILE_ENTITY.get(), ForagePileRenderer::new);
        event.registerBlockEntityRenderer(ModBlocks.HOUSE_NEST_ENTITY.get(),HouseNestRenderer::new);
        event.registerBlockEntityRenderer(ModBlocks.HOUSE_SUPPLIES_ENTITY.get(),SupplyPileRenderer::new);
        event.registerBlockEntityRenderer(ModBlocks.GRAVE_ENTITY.get(),GraveRenderer::new);
        event.registerBlockEntityRenderer(ModBlocks.DETACHED_STEM_ENTITY.get(),DetachedStemRenderer::new);
    }


    public static void registerReloadListeners(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener(YukkuriModel.RELOAD);
        event.registerReloadListener(AgitationPresentation.RELOAD);
        event.registerReloadListener(PropModels.RELOAD);
        event.registerReloadListener(HatDamageMask.RELOAD);
    }


    public static void registerItemExtensions(RegisterClientExtensionsEvent event) {
        var extension=new IClientItemExtensions(){
            private PropItemRenderer renderer;
            @Override public BlockEntityWithoutLevelRenderer getCustomRenderer(){if(renderer==null)renderer=new PropItemRenderer();return renderer;}
        };
        for(String name:new String[]{"food_bowl","yukkuri_nest","yukkuri_toilet","yukkuri_carrier","wash_cloth","teaching_whistle","yarn_ball",
                "companion_cord","food_scrap","yukkuri_waste","leaked_filling","core_filling","reimu_ribbon","marisa_hat","chen_hat","alice_headband",
                "myon_hairband","marisa_kotatsu","patchouli_bonnet","remilia_bonnet","flandre_bonnet","predator_nest","oil_grain_budget",
                "oil_grain_regular","oil_grain_premium","orange_juice","flour_paste","mold_remover","forage_pile"})
            event.registerItem(extension,BuiltInRegistries.ITEM.get(MCYukkuri.id(name)));
        ModItems.OPERATION_TOOLS.values().forEach(item->event.registerItem(extension,item.get()));
        event.registerItem(extension,ModItems.TRIMMED_HAIR.get());
        event.registerItem(extension,ModItems.ACCESSORY_SHARD.get());
        event.registerItem(extension,ModItems.REMAINS.get());
        event.registerItem(extension,ModItems.YUKKURI_STEM.get());
        event.registerItem(extension,ModItems.NEST_LINT.get());
        event.registerItem(extension,ModItems.HOUSE_EGG.get());
        Facilities.ITEMS.values().forEach(item->event.registerItem(extension,item.get()));
        Playgrounds.ITEMS.values().forEach(item->event.registerItem(extension,item.get()));
        Consumables.ITEMS.values().forEach(item->event.registerItem(extension,item.get()));
        event.registerItem(extension,ModItems.FOREST_BURROW.get());
        event.registerItem(extension,ModItems.CARDBOARD_SHELTER.get());
        event.registerItem(extension,ModItems.WILD_LATRINE.get());
    }

    public static void registerScreens(RegisterMenuScreensEvent event){
        event.register(ModMenus.CARE.get(),CareScreen::new);
    }

    private ClientEvents() {}
}
