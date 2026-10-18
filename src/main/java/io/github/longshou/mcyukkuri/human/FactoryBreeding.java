package io.github.longshou.mcyukkuri.human;

import io.github.longshou.mcyukkuri.social.Bonds;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.family.FamilyData;
import io.github.longshou.mcyukkuri.life.YukkuriWorldData;
import java.util.*;
import io.github.longshou.mcyukkuri.shop.CommodityLife;
import net.minecraft.core.GlobalPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;

/** Two alternating pairs, authoritative unborn reservations and finite parent service lives. */
public final class FactoryBreeding {
    private final HumanNpc worker;
    private int nextGroup;
    public FactoryBreeding(HumanNpc worker) { this.worker=worker; }
    public static boolean member(YukkuriEntity y) { return Set.of("breeder","reserve_parent").contains(HumanCommerce.data(y).getString("Use")); }
    public int population() {
        var family=FamilyData.get((ServerLevel)worker.level());
        Set<UUID> parents=new HashSet<>();worker.animals("breeder").forEach(y->parents.add(y.getUUID()));
        // A removed or unloaded factory child must not silently free a reservation.
        int unborn=family.reservedForResidents(parents::contains);
        return worker.production().pipeline().trackedPopulation()+worker.animals("reserve_parent").size()+unborn;
    }
    public boolean allows(YukkuriEntity a,YukkuriEntity b,int count) {
        var ta=HumanCommerce.data(a);var tb=HumanCommerce.data(b);long now=worker.level().getGameTime();
        return ta.getString("Use").equals("breeder") && tb.getString("Use").equals("breeder")
                && !FactoryPipeline.managed(a) && !FactoryPipeline.managed(b)
                && ta.getInt("FactoryGroup")==tb.getInt("FactoryGroup") && ta.getInt("FactoryGroup")==nextGroup
                && ta.getLong("FactoryRestUntil")<=now && tb.getLong("FactoryRestUntil")<=now
                && ta.getInt("FactoryCycles")<6 && tb.getInt("FactoryCycles")<6 && population()+count<=8;
    }
    public void conceived(YukkuriEntity a,YukkuriEntity b) {
        for(var y:List.of(a,b)){var tag=HumanCommerce.data(y).copy();tag.putInt("FactoryCycles",tag.getInt("FactoryCycles")+1);
            tag.putLong("FactoryRestUntil",worker.level().getGameTime()+1200);HumanCommerce.set(y,tag);
            wear(y,tag.getInt("FactoryCycles"));}
        nextGroup=1-nextGroup;
    }
    /** The parent animal exists only to produce (komicolle 11518786): every litter leaves it more anxious and shabbier, until it is retired into the line itself. */
    private void wear(YukkuriEntity y,int cycles) {
        int h=CommodityLife.harshness();if(h==0)return;
        CommodityLife.pressure(y,5f*h,"factory_breeder");
        y.life().applyEnvironment(0,6*h,4*h);
        y.showReaction("cry","",100);
    }
    /** Between litters the strain does not leave: unease and grime settle at a level that grows with the number of litters served. */
    private void strain(YukkuriEntity y,int cycles) {
        int h=CommodityLife.harshness();if(h==0||cycles==0)return;
        float floor=Math.min(CommodityLife.UNEASE_CAP,cycles*8f*h);
        if(y.mind().unease()<floor)y.mind().add(2f,"factory_breeder");
        float grime=100-cycles*10f*h;
        if(y.life().hygiene()>Math.max(20f,grime))y.life().applyEnvironment(0,2,0);
    }
    public void tick() {
        if(!worker.production().pipeline().fullyLoaded())return;
        var level=(ServerLevel)worker.level();var family=FamilyData.get(level);
        for(var y:worker.animals("breeder")) {
            var tag=HumanCommerce.data(y).copy();
            strain(y,tag.getInt("FactoryCycles"));
            if((y.family().elder() || tag.getInt("FactoryCycles")>=6) && !family.busyParent(y.getUUID())) {
                y.life().releaseHome();tag.putString("Use","input");tag.putBoolean("RetiredParent",true);tag.remove("FactoryStage");HumanCommerce.set(y,tag);
            }
        }
        for(int group=0;group<2;group++) {
            final int g=group;
            var current=worker.animals("breeder").stream().filter(y->HumanCommerce.data(y).getInt("FactoryGroup")==g).toList();
            if(current.size()>=2)continue;
            // Replacement pairs are supplied as real adults. A surviving old mate retires with its partner.
            for(var y:current)if(!family.busyParent(y.getUUID())) {
                y.life().releaseHome();var tag=HumanCommerce.data(y).copy();tag.putString("Use","input");tag.putBoolean("RetiredParent",true);tag.remove("FactoryStage");HumanCommerce.set(y,tag);
            }
            if(!worker.animals("breeder").stream().noneMatch(y->HumanCommerce.data(y).getInt("FactoryGroup")==g))continue;
            var reserve=worker.animals("reserve_parent");
            outer:for(var a:reserve)for(var b:reserve) {
                if(a==b || !a.family().adult() || !b.family().adult() || a.family().elder() || b.family().elder()
                        || a.family().sterile() || b.family().sterile() || a.profile().houseBody() || b.profile().houseBody()
                        || !FactoryPipeline.healthy(a) || !FactoryPipeline.healthy(b) || Bonds.biological(a, b)
                        || a.family().partner()!=null&&!a.family().partner().equals(b.getUUID())
                        || b.family().partner()!=null&&!b.family().partner().equals(a.getUUID()))continue;
                for(var y:List.of(a,b)) {var tag=HumanCommerce.data(y).copy();tag.putString("Use","breeder");tag.putInt("FactoryGroup",g);
                    tag.putInt("FactoryCycles",0);tag.putInt("FactoryParentSlot",y==a?0:1);tag.putString("FactoryStage","parent_transfer");y.life().releaseHome();HumanCommerce.set(y,tag);}
                var relationships=YukkuriWorldData.get(level);relationships.relate(a.getUUID(),b.getUUID(),20);relationships.relate(b.getUUID(),a.getUUID(),20);break outer;
            }
        }
        // If one group is not ready, the other may work; no ready pair is starved by a missing mate.
        for(int offset=0;offset<2;offset++) {
            int group=(nextGroup+offset)%2;
            var pair=worker.animals("breeder").stream().filter(y->HumanCommerce.data(y).getInt("FactoryGroup")==group).toList();
            if(pair.size()!=2)continue;
            var a=pair.get(0);var b=pair.get(1);
            if(a.family().canReproduceWith(b)) {
                nextGroup=group;
                if(family.conceive(a,b,FamilyData.BirthKind.STEM,GlobalPos.of(level.dimension(),a.life().homeStand()))!=null)return;
            }
        }
    }
    public CompoundTag save(){var t=new CompoundTag();t.putInt("NextGroup",nextGroup);return t;}
    public void load(CompoundTag t){nextGroup=Math.clamp(t.getInt("NextGroup"),0,1);}
}
