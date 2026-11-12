package io.github.longshou.mcyukkuri.human;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.pet.BadgeGrade;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import io.github.longshou.mcyukkuri.shop.ShopRegistry;
import java.util.Comparator;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;

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
    public static void prepareBreederHome(YukkuriEntity animal) {
        if(!(animal.level() instanceof ServerLevel level)||!Set.of(Custody.Role.BREEDER,Custody.Role.STUD).contains(Custody.view(animal).role())||animal.life().homeStand()!=null)return;
        for(var pos:BlockPos.betweenClosed(animal.blockPosition().offset(-3,0,-3),animal.blockPosition().offset(3,0,3)))
            if(animal.life().assignHome(GlobalPos.of(level.dimension(),pos.immutable())))return;
    }
    /** Authoritative birth assigns custody only. Skills and badges are earned by the child itself. */
    public static void inheritPet(YukkuriEntity child, YukkuriEntity mother, YukkuriEntity father) {
        if(child==null)return;
        if(mother!=null && Custody.view(child).owner()==null) {
            var parent=Custody.view(mother);
            if(parent.owner()!=null) {
                if(parent.role()==Custody.Role.COMPANION)Custody.assign(child,parent.owner(),parent.ownerKind(),Custody.Role.COMPANION);
                else if(parent.role()==Custody.Role.STUD)Custody.assign(child,parent.owner(),parent.ownerKind(),Custody.Role.SHOP_NURSERY);
            }
        }
    }
    public static void tickAnimal(YukkuriEntity animal) {
        if (!(animal.level() instanceof ServerLevel level) || animal.tickCount % 20 != 0) return;
        var custody = Custody.view(animal); String role = custody.pendingStaffRole();
        if (role.isEmpty()) {
            prepareBreederHome(animal);
            return;
        }
        var owners = CustodyData.get(level);
        level.getEntitiesOfClass(HumanNpc.class, animal.getBoundingBox().inflate(48), n -> n.isAlive() && !owners.deadNpc(n.getUUID()) && n.siteRole().equals(role)).stream()
                .min(Comparator.comparingDouble(animal::distanceToSqr)).ifPresent(npc -> bindPending(animal, npc));
    }
    /**
     * Binds an animal that carries a PendingRole (template entities, scenario stock) to the staff member of that role.
     * Custody metadata does not grant a badge, skill, or hereditary value. Shop stock is entered in the {@code ShopRegistry}.
     */
    public static void bindPending(YukkuriEntity animal, HumanNpc npc) {
        if (!(animal.level() instanceof ServerLevel level)) return;
        if (npc.closedFactory() || CustodyData.get(level).deadNpc(npc.getUUID())) return;
        var tag = data(animal).copy();
        var custody = Custody.fromData(tag);
        if (custody.pendingStaffRole().isEmpty()) return;
        Custody.assignRaw(animal, npc.getUUID(), Custody.OwnerKind.NPC, custody.rawUse());
        var bound = Custody.view(animal);
        if (!bound.ownedBy(npc.getUUID()) || bound.hasPendingStaffRole()) return;
        // Authored templates carry qualifications in YukkuriPet. Binding supplies the real
        // issuer, serial and world-relative expiry without granting any skill or trait.
        var pet = animal.pet();
        if ((custody.role() == Custody.Role.STOCK || custody.role() == Custody.Role.STUD)
                && pet.grade() != BadgeGrade.NONE && pet.serial() == null)
            pet.issue(pet.grade(), pet.source(), npc.getUUID());
        if (custody.role() == Custody.Role.STOCK && npc.job() == HumanNpc.Job.SHOPKEEPER) ShopRegistry.get(level).stocked(npc, animal);
        if (custody.role() == Custody.Role.LOST) npc.setLostAnimal(animal.getUUID(), animal.blockPosition());
    }
    private HumanCommerce() {}
}
