package io.github.longshou.mcyukkuri.motor.geom;

import java.util.List;

/** Inner bottom shape of a container in its plane coordinates (pure Java, no Minecraft types). */
public sealed interface FloorShape permits FloorShape.Circle,FloorShape.RoundedRect,FloorShape.Polygon {
    /** Whether the plane point is inside the floor; the boundary counts as inside. */
    boolean contains(double x,double z);

    /** Whether the plane point is inside the floor grown by {@code g} on every side. */
    boolean containsGrown(double x,double z,double g);

    /** Circular floor of radius {@code r} around the origin. */
    record Circle(double r) implements FloorShape {
        @Override public boolean contains(double x,double z){return x*x+z*z<=r*r;}

        @Override public boolean containsGrown(double x,double z,double g){
            double s=r+g;
            return x*x+z*z<=s*s;
        }
    }

    /** Axis-aligned rounded square: half side {@code half} and corner radius {@code corner}, centred on the origin. */
    record RoundedRect(double half,double corner) implements FloorShape {
        @Override public boolean contains(double x,double z){return inset(x,z,half,corner)<=0;}

        @Override public boolean containsGrown(double x,double z,double g){return inset(x,z,half+g,corner+g)<=0;}

        private static double inset(double x,double z,double half,double corner){
            double qx=Math.abs(x)-(half-corner),qz=Math.abs(z)-(half-corner);
            return Math.hypot(Math.max(qx,0),Math.max(qz,0))+Math.min(Math.max(qx,qz),0)-corner;
        }
    }

    /** Closed polygon over plane points ({@code Vec.y} ignored), even-odd inside test. */
    record Polygon(List<Vec> xz) implements FloorShape {
        @Override public boolean contains(double x,double z){
            boolean in=false;
            for(int i=0,j=xz.size()-1;i<xz.size();j=i++){
                Vec a=xz.get(i),b=xz.get(j);
                if((a.z()>z)!=(b.z()>z)&&x<(b.x()-a.x())*(z-a.z())/(b.z()-a.z())+a.x())in=!in;
            }
            return in;
        }

        @Override public boolean containsGrown(double x,double z,double g){
            if(contains(x,z))return true;
            for(int i=0,j=xz.size()-1;i<xz.size();j=i++)if(segment(x,z,xz.get(i),xz.get(j))<=g)return true;
            return false;
        }

        private static double segment(double x,double z,Vec a,Vec b){
            double ax=a.x(),az=a.z(),vx=b.x()-ax,vz=b.z()-az,len=vx*vx+vz*vz;
            if(len<1e-12)return Math.hypot(x-ax,z-az);
            double t=Math.max(0,Math.min(1,((x-ax)*vx+(z-az)*vz)/len));
            return Math.hypot(x-(ax+vx*t),z-(az+vz*t));
        }
    }
}
