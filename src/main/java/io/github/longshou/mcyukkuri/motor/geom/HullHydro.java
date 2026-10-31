package io.github.longshou.mcyukkuri.motor.geom;

import io.github.longshou.mcyukkuri.headwear.HullProfile;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Hydrostatics of either exported shell, clipped analytically against the horizontal water plane. */
public final class HullHydro {
    private static final Map<String,HullHydro> FORMS=new ConcurrentHashMap<>();
    private final HullShell shell;
    private final ThreadLocal<HullShell.Slice> slices;
    public static HullHydro of(HullProfile profile){return FORMS.computeIfAbsent(profile.id(),id->new HullHydro(profile));}
    private HullHydro(HullProfile profile){shell=new HullShell(profile);slices=ThreadLocal.withInitial(()->new HullShell.Slice(shell));}

    /** Unit-scale cloth moments are integrated once when the profile loads; scaling needs no shell traversal. */
    public Buoyancy.Load hull(double unit) {
        var c=shell.cloth;double area=unit*unit;
        return new Buoyancy.Load(c.mass()*area,c.moment().scale(area*unit),c.rollInertia()*area*area,c.pitchInertia()*area*area);
    }
    public record Sample(double draft,double volume,Vec center,Buoyancy.Plane plane,double clearance,double depth) {
        /** Fraction of upright hull depth consumed by draft and heel; one at the wet rim, increasing with upright draft.
         * Finite beyond flooding, so overloaded candidate positions can still be ordered. */
        public double ratio(){return 1-clearance/depth;}
        public Vec moment(Buoyancy.Load load,double roll,double pitch) {
            var d=center.scale(volume).sub(Buoyancy.rotate(load.moment(),roll,pitch));
            return new Vec(d.x(),0,-d.z()*Math.cos(Math.toRadians(roll)));
        }
    }
    public double rimHeight(double unit,double roll,double pitch) {
        var s=pose(unit,roll,pitch);double low=Double.POSITIVE_INFINITY;
        for(int i:shell.rim)low=Math.min(low,s.xyz[3*i+1]);
        return low;
    }
    public record Bounds(double bottom,double top) {}
    public Bounds bounds(double unit,double roll,double pitch){var s=pose(unit,roll,pitch);return new Bounds(s.low,s.high);}
    private HullShell.Slice pose(double unit,double roll,double pitch){var s=slices.get();s.pose(unit,roll,pitch);return s;}
    public Sample sample(double unit,double roll,double pitch,double draft) {
        var s=pose(unit,roll,pitch);s.cut(draft);
        var plane=s.area<=0?new Buoyancy.Plane(0,0,0,0):new Buoyancy.Plane(s.area,s.xx-s.ax*s.ax/s.area,s.zz-s.az*s.az/s.area,s.xz-s.ax*s.az/s.area);
        return new Sample(draft,s.volume,s.volume<=0?Vec.ZERO:new Vec(s.mx/s.volume,s.my/s.volume,s.mz/s.volume),plane,
                rimHeight(unit,roll,pitch)-draft,shell.rimDepth*unit);
    }
    /** Excess mass leaves the cloth awash at the surface (project design), with its real displaced volume reported. */
    public Sample balance(double unit,double mass,double roll,double pitch) {
        var s=pose(unit,roll,pitch);double lo=s.low,hi=s.high,draft=(lo+hi)/2;
        var full=sample(unit,roll,pitch,hi);
        if(mass>=full.volume())return full;
        for(int i=0;i<24;i++) {
            var water=sample(unit,roll,pitch,draft);double error=mass-water.volume();
            if(Math.abs(error)<1e-9)return water;
            if(error>0)lo=draft;else hi=draft;
            double next=draft+error/water.plane().area();draft=next>lo&&next<hi?next:(lo+hi)/2;
        }
        return sample(unit,roll,pitch,draft);
    }

    /** One load configuration per spot scan. Candidates share the upright draft/waterplane; only moments vary. */
    public final class Stability {
        private final double unit;
        private final Sample water;
        public Stability(double unit,double mass){this.unit=unit;water=balance(unit,mass,0,0);}
        public double ratio(Buoyancy.Load load) {
            var torque=water.moment(load,0,0);double vertical=water.volume()*water.center().y()-load.moment().y();
            double a=water.plane().rollInertia()+vertical,b=water.plane().pitchInertia()+vertical,c=-water.plane().cross(),det=a*b-c*c;
            if(a<=0||b<=0||det<=0)return Math.max(1,water.ratio())+Math.hypot(torque.x(),torque.z())/(load.mass()*water.depth());
            double roll=Math.toDegrees((b*torque.x()-c*torque.z())/det),pitch=Math.toDegrees((a*torque.z()-c*torque.x())/det);
            // Linear clearance preserves ordering even for far-overloaded candidates, without wrapping their angles.
            double loss=0;
            for(int i:shell.rim)loss=Math.max(loss,unit*(-shell.vertices[3*i]*Math.toRadians(roll)+shell.vertices[3*i+2]*Math.toRadians(pitch)));
            return water.ratio()+loss/water.depth();
        }
    }
    /** Allocation-free section output; this workspace is retained by its renderer. */
    public final class Waterline {
        private final HullShell.Slice slice=new HullShell.Slice(shell);
        private final double[] x=new double[shell.cells.length*4],z=new double[x.length],cx=new double[9],cz=new double[9],angle=new double[9];
        public int update(double unit,double roll,double pitch,double draft) {
            slice.pose(unit,roll,pitch);
            if(rimHeight(unit,roll,pitch)<=draft)return 0;
            int count=0;
            // Each mesh cell is a convex triangular prism; its section needs no contour joining or hole bridging.
            for(int i=0;i<shell.cells.length;i+=3) {
                int n=0;double mx=0,mz=0;
                for(int edge=0;edge<9;edge++) {
                    int corner=edge%3,a=shell.cells[i+corner]+(edge>=3&&edge<6?1:0);
                    int b=edge>=6?a+1:shell.cells[i+(corner+1)%3]+(edge>=3?1:0);
                    double ay=slice.xyz[3*a+1]-draft,by=slice.xyz[3*b+1]-draft;
                    if((ay<0)==(by<0))continue;
                    double t=ay/(ay-by);
                    cx[n]=slice.xyz[3*a]+t*(slice.xyz[3*b]-slice.xyz[3*a]);
                    cz[n]=slice.xyz[3*a+2]+t*(slice.xyz[3*b+2]-slice.xyz[3*a+2]);mx+=cx[n];mz+=cz[n++];
                }
                if(n<3)continue;
                for(int j=0;j<n;j++)angle[j]=Math.atan2(cz[j]-mz/n,cx[j]-mx/n);
                for(int j=1;j<n;j++)for(int k=j;k>0&&angle[k]<angle[k-1];k--) {
                    double swap=angle[k];angle[k]=angle[k-1];angle[k-1]=swap;
                    swap=cx[k];cx[k]=cx[k-1];cx[k-1]=swap;swap=cz[k];cz[k]=cz[k-1];cz[k-1]=swap;
                }
                for(int j=1;j+1<n;j++)for(int k=0;k<3;k++){int v=k==0?0:j+k-1;x[count]=cx[v];z[count++]=cz[v];}
            }
            return count;
        }
        public double x(int index){return x[index];}
        public double z(int index){return z[index];}
    }
    public Waterline waterline(){return new Waterline();}
}
