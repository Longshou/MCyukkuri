package io.github.longshou.mcyukkuri.human;

import io.github.longshou.mcyukkuri.MCYukkuri;
import io.github.longshou.mcyukkuri.registry.ModCreativeTabs;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.*;
import net.minecraft.world.item.*;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.*;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.event.entity.*;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.*;

/** Human residents and trades; buildings use vanilla, data-driven jigsaw structures. */
public final class HumanWorld {
    public static final DeferredRegister<EntityType<?>> ENTITIES = DeferredRegister.create(Registries.ENTITY_TYPE, MCYukkuri.MOD_ID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MCYukkuri.MOD_ID);
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MCYukkuri.MOD_ID);
    public static final DeferredBlock<FactoryStationBlock> LABOR_BENCH = station("labor_bench");
    public static final DeferredBlock<FactoryStationBlock> MANJU_STEAMER = station("manju_steamer");
    public static final DeferredBlock<FactoryStationBlock> PACKING_TABLE = station("live_packing_table");
    public static final DeferredBlock<FactoryStationBlock> NURSERY_STATION = station("nursery_station");
    public static final DeferredBlock<FactoryStationBlock> RECEIVING_TABLE = station("factory_receiving_table");
    public static final DeferredBlock<FactoryStationBlock> INSPECTION_TABLE = station("factory_inspection_table");
    public static final DeferredBlock<FactoryStationBlock> WASHING_TABLE = station("factory_washing_table");
    public static final DeferredBlock<FactoryStationBlock> OBSERVATION_STATION = station("factory_observation_station");
    public static final DeferredBlock<FactoryStationBlock> TRANSFER_TRAY = station("factory_transfer_tray");
    public static final DeferredBlock<ExamPodiumBlock> EXAM_PODIUM = simple("exam_podium", () -> new ExamPodiumBlock(net.minecraft.world.level.block.state.BlockBehaviour.Properties.of().strength(1.5f).sound(net.minecraft.world.level.block.SoundType.WOOD).noOcclusion()));
    public static final DeferredBlock<ExamHideBoxBlock> EXAM_HIDE_BOX = simple("exam_hide_box", () -> new ExamHideBoxBlock(net.minecraft.world.level.block.state.BlockBehaviour.Properties.of().strength(.4f).sound(net.minecraft.world.level.block.SoundType.WOOL).noOcclusion()));
    public static final DeferredBlock<ExamMarkBlock> EXAM_MARK = simple("exam_mark", () -> new ExamMarkBlock(net.minecraft.world.level.block.state.BlockBehaviour.Properties.of().strength(.2f).sound(net.minecraft.world.level.block.SoundType.WOOL).noCollission().noOcclusion()));
    public static final DeferredHolder<EntityType<?>, EntityType<FactoryTray>> TRAY = ENTITIES.register("factory_tray", () -> EntityType.Builder.<FactoryTray>of(FactoryTray::new, MobCategory.MISC)
            .sized(.95f, .25f).clientTrackingRange(10).build(MCYukkuri.id("factory_tray").toString()));
    public static final DeferredItem<Item> MANJU = ITEMS.register("yukkuri_manju", () -> new Item(new Item.Properties()
            .food(new net.minecraft.world.food.FoodProperties.Builder().nutrition(5).saturationModifier(.4f).build())));
    public static final DeferredItem<LiveBagItem> LIVE_BAG = ITEMS.register("live_yukkuri_bag", () -> new LiveBagItem(new Item.Properties().stacksTo(1)));
    public static final DeferredItem<Item> BRONZE_BADGE = ITEMS.register("bronze_yukkuri_badge", () -> new io.github.longshou.mcyukkuri.badge.BadgeItem(false));
    public static final DeferredItem<Item> SILVER_BADGE = ITEMS.register("silver_yukkuri_badge", () -> new io.github.longshou.mcyukkuri.badge.BadgeItem(false));
    public static final DeferredItem<Item> GOLD_BADGE = ITEMS.register("gold_yukkuri_badge", () -> new io.github.longshou.mcyukkuri.badge.BadgeItem(false));
    public static final DeferredItem<io.github.longshou.mcyukkuri.badge.BadgeItem> BADGE_HANDBOOK = ITEMS.register("badge_handbook", () -> new io.github.longshou.mcyukkuri.badge.BadgeItem(true));
    public static final DeferredRegister<MenuType<?>> MENUS = DeferredRegister.create(Registries.MENU, MCYukkuri.MOD_ID);
    public static final DeferredRegister<net.minecraft.world.level.levelgen.structure.StructureType<?>> STRUCTURES = DeferredRegister.create(Registries.STRUCTURE_TYPE, MCYukkuri.MOD_ID);
    public static final DeferredHolder<net.minecraft.world.level.levelgen.structure.StructureType<?>,net.minecraft.world.level.levelgen.structure.StructureType<HumanBuildingStructure>> BUILDING_TYPE = STRUCTURES.register("human_building",()->()->HumanBuildingStructure.CODEC);
    public static final DeferredHolder<EntityType<?>, EntityType<HumanNpc>> ONIISAN = type("oniisan", HumanNpc.Look.ONIISAN);
    public static final DeferredHolder<EntityType<?>, EntityType<HumanNpc>> BROTHER = type("caretaker_brother", HumanNpc.Look.BROTHER);
    public static final DeferredHolder<EntityType<?>, EntityType<HumanNpc>> SISTER = type("caretaker_sister", HumanNpc.Look.SISTER);
    public static final DeferredItem<DeferredSpawnEggItem> ONIISAN_EGG = egg("oniisan", ONIISAN, 0x333b48, 0xa24e42);
    public static final DeferredItem<DeferredSpawnEggItem> BROTHER_EGG = egg("caretaker_brother", BROTHER, 0x486f66, 0xebd6ad);
    public static final DeferredItem<DeferredSpawnEggItem> SISTER_EGG = egg("caretaker_sister", SISTER, 0xad596b, 0xf0d4aa);
    public static final DeferredHolder<MenuType<?>, MenuType<HumanMenu>> MENU = MENUS.register("human_trades",
            () -> IMenuTypeExtension.create((id, inv, buf) -> new HumanMenu(id, inv, buf.readVarInt())));
    public static final DeferredHolder<MenuType<?>, MenuType<io.github.longshou.mcyukkuri.badge.BadgeMenu>> BADGE_MENU = MENUS.register("badge_assessment",
            () -> IMenuTypeExtension.create((id, inv, buf) -> new io.github.longshou.mcyukkuri.badge.BadgeMenu(id, inv, buf.readVarInt())));
    public static final DeferredHolder<MenuType<?>, MenuType<io.github.longshou.mcyukkuri.shop.ShopMenu>> SHOP_MENU = MENUS.register("pet_shop",
            () -> IMenuTypeExtension.create((id, inv, buf) -> new io.github.longshou.mcyukkuri.shop.ShopMenu(id, inv, buf.readVarInt())));
    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.BooleanValue ROAMING, CRUELTY;
    public static final ModConfigSpec.IntValue TRADE_DAY_LIMIT, CARE_SUPPLIES, BADGE_VALIDITY_DAYS;
    static {
        var b = new ModConfigSpec.Builder();
        ROAMING = b.comment("Allow rare daylight human encounters; structure residents are independent.").define("roamingResidents", true);
        CRUELTY = b.comment("Oniisan may harm unclaimed wild yukkuri. Factory processing requires a delivered input.").define("oniisanCruelty", true);
        TRADE_DAY_LIMIT = b.defineInRange("supplyTradesPerDay", 8, 1, 64);
        CARE_SUPPLIES = b.defineInRange("initialCareFood", 32, 0, 256);
        BADGE_VALIDITY_DAYS = b.comment("Game days a silver or gold badge stays valid; 0 means never expires.").defineInRange("badgeValidityDays", 20, 0, 1000);
        SPEC = b.build();
    }
    private static DeferredHolder<EntityType<?>, EntityType<HumanNpc>> type(String id, HumanNpc.Look look) {
        return ENTITIES.register(id, () -> EntityType.Builder.<HumanNpc>of((type, level) -> new HumanNpc(type, level, look), MobCategory.CREATURE)
                .sized(.6f, 1.8f).eyeHeight(1.62f).clientTrackingRange(10).build(MCYukkuri.id(id).toString()));
    }
    private static DeferredBlock<FactoryStationBlock> station(String id) {
        var result=BLOCKS.register(id,()->new FactoryStationBlock(id,net.minecraft.world.level.block.state.BlockBehaviour.Properties.of().strength(3).noOcclusion()));
        ITEMS.registerSimpleBlockItem(id,result); return result;
    }
    private static <B extends net.minecraft.world.level.block.Block> DeferredBlock<B> simple(String id, java.util.function.Supplier<B> block) {
        var result=BLOCKS.register(id,block); ITEMS.registerSimpleBlockItem(id,result); return result;
    }
    private static DeferredItem<DeferredSpawnEggItem> egg(String id, DeferredHolder<EntityType<?>, EntityType<HumanNpc>> type, int a, int b) {
        return ITEMS.register(id + "_spawn_egg", () -> new DeferredSpawnEggItem(type, a, b, new Item.Properties()));
    }
    public static void register(IEventBus bus, ModContainer container) {
        ENTITIES.register(bus); ITEMS.register(bus); BLOCKS.register(bus); MENUS.register(bus); STRUCTURES.register(bus);
        bus.addListener(HumanWorld::attributes); bus.addListener(HumanWorld::placements); bus.addListener(HumanWorld::creative);
        NeoForge.EVENT_BUS.addListener(HumanCommerce::tickAnimal);
        container.registerConfig(ModConfig.Type.SERVER, SPEC, "mcyukkuri-human-world.toml");
    }
    private static void attributes(EntityAttributeCreationEvent e) {
        e.put(TRAY.get(), FactoryTray.attributes().build());
        for (var type : java.util.List.of(ONIISAN, BROTHER, SISTER)) e.put(type.get(), HumanNpc.attributes().build());
    }
    private static void placements(RegisterSpawnPlacementsEvent e) {
        for (var type : java.util.List.of(ONIISAN, BROTHER, SISTER))
            e.register(type.get(), SpawnPlacementTypes.ON_GROUND, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                    (t, level, reason, pos, random) -> ROAMING.get() && level.getLevel().isDay()
                            && level.getRawBrightness(pos, 0) > 8 && level.getFluidState(pos).isEmpty()
                            && level.getBlockState(pos.below()).is(net.minecraft.tags.BlockTags.ANIMALS_SPAWNABLE_ON)
                            && level.getLevel().getEntitiesOfClass(HumanNpc.class, new net.minecraft.world.phys.AABB(pos).inflate(96)).size() < 3,
                    RegisterSpawnPlacementsEvent.Operation.REPLACE);
    }
    private static void creative(BuildCreativeModeTabContentsEvent e) {
        ModCreativeTabs.addHumanContents(e);
    }
    private HumanWorld() {}
}
