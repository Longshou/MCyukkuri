package io.github.longshou.mcyukkuri.family;

import io.github.longshou.mcyukkuri.mind.EmotionEvent;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.entity.YukkuriVariant;
import io.github.longshou.mcyukkuri.registry.ModBlocks;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/** The block owns one existing brood by a durable token, never by a copied list of child entities. */
public final class DetachedStemBlockEntity extends BlockEntity {
    private UUID identity=UUID.randomUUID(),broodId;
    private int sugar,fuel,exposure,count,born,types;
    private float progress,variation=1;
    private YukkuriVariant source=YukkuriVariant.REIMU;
    public DetachedStemBlockEntity(BlockPos pos,BlockState state){super(ModBlocks.DETACHED_STEM_ENTITY.get(),pos,state);}
    public boolean bottled(){return getBlockState().getBlock() instanceof DetachedStemBlock block&&block.bottled;}
    public int count(){return count;}
    public int born(){return born;}
    public int types(){return types;}
    public float progress(){return progress;}
    public float variation(){return variation;}
    public int sugar(){return sugar;}
    public YukkuriVariant source(){return source;}
    public FamilyData.Brood brood(){
        if(!(level instanceof ServerLevel server))return null;
        var value=FamilyData.get(server).brood(broodId);
        return value!=null&&identity.equals(value.detachedHolder)&&value.anchor.dimension().equals(server.dimension())
                &&value.anchor.pos().equals(worldPosition)?value:null;
    }
    public static boolean cut(YukkuriEntity mother,Player player,boolean bottle){
        if(!(mother.level() instanceof ServerLevel server))return false;
        var brood=mother.family().brood();if(brood==null||brood.cancelled||brood.complete()||brood.progress()<.15f)return false;
        var water=player.getOffhandItem();
        if(bottle&&!(water.is(Items.POTION)&&water.getOrDefault(net.minecraft.core.component.DataComponents.POTION_CONTENTS,
                net.minecraft.world.item.alchemy.PotionContents.EMPTY).is(net.minecraft.world.item.alchemy.Potions.WATER)))return false;
        BlockPos destination=null;
        for(var direction:net.minecraft.core.Direction.Plane.HORIZONTAL){
            var pos=mother.blockPosition().relative(direction,2);
            if(clearance(server,pos,brood,mother.getVariant(),bottle)&&server.getBlockState(pos.below()).isFaceSturdy(server,pos.below(),net.minecraft.core.Direction.UP)
                    &&player.distanceToSqr(Vec3.atCenterOf(pos))<=25){destination=pos;break;}
        }
        if(destination==null)return false;
        // A block at this address will be the sole owner. The old pregnancy remains intact on placement failure.
        if(!server.setBlock(destination,(bottle?ModBlocks.BOTTLED_STEM:ModBlocks.DETACHED_STEM).get().defaultBlockState(),3))return false;
        if(!(server.getBlockEntity(destination) instanceof DetachedStemBlockEntity holder))return false;
        var data=FamilyData.get(server);
        if(!data.relocate(brood,server,destination)){server.removeBlock(destination,false);return false;}
        holder.broodId=brood.id;holder.source=mother.getVariant();brood.detachedHolder=holder.identity;
        brood.anchor=net.minecraft.core.GlobalPos.of(server.dimension(),destination);data.setDirty();
        if(bottle&&!player.getAbilities().instabuild)water.shrink(1);
        mother.family().detached();mother.life().personality().confiscated(player.getUUID());
        mother.mind().felt(EmotionEvent.FAMILY_DEATH); mother.showReaction("surprise","family_died",120);holder.sync();return true;
    }
    private static boolean clearance(ServerLevel level,BlockPos center,FamilyData.Brood brood,YukkuriVariant parent,boolean bottled){
        for(int x=-1;x<=1;x++)for(int z=-1;z<=1;z++)for(int y=0;y<=2;y++){
            var pos=center.offset(x,y,z);if(!level.hasChunkAt(pos)||!level.isEmptyBlock(pos))return false;
        }
        var offset=Vec3.atBottomCenterOf(center).add(DetachedStemGeometry.offset(brood.children.size(),brood.variation,parent,bottled));
        for(var point:FamilyGeometry.arc(brood.children.size(),1,1,brood.variation,parent)){
            var world=point.add(offset);var bounds=new net.minecraft.world.phys.AABB(world.x-.035,Math.max(center.getY()+.001,world.y-.035),world.z-.035,
                    world.x+.035,world.y+.035,world.z+.035);if(!clearBox(level,bounds))return false;
        }
        for(int i=0;i<brood.children.size();i++)for(float progress:new float[]{brood.progress(),1}){
            var child=brood.children.get(i);if(child.born)continue;
            var point=Vec3.atBottomCenterOf(center).add(DetachedStemGeometry.childBase(brood.children.size(),i,progress,brood.variation,parent,child.variant,bottled));
            var box=LiveBirthGeometry.childDimensions(child,1).scale(GrowthSize.STEM/LiveBirthGeometry.BABY_SCALE).makeBoundingBox(point).inflate(.04);
            if(!clearBox(level,box))return false;
        }
        return true;
    }
    private static boolean clearBox(ServerLevel level,net.minecraft.world.phys.AABB box){
        return level.hasChunkAt(BlockPos.containing(box.minX,box.minY,box.minZ))&&level.hasChunkAt(BlockPos.containing(box.maxX,box.maxY,box.maxZ))
                &&!level.getBlockCollisions(null,box).iterator().hasNext()&&!level.containsAnyLiquid(box);
    }
    public boolean canSupply(ItemStack stack){var brood=brood();return bottled()&&stack.is(Items.SUGAR)&&sugar<16&&brood!=null&&!brood.cancelled&&!brood.complete();}
    public boolean supply(ItemStack stack){if(!canSupply(stack))return false;sugar++;changed();return true;}
    public void tick(){
        if(!(level instanceof ServerLevel server))return;
        var brood=brood();if(brood==null)return;
        if(brood.cancelled||brood.complete()){if(born!=(1<<brood.children.size())-1)sync();return;}
        if(!server.getBlockState(worldPosition.below()).isFaceSturdy(server,worldPosition.below(),net.minecraft.core.Direction.UP)){
            destroyBrood();server.removeBlock(worldPosition,false);return;
        }
        if(!bottled()){
            // Freshly detached fruit keeps its existing maturity. Immature fruit withers without a bottle.
            if(++exposure>=2400){FamilyData.get(server).cancel(brood);sync();return;}
            if(brood.elapsed>=brood.duration){brood.elapsed=Math.min(brood.duration+40,brood.elapsed+1);FamilyData.get(server).setDirty();}
        }else{
            if(fuel==0&&sugar>0){sugar--;fuel=200;}
            if(fuel>0){fuel--;brood.elapsed=Math.min(brood.duration+40,brood.elapsed+1);FamilyData.get(server).setDirty();}
        }
        for(int i=0;i<brood.children.size();i++){
            Vec3 position=Vec3.atBottomCenterOf(worldPosition).add(DetachedStemGeometry.childBase(brood.children.size(),i,brood.progress(),brood.variation,source,brood.children.get(i).variant,bottled()));
            FamilyData.get(server).birth(brood,i,server,position);
        }
        if(brood.complete()||exposure%20==0||bottled()&&server.getGameTime()%20==0)sync();
        setChanged();
    }
    public void destroyBrood(){if(level instanceof ServerLevel server)FamilyData.get(server).cancel(brood());}
    private void sync(){
        var brood=brood();if(brood!=null){count=brood.children.size();born=types=0;progress=brood.progress();variation=brood.variation;
            for(int i=0;i<count;i++){if(brood.children.get(i).born||brood.cancelled)born|=1<<i;types|=brood.children.get(i).variant.ordinal()<<(i*4);}}
        changed();
    }
    private void changed(){setChanged();if(level!=null)level.sendBlockUpdated(worldPosition,getBlockState(),getBlockState(),3);}
    @Override protected void saveAdditional(CompoundTag tag,HolderLookup.Provider registries){
        super.saveAdditional(tag,registries);tag.putUUID("Identity",identity);if(broodId!=null)tag.putUUID("Brood",broodId);
        tag.putInt("Sugar",sugar);tag.putInt("Fuel",fuel);tag.putInt("Exposure",exposure);tag.putInt("Count",count);tag.putInt("Born",born);tag.putInt("Types",types);
        tag.putInt("TypeBits",4);tag.putFloat("Progress",progress);tag.putFloat("Variation",variation);tag.putString("Source",source.name());
    }
    @Override protected void loadAdditional(CompoundTag tag,HolderLookup.Provider registries){
        super.loadAdditional(tag,registries);if(tag.hasUUID("Identity"))identity=tag.getUUID("Identity");broodId=tag.hasUUID("Brood")?tag.getUUID("Brood"):null;
        sugar=Math.clamp(tag.getInt("Sugar"),0,16);fuel=Math.clamp(tag.getInt("Fuel"),0,200);exposure=Math.clamp(tag.getInt("Exposure"),0,2400);
        count=Math.clamp(tag.getInt("Count"),0,5);born=tag.getInt("Born");types=tag.getInt("Types");progress=Math.clamp(tag.getFloat("Progress"),0,1);variation=Math.clamp(tag.getFloat("Variation"),.85f,1.15f);
        try{source=YukkuriVariant.valueOf(tag.getString("Source"));}catch(IllegalArgumentException ignored){source=YukkuriVariant.REIMU;}
    }
    @Override public CompoundTag getUpdateTag(HolderLookup.Provider registries){return saveWithoutMetadata(registries);}
    @Override public ClientboundBlockEntityDataPacket getUpdatePacket(){return ClientboundBlockEntityDataPacket.create(this);}
}
