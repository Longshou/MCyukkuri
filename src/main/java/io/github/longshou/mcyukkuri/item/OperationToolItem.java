package io.github.longshou.mcyukkuri.item;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.interaction.CareMenu;
import io.github.longshou.mcyukkuri.registry.ModItems;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

public final class OperationToolItem extends Item {
    public enum Tool {
        PIN("pin_tool",true),MALLET("care_mallet",false),AWL("care_awl",false),HEATED_AWL("heated_awl",false),
        RESTRAINT("restraint_frame",true),SLING("wrap_sling",true),HOOK("hanging_hook",true),
        CLIPPERS("hair_clippers",false),CUTTER("cutting_knife",false),
        VIBRATOR("care_vibrator",true),STERILIZER("sterilizing_shears",false),BOX("iron_enclosure",true),
        STEM_SHEARS("stem_shears",false),BOTTLE_SHEARS("bottle_stem_shears",false);
        public final String id;
        public final boolean inserted;
        Tool(String id,boolean inserted){this.id=id;this.inserted=inserted;}
        public Item item(){return ModItems.OPERATION_TOOLS.get(this).get();}
    }
    private final Tool tool;
    public OperationToolItem(Tool tool,Properties properties){super(properties);this.tool=tool;}
    public Tool tool(){return tool;}
    public static boolean isHot(ItemStack stack,net.minecraft.world.level.Level level){
        return stack.is(Tool.HEATED_AWL.item())&&stack.getOrDefault(net.minecraft.core.component.DataComponents.CUSTOM_DATA,
                net.minecraft.world.item.component.CustomData.EMPTY).copyTag().getLong("HeatUntil")>level.getGameTime();
    }
    private static ItemStack temperature(ItemStack stack,net.minecraft.world.level.Level level){
        var tag=stack.getOrDefault(net.minecraft.core.component.DataComponents.CUSTOM_DATA,net.minecraft.world.item.component.CustomData.EMPTY).copyTag();
        if(!tag.contains("HeatUntil")){
            net.minecraft.world.item.component.CustomData.update(net.minecraft.core.component.DataComponents.CUSTOM_DATA,stack,data->data.putLong("HeatUntil",level.getGameTime()+600));
        }else if(!isHot(stack,level)){
            var cold=new ItemStack(Tool.AWL.item());cold.setDamageValue(stack.getDamageValue());return cold;
        }
        return stack;
    }
    @Override public void inventoryTick(ItemStack stack,net.minecraft.world.level.Level level,net.minecraft.world.entity.Entity carrier,int slot,boolean selected){
        if(level.isClientSide||tool!=Tool.HEATED_AWL)return;
        var changed=temperature(stack,level);
        if(changed!=stack&&carrier instanceof Player player)player.getInventory().setItem(slot,changed);
    }
    @Override public boolean onEntityItemUpdate(ItemStack stack,net.minecraft.world.entity.item.ItemEntity item){
        if(!item.level().isClientSide&&tool==Tool.HEATED_AWL){var changed=temperature(stack,item.level());if(changed!=stack)item.setItem(changed);}
        return false;
    }
    @Override public InteractionResult useOn(net.minecraft.world.item.context.UseOnContext context){
        if(tool!=Tool.AWL)return InteractionResult.PASS;
        var level=context.getLevel();var state=level.getBlockState(context.getClickedPos());
        boolean fire=state.is(net.minecraft.tags.BlockTags.FIRE)||state.is(net.minecraft.world.level.block.Blocks.LAVA)
                ||state.getBlock() instanceof net.minecraft.world.level.block.CampfireBlock&&state.getValue(net.minecraft.world.level.block.CampfireBlock.LIT);
        if(!fire||context.getPlayer()==null)return InteractionResult.PASS;
        if(!level.isClientSide){
            var hot=new ItemStack(Tool.HEATED_AWL.item());hot.setDamageValue(context.getItemInHand().getDamageValue());temperature(hot,level);
            context.getPlayer().setItemInHand(context.getHand(),hot);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }
    @Override public InteractionResult interactLivingEntity(ItemStack stack,Player player,LivingEntity target,InteractionHand hand){
        if(!(target instanceof YukkuriEntity entity)||hand!=InteractionHand.MAIN_HAND)return InteractionResult.PASS;
        CareMenu.open(player,entity);return InteractionResult.sidedSuccess(player.level().isClientSide);
    }
}
