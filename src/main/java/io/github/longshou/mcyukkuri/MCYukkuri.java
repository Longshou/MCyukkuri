package io.github.longshou.mcyukkuri;

import com.mojang.logging.LogUtils;
import io.github.longshou.mcyukkuri.registry.CommonRegistration;
import io.github.longshou.mcyukkuri.event.SettlementEventBridge;
import io.github.longshou.mcyukkuri.settlement.SettlementEvents;
import io.github.longshou.mcyukkuri.registry.ModCreativeTabs;
import io.github.longshou.mcyukkuri.registry.ModEntities;
import io.github.longshou.mcyukkuri.registry.ModItems;
import io.github.longshou.mcyukkuri.registry.ModBlocks;
import io.github.longshou.mcyukkuri.registry.ModSounds;
import io.github.longshou.mcyukkuri.life.YukkuriConfig;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import org.slf4j.Logger;

/** Shared entry point: keep client rendering classes out of this package. */
@Mod(MCYukkuri.MOD_ID)
public final class MCYukkuri {
    public static final String MOD_ID = "mcyukkuri";
    public static final Logger LOGGER = LogUtils.getLogger();

    public MCYukkuri(IEventBus modBus, ModContainer container) {
        SettlementEvents.install(new SettlementEventBridge());
        io.github.longshou.mcyukkuri.human.HumanWorld.register(modBus, container);
        io.github.longshou.mcyukkuri.settlement.site.SiteWorld.register(modBus);
        io.github.longshou.mcyukkuri.life.Facilities.register(modBus);
        io.github.longshou.mcyukkuri.play.Playgrounds.register(modBus);
        io.github.longshou.mcyukkuri.life.Consumables.register(modBus);
        io.github.longshou.mcyukkuri.freezer.FreezerModule.register(modBus);
        ModBlocks.BLOCKS.register(modBus);
        ModBlocks.BLOCK_ENTITIES.register(modBus);
        ModEntities.TYPES.register(modBus);
        ModItems.ITEMS.register(modBus);
        ModSounds.SOUND_EVENTS.register(modBus);
        io.github.longshou.mcyukkuri.registry.ModMenus.MENUS.register(modBus);
        ModCreativeTabs.TABS.register(modBus);
        CommonRegistration.register(modBus, this::commonSetup);
        container.registerConfig(ModConfig.Type.SERVER, YukkuriConfig.SPEC);
        container.registerConfig(ModConfig.Type.SERVER, io.github.longshou.mcyukkuri.family.FamilyConfig.SPEC, "mcyukkuri-family.toml");
    }

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(()->net.minecraft.world.level.block.ComposterBlock.COMPOSTABLES.put(ModItems.WASTE.get(),.3f));
        // Last words to the kin around a dying body (social/GriefLife).
        io.github.longshou.mcyukkuri.body.BodyLife.DYING_LISTENERS.add(io.github.longshou.mcyukkuri.social.GriefLife::dying);
        LOGGER.info("Yukkuri World initialized with ten Yukkuri entity types.");
    }
}
