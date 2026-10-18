package io.github.longshou.mcyukkuri.settlement;

import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import java.io.IOException;
import java.io.UncheckedIOException;

/** Authoring exports supply both render geometry and clipped physical shell boxes. */
public final class ShelterGeometry {
    private static final EnumMap<ShelterBlock.Kind,VoxelShape[][]> CACHE=new EnumMap<>(ShelterBlock.Kind.class);
    public static VoxelShape shape(ShelterBlock.Kind kind, Direction facing, int part) {
        return CACHE.computeIfAbsent(kind,ShelterGeometry::load)[facing.get2DDataValue()][part];
    }
    private static VoxelShape[][] load(ShelterBlock.Kind kind) {
        VoxelShape[][] result=new VoxelShape[4][75];
        try(var reader=new InputStreamReader(ShelterGeometry.class.getResourceAsStream("/data/mcyukkuri/shelters/"+kind.id+".json"),StandardCharsets.UTF_8)) {
            var boxes=JsonParser.parseReader(reader).getAsJsonObject().getAsJsonArray("collision");
            for(Direction facing:Direction.Plane.HORIZONTAL) for(int part=0;part<75;part++) {
                var offset=ShelterBlock.offset(part); VoxelShape shape=Shapes.empty();
                for(var raw:boxes) {
                    var box=raw.getAsJsonArray();double x0=box.get(0).getAsDouble(),y0=box.get(1).getAsDouble(),z0=box.get(2).getAsDouble();
                    double x1=box.get(3).getAsDouble(),y1=box.get(4).getAsDouble(),z1=box.get(5).getAsDouble();
                    for(int turn=0;turn<turns(facing);turn++) { double a=x0,b=x1;x0=-z1;x1=-z0;z0=a;z1=b; }
                    x0=Math.max(0,x0/16+.5-offset.getX());x1=Math.min(1,x1/16+.5-offset.getX());
                    y0=Math.max(0,y0/16-offset.getY());y1=Math.min(1,y1/16-offset.getY());
                    z0=Math.max(0,z0/16+.5-offset.getZ());z1=Math.min(1,z1/16+.5-offset.getZ());
                    if(x1>x0&&y1>y0&&z1>z0)shape=Shapes.or(shape,Shapes.box(x0,y0,z0,x1,y1,z1));
                }
                result[facing.get2DDataValue()][part]=shape.optimize();
            }
        } catch(IOException error) { throw new UncheckedIOException(error); }
        return result;
    }
    public static int turns(Direction facing) { return switch(facing) { case SOUTH->0;case WEST->1;case NORTH->2;default->3; }; }
    private ShelterGeometry() {}
}
