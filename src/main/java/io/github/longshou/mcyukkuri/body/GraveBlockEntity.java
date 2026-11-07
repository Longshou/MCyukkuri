package io.github.longshou.mcyukkuri.body;

import io.github.longshou.mcyukkuri.entity.RemainsEntity;
import io.github.longshou.mcyukkuri.registry.ModBlocks;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** Burial retains the identity and source size; breaking a grave does not mint another body. */
public final class GraveBlockEntity extends BlockEntity {
    /** In-game day index ({@code dayTime / 24000}) the body was buried on, kept in the buried record. */
    public static final String BURIED_DAY="BuriedDay";
    private CompoundTag remains=new CompoundTag();
    public GraveBlockEntity(BlockPos pos,BlockState state){super(ModBlocks.GRAVE_ENTITY.get(),pos,state);}
    public float sourceScale(){float value=remains.contains("SourceScale")?remains.getFloat("SourceScale"):1;return Float.isFinite(value)?Math.clamp(value,.05f,4f):1;}
    public void bury(CompoundTag data){remains=data.copy();setChanged();if(level!=null)level.sendBlockUpdated(worldPosition,getBlockState(),getBlockState(),3);}
    /**
     * Server side: the marker read like a sign, from the buried record only: the name if it had one, species, age stage,
     * cause-of-death category and burial day.
     */
    public void read(Player player){
        if(level==null||level.isClientSide)return;
        Component name=null,species=Component.translatable("entity.mcyukkuri."+remains.getString("Variant"));
        if(remains.contains("Name")){
            // The remains name is "<name>'s remains"; its argument is the living name, which is the species name when it had none.
            Component stored=Component.Serializer.fromJson(remains.getString("Name"),level.registryAccess());
            Object who=stored!=null&&stored.getContents() instanceof TranslatableContents named&&named.getKey().equals(RemainsEntity.NAMED)
                    &&named.getArgs().length>0?named.getArgs()[0]:stored;
            Component living=who instanceof Component component?component:who==null?null:Component.literal(String.valueOf(who));
            if(living!=null&&living.getContents() instanceof TranslatableContents type&&(type.getKey().startsWith("entity.")||type.getKey().startsWith("subspecies.")))
                species=living;
            else name=living;
        }
        String stage=remains.getString("Stage"),category=RemainsCause.of(remains);
        player.displayClientMessage(Component.translatable("grave.mcyukkuri.read",
                name==null?Component.translatable("grave.mcyukkuri.title.nameless"):Component.translatable("grave.mcyukkuri.title.named",name),
                species,Component.translatable(stage.isEmpty()?"grave.mcyukkuri.stage_unknown":"stage.mcyukkuri."+stage),
                Component.translatable("remains.mcyukkuri.category."+category),remains.contains(BURIED_DAY)?remains.getLong(BURIED_DAY)+1:"?"),false);
        String identity=remains.hasUUID("Identity")?remains.getUUID("Identity").toString():"none";
        GameplayLog.limited(player,"remains","grave_read:"+identity,100,"grave read grave={} identity={} variant={} stage={} category={} buried_day={} named={}",
                worldPosition.toShortString(),identity,remains.getString("Variant"),stage.isEmpty()?"none":stage,category,
                remains.contains(BURIED_DAY)?remains.getLong(BURIED_DAY):"none",name!=null);
    }
    @Override protected void saveAdditional(CompoundTag tag,HolderLookup.Provider registries){super.saveAdditional(tag,registries);tag.put("Remains",remains.copy());}
    @Override protected void loadAdditional(CompoundTag tag,HolderLookup.Provider registries){super.loadAdditional(tag,registries);remains=tag.getCompound("Remains").copy();}
    @Override public CompoundTag getUpdateTag(HolderLookup.Provider registries){var tag=new CompoundTag();tag.put("Remains",remains.copy());return tag;}
    @Override public ClientboundBlockEntityDataPacket getUpdatePacket(){return ClientboundBlockEntityDataPacket.create(this);}
}
