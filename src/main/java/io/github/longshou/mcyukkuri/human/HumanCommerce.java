package io.github.longshou.mcyukkuri.human;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.item.CarrierItem;
import io.github.longshou.mcyukkuri.life.YukkuriWorldData;
import io.github.longshou.mcyukkuri.registry.ModItems;
import java.util.UUID;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

/** Custody metadata is inside entity NeoForgeData, so carrier capture keeps exactly the same identity. */
public final class HumanCommerce {
    public static CompoundTag data(YukkuriEntity animal) { return animal.getPersistentData().getCompound("HumanCommerce"); }
    public static void set(YukkuriEntity animal, CompoundTag tag) { animal.getPersistentData().put("HumanCommerce", tag.copy()); }
    public static boolean ownedBy(YukkuriEntity animal, UUID id) { var t = data(animal); return t.hasUUID("Owner") && t.getUUID("Owner").equals(id); }
    public static void assign(YukkuriEntity animal, UUID owner, String use) {
        var tag = data(animal).copy(); tag.remove("PendingRole"); tag.putUUID("Owner", owner); tag.putString("Use", use); set(animal, tag);
        if(use.equals("companion")||use.equals("stock")||use.equals("reserve_parent")) {
            var clean=data(animal).copy();for(String key:java.util.List.of("FactoryStage","StageWork","LastFactoryWork","FactoryYoung","HoldReason"))clean.remove(key);set(animal,clean);
        }
        animal.setPersistenceRequired();
    }
    public static boolean protectedAnimal(YukkuriEntity animal) {
        var t = data(animal);
        if (t.hasUUID("Owner") || t.contains("PendingRole") || animal.life().home() != null && !animal.life().naturalHome()) return true;
        for (var v : animal.life().personality().save().getList("Players", 10))
            if (((CompoundTag)v).getInt("Trust") >= 10) return true;
        return false;
    }
    public static boolean mayCapture(Player player, YukkuriEntity animal) {
        var tag = data(animal);
        return !tag.contains("PendingRole") && (!tag.hasUUID("Owner") || tag.getUUID("Owner").equals(player.getUUID())
                || tag.getString("Use").equals("lost"));
    }
    public static ItemStack carrier(YukkuriEntity animal, UUID token) {
        ItemStack result = new ItemStack(ModItems.CARRIER.get());
        CustomData.update(DataComponents.CUSTOM_DATA, result, tag -> {
            tag.putUUID("CaptiveToken", token); tag.putUUID("EntityUUID", animal.getUUID()); tag.putString("Variant", animal.getVariant().assetId());
        });
        return result;
    }
    public static CompoundTag heldData(ServerLevel level, ItemStack stack) {
        UUID token = CarrierItem.token(stack);
        if (!stack.is(ModItems.CARRIER.get()) || token == null) return new CompoundTag();
        return YukkuriWorldData.get(level).captiveSnapshot(token);
    }
    public static void prepareBreederHome(YukkuriEntity animal) {
        if(!(animal.level() instanceof ServerLevel level)||!data(animal).getString("Use").equals("breeder")||animal.life().homeStand()!=null)return;
        for(var pos:net.minecraft.core.BlockPos.betweenClosed(animal.blockPosition().offset(-3,0,-3),animal.blockPosition().offset(3,0,3)))
            if(animal.life().assignHome(net.minecraft.core.GlobalPos.of(level.dimension(),pos.immutable())))return;
    }
    /** Called at authoritative birth, before a completed parent can retire or unload. */
    public static void inheritFactoryChild(YukkuriEntity child, YukkuriEntity mother) {
        if(child==null || mother==null || data(child).hasUUID("Owner"))return;
        var parent=data(mother);
        if(parent.hasUUID("Owner") && (parent.getString("Use").equals("breeder") || parent.getBoolean("RetiredParent")))
            assign(child,parent.getUUID("Owner"),"nursery");
    }
    public static void tickAnimal(EntityTickEvent.Post e) {
        if (!(e.getEntity() instanceof YukkuriEntity animal) || !(animal.level() instanceof ServerLevel level) || animal.tickCount % 20 != 0) return;
        var tag = data(animal); String role = tag.getString("PendingRole");
        if (role.isEmpty()) {
            prepareBreederHome(animal);
            if (!tag.hasUUID("Owner") && animal.family().mother()!=null && level.getEntity(animal.family().mother()) instanceof YukkuriEntity mother) {
                inheritFactoryChild(animal,mother);
            }
            return;
        }
        level.getEntitiesOfClass(HumanNpc.class, animal.getBoundingBox().inflate(48), n -> n.isAlive() && n.siteRole().equals(role)).stream()
                .min(java.util.Comparator.comparingDouble(animal::distanceToSqr)).ifPresent(npc -> {
                    assign(animal, npc.getUUID(), tag.getString("Use"));
                    if (tag.getString("Use").equals("lost")) npc.setLostAnimal(animal.getUUID(), animal.blockPosition());
                    if (tag.getString("Use").equals("stock")) {
                        int tier = Math.clamp(tag.getInt("Medal"),0,2);
                        if (tier == 0) animal.life().personality().lessonCompleted(npc.getUUID());
                        else animal.badges().initializeStock(tier, npc.getUUID());
                    }
                    if (tag.getString("Use").equals("breeder")) {
                        prepareBreederHome(animal);
                        for(var other:npc.animals("breeder"))if(other!=animal && data(other).getInt("FactoryGroup")==tag.getInt("FactoryGroup")){
                            YukkuriWorldData.get(level).relate(animal.getUUID(),other.getUUID(),20);
                            YukkuriWorldData.get(level).relate(other.getUUID(),animal.getUUID(),20);
                        }
                    }
                });
    }
    private HumanCommerce() {}
}
