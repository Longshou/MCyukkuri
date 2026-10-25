package io.github.longshou.mcyukkuri.registry;

import io.github.longshou.mcyukkuri.MCYukkuri;
import io.github.longshou.mcyukkuri.freezer.FreezerModule;
import io.github.longshou.mcyukkuri.human.HumanWorld;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import io.github.longshou.mcyukkuri.life.Consumables;
import io.github.longshou.mcyukkuri.life.Facilities;
import io.github.longshou.mcyukkuri.play.Playgrounds;
import java.util.List;

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
                        output.accept(ModItems.MYON_SPAWN_EGG.get());
                        output.accept(ModItems.REMILIA_SPAWN_EGG.get()); output.accept(ModItems.FLANDRE_SPAWN_EGG.get());
                        output.accept(ModItems.PREDATOR_NEST.get());
                        output.accept(ModItems.REMILIA_BONNET.get()); output.accept(ModItems.FLANDRE_BONNET.get());
                        output.accept(ModItems.HOUSE_REIMU_SPAWN_EGG.get());
                        output.accept(ModItems.HOUSE_MARISA_SPAWN_EGG.get());
                        output.accept(ModItems.CARRIER.get());
                        output.accept(ModItems.FOOD_BOWL.get());
                        output.accept(ModItems.NEST.get());
                        output.accept(ModItems.LARGE_NEST.get());output.accept(ModItems.LARGE_WILD_NEST.get());
                        output.accept(ModItems.TOILET.get());
                        output.accept(ModItems.CARDBOARD_SHELTER.get());
                        output.accept(ModItems.FORAGE_PILE.get());
                        output.accept(ModItems.WASH_CLOTH.get());
                        output.accept(ModItems.HOUSEHOLD_GUARD.get());
                        output.accept(ModItems.TOY.get());
                        output.accept(ModItems.WHISTLE.get());
                        output.accept(ModItems.COMPANION_CORD.get());output.accept(ModItems.PET_HARNESS.get());output.accept(ModItems.SWADDLE_CLOTH.get());
                        output.accept(ModItems.YUKKURI_STEM.get());
                        output.accept(ModItems.GRAIN_BUDGET.get());output.accept(ModItems.GRAIN_REGULAR.get());output.accept(ModItems.GRAIN_PREMIUM.get());
                        output.accept(ModItems.SWEET_CANDY.get());output.accept(ModItems.CUSTARD_PUDDING.get());
                        output.accept(ModItems.ORANGE_JUICE.get());output.accept(ModItems.FLOUR_PASTE.get());output.accept(ModItems.MOLD_REMOVER.get());
                        ModItems.OPERATION_TOOLS.values().forEach(item->output.accept(item.get()));
                        Facilities.ITEMS.values().forEach(item->output.accept(item.get()));
                        Playgrounds.ITEMS.values().forEach(item->output.accept(item.get()));
                        Consumables.ITEMS.values().forEach(item->output.accept(item.get()));
                    })
                    .build());

    public static void addVanillaSpawnEggs(BuildCreativeModeTabContentsEvent event) {
        if (!event.getTabKey().equals(CreativeModeTabs.SPAWN_EGGS)) return;
        event.accept(ModItems.REIMU_SPAWN_EGG.get());
        event.accept(ModItems.MARISA_SPAWN_EGG.get());
        event.accept(ModItems.CHEN_SPAWN_EGG.get());
        event.accept(ModItems.ALICE_SPAWN_EGG.get());
        event.accept(ModItems.PATCHOULI_SPAWN_EGG.get());
        event.accept(ModItems.MYON_SPAWN_EGG.get());
        event.accept(ModItems.REMILIA_SPAWN_EGG.get()); event.accept(ModItems.FLANDRE_SPAWN_EGG.get());
        event.accept(ModItems.HOUSE_REIMU_SPAWN_EGG.get());
        event.accept(ModItems.HOUSE_MARISA_SPAWN_EGG.get());
    }

    /** Called through HumanWorld's original listener so its event registration order stays unchanged. */
    public static void addHumanContents(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey().equals(CreativeModeTabs.SPAWN_EGGS)
                || event.getTabKey().location().equals(MCYukkuri.id("yukkuri_world"))) {
            event.accept(HumanWorld.ONIISAN_EGG);
            event.accept(HumanWorld.BROTHER_EGG);
            event.accept(HumanWorld.SISTER_EGG);
            for (var item : List.of(HumanWorld.MANJU, HumanWorld.LIVE_BAG, HumanWorld.BRONZE_BADGE, HumanWorld.SILVER_BADGE,
                    HumanWorld.GOLD_BADGE, HumanWorld.BADGE_HANDBOOK)) event.accept(item.get());
            for (var block : List.of(HumanWorld.LABOR_BENCH, HumanWorld.MANJU_STEAMER,
                    HumanWorld.PACKING_TABLE, HumanWorld.NURSERY_STATION, HumanWorld.RECEIVING_TABLE,
                    HumanWorld.INSPECTION_TABLE, HumanWorld.WASHING_TABLE, HumanWorld.OBSERVATION_STATION,
                    HumanWorld.TRANSFER_TRAY)) event.accept(block.get());
            event.accept(HumanWorld.EXAM_PODIUM.get()); event.accept(HumanWorld.EXAM_HIDE_BOX.get()); event.accept(HumanWorld.EXAM_MARK.get());
        }
    }

    /** Called through FreezerModule's existing automatic MOD-bus subscriber. */
    public static void addFreezerContents(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey().equals(YUKKURI_WORLD.getKey())) event.accept(FreezerModule.ITEM.get());
    }

    private ModCreativeTabs() {}
}
