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
        var tag = data(animal).copy(); tag.remove("PendingRole"); if (!use.equals("stock")) { tag.remove("ForSaleSince"); tag.remove("StaleCuts"); } tag.putUUID("Owner", owner); tag.putString("Use", use); set(animal, tag);
        if(use.equals("companion")||use.equals("stock")||use.equals("reserve_parent")) {
            var clean=data(animal).copy();for(String key:java.util.List.of("FactoryStage","StageWork","LastFactoryWork","FactoryYoung","HoldReason"))clean.remove(key);set(animal,clean);
        }
        animal.setPersistenceRequired(); animal.pet().newOwner(owner);
    }
    public static boolean protectedAnimal(YukkuriEntity animal) {
        var t = data(animal);
        if (t.hasUUID("Owner") || t.contains("PendingRole") || animal.life().home() != null && !animal.life().naturalHome()) return true;
        if (animal.pet().abandoned() || animal.pet().voidBadge()) return false; // abandoned or registry-void: no keeper stands behind it
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
        if(!(animal.level() instanceof ServerLevel level)||!java.util.Set.of("breeder","stud").contains(data(animal).getString("Use"))||animal.life().homeStand()!=null)return;
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
    /** Authoritative birth: companion mothers keep their young, stud mothers' young join the shop nursery; pedigree and first manners come from the parents. Badges are never inherited. */
    public static void inheritPet(YukkuriEntity child, YukkuriEntity mother, YukkuriEntity father) {
        if(child==null)return;
        if(mother!=null && !data(child).hasUUID("Owner")) {
            var parent=data(mother);
            if(parent.hasUUID("Owner")) {
                if(parent.getString("Use").equals("companion"))assign(child,parent.getUUID("Owner"),"companion");
                else if(parent.getString("Use").equals("stud"))assign(child,parent.getUUID("Owner"),"shop_nursery");
            }
        }
        int sum=0,known=0,manners=0,parents=0;boolean silver=false;
        for(var parent:new YukkuriEntity[]{mother,father})if(parent!=null) {
            parents++;manners+=parent.pet().ability(io.github.longshou.mcyukkuri.pet.Ability.MANNERS);
            if(parent.pet().pedigreeKnown()){sum+=parent.pet().pedigree();known++;silver|=parent.pet().grade().id()>=io.github.longshou.mcyukkuri.pet.BadgeGrade.SILVER.id();}
        }
        if(parents>0)child.pet().setAbility(io.github.longshou.mcyukkuri.pet.Ability.MANNERS,Math.round(manners/(float)parents*.2f));
        if(known>0)child.pet().setPedigree(Math.round(sum/(float)known)+(silver?5:0)+child.getRandom().nextInt(21)-10);
    }
    public static void tickAnimal(EntityTickEvent.Post e) {
        if (!(e.getEntity() instanceof YukkuriEntity animal) || !(animal.level() instanceof ServerLevel level) || animal.tickCount % 20 != 0) return;
        io.github.longshou.mcyukkuri.shop.ShopPerformance.appeal(animal, level);
        var tag = data(animal); String role = tag.getString("PendingRole");
        if (role.isEmpty()) {
            prepareBreederHome(animal);
            if (!tag.hasUUID("Owner") && animal.family().mother()!=null && level.getEntity(animal.family().mother()) instanceof YukkuriEntity mother) {
                inheritFactoryChild(animal,mother);
            }
            return;
        }
        level.getEntitiesOfClass(HumanNpc.class, animal.getBoundingBox().inflate(48), n -> n.isAlive() && n.siteRole().equals(role)).stream()
                .min(java.util.Comparator.comparingDouble(animal::distanceToSqr)).ifPresent(npc -> bindPending(animal, npc));
    }
    /**
     * Binds an animal that carries a PendingRole (template entities, scenario stock) to the staff member of that role: it takes the Use / Grade /
     * Pedigree written in its custody data, and badge-grade stock is issued its badge by the shop. Shop stock is also entered in the {@code ShopRegistry}.
     */
    public static void bindPending(YukkuriEntity animal, HumanNpc npc) {
        if (!(animal.level() instanceof ServerLevel level)) return;
        var tag = data(animal).copy();
        if (tag.getString("PendingRole").isEmpty()) return;
        assign(animal, npc.getUUID(), tag.getString("Use"));
        if (tag.getString("Use").equals("stock") && npc.job() == HumanNpc.Job.SHOPKEEPER) io.github.longshou.mcyukkuri.shop.ShopRegistry.get(level).stocked(npc, animal);
        if (tag.getString("Use").equals("lost")) npc.setLostAnimal(animal.getUUID(), animal.blockPosition());
        if (tag.getString("Use").equals("stud")) {
            var grade = io.github.longshou.mcyukkuri.pet.BadgeGrade.of(tag.getInt("Grade"));
            if (grade == io.github.longshou.mcyukkuri.pet.BadgeGrade.NONE) animal.pet().setPedigree(tag.contains("Pedigree") ? tag.getInt("Pedigree") : 55 + animal.getRandom().nextInt(31));
            else animal.pet().initializeStock(grade, npc.getUUID(), tag.contains("Pedigree") ? tag.getInt("Pedigree") : 70);
        }
        if (tag.getString("Use").equals("stock")) {
            int gradeId = tag.getInt("Grade");
            var grade = io.github.longshou.mcyukkuri.pet.BadgeGrade.of(gradeId);
            if (grade == io.github.longshou.mcyukkuri.pet.BadgeGrade.NONE) {
                animal.life().personality().lessonCompleted(npc.getUUID());
                animal.pet().setPedigree(tag.contains("Pedigree") ? tag.getInt("Pedigree") : 30);
            } else animal.pet().initializeStock(grade, npc.getUUID(), tag.contains("Pedigree") ? tag.getInt("Pedigree") : 60);
        }
        if (tag.getString("Use").equals("breeder")) {
            prepareBreederHome(animal);
            for(var other:npc.animals("breeder"))if(other!=animal && data(other).getInt("FactoryGroup")==tag.getInt("FactoryGroup")){
                YukkuriWorldData.get(level).relate(animal.getUUID(),other.getUUID(),20);
                YukkuriWorldData.get(level).relate(other.getUUID(),animal.getUUID(),20);
            }
        }
    }
    private HumanCommerce() {}
}
