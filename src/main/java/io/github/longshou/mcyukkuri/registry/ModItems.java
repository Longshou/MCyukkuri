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
import net.neoforged.neoforge.common.DeferredSpawnEggItem;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModItems {
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MCYukkuri.MOD_ID);
    private static final java.util.Map<YukkuriVariant.Accessory, DeferredItem<AccessoryItem>> ACCESSORIES = new java.util.EnumMap<>(YukkuriVariant.Accessory.class);
    private static final java.util.Map<YukkuriVariant, DeferredItem<DeferredSpawnEggItem>> SPAWN_EGGS = new java.util.EnumMap<>(YukkuriVariant.class);
    public static final java.util.Map<io.github.longshou.mcyukkuri.item.OperationToolItem.Tool,DeferredItem<io.github.longshou.mcyukkuri.item.OperationToolItem>> OPERATION_TOOLS=new java.util.EnumMap<>(io.github.longshou.mcyukkuri.item.OperationToolItem.Tool.class);
    static {
        for(var tool:io.github.longshou.mcyukkuri.item.OperationToolItem.Tool.values())OPERATION_TOOLS.put(tool,ITEMS.register(tool.id,()->new io.github.longshou.mcyukkuri.item.OperationToolItem(tool,
                tool.inserted?new Item.Properties().stacksTo(16):new Item.Properties().durability(128))));
    }
    public static final DeferredItem<Item> TRIMMED_HAIR=ITEMS.registerSimpleItem("trimmed_hair",new Item.Properties());

    public static final DeferredItem<NotebookItem> FIELD_NOTEBOOK = ITEMS.register("field_notebook", () -> new NotebookItem(new Item.Properties().stacksTo(1)));
    public static final DeferredItem<CarrierItem> CARRIER = ITEMS.register("yukkuri_carrier", () -> new CarrierItem(new Item.Properties().stacksTo(1)));
    public static final DeferredItem<WashClothItem> WASH_CLOTH = ITEMS.register("wash_cloth", () -> new WashClothItem(new Item.Properties().stacksTo(1)));
    public static final DeferredItem<io.github.longshou.mcyukkuri.item.HouseholdSprayItem> HOUSEHOLD_GUARD=ITEMS.register("household_guard",()->new io.github.longshou.mcyukkuri.item.HouseholdSprayItem(new Item.Properties().stacksTo(1)));
    public static final DeferredItem<TeachingWhistleItem> WHISTLE = ITEMS.register("teaching_whistle", () -> new TeachingWhistleItem(new Item.Properties().stacksTo(1)));
    public static final DeferredItem<AccessoryItem> RIBBON = registerAccessory(YukkuriVariant.Accessory.RIBBON);
    public static final DeferredItem<AccessoryItem> HAT = registerAccessory(YukkuriVariant.Accessory.HAT);
    public static final DeferredItem<AccessoryItem> MARISA_SHELL = ITEMS.register("marisa_shell", () -> new AccessoryItem(new Item.Properties().stacksTo(1)));
    public static final DeferredItem<Item> TOY = ITEMS.registerSimpleItem("yarn_ball", new Item.Properties().stacksTo(16));
    public static final DeferredItem<io.github.longshou.mcyukkuri.item.SupplyItem> WASTE = ITEMS.register("yukkuri_waste",()->new io.github.longshou.mcyukkuri.item.SupplyItem(new Item.Properties()));
    public static final DeferredItem<io.github.longshou.mcyukkuri.item.SupplyItem> FOOD_SCRAP=ITEMS.register("food_scrap",()->new io.github.longshou.mcyukkuri.item.SupplyItem(new Item.Properties()));
    public static final DeferredItem<io.github.longshou.mcyukkuri.item.SupplyItem> NEST_LINT=ITEMS.register("nest_lint",()->new io.github.longshou.mcyukkuri.item.SupplyItem(new Item.Properties()));
    public static final DeferredItem<io.github.longshou.mcyukkuri.item.HouseEggItem> HOUSE_EGG=ITEMS.register("house_egg",
            ()->new io.github.longshou.mcyukkuri.item.HouseEggItem(new Item.Properties().stacksTo(1)));
    public static final DeferredItem<Item> YUKKURI_STEM=ITEMS.registerSimpleItem("yukkuri_stem",new Item.Properties());
    public static final DeferredItem<io.github.longshou.mcyukkuri.item.FillingItem> FILLING=ITEMS.register("leaked_filling",()->new io.github.longshou.mcyukkuri.item.FillingItem(new Item.Properties()));
    public static final DeferredItem<Item> GRAIN_BUDGET=ITEMS.registerSimpleItem("oil_grain_budget",new Item.Properties());
    public static final DeferredItem<Item> GRAIN_REGULAR=ITEMS.registerSimpleItem("oil_grain_regular",new Item.Properties());
    public static final DeferredItem<Item> GRAIN_PREMIUM=ITEMS.registerSimpleItem("oil_grain_premium",new Item.Properties());
    // Sweets (docs/PET_SHOP.md section 12): placeholder icons, tagged mcyukkuri:yukkuri_sweets together with the vanilla cookie.
    public static final DeferredItem<Item> SWEET_CANDY=ITEMS.registerSimpleItem("sweet_candy",new Item.Properties());
    public static final DeferredItem<Item> CUSTARD_PUDDING=ITEMS.registerSimpleItem("custard_pudding",new Item.Properties().stacksTo(16));
    public static final DeferredItem<io.github.longshou.mcyukkuri.item.TreatmentItem> ORANGE_JUICE=ITEMS.register("orange_juice",()->new io.github.longshou.mcyukkuri.item.TreatmentItem(io.github.longshou.mcyukkuri.item.TreatmentItem.Kind.ORANGE_JUICE,new Item.Properties().stacksTo(16)));
    public static final DeferredItem<io.github.longshou.mcyukkuri.item.TreatmentItem> MOLD_REMOVER=ITEMS.register("mold_remover",()->new io.github.longshou.mcyukkuri.item.TreatmentItem(io.github.longshou.mcyukkuri.item.TreatmentItem.Kind.MOLD_REMOVER,new Item.Properties().stacksTo(16)));
    public static final DeferredItem<io.github.longshou.mcyukkuri.item.TreatmentItem> BANDAGE=ITEMS.register("bandage",()->new io.github.longshou.mcyukkuri.item.TreatmentItem(false,new Item.Properties()));
    public static final DeferredItem<io.github.longshou.mcyukkuri.item.TreatmentItem> MEDICINE=ITEMS.register("herbal_medicine",()->new io.github.longshou.mcyukkuri.item.TreatmentItem(true,new Item.Properties()));
    public static final DeferredItem<io.github.longshou.mcyukkuri.item.RemainsItem> REMAINS=ITEMS.register("yukkuri_remains",()->new io.github.longshou.mcyukkuri.item.RemainsItem(new Item.Properties().stacksTo(1)));
    public static final DeferredItem<io.github.longshou.mcyukkuri.item.PetHarnessItem> PET_HARNESS = ITEMS.register("pet_harness",
            () -> new io.github.longshou.mcyukkuri.item.PetHarnessItem(new Item.Properties().stacksTo(16)));
    public static final DeferredItem<io.github.longshou.mcyukkuri.item.SwaddleItem> SWADDLE_CLOTH = ITEMS.register("swaddle_cloth",
            () -> new io.github.longshou.mcyukkuri.item.SwaddleItem(new Item.Properties().stacksTo(16)));
    public static final DeferredItem<io.github.longshou.mcyukkuri.item.CompanionCordItem> COMPANION_CORD = ITEMS.register("companion_cord",
            () -> new io.github.longshou.mcyukkuri.item.CompanionCordItem(new Item.Properties().stacksTo(1)));
    public static final DeferredItem<BlockItem> FOOD_BOWL = ITEMS.registerSimpleBlockItem("food_bowl", ModBlocks.FOOD_BOWL);
    public static final DeferredItem<BlockItem> NEST = ITEMS.registerSimpleBlockItem("yukkuri_nest", ModBlocks.NEST);
    public static final DeferredItem<BlockItem> TOILET = ITEMS.registerSimpleBlockItem("yukkuri_toilet", ModBlocks.TOILET);
    public static final DeferredItem<BlockItem> WILD_LATRINE=ITEMS.registerSimpleBlockItem("wild_latrine",ModBlocks.WILD_LATRINE);
    public static final DeferredItem<ShelterItem> FOREST_BURROW=ITEMS.register("forest_burrow",()->new ShelterItem(io.github.longshou.mcyukkuri.settlement.ShelterBlock.Kind.BURROW,new Item.Properties().stacksTo(16)));
    public static final DeferredItem<ShelterItem> CARDBOARD_SHELTER=ITEMS.register("cardboard_shelter",()->new ShelterItem(io.github.longshou.mcyukkuri.settlement.ShelterBlock.Kind.CARDBOARD,new Item.Properties().stacksTo(16)));

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
    private static DeferredItem<AccessoryItem> registerAccessory(YukkuriVariant.Accessory accessory) {
        var holder = ITEMS.register(accessory.id(), () -> new AccessoryItem(new Item.Properties().stacksTo(1)));
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
    public static Item accessoryFor(io.github.longshou.mcyukkuri.entity.YukkuriEntity entity) {
        return entity.subspecies().kind() == io.github.longshou.mcyukkuri.subspecies.SubspeciesLife.Kind.SHELL_MARISA
                ? MARISA_SHELL.get() : accessoryFor(entity.getVariant());
    }

    private ModItems() {}
}
