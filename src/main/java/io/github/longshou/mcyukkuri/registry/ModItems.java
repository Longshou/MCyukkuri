package io.github.longshou.mcyukkuri.registry;

import io.github.longshou.mcyukkuri.MCYukkuri;
import io.github.longshou.mcyukkuri.entity.YukkuriVariant;
import io.github.longshou.mcyukkuri.item.AccessoryItem;
import io.github.longshou.mcyukkuri.item.CarrierItem;
import io.github.longshou.mcyukkuri.item.NotebookItem;
import io.github.longshou.mcyukkuri.item.TeachingWhistleItem;
import io.github.longshou.mcyukkuri.item.WashClothItem;
import io.github.longshou.mcyukkuri.item.ShelterItem;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.food.FoodProperties;
import net.neoforged.neoforge.common.DeferredSpawnEggItem;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.item.AccessoryShardItem;
import io.github.longshou.mcyukkuri.item.CompanionCordItem;
import io.github.longshou.mcyukkuri.item.FillingItem;
import io.github.longshou.mcyukkuri.item.HouseEggItem;
import io.github.longshou.mcyukkuri.item.HouseholdSprayItem;
import io.github.longshou.mcyukkuri.item.OperationToolItem;
import io.github.longshou.mcyukkuri.item.PetHarnessItem;
import io.github.longshou.mcyukkuri.item.RemainsItem;
import io.github.longshou.mcyukkuri.item.SupplyItem;
import io.github.longshou.mcyukkuri.item.SwaddleItem;
import io.github.longshou.mcyukkuri.item.TreatmentItem;
import io.github.longshou.mcyukkuri.settlement.ShelterBlock;
import io.github.longshou.mcyukkuri.subspecies.SubspeciesLife;
import java.util.EnumMap;
import java.util.Map;

public final class ModItems {
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MCYukkuri.MOD_ID);
    private static final Map<YukkuriVariant.Accessory, DeferredItem<AccessoryItem>> ACCESSORIES = new EnumMap<>(YukkuriVariant.Accessory.class);
    private static final Map<YukkuriVariant, DeferredItem<DeferredSpawnEggItem>> SPAWN_EGGS = new EnumMap<>(YukkuriVariant.class);
    public static final Map<OperationToolItem.Tool,DeferredItem<OperationToolItem>> OPERATION_TOOLS=new EnumMap<>(OperationToolItem.Tool.class);
    static {
        for(var tool:OperationToolItem.Tool.values())OPERATION_TOOLS.put(tool,ITEMS.register(tool.id,()->new OperationToolItem(tool,
                tool.inserted?new Item.Properties().stacksTo(16):new Item.Properties().durability(128))));
    }
    public static final DeferredItem<Item> TRIMMED_HAIR=ITEMS.registerSimpleItem("trimmed_hair",new Item.Properties());

    public static final DeferredItem<NotebookItem> FIELD_NOTEBOOK = ITEMS.register("field_notebook", () -> new NotebookItem(new Item.Properties().stacksTo(1)));
    public static final DeferredItem<CarrierItem> CARRIER = ITEMS.register("yukkuri_carrier", () -> new CarrierItem(new Item.Properties().stacksTo(1)));
    public static final DeferredItem<WashClothItem> WASH_CLOTH = ITEMS.register("wash_cloth", () -> new WashClothItem(new Item.Properties().stacksTo(1)));
    public static final DeferredItem<HouseholdSprayItem> HOUSEHOLD_GUARD=ITEMS.register("household_guard",()->new HouseholdSprayItem(new Item.Properties().stacksTo(1)));
    public static final DeferredItem<TeachingWhistleItem> WHISTLE = ITEMS.register("teaching_whistle", () -> new TeachingWhistleItem(new Item.Properties().stacksTo(1)));
    public static final DeferredItem<AccessoryItem> RIBBON = registerAccessory(YukkuriVariant.Accessory.RIBBON);
    public static final DeferredItem<AccessoryItem> HAT = registerAccessory(YukkuriVariant.Accessory.HAT);
    /** The aquatic Marisa's larger hat uses the shared shape with a separate accessory item. */
    public static final DeferredItem<AccessoryItem> AQUATIC_MARISA_HAT = ITEMS.register("marisa_aquatic_hat", () -> new AccessoryItem(new Item.Properties()));
    /** Stackable repair material accepted by a shattered individual of the same accessory kind. */
    public static final DeferredItem<AccessoryShardItem> ACCESSORY_SHARD = ITEMS.register("accessory_shard", () -> new AccessoryShardItem(new Item.Properties()));
    public static final DeferredItem<AccessoryItem> MARISA_SHELL = ITEMS.register("marisa_shell", () -> new AccessoryItem(new Item.Properties()));
    public static final DeferredItem<Item> TOY = ITEMS.registerSimpleItem("yarn_ball", new Item.Properties().stacksTo(16));
    public static final DeferredItem<SupplyItem> WASTE = ITEMS.register("yukkuri_waste",()->new SupplyItem(new Item.Properties()));
    public static final DeferredItem<SupplyItem> FOOD_SCRAP=ITEMS.register("food_scrap",()->new SupplyItem(new Item.Properties()));
    public static final DeferredItem<SupplyItem> NEST_LINT=ITEMS.register("nest_lint",()->new SupplyItem(new Item.Properties()));
    public static final DeferredItem<HouseEggItem> HOUSE_EGG=ITEMS.register("house_egg",
            ()->new HouseEggItem(new Item.Properties().stacksTo(1)));
    public static final DeferredItem<Item> YUKKURI_STEM=ITEMS.registerSimpleItem("yukkuri_stem",new Item.Properties());
    /** The valuable centre filling is a real item. Old core stacks stored in leaked_filling remain compatible. */
    public static final DeferredItem<Item> CORE_FILLING=ITEMS.registerSimpleItem("core_filling",new Item.Properties().stacksTo(16)
            .food(new FoodProperties.Builder().nutrition(6).saturationModifier(1.2f).build()));
    /** Compatibility placeholder for old saves only; new ordinary filling is never created. */
    public static final DeferredItem<FillingItem> FILLING=ITEMS.register("leaked_filling",()->new FillingItem(new Item.Properties()));
    public static final DeferredItem<Item> GRAIN_BUDGET=ITEMS.registerSimpleItem("oil_grain_budget",new Item.Properties());
    public static final DeferredItem<Item> GRAIN_REGULAR=ITEMS.registerSimpleItem("oil_grain_regular",new Item.Properties());
    public static final DeferredItem<Item> GRAIN_PREMIUM=ITEMS.registerSimpleItem("oil_grain_premium",new Item.Properties());
    // Sweets (docs/PET_SHOP.md): placeholder icons, tagged mcyukkuri:yukkuri_sweets together with the vanilla cookie.
    public static final DeferredItem<Item> SWEET_CANDY=ITEMS.registerSimpleItem("sweet_candy",new Item.Properties());
    public static final DeferredItem<Item> CUSTARD_PUDDING=ITEMS.registerSimpleItem("custard_pudding",new Item.Properties().stacksTo(16));
    public static final DeferredItem<TreatmentItem> ORANGE_JUICE=ITEMS.register("orange_juice",()->new TreatmentItem(TreatmentItem.Kind.ORANGE_JUICE,new Item.Properties().stacksTo(16)));
    public static final DeferredItem<TreatmentItem> FLOUR_PASTE=ITEMS.register("flour_paste",()->new TreatmentItem(TreatmentItem.Kind.FLOUR_PASTE,new Item.Properties().stacksTo(16)));
    public static final DeferredItem<TreatmentItem> MOLD_REMOVER=ITEMS.register("mold_remover",()->new TreatmentItem(TreatmentItem.Kind.MOLD_REMOVER,new Item.Properties().stacksTo(16)));
    public static final DeferredItem<TreatmentItem> MEDICINE=ITEMS.register("herbal_medicine",()->new TreatmentItem(TreatmentItem.Kind.HERB,new Item.Properties()));
    static {
        // Old saved stacks resolve to the existing skin-repair material; the bandage itself is no longer registered.
        ITEMS.addAlias(MCYukkuri.id("bandage"), MCYukkuri.id("flour_paste"));
    }
    public static final DeferredItem<RemainsItem> REMAINS=ITEMS.register("yukkuri_remains",()->new RemainsItem(new Item.Properties().stacksTo(1)));
    public static final DeferredItem<PetHarnessItem> PET_HARNESS = ITEMS.register("pet_harness",
            () -> new PetHarnessItem(new Item.Properties().stacksTo(16)));
    public static final DeferredItem<SwaddleItem> SWADDLE_CLOTH = ITEMS.register("swaddle_cloth",
            () -> new SwaddleItem(new Item.Properties().stacksTo(16)));
    public static final DeferredItem<CompanionCordItem> COMPANION_CORD = ITEMS.register("companion_cord",
            () -> new CompanionCordItem(new Item.Properties().stacksTo(1)));
    public static final DeferredItem<BlockItem> FOOD_BOWL = ITEMS.registerSimpleBlockItem("food_bowl", ModBlocks.FOOD_BOWL);
    public static final DeferredItem<BlockItem> NEST = ITEMS.registerSimpleBlockItem("yukkuri_nest", ModBlocks.NEST);
    public static final DeferredItem<BlockItem> TOILET = ITEMS.registerSimpleBlockItem("yukkuri_toilet", ModBlocks.TOILET);
    public static final DeferredItem<BlockItem> WILD_LATRINE=ITEMS.registerSimpleBlockItem("wild_latrine",ModBlocks.WILD_LATRINE);
    public static final DeferredItem<BlockItem> FORAGE_PILE=ITEMS.registerSimpleBlockItem("forage_pile",ModBlocks.FORAGE_PILE);
    public static final DeferredItem<ShelterItem> FOREST_BURROW=ITEMS.register("forest_burrow",()->new ShelterItem(ShelterBlock.Kind.BURROW,new Item.Properties().stacksTo(16)));
    public static final DeferredItem<ShelterItem> CARDBOARD_SHELTER=ITEMS.register("cardboard_shelter",()->new ShelterItem(ShelterBlock.Kind.CARDBOARD,new Item.Properties().stacksTo(16)));

    public static final DeferredItem<DeferredSpawnEggItem> REIMU_SPAWN_EGG = registerSpawnEgg(YukkuriVariant.REIMU);
    public static final DeferredItem<DeferredSpawnEggItem> MARISA_SPAWN_EGG = registerSpawnEgg(YukkuriVariant.MARISA);
    public static final DeferredItem<DeferredSpawnEggItem> HOUSE_REIMU_SPAWN_EGG = registerSpawnEgg(YukkuriVariant.HOUSE_REIMU);
    public static final DeferredItem<DeferredSpawnEggItem> HOUSE_MARISA_SPAWN_EGG = registerSpawnEgg(YukkuriVariant.HOUSE_MARISA);

    public static final DeferredItem<DeferredSpawnEggItem> CHEN_SPAWN_EGG = registerSpawnEgg(YukkuriVariant.CHEN);
    public static final DeferredItem<DeferredSpawnEggItem> ALICE_SPAWN_EGG = registerSpawnEgg(YukkuriVariant.ALICE);
    public static final DeferredItem<DeferredSpawnEggItem> PATCHOULI_SPAWN_EGG = registerSpawnEgg(YukkuriVariant.PATCHOULI);
    public static final DeferredItem<AccessoryItem> CHEN_HAT = registerAccessory(YukkuriVariant.Accessory.CHEN_HAT);
    public static final DeferredItem<AccessoryItem> ALICE_HEADBAND = registerAccessory(YukkuriVariant.Accessory.ALICE_HEADBAND);
    public static final DeferredItem<AccessoryItem> PATCHOULI_BONNET = registerAccessory(YukkuriVariant.Accessory.PATCHOULI_BONNET);
    public static final DeferredItem<DeferredSpawnEggItem> REMILIA_SPAWN_EGG = registerSpawnEgg(YukkuriVariant.REMILIA);
    public static final DeferredItem<DeferredSpawnEggItem> FLANDRE_SPAWN_EGG = registerSpawnEgg(YukkuriVariant.FLANDRE);
    public static final DeferredItem<AccessoryItem> REMILIA_BONNET = registerAccessory(YukkuriVariant.Accessory.REMILIA_BONNET);
    public static final DeferredItem<AccessoryItem> FLANDRE_BONNET = registerAccessory(YukkuriVariant.Accessory.FLANDRE_BONNET);
    public static final DeferredItem<BlockItem> PREDATOR_NEST = ITEMS.registerSimpleBlockItem("predator_nest", ModBlocks.PREDATOR_NEST);
    public static final DeferredItem<DeferredSpawnEggItem> MYON_SPAWN_EGG = registerSpawnEgg(YukkuriVariant.MYON);
    public static final DeferredItem<AccessoryItem> MYON_HAIRBAND = registerAccessory(YukkuriVariant.Accessory.MYON_HAIRBAND);
    /** The kotatsu (table board and quilt) that a kotatsu Marisa wears instead of a hat; like the shell, a subspecies accessory. */
    public static final DeferredItem<AccessoryItem> MARISA_KOTATSU = ITEMS.register("marisa_kotatsu", () -> new AccessoryItem(new Item.Properties()));
    private static DeferredItem<AccessoryItem> registerAccessory(YukkuriVariant.Accessory accessory) {
        var holder = ITEMS.register(accessory.id(), () -> new AccessoryItem(new Item.Properties()));
        ACCESSORIES.put(accessory, holder);
        return holder;
    }
    private static DeferredItem<DeferredSpawnEggItem> registerSpawnEgg(YukkuriVariant variant) {
        var holder = ITEMS.register(variant.spawnEggId(), () -> new DeferredSpawnEggItem(
                ModEntities.holderFor(variant), variant.eggPrimaryColor(), variant.eggSecondaryColor(), new Item.Properties()));
        SPAWN_EGGS.put(variant, holder);
        return holder;
    }
    public static DeferredSpawnEggItem spawnEggFor(YukkuriVariant variant) { return SPAWN_EGGS.get(variant).get(); }
    public static Item accessoryFor(YukkuriVariant variant) { return ACCESSORIES.get(variant.accessory()).get(); }
    public static Item accessoryFor(YukkuriEntity entity) {
        var kind = entity.subspecies().kind();
        return kind == SubspeciesLife.Kind.AQUATIC_MARISA ? AQUATIC_MARISA_HAT.get()
                : kind == SubspeciesLife.Kind.SHELL_MARISA ? MARISA_SHELL.get()
                : kind == SubspeciesLife.Kind.KOTATSU_MARISA ? MARISA_KOTATSU.get()
                : accessoryFor(entity.getVariant());
    }
    /** Canonical loose accessory shape per kind; age, congenital body shape and obesity do not create item variants. */
    public static String accessoryModelFor(YukkuriEntity entity) {
        return switch (entity.subspecies().kind()) {
            case SHELL_MARISA -> "marisa_tsumuri";
            case KOTATSU_MARISA -> "marisa_kotatsu";
            default -> entity.getVariant().assetId();
        };
    }

    private ModItems() {}
}
