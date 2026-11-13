package io.github.longshou.mcyukkuri.registry;

import io.github.longshou.mcyukkuri.aquatic.AquaticLife;
import io.github.longshou.mcyukkuri.aquatic.HullAfloat;
import io.github.longshou.mcyukkuri.command.PlaygroundTools;
import io.github.longshou.mcyukkuri.command.YukkuriCommands;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.event.EventReservations;
import io.github.longshou.mcyukkuri.event.HomeClaim;
import io.github.longshou.mcyukkuri.event.WildEvents;
import io.github.longshou.mcyukkuri.family.FamilyData;
import io.github.longshou.mcyukkuri.family.RelationshipCleanup;
import io.github.longshou.mcyukkuri.family.ShedStemDrops;
import io.github.longshou.mcyukkuri.freezer.FreezerModule;
import io.github.longshou.mcyukkuri.house.HouseEggs;
import io.github.longshou.mcyukkuri.house.HouseholdEcology;
import io.github.longshou.mcyukkuri.human.Custody;
import io.github.longshou.mcyukkuri.human.HumanWorld;
import io.github.longshou.mcyukkuri.intent.AskLedger;
import io.github.longshou.mcyukkuri.intent.Conductor;
import io.github.longshou.mcyukkuri.intent.DriveWeights;
import io.github.longshou.mcyukkuri.life.YukkuriWorldData;
import io.github.longshou.mcyukkuri.item.MagnifyingGlassItem;
import io.github.longshou.mcyukkuri.mind.Naming;
import io.github.longshou.mcyukkuri.motor.AbilityTable;
import io.github.longshou.mcyukkuri.motor.Motor;
import io.github.longshou.mcyukkuri.network.ReadoutNetworking;
import io.github.longshou.mcyukkuri.network.BodyEffectNetworking;
import io.github.longshou.mcyukkuri.interaction.InteractionDispatch;
import io.github.longshou.mcyukkuri.pet.SceneReset;
import io.github.longshou.mcyukkuri.command.PlaygroundFields;
import io.github.longshou.mcyukkuri.pet.exam.ExamSession;
import io.github.longshou.mcyukkuri.pet.stage.Staging;
import io.github.longshou.mcyukkuri.settlement.ForageClaims;
import io.github.longshou.mcyukkuri.settlement.Grazing;
import io.github.longshou.mcyukkuri.settlement.GroupMemberEvents;
import io.github.longshou.mcyukkuri.settlement.SettlementTicker;
import io.github.longshou.mcyukkuri.settlement.ShelterIndexEvents;
import io.github.longshou.mcyukkuri.settlement.FoundShelterCleanup;
import io.github.longshou.mcyukkuri.settlement.SiteClaimer;
import io.github.longshou.mcyukkuri.shop.ShopPerformance;
import io.github.longshou.mcyukkuri.shop.ShopProtection;
import io.github.longshou.mcyukkuri.shop.ShopStaff;
import io.github.longshou.mcyukkuri.shop.ShopTrade;
import io.github.longshou.mcyukkuri.shop.ShopRegistry;
import io.github.longshou.mcyukkuri.network.ShopRowPayload;
import io.github.longshou.mcyukkuri.status.Reactions;
import io.github.longshou.mcyukkuri.status.TraceRecorder;
import io.github.longshou.mcyukkuri.world.SearchBudgetEvents;
import java.util.function.Consumer;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.entity.EntityMountEvent;
import net.neoforged.neoforge.event.entity.RegisterSpawnPlacementsEvent;
import net.neoforged.neoforge.event.entity.living.LivingEntityUseItemEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.entity.player.PlayerWakeUpEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import io.github.longshou.mcyukkuri.human.VillageBin;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/** Explicit common event wiring; callback order is declared here. */
public final class CommonRegistration {
    public static void register(IEventBus modBus, Consumer<FMLCommonSetupEvent> commonSetup) {
        // The aquatic add-on: a set-down hat in water floats, the aquatic life's goal and natural spawn (the base never names them).
        HullAfloat.register();
        AquaticLife.register();
        modBus.addListener(EventPriority.NORMAL, false, EntityAttributeCreationEvent.class, HumanWorld::attributes);
        modBus.addListener(EventPriority.NORMAL, false, EntityAttributeCreationEvent.class, ModEntities::registerAttributes);

        modBus.addListener(EventPriority.NORMAL, false, RegisterSpawnPlacementsEvent.class, HumanWorld::placements);
        modBus.addListener(EventPriority.NORMAL, false, RegisterSpawnPlacementsEvent.class, ModEntities::registerSpawnPlacements);

        modBus.addListener(EventPriority.NORMAL, false, BuildCreativeModeTabContentsEvent.class, HumanWorld::creative);
        modBus.addListener(EventPriority.NORMAL, false, BuildCreativeModeTabContentsEvent.class, ModCreativeTabs::addVanillaSpawnEggs);
        modBus.addListener(EventPriority.NORMAL, false, BuildCreativeModeTabContentsEvent.class, FreezerModule::creative);

        modBus.addListener(EventPriority.NORMAL, false, FMLCommonSetupEvent.class, commonSetup);

        modBus.addListener(EventPriority.NORMAL, false, RegisterPayloadHandlersEvent.class, ReadoutNetworking::register);
        modBus.addListener(EventPriority.NORMAL, false, RegisterPayloadHandlersEvent.class, BodyEffectNetworking::register);
        modBus.addListener(EventPriority.NORMAL, false, RegisterPayloadHandlersEvent.class, ShopRowPayload::register);

        NeoForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, RegisterCommandsEvent.class, YukkuriCommands::register);

        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, false, ServerTickEvent.Pre.class, TraceRecorder::tickPre);
        NeoForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, ServerTickEvent.Pre.class, SearchBudgetEvents::tick);

        // Preserve the constructor prefix, including the settlement ticker's position.
        NeoForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, ServerTickEvent.Post.class, SiteClaimer::tick);
        NeoForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, ServerTickEvent.Post.class, SettlementTicker::tick);
        NeoForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, ServerTickEvent.Post.class, RelationshipCleanup::tick);
        NeoForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, ServerTickEvent.Post.class, HouseholdEcology::tick);
        NeoForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, ServerTickEvent.Post.class, PlaygroundTools::tick);
        NeoForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, ServerTickEvent.Post.class, HomeClaim::tick);
        NeoForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, ServerTickEvent.Post.class, WildEvents::tick);
        NeoForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, ServerTickEvent.Post.class, ShedStemDrops::tick);
        NeoForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, ServerTickEvent.Post.class, YukkuriWorldData::tickDeathHeadwear);
        NeoForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, ServerTickEvent.Post.class, ExamSession::tick);
        NeoForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, ServerTickEvent.Post.class, Staging::tick);
        NeoForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, ServerTickEvent.Post.class, Grazing::tick);
        NeoForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, ServerTickEvent.Post.class, GroupMemberEvents::tick);
        NeoForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, ServerTickEvent.Post.class, ShopPerformance::tick);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, false, ServerTickEvent.Post.class, TraceRecorder::tickPost);

        NeoForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, EntityJoinLevelEvent.class, Custody::joining);
        NeoForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, EntityJoinLevelEvent.class, YukkuriWorldData::joining);
        NeoForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, EntityJoinLevelEvent.class, FamilyData::joining);
        NeoForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, EntityJoinLevelEvent.class, HouseholdEcology::joining);
        NeoForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, EntityJoinLevelEvent.class, HouseholdEcology::itemJoin);
        // Restore staging before cognition mirrors the entity's saved state.
        NeoForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, EntityJoinLevelEvent.class, Staging::joining);
        NeoForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, EntityJoinLevelEvent.class, GroupMemberEvents::joining);
        // A lightning strike: every yukkuri in earshot hears the thunder clap.
        NeoForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, EntityJoinLevelEvent.class, Reactions::lightning);
        // Last, after every listener that may refuse the join: a naturally spawned aquatic marisa is seated on her hat.
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, false, EntityJoinLevelEvent.class, AquaticLife::joined);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, false, EntityJoinLevelEvent.class, TraceRecorder::joined);

        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, false, EntityLeaveLevelEvent.class, TraceRecorder::leaving);
        NeoForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, EntityLeaveLevelEvent.class, FamilyData::leaving);
        NeoForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, EntityLeaveLevelEvent.class, HouseEggs::leaving);
        NeoForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, EntityLeaveLevelEvent.class, HouseholdEcology::leaving);
        NeoForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, EntityLeaveLevelEvent.class, GroupMemberEvents::leaving);
        NeoForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, EntityLeaveLevelEvent.class, SearchBudgetEvents::leaving);
        NeoForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, EntityLeaveLevelEvent.class, ShopStaff::leaving);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, false, net.neoforged.neoforge.event.entity.living.LivingDeathEvent.class, ShopRegistry::animalDied);
        NeoForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, net.neoforged.neoforge.event.tick.LevelTickEvent.Post.class, ShopRegistry::levelTick);

        NeoForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, EntityMountEvent.class, Motor::mounting);

        NeoForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, LivingEntityUseItemEvent.Finish.class, HouseholdEcology::finished);

        NeoForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, PlayerWakeUpEvent.class, HouseholdEcology::woke);

        NeoForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, BlockEvent.BreakEvent.class, HouseholdEcology::broke);
        // Pet shops: nothing is destroyed inside and stock is not handled before it is bought; first, so later handlers see the cancellation.
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGH, false, BlockEvent.BreakEvent.class, ShopProtection::broke);
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGH, false, BlockEvent.EntityPlaceEvent.class, ShopProtection::placed);
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGH, false, BlockEvent.FluidPlaceBlockEvent.class, ShopProtection::fluid);
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGH, false, net.neoforged.neoforge.event.level.PistonEvent.Pre.class, ShopProtection::piston);
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGH, false, PlayerInteractEvent.RightClickBlock.class, ShopProtection::used);
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGH, false, PlayerInteractEvent.EntityInteract.class, ShopProtection::touched);
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGH, false, PlayerInteractEvent.EntityInteractSpecific.class, ShopProtection::touchedAt);
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGH, false, AttackEntityEvent.class, ShopProtection::struck);
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGH, false, LivingIncomingDamageEvent.class, ShopProtection::harmed);
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGH, false, ExplosionEvent.Detonate.class, ShopProtection::exploded);

        NeoForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, PlayerInteractEvent.EntityInteract.class, HouseholdEcology::sheared);
        NeoForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, PlayerInteractEvent.EntityInteract.class, MagnifyingGlassItem::blockCare);
        NeoForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, PlayerInteractEvent.EntityInteract.class, InteractionDispatch::interact);
        NeoForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, PlayerInteractEvent.EntityInteract.class, Naming::nameTag);
        NeoForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, PlayerInteractEvent.RightClickBlock.class, PlaygroundTools::sceneButton);

        NeoForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, PlayerEvent.ItemCraftedEvent.class, HouseholdEcology::crafted);

        NeoForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, PlayerEvent.ItemSmeltedEvent.class, HouseholdEcology::smelted);

        NeoForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, PlayerTickEvent.Post.class, HouseholdEcology::purgeInventory);
        NeoForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, PlayerTickEvent.Post.class, ShopRegistry::playerTick);
        NeoForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, net.neoforged.neoforge.event.entity.player.ItemEntityPickupEvent.Post.class, ShopTrade::pickedUp);



        // Commerce already ran at the end of YukkuriEntity.tick; carrying precedes discovery.
        NeoForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, EntityTickEvent.Post.class, WildEvents::discover);
        NeoForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, EntityTickEvent.Post.class, FoundShelterCleanup::tick);
        NeoForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, EntityTickEvent.Post.class, VillageBin::residentTick);
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, false, EntityJoinLevelEvent.class, PlaygroundFields::joining);
        NeoForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, EntityTickEvent.Post.class, PlaygroundFields::tick);

        NeoForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, ServerStartingEvent.class, SceneReset::started);
        NeoForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, ServerStartingEvent.class, ResourceWarmup::started);
        NeoForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, ServerStartingEvent.class, ForageClaims::started);
        NeoForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, ServerStartingEvent.class, ShopStaff::started);
        NeoForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, ServerStartingEvent.class, ShopPerformance::started);
        NeoForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, ServerStartingEvent.class, TraceRecorder::started);

        NeoForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, ServerStoppingEvent.class, TraceRecorder::stopping);
        NeoForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, ServerStoppingEvent.class, ExamSession::stopping);

        NeoForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, ServerStoppedEvent.class, SettlementTicker::stopped);
        NeoForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, ServerStoppedEvent.class, event -> { Conductor.reset(); AskLedger.server().clear(); });
        NeoForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, ServerStoppedEvent.class, EventReservations::stopped);
        NeoForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, ServerStoppedEvent.class, HomeClaim::stopped);
        NeoForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, ServerStoppedEvent.class, SceneReset::stopped);
        NeoForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, ServerStoppedEvent.class, ForageClaims::stopped);
        NeoForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, ServerStoppedEvent.class, GroupMemberEvents::stopped);
        NeoForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, ServerStoppedEvent.class, ShelterIndexEvents::stopped);
        NeoForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, ServerStoppedEvent.class, ShopStaff::stopped);
        NeoForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, ServerStoppedEvent.class, ShopPerformance::stopped);
        NeoForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, ServerStoppedEvent.class, SearchBudgetEvents::stopped);

        // Ability data: reload before any action reads it, and let /reload swap the numbers.
        NeoForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, AddReloadListenerEvent.class,
                event -> event.addListener(new AbilityTable()));
        // Desire data, the same way.
        NeoForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, AddReloadListenerEvent.class,
                event -> event.addListener(new DriveWeights()));

        NeoForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, ChunkEvent.Load.class, ShelterIndexEvents::loaded);

        NeoForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, ChunkEvent.Unload.class, ShelterIndexEvents::unloaded);

        NeoForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, LevelEvent.Unload.class, SettlementTicker::unloaded);
        NeoForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, LevelEvent.Unload.class, GroupMemberEvents::unloaded);
        NeoForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, LevelEvent.Unload.class, ShelterIndexEvents::unloaded);
        NeoForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, LevelEvent.Unload.class, SearchBudgetEvents::unloaded);
    }

    private CommonRegistration() {}
}
