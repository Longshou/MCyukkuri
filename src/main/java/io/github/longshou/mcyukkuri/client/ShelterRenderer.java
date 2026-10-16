package io.github.longshou.mcyukkuri.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import io.github.longshou.mcyukkuri.settlement.ShelterBlockEntity;
import io.github.longshou.mcyukkuri.settlement.ShelterGeometry;
import java.util.Set;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.world.phys.AABB;

/** One render at the household root; shell collision belongs to every physical part. */
public final class ShelterRenderer implements BlockEntityRenderer<ShelterBlockEntity> {
    public ShelterRenderer(BlockEntityRendererProvider.Context context) {}
    @Override public void render(ShelterBlockEntity home,float partial,PoseStack pose,MultiBufferSource buffers,int light,int overlay) {
        if(home.kind()==io.github.longshou.mcyukkuri.settlement.ShelterBlock.Kind.BURROW) {
            var shared=Set.copyOf(home.sharedBeds());
            if(home.lining()>0) for(var bed:shared) {
                var offset=bed.subtract(home.getBlockPos());
                int local=home.getLevel()==null?light:net.minecraft.client.renderer.LevelRenderer.getLightColor(home.getLevel(),bed);
                pose.pushPose();pose.translate(offset.getX()+.5,0,offset.getZ()+.5);
                PropModels.renderLayers("forest_burrow",sharedLeafLayers(shared,bed),pose,buffers,local,overlay);pose.popPose();
            }
            for(var bed:home.privateBeds()) {
                var offset=bed.subtract(home.getBlockPos());
                int local=home.getLevel()==null?light:net.minecraft.client.renderer.LevelRenderer.getLightColor(home.getLevel(),bed);
                pose.pushPose();pose.translate(offset.getX()+.5,0,offset.getZ()+.5);
                PropModels.render("personal_leaf_nest",0,pose,buffers,local,overlay);pose.popPose();
            }
            if(home.branches()>0) {
                if(home.getLevel()!=null)light=net.minecraft.client.renderer.LevelRenderer.getLightColor(home.getLevel(),home.entrance());
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
                    for(int i=1;i<=3;i++)if(home.getLevel()!=null&&io.github.longshou.mcyukkuri.settlement.NaturalShelter.earth(home.getLevel(),home.getBlockPos().relative(side,i))){distance=i;break;}
                    if(distance==0)continue;
                    if(distance>1&&!io.github.longshou.mcyukkuri.settlement.NaturalShelter.stand(home.getLevel(),home.entrance().relative(side,distance-1)))continue;
                    pose.pushPose();pose.translate((twig==0?-1:1)*(distance-1),0,0);
                    PropModels.renderLayers("nest_boundary",1<<twig,pose,buffers,light,overlay);pose.popPose();
                }
                pose.popPose();
            }
            return;
        }
        if(home.getLevel()!=null)light=net.minecraft.client.renderer.LevelRenderer.getLightColor(home.getLevel(),home.getBlockPos().above(home.kind().height));
        pose.pushPose();pose.translate(.5,0,.5);
        pose.mulPose(Axis.YP.rotationDegrees(-90*ShelterGeometry.turns(home.facing())));
        PropModels.renderLayers(home.kind().id,(home.lining()>0?1:0)|(home.food()>0?2:0),pose,buffers,light,overlay);
        pose.popPose();
    }
    /** Unit 1 is the bedding; units 2..5 are north/east/south/west rim pieces. */
    private static int sharedLeafLayers(Set<net.minecraft.core.BlockPos> shared,net.minecraft.core.BlockPos bed) {
        int layers=1;
        if(!shared.contains(bed.north()))layers|=2;
        if(!shared.contains(bed.east()))layers|=4;
        if(!shared.contains(bed.south()))layers|=8;
        if(!shared.contains(bed.west()))layers|=16;
        return layers;
    }
    @Override public AABB getRenderBoundingBox(ShelterBlockEntity home) {
        return home.kind()==io.github.longshou.mcyukkuri.settlement.ShelterBlock.Kind.BURROW?home.interior().inflate(1):new AABB(home.getBlockPos()).inflate(home.kind().radius,0,home.kind().radius).expandTowards(0,home.kind().height,0);
    }
    @Override public boolean shouldRenderOffScreen(ShelterBlockEntity home) { return true; }
}
