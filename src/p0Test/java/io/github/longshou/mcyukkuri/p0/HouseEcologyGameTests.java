package io.github.longshou.mcyukkuri.p0;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.family.FamilyData;
import io.github.longshou.mcyukkuri.house.HouseEggs;
import io.github.longshou.mcyukkuri.house.HouseNestBlockEntity;
import io.github.longshou.mcyukkuri.registry.ModBlocks;
import io.github.longshou.mcyukkuri.registry.ModItems;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Remote-only transaction checks using the real family ledger, physical nests and actual births. */
@PrefixGameTestTemplate(false)
public final class HouseEcologyGameTests {
    @GameTest(templateNamespace=P0Mod.ID,template="empty",timeoutTicks=100,batch="house_ecology")
    public static void transferredEggAndBornYoungSurviveOldNestCleaning(GameTestHelper h) {
        try(var f=new Fixture(h,3,1200)) {
            f.source.tick();
            var brood=f.brood();
            h.assertTrue(f.source.eggs()==3,"legacy clutch becomes three actual identified egg slots");
            var first=f.data().birth(brood,0,f.level,f.source.eggPosition(0));
            h.assertTrue(first!=null,"ready egg creates a real child at the original nest slot");
            f.babies.add(first);
            f.source.tick();
            var ticket=f.source.extractEgg();
            var copied=ticket.copy();
            h.assertTrue(ticket.is(ModItems.HOUSE_EGG.get())&&f.source.eggs()==1,
                    "extract skips the born child and withdraws exactly one remaining real egg");
            h.assertTrue(f.target.insertEgg(ticket)&&f.target.eggs()==1,"one ticket transfers the same unborn identity to a second physical nest");
            h.assertTrue(!f.source.insertEgg(copied)&&HouseEggs.ticket(f.level,copied)==null,
                    "insertion revokes copied item tickets instead of duplicating an egg");
            var transferred=brood.children.get(1);
            var person=f.data().person(transferred.id);
            h.assertTrue(brood.id.equals(person.brood)&&f.mother.equals(person.mother)&&f.father.equals(person.father)
                    &&GlobalPos.of(f.level.dimension(),f.target.getBlockPos()).equals(transferred.eggNest)
                    &&transferred.eggNest.equals(person.position),
                    "transfer preserves ancestry and moves only this child's population reservation to the new nest");
            f.source.clean(true);
            h.assertTrue(first.isAlive()&&f.level.getEntity(first.getUUID())==first,
                    "dismantling the original nest leaves its already born individual alive");
            h.assertTrue(!transferred.eggLost&&f.target.eggs()==1&&brood.children.get(2).eggLost,
                    "dismantling loses only the egg still physically in the original nest");
            h.assertTrue(f.data().person(transferred.id).status==FamilyData.Status.UNBORN
                    &&f.data().person(brood.children.get(2).id).status==FamilyData.Status.LOST,
                    "transferred and lost population reservations retain distinct outcomes");
            var second=f.data().birth(brood,1,f.level,f.target.eggPosition(0));
            h.assertTrue(second!=null&&second.getUUID().equals(transferred.id),"the transferred egg hatches with its original reserved identity");
            f.babies.add(second);
            h.assertTrue(f.data().birth(brood,1,f.level,f.target.eggPosition(0))==null
                    &&!f.target.insertEgg(copied),"neither a repeated birth nor a stale ticket creates another child");
            f.target.destroyNest();
            h.assertTrue(first.isAlive()&&second.isAlive(),"destroying both nests does not retroactively delete their born young");
        }
        h.succeed();
    }

    @GameTest(templateNamespace=P0Mod.ID,template="empty",timeoutTicks=100,batch="house_ecology")
    public static void inventoryAndNestRoundTripPreserveIdentityAndTime(GameTestHelper h) {
        try(var f=new Fixture(h,2,271)) {
            f.source.tick();
            var ticket=f.source.extractEgg();
            var childId=f.brood().children.getFirst().id;
            h.assertTrue(HouseEggs.ticket(f.level,ticket)!=null,"real extracted item carries a valid family-ledger ticket");
            var savedTicket=ticket.save(f.level.registryAccess());
            f.reloadLedger();
            ticket=ItemStack.parse(f.level.registryAccess(),savedTicket).orElseThrow();
            var carried=HouseEggs.ticket(f.level,ticket);
            h.assertTrue(carried!=null&&carried.child().id.equals(childId)&&carried.child().eggElapsed==271,
                    "ledger reload preserves the carried egg's identity, custody token and incubation time");
            h.assertTrue(f.data().birth(f.brood(),0,f.level,f.target.eggPosition(0))==null,
                    "an inventory-held egg cannot hatch through a direct birth call");
            h.assertTrue(!f.target.insertEgg(new ItemStack(ModItems.HOUSE_EGG.get())),
                    "a fresh icon without a real custody ticket cannot create an egg");
            h.assertTrue(!f.target.insertEgg(ticket.copyWithCount(2))&&HouseEggs.ticket(f.level,ticket)!=null,
                    "an invalid multi-item request fails without revoking the legitimate ticket");
            h.assertTrue(f.target.insertEgg(ticket),"the same restored ticket is still insertable once");
            var savedSource=f.source.saveWithFullMetadata(f.level.registryAccess());
            var savedTarget=f.target.saveWithFullMetadata(f.level.registryAccess());
            var identity=f.target.identity();
            f.reloadLedger();
            f.source.loadWithComponents(savedSource,f.level.registryAccess());
            f.target.loadWithComponents(savedTarget,f.level.registryAccess());
            f.source.tick();f.target.tick();
            h.assertTrue(f.target.identity().equals(identity)&&f.target.eggs()==1&&f.source.eggs()==1,
                    "both physical nest inventories and stable nest identity survive save/load");
            h.assertTrue(f.brood().children.getFirst().eggElapsed==271
                    &&Math.abs(f.target.eggProgress(0)-271f/1200)<0.00001f,
                    "incubation does not reset, advance without care or become a clutch-wide average after reload");
            h.assertTrue(!f.source.insertEgg(ticket),"a pre-save consumed ticket remains invalid after both ledger and nest reload");
            var renewed=f.target.extractEgg();
            h.assertTrue(HouseEggs.ticket(f.level,renewed)!=null&&HouseEggs.ticket(f.level,ticket)==null,
                    "re-extraction grants a new token while permanently revoking the old copy");
            h.assertTrue(HouseEggs.ticket(f.level,renewed).child().id.equals(childId),"re-extraction preserves the child instead of allocating a new identity");
        }
        h.succeed();
    }

    @GameTest(templateNamespace=P0Mod.ID,template="empty",timeoutTicks=100,batch="house_ecology")
    public static void legacyNestMigratesOnceAndKeepsItsSlots(GameTestHelper h) {
        try(var f=new Fixture(h,4,843)) {
            f.source.tick();
            h.assertTrue(f.source.built()&&f.source.eggs()==4,"v1 Brood tag migrates into four independent egg places");
            for(int slot=0;slot<4;slot++) {
                var child=f.brood().children.get(slot);
                h.assertTrue(child.eggManaged&&child.eggElapsed==843&&f.source.eggPresent(slot)
                        &&HouseEggs.belongs(HouseEggs.resolve(f.level,new HouseEggs.Ref(f.broodId,child.id)),f.source),
                        "migration retains old progress and assigns each unborn child to this physical nest exactly once");
            }
            var saved=f.source.saveWithFullMetadata(f.level.registryAccess());
            h.assertTrue(saved.getInt("Version")==2&&saved.getList("EggSlots",Tag.TAG_COMPOUND).size()==4,
                    "migrated nest saves the current slot format");
            f.source.loadWithComponents(saved,f.level.registryAccess());f.source.tick();f.source.tick();
            h.assertTrue(f.source.eggs()==4&&f.brood().children.size()==4,"reloading and repeated ticks do not allocate or duplicate eggs");
            // A stale v1 tag at another location is not ownership of the same clutch.
            var wrong=f.target.saveWithFullMetadata(f.level.registryAccess());
            wrong.remove("EggSlots");wrong.putInt("Version",1);wrong.putUUID("Brood",f.broodId);
            f.target.loadWithComponents(wrong,f.level.registryAccess());f.target.tick();
            h.assertTrue(f.target.eggs()==0&&f.source.eggs()==4,"a copied legacy brood reference cannot claim eggs from a different anchored nest");
            for(int slot=0;slot<4;slot++)h.assertTrue(!f.source.extractEgg().isEmpty(),"every migrated egg is available once");
            h.assertTrue(f.source.extractEgg().isEmpty()&&f.source.eggs()==0,"a migrated clutch has no hidden fifth egg");
        }
        h.succeed();
    }

    @GameTest(templateNamespace=P0Mod.ID,template="empty",timeoutTicks=100,batch="house_ecology")
    public static void looseEggDestructionReleasesReservationWithoutKillingTransferredEgg(GameTestHelper h) {
        try(var f=new Fixture(h,1,271)) {
            f.source.tick();
            var ticket=f.source.extractEgg();
            var copied=ticket.copy();
            h.assertTrue(f.target.insertEgg(ticket),"a real egg is first moved into the second nest");
            var stale=f.loose(copied);stale.discard();
            h.assertTrue(!f.brood().children.getFirst().eggLost&&f.target.eggs()==1,
                    "discarding an obsolete copied ticket cannot harm the egg now in a nest");
            var current=f.target.extractEgg();
            var picked=f.loose(current.copy());picked.setItem(ItemStack.EMPTY);picked.discard();
            h.assertTrue(HouseEggs.ticket(f.level,current)!=null,
                    "normal pickup's empty dropped stack does not kill the egg in the inventory");
            var unloaded=f.loose(current.copy());unloaded.remove(Entity.RemovalReason.UNLOADED_TO_CHUNK);
            h.assertTrue(HouseEggs.ticket(f.level,current)!=null,
                    "chunk unloading preserves the live custody ticket");
            var destroyed=f.loose(current);destroyed.discard();
            var child=f.brood().children.getFirst();
            h.assertTrue(child.eggLost&&HouseEggs.ticket(f.level,current)==null
                    &&f.data().person(child.id).status==FamilyData.Status.LOST,
                    "destruction of the actual nonempty loose egg marks its reservation lost exactly once");
            h.assertTrue(f.brood().complete()&&!f.data().busyParent(f.mother)&&!f.data().busyParent(f.father),
                    "a destroyed final egg no longer leaves both parents permanently reserved by an unfinished clutch");
        }
        h.succeed();
    }

    /** Synchronous, legal saved-data setup removes conception randomness without bypassing custody operations. */
    private static final class Fixture implements AutoCloseable {
        final ServerLevel level;
        final FamilyData previous;
        final UUID broodId=UUID.randomUUID(),mother=UUID.randomUUID(),father=UUID.randomUUID();
        final Map<BlockPos,BlockState> blocks=new LinkedHashMap<>();
        final List<YukkuriEntity> babies=new ArrayList<>();
        final List<ItemEntity> drops=new ArrayList<>();
        final HouseNestBlockEntity source,target;

        Fixture(GameTestHelper h,int count,int elapsed) {
            level=h.getLevel();previous=FamilyData.get(level);
            var origin=h.absolutePos(new BlockPos(2,2,2));
            for(var p:BlockPos.betweenClosed(origin.offset(-1,-1,-2),origin.offset(5,3,1))) {
                var pos=p.immutable();blocks.put(pos,level.getBlockState(pos));
                level.setBlockAndUpdate(pos,pos.getY()==origin.getY()-1?Blocks.STONE.defaultBlockState():Blocks.AIR.defaultBlockState());
            }
            source=nest(origin);target=nest(origin.east(4));
            var snapshot=previous.save(new CompoundTag(),level.registryAccess());
            var people=snapshot.getList("People",Tag.TAG_COMPOUND);
            var children=new ListTag();
            for(int i=0;i<count;i++) {
                var id=UUID.randomUUID();var child=new CompoundTag();
                child.putUUID("Id",id);child.putString("Variant",i%2==0?"HOUSE_REIMU":"HOUSE_MARISA");
                child.putFloat("AdultScale",1);child.putLong("Lifespan",72000);child.putLong("Maturity",24000);
                child.putIntArray("Traits",new int[]{50,50,50,50,50,50});child.putIntArray("Dispositions",new int[]{50,50,50,50});children.add(child);
                var person=new CompoundTag();person.putUUID("Id",id);person.putString("Variant",i%2==0?"house_reimu":"house_marisa");
                person.putString("Status","UNBORN");person.putUUID("Mother",mother);person.putUUID("Father",father);person.putUUID("Brood",broodId);
                position(person,"Position",origin);people.add(person);
            }
            var brood=new CompoundTag();brood.putUUID("Id",broodId);brood.putUUID("Mother",mother);brood.putUUID("Father",father);
            brood.putString("Kind","EGG");brood.putInt("Elapsed",elapsed);brood.putInt("Duration",1200);brood.putFloat("Variation",1);
            position(brood,"Anchor",origin);brood.put("Children",children);snapshot.getList("Broods",Tag.TAG_COMPOUND).add(brood);
            install(FamilyData.load(snapshot,level.registryAccess()));
            var legacy=source.saveWithFullMetadata(level.registryAccess());
            legacy.remove("EggSlots");legacy.putInt("Version",1);legacy.putUUID("Brood",broodId);
            source.loadWithComponents(legacy,level.registryAccess());
            h.assertTrue(brood()!=null&&brood().children.size()==count,"fixture loads a valid real family reservation");
        }
        private HouseNestBlockEntity nest(BlockPos pos) {
            level.setBlockAndUpdate(pos,ModBlocks.HOUSE_NEST.get().defaultBlockState());
            level.setBlockAndUpdate(pos.north().above(2),Blocks.STONE.defaultBlockState());
            var nest=(HouseNestBlockEntity)level.getBlockEntity(pos);
            for(int i=0;i<HouseNestBlockEntity.MATERIAL_COST;i++)nest.deposit(new ItemStack(ModItems.NEST_LINT.get()));
            return nest;
        }
        private void position(CompoundTag tag,String key,BlockPos pos) {
            var value=new CompoundTag();value.putString("Dimension",level.dimension().location().toString());value.putLong("Pos",pos.asLong());tag.put(key,value);
        }
        FamilyData data(){return FamilyData.get(level);}
        FamilyData.Brood brood(){return data().brood(broodId);}
        void install(FamilyData data){level.getServer().overworld().getDataStorage().set("mcyukkuri_families",data);}
        void reloadLedger(){install(FamilyData.load(data().save(new CompoundTag(),level.registryAccess()),level.registryAccess()));}
        ItemEntity loose(ItemStack stack) {
            var pos=source.entrance();var item=new ItemEntity(level,pos.getX()+.5,pos.getY()+.25,pos.getZ()+.5,stack);
            if(!level.addFreshEntity(item))throw new IllegalStateException("Fixture loose egg insertion failed");
            drops.add(item);return item;
        }
        @Override public void close() {
            babies.forEach(YukkuriEntity::discard);drops.forEach(ItemEntity::discard);
            // Empty the fixture's stores before restoring blocks, so cleanup cannot spawn loose drops.
            source.clean(true);target.clean(true);blocks.forEach(level::setBlockAndUpdate);install(previous);
        }
    }
    private HouseEcologyGameTests(){}
}
