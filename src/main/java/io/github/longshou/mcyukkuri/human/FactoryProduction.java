package io.github.longshou.mcyukkuri.human;

import io.github.longshou.mcyukkuri.status.GameplayLog;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.entity.RemainsEntity;
import io.github.longshou.mcyukkuri.life.YukkuriWorldData;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.*;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import io.github.longshou.mcyukkuri.body.BodyFood;
import io.github.longshou.mcyukkuri.body.CorpseFood;
import io.github.longshou.mcyukkuri.shop.CommodityLife;
import io.github.longshou.mcyukkuri.shop.ShopDeliveries;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;

/** Finite materials, real animals and persistent output. Production pauses when space or material is exhausted. */
public final class FactoryProduction {
    private final HumanNpc worker;
    private final List<ItemStack> output=new ArrayList<>();
    private final Map<Block,BlockPos> stations=new HashMap<>();
    private final Map<Block,Long> retryStation=new HashMap<>();
    private int material=32, batches, delay;
    /**
     * A manju line owns at most one world corpse at a time. The source identity
     * is written first so a failed hand-off remains a stopped line rather than
     * silently advancing to another live input; the entity id resumes the exact
     * claimed corpse after a save/load.
     */
    private UUID manjuSource,manjuRemains;
    private static final String FACTORY_OWNER="FactoryOwner",FACTORY_SOURCE="FactorySource";
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
        var a=Custody.view(mother);var b=Custody.view(father);
        if(!FactoryBreeding.member(mother)&&!FactoryBreeding.member(father)) {
            // Factory juveniles, inputs and held animals do not reproduce outside the controlled parent groups.
            if((a.owner()!=null) && mother.level() instanceof ServerLevel sl && sl.getEntity(a.owner()) instanceof HumanNpc owner && !owner.production().kind().isEmpty()) return false;
            if((b.owner()!=null) && father.level() instanceof ServerLevel sl && sl.getEntity(b.owner()) instanceof HumanNpc owner && !owner.production().kind().isEmpty()) return false;
            return true;
        }
        if(a.role()!=Custody.Role.BREEDER||b.role()!=Custody.Role.BREEDER||a.owner() == null||b.owner() == null||!a.owner().equals(b.owner()))return false;
        if(!(mother.level() instanceof ServerLevel l)||!(l.getEntity(a.owner()) instanceof HumanNpc n))return false;
        var line=n.production();return n.isAlive()&&!n.resting()&&(line.kind().equals("manju")||line.kind().equals("bag"))
                &&n.careFood()>=children&&line.material>0&&line.output.size()<16&&line.breeding.allows(mother,father,children)
                &&line.pipeline.operating()&&(line.pipeline.tray()==null||!line.pipeline.tray().blocked());
    }
    public Item ingredient(){return switch(kind()){case "labor"->Items.SUGAR_CANE;case "bag"->Items.PAPER;default->Items.WHEAT;};}
    public boolean replenish(ItemStack stack){
        String reason=kind().isEmpty()?"no_line":material>120?"material_full":!stack.is(ingredient())?"wrong_ingredient":stack.getCount()<8?"insufficient_ingredient":null;
        if(reason!=null){GameplayLog.event(worker,"factory","action=replenish result=refused reason={} material={} held={}",reason,material,stack);return false;}
        stack.shrink(8);material+=8;
        GameplayLog.event(worker,"factory","action=replenish result=committed ingredient={} used=8 material={}->{}",ingredient(),material-8,material);
        return true;
    }
    public ItemStack take(){for(int i=0;i<output.size();i++)if(pipeline.matches(output.get(i)))return output.remove(i);return ItemStack.EMPTY;}
    public ItemStack product(){return output.stream().filter(pipeline::matches).findFirst().map(ItemStack::copy).orElse(ItemStack.EMPTY);}
    public static boolean nurseryReady(YukkuriEntity y){
        // Stem births already use JUVENILE. Actual loaded age and nourishment enforce a nursery interval.
        return !y.family().awaitingLanding()&&y.family().age()>=Math.max(200,y.family().maturityAge()/8)
                &&y.family().save().getLong("Nourishment")>=Math.max(150,y.family().maturityAge()*3/32)
                &&y.life().nutrition()>=45&&y.getHealth()>=y.getMaxHealth()*.8;
    }
    /** Core filling sold to the factory is premium raw material: it tops up the finite stock. */
    public void receiveCore(){int before=material;material=Math.min(128,material+4);GameplayLog.event(worker,"factory","action=receive_core material={}->{}",before,material);}
    /** Unsold shop stock sent back for reclamation: the collected individual is released at the receiving table as an input and goes through the line. Returns false when there is no room yet. */
    public boolean takeRecall(UUID token){
        if(!(worker.level() instanceof ServerLevel level)||kind().isEmpty()||worker.resting()||worker.animals(Custody.Role.INPUT).size()>=2
                ||!kind().equals("labor")&&breeding.population()>=8)return false;
        if(CustodyData.get(level).deadNpc(worker.getUUID()))return false;
        var table=station(HumanWorld.RECEIVING_TABLE.get());
        Vec3 at=table!=null?Vec3.atBottomCenterOf(frontOf(table)):worker.position();
        YukkuriEntity y=null;
        for(int[] o:new int[][]{{0,0},{1,0},{-1,0},{0,1},{0,-1}}){y=YukkuriWorldData.get(level).release(token,level,at.add(o[0],0,o[1]),worker.getYRot());if(y!=null)break;}
        if(y==null)return false;
        y.pet().unlock("sale");y.pet().unlock("courier");
        Custody.assign(y, worker, Custody.Role.INPUT);
        var tag=HumanCommerce.data(y).copy();tag.remove("ForSaleSince");tag.remove("StaleCuts");tag.remove("ShopCandidate");tag.putBoolean("Recycled",true);HumanCommerce.set(y,tag);
        y.life().releaseHome();
        CommodityLife.pressure(y,15f,"recalled");
        if(y.life().sedated())y.life().shortenDrugSleep(100);else y.showReaction("cry","",120);
        worker.swing(InteractionHand.MAIN_HAND);
        return true;
    }
    public void dropContents(){
        var remains=currentManjuRemains();if(remains!=null)releaseManjuRemains(remains);
        clearManjuRemains();
        for(var s:output)worker.spawnAtLocation(s);output.clear();
    }
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
    public Direction frontDirection(){var p=station(HumanWorld.NURSERY_STATION.get());return p==null?Direction.NORTH:worker.level().getBlockState(p).getValue(FactoryStationBlock.FACING);}
    public BlockPos frontOf(BlockPos p){return p.relative(worker.level().getBlockState(p).getValue(FactoryStationBlock.FACING));}
    public BlockPos workPosition(YukkuriEntity y){
        if(!kind().equals("labor"))return null;
        var use=Custody.view(y).role();
        if(kind().isEmpty()||material<=0||output.size()>=16||worker.resting()||!HumanCommerce.ownedBy(y,worker.getUUID())||!(use==Custody.Role.INPUT||use==Custody.Role.LABOR))return null;
        var machine=station(machine());return machine==null?null:frontOf(machine);
    }
    public boolean process(YukkuriEntity y){return kind().equals("bag")||kind().equals("manju")?pipeline.work(y):false;}
    /** A claimed or failed-to-claim corpse blocks the next live manju input. */
    public boolean manjuWorkPending(){return kind().equals("manju")&&manjuSource!=null;}
    boolean finish(YukkuriEntity y){
        if(!pipeline.readyToFinish(y)||kind().equals("manju")&&manjuWorkPending())return false;
        String reason=kind().isEmpty()?"no_line":material<=0?"no_material":output.size()>=16?"output_full":!y.isAlive()?"target_dead":
                !HumanCommerce.ownedBy(y,worker.getUUID())?"not_owned":Custody.view(y).role()!=Custody.Role.INPUT?"not_input":y.operations().active()?"operation_active":delay>0?"cooldown":null;
        if(reason!=null){
            if(!reason.equals("cooldown"))GameplayLog.limited(worker,"factory","finish:"+reason,1200,"action=finish result=blocked target={} reason={} material={} output={}",y.getUUID(),reason,material,output.size());
            return false;
        }
        var pos=station(machine());if(pos==null||y.distanceToSqr(Vec3.atCenterOf(pos))>36)return false;
        if(kind().equals("labor")){Custody.assign(y, worker, Custody.Role.LABOR);return false;}
        if(kind().equals("bag")){
            var before=HumanCommerce.data(y).copy();var tag=before.copy();tag.putBoolean("ProcessedLive",true);HumanCommerce.set(y,tag);
            // Cleaning was completed at its own staffed station.
            var token=YukkuriWorldData.get((ServerLevel)worker.level()).capture(y);
            if(token==null){Custody.restore(y,before);GameplayLog.limited(worker,"factory","bag_capture",200,"action=pack result=blocked target={} reason=capture_failed material={} output={}",y.getUUID(),material,output.size());return false;}
            var bag=HumanCommerce.carrier(y,token);var result=new ItemStack(HumanWorld.LIVE_BAG.get());
            result.set(DataComponents.CUSTOM_DATA,bag.get(DataComponents.CUSTOM_DATA));pipeline.decorate(result,y);output.add(result);
            material--;batches++;delay=200;
            GameplayLog.event(worker,"factory","action=produce product=live_bag target={} token={} material={}->{} output={}->{} batches={}",y.getUUID(),token,material+1,material,output.size()-1,output.size(),batches);
            return true;
        }
        // The live input becomes one real corpse first. Its shared balance is
        // retained by this line and consumed one whole portion per later output.
        if(!canStartManju(y)){
            GameplayLog.limited(worker,"factory","manju_ineligible",1200,"action=manju_input result=refused target={} reason=unusable_filling",y.getUUID());
            return false;
        }
        UUID source=y.getUUID();
        manjuSource=source;
        y.body().terminalKill(worker.damageSources().mobAttack(worker),true,false);
        var remains=factoryRemains(source,y.position());
        if(remains==null||!claimManjuRemains(remains,source)){
            GameplayLog.event(worker,"factory","action=manju_input result=blocked target={} reason=remains_missing_or_claim_failed",source);
            return false;
        }
        manjuRemains=remains.getUUID();
        GameplayLog.event(worker,"factory","action=manju_input result=claimed target={} remains={}",source,manjuRemains);
        return true;
    }
    /** Validate the death snapshot before terminal work so an ineligible input stays alive. */
    private static boolean canStartManju(YukkuriEntity y){
        return !y.mind().foulFilling()&&BodyFood.capacity(y)*y.getHealth()/Math.max(1,y.getMaxHealth())>=1;
    }
    /** Continue the independently-owned corpse phase. True keeps live input work paused. */
    private boolean processManjuRemains(){
        if(!manjuWorkPending())return false;
        var remains=currentManjuRemains();
        // Missing or mismatched ownership is an explicit stopped line. Do not
        // replace it with a different corpse or consume a new live input.
        if(remains==null){
            GameplayLog.limited(worker,"factory","missing_remains",1200,"action=manju_process result=blocked target={} remains={} reason=remains_unavailable_or_claim_mismatch",manjuSource,manjuRemains);
            return true;
        }
        if(!CorpseFood.canProcessInFactory(remains.data())){
            releaseManjuRemains(remains);clearManjuRemains();return false;
        }
        if(delay>0||material<=0||output.size()>=16||worker.resting()||worker.isTrading()||!manjuStationReady(remains))return true;
        var bite=CorpseFood.take(remains,1);
        if(bite.isEmpty()){
            releaseManjuRemains(remains);clearManjuRemains();return false;
        }
        var result=new ItemStack(HumanWorld.MANJU.get());
        CustomData.update(DataComponents.CUSTOM_DATA,result,tag->{
            var data=remains.data();
            tag.putString("Method","factory_manju");tag.putFloat("Portions",bite.portions());tag.putFloat("Nutrition",bite.nutrition());
            tag.putInt("Odor",bite.odor());tag.putString("Taste",bite.taste());
            if(data.hasUUID("Identity"))tag.putUUID("Source",data.getUUID("Identity"));
            tag.putString("Variant",data.getString("Variant"));tag.putFloat("SourceScale",data.contains("SourceScale")?data.getFloat("SourceScale"):1);
        });
        output.add(result);material--;batches++;delay=200;
        GameplayLog.event(worker,"factory","action=produce product=manju target={} remains={} portions_used={} material={}->{} output={}->{} batches={}",
                manjuSource,manjuRemains,bite.portions(),material+1,material,output.size()-1,output.size(),batches);
        if(!CorpseFood.canProcessInFactory(remains.data())){
            releaseManjuRemains(remains);clearManjuRemains();
        }
        return true;
    }
    private boolean manjuStationReady(RemainsEntity remains){
        var steamer=station(HumanWorld.MANJU_STEAMER.get());
        return steamer!=null&&remains.distanceToSqr(Vec3.atCenterOf(steamer))<=36
                &&worker.distanceToSqr(remains)<=9&&worker.hasLineOfSight(remains);
    }
    private boolean claimManjuRemains(RemainsEntity remains,UUID source){
        var data=remains.data();
        if(remains.isRemoved()||!data.hasUUID("Identity")||!source.equals(data.getUUID("Identity")))return false;
        if(data.hasUUID(FACTORY_OWNER)&&!worker.getUUID().equals(data.getUUID(FACTORY_OWNER)))return false;
        var stack=remains.remains();CustomData.update(DataComponents.CUSTOM_DATA,stack,tag->{tag.putUUID(FACTORY_OWNER,worker.getUUID());tag.putUUID(FACTORY_SOURCE,source);});
        remains.setRemains(stack);
        var claimed=remains.data();return claimed.hasUUID(FACTORY_OWNER)&&worker.getUUID().equals(claimed.getUUID(FACTORY_OWNER))
                &&claimed.hasUUID(FACTORY_SOURCE)&&source.equals(claimed.getUUID(FACTORY_SOURCE));
    }
    private RemainsEntity currentManjuRemains(){
        if(!(worker.level() instanceof ServerLevel level)||manjuRemains==null)return null;
        if(!(level.getEntity(manjuRemains) instanceof RemainsEntity remains)||remains.isRemoved())return null;
        var data=remains.data();return data.hasUUID(FACTORY_OWNER)&&worker.getUUID().equals(data.getUUID(FACTORY_OWNER))
                &&data.hasUUID(FACTORY_SOURCE)&&manjuSource!=null&&manjuSource.equals(data.getUUID(FACTORY_SOURCE))?remains:null;
    }
    private void releaseManjuRemains(RemainsEntity remains){
        var data=remains.data();if(!data.hasUUID(FACTORY_OWNER)||!worker.getUUID().equals(data.getUUID(FACTORY_OWNER)))return;
        clearClaim(remains);
    }
    /** Unloaded remains release their claim after the owner's recorded death, never merely its absence. */
    public static void releaseDeadOwner(RemainsEntity remains){
        var data=remains.data();
        if(remains.level() instanceof ServerLevel level&&data.hasUUID(FACTORY_OWNER)
                &&CustodyData.get(level).deadNpc(data.getUUID(FACTORY_OWNER)))clearClaim(remains);
    }
    private static void clearClaim(RemainsEntity remains){
        var stack=remains.remains();CustomData.update(DataComponents.CUSTOM_DATA,stack,tag->{tag.remove(FACTORY_OWNER);tag.remove(FACTORY_SOURCE);});remains.setRemains(stack);
    }
    private void clearManjuRemains(){manjuSource=null;manjuRemains=null;}
    private RemainsEntity factoryRemains(UUID source,Vec3 at){
        return worker.level().getEntitiesOfClass(RemainsEntity.class,new AABB(at,at).inflate(2),remains->{
            var data=remains.data();return !remains.isRemoved()&&data.hasUUID("Identity")&&source.equals(data.getUUID("Identity"));
        }).stream().min(Comparator.comparingDouble(remains->remains.distanceToSqr(at))).orElse(null);
    }
    public void tick(){
        if(kind().isEmpty())return;if(delay>0)delay--;
        if(worker.resting()||worker.isTrading())return;
        if(worker.tickCount%40==3&&worker.level() instanceof ServerLevel sl)ShopDeliveries.collect(worker,sl,this::takeRecall);
        if(kind().equals("manju")&&processManjuRemains())return;
        if(!kind().equals("labor")){if(worker.tickCount%20==0)pipeline.tick();if(worker.tickCount%100==0)breeding.tick();return;}
        if(worker.tickCount%100!=0)return;
        if(kind().equals("labor")){
            var p=station(machine());if(p==null)return;
            for(var y:worker.animals(Custody.Role.INPUT))Custody.assign(y, worker, Custody.Role.LABOR);
            for(var y:worker.animals(Custody.Role.LABOR)){
                if(material<=0||output.size()>=16||delay>0)break;
                if(y.life().nutrition()<35||y.life().energy()<25||y.isPanicking())continue;
                var stand=Vec3.atBottomCenterOf(frontOf(p));
                if(y.distanceToSqr(stand)<3){
                    y.life().spendNutrition(3);material--;batches++;output.add(new ItemStack(Items.PAPER));delay=200;
                    GameplayLog.event(worker,"factory","action=produce product=paper target={} nutrition_used=3 material={}->{} output={}->{} batches={}",
                            y.getUUID(),material+1,material,output.size()-1,output.size(),batches);
                    y.showReaction("tired","",80);break;
                }
            }
        }
    }
    public CompoundTag save(){var t=new CompoundTag();t.putInt("Material",material);t.putInt("Batches",batches);t.putInt("Delay",delay);
        if(manjuSource!=null)t.putUUID("ManjuSource",manjuSource);if(manjuRemains!=null)t.putUUID("ManjuRemains",manjuRemains);
        var list=new ListTag();for(var s:output)list.add(s.save(worker.registryAccess()));t.put("Output",list);t.put("Pipeline",pipeline.save());t.put("Breeding",breeding.save());return t;}
    public void load(CompoundTag t){material=t.contains("Material")?Math.clamp(t.getInt("Material"),0,128):32;batches=Math.max(0,t.getInt("Batches"));delay=Math.clamp(t.getInt("Delay"),0,200);
        manjuSource=t.hasUUID("ManjuSource")?t.getUUID("ManjuSource"):null;manjuRemains=t.hasUUID("ManjuRemains")?t.getUUID("ManjuRemains"):null;
        stations.clear();retryStation.clear();pipeline.load(t.getCompound("Pipeline"));breeding.load(t.getCompound("Breeding"));
        output.clear();for(var s:t.getList("Output",10))if(output.size()<16){var item=ItemStack.parseOptional(worker.registryAccess(),(CompoundTag)s);if(!item.isEmpty())output.add(item);}}
}
