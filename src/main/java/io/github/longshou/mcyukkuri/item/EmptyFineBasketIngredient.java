package io.github.longshou.mcyukkuri.item;

import com.mojang.serialization.MapCodec;
import io.github.longshou.mcyukkuri.MCYukkuri;
import io.github.longshou.mcyukkuri.registry.ModItems;
import java.util.function.Supplier;
import java.util.stream.Stream;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.crafting.ICustomIngredient;
import net.neoforged.neoforge.common.crafting.IngredientType;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

public enum EmptyFineBasketIngredient implements ICustomIngredient {
    INSTANCE;

    private static final DeferredRegister<IngredientType<?>> TYPES =
            DeferredRegister.create(NeoForgeRegistries.Keys.INGREDIENT_TYPES, MCYukkuri.MOD_ID);
    private static final Supplier<IngredientType<EmptyFineBasketIngredient>> TYPE =
            TYPES.register("empty_fine_basket", () -> new IngredientType<>(MapCodec.unit(INSTANCE)));

    public static void register(IEventBus bus) { TYPES.register(bus); }

    @Override public boolean test(ItemStack stack) {
        return stack.is(ModItems.FINE_CARRIER.get()) && CarrierContents.tokens(stack).isEmpty();
    }

    @Override public Stream<ItemStack> getItems() { return Stream.of(new ItemStack(ModItems.FINE_CARRIER.get())); }
    @Override public boolean isSimple() { return false; }
    @Override public IngredientType<?> getType() { return TYPE.get(); }
}
