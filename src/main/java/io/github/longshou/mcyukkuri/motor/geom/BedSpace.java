package io.github.longshou.mcyukkuri.motor.geom;

import io.github.longshou.mcyukkuri.pose.WallYield;
import java.util.ArrayList;
import java.util.List;

/** Block collision boxes relative to the bedding plane. The sole must be covered even between its corners. */
public final class BedSpace {
    public record Box(double x0,double y0,double z0,double x1,double y1,double z1) {
        boolean supportsSide(double x,double z,double bodyTop) {
            return WallYield.supportsSide(x,z,bodyTop,x0,y0,z0,x1,y1,z1);
        }
    }
    private record Rect(double x0,double z0,double x1,double z1) {
        void subtract(Box b,List<Rect> remainder) {
            double left=Math.max(x0,b.x0), right=Math.min(x1,b.x1);
            double near=Math.max(z0,b.z0), far=Math.min(z1,b.z1);
            if(left>=right || near>=far){remainder.add(this);return;}
            if(x0<left)remainder.add(new Rect(x0,z0,left,z1));
            if(right<x1)remainder.add(new Rect(right,z0,x1,z1));
            if(z0<near)remainder.add(new Rect(left,z0,right,near));
            if(far<z1)remainder.add(new Rect(left,far,right,z1));
        }
    }

    public static boolean fits(List<Box> boxes,double x,double z,double width,double height,double reach,double brim,double top) {
        double r=width*.5;
        List<Rect> unsupported=List.of(new Rect(x-r,z-r,x+r,z+r));
        for(var b:boxes) {
            if(Math.abs(b.y1)<1e-7 && !unsupported.isEmpty()){
                var rest=new ArrayList<Rect>();
                for(var area:unsupported)area.subtract(b,rest);
                unsupported=rest;
            }
            if(b.y1<=1e-7)continue;
            if(b.y0<height && b.x0<x+r && b.x1>x-r && b.z0<z+r && b.z1>z-r)return false;
            if(b.y0<top && b.y1>brim){
                double nearX=Math.clamp(x,b.x0,b.x1), nearZ=Math.clamp(z,b.z0,b.z1);
                double dx=x-nearX, dz=z-nearZ;
                if(dx*dx+dz*dz<reach*reach && !WallYield.pressesSide(x,z,nearX,nearZ,b.y0,height,
                        (px,pz,bodyTop)->boxes.stream().anyMatch(wall->wall.supportsSide(px,pz,bodyTop))))return false;
            }
        }
        return unsupported.isEmpty();
    }
    private BedSpace() {}
}
