package io.github.longshou.mcyukkuri.family;

import io.github.longshou.mcyukkuri.random.ModRandom;

import io.github.longshou.mcyukkuri.status.GameplayLog;

import io.github.longshou.mcyukkuri.mind.EmotionEvent;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.entity.YukkuriVariant;
import io.github.longshou.mcyukkuri.life.Consumables;
import io.github.longshou.mcyukkuri.registry.ModBlocks;
import io.github.longshou.mcyukkuri.human.Custody;
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
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.world.phys.AABB;

/** The block owns one existing brood by a durable token, never by a copied list of child entities. */
public final class DetachedStemBlockEntity extends BlockEntity {
    private UUID identity,broodId;
    @Override public void setLevel(Level level) {
        super.setLevel(level);
        if (identity == null) identity = ModRandom.identity(ModRandom.world(level));
    }
    private int sugar,fuel,exposure,count,born,types;
    private float progress,variation=1;
    private float previousProgress;
    private long progressReceivedAt=Long.MIN_VALUE;
    private YukkuriVariant source=YukkuriVariant.REIMU;
    public DetachedStemBlockEntity(BlockPos pos,BlockState state){super(ModBlocks.DETACHED_STEM_ENTITY.get(),pos,state);}
    public boolean bottled(){return getBlockState().getBlock() instanceof DetachedStemBlock block&&block.bottled;}
    public int count(){return count;}
    public int born(){return born;}
    public int types(){return types;}
    public float progress(){return progress;}
    public float progress(float partialTick){
        if(progressReceivedAt==Long.MIN_VALUE)return progress;
        float t=Math.clamp(level.getGameTime()-progressReceivedAt+partialTick,0,1);
        return previousProgress+(progress-previousProgress)*t;
    }
    public float variation(){return variation;}
    public int sugar(){return sugar;}
    public YukkuriVariant source(){return source;}
    public FamilyData.Brood brood(){
        if(!(level instanceof ServerLevel server))return null;
        var value=FamilyData.get(server).brood(broodId);
        return value!=null&&identity.equals(value.detachedHolder)&&value.anchor.dimension().equals(server.dimension())
                &&value.anchor.pos().equals(worldPosition)?value:null;
    }
    public static String cutRefusal(YukkuriEntity mother, Player player, boolean bottle) {
        var brood = mother.family().brood();
        if (brood == null || brood.kind != FamilyData.BirthKind.STEM || brood.cancelled || brood.complete())
            return "condition";
        if (brood.progress() < .15f) return "cut_too_early";
        if (brood.children.stream().anyMatch(child -> child.born)) return "cut_birth_started";
        return bottle && !Consumables.water(player.getOffhandItem()) ? "bottle" : null;
    }

    public static boolean cut(YukkuriEntity mother,Player player,boolean bottle){
        if(!(mother.level() instanceof ServerLevel server)||cutRefusal(mother,player,bottle)!=null)return false;
        var brood=mother.family().brood();
        var water=player.getOffhandItem();
        BlockPos destination=null;
        for(var direction:Direction.Plane.HORIZONTAL){
            var pos=mother.blockPosition().relative(direction,2);
            if(Custody.harmRefusal(player,mother,pos)==null&&clearance(server,pos,brood,mother.getVariant(),bottle)&&server.getBlockState(pos.below()).isFaceSturdy(server,pos.below(),Direction.UP)
                    &&player.distanceToSqr(Vec3.atCenterOf(pos))<=25){destination=pos;break;}
        }
        if(destination==null)return false;
        // A block at this address will be the sole owner. The old pregnancy remains intact on placement failure.
        if(!server.setBlock(destination,(bottle?ModBlocks.BOTTLED_STEM:ModBlocks.DETACHED_STEM).get().defaultBlockState(),3))return false;
        if(!(server.getBlockEntity(destination) instanceof DetachedStemBlockEntity holder))return false;
        var data=FamilyData.get(server);
        if(!data.relocate(brood,server,destination)){server.removeBlock(destination,false);return false;}
        holder.broodId=brood.id;holder.source=mother.getVariant();brood.detachedHolder=holder.identity;
        brood.anchor=GlobalPos.of(server.dimension(),destination);data.setDirty();
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
            var world=point.add(offset);var bounds=new AABB(world.x-.035,Math.max(center.getY()+.001,world.y-.035),world.z-.035,
                    world.x+.035,world.y+.035,world.z+.035);if(!clearBox(level,bounds))return false;
        }
        for(int i=0;i<brood.children.size();i++)for(float progress:new float[]{brood.progress(),1}){
            var child=brood.children.get(i);if(child.born)continue;
            float physical=GrowthSize.physicalBirth(brood.kind,child);
            var point=Vec3.atBottomCenterOf(center).add(DetachedStemGeometry.childBase(brood.children.size(),i,progress,brood.variation,parent,child.variant,bottled,physical));
            var box=LiveBirthGeometry.childDimensions(child,1).scale(FamilyGeometry.fruitScale(progress,physical)/physical).makeBoundingBox(point).inflate(.04);
            if(!clearBox(level,box))return false;
        }
        return true;
    }
    private static boolean clearBox(ServerLevel level,AABB box){
        return level.hasChunkAt(BlockPos.containing(box.minX,box.minY,box.minZ))&&level.hasChunkAt(BlockPos.containing(box.maxX,box.maxY,box.maxZ))
                &&!level.getBlockCollisions(null,box).iterator().hasNext()&&!level.containsAnyLiquid(box);
    }
    public boolean canSupply(ItemStack stack){var brood=brood();return bottled()&&stack.is(Items.SUGAR)&&sugar<16&&brood!=null&&!brood.cancelled&&!brood.complete();}
    public boolean supply(ItemStack stack){if(!canSupply(stack))return false;sugar++;changed();return true;}
    public void tick(){
        if(!(level instanceof ServerLevel server))return;
        var brood=brood();if(brood==null)return;
        if(brood.cancelled||brood.complete()){if(born!=(1<<brood.children.size())-1)sync();return;}
        if(!server.getBlockState(worldPosition.below()).isFaceSturdy(server,worldPosition.below(),Direction.UP)){
            destroyBrood();server.removeBlock(worldPosition,false);return;
        }
        int previousElapsed=brood.elapsed,previousUnborn=brood.unborn();
        if(!bottled()){
            // Freshly detached fruit keeps its existing maturity. Immature fruit withers without a bottle.
            if(++exposure>=2400){cancelBrood(server,brood,"exposure_timeout");sync();return;}
        }else if(brood.elapsed<brood.duration){
            // One sugar supplies a tenth of a new brood's full growth, rounded up to a whole tick.
            if(fuel==0&&sugar>0){sugar--;fuel=brood.fastStem?Math.ceilDiv(brood.duration,10):200;}
            if(fuel>0){fuel--;brood.elapsed++;}
        }
        if(previousElapsed>=brood.duration)brood.elapsed=Math.min(brood.duration+40,brood.elapsed+1);
        if(brood.elapsed!=previousElapsed)FamilyData.get(server).setDirty();
        for(int i=0;i<brood.children.size();i++){
            Vec3 position=Vec3.atBottomCenterOf(worldPosition).add(DetachedStemGeometry.childBase(brood.children.size(),i,brood.progress(),brood.variation,source,
                    brood.children.get(i).variant,bottled(),GrowthSize.physicalBirth(brood.kind,brood.children.get(i))));
            FamilyData.get(server).birth(brood,i,server,position);
        }
        if(previousUnborn!=brood.unborn()||brood.elapsed!=previousElapsed&&brood.elapsed<=brood.duration)sync();
        setChanged();
    }
    public void destroyBrood(){if(level instanceof ServerLevel server)cancelBrood(server,brood(),"detached_stem_destroyed");}
    private void cancelBrood(ServerLevel server,FamilyData.Brood brood,String reason){
        if(brood==null||brood.cancelled)return;
        int unborn=brood.unborn();
        FamilyData.get(server).cancel(brood);
        GameplayLog.world(server,"family","brood cancelled brood={} kind={} mother={} unborn={} reason={} pos={}",
                brood.id,brood.kind,brood.mother,unborn,reason,worldPosition);
    }
    private int[] birthScales=new int[0];
    private int[] birthMorphs=new int[0];
    public float birthChildScale(int index){
        float base=index>=0&&index<birthScales.length?birthScales[index]/10000f:GrowthSize.STEM;
        return GrowthSize.physicalBirth(base,index>=0&&index<birthMorphs.length?birthMorphs[index]:0);
    }
    private void sync(){
        var brood=brood();if(brood!=null){count=brood.children.size();born=types=0;progress=brood.progress();variation=brood.variation;
            for(int i=0;i<count;i++){if(brood.children.get(i).born||brood.cancelled)born|=1<<i;types|=brood.children.get(i).variant.ordinal()<<(i*4);}}
        if(brood!=null){
            birthScales=brood.children.stream().mapToInt(c->Math.round(GrowthSize.birth(brood.kind,c)*10000)).toArray();
            birthMorphs=brood.children.stream().mapToInt(c->c.subspeciesFlags).toArray();
        }
        changed();
    }
    private void changed(){setChanged();if(level!=null)level.sendBlockUpdated(worldPosition,getBlockState(),getBlockState(),3);}
    @Override protected void saveAdditional(CompoundTag tag,HolderLookup.Provider registries){
        super.saveAdditional(tag,registries);tag.putUUID("Identity",identity);if(broodId!=null)tag.putUUID("Brood",broodId);
        tag.putInt("Sugar",sugar);tag.putInt("Fuel",fuel);tag.putInt("Exposure",exposure);tag.putInt("Count",count);tag.putInt("Born",born);tag.putInt("Types",types);
        tag.putIntArray("BirthScales",birthScales);tag.putIntArray("BirthMorphs",birthMorphs);tag.putInt("TypeBits",4);tag.putFloat("Progress",progress);tag.putFloat("Variation",variation);tag.putString("Source",source.name());
    }
    @Override protected void loadAdditional(CompoundTag tag,HolderLookup.Provider registries){
        super.loadAdditional(tag,registries);if(tag.hasUUID("Identity"))identity=tag.getUUID("Identity");broodId=tag.hasUUID("Brood")?tag.getUUID("Brood"):null;
        sugar=Math.clamp(tag.getInt("Sugar"),0,16);fuel=Math.clamp(tag.getInt("Fuel"),0,200);exposure=Math.clamp(tag.getInt("Exposure"),0,2400);
        birthScales=tag.getIntArray("BirthScales");birthMorphs=tag.getIntArray("BirthMorphs");
        count=Math.clamp(tag.getInt("Count"),0,5);born=tag.getInt("Born");types=tag.getInt("Types");
        float next=Math.clamp(tag.getFloat("Progress"),0,1);
        previousProgress=next>=progress&&next-progress<.1f?progress:next;
        progress=next;
        if(level!=null&&level.isClientSide)progressReceivedAt=level.getGameTime();
                variation=Math.clamp(tag.getFloat("Variation"),.85f,1.15f);
        try{source=YukkuriVariant.valueOf(tag.getString("Source"));}catch(IllegalArgumentException ignored){source=YukkuriVariant.REIMU;}
    }
    @Override public CompoundTag getUpdateTag(HolderLookup.Provider registries){return saveWithoutMetadata(registries);}
    @Override public ClientboundBlockEntityDataPacket getUpdatePacket(){return ClientboundBlockEntityDataPacket.create(this);}
}
