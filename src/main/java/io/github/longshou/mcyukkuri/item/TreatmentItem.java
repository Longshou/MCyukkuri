package io.github.longshou.mcyukkuri.item;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.mind.EmotionEvent;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
public final class TreatmentItem extends Item {
    public enum Kind { HERB, ORANGE_JUICE, MOLD_REMOVER, FLOUR_PASTE }
    private final Kind kind;
    public TreatmentItem(Kind kind,Properties properties){super(properties);this.kind=kind;}
    /** Player items, NPC supplies and diagnostic commands use the same treatment and successful-dose effects. */
    public static boolean apply(YukkuriEntity entity, Kind kind) {
        boolean applied=switch(kind){
            case HERB -> entity.body().treatInfection();
            case ORANGE_JUICE -> entity.body().restorePhysical();
            case MOLD_REMOVER -> entity.body().removeMold();
            case FLOUR_PASTE -> entity.body().repairScars();
        };
        if(applied&&kind==Kind.ORANGE_JUICE)entity.status().orangeRestored();
        return applied;
    }
    @Override public InteractionResult interactLivingEntity(ItemStack stack,Player player,LivingEntity target,InteractionHand hand){
        if(!(target instanceof YukkuriEntity entity))return InteractionResult.PASS;
        if(!player.level().isClientSide){
            boolean applied=apply(entity,kind);
            if(!applied){
                // A freshly hurt animal is still bolting or curled up for a few seconds; say so instead of failing silently.
                if(entity.isPanicking()){ entity.mind().felt(EmotionEvent.FRIGHTENED); entity.showReaction("surprise","afraid",40); }
                return InteractionResult.FAIL;
            }
            if(!player.getAbilities().instabuild){
                stack.shrink(1);
                if(kind==Kind.ORANGE_JUICE||kind==Kind.MOLD_REMOVER){
                    var empty=new ItemStack(Items.GLASS_BOTTLE);
                    if(!player.getInventory().add(empty))player.drop(empty,false);
                }
            }
        }
        return InteractionResult.sidedSuccess(player.level().isClientSide);
    }
}
