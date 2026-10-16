package io.github.longshou.mcyukkuri.p0;

import io.github.longshou.mcyukkuri.house.HouseholdProtection;
import io.github.longshou.mcyukkuri.item.HouseholdSprayItem;
import io.github.longshou.mcyukkuri.registry.ModBlocks;
import io.github.longshou.mcyukkuri.registry.ModItems;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Remote-only spray transactions. Synthetic clocks belong to detached data, never the shared level. */
@PrefixGameTestTemplate(false)
public final class HouseRepellentGameTests {
    @GameTest(templateNamespace=P0Mod.ID,template="empty",timeoutTicks=100,batch="house_ecology")
    public static void timedSprayRefreshesWithoutStackingAndPersistsBoundedExpiry(GameTestHelper h){
        var data=new HouseholdProtection();var pos=new BlockPos(15,65,-16);long now=1000;
        h.assertTrue(data.spray(pos,now)&&data.remainingTicks(pos.offset(8,4,8),now)==HouseholdProtection.DURATION
                &&data.remainingTicks(pos.offset(-8,-4,-8),now)>0&&data.remainingTicks(pos.east(9),now)==0
                &&data.remainingTicks(pos.above(5),now)==0,"all inclusive spray boundaries survive positive and negative chunk crossings");
        h.assertTrue(data.spray(pos,now+1000)&&data.size(now+1000)==1
                &&data.remainingTicks(pos,now+1000)==HouseholdProtection.DURATION,"same-anchor refresh starts one new duration, never adds durations");
        for(int i=1;i<HouseholdProtection.MAX_ANCHORS;i++)h.assertTrue(data.spray(pos.east(i*32),now+1000),"fill finite active spray anchors");
        h.assertTrue(!data.spray(pos.west(32),now+1000)&&data.size(now+1000)==HouseholdProtection.MAX_ANCHORS
                &&data.remainingTicks(pos.east(32),now+1000)==HouseholdProtection.DURATION,"a full store rejects new points without evicting active protection");
        h.assertTrue(data.spray(pos,now+2000)&&data.size(now+2000)==HouseholdProtection.MAX_ANCHORS,"a full store still permits refreshing its existing point");
        var saved=data.save(new CompoundTag(),h.getLevel().registryAccess());
        var loaded=HouseholdProtection.load(saved,h.getLevel().registryAccess());
        h.assertTrue(loaded.remainingTicks(pos.offset(-8,-4,-8),now+2000)==HouseholdProtection.DURATION
                &&loaded.size(now+2000)==HouseholdProtection.MAX_ANCHORS,"reload retains the exact expiry and rebuilds the spatial index");
        long oldExpiry=now+1000+HouseholdProtection.DURATION;
        h.assertTrue(loaded.remainingTicks(pos.east(32),oldExpiry-1)==1&&loaded.remainingTicks(pos.east(32),oldExpiry)==0
                &&loaded.remainingTicks(pos,oldExpiry)==1000&&loaded.size(oldExpiry)==1,"expiry is exact and pruning leaves the independently refreshed treatment intact");
        h.assertTrue(loaded.spray(pos.west(32),oldExpiry),"expired entries release capacity without evicting any live point");
        h.assertTrue(loaded.remainingTicks(pos,oldExpiry+1000)==0,"the refreshed area also expires at its own boundary");
        var legacy=new CompoundTag();legacy.putInt("Version",1);legacy.putLongArray("Fixtures",new long[]{pos.asLong()});
        h.assertTrue(HouseholdProtection.load(legacy,h.getLevel().registryAccess()).remainingTicks(pos,now)==0,"version-one fixtures cannot restore a permanent or free spray effect");
        h.succeed();
    }

    @GameTest(templateNamespace=P0Mod.ID,template="empty",timeoutTicks=100,batch="house_ecology")
    public static void blockInteractionDebitsOneUseAndRecoversLegacyBoxesOnce(GameTestHelper h){
        try(var f=new Fixture(h,144)){
            var item=ModItems.HOUSEHOLD_GUARD.get();var stack=new ItemStack(item,2); // A legal legacy save may contain a former BlockItem stack.
            f.player.setItemInHand(InteractionHand.MAIN_HAND,stack);
            f.level.setBlockAndUpdate(f.pos,Blocks.CHEST.defaultBlockState());
            var existing=HouseholdProtection.get(f.level);var full=new HouseholdProtection();
            for(int i=0;i<HouseholdProtection.MAX_ANCHORS;i++)full.spray(f.pos.offset(32+i*32,0,32),f.level.getGameTime());
            f.level.getDataStorage().set("mcyukkuri_household_protection",full);
            try{
                h.assertTrue(!f.use().consumesAction()&&stack.getCount()==2&&HouseholdSprayItem.uses(stack)==16&&f.bottles()==2
                        &&full.size(f.level.getGameTime())==HouseholdProtection.MAX_ANCHORS,
                        "capacity rejection leaves legacy bottles stacked, full and unconsumed without evicting existing treatments");
            }finally{f.level.getDataStorage().set("mcyukkuri_household_protection",existing);}
            h.assertTrue(f.use().consumesAction()&&HouseholdSprayItem.uses(stack)==15&&stack.getCount()==1
                    &&f.bottles()==2&&f.player.getInventory().items.stream().filter(s->s.is(item)).mapToInt(HouseholdSprayItem::uses).sum()==31
                    &&HouseholdProtection.protectedAt(f.level,f.pos)&&f.player.containerMenu==f.player.inventoryMenu,
                    "use-first sprays a chest once, retaining 15 uses in hand and a separate untouched 16-use bottle without opening the chest");
            h.assertTrue(!f.use().consumesAction()&&HouseholdSprayItem.uses(stack)==15,"immediate repeat is rejected by cooldown with no consumption");
            f.player.getCooldowns().removeCooldown(item);f.player.setShiftKeyDown(true);
            long before=HouseholdProtection.get(f.level).remainingTicks(f.pos,f.level.getGameTime());
            h.assertTrue(f.use().consumesAction()&&HouseholdSprayItem.uses(stack)==15
                    &&HouseholdProtection.get(f.level).remainingTicks(f.pos,f.level.getGameTime())==before
                    &&!f.player.getCooldowns().isOnCooldown(item),"sneak inspection neither spends spray, refreshes duration nor starts cooldown");
            f.player.setShiftKeyDown(false);f.player.setGameMode(GameType.ADVENTURE);
            h.assertTrue(f.player.gameMode.getGameModeForPlayer()==GameType.ADVENTURE&&!f.player.getAbilities().mayBuild
                    &&!f.player.mayBuild()&&!f.player.getAbilities().instabuild,"the fixture really entered adventure permissions: "+f.state(null));
            HouseholdProtection.get(f.level).remove(f.pos);
            // NeoForge's ItemStack hook returns PASS before calling the Item when adventure forbids
            // placement. The chest may then consume its own ordinary interaction, without spraying.
            var chestResult=f.use();
            h.assertTrue(chestResult.consumesAction()&&HouseholdSprayItem.uses(stack)==15&&f.bottles()==2
                    &&!HouseholdProtection.protectedAt(f.level,f.pos)&&!f.player.getCooldowns().isOnCooldown(item),
                    "adventure chest interaction cannot create a spray anchor, spend a charge or start spray cooldown: "+f.state(chestResult));
            f.level.setBlockAndUpdate(f.pos,Blocks.STONE.defaultBlockState());
            var deniedResult=f.use();
            h.assertTrue(!deniedResult.consumesAction()&&HouseholdSprayItem.uses(stack)==15&&f.bottles()==2
                    &&!HouseholdProtection.protectedAt(f.level,f.pos)&&!f.player.getCooldowns().isOnCooldown(item),
                    "adventure permissions reject spraying an untreated solid block without spending it: "+f.state(deniedResult));
            f.player.setGameMode(GameType.SURVIVAL);
            for(int i=0;i<15;i++){f.player.getCooldowns().removeCooldown(item);h.assertTrue(f.use().consumesAction(),"remaining legacy bottle use succeeds");}
            h.assertTrue(stack.isEmpty()&&f.bottles()==1&&f.player.getInventory().items.stream().filter(s->s.is(item)).allMatch(s->HouseholdSprayItem.uses(s)==16),
                    "sixteen sprays empty only the active bottle and leave the other bottle completely unused");
            for(int i=0;i<9;i++)if(f.player.getInventory().getItem(i).is(item)){f.player.getInventory().selected=i;break;}
            stack=f.player.getMainHandItem();f.player.getCooldowns().removeCooldown(item);
            h.assertTrue(f.use().consumesAction()&&HouseholdSprayItem.uses(stack)==15,"the next bottle has its own full supply");
            var restored=ItemStack.parse(f.level.registryAccess(),stack.save(f.level.registryAccess())).orElseThrow();
            h.assertTrue(restored.getCount()==1&&HouseholdSprayItem.uses(restored)==15,"remaining uses survive item component serialization");

            HouseholdProtection.get(f.level).remove(f.pos);f.player.getInventory().clearContent();f.player.getCooldowns().removeCooldown(item);
            f.level.setBlockAndUpdate(f.pos,ModBlocks.HOUSEHOLD_GUARD.get().defaultBlockState());
            h.assertTrue(!HouseholdProtection.protectedAt(f.level,f.pos),"placing a legacy block no longer registers permanent protection");
            f.player.setGameMode(GameType.ADVENTURE);f.use();
            h.assertTrue(f.level.getBlockState(f.pos).is(ModBlocks.HOUSEHOLD_GUARD.get())&&f.bottles()==0,"legacy recovery respects build permission");
            f.player.setGameMode(GameType.SURVIVAL);
            h.assertTrue(f.use().consumesAction()&&f.level.isEmptyBlock(f.pos)&&f.bottles()==1,"empty-hand recovery removes the old block and returns exactly one finite bottle");
            f.player.getInventory().selected=8;f.use();
            h.assertTrue(f.bottles()==1&&!HouseholdProtection.protectedAt(f.level,f.pos),"repeated recovery cannot duplicate bottles or leave hidden protection");
        }
        h.succeed();
    }

    @GameTest(templateNamespace=P0Mod.ID,template="empty",timeoutTicks=100,batch="house_ecology")
    public static void savedSprayExpiresOnRealTicksWithoutMovingWorldTime(GameTestHelper h){
        var f=new Fixture(h,148);long[] expiry={0};
        h.testInfo.addListener(new net.minecraft.gametest.framework.GameTestListener(){
            public void testStructureLoaded(net.minecraft.gametest.framework.GameTestInfo info){}
            public void testPassed(net.minecraft.gametest.framework.GameTestInfo info,net.minecraft.gametest.framework.GameTestRunner runner){f.close();}
            public void testFailed(net.minecraft.gametest.framework.GameTestInfo info,net.minecraft.gametest.framework.GameTestRunner runner){f.close();}
            public void testAddedForRerun(net.minecraft.gametest.framework.GameTestInfo oldInfo,net.minecraft.gametest.framework.GameTestInfo newInfo,net.minecraft.gametest.framework.GameTestRunner runner){}
        });
        h.startSequence().thenExecute(()->{
            f.player.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(ModItems.HOUSEHOLD_GUARD.get()));
            h.assertTrue(f.use().consumesAction(),"a real item interaction starts the area");
            var saved=HouseholdProtection.get(f.level).save(new CompoundTag(),f.level.registryAccess());
            var entries=saved.getList("Sprays",Tag.TAG_COMPOUND);expiry[0]=f.level.getGameTime()+30;
            // Shorten only this fixture's saved record; other parallel tests retain their real durations.
            for(int i=0;i<entries.size();i++)if(entries.getCompound(i).getLong("Pos")==f.pos.asLong())entries.getCompound(i).putLong("Until",expiry[0]);
            f.level.getDataStorage().set("mcyukkuri_household_protection",HouseholdProtection.load(saved,f.level.registryAccess()));
            h.assertTrue(HouseholdProtection.get(f.level).remainingTicks(f.pos,f.level.getGameTime())==30,"the reloaded record keeps its saved remaining ticks");
        }).thenWaitUntil(()->{
            long now=f.level.getGameTime();
            if(now<expiry[0])h.assertTrue(HouseholdProtection.protectedAt(f.level,f.pos),"treatment must remain active before expiry");
            h.assertTrue(now>=expiry[0],"waiting for actual server ticks to reach the saved expiry");
        }).thenExecute(()->h.assertTrue(!HouseholdProtection.protectedAt(f.level,f.pos),"actual expiry ends protection without a placed fixture or ticking block entity")).thenSucceed();
    }

    private static final class Fixture implements AutoCloseable{
        final ServerLevel level;final BlockPos pos;final BlockState previous;final FakePlayer player;boolean closed;
        Fixture(GameTestHelper h,int chunk){
            level=h.getLevel();pos=new BlockPos(chunk*16+8,65,8);previous=level.getBlockState(pos);
            level.setBlockAndUpdate(pos,Blocks.STONE.defaultBlockState());
            player=FakePlayerFactory.get(level,new com.mojang.authlib.GameProfile(UUID.randomUUID(),"SprayTest"));
            player.setGameMode(GameType.SURVIVAL);player.getInventory().clearContent();player.moveTo(pos.getX()+.5,pos.getY()+1,pos.getZ()-1,0,0);
        }
        InteractionResult use(){return player.gameMode.useItemOn(player,level,player.getMainHandItem(),InteractionHand.MAIN_HAND,new BlockHitResult(Vec3.atCenterOf(pos).add(0,.5,0),Direction.UP,pos,false));}
        int bottles(){return player.getInventory().items.stream().filter(s->s.is(ModItems.HOUSEHOLD_GUARD.get())).mapToInt(ItemStack::getCount).sum();}
        String state(InteractionResult result){return "result="+result+", mode="+player.gameMode.getGameModeForPlayer()+", mayBuild="+player.mayBuild()
                +", abilityBuild="+player.getAbilities().mayBuild+", creative="+player.getAbilities().instabuild+", sneak="+player.isShiftKeyDown()
                +", held="+player.getMainHandItem()+", uses="+(player.getMainHandItem().isEmpty()?0:HouseholdSprayItem.uses(player.getMainHandItem()))
                +", bottles="+bottles()+", remaining="+HouseholdProtection.get(level).remainingTicks(pos,level.getGameTime())
                +", cooldown="+player.getCooldowns().isOnCooldown(ModItems.HOUSEHOLD_GUARD.get())+", menu="+player.containerMenu.getClass().getSimpleName()
                +", block="+level.getBlockState(pos);}
        @Override public void close(){if(closed)return;closed=true;HouseholdProtection.get(level).remove(pos);level.setBlockAndUpdate(pos,previous);player.getInventory().clearContent();player.setShiftKeyDown(false);player.getCooldowns().removeCooldown(ModItems.HOUSEHOLD_GUARD.get());}
    }
    private HouseRepellentGameTests(){}
}
