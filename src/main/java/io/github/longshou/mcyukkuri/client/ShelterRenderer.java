package io.github.longshou.mcyukkuri.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import io.github.longshou.mcyukkuri.settlement.NaturalShelter;
import io.github.longshou.mcyukkuri.settlement.ShelterBlock;
import io.github.longshou.mcyukkuri.settlement.ShelterBlockEntity;
import io.github.longshou.mcyukkuri.settlement.ShelterGeometry;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

/** One render at the household root; shell collision belongs to every physical part. */
public final class ShelterRenderer implements BlockEntityRenderer<ShelterBlockEntity> {
    public ShelterRenderer(BlockEntityRendererProvider.Context context) {}
    /**
     * Burrow bedding (the personal leaf nest, the large family bedding) is a loose heap reaching past its cell; where a block
     * stands in its way it is pushed together on that side so nothing pokes into the earth: each axis is squeezed (and shifted
     * off the wall) to end at the nearest block face, like the pet bed's flattened roll.
     */
    private static void fitAgainstWalls(ShelterBlockEntity home,BlockPos bed,String prop,int units,PoseStack pose){
        var level=home.getLevel();if(level==null)return;
        var bounds=PropModels.bounds(prop,units);
        double west=-reach(level,bed,Direction.WEST,-bounds.minX,bounds.maxY),east=reach(level,bed,Direction.EAST,bounds.maxX,bounds.maxY);
        double north=-reach(level,bed,Direction.NORTH,-bounds.minZ,bounds.maxY),south=reach(level,bed,Direction.SOUTH,bounds.maxZ,bounds.maxY);
        double x=(east-west)/(bounds.maxX-bounds.minX),z=(south-north)/(bounds.maxZ-bounds.minZ);
        pose.translate(west-x*bounds.minX,0,north-z*bounds.minZ);pose.scale((float)x,1,(float)z);
    }
    /**
     * How far the bedding reaches from the bed cell's centre toward {@code side}: its own reach {@code own}, or the nearest face
     * of a block standing below its top in the cells it would enter, never short of the cell face. The heap is rounded, so one
     * squeezed axis clears a corner cell: the corner cells count for the east and west sides.
     */
    private static double reach(Level level,BlockPos bed,Direction side,double own,double top){
        var axis=side.getAxis();
        for(int across=axis==Direction.Axis.X?-1:0;across<=(axis==Direction.Axis.X?1:0);across++){
            var cell=bed.relative(side).south(across);
            var shape=level.getBlockState(cell).getCollisionShape(level,cell);
            if(shape.isEmpty()||shape.min(Direction.Axis.Y)>=top)continue;
            own=Math.min(own,.5+(side.getAxisDirection()==Direction.AxisDirection.POSITIVE?shape.min(axis):1-shape.max(axis)));
        }
        return own;
    }
    @Override public void render(ShelterBlockEntity home,float partial,PoseStack pose,MultiBufferSource buffers,int light,int overlay) {
        if(home.kind()==ShelterBlock.Kind.BURROW) {
            var shared=new HashSet<>(home.sharedBeds());
            for(var project:home.nestConstructions())shared.remove(project.bed());
            shared.removeAll(home.largeBeds());
            for(var bed:home.largeBeds()){
                var offset=bed.subtract(home.getBlockPos());
                pose.pushPose();pose.translate(offset.getX()+.5,offset.getY(),offset.getZ()+.5);fitAgainstWalls(home,bed,"large_wild_nest",1,pose);
                PropModels.render("large_wild_nest",1,pose,buffers,light,overlay);pose.popPose();
            }
            if(home.lining()>0) for(var bed:shared) {
                var offset=bed.subtract(home.getBlockPos());
                int local=home.getLevel()==null?light:LevelRenderer.getLightColor(home.getLevel(),bed);
                pose.pushPose();pose.translate(offset.getX()+.5,0,offset.getZ()+.5);
                PropModels.renderLayers("forest_burrow",sharedLeafLayers(shared,bed),pose,buffers,local,overlay);pose.popPose();
            }
            for(var bed:home.privateBeds()) {
                var offset=bed.subtract(home.getBlockPos());
                int local=home.getLevel()==null?light:LevelRenderer.getLightColor(home.getLevel(),bed);
                pose.pushPose();pose.translate(offset.getX()+.5,0,offset.getZ()+.5);fitAgainstWalls(home,bed,"personal_leaf_nest",7,pose);
                PropModels.renderLayers("personal_leaf_nest",7,pose,buffers,local,overlay);pose.popPose();
            }
            for(var project:home.nestConstructions()) {
                var bed=project.bed();var offset=bed.subtract(home.getBlockPos());
                int local=home.getLevel()==null?light:LevelRenderer.getLightColor(home.getLevel(),bed);
                pose.pushPose();pose.translate(offset.getX()+.5,offset.getY(),offset.getZ()+.5);
                if(project.work()<20)PropModels.render("bedding_bundle",0,pose,buffers,local,overlay);
                else {
                    pose.pushPose();fitAgainstWalls(home,bed,"personal_leaf_nest",7,pose);
                    PropModels.renderLayers("personal_leaf_nest",project.work()<80?1:project.work()<100?3:7,pose,buffers,local,overlay);
                    pose.popPose();
                }
                if(project.portions()==2&&project.work()<80){pose.translate(.18,0,.12);PropModels.render("bedding_bundle",0,pose,buffers,local,overlay);}
                pose.popPose();
            }
            if(home.branches()>0) {
                if(home.getLevel()!=null)light=LevelRenderer.getLightColor(home.getLevel(),home.entrance());
                var offset=home.entrance().subtract(home.getBlockPos());
                pose.pushPose();pose.translate(offset.getX()+.5,offset.getY(),offset.getZ()+.5);
                pose.mulPose(Axis.YP.rotationDegrees(-90*ShelterGeometry.turns(home.facing())));
                if(offset.getY()>0) {
                    for(int twig=0;twig<home.branches();twig++) {
                        pose.pushPose();pose.translate(0,0,.18*twig);
                        pose.mulPose(Axis.YP.rotationDegrees(12*twig));
                        PropModels.renderLayers("nest_boundary",4,pose,buffers,light,overlay);pose.popPose();
                    }
                    pose.popPose();return;
                }
                for(int twig=0;twig<home.branches();twig++) {
                    if(twig==2){PropModels.renderLayers("nest_boundary",4,pose,buffers,light,overlay);continue;}
                    var side=twig==0?home.facing().getClockWise():home.facing().getCounterClockWise();
                    int distance=0;
                    for(int i=1;i<=3;i++)if(home.getLevel()!=null&&NaturalShelter.earth(home.getLevel(),home.getBlockPos().relative(side,i))){distance=i;break;}
                    if(distance==0)continue;
                    if(distance>1&&!NaturalShelter.stand(home.getLevel(),home.entrance().relative(side,distance-1)))continue;
                    pose.pushPose();pose.translate((twig==0?-1:1)*(distance-1),0,0);
                    PropModels.renderLayers("nest_boundary",1<<twig,pose,buffers,light,overlay);pose.popPose();
                }
                pose.popPose();
            }
            return;
        }
        if(home.getLevel()!=null)light=LevelRenderer.getLightColor(home.getLevel(),home.getBlockPos().above(home.kind().height));
        pose.pushPose();pose.translate(.5,0,.5);
        pose.mulPose(Axis.YP.rotationDegrees(-90*ShelterGeometry.turns(home.facing())));
        int lined=home.lining()>0?1:0;
        // Damp stains the panels. The soaked roof and lip fold down over an empty opening. Unit 1 is bedding.
        String id=home.kind()==ShelterBlock.Kind.CARDBOARD&&home.rainStage()>0?home.kind().id+(home.rainStage()==1?"_damp":"_soaked"):home.kind().id;
        PropModels.renderLayers(id,lined,pose,buffers,light,overlay);
        pose.popPose();
    }
    /** Unit 1 is the bedding; 2..5 are the north/east/south/west rim middles, 6..9 the NE/SE/SW/NW corner arcs (both sides
     * exposed) and 10..17 the rim ends where an exposed edge runs on over the next bed: N-west, N-east, E-north, E-south,
     * S-east, S-west, W-south, W-north. Together they make one round-cornered rim around the whole connected nest. */
    private static int sharedLeafLayers(Set<BlockPos> shared,BlockPos bed) {
        boolean north=!shared.contains(bed.north()),east=!shared.contains(bed.east()),south=!shared.contains(bed.south()),west=!shared.contains(bed.west());
        int layers=1;
        if(north)layers|=2;
        if(east)layers|=4;
        if(south)layers|=8;
        if(west)layers|=16;
        if(north&&east)layers|=1<<5;
        if(south&&east)layers|=1<<6;
        if(south&&west)layers|=1<<7;
        if(north&&west)layers|=1<<8;
        if(north&&!west)layers|=1<<9;
        if(north&&!east)layers|=1<<10;
        if(east&&!north)layers|=1<<11;
        if(east&&!south)layers|=1<<12;
        if(south&&!east)layers|=1<<13;
        if(south&&!west)layers|=1<<14;
        if(west&&!south)layers|=1<<15;
        if(west&&!north)layers|=1<<16;
        return layers;
    }
    @Override public AABB getRenderBoundingBox(ShelterBlockEntity home) {
        if (home.kind()==ShelterBlock.Kind.CARDBOARD) return home.interior();
        return home.interior().inflate(1);
    }
    @Override public boolean shouldRenderOffScreen(ShelterBlockEntity home) { return true; }
}
