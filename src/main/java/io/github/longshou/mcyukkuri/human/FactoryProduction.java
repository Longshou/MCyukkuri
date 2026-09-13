package io.github.longshou.mcyukkuri.human;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.family.FamilyData;
import io.github.longshou.mcyukkuri.life.YukkuriWorldData;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.*;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.Vec3;

/** Finite materials, real animals and persistent output. Production pauses when space or material is exhausted. */
public final class FactoryProduction {
    private final HumanNpc worker;
    private final List<ItemStack> output=new ArrayList<>();
    private final Map<Block,BlockPos> stations=new HashMap<>();
    private final Map<Block,Long> retryStation=new HashMap<>();
    private int material=32, batches, delay;
    private final FactoryPipeline pipeline;
    private final FactoryBreeding breeding;
    public FactoryProduction(HumanNpc worker){this.worker=worker;pipeline=new FactoryPipeline(worker,this);breeding=new FactoryBreeding(worker);}
    public FactoryPipeline pipeline(){return pipeline;}
    public FactoryBreeding breeding(){return breeding;}
    public int totalOutputCount(){return output.size();}
    public String kind(){return switch(worker.siteRole()){case "labor_worker"->"labor";case "manju_worker"->"manju";case "bag_worker"->"bag";default->"";};}
    public int material(){return material;}
    public int outputCount(){return (int)output.stream().filter(pipeline::matches).count();}
    public int batches(){return batches;}
    public int productPrice(){return switch(kind()){case "labor"->1;case "bag"->4;case "manju"->2;default->0;};}
    /** Called by the family ledger too, so autonomous pairing cannot bypass the workshop budget. */
    public static boolean reproductionAllowed(YukkuriEntity mother,YukkuriEntity father,int children){
        var a=HumanCommerce.data(mother);var b=HumanCommerce.data(father);
        if(!FactoryBreeding.member(mother)&&!FactoryBreeding.member(father)) {
            // Factory juveniles, inputs and held animals do not reproduce outside the controlled parent groups.
            if(a.hasUUID("Owner") && mother.level() instanceof ServerLevel sl && sl.getEntity(a.getUUID("Owner")) instanceof HumanNpc owner && !owner.production().kind().isEmpty()) return false;
            if(b.hasUUID("Owner") && father.level() instanceof ServerLevel sl && sl.getEntity(b.getUUID("Owner")) instanceof HumanNpc owner && !owner.production().kind().isEmpty()) return false;
            return true;
        }
        if(!a.getString("Use").equals("breeder")||!b.getString("Use").equals("breeder")||!a.hasUUID("Owner")||!b.hasUUID("Owner")||!a.getUUID("Owner").equals(b.getUUID("Owner")))return false;
        if(!(mother.level() instanceof ServerLevel l)||!(l.getEntity(a.getUUID("Owner")) instanceof HumanNpc n))return false;
        var line=n.production();return n.isAlive()&&!n.resting()&&(line.kind().equals("manju")||line.kind().equals("bag"))
                &&n.careFood()>=children&&line.material>0&&line.output.size()<16&&line.breeding.allows(mother,father,children)
                &&line.pipeline.operating()&&(line.pipeline.tray()==null||!line.pipeline.tray().blocked());
    }
    public Item ingredient(){return switch(kind()){case "labor"->Items.SUGAR_CANE;case "bag"->Items.PAPER;default->Items.WHEAT;};}
    public boolean replenish(ItemStack stack){if(kind().isEmpty()||material>120||!stack.is(ingredient())||stack.getCount()<8)return false;stack.shrink(8);material+=8;return true;}
    public ItemStack take(){for(int i=0;i<output.size();i++)if(pipeline.matches(output.get(i)))return output.remove(i);return ItemStack.EMPTY;}
    public ItemStack product(){return output.stream().filter(pipeline::matches).findFirst().map(ItemStack::copy).orElse(ItemStack.EMPTY);}
    public static boolean nurseryReady(YukkuriEntity y){
        // Stem births already use JUVENILE. Actual loaded age and nourishment enforce a nursery interval.
        return !y.family().awaitingLanding()&&y.family().age()>=Math.max(200,y.family().maturityAge()/8)
                &&y.family().save().getLong("Nourishment")>=Math.max(150,y.family().maturityAge()*3/32)
                &&y.life().nutrition()>=45&&y.getHealth()>=y.getMaxHealth()*.8;
    }
    public void dropContents(){for(var s:output)worker.spawnAtLocation(s);output.clear();}
    private Block machine(){return switch(kind()){case "labor"->HumanWorld.LABOR_BENCH.get();case "bag"->HumanWorld.PACKING_TABLE.get();default->HumanWorld.MANJU_STEAMER.get();};}
    BlockPos station(Block block){
        var l=worker.level();
        var cached=stations.get(block);if(cached!=null&&l.hasChunkAt(cached)&&l.getBlockState(cached).is(block))return cached;
        if(l.getGameTime()<retryStation.getOrDefault(block,0L))return null;
        retryStation.put(block,l.getGameTime()+100);
        for(var p:BlockPos.betweenClosed(worker.home().offset(-22,-1,-22),worker.home().offset(22,1,22)))
            if(l.hasChunkAt(p)&&l.getBlockState(p).is(block)){stations.put(block,p.immutable());return p.immutable();}
        return null;
    }
    public net.minecraft.core.Direction frontDirection(){var p=station(HumanWorld.NURSERY_STATION.get());return p==null?net.minecraft.core.Direction.NORTH:worker.level().getBlockState(p).getValue(FactoryStationBlock.FACING);}
    public BlockPos frontOf(BlockPos p){return p.relative(worker.level().getBlockState(p).getValue(FactoryStationBlock.FACING));}
    public BlockPos workPosition(YukkuriEntity y){
        if(!kind().equals("labor"))return null;
        String use=HumanCommerce.data(y).getString("Use");
        if(kind().isEmpty()||material<=0||output.size()>=16||worker.resting()||!HumanCommerce.ownedBy(y,worker.getUUID())||!(use.equals("input")||use.equals("labor")))return null;
        var machine=station(machine());return machine==null?null:frontOf(machine);
    }
    public boolean process(YukkuriEntity y){return kind().equals("bag")||kind().equals("manju")?pipeline.work(y):false;}
    boolean finish(YukkuriEntity y){
        if(!pipeline.readyToFinish(y))return false;
        if(kind().isEmpty()||material<=0||output.size()>=16||!y.isAlive()||!HumanCommerce.ownedBy(y,worker.getUUID())
                ||!HumanCommerce.data(y).getString("Use").equals("input")||y.operations().active()||delay>0)return false;
        var pos=station(machine());if(pos==null||y.distanceToSqr(Vec3.atCenterOf(pos))>36)return false;
        if(kind().equals("labor")){HumanCommerce.assign(y,worker.getUUID(),"labor");return false;}
        if(kind().equals("bag")){
            var before=HumanCommerce.data(y).copy();var tag=before.copy();tag.putBoolean("ProcessedLive",true);HumanCommerce.set(y,tag);
            // Cleaning was completed at its own staffed station.
            var token=YukkuriWorldData.get((ServerLevel)worker.level()).capture(y);
            if(token==null){HumanCommerce.set(y,before);return false;}
            var bag=HumanCommerce.carrier(y,token);var result=new ItemStack(HumanWorld.LIVE_BAG.get());
            result.set(DataComponents.CUSTOM_DATA,bag.get(DataComponents.CUSTOM_DATA));pipeline.decorate(result,y);output.add(result);
        }else{
            // Use the ordinary finite portion debit and consume that exact resulting resource.
            var before=new HashSet<UUID>();worker.level().getEntitiesOfClass(ItemEntity.class,y.getBoundingBox().inflate(2)).forEach(e->before.add(e.getUUID()));
            if(!y.body().releaseFilling("factory_manju"))return false;
            var portion=worker.level().getEntitiesOfClass(ItemEntity.class,y.getBoundingBox().inflate(2),e->!before.contains(e.getUUID())
                    && io.github.longshou.mcyukkuri.body.FillingResource.data(e.getItem()).hasUUID("Source")
                    && io.github.longshou.mcyukkuri.body.FillingResource.data(e.getItem()).getUUID("Source").equals(y.getUUID())).stream().findFirst().orElse(null);
            if(portion==null)return false;
            var result=new ItemStack(HumanWorld.MANJU.get());result.set(DataComponents.CUSTOM_DATA,portion.getItem().get(DataComponents.CUSTOM_DATA));
            portion.discard();output.add(result);y.hurt(worker.damageSources().mobAttack(worker),2);
            var tag=HumanCommerce.data(y).copy();tag.putString("FactoryStage","observe_transfer");tag.putInt("StageWork",0);HumanCommerce.set(y,tag);
        }
        material--;batches++;delay=200;return true;
    }
    public void tick(){
        if(kind().isEmpty())return;if(delay>0)delay--;
        if(worker.resting()||worker.isTrading())return;
        if(!kind().equals("labor")){if(worker.tickCount%20==0)pipeline.tick();if(worker.tickCount%100==0)breeding.tick();return;}
        if(worker.tickCount%100!=0)return;
        if(kind().equals("labor")){
            var p=station(machine());if(p==null)return;
            for(var y:worker.animals("input"))HumanCommerce.assign(y,worker.getUUID(),"labor");
            for(var y:worker.animals("labor")){
                if(material<=0||output.size()>=16||delay>0)break;
                if(y.life().nutrition()<35||y.life().energy()<25||y.isPanicking())continue;
                var stand=Vec3.atBottomCenterOf(frontOf(p));
                if(y.distanceToSqr(stand)<3){
                    y.life().spendNutrition(3);material--;batches++;output.add(new ItemStack(Items.PAPER));delay=200;
                    y.showReaction("tired","",80);break;
                }
            }
        }
    }
    public CompoundTag save(){var t=new CompoundTag();t.putInt("Material",material);t.putInt("Batches",batches);t.putInt("Delay",delay);
        var list=new ListTag();for(var s:output)list.add(s.save(worker.registryAccess()));t.put("Output",list);t.put("Pipeline",pipeline.save());t.put("Breeding",breeding.save());return t;}
    public void load(CompoundTag t){material=t.contains("Material")?Math.clamp(t.getInt("Material"),0,128):32;batches=Math.max(0,t.getInt("Batches"));delay=Math.clamp(t.getInt("Delay"),0,200);
        stations.clear();retryStation.clear();pipeline.load(t.getCompound("Pipeline"));breeding.load(t.getCompound("Breeding"));
        output.clear();for(var s:t.getList("Output",10))if(output.size()<16){var item=ItemStack.parseOptional(worker.registryAccess(),(CompoundTag)s);if(!item.isEmpty())output.add(item);}}
}
