package io.github.longshou.mcyukkuri.house;

import io.github.longshou.mcyukkuri.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ItemLike;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/** One persistent, finite heap of lint, crumbs or waste per ground cell. There is no per-fragment entity or ticker. */
public final class SupplyPileBlockEntity extends BlockEntity {
    public static final int CAPACITY=64;
    /**
     * Lint blows away and crumbs spoil: untouched lint or crumbs lose one unit per random tick once this long has passed
     * since the last change (1.5 MC days, about 30 real minutes). Waste is the cleaned-up result and does not decay.
     */
    public static final long DECAY_AFTER=36000;
    private ItemStack contents=ItemStack.EMPTY;
    private long touched;
    private final Vec3 pickup;
    public SupplyPileBlockEntity(BlockPos pos,BlockState state){super(ModBlocks.HOUSE_SUPPLIES_ENTITY.get(),pos,state);pickup=Vec3.atBottomCenterOf(pos).add(0,.025,0);}
    public ItemStack contents(){return contents.copy();}
    public boolean is(ItemLike item){return contents.is(item.asItem());}
    public boolean lint(){return SupplyPiles.lint(contents);}
    public boolean crumb(){return SupplyPiles.crumb(contents);}
    public Component displayName(){return contents.getHoverName();}
    public int count(){return contents.getCount();}
    public Vec3 pickupPosition(){return pickup;}
    public boolean accepts(ItemStack stack){return SupplyPiles.material(stack)&&(contents.isEmpty()||ItemStack.isSameItemSameComponents(contents,stack));}
    /** Returns the amount stored; the caller owns and debits the source stack. */
    public int deposit(ItemStack stack,int limit){
        if(level==null||level.isClientSide||isRemoved()||level.getBlockEntity(worldPosition)!=this||!accepts(stack))return 0;
        int amount=Math.min(Math.min(stack.getCount(),Math.max(0,limit)),CAPACITY-count());
        if(amount==0)return 0;
        if(contents.isEmpty())contents=stack.copyWithCount(amount);else contents.grow(amount);
        touched=level.getGameTime();changed();return amount;
    }
    public ItemStack takeOne(){return take(1);}
    public ItemStack takeAll(){return take(CAPACITY);}
    private ItemStack take(int amount){
        if(level==null||level.isClientSide||isRemoved())return ItemStack.EMPTY;
        var taken=contents.split(amount);
        if(contents.isEmpty())level.removeBlock(worldPosition,false);else{touched=level.getGameTime();changed();}
        return taken;
    }
    /** Random-tick decay: once expired, one unit disappears per call until the heap is gone. */
    public void decay(long now){
        if(contents.isEmpty()||!SupplyPiles.internal(contents)||now-touched<DECAY_AFTER)return;
        take(1);
    }
    /** Used by block removal after the stock has been cleared, preventing duplicate drops. */
    public ItemStack drain(){var result=contents;contents=ItemStack.EMPTY;setChanged();return result;}
    private void changed(){setChanged();level.sendBlockUpdated(worldPosition,getBlockState(),getBlockState(),3);}
    @Override protected void saveAdditional(CompoundTag tag,HolderLookup.Provider registries){
        super.saveAdditional(tag,registries);tag.putInt("Version",3);tag.putLong("Touched",touched);
        if(!contents.isEmpty())tag.put("Contents",contents.save(registries));
    }
    @Override protected void loadAdditional(CompoundTag tag,HolderLookup.Provider registries){
        super.loadAdditional(tag,registries);contents=ItemStack.parseOptional(registries,tag.getCompound("Contents"));
        if(!SupplyPiles.material(contents))contents=ItemStack.EMPTY;
        else contents.setCount(Math.min(CAPACITY,contents.getCount()));
        touched=tag.getLong("Touched");
    }
    @Override public CompoundTag getUpdateTag(HolderLookup.Provider registries){return saveWithoutMetadata(registries);}
    @Override public ClientboundBlockEntityDataPacket getUpdatePacket(){return ClientboundBlockEntityDataPacket.create(this);}
}
