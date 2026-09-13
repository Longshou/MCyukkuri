package io.github.longshou.mcyukkuri.p0;

import io.github.longshou.mcyukkuri.MCYukkuri;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.human.*;
import io.github.longshou.mcyukkuri.item.CarrierItem;
import io.github.longshou.mcyukkuri.life.YukkuriWorldData;
import io.github.longshou.mcyukkuri.registry.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.*;
import net.minecraft.world.item.*;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.phys.*;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@PrefixGameTestTemplate(false)
public final class HumanGameTests {
    private record Check(String name, boolean pass) {}
    private final List<Check> checks = new ArrayList<>();
    private void check(String name, boolean pass) { checks.add(new Check(name, pass)); }
    private static final AABB AREA = new AABB(990, 62, -2, 1110, 84, 75);
    @GameTest(templateNamespace=P0Mod.ID, template="empty", timeoutTicks=700, batch="human_world")
    public static void humanWorldAcceptance(GameTestHelper h) {
        var t = new HumanGameTests(); var level = h.getLevel();
        for (int x=62;x<=69;x++) for (int z=0;z<=4;z++) level.setChunkForced(x,z,true);
        h.startSequence().thenWaitUntil(() -> {
            for (int x=62;x<=69;x++) for (int z=0;z<=4;z++)
                h.assertTrue(level.isPositionEntityTicking(new BlockPos(x*16+8,67,z*16+8)),"Waiting for all human fixture chunks");
        })
            .thenExecute(() -> {
                level.setDayTime(6000); level.getEntitiesOfClass(Entity.class,AREA,e->!(e instanceof net.minecraft.world.entity.player.Player)).forEach(Entity::discard);
                var templates = level.getStructureManager();
                var shop = templates.get(MCYukkuri.id("yukkuri_shop")).orElseThrow();
                var factory = templates.get(MCYukkuri.id("yukkuri_factory")).orElseThrow();
                t.check("Shop template has its authored footprint",shop.getSize().equals(new BlockPos(25,13,25)));
                t.check("Factory template has its authored footprint",factory.getSize().equals(new BlockPos(37,13,31)));
                var settings = new StructurePlaceSettings().setRotation(Rotation.CLOCKWISE_90).setIgnoreEntities(false).setFinalizeEntities(true);
                t.check("Rotated shop places through vanilla StructureTemplate",shop.placeInWorld(level,new BlockPos(1030,64,0),new BlockPos(1030,64,0),settings,level.random,2));
                t.check("Factory places through vanilla StructureTemplate",factory.placeInWorld(level,new BlockPos(1060,64,36),new BlockPos(1060,64,36),new StructurePlaceSettings().setIgnoreEntities(false).setFinalizeEntities(true),level.random,2));
                level.getEntitiesOfClass(HumanNpc.class,AREA).forEach(n->{n.aiStep();n.setNoAi(true);});
                level.getEntitiesOfClass(YukkuriEntity.class,AREA).forEach(y->y.setNoAi(true));
            }).thenWaitUntil(() -> h.assertTrue(level.getEntitiesOfClass(YukkuriEntity.class,AREA).stream()
                    .noneMatch(y->HumanCommerce.data(y).contains("PendingRole")),"Waiting for stock custody binding"))
            .thenExecute(() -> {
                try { t.run(level); } catch(Throwable e) { t.check("Unexpected exception: "+e,false); }
                finally {
                    level.getEntitiesOfClass(Entity.class,AREA,e->!(e instanceof net.minecraft.world.entity.player.Player)).forEach(Entity::discard);
                    for(int x=62;x<=69;x++)for(int z=0;z<=4;z++)level.setChunkForced(x,z,false);
                }
                P0Control.write("human-world-checks",t.checks);
                var failed=t.checks.stream().filter(c->!c.pass).toList();if(failed.isEmpty())h.succeed();else h.fail(failed.toString());
            });
    }
    private void run(ServerLevel level) {
        level.setDayTime(6000);
        var humans=level.getEntitiesOfClass(HumanNpc.class,AREA);
        check("Both structures contain five finite human residents",humans.size()==5);
        var shop=humans.stream().filter(n->n.job()==HumanNpc.Job.SHOPKEEPER).findFirst().orElseThrow();
        var worker=humans.stream().filter(n->n.job()==HumanNpc.Job.FACTORY).findFirst().orElseThrow();
        var sister=humans.stream().filter(n->n.look()==HumanNpc.Look.SISTER).findFirst().orElseThrow();
        var oni=humans.stream().filter(n->n.look()==HumanNpc.Look.ONIISAN).findFirst().orElseThrow();
        check("Profession and attitude are separate",shop.attitude()==HumanNpc.Attitude.NEUTRAL&&worker.attitude()==HumanNpc.Attitude.NEUTRAL&&oni.attitude()==HumanNpc.Attitude.CRUEL);
        check("Rotated structure stock binds all six real animals to its keeper ("+shop.animals("stock").size()+")",shop.animals("stock").size()==6);
        check("Shop stocks ordinary, silver and gold education grades",shop.animals("stock").stream().map(y->y.life().medal()).collect(java.util.stream.Collectors.toSet()).equals(Set.of(0,1,2)));
        check("Factory reserves are not silently processed",worker.animals("reserve").size()==4&&worker.animals("input").isEmpty());
        check("Missing companion has a persistent exact identity",sister.lostAnimal()!=null);
        var p=FakePlayerFactory.get(level,new com.mojang.authlib.GameProfile(UUID.fromString("ce1a6418-fdea-46fd-b797-c8ad9daf17ed"),"HumanAcceptance"));
        p.getInventory().clearContent(); p.getInventory().add(new ItemStack(Items.EMERALD,64));
        p.moveTo(shop.position().add(0,0,1));
        // This rotated gallery position is kept free, on the keeper's side of the counter.
        p.moveTo(shop.position().add(0,0,-1));
        var animal=shop.animals("stock").stream().sorted(Comparator.comparingInt((YukkuriEntity y)->y.getVariant().ordinal())
                .thenComparing(Comparator.comparingInt((YukkuriEntity y)->y.life().medal()).reversed()).thenComparing(YukkuriEntity::getUUID)).findFirst().orElseThrow();UUID identity=animal.getUUID();
        check("Unsold stock cannot be taken with a free carrier",!HumanCommerce.mayCapture(p,animal));
        var originalFamily=animal.family().save();var originalPersonality=animal.life().personality().save();
        var menu=new HumanMenu(1,p.getInventory(),shop.getId());p.containerMenu=menu;
        check("Insufficient funds cannot remove a live individual",rejectWithoutFunds(p,menu,animal));
        check("Purchase transfers the actual animal",menu.clickMenuButton(p,4)&&animal.isRemoved());
        ItemStack held=p.getInventory().items.stream().filter(s->s.is(ModItems.CARRIER.get())).findFirst().orElseThrow();
        UUID token=CarrierItem.token(held);var snapshot=HumanCommerce.heldData(level,held);
        check("Purchase preserves UUID, family and education",snapshot.getUUID("UUID").equals(identity)&&snapshot.getCompound("YukkuriFamily").equals(originalFamily)
                &&snapshot.getCompound("YukkuriLife").getCompound("Personality").equals(originalPersonality));
        check("Purchased carrier belongs to buyer",snapshot.getCompound("NeoForgeData").getCompound("HumanCommerce").getUUID("Owner").equals(p.getUUID()));
        int cash=count(p,Items.EMERALD);
        check("A repeated stale quote cannot charge or clone",!menu.clickMenuButton(p,4)&&count(p,Items.EMERALD)==cash&&YukkuriWorldData.get(level).hasToken(token));
        // Put the real purchased carrier into the main hand for factory handover.
        ItemStack carried=held.copy();held.setCount(0);p.getInventory().selected=8;p.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,carried);
        p.moveTo(worker.position().add(-1,0,0));var factory=new HumanMenu(2,p.getInventory(),worker.getId());p.containerMenu=factory;
        check("Factory accepts an authoritative owned carrier",factory.clickMenuButton(p,8));
        check("Delivery retires its token and keeps the same individual",!YukkuriWorldData.get(level).hasToken(token)
                &&worker.animals("input").stream().anyMatch(y->y.getUUID().equals(identity))&&CarrierItem.token(carried)==null);
        cash=count(p,Items.EMERALD);check("Delivery cannot pay twice",!factory.clickMenuButton(p,8)&&count(p,Items.EMERALD)==cash);
        var input=(YukkuriEntity)level.getEntity(identity);worker.moveTo(input.position().add(1.1,0,0));
        int filling=input.body().filling();float health=input.getHealth();
        check("Worker processes only the delivered physical input",worker.attend(input)&&input.getHealth()<health&&input.body().filling()<filling);
        check("Oniisan respects sold and factory-owned individuals",!oni.canTorment(input));
        input.discard();
        var lost=(YukkuriEntity)level.getEntity(sister.lostAnimal());
        p.moveTo(sister.position().add(-1,0,0));var rescue=new HumanMenu(3,p.getInventory(),sister.getId());p.containerMenu=rescue;
        check("Caretaker offers the recorded missing companion",rescue.clickMenuButton(p,9));
        UUID rescueToken=YukkuriWorldData.get(level).capture(lost);var rescueCarrier=HumanCommerce.carrier(lost,rescueToken);p.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,rescueCarrier);
        check("Exact missing companion returns and earns reward",rescue.clickMenuButton(p,10)&&sister.questComplete());
        cash=count(p,Items.EMERALD);check("Completed rescue cannot be rewarded twice",!rescue.clickMenuButton(p,10)&&count(p,Items.EMERALD)==cash);
        var saved=new CompoundTag();sister.save(saved);var clone=HumanWorld.SISTER.get().create(level);clone.load(saved);
        check("NPC home, quest identity, completion and supplies survive NBT",clone.home().equals(sister.home())&&clone.lostAnimal().equals(sister.lostAnimal())
                &&clone.questComplete()&&clone.careFood()==sister.careFood());
        p.getInventory().clearContent();p.getInventory().add(new ItemStack(Items.EMERALD,64));
        int limit=sister.remainingTrades();
        for(int i=0;i<limit;i++)check("Finite supply trade "+i,rescue.clickMenuButton(p,0));
        check("Daily supply limit is enforced",!rescue.clickMenuButton(p,0));
        saved=new CompoundTag();sister.save(saved);clone.load(saved);check("Reload does not refresh daily trade quota",clone.remainingTrades()==0);
        level.setDayTime(18000);check("Nighttime trade is refused",!rescue.clickMenuButton(p,0));level.setDayTime(6000);
        p.moveTo(1100,67,70);check("Menu rejects remote operations",!rescue.clickMenuButton(p,11));
        p.containerMenu=p.inventoryMenu;p.getInventory().clearContent();
    }
    private boolean rejectWithoutFunds(net.minecraft.world.entity.player.Player p,HumanMenu menu,YukkuriEntity y) {
        var cash=p.getInventory().getItem(0).copy();p.getInventory().setItem(0,ItemStack.EMPTY);
        boolean result=!menu.clickMenuButton(p,4)&&y.isAlive()&&!y.isRemoved();p.getInventory().setItem(0,cash);return result;
    }
    private int count(net.minecraft.world.entity.player.Player p,Item item) {return p.getInventory().items.stream().filter(s->s.is(item)).mapToInt(ItemStack::getCount).sum();}
}
