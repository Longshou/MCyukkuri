package io.github.longshou.mcyukkuri.item;

import io.github.longshou.mcyukkuri.body.BodyLife;
import io.github.longshou.mcyukkuri.entity.YukkuriVariant;
import io.github.longshou.mcyukkuri.family.FamilyLife;
import io.github.longshou.mcyukkuri.headwear.Headwear;
import io.github.longshou.mcyukkuri.headwear.JunkHeadwear;
import io.github.longshou.mcyukkuri.life.YukkuriWorldData;
import io.github.longshou.mcyukkuri.registry.ModItems;
import io.github.longshou.mcyukkuri.subspecies.SubspeciesLife;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

/** An ordered list of custody tokens and their read-only display summaries. */
public final class CarrierContents {
    private static final String OCCUPANTS = "BasketOccupants";
    private static final String LEGACY_TOKEN = "CaptiveToken";

    private CarrierContents() {}

    public static boolean isBasket(ItemStack stack) { return stack.getItem() instanceof CarrierItem; }
    private static CompoundTag data(ItemStack stack) {
        return stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
    }

    private static ListTag entries(CompoundTag data) {
        if (!data.hasUUID(LEGACY_TOKEN)) return data.getList(OCCUPANTS, Tag.TAG_COMPOUND);
        var entries = new ListTag();
        var legacy = new CompoundTag();
        legacy.putUUID("Token", data.getUUID(LEGACY_TOKEN));
        entries.add(legacy);
        return entries;
    }

    public static List<UUID> tokens(ItemStack stack) {
        if (!isBasket(stack)) return List.of();
        return entries(data(stack)).stream().map(CompoundTag.class::cast)
                .filter(entry -> entry.hasUUID("Token")).map(entry -> entry.getUUID("Token")).toList();
    }

    public static UUID singleToken(ItemStack stack) {
        var tokens = tokens(stack);
        return tokens.size() == 1 ? tokens.getFirst() : null;
    }

    public static ItemStack plain(YukkuriWorldData world, UUID token) {
        var basket = new ItemStack(ModItems.CARRIER.get());
        add(basket, token, world.captiveSnapshot(token));
        return basket;
    }

    public static void add(ItemStack stack, UUID token, CompoundTag snapshot) {
        var data = data(stack);
        var entries = entries(data);
        entries.add(entry(token, snapshot));
        write(stack, data, entries);
    }

    public static void remove(ItemStack stack, UUID token) {
        var data = data(stack);
        var entries = entries(data);
        entries.removeIf(value -> ((CompoundTag) value).hasUUID("Token") && ((CompoundTag) value).getUUID("Token").equals(token));
        write(stack, data, entries);
    }

    static boolean needsAppearance(ItemStack stack) {
        var data = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).getUnsafe();
        if (data.hasUUID(LEGACY_TOKEN)) return true;
        if (data.get(OCCUPANTS) instanceof ListTag entries) {
            for (int i = 0; i < entries.size(); i++) {
                var entry = entries.getCompound(i);
                if (entry.hasUUID("Token") && !entry.contains("Model")) return true;
            }
        }
        return false;
    }

    /** Upgrade old summaries using the original captive records. */
    static boolean refreshAppearance(ItemStack stack, YukkuriWorldData world) {
        var data = data(stack);
        var entries = entries(data);
        boolean changed = false;
        for (int i = 0; i < entries.size(); i++) {
            var entry = entries.getCompound(i);
            if (entry.contains("Model") || !entry.hasUUID("Token")) continue;
            var token = entry.getUUID("Token");
            var snapshot = world.captiveSnapshot(token);
            if (snapshot.isEmpty()) continue;
            entries.set(i, entry(token, snapshot));
            changed = true;
        }
        if (changed) write(stack, data, entries);
        return changed;
    }

    /** The client reads these tags directly as an immutable projection of the item component. */
    public static ListTag appearances(CustomData data) {
        return data.getUnsafe().getList(OCCUPANTS, Tag.TAG_COMPOUND);
    }

    private static void write(ItemStack stack, CompoundTag data, ListTag entries) {
        if (data.hasUUID(LEGACY_TOKEN)) {
            data.remove(LEGACY_TOKEN);
            data.remove("EntityUUID");
            data.remove("Variant");
        }
        if (entries.isEmpty()) data.remove(OCCUPANTS);
        else data.put(OCCUPANTS, entries);
        CustomData.set(DataComponents.CUSTOM_DATA, stack, data);
    }

    static String stage(CompoundTag snapshot) {
        return FamilyLife.savedStageId(snapshot.getCompound("YukkuriFamily"));
    }

    private static CompoundTag entry(UUID token, CompoundTag snapshot) {
        var entry = new CompoundTag();
        entry.putUUID("Token", token);
        entry.putString("Species", snapshot.getString("id").replace(':', '.'));
        entry.putString("Stage", stage(snapshot));
        var pet = snapshot.getCompound("YukkuriPet");
        entry.putInt("Badge", pet.getInt("Grade"));
        entry.putBoolean("BadgeWorn", pet.getBoolean("Worn"));
        if (snapshot.contains("CustomName", Tag.TAG_STRING)) entry.putString("Name", snapshot.getString("CustomName"));
        var life = snapshot.getCompound("YukkuriLife");
        entry.putBoolean("Hungry", life.getFloat("Nutrition") < 30);
        entry.putBoolean("Hurt", snapshot.getBoolean("TransportHurt") || BodyLife.savedInjury(snapshot.getCompound("YukkuriBody")));
        entry.putBoolean("Asleep", snapshot.getBoolean("TransportAsleep") || life.getInt("DrugSleep") > 0);
        var savedForm = snapshot.getCompound("YukkuriSubspecies");
        var kind = savedForm.contains("Kind") ? SubspeciesLife.Kind.valueOf(savedForm.getString("Kind")) : SubspeciesLife.Kind.NORMAL;
        var form = savedForm.contains("Congenital") ? SubspeciesLife.Congenital.valueOf(savedForm.getString("Congenital")) : SubspeciesLife.Congenital.NORMAL;
        boolean deibu = savedForm.getBoolean("Deibu");
        int flags = kind.ordinal() | form.ordinal() << 4 | (deibu ? SubspeciesLife.DEIBU_FLAG : 0);
        var variant = YukkuriVariant.valueOf(snapshot.getString("id").substring("mcyukkuri:".length()).toUpperCase(Locale.ROOT));
        float growth = Math.clamp(snapshot.getCompound("YukkuriFamily").getFloat("Scale"), .1f, 1.2f);
        boolean accessory = !life.contains("Accessory") || life.getBoolean("Accessory");
        var headwear = snapshot.getCompound("YukkuriHeadwear");
        var junk = JunkHeadwear.read(life.getCompound("HatCustody"));
        entry.putString("Model", SubspeciesLife.assetId(variant, flags));
        entry.putInt("Form", flags);
        entry.putFloat("RenderScale", growth * (deibu ? 1.5f : 1f) * attributeScale(snapshot));
        entry.putInt("HairLoss", snapshot.getCompound("YukkuriBody").getInt("HairLoss"));
        entry.putBoolean("Accessory", accessory);
        entry.putInt("Headwear", Headwear.savedVisual(headwear, accessory, growth, junk));
        return entry;
    }

    private static float attributeScale(CompoundTag snapshot) {
        String scaleId = Attributes.SCALE.unwrapKey().orElseThrow().location().toString();
        var attributes = snapshot.getList(LivingEntity.ATTRIBUTES_FIELD, Tag.TAG_COMPOUND);
        for (int i = 0; i < attributes.size(); i++) {
            var saved = attributes.getCompound(i);
            if (!scaleId.equals(saved.getString(AttributeInstance.ID_FIELD))) continue;
            var scale = new AttributeInstance(Attributes.SCALE, ignored -> {});
            scale.load(saved);
            return (float) scale.getValue();
        }
        return (float) Attributes.SCALE.value().getDefaultValue();
    }

    static void appendOccupants(ItemStack stack, HolderLookup.Provider registries, List<Component> tooltip) {
        for (Tag value : entries(data(stack))) {
            var entry = (CompoundTag) value;
            if (!entry.contains("Species")) {
                tooltip.add(Component.translatable("care.mcyukkuri.carrier.unknown"));
                continue;
            }
            var species = Component.translatable("entity." + entry.getString("Species"));
            var stage = Component.translatable("stage.mcyukkuri." + entry.getString("Stage"));
            var badge = Component.translatable("badge.mcyukkuri.profile.grade." + entry.getInt("Badge"));
            var state = Component.empty();
            for (String sign : List.of("Hungry", "Hurt", "Asleep")) {
                if (!entry.getBoolean(sign)) continue;
                if (!state.getSiblings().isEmpty()) state.append(" / ");
                state.append(Component.translatable("care.mcyukkuri.carrier.state." + sign));
            }
            if (state.getSiblings().isEmpty()) state.append(Component.translatable("care.mcyukkuri.carrier.state.Settled"));
            var name = entry.contains("Name") && registries != null ? Component.Serializer.fromJson(entry.getString("Name"), registries) : null;
            tooltip.add(name == null ? Component.translatable("care.mcyukkuri.carrier.occupant", species, stage, badge, state)
                    : Component.translatable("care.mcyukkuri.carrier.named_occupant", name, species, stage, badge, state));
        }
    }
}
