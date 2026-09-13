package io.github.longshou.mcyukkuri.item;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
public final class TreatmentItem extends Item {
    public enum Kind { LEGACY_BANDAGE, LEGACY_HERB, ORANGE_JUICE, MOLD_REMOVER }
    private final Kind kind;
    public TreatmentItem(boolean medicine,Properties properties){this(medicine?Kind.LEGACY_HERB:Kind.LEGACY_BANDAGE,properties);}
    public TreatmentItem(Kind kind,Properties properties){super(properties);this.kind=kind;}
    @Override public InteractionResult interactLivingEntity(ItemStack stack,Player player,LivingEntity target,InteractionHand hand){
        if(!(target instanceof YukkuriEntity entity))return InteractionResult.PASS;
        if(!player.level().isClientSide){
            boolean applied=switch(kind){
                case LEGACY_BANDAGE -> entity.body().treat(false);
                case LEGACY_HERB -> entity.body().treat(true);
                case ORANGE_JUICE -> entity.body().restorePhysical();
                case MOLD_REMOVER -> entity.body().removeMold();
            };
            if(!applied)return InteractionResult.FAIL;
            if(!player.getAbilities().instabuild){
                stack.shrink(1);
                if(kind==Kind.ORANGE_JUICE||kind==Kind.MOLD_REMOVER){
                    var empty=new ItemStack(net.minecraft.world.item.Items.GLASS_BOTTLE);
                    if(!player.getInventory().add(empty))player.drop(empty,false);
                }
            }
        }
        return InteractionResult.sidedSuccess(player.level().isClientSide);
    }
}
