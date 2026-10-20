package io.github.longshou.mcyukkuri.item;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.mind.EmotionEvent;
import io.github.longshou.mcyukkuri.status.GameplayLog;
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
            int before=stack.getCount();
            boolean applied=apply(entity,kind);
            if(!applied){
                String blocked=entity.body().treatmentBlockReason();
                String reason=blocked.equals("none")
                        ? kind==Kind.MOLD_REMOVER&&entity.body().mold()>=8?"terminal_mold":"no_applicable_condition"
                        : blocked;
                GameplayLog.limited(entity,"body","treatment_refused:"+player.getUUID()+":"+kind+":"+reason,20,
                        "treatment refused; player={} kind={} reason={} item_count={} health={}/{} wounds={} scars={} infection={} mold={}",
                        player.getUUID(),kind,reason,before,entity.getHealth(),entity.getMaxHealth(),entity.body().wounds().size(),
                        entity.status().marks().scarCount(),entity.body().infection(),entity.body().mold());
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
            GameplayLog.event(entity,"body","treatment applied; player={} kind={} item_count={} -> {} creative={}",
                    player.getUUID(),kind,before,stack.getCount(),player.getAbilities().instabuild);
        }
        return InteractionResult.sidedSuccess(player.level().isClientSide);
    }
}
