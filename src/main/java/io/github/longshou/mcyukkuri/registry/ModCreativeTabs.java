package io.github.longshou.mcyukkuri.registry;

import io.github.longshou.mcyukkuri.MCYukkuri;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModCreativeTabs {
    public static final DeferredRegister<CreativeModeTab> TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MCYukkuri.MOD_ID);

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> YUKKURI_WORLD =
            TABS.register("yukkuri_world", () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.mcyukkuri"))
                    .icon(() -> ModItems.FIELD_NOTEBOOK.get().getDefaultInstance())
                    .displayItems((parameters, output) -> {
                        output.accept(ModItems.FIELD_NOTEBOOK.get());
                        output.accept(ModItems.REIMU_SPAWN_EGG.get());
                        output.accept(ModItems.MARISA_SPAWN_EGG.get());
                        output.accept(ModItems.CHEN_SPAWN_EGG.get());
                        output.accept(ModItems.ALICE_SPAWN_EGG.get());
                        output.accept(ModItems.PATCHOULI_SPAWN_EGG.get());
                        output.accept(ModItems.REMILIA_SPAWN_EGG.get()); output.accept(ModItems.FLANDRE_SPAWN_EGG.get());
                        output.accept(ModItems.PREDATOR_NEST.get());
                        output.accept(ModItems.REMILIA_BONNET.get()); output.accept(ModItems.FLANDRE_BONNET.get());
                        output.accept(ModItems.HOUSE_REIMU_SPAWN_EGG.get());
                        output.accept(ModItems.HOUSE_MARISA_SPAWN_EGG.get());
                        output.accept(ModItems.CARRIER.get());
                        output.accept(ModItems.FOOD_BOWL.get());
                        output.accept(ModItems.NEST.get());
                        output.accept(ModItems.TOILET.get());
                        output.accept(ModItems.CARDBOARD_SHELTER.get());
                        output.accept(ModItems.WILD_LATRINE.get());
                        output.accept(ModItems.WASH_CLOTH.get());
                        output.accept(ModItems.TOY.get());
                        output.accept(ModItems.WHISTLE.get());
                        output.accept(ModItems.COMPANION_CORD.get());
                        output.accept(ModItems.HOUSE_NEST.get());output.accept(ModItems.FOOD_SCRAP.get());
                        output.accept(ModItems.YUKKURI_STEM.get());
                        output.accept(ModItems.GRAIN_BUDGET.get());output.accept(ModItems.GRAIN_REGULAR.get());output.accept(ModItems.GRAIN_PREMIUM.get());
                        output.accept(ModItems.ORANGE_JUICE.get());output.accept(ModItems.MOLD_REMOVER.get());
                        ModItems.OPERATION_TOOLS.values().forEach(item->output.accept(item.get()));
                        io.github.longshou.mcyukkuri.life.Facilities.ITEMS.values().forEach(item->output.accept(item.get()));
                        io.github.longshou.mcyukkuri.play.Playgrounds.ITEMS.values().forEach(item->output.accept(item.get()));
                        io.github.longshou.mcyukkuri.life.Consumables.ITEMS.values().forEach(item->output.accept(item.get()));
                    })
                    .build());

    public static void addVanillaSpawnEggs(BuildCreativeModeTabContentsEvent event) {
        if (!event.getTabKey().equals(CreativeModeTabs.SPAWN_EGGS)) return;
        event.accept(ModItems.REIMU_SPAWN_EGG.get());
        event.accept(ModItems.MARISA_SPAWN_EGG.get());
        event.accept(ModItems.CHEN_SPAWN_EGG.get());
        event.accept(ModItems.ALICE_SPAWN_EGG.get());
        event.accept(ModItems.PATCHOULI_SPAWN_EGG.get());
        event.accept(ModItems.REMILIA_SPAWN_EGG.get()); event.accept(ModItems.FLANDRE_SPAWN_EGG.get());
        event.accept(ModItems.HOUSE_REIMU_SPAWN_EGG.get());
        event.accept(ModItems.HOUSE_MARISA_SPAWN_EGG.get());
    }

    private ModCreativeTabs() {}
}
