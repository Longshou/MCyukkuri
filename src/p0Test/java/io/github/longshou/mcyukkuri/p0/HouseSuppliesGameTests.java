package io.github.longshou.mcyukkuri.p0;

import io.github.longshou.mcyukkuri.house.SupplyPileBlockEntity;
import io.github.longshou.mcyukkuri.house.SupplyPiles;
import io.github.longshou.mcyukkuri.registry.ModItems;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Remote-only finite-stock checks through the actual pile inventory and player interaction. */
@PrefixGameTestTemplate(false)
public final class HouseSuppliesGameTests {
    @GameTest(templateNamespace=P0Mod.ID,template="empty",timeoutTicks=100,batch="house_ecology")
    public static void finitePileRejectsMixingAndRetainsComponentsAfterReload(GameTestHelper h) {
        try(var f=new Fixture(h)) {
            var source=scraps(60);
            source.shrink(SupplyPiles.offer(f.level,f.pos,source,source.getCount()));
            var pile=f.pile();
            h.assertTrue(source.isEmpty()&&pile.count()==60,"offered stock moves from the caller into one finite pile");
            var lint=new ItemStack(ModItems.NEST_LINT.get(),2);
            var different=scraps(2);CustomData.update(DataComponents.CUSTOM_DATA,different,t->t.putString("Batch","other"));
            h.assertTrue(SupplyPiles.offer(f.level,f.pos,lint,2)==0&&lint.getCount()==2
                    &&SupplyPiles.offer(f.level,f.pos,different,2)==0&&different.getCount()==2&&pile.count()==60,
                    "an existing pile rejects a different material or component batch without consuming or mixing either source");
            var overflow=scraps(8);int accepted=SupplyPiles.offer(f.level,f.pos,overflow,8);overflow.shrink(accepted);
            h.assertTrue(accepted==4&&overflow.getCount()==4&&pile.count()==64
                    &&SupplyPiles.offer(f.level,f.pos,overflow,4)==0,
                    "a cell holds exactly 64 supplies and leaves every rejected surplus item with its caller");
            var displayed=pile.contents();displayed.shrink(63);
            h.assertTrue(pile.count()==64,"reading or changing a contents snapshot cannot mutate the stored stock");
            var saved=pile.saveWithFullMetadata(f.level.registryAccess());
            // Rebuild the actual block entity from its saved data, as a chunk reload does.
            f.level.removeBlockEntity(f.pos);
            var restored=new SupplyPileBlockEntity(f.pos,f.level.getBlockState(f.pos));
            restored.loadWithComponents(saved,f.level.registryAccess());f.level.setBlockEntity(restored);
            h.assertTrue(f.pile()==restored&&restored.count()==64&&ItemStack.isSameItemSameComponents(restored.contents(),scraps(1)),
                    "the reloaded physical pile retains its full quantity, custom name and custom data components");
            h.assertTrue(pile.takeOne().isEmpty()&&restored.count()==64,
                    "a stale pre-reload block entity cannot hand out a second copy of saved inventory");
        }
        h.succeed();
    }

    @GameTest(templateNamespace=P0Mod.ID,template="empty",timeoutTicks=100,batch="house_ecology")
    public static void takingLastSupplyAndPlayerRecoveryRemoveStockExactlyOnce(GameTestHelper h) {
        try(var f=new Fixture(h)) {
            h.assertTrue(SupplyPiles.offer(f.level,f.pos,scraps(1),1)==1,"one real scrap creates one pile");
            var old=f.pile();var last=old.takeOne();
            h.assertTrue(last.getCount()==1&&ItemStack.isSameItemSameComponents(last,scraps(1))
                    &&f.level.isEmptyBlock(f.pos)&&f.level.getBlockEntity(f.pos)==null&&f.looseCount()==0,
                    "taking the final portion removes its ground block without also dropping the same supply");
            h.assertTrue(old.takeOne().isEmpty()&&old.takeAll().isEmpty(),"the removed pile cannot serve its final item again");
            h.assertTrue(SupplyPiles.offer(f.level,f.pos,scraps(12),12)==12,"the same floor cell can host a new independent pile");
            var pile=f.pile();var player=f.player();
            player.setShiftKeyDown(true);
            var hit=new BlockHitResult(Vec3.atBottomCenterOf(f.pos).add(0,.025,0),Direction.UP,f.pos,false);
            var result=player.gameMode.useItemOn(player,f.level,player.getMainHandItem(),InteractionHand.MAIN_HAND,hit);
            h.assertTrue(result.consumesAction()&&f.inventoryCount()==12&&f.level.isEmptyBlock(f.pos)
                    &&f.level.getBlockEntity(f.pos)==null&&f.looseCount()==0,
                    "a survival player's empty-hand crouch interaction recovers the whole pile into inventory exactly once");
            h.assertTrue(player.getInventory().items.stream().filter(s->s.is(ModItems.FOOD_SCRAP.get()))
                    .allMatch(s->ItemStack.isSameItemSameComponents(s,scraps(1))),
                    "whole-pile recovery preserves item components instead of recreating generic scraps");
            player.getInventory().selected=8; // Leave the recovered stock in slot zero and retry with an empty hand.
            player.gameMode.useItemOn(player,f.level,player.getMainHandItem(),InteractionHand.MAIN_HAND,hit);
            player.gameMode.useItemOn(player,f.level,player.getMainHandItem(),InteractionHand.MAIN_HAND,hit);
            h.assertTrue(pile.takeAll().isEmpty()&&old.takeOne().isEmpty()&&f.inventoryCount()+last.getCount()==13&&f.looseCount()==0,
                    "repeated clicks and stale references leave exactly the original thirteen supplies across both withdrawals");
        }
        h.succeed();
    }

    private static ItemStack scraps(int count) {
        var stack=new ItemStack(ModItems.FOOD_SCRAP.get(),count);
        stack.set(DataComponents.CUSTOM_NAME,Component.literal("Saved house supplies"));
        CustomData.update(DataComponents.CUSTOM_DATA,stack,t->t.putString("Batch","house-supplies-test"));
        return stack;
    }
    private static final class Fixture implements AutoCloseable {
        final ServerLevel level;
        final BlockPos pos;
        final Map<BlockPos,BlockState> blocks=new LinkedHashMap<>();
        FakePlayer player;
        Fixture(GameTestHelper h) {
            level=h.getLevel();pos=h.absolutePos(new BlockPos(1,2,1));
            blocks.put(pos,level.getBlockState(pos));blocks.put(pos.below(),level.getBlockState(pos.below()));
            level.setBlockAndUpdate(pos,Blocks.AIR.defaultBlockState());
            level.setBlockAndUpdate(pos.below(),Blocks.STONE.defaultBlockState());
        }
        SupplyPileBlockEntity pile() {
            if(!(level.getBlockEntity(pos) instanceof SupplyPileBlockEntity value))throw new IllegalStateException("Missing real supply pile at "+pos);
            return value;
        }
        FakePlayer player() {
            if(player==null) {
                player=FakePlayerFactory.get(level,new com.mojang.authlib.GameProfile(UUID.randomUUID(),"SupplyRecovery"));
                player.setGameMode(GameType.SURVIVAL);player.getInventory().clearContent();
                player.moveTo(pos.getX()+.5,pos.getY(),pos.getZ()-1,0,0);
                player.setItemInHand(InteractionHand.MAIN_HAND,ItemStack.EMPTY);
            }
            return player;
        }
        int inventoryCount(){return player.getInventory().items.stream().filter(s->s.is(ModItems.FOOD_SCRAP.get())).mapToInt(ItemStack::getCount).sum();}
        int looseCount(){return level.getEntitiesOfClass(ItemEntity.class,new AABB(pos).inflate(1)).stream().map(ItemEntity::getItem).filter(s->s.is(ModItems.FOOD_SCRAP.get())).mapToInt(ItemStack::getCount).sum();}
        @Override public void close() {
            if(level.getBlockEntity(pos) instanceof SupplyPileBlockEntity pile)pile.drain();
            blocks.forEach(level::setBlockAndUpdate);
            level.getEntitiesOfClass(ItemEntity.class,new AABB(pos).inflate(1)).forEach(ItemEntity::discard);
            if(player!=null){player.getInventory().clearContent();player.setShiftKeyDown(false);}
        }
    }
    private HouseSuppliesGameTests(){}
}
