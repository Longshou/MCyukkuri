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

/** Compatibility facade: Custody validates metadata and pending cleanup; storage remains entity NeoForgeData. */
public final class HumanCommerce {
    public static CompoundTag data(YukkuriEntity animal) { return Custody.data(animal); }
    public static void set(YukkuriEntity animal, CompoundTag tag) { Custody.set(animal, tag); }
    public static boolean ownedBy(YukkuriEntity animal, UUID id) { return Custody.ownedBy(animal, id); }
    public static void assign(YukkuriEntity animal, UUID owner, String use) {
        Custody.assignLegacy(animal, owner, use);
    }
    public static boolean protectedAnimal(YukkuriEntity animal) { return Custody.protectedAnimal(animal); }
    public static boolean mayCapture(Player player, YukkuriEntity animal) { return Custody.mayCapture(player, animal); }
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
        if(!(animal.level() instanceof ServerLevel level)||!java.util.Set.of(Custody.Role.BREEDER,Custody.Role.STUD).contains(Custody.view(animal).role())||animal.life().homeStand()!=null)return;
        for(var pos:net.minecraft.core.BlockPos.betweenClosed(animal.blockPosition().offset(-3,0,-3),animal.blockPosition().offset(3,0,3)))
            if(animal.life().assignHome(net.minecraft.core.GlobalPos.of(level.dimension(),pos.immutable())))return;
    }
    /** Called at authoritative birth, before a completed parent can retire or unload. */
    public static void inheritFactoryChild(YukkuriEntity child, YukkuriEntity mother) {
        if(child==null || mother==null || Custody.view(child).owner()!=null)return;
        var parent=Custody.view(mother);
        if(parent.owner()!=null && (parent.role()==Custody.Role.BREEDER || data(mother).getBoolean("RetiredParent")))
            Custody.assign(child,parent.owner(),parent.ownerKind(),Custody.Role.NURSERY);
    }
    /** Authoritative birth: companion mothers keep their young, stud mothers' young join the shop nursery; pedigree and first manners come from the parents. Badges are never inherited. */
    public static void inheritPet(YukkuriEntity child, YukkuriEntity mother, YukkuriEntity father) {
        if(child==null)return;
        if(mother!=null && Custody.view(child).owner()==null) {
            var parent=Custody.view(mother);
            if(parent.owner()!=null) {
                if(parent.role()==Custody.Role.COMPANION)Custody.assign(child,parent.owner(),parent.ownerKind(),Custody.Role.COMPANION);
                else if(parent.role()==Custody.Role.STUD)Custody.assign(child,parent.owner(),parent.ownerKind(),Custody.Role.SHOP_NURSERY);
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
        var custody = Custody.view(animal); String role = custody.pendingStaffRole();
        if (role.isEmpty()) {
            prepareBreederHome(animal);
            if (custody.owner() == null && animal.family().mother()!=null && level.getEntity(animal.family().mother()) instanceof YukkuriEntity mother) {
                inheritFactoryChild(animal,mother);
            }
            return;
        }
        var owners = CustodyData.get(level);
        level.getEntitiesOfClass(HumanNpc.class, animal.getBoundingBox().inflate(48), n -> n.isAlive() && !owners.deadNpc(n.getUUID()) && n.siteRole().equals(role)).stream()
                .min(java.util.Comparator.comparingDouble(animal::distanceToSqr)).ifPresent(npc -> bindPending(animal, npc));
    }
    /**
     * Binds an animal that carries a PendingRole (template entities, scenario stock) to the staff member of that role: it takes the Use / Grade /
     * Pedigree written in its custody data, and badge-grade stock is issued its badge by the shop. Shop stock is also entered in the {@code ShopRegistry}.
     */
    public static void bindPending(YukkuriEntity animal, HumanNpc npc) {
        if (!(animal.level() instanceof ServerLevel level)) return;
        if (CustodyData.get(level).deadNpc(npc.getUUID())) return;
        var tag = data(animal).copy();
        var custody = Custody.fromData(tag);
        if (custody.pendingStaffRole().isEmpty()) return;
        Custody.assignRaw(animal, npc.getUUID(), Custody.OwnerKind.NPC, custody.rawUse());
        var bound = Custody.view(animal);
        if (!bound.ownedBy(npc.getUUID()) || bound.hasPendingStaffRole()) return;
        if (custody.role() == Custody.Role.STOCK && npc.job() == HumanNpc.Job.SHOPKEEPER) io.github.longshou.mcyukkuri.shop.ShopRegistry.get(level).stocked(npc, animal);
        if (custody.role() == Custody.Role.LOST) npc.setLostAnimal(animal.getUUID(), animal.blockPosition());
        if (custody.role() == Custody.Role.STUD) {
            var grade = io.github.longshou.mcyukkuri.pet.BadgeGrade.of(tag.getInt("Grade"));
            if (grade == io.github.longshou.mcyukkuri.pet.BadgeGrade.NONE) animal.pet().setPedigree(tag.contains("Pedigree") ? tag.getInt("Pedigree") : 55 + animal.getRandom().nextInt(31));
            else animal.pet().initializeStock(grade, npc.getUUID(), tag.contains("Pedigree") ? tag.getInt("Pedigree") : 70);
        }
        if (custody.role() == Custody.Role.STOCK) {
            int gradeId = tag.getInt("Grade");
            var grade = io.github.longshou.mcyukkuri.pet.BadgeGrade.of(gradeId);
            if (grade == io.github.longshou.mcyukkuri.pet.BadgeGrade.NONE) {
                animal.life().personality().lessonCompleted(npc.getUUID());
                animal.pet().setPedigree(tag.contains("Pedigree") ? tag.getInt("Pedigree") : 30);
            } else animal.pet().initializeStock(grade, npc.getUUID(), tag.contains("Pedigree") ? tag.getInt("Pedigree") : 60);
        }
        if (custody.role() == Custody.Role.BREEDER) {
            prepareBreederHome(animal);
            for(var other:npc.animals(Custody.Role.BREEDER))if(other!=animal && data(other).getInt("FactoryGroup")==tag.getInt("FactoryGroup")){
                YukkuriWorldData.get(level).relate(animal.getUUID(),other.getUUID(),20);
                YukkuriWorldData.get(level).relate(other.getUUID(),animal.getUUID(),20);
            }
        }
    }
    private HumanCommerce() {}
}
