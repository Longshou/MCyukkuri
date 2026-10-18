package io.github.longshou.mcyukkuri.registry;

import io.github.longshou.mcyukkuri.command.YukkuriCommands;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.family.FamilyData;
import io.github.longshou.mcyukkuri.family.RelationshipCleanup;
import io.github.longshou.mcyukkuri.house.HouseEggs;
import io.github.longshou.mcyukkuri.house.HouseholdEcology;
import io.github.longshou.mcyukkuri.human.Custody;
import io.github.longshou.mcyukkuri.human.HumanCommerce;
import io.github.longshou.mcyukkuri.human.HumanWorld;
import io.github.longshou.mcyukkuri.life.YukkuriWorldData;
import io.github.longshou.mcyukkuri.registry.ModCreativeTabs;
import io.github.longshou.mcyukkuri.registry.ModEntities;
import io.github.longshou.mcyukkuri.settlement.SettlementTicker;
import io.github.longshou.mcyukkuri.settlement.SiteClaimer;
import java.util.function.Consumer;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.entity.EntityTickEvent;
import net.neoforged.neoforge.event.entity.RegisterSpawnPlacementsEvent;
import net.neoforged.neoforge.event.entity.living.LivingBreatheEvent;
import net.neoforged.neoforge.event.entity.living.LivingEntityUseItemEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.entity.player.PlayerWakeUpEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Explicit common event wiring; callback order is declared here. */
public final class CommonRegistration {
    public static void register(IEventBus modBus, Consumer<FMLCommonSetupEvent> commonSetup) {
        modBus.addListener(EventPriority.NORMAL, false, EntityAttributeCreationEvent.class, HumanWorld::attributes);

        modBus.addListener(EventPriority.NORMAL, false, RegisterSpawnPlacementsEvent.class, HumanWorld::placements);

        modBus.addListener(EventPriority.NORMAL, false, BuildCreativeModeTabContentsEvent.class, HumanWorld::creative);

        NeoForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, EntityTickEvent.Post.class, HumanCommerce::tickAnimal);

        NeoForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, EntityJoinLevelEvent.class, Custody::joining);

        modBus.addListener(EventPriority.NORMAL, false, EntityAttributeCreationEvent.class, ModEntities::registerAttributes);

        modBus.addListener(EventPriority.NORMAL, false, RegisterSpawnPlacementsEvent.class, ModEntities::registerSpawnPlacements);

        modBus.addListener(EventPriority.NORMAL, false, BuildCreativeModeTabContentsEvent.class, ModCreativeTabs::addVanillaSpawnEggs);

        modBus.addListener(EventPriority.NORMAL, false, FMLCommonSetupEvent.class, commonSetup);

        NeoForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, RegisterCommandsEvent.class, YukkuriCommands::register);

        NeoForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, ServerTickEvent.Post.class, SiteClaimer::tick);
        NeoForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, ServerTickEvent.Post.class, SettlementTicker::tick);

        NeoForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, LevelEvent.Unload.class, SettlementTicker::unloaded);

        NeoForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, ServerStoppedEvent.class, SettlementTicker::stopped);

        NeoForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, EntityJoinLevelEvent.class, YukkuriWorldData::joining);

        NeoForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, ServerTickEvent.Post.class, RelationshipCleanup::tick);

        NeoForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, EntityJoinLevelEvent.class, FamilyData::joining);

        NeoForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, EntityLeaveLevelEvent.class, FamilyData::leaving);
        NeoForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, EntityLeaveLevelEvent.class, HouseEggs::leaving);

        NeoForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, ServerTickEvent.Post.class, HouseholdEcology::tick);

        NeoForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, LivingEntityUseItemEvent.Finish.class, HouseholdEcology::finished);

        NeoForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, EntityJoinLevelEvent.class, HouseholdEcology::joining);

        NeoForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, EntityLeaveLevelEvent.class, HouseholdEcology::leaving);

        NeoForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, PlayerWakeUpEvent.class, HouseholdEcology::woke);

        NeoForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, BlockEvent.BreakEvent.class, HouseholdEcology::broke);

        NeoForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, PlayerInteractEvent.EntityInteract.class, HouseholdEcology::sheared);

        NeoForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, PlayerEvent.ItemCraftedEvent.class, HouseholdEcology::crafted);

        NeoForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, PlayerEvent.ItemSmeltedEvent.class, HouseholdEcology::smelted);

        NeoForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, EntityJoinLevelEvent.class, HouseholdEcology::itemJoin);

        NeoForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, PlayerTickEvent.Post.class, HouseholdEcology::purgeInventory);

        NeoForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, LivingBreatheEvent.class, YukkuriEntity::breatheInHat);
    }

    private CommonRegistration() {}
}
