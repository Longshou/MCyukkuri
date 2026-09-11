package io.github.longshou.mcyukkuri;

import com.mojang.logging.LogUtils;
import io.github.longshou.mcyukkuri.registry.ModCreativeTabs;
import io.github.longshou.mcyukkuri.registry.ModEntities;
import io.github.longshou.mcyukkuri.registry.ModItems;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import org.slf4j.Logger;

/** Shared entry point: keep client rendering classes out of this package. */
@Mod(MCYukkuri.MOD_ID)
public final class MCYukkuri {
    public static final String MOD_ID = "mcyukkuri";
    public static final Logger LOGGER = LogUtils.getLogger();

    public MCYukkuri(IEventBus modBus) {
        ModEntities.TYPES.register(modBus);
        ModItems.ITEMS.register(modBus);
        ModCreativeTabs.TABS.register(modBus);
        modBus.addListener(ModEntities::registerAttributes);
        modBus.addListener(this::commonSetup);
    }

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        LOGGER.info("Yukkuri World initialized with Reimu and Marisa entities.");
    }
}
