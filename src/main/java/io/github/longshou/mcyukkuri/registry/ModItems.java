package io.github.longshou.mcyukkuri.registry;

import io.github.longshou.mcyukkuri.MCYukkuri;
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
    public static final java.util.Map<io.github.longshou.mcyukkuri.item.OperationToolItem.Tool,DeferredItem<io.github.longshou.mcyukkuri.item.OperationToolItem>> OPERATION_TOOLS=new java.util.EnumMap<>(io.github.longshou.mcyukkuri.item.OperationToolItem.Tool.class);
    static {
        for(var tool:io.github.longshou.mcyukkuri.item.OperationToolItem.Tool.values())OPERATION_TOOLS.put(tool,ITEMS.register(tool.id,()->new io.github.longshou.mcyukkuri.item.OperationToolItem(tool,
                tool.inserted?new Item.Properties().stacksTo(16):new Item.Properties().durability(128))));
    }
    public static final DeferredItem<Item> TRIMMED_HAIR=ITEMS.registerSimpleItem("trimmed_hair",new Item.Properties());

    public static final DeferredItem<NotebookItem> FIELD_NOTEBOOK = ITEMS.register("field_notebook", () -> new NotebookItem(new Item.Properties().stacksTo(1)));
    public static final DeferredItem<CarrierItem> CARRIER = ITEMS.register("yukkuri_carrier", () -> new CarrierItem(new Item.Properties().stacksTo(1)));
    public static final DeferredItem<WashClothItem> WASH_CLOTH = ITEMS.register("wash_cloth", () -> new WashClothItem(new Item.Properties().stacksTo(1)));
    public static final DeferredItem<TeachingWhistleItem> WHISTLE = ITEMS.register("teaching_whistle", () -> new TeachingWhistleItem(new Item.Properties().stacksTo(1)));
    public static final DeferredItem<AccessoryItem> RIBBON = ITEMS.register("reimu_ribbon", () -> new AccessoryItem(new Item.Properties().stacksTo(1)));
    public static final DeferredItem<AccessoryItem> HAT = ITEMS.register("marisa_hat", () -> new AccessoryItem(new Item.Properties().stacksTo(1)));
    public static final DeferredItem<AccessoryItem> MARISA_SHELL = ITEMS.register("marisa_shell", () -> new AccessoryItem(new Item.Properties().stacksTo(1)));
    public static final DeferredItem<Item> TOY = ITEMS.registerSimpleItem("yarn_ball", new Item.Properties().stacksTo(16));
    public static final DeferredItem<Item> WASTE = ITEMS.registerSimpleItem("yukkuri_waste", new Item.Properties());
    public static final DeferredItem<Item> FOOD_SCRAP=ITEMS.registerSimpleItem("food_scrap",new Item.Properties());
    public static final DeferredItem<Item> YUKKURI_STEM=ITEMS.registerSimpleItem("yukkuri_stem",new Item.Properties());
    public static final DeferredItem<io.github.longshou.mcyukkuri.item.FillingItem> FILLING=ITEMS.register("leaked_filling",()->new io.github.longshou.mcyukkuri.item.FillingItem(new Item.Properties()));
    public static final DeferredItem<Item> GRAIN_BUDGET=ITEMS.registerSimpleItem("oil_grain_budget",new Item.Properties());
    public static final DeferredItem<Item> GRAIN_REGULAR=ITEMS.registerSimpleItem("oil_grain_regular",new Item.Properties());
    public static final DeferredItem<Item> GRAIN_PREMIUM=ITEMS.registerSimpleItem("oil_grain_premium",new Item.Properties());
    public static final DeferredItem<io.github.longshou.mcyukkuri.item.TreatmentItem> ORANGE_JUICE=ITEMS.register("orange_juice",()->new io.github.longshou.mcyukkuri.item.TreatmentItem(io.github.longshou.mcyukkuri.item.TreatmentItem.Kind.ORANGE_JUICE,new Item.Properties().stacksTo(16)));
    public static final DeferredItem<io.github.longshou.mcyukkuri.item.TreatmentItem> MOLD_REMOVER=ITEMS.register("mold_remover",()->new io.github.longshou.mcyukkuri.item.TreatmentItem(io.github.longshou.mcyukkuri.item.TreatmentItem.Kind.MOLD_REMOVER,new Item.Properties().stacksTo(16)));
    public static final DeferredItem<io.github.longshou.mcyukkuri.item.TreatmentItem> BANDAGE=ITEMS.register("bandage",()->new io.github.longshou.mcyukkuri.item.TreatmentItem(false,new Item.Properties()));
    public static final DeferredItem<io.github.longshou.mcyukkuri.item.TreatmentItem> MEDICINE=ITEMS.register("herbal_medicine",()->new io.github.longshou.mcyukkuri.item.TreatmentItem(true,new Item.Properties()));
    public static final DeferredItem<io.github.longshou.mcyukkuri.item.RemainsItem> REMAINS=ITEMS.register("yukkuri_remains",()->new io.github.longshou.mcyukkuri.item.RemainsItem(new Item.Properties().stacksTo(1)));
    public static final DeferredItem<BlockItem> HOUSE_NEST=ITEMS.registerSimpleBlockItem("house_crevice",ModBlocks.HOUSE_NEST);
    public static final DeferredItem<io.github.longshou.mcyukkuri.item.CompanionCordItem> COMPANION_CORD = ITEMS.register("companion_cord",
            () -> new io.github.longshou.mcyukkuri.item.CompanionCordItem(new Item.Properties().stacksTo(1)));
    public static final DeferredItem<BlockItem> FOOD_BOWL = ITEMS.registerSimpleBlockItem("food_bowl", ModBlocks.FOOD_BOWL);
    public static final DeferredItem<BlockItem> NEST = ITEMS.registerSimpleBlockItem("yukkuri_nest", ModBlocks.NEST);
    public static final DeferredItem<BlockItem> TOILET = ITEMS.registerSimpleBlockItem("yukkuri_toilet", ModBlocks.TOILET);
    public static final DeferredItem<BlockItem> WILD_LATRINE=ITEMS.registerSimpleBlockItem("wild_latrine",ModBlocks.WILD_LATRINE);
    public static final DeferredItem<ShelterItem> FOREST_BURROW=ITEMS.register("forest_burrow",()->new ShelterItem(io.github.longshou.mcyukkuri.settlement.ShelterBlock.Kind.BURROW,new Item.Properties().stacksTo(16)));
    public static final DeferredItem<ShelterItem> CARDBOARD_SHELTER=ITEMS.register("cardboard_shelter",()->new ShelterItem(io.github.longshou.mcyukkuri.settlement.ShelterBlock.Kind.CARDBOARD,new Item.Properties().stacksTo(16)));

    public static final DeferredItem<DeferredSpawnEggItem> REIMU_SPAWN_EGG = ITEMS.register(
            "reimu_spawn_egg", () -> new DeferredSpawnEggItem(
                    ModEntities.REIMU, 0xF1D5AA, 0xB52E36, new Item.Properties()));
    public static final DeferredItem<DeferredSpawnEggItem> MARISA_SPAWN_EGG = ITEMS.register(
            "marisa_spawn_egg", () -> new DeferredSpawnEggItem(
                    ModEntities.MARISA, 0xF1D5AA, 0x342D38, new Item.Properties()));
    public static final DeferredItem<DeferredSpawnEggItem> HOUSE_REIMU_SPAWN_EGG = ITEMS.register(
            "house_reimu_spawn_egg", () -> new DeferredSpawnEggItem(
                    ModEntities.HOUSE_REIMU, 0xD9C39D, 0xB52E36, new Item.Properties()));
    public static final DeferredItem<DeferredSpawnEggItem> HOUSE_MARISA_SPAWN_EGG = ITEMS.register(
            "house_marisa_spawn_egg", () -> new DeferredSpawnEggItem(
                    ModEntities.HOUSE_MARISA, 0xD9C39D, 0x342D38, new Item.Properties()));

    public static final DeferredItem<DeferredSpawnEggItem> CHEN_SPAWN_EGG = ITEMS.register(
            "chen_spawn_egg", () -> new DeferredSpawnEggItem(ModEntities.CHEN, 0xF1D5AA, 0x497647, new Item.Properties()));
    public static final DeferredItem<DeferredSpawnEggItem> ALICE_SPAWN_EGG = ITEMS.register(
            "alice_spawn_egg", () -> new DeferredSpawnEggItem(ModEntities.ALICE, 0xF1D5AA, 0xC93D4E, new Item.Properties()));
    public static final DeferredItem<DeferredSpawnEggItem> PATCHOULI_SPAWN_EGG = ITEMS.register(
            "patchouli_spawn_egg", () -> new DeferredSpawnEggItem(ModEntities.PATCHOULI, 0xF1D5AA, 0xAD87BA, new Item.Properties()));
    public static final DeferredItem<AccessoryItem> CHEN_HAT = ITEMS.register("chen_hat", () -> new AccessoryItem(new Item.Properties().stacksTo(1)));
    public static final DeferredItem<AccessoryItem> ALICE_HEADBAND = ITEMS.register("alice_headband", () -> new AccessoryItem(new Item.Properties().stacksTo(1)));
    public static final DeferredItem<AccessoryItem> PATCHOULI_BONNET = ITEMS.register("patchouli_bonnet", () -> new AccessoryItem(new Item.Properties().stacksTo(1)));
    public static final DeferredItem<DeferredSpawnEggItem> REMILIA_SPAWN_EGG = ITEMS.register(
            "remilia_spawn_egg", () -> new DeferredSpawnEggItem(ModEntities.REMILIA, 0xA8C4E8, 0xC63642, new Item.Properties()));
    public static final DeferredItem<DeferredSpawnEggItem> FLANDRE_SPAWN_EGG = ITEMS.register(
            "flandre_spawn_egg", () -> new DeferredSpawnEggItem(ModEntities.FLANDRE, 0xEFCA65, 0xD03848, new Item.Properties()));
    public static final DeferredItem<AccessoryItem> REMILIA_BONNET = ITEMS.register("remilia_bonnet", () -> new AccessoryItem(new Item.Properties().stacksTo(1)));
    public static final DeferredItem<AccessoryItem> FLANDRE_BONNET = ITEMS.register("flandre_bonnet", () -> new AccessoryItem(new Item.Properties().stacksTo(1)));
    public static final DeferredItem<BlockItem> PREDATOR_NEST = ITEMS.registerSimpleBlockItem("predator_nest", ModBlocks.PREDATOR_NEST);
    public static Item accessoryFor(io.github.longshou.mcyukkuri.entity.YukkuriVariant variant) {
        return switch (variant) {
            case REIMU, HOUSE_REIMU -> RIBBON.get(); case MARISA, HOUSE_MARISA -> HAT.get();
            case CHEN -> CHEN_HAT.get(); case ALICE -> ALICE_HEADBAND.get(); case PATCHOULI -> PATCHOULI_BONNET.get();
            case REMILIA -> REMILIA_BONNET.get(); case FLANDRE -> FLANDRE_BONNET.get();
        };
    }
    public static Item accessoryFor(io.github.longshou.mcyukkuri.entity.YukkuriEntity entity) {
        return entity.subspecies().kind() == io.github.longshou.mcyukkuri.subspecies.SubspeciesLife.Kind.SHELL_MARISA
                ? MARISA_SHELL.get() : accessoryFor(entity.getVariant());
    }

    private ModItems() {}
}
