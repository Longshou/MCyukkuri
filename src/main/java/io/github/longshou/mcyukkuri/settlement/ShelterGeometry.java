package io.github.longshou.mcyukkuri.settlement;

import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.EnumMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import java.io.IOException;
import java.io.UncheckedIOException;

/** Authoring exports supply both render geometry and physical shell boxes for each part. */
public final class ShelterGeometry {
    /** Physical shell per part, and the coarser visible silhouette that selects and outlines the same part. */
    private record PartShapes(VoxelShape[][] collision,VoxelShape[][] outline) {}
    private static final EnumMap<ShelterBlock.Kind,PartShapes> CACHE=new EnumMap<>(ShelterBlock.Kind.class);
    public static VoxelShape shape(ShelterBlock.Kind kind, Direction facing, int part) {
        return load(kind).collision[facing.get2DDataValue()][part];
    }
    public static VoxelShape outline(ShelterBlock.Kind kind, Direction facing, int part) {
        return load(kind).outline[facing.get2DDataValue()][part];
    }
    private static PartShapes load(ShelterBlock.Kind kind) {
        return CACHE.computeIfAbsent(kind,ShelterGeometry::read);
    }
    private static PartShapes read(ShelterBlock.Kind kind) {
        VoxelShape[][] collision=new VoxelShape[4][75],outline=new VoxelShape[4][75];
        try(var reader=new InputStreamReader(ShelterGeometry.class.getResourceAsStream("/data/mcyukkuri/shelters/"+kind.id+".json"),StandardCharsets.UTF_8)) {
            var data=JsonParser.parseReader(reader).getAsJsonObject();
            var collisionBoxes=data.getAsJsonArray("collision");
            var outlineBoxes=data.has("outline")?data.getAsJsonArray("outline"):collisionBoxes;
            for(Direction facing:Direction.Plane.HORIZONTAL) for(int part=0;part<75;part++) {
                var offset=ShelterBlock.offset(part);
                collision[facing.get2DDataValue()][part]=clipped(collisionBoxes,offset,facing);
                outline[facing.get2DDataValue()][part]=clipped(outlineBoxes,offset,facing);
            }
        } catch(IOException error) { throw new UncheckedIOException(error); }
        return new PartShapes(collision,outline);
    }
    /** Authoring boxes lie in the south-facing frame around the root block centre; keep the part of each box inside one cell. */
    private static VoxelShape clipped(JsonArray boxes, BlockPos offset, Direction facing) {
        VoxelShape shape=Shapes.empty();
        for(var raw:boxes) {
            var box=raw.getAsJsonArray();double x0=box.get(0).getAsDouble(),y0=box.get(1).getAsDouble(),z0=box.get(2).getAsDouble();
            double x1=box.get(3).getAsDouble(),y1=box.get(4).getAsDouble(),z1=box.get(5).getAsDouble();
            for(int turn=0;turn<turns(facing);turn++) { double a=x0,b=x1;x0=-z1;x1=-z0;z0=a;z1=b; }
            x0=Math.max(0,x0/16+.5-offset.getX());x1=Math.min(1,x1/16+.5-offset.getX());
            y0=Math.max(0,y0/16-offset.getY());y1=Math.min(1,y1/16-offset.getY());
            z0=Math.max(0,z0/16+.5-offset.getZ());z1=Math.min(1,z1/16+.5-offset.getZ());
            if(x1>x0&&y1>y0&&z1>z0)shape=Shapes.or(shape,Shapes.box(x0,y0,z0,x1,y1,z1));
        }
        return shape.optimize();
    }
    public static int turns(Direction facing) { return switch(facing) { case SOUTH->0;case WEST->1;case NORTH->2;default->3; }; }
    /** Whether {@code cell} lies in the 3x3 footprint of a large bed: its mesh covers the cell, so no shared leaf mat belongs there. */
    public static boolean inLargeFootprint(Collection<BlockPos> largeBeds, BlockPos cell) {
        return largeBeds.stream().anyMatch(bed -> bed.getY() == cell.getY() && Math.abs(bed.getX() - cell.getX()) <= 1 && Math.abs(bed.getZ() - cell.getZ()) <= 1);
    }
    private ShelterGeometry() {}
}
