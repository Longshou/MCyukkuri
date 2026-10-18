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
import io.github.longshou.mcyukkuri.body.BodyLife;
import io.github.longshou.mcyukkuri.family.FamilyConfig;
import io.github.longshou.mcyukkuri.freezer.FreezerModule;
import io.github.longshou.mcyukkuri.human.HumanWorld;
import io.github.longshou.mcyukkuri.life.Consumables;
import io.github.longshou.mcyukkuri.life.Facilities;
import io.github.longshou.mcyukkuri.play.Playgrounds;
import io.github.longshou.mcyukkuri.registry.ModMenus;
import io.github.longshou.mcyukkuri.settlement.site.SiteWorld;
import io.github.longshou.mcyukkuri.social.GriefLife;
import net.minecraft.world.level.block.ComposterBlock;

/** Shared entry point: keep client rendering classes out of this package. */
@Mod(MCYukkuri.MOD_ID)
public final class MCYukkuri {
    public static final String MOD_ID = "mcyukkuri";
    public static final Logger LOGGER = LogUtils.getLogger();

    public MCYukkuri(IEventBus modBus, ModContainer container) {
        SettlementEvents.install(new SettlementEventBridge());
        HumanWorld.register(modBus, container);
        SiteWorld.register(modBus);
        Facilities.register(modBus);
        Playgrounds.register(modBus);
        Consumables.register(modBus);
        FreezerModule.register(modBus);
        ModBlocks.BLOCKS.register(modBus);
        ModBlocks.BLOCK_ENTITIES.register(modBus);
        ModEntities.TYPES.register(modBus);
        ModItems.ITEMS.register(modBus);
        ModSounds.SOUND_EVENTS.register(modBus);
        ModMenus.MENUS.register(modBus);
        ModCreativeTabs.TABS.register(modBus);
        CommonRegistration.register(modBus, this::commonSetup);
        container.registerConfig(ModConfig.Type.SERVER, YukkuriConfig.SPEC);
        container.registerConfig(ModConfig.Type.SERVER, FamilyConfig.SPEC, "mcyukkuri-family.toml");
    }

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(()->ComposterBlock.COMPOSTABLES.put(ModItems.WASTE.get(),.3f));
        // Last words to the kin around a dying body (social/GriefLife).
        BodyLife.DYING_LISTENERS.add(GriefLife::dying);
        LOGGER.info("Yukkuri World initialized with ten Yukkuri entity types.");
    }
}
