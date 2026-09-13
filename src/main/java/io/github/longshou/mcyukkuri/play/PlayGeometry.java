package io.github.longshou.mcyukkuri.play;

import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Authoring geometry is clipped to physical cells, avoiding oversized single-block collision. */
public final class PlayGeometry {
    private static final EnumMap<PlayBlock.Kind,VoxelShape[][]> CACHE=new EnumMap<>(PlayBlock.Kind.class);
    public static VoxelShape shape(PlayBlock.Kind kind,Direction facing,int part){return CACHE.computeIfAbsent(kind,PlayGeometry::load)[facing.get2DDataValue()][part];}
    public static int turns(Direction facing){return switch(facing){case SOUTH->0;case WEST->1;case NORTH->2;default->3;};}
    public static Vec3 world(BlockPos root,Direction facing,double x,double y,double z){
        for(int i=0;i<turns(facing);i++){double old=x;x=-z;z=old;}
        return new Vec3(root.getX()+.5+x,root.getY()+y,root.getZ()+.5+z);
    }
    private static VoxelShape[][] load(PlayBlock.Kind kind){
        VoxelShape[][] result=new VoxelShape[4][50];
        try(var reader=new InputStreamReader(PlayGeometry.class.getResourceAsStream("/data/mcyukkuri/playgrounds/"+kind.id+".json"),StandardCharsets.UTF_8)){
            var boxes=JsonParser.parseReader(reader).getAsJsonObject().getAsJsonArray("collision");
            for(Direction facing:Direction.Plane.HORIZONTAL)for(int part=0;part<50;part++){
                var offset=PlayBlock.offset(part);VoxelShape shape=Shapes.empty();
                for(var raw:boxes){
                    var box=raw.getAsJsonArray();double x0=box.get(0).getAsDouble(),y0=box.get(1).getAsDouble(),z0=box.get(2).getAsDouble();
                    double x1=box.get(3).getAsDouble(),y1=box.get(4).getAsDouble(),z1=box.get(5).getAsDouble();
                    for(int t=0;t<turns(facing);t++){double a=x0,b=x1;x0=-z1;x1=-z0;z0=a;z1=b;}
                    x0=Math.max(0,x0/16+.5-offset.getX());x1=Math.min(1,x1/16+.5-offset.getX());
                    y0=Math.max(0,y0/16-offset.getY());y1=Math.min(1,y1/16-offset.getY());
                    z0=Math.max(0,z0/16+.5-offset.getZ());z1=Math.min(1,z1/16+.5-offset.getZ());
                    if(x1>x0&&y1>y0&&z1>z0)shape=Shapes.or(shape,Shapes.box(x0,y0,z0,x1,y1,z1));
                }
                result[facing.get2DDataValue()][part]=shape.optimize();
            }
        }catch(java.io.IOException error){throw new java.io.UncheckedIOException(error);}
        return result;
    }
    private PlayGeometry(){}
}
