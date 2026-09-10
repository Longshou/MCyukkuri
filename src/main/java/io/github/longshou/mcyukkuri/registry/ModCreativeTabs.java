package io.github.longshou.mcyukkuri.registry;

import io.github.longshou.mcyukkuri.MCYukkuri;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModCreativeTabs {
    public static final DeferredRegister<CreativeModeTab> TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MCYukkuri.MOD_ID);

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> YUKKURI_WORLD =
            TABS.register("yukkuri_world", () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.mcyukkuri"))
                    .icon(() -> ModItems.FIELD_NOTEBOOK.get().getDefaultInstance())
                    .displayItems((parameters, output) -> output.accept(ModItems.FIELD_NOTEBOOK.get()))
                    .build());

    private ModCreativeTabs() {}
}
