package io.github.longshou.mcyukkuri.p0;

import io.github.longshou.mcyukkuri.MCYukkuri;
import io.github.longshou.mcyukkuri.entity.YukkuriVariant;
import io.github.longshou.mcyukkuri.registry.ModEntities;
import io.github.longshou.mcyukkuri.registry.ModItems;
import io.github.longshou.mcyukkuri.subspecies.SubspeciesLife;
import java.util.List;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.SpawnPlacementTypes;
import net.minecraft.world.entity.SpawnPlacements;
import net.minecraft.world.entity.ai.attributes.DefaultAttributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SpawnEggItem;
import net.minecraft.world.item.component.CustomData;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Compatibility contract from the pre-refactor registrations, checked against the live server registries. */
@PrefixGameTestTemplate(false)
public final class SpeciesRegistryGameTests {
    private record Expected(String name, String id, String dialogue, boolean house, boolean predator, boolean natural,
                            float width, float height, float eye, String accessory, int eggBase, int eggSpots) {}
    private static final List<Expected> LEGACY = List.of(
            new Expected("REIMU", "reimu", "reimu", false, false, true, .75F, .53F, .28F, "reimu_ribbon", 0xF1D5AA, 0xB52E36),
            new Expected("MARISA", "marisa", "marisa", false, false, true, .75F, .53F, .28F, "marisa_hat", 0xF1D5AA, 0x342D38),
            new Expected("HOUSE_REIMU", "house_reimu", "reimu", true, false, false, .27F, .23875F, .14F, "reimu_ribbon", 0xD9C39D, 0xB52E36),
            new Expected("HOUSE_MARISA", "house_marisa", "marisa", true, false, false, .27F, .23875F, .14F, "marisa_hat", 0xD9C39D, 0x342D38),
            new Expected("CHEN", "chen", "chen", false, false, true, .75F, .53F, .28F, "chen_hat", 0xF1D5AA, 0x497647),
            new Expected("ALICE", "alice", "alice", false, false, true, .75F, .53F, .28F, "alice_headband", 0xF1D5AA, 0xC93D4E),
            new Expected("PATCHOULI", "patchouli", "patchouli", false, false, true, .75F, .53F, .28F, "patchouli_bonnet", 0xF1D5AA, 0xAD87BA),
            new Expected("REMILIA", "remilia", "remilia", false, true, true, .80F, .60F, .34F, "remilia_bonnet", 0xA8C4E8, 0xC63642),
            new Expected("FLANDRE", "flandre", "flandre", false, true, true, .80F, .60F, .34F, "flandre_bonnet", 0xEFCA65, 0xD03848));

    @GameTest(templateNamespace=P0Mod.ID, template="empty", batch="species_registry")
    public static void registeredTypesEggsAndAccessories(GameTestHelper helper) {
        var typeAliases=List.of(ModEntities.REIMU,ModEntities.MARISA,ModEntities.HOUSE_REIMU,ModEntities.HOUSE_MARISA,
                ModEntities.CHEN,ModEntities.ALICE,ModEntities.PATCHOULI,ModEntities.REMILIA,ModEntities.FLANDRE);
        var eggAliases=List.of(ModItems.REIMU_SPAWN_EGG,ModItems.MARISA_SPAWN_EGG,ModItems.HOUSE_REIMU_SPAWN_EGG,ModItems.HOUSE_MARISA_SPAWN_EGG,
                ModItems.CHEN_SPAWN_EGG,ModItems.ALICE_SPAWN_EGG,ModItems.PATCHOULI_SPAWN_EGG,ModItems.REMILIA_SPAWN_EGG,ModItems.FLANDRE_SPAWN_EGG);
        var accessoryAliases=List.of(ModItems.RIBBON,ModItems.HAT,ModItems.RIBBON,ModItems.HAT,ModItems.CHEN_HAT,
                ModItems.ALICE_HEADBAND,ModItems.PATCHOULI_BONNET,ModItems.REMILIA_BONNET,ModItems.FLANDRE_BONNET);
        helper.assertTrue(YukkuriVariant.values().length==9,"The persisted variant domain remains exactly nine entries");
        for(int ordinal=0;ordinal<LEGACY.size();ordinal++) {
            var expected=LEGACY.get(ordinal);var variant=YukkuriVariant.values()[ordinal];
            helper.assertTrue(variant.name().equals(expected.name)&&variant.assetId().equals(expected.id),"Old ordinal and asset identity: "+expected.id);
            helper.assertTrue(variant.isHouse()==expected.house&&variant.isPredator()==expected.predator
                    &&variant.dialogueSpecies().equals(expected.dialogue)&&variant.naturalSpawn()==expected.natural,"Old species classification: "+expected.id);
            var type=ModEntities.forVariant(variant);
            helper.assertTrue(BuiltInRegistries.ENTITY_TYPE.get(MCYukkuri.id(expected.id))==type&&typeAliases.get(ordinal).get()==type
                    &&ModEntities.holderFor(variant)==typeAliases.get(ordinal),"Registry identity and legacy holder alias: "+expected.id);
            var bounds=type.getDimensions();
            helper.assertTrue(bounds.width()==expected.width&&bounds.height()==expected.height&&bounds.eyeHeight()==expected.eye,"Registered dimensions and eye height: "+expected.id);
            helper.assertTrue(DefaultAttributes.hasSupplier(type),"The registered type has server attributes: "+expected.id);
            helper.assertTrue(SpawnPlacements.getPlacementType(type)==(expected.natural?SpawnPlacementTypes.ON_GROUND:SpawnPlacementTypes.NO_RESTRICTIONS),
                    "Only the old seven wild species receive ground spawn placement: "+expected.id);
            var egg=ModItems.spawnEggFor(variant);var stack=egg.getDefaultInstance();
            helper.assertTrue(BuiltInRegistries.ITEM.get(MCYukkuri.id(expected.id+"_spawn_egg"))==egg&&eggAliases.get(ordinal).get()==egg,
                    "Exact registered egg ID and old alias: "+expected.id);
            helper.assertTrue(egg.getType(stack)==type&&SpawnEggItem.byId(type)==egg&&egg.getColor(0)==expected.eggBase&&egg.getColor(1)==expected.eggSpots,
                    "Actual deferred egg type, reverse lookup and colors: "+expected.id);
            var roundTrip=ItemStack.parse(helper.getLevel().registryAccess(),stack.save(helper.getLevel().registryAccess())).orElseThrow();
            helper.assertTrue(roundTrip.is(egg)&&egg.getType(roundTrip)==type,"Normal egg item/registration survives component serialization: "+expected.id);
            var override=stack.copy();var data=new CompoundTag();data.putString("id","minecraft:pig");override.set(DataComponents.ENTITY_DATA,CustomData.of(data));
            override=ItemStack.parse(helper.getLevel().registryAccess(),override.save(helper.getLevel().registryAccess())).orElseThrow();
            helper.assertTrue(egg.getType(override)==EntityType.PIG&&egg.getType(stack)==type,"Entity-data override works without changing this egg's default binding: "+expected.id);
            var accessory=ModItems.accessoryFor(variant);
            helper.assertTrue(BuiltInRegistries.ITEM.get(MCYukkuri.id(expected.accessory))==accessory&&accessoryAliases.get(ordinal).get()==accessory,
                    "Accessory ID and shared old holder: "+expected.id);
            var actor=type.create(helper.getLevel());
            helper.assertTrue(actor!=null,"Registered entity factory constructs a server entity: "+expected.id);
            try {
                helper.assertTrue(actor.getVariant()==variant&&actor.getType()==type&&ModItems.accessoryFor(actor)==accessory,
                        "Live factory and ordinary entity accessory resolve the expected species: "+expected.id);
                if(variant==YukkuriVariant.MARISA) {
                    helper.assertTrue(actor.subspecies().set(SubspeciesLife.Kind.SHELL_MARISA,SubspeciesLife.Congenital.NORMAL),"Existing Marisa can become shell form");
                    helper.assertTrue(ModItems.accessoryFor(actor)==ModItems.MARISA_SHELL.get()
                            &&BuiltInRegistries.ITEM.get(MCYukkuri.id("marisa_shell"))==ModItems.MARISA_SHELL.get(),"Shell form keeps its separate registered shell item");
                }
            } finally { actor.discard(); }
        }
        helper.succeed();
    }
}
