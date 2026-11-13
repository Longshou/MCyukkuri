package io.github.longshou.mcyukkuri.item;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.interaction.CareAction;
import io.github.longshou.mcyukkuri.interaction.InteractionDispatch;
import io.github.longshou.mcyukkuri.registry.ModItems;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;

/** Tools with no vanilla counterpart. Cutting uses vanilla shears (InteractionDispatch.openCutter); the awl's heat is a timed state of the same stack. */
public final class OperationToolItem extends Item {
    public enum Tool {
        PIN("pin_tool",true),MALLET("care_mallet",false),AWL("care_awl",false),
        RESTRAINT("restraint_frame",true),SLING("wrap_sling",true),HOOK("hanging_hook",true),
        VIBRATOR("care_vibrator",true),BOX("iron_enclosure",true);
        public final String id;
        public final boolean inserted;
        Tool(String id,boolean inserted){this.id=id;this.inserted=inserted;}
        public Item item(){return ModItems.OPERATION_TOOLS.get(this).get();}
    }
    /** Heat lasts 30 s after the awl touches fire; then the same stack is a plain awl again. */
    private static final int HEAT_TICKS = 600;
    private static final String HEAT = "HeatUntil";
    private final Tool tool;
    public OperationToolItem(Tool tool,Properties properties){super(properties);this.tool=tool;}
    public Tool tool(){return tool;}
    private static long heatUntil(ItemStack stack){
        return stack.getOrDefault(DataComponents.CUSTOM_DATA,CustomData.EMPTY).copyTag().getLong(HEAT);
    }
    public static boolean isHot(ItemStack stack,Level level){
        return stack.is(Tool.AWL.item())&&level!=null&&heatUntil(stack)>level.getGameTime();
    }
    /** An expired heat mark is removed, so the cooled awl reads and stacks exactly like a plain one. */
    private static void cool(ItemStack stack,Level level){
        if(heatUntil(stack)!=0&&!isHot(stack,level))CustomData.update(DataComponents.CUSTOM_DATA,stack,data->data.remove(HEAT));
    }
    @Override public void inventoryTick(ItemStack stack,Level level,Entity carrier,int slot,boolean selected){
        if(!level.isClientSide&&tool==Tool.AWL)cool(stack,level);
    }
    @Override public boolean onEntityItemUpdate(ItemStack stack,ItemEntity item){
        if(!item.level().isClientSide&&tool==Tool.AWL)cool(stack,item.level());
        return false;
    }
    @Override public Component getName(ItemStack stack){
        return heatUntil(stack)!=0?Component.translatable("item.mcyukkuri.heated_awl"):super.getName(stack);
    }
    @Override public InteractionResult useOn(UseOnContext context){
        if(tool!=Tool.AWL)return InteractionResult.PASS;
        var level=context.getLevel();var state=level.getBlockState(context.getClickedPos());
        boolean fire=state.is(BlockTags.FIRE)||state.is(Blocks.LAVA)
                ||state.getBlock() instanceof CampfireBlock&&state.getValue(CampfireBlock.LIT);
        if(!fire||context.getPlayer()==null)return InteractionResult.PASS;
        if(!level.isClientSide){
            long until=level.getGameTime()+HEAT_TICKS;
            CustomData.update(DataComponents.CUSTOM_DATA,context.getItemInHand(),data->data.putLong(HEAT,until));
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }
    @Override public InteractionResult interactLivingEntity(ItemStack stack,Player player,LivingEntity target,InteractionHand hand){
        if(!(target instanceof YukkuriEntity entity)||hand!=InteractionHand.MAIN_HAND)return InteractionResult.PASS;
        if(player instanceof ServerPlayer){
            var action=tool==Tool.AWL&&isHot(stack,player.level())?CareAction.BURN:CareAction.forTool(tool);
            return InteractionDispatch.perform(player, entity, action) ? InteractionResult.CONSUME : InteractionResult.FAIL;
        }
        return InteractionResult.sidedSuccess(player.level().isClientSide);
    }
    @Override public void appendHoverText(ItemStack stack,TooltipContext context,List<Component> lines,TooltipFlag flag){
        lines.add(Component.translatable("operation.mcyukkuri.hint.tool").withStyle(ChatFormatting.GRAY));
        var level=context.level();
        boolean hot=level==null?heatUntil(stack)!=0:isHot(stack,level);
        lines.add((hot?CareAction.BURN:CareAction.forTool(tool)).help());
    }
}
