package io.github.longshou.mcyukkuri.p0;

import net.neoforged.fml.common.Mod;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

@Mod(P0Mod.ID)
public final class P0Mod {
    public static final String ID = "mcyukkuri_p0";

    public P0Mod(IEventBus modBus) {
        if (P0Control.enabled()) {
            if("species_registry".equals(System.getProperty("mcyukkuri.p0.suite"))){modBus.addListener((RegisterGameTestsEvent event)->event.register(SpeciesRegistryGameTests.class));return;}
            if("winter".equals(System.getProperty("mcyukkuri.p0.suite"))){modBus.addListener((RegisterGameTestsEvent event)->event.register(WinterGameTests.class));return;}
            if("succession".equals(System.getProperty("mcyukkuri.p0.suite"))){modBus.addListener((RegisterGameTestsEvent event)->event.register(SuccessionGameTests.class));return;}
            if("autumn_stock".equals(System.getProperty("mcyukkuri.p0.suite"))){modBus.addListener((RegisterGameTestsEvent event)->event.register(AutumnStockGameTests.class));return;}
            if("seasons".equals(System.getProperty("mcyukkuri.p0.suite"))){modBus.addListener((RegisterGameTestsEvent event)->event.register(SeasonGameTests.class));return;}
            if("independence".equals(System.getProperty("mcyukkuri.p0.suite"))){modBus.addListener((RegisterGameTestsEvent event)->event.register(IndependenceGameTests.class));return;}
            if("childhood".equals(System.getProperty("mcyukkuri.p0.suite"))){modBus.addListener((RegisterGameTestsEvent event)->{event.register(FamilyCareGameTests.class);event.register(CareTerrainGameTests.class);event.register(ChildhoodNestGameTests.class);event.register(ChildhoodLifeGameTests.class);});return;}
            if("shed_stem".equals(System.getProperty("mcyukkuri.p0.suite"))){modBus.addListener((RegisterGameTestsEvent event)->event.register(ShedStemGameTests.class));return;}
            if("hats".equals(System.getProperty("mcyukkuri.p0.suite"))){modBus.addListener((RegisterGameTestsEvent event)->event.register(HatInteractionGameTests.class));return;}
            if("personality".equals(System.getProperty("mcyukkuri.p0.suite"))){modBus.addListener((RegisterGameTestsEvent event)->event.register(PersonalityDevelopmentGameTests.class));return;}
            if("growth".equals(System.getProperty("mcyukkuri.p0.suite"))){modBus.addListener((RegisterGameTestsEvent event)->{event.register(GrowthGameTests.class);event.register(FamilyGameTests.class);event.register(ConsumableGameTests.class);});return;}
            if("cognition".equals(System.getProperty("mcyukkuri.p0.suite"))){modBus.addListener((RegisterGameTestsEvent event)->event.register(CognitionGameTests.class));return;}
            if("wild_events".equals(System.getProperty("mcyukkuri.p0.suite"))){modBus.addListener((RegisterGameTestsEvent event)->event.register(WildEventGameTests.class));return;}
            if("living".equals(System.getProperty("mcyukkuri.p0.suite"))){modBus.addListener((RegisterGameTestsEvent event)->event.register(LivingGameTests.class));return;}
            if("subspecies".equals(System.getProperty("mcyukkuri.p0.suite"))){modBus.addListener((RegisterGameTestsEvent event)->event.register(SubspeciesGameTests.class));return;}
            if("aquatic_family".equals(System.getProperty("mcyukkuri.p0.suite"))){modBus.addListener((RegisterGameTestsEvent event)->{event.register(AquaticFamilyGameTests.class);event.register(HatShoreForageGameTests.class);});return;}
            if("predators".equals(System.getProperty("mcyukkuri.p0.suite"))){modBus.addListener((RegisterGameTestsEvent event)->event.register(PredatorGameTests.class));return;}
            if("dialogue".equals(System.getProperty("mcyukkuri.p0.suite"))){modBus.addListener((RegisterGameTestsEvent event)->event.register(DialogueGameTests.class));return;}
            if("freezer".equals(System.getProperty("mcyukkuri.p0.suite"))){modBus.addListener((RegisterGameTestsEvent event)->event.register(FreezerGameTests.class));return;}
            if("ecology".equals(System.getProperty("mcyukkuri.p0.suite"))){modBus.addListener((RegisterGameTestsEvent event)->event.register(EcologyGameTests.class));return;}
            if("waddle".equals(System.getProperty("mcyukkuri.p0.suite"))){modBus.addListener((RegisterGameTestsEvent event)->event.register(WaddleGameTests.class));return;}
            if("consumables".equals(System.getProperty("mcyukkuri.p0.suite"))){modBus.addListener((RegisterGameTestsEvent event)->event.register(ConsumableGameTests.class));return;}
            if("house_motion".equals(System.getProperty("mcyukkuri.p0.suite"))){modBus.addListener((RegisterGameTestsEvent event)->event.register(HouseMotionGameTests.class));return;}
            if("house_ecology".equals(System.getProperty("mcyukkuri.p0.suite"))){modBus.addListener((RegisterGameTestsEvent event)->{event.register(HouseEcologyGameTests.class);event.register(HouseRoutineGameTests.class);event.register(HouseSuppliesGameTests.class);event.register(HouseholdEcologyGameTests.class);});return;}
            if("core".equals(System.getProperty("mcyukkuri.p0.suite"))){modBus.addListener((RegisterGameTestsEvent event)->event.register(CoreGameTests.class));return;}
            if("playgrounds".equals(System.getProperty("mcyukkuri.p0.suite"))){modBus.addListener((RegisterGameTestsEvent event)->event.register(PlaygroundGameTests.class));return;}
            if("pet_shop".equals(System.getProperty("mcyukkuri.p0.suite"))){modBus.addListener((RegisterGameTestsEvent event)->event.register(PetShopGameTests.class));return;}
            if("badges".equals(System.getProperty("mcyukkuri.p0.suite"))){modBus.addListener((RegisterGameTestsEvent event)->event.register(BadgeGameTests.class));return;}
            if("humans".equals(System.getProperty("mcyukkuri.p0.suite"))){modBus.addListener((RegisterGameTestsEvent event)->{event.register(HumanGameTests.class);event.register(HumanExpansionGameTests.class);});return;}
            if("groups".equals(System.getProperty("mcyukkuri.p0.suite"))){modBus.addListener((RegisterGameTestsEvent event)->event.register(GroupGameTests.class));return;}
            if("wounds".equals(System.getProperty("mcyukkuri.p0.suite"))){modBus.addListener((RegisterGameTestsEvent event)->event.register(WoundGameTests.class));return;}
            if("remains".equals(System.getProperty("mcyukkuri.p0.suite"))){modBus.addListener((RegisterGameTestsEvent event)->{event.register(RemainsGameTests.class);event.register(WoundGameTests.class);event.register(CoreGameTests.class);});return;}
            if ("settlements".equals(System.getProperty("mcyukkuri.p0.suite"))) {
                modBus.addListener((RegisterGameTestsEvent event) -> {event.register(SettlementGameTests.class);event.register(ShelterGameTests.class);});
                return;
            }
            modBus.addListener((RegisterGameTestsEvent event) -> {event.register(HumanGameTests.class);event.register(HumanExpansionGameTests.class);});
            modBus.addListener((RegisterGameTestsEvent event) -> { event.register(P0GameTests.class); event.register(LivingGameTests.class); event.register(FamilyGameTests.class);event.register(CoreGameTests.class); event.register(DialogueGameTests.class); event.register(FillingGameTests.class);event.register(GrowthGameTests.class);event.register(PersonalityDevelopmentGameTests.class); });
            modBus.addListener((RegisterGameTestsEvent event) -> {event.register(SettlementGameTests.class);event.register(ShelterGameTests.class);});
            modBus.addListener((RegisterGameTestsEvent event)->event.register(OperationGameTests.class));
            modBus.addListener((RegisterGameTestsEvent event)->event.register(InstrumentGameTests.class));
            modBus.addListener((RegisterGameTestsEvent event)->event.register(GroupGameTests.class));
            modBus.addListener((RegisterGameTestsEvent event)->event.register(PlaygroundGameTests.class));
            modBus.addListener((RegisterGameTestsEvent event)->event.register(ConsumableGameTests.class));
            modBus.addListener((RegisterGameTestsEvent event)->event.register(WildEventGameTests.class));
            modBus.addListener((RegisterGameTestsEvent event)->event.register(CognitionGameTests.class));
            P0Server server = new P0Server();
            if ("integration".equals(System.getProperty("mcyukkuri.p0.suite"))) {
                modBus.addListener((RegisterGameTestsEvent event) -> {
                    event.register(BadgeGameTests.class);
                    event.register(WoundGameTests.class);
                    event.register(RemainsGameTests.class);
                    event.register(HouseMotionGameTests.class);
                    event.register(IntegrationEventGameTests.class);
                });
            }
            modBus.addListener((RegisterGameTestsEvent event)->event.register(EcologyGameTests.class));
            modBus.addListener((RegisterGameTestsEvent event)->event.register(FreezerGameTests.class));
            modBus.addListener((RegisterGameTestsEvent event)->event.register(WaddleGameTests.class));
            modBus.addListener((RegisterGameTestsEvent event)->event.register(PredatorGameTests.class));
            modBus.addListener((RegisterGameTestsEvent event)->event.register(SubspeciesGameTests.class));
            modBus.addListener((RegisterGameTestsEvent event)->event.register(AquaticFamilyGameTests.class));
            modBus.addListener((RegisterGameTestsEvent event)->event.register(ChildhoodLifeGameTests.class));
            NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.tick.ServerTickEvent.Pre event)->WaddlePreview.tick(event.getServer()));
            NeoForge.EVENT_BUS.addListener(server::started);
            NeoForge.EVENT_BUS.addListener(server::tick);
            NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.tick.ServerTickEvent.Post event)->HouseInteractionPreview.tick(event.getServer()));
            NeoForge.EVENT_BUS.addListener(server::login);
            NeoForge.EVENT_BUS.addListener(Showcase::commands);
            NeoForge.EVENT_BUS.addListener(Showcase::button);
            NeoForge.EVENT_BUS.addListener(HumanNaturalChecks::freezeNatural);
        }
    }
}
