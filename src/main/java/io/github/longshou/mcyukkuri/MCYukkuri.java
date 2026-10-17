package io.github.longshou.mcyukkuri;

import com.mojang.logging.LogUtils;
import io.github.longshou.mcyukkuri.command.YukkuriCommands;
import io.github.longshou.mcyukkuri.registry.ModCreativeTabs;
import io.github.longshou.mcyukkuri.registry.ModEntities;
import io.github.longshou.mcyukkuri.registry.ModItems;
import io.github.longshou.mcyukkuri.registry.ModBlocks;
import io.github.longshou.mcyukkuri.registry.ModSounds;
import io.github.longshou.mcyukkuri.life.YukkuriConfig;
import io.github.longshou.mcyukkuri.life.YukkuriWorldData;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

/** Shared entry point: keep client rendering classes out of this package. */
@Mod(MCYukkuri.MOD_ID)
public final class MCYukkuri {
    public static final String MOD_ID = "mcyukkuri";
    public static final Logger LOGGER = LogUtils.getLogger();

    public MCYukkuri(IEventBus modBus, ModContainer container) {
        io.github.longshou.mcyukkuri.human.HumanWorld.register(modBus, container);
        io.github.longshou.mcyukkuri.settlement.site.SiteWorld.register(modBus);
        io.github.longshou.mcyukkuri.life.Facilities.init();
        io.github.longshou.mcyukkuri.play.Playgrounds.init();
        io.github.longshou.mcyukkuri.life.Consumables.init();
        io.github.longshou.mcyukkuri.freezer.FreezerModule.init();
        ModBlocks.BLOCKS.register(modBus);
        ModBlocks.BLOCK_ENTITIES.register(modBus);
        ModEntities.TYPES.register(modBus);
        ModItems.ITEMS.register(modBus);
        ModSounds.SOUND_EVENTS.register(modBus);
        io.github.longshou.mcyukkuri.registry.ModMenus.MENUS.register(modBus);
        ModCreativeTabs.TABS.register(modBus);
        modBus.addListener(ModEntities::registerAttributes);
        modBus.addListener(ModEntities::registerSpawnPlacements);
        modBus.addListener(ModCreativeTabs::addVanillaSpawnEggs);
        modBus.addListener(this::commonSetup);
        NeoForge.EVENT_BUS.addListener(YukkuriCommands::register);
        NeoForge.EVENT_BUS.addListener(io.github.longshou.mcyukkuri.settlement.SiteClaimer::tick);
        NeoForge.EVENT_BUS.addListener(YukkuriWorldData::joining);
        NeoForge.EVENT_BUS.addListener(io.github.longshou.mcyukkuri.family.FamilyData::joining);
        NeoForge.EVENT_BUS.addListener(io.github.longshou.mcyukkuri.family.FamilyData::leaving);
        NeoForge.EVENT_BUS.addListener(io.github.longshou.mcyukkuri.house.HouseEggs::leaving);
        NeoForge.EVENT_BUS.addListener(io.github.longshou.mcyukkuri.house.HouseholdEcology::tick);
        NeoForge.EVENT_BUS.addListener(io.github.longshou.mcyukkuri.house.HouseholdEcology::finished);
        NeoForge.EVENT_BUS.addListener(io.github.longshou.mcyukkuri.house.HouseholdEcology::joining);
        NeoForge.EVENT_BUS.addListener(io.github.longshou.mcyukkuri.house.HouseholdEcology::leaving);
        NeoForge.EVENT_BUS.addListener(io.github.longshou.mcyukkuri.house.DropSettling::tick);
        NeoForge.EVENT_BUS.addListener(io.github.longshou.mcyukkuri.house.DropSettling::joining);
        NeoForge.EVENT_BUS.addListener(io.github.longshou.mcyukkuri.house.DropSettling::leaving);
        NeoForge.EVENT_BUS.addListener(io.github.longshou.mcyukkuri.entity.YukkuriEntity::breatheInHat);
        container.registerConfig(ModConfig.Type.SERVER, YukkuriConfig.SPEC);
        container.registerConfig(ModConfig.Type.SERVER, io.github.longshou.mcyukkuri.family.FamilyConfig.SPEC, "mcyukkuri-family.toml");
    }

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(()->net.minecraft.world.level.block.ComposterBlock.COMPOSTABLES.put(ModItems.WASTE.get(),.3f));
        LOGGER.info("Yukkuri World initialized with nine Yukkuri entity types.");
    }
}
