package io.github.longshou.mcyukkuri.motor.geom;

import io.github.longshou.mcyukkuri.headwear.HullProfile;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Polar quadrature of the exported outer shell. Columns include the dry cavity up to its spill crest.
 * Coordinates are keel-relative hat units; posed samples and loads use blocks. No opening rectangle. */
public final class HullHydro {
    private static final Map<String,HullHydro> FORMS=new ConcurrentHashMap<>();
    private record Column(double x,double z,double bottom,double top,double area) {}
    private final List<Column> columns=new ArrayList<>();
    private final List<Vec> rim=new ArrayList<>();
    private final List<Integer> starts=new ArrayList<>();
    private final double hullMass,area,volume;

    public static HullHydro of(HullProfile profile) { return FORMS.computeIfAbsent(profile.id(),id->new HullHydro(profile)); }
    private HullHydro(HullProfile profile) {
        var source=new GeometryJson(profile.hull(),profile.data());
        var angles=source.field("azimuths").getAsJsonArray();var rays=source.field("outer").getAsJsonArray();
        source.check(rays.size()==angles.size(),"outer","ray count mismatch");
        double u=profile.measuredUnit(),keel=source.number("keel.y"),sum=0,capacity=0;
        hullMass=source.number("volume.hull_mass")/(u*u);
        for(int i=0;i<rays.size();i++) {
            starts.add(columns.size());
            var ray=rays.get(i).getAsJsonArray();double a=Math.toRadians(angles.get(i).getAsDouble());
            double prev=i==0?angles.get(angles.size()-1).getAsDouble()-360:angles.get(i-1).getAsDouble();
            double next=i+1==angles.size()?360:angles.get(i+1).getAsDouble(),da=Math.toRadians(next-prev)/2;
            source.check(ray.size()>1,"outer["+i+"]","expected radial samples");
            for(int j=0;j<ray.size();j++) {
                var row=ray.get(j).getAsJsonArray();
                source.check(row.size()==3,"outer["+i+"]["+j+"]","expected radius, bottom, top");
                double r=row.get(0).getAsDouble(),b=row.get(1).getAsDouble(),t=row.get(2).getAsDouble();
                source.check(Double.isFinite(r)&&Double.isFinite(b)&&Double.isFinite(t)&&t>=b,"outer","invalid shell column");
                double lo=j==0?0:(ray.get(j-1).getAsJsonArray().get(0).getAsDouble()+r)/2;
                double hi=j+1==ray.size()?r:(ray.get(j+1).getAsJsonArray().get(0).getAsDouble()+r)/2;
                source.check(hi>=lo,"outer","radii must not decrease"); // the rounded final sample can repeat its neighbour
                double cell=da*(hi*hi-lo*lo)/2/(u*u);
                columns.add(new Column(r*Math.cos(a)/u,r*Math.sin(a)/u,(b-keel)/u,(t-keel)/u,cell));sum+=cell;capacity+=cell*(t-b)/u;
                if(j+1==ray.size())rim.add(new Vec(r*Math.cos(a)/u,(t-keel)/u,r*Math.sin(a)/u));
            }
        }
        area=sum;volume=capacity;
        starts.add(columns.size());
    }

    /** Cloth mass spread over its measured bottom, scaling with area rather than displaced volume. */
    public Buoyancy.Load hull(double unit) {
        var load=Buoyancy.Load.ZERO;
        for(var c:columns)load=load.add(hullMass*unit*unit*c.area/area,new Vec(c.x,c.bottom,c.z).scale(unit));
        return load;
    }

    public record Sample(double draft,double volume,Vec center,Buoyancy.Plane plane,double lowRim,double rimCenter) {
        public double freeboard() { return lowRim-draft; }
        public double ratio() { return rimCenter<=draft?Double.POSITIVE_INFINITY:(rimCenter-lowRim)/(rimCenter-draft); }
        public Vec moment(Buoyancy.Load load,double roll,double pitch) {
            var d=center.scale(volume).sub(Buoyancy.rotate(load.moment(),roll,pitch));
            return new Vec(d.x(),0,-d.z()*Math.cos(Math.toRadians(roll)));
        }
    }

    public double rimHeight(double unit,double roll,double pitch) {
        double low=Double.POSITIVE_INFINITY;
        for(var p:rim)low=Math.min(low,Buoyancy.rotate(p.scale(unit),roll,pitch).y());
        return low;
    }

    /** Waterline outline for the depth mask, in the yaw-free world frame; no mask survives a wet rim. */
    public List<Vec> waterline(double unit,double roll,double pitch,double draft) {
        if(rimHeight(unit,roll,pitch)<=draft)return List.of();
        var normal=Buoyancy.rotate(new Vec(0,1,0),roll,pitch);var points=new ArrayList<Vec>();
        for(int i=0;i+1<starts.size();i++) {
            Vec edge=null;
            for(int j=starts.get(i);j<starts.get(i+1);j++) {
                var c=columns.get(j);var base=Buoyancy.rotate(new Vec(c.x,0,c.z).scale(unit),roll,pitch);
                double cut=(draft-base.y())/normal.y();
                if(cut>c.bottom*unit&&cut<c.top*unit)edge=base.add(normal.scale(cut));
            }
            if(edge!=null)points.add(edge);
        }
        return points;
    }

    /** Integrates submerged lengths and first moments; partially wet columns also integrate the actual waterplane. */
    public Sample sample(double unit,double roll,double pitch,double draft) {
        double r=Math.toRadians(roll),p=Math.toRadians(pitch),sr=Math.sin(r),cr=Math.cos(r),sp=Math.sin(p),cp=Math.cos(p);
        double nx=-sr*cp,ny=cr*cp,nz=sp,v=0,mx=0,my=0,mz=0,wp=0,wx=0,wz=0,xx=0,zz=0,xz=0;
        for(var c:columns) {
            double bx=(c.x*cr+c.z*sp*sr)*unit,by=(c.x*sr-c.z*sp*cr)*unit,bz=c.z*cp*unit;
            double bottom=c.bottom*unit,top=c.top*unit,cut=(draft-by)/ny;
            double lo=ny>=0?bottom:Math.clamp(cut,bottom,top),hi=ny>=0?Math.clamp(cut,bottom,top):top;
            double cell=c.area*unit*unit,weight=cell*(hi-lo),mid=(lo+hi)/2;
            v+=weight;mx+=weight*(bx+nx*mid);my+=weight*(by+ny*mid);mz+=weight*(bz+nz*mid);
            if(cut>bottom&&cut<top) {
                double a=cell/Math.abs(ny),x=bx+nx*cut,z=bz+nz*cut;
                wp+=a;wx+=a*x;wz+=a*z;xx+=a*x*x;zz+=a*z*z;xz+=a*x*z;
            }
        }
        double low=Double.POSITIVE_INFINITY,ry=0;
        for(var point:rim){double y=(point.x()*sr+(point.y()*cp-point.z()*sp)*cr)*unit;low=Math.min(low,y);ry+=y/rim.size();}
        return new Sample(draft,v,v==0?Vec.ZERO:new Vec(mx/v,my/v,mz/v),
                wp==0?new Buoyancy.Plane(0,0,0,0):new Buoyancy.Plane(wp,xx-wx*wx/wp,zz-wz*wz/wp,xz-wx*wz/wp),low,ry);
    }

    private double[] bounds(double unit,double roll,double pitch) {
        double r=Math.toRadians(roll),p=Math.toRadians(pitch),sr=Math.sin(r),cr=Math.cos(r),sp=Math.sin(p),ny=cr*Math.cos(p);
        double lo=Double.POSITIVE_INFINITY,hi=Double.NEGATIVE_INFINITY;
        for(var c:columns) {
            double base=(c.x*sr-c.z*sp*cr)*unit,bottom=base+c.bottom*unit*ny,top=base+c.top*unit*ny;
            lo=Math.min(lo,Math.min(bottom,top));hi=Math.max(hi,Math.max(bottom,top));
        }
        return new double[]{lo,hi};
    }
    public double lowest(double unit,double roll,double pitch) { return bounds(unit,roll,pitch)[0]; }

    /** Mass equilibrium at this tilt. Excess mass fully immerses the shell and therefore floods it. */
    public Sample balance(double unit,double mass,double roll,double pitch) {
        var range=bounds(unit,roll,pitch);double lo=range[0],hi=range[1];
        if(mass>=volume*unit*unit*unit)return sample(unit,roll,pitch,hi);
        double draft=(lo+hi)/2;
        for(int i=0;i<32;i++) {
            var s=sample(unit,roll,pitch,draft);double error=mass-s.volume();
            if(Math.abs(error)<1e-10)return s;
            if(error>0)lo=draft;else hi=draft;
            double next=draft+error/s.plane().area();
            draft=next>lo&&next<hi?next:(lo+hi)/2;
        }
        return sample(unit,roll,pitch,draft);
    }

    public record Equilibrium(double roll,double pitch,Sample water,boolean stable) {
        public double heel() { return Math.toDegrees(Math.acos(Buoyancy.up(roll,pitch))); }
        public double ratio() { return stable?water.ratio():Double.POSITIVE_INFINITY; }
    }
    /** Coupled waterplane Newton solve. Two-degree search steps resolve shell ledges without jumping past a dry root. */
    public Equilibrium equilibrium(double unit,Buoyancy.Load load) {
        double roll=0,pitch=0;
        for(int i=0;i<80;i++) {
            var s=balance(unit,load.mass(),roll,pitch);var torque=s.moment(load,roll,pitch);
            double vertical=s.volume()*s.center().y()-Buoyancy.rotate(load.moment(),roll,pitch).y();
            double a=s.plane().rollInertia()+vertical,b=s.plane().pitchInertia()+vertical,c=-s.plane().cross(),det=a*b-c*c;
            if(s.freeboard()<=0||a<=0||b<=0||det<=0)return new Equilibrium(roll,pitch,s,false);
            if(Math.hypot(torque.x(),torque.z())<1e-8)return new Equilibrium(roll,pitch,s,true);
            double dr=Math.toDegrees((b*torque.x()-c*torque.z())/det),dp=Math.toDegrees((a*torque.z()-c*torque.x())/det);
            double step=Math.min(.7,2/Math.hypot(dr,dp));
            roll+=dr*step;pitch+=dp*step;
        }
        return new Equilibrium(roll,pitch,balance(unit,load.mass(),roll,pitch),false);
    }
}
