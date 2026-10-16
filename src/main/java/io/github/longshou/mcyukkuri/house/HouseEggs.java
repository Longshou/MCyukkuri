package io.github.longshou.mcyukkuri.house;

import io.github.longshou.mcyukkuri.family.FamilyConfig;
import io.github.longshou.mcyukkuri.family.FamilyData;
import io.github.longshou.mcyukkuri.registry.ModItems;
import java.util.UUID;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

/** Items carry revocable tickets; the shared family ledger owns the child and incubation time. */
public final class HouseEggs {
    public record Ref(UUID brood, UUID child) {
        public CompoundTag save() {
            var tag=new CompoundTag();tag.putUUID("Brood",brood);tag.putUUID("Child",child);return tag;
        }
        public static Ref load(CompoundTag tag) {
            return tag.hasUUID("Brood")&&tag.hasUUID("Child")?new Ref(tag.getUUID("Brood"),tag.getUUID("Child")):null;
        }
    }
    public record Entry(FamilyData.Brood brood, FamilyData.Child child, int index) {
        public float progress(){return Math.min(1,child.eggElapsed/(float)Math.max(1,brood.duration));}
    }
    public static Entry resolve(ServerLevel level,Ref ref) {
        if(ref==null)return null;
        var brood=FamilyData.get(level).brood(ref.brood());
        if(brood==null||brood.cancelled||brood.kind!=FamilyData.BirthKind.EGG)return null;
        for(int i=0;i<brood.children.size();i++){
            var child=brood.children.get(i);
            if(child.id.equals(ref.child())&&!child.born&&!child.eggLost)return new Entry(brood,child,i);
        }
        return null;
    }
    public static boolean belongs(Entry entry,HouseNestBlockEntity nest) {
        if(entry==null||nest.getLevel()==null)return false;
        var child=entry.child();
        return child.eggManaged&&child.eggToken==null&&nest.identity().equals(child.eggNestId)
                &&GlobalPos.of(nest.getLevel().dimension(),nest.getBlockPos()).equals(child.eggNest);
    }
    public static Ref attachNew(FamilyData.Brood brood,FamilyData.Child child,HouseNestBlockEntity nest) {
        if(child.born||child.eggLost||child.eggManaged)return null;
        child.eggManaged=true;child.eggElapsed=brood.elapsed;
        child.eggNest=GlobalPos.of(nest.getLevel().dimension(),nest.getBlockPos());child.eggNestId=nest.identity();
        return new Ref(brood.id,child.id);
    }
    public static ItemStack extract(ServerLevel level,Ref ref,HouseNestBlockEntity nest) {
        var entry=resolve(level,ref);if(!belongs(entry,nest))return ItemStack.EMPTY;
        var child=entry.child();child.eggToken=UUID.randomUUID();child.eggNest=null;child.eggNestId=null;
        var stack=new ItemStack(ModItems.HOUSE_EGG.get());var tag=ref.save();
        tag.putUUID("EggToken",child.eggToken);tag.putInt("Progress",Math.round(entry.progress()*100));
        tag.putString("Variant",child.variant.assetId());
        stack.set(DataComponents.CUSTOM_DATA,CustomData.of(tag));FamilyData.get(level).setDirty();return stack;
    }
    public static Entry ticket(ServerLevel level,ItemStack stack) {
        if(!stack.is(ModItems.HOUSE_EGG.get())||stack.getCount()!=1)return null;
        var tag=stack.getOrDefault(DataComponents.CUSTOM_DATA,CustomData.EMPTY).copyTag();
        var entry=resolve(level,Ref.load(tag));
        return entry!=null&&tag.hasUUID("EggToken")&&tag.getUUID("EggToken").equals(entry.child().eggToken)?entry:null;
    }
    public static Ref insert(ServerLevel level,ItemStack stack,HouseNestBlockEntity nest) {
        var entry=ticket(level,stack);if(entry==null)return null;
        var data=FamilyData.get(level);var person=data.person(entry.child().id);
        boolean alreadyCounted=person!=null&&person.position!=null&&person.position.dimension().equals(level.dimension())
                &&person.position.pos().distSqr(nest.getBlockPos())<=32*32;
        if(data.population(level,nest.getBlockPos())-(alreadyCounted?1:0)+1>FamilyConfig.POPULATION_CAP.get())return null;
        var child=entry.child();child.eggNest=GlobalPos.of(level.dimension(),nest.getBlockPos());
        child.eggNestId=nest.identity();child.eggToken=null;
        if(person!=null)person.position=child.eggNest;
        data.setDirty();return new Ref(entry.brood().id,child.id);
    }
    public static void lose(ServerLevel level,Ref ref,HouseNestBlockEntity nest) {
        var entry=resolve(level,ref);if(!belongs(entry,nest))return;
        var child=entry.child();child.eggLost=true;child.eggNest=null;child.eggNestId=null;
        var person=FamilyData.get(level).person(child.id);if(person!=null)person.status=FamilyData.Status.LOST;
        FamilyData.get(level).setDirty();
    }
    /** A real loose egg was destroyed or expired. Pickup empties the stack; unloading does not destroy it. */
    public static void leaving(net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent event) {
        if(!(event.getEntity() instanceof net.minecraft.world.entity.item.ItemEntity item)
                ||!(event.getLevel() instanceof ServerLevel level)||item.getItem().isEmpty()
                ||item.getRemovalReason()!=net.minecraft.world.entity.Entity.RemovalReason.DISCARDED
                &&item.getRemovalReason()!=net.minecraft.world.entity.Entity.RemovalReason.KILLED)return;
        var entry=ticket(level,item.getItem());if(entry==null)return;
        var child=entry.child();child.eggLost=true;child.eggToken=null;
        var data=FamilyData.get(level);var person=data.person(child.id);
        if(person!=null)person.status=FamilyData.Status.LOST;
        data.setDirty();
    }
    private HouseEggs(){}
}
