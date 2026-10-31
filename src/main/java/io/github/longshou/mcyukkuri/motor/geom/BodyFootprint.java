package io.github.longshou.mcyukkuri.motor.geom;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.concurrent.ConcurrentHashMap;

/** Body-only columns of the species' own body mesh, scaled to collision width/height. No soft accessories. */
public record BodyFootprint(List<Column> columns, double width, double height, double yaw, double radius, Shape shape, double cosine, double sine) {
    public record Column(double x, double z, double bottom, double top) {}
    private static final double STEP = 1.0 / 24;
    private record Key(String asset,double width,double height,int yaw) {}
    private static final Map<String,Shape> SHAPES=new ConcurrentHashMap<>();
    private static final Map<Key,BodyFootprint> CACHE=new LinkedHashMap<>(256,.75f,true) {
        @Override protected boolean removeEldestEntry(Map.Entry<Key,BodyFootprint> entry){return size()>256;}
    };
    public static int yawBucket(double yaw){return Math.floorMod((int)Math.round(Math.toDegrees(yaw)/5),72);}
    public static synchronized BodyFootprint of(String asset,double width,double height,double yaw) {
        return CACHE.computeIfAbsent(new Key(asset,width,height,yawBucket(yaw)),key->{
            var shape=SHAPES.computeIfAbsent(asset,BodyFootprint::shape);
            double angle=Math.toRadians(key.yaw*5),c=Math.cos(angle),s=Math.sin(angle),radius=0;
            var out=new ArrayList<Column>(shape.columns.size());
            for(var p:shape.columns){
                var q=new Column(width*(p.x*c-p.z*s),width*(p.z*c+p.x*s),height*p.bottom,height*p.top);
                out.add(q);radius=Math.max(radius,Math.hypot(q.x,q.z));
            }
            return new BodyFootprint(List.copyOf(out),width,height,angle,radius,shape,c,s);
        });
    }
    /** Body collision at shared columns, including the curved underside and crown. */
    public boolean overlaps(double x,double y,double z,BodyFootprint other,double ox,double oy,double oz,double gap) {
        return overlaps(x,y,z,other,ox,oy,oz,gap,0);
    }
    public boolean overlaps(double x,double y,double z,BodyFootprint other,double ox,double oy,double oz,double gap,double rise) {
        return Math.hypot(x-ox,z-oz)<radius+other.radius+gap && (probes(x,y,z,other,ox,oy,oz,gap,rise,0) || other.probes(ox,oy,oz,this,x,y,z,gap,0,rise));
    }
    private boolean probes(double x,double y,double z,BodyFootprint other,double ox,double oy,double oz,double gap,double rise,double otherRise) {
        double c=other.cosine,s=other.sine;
        for(var p:columns){
            double dx=x+p.x-ox,dz=z+p.z-oz;
            var q=other.shape.column((dx*c+dz*s)/other.width,(dz*c-dx*s)/other.width);
            if(q!=null && y+p.bottom<oy+q.top*other.height+otherRise+gap && y+p.top+rise>oy+q.bottom*other.height-gap)return true;
        }
        return false;
    }
    public boolean overlaps(double x,double y,double z,Disc solid,double gap) {
        return overlaps(x,y,z,solid,gap,0);
    }
    public boolean overlaps(double x,double y,double z,Disc solid,double gap,double rise) {
        if(Math.hypot(x-solid.x(),z-solid.z())>=radius+solid.r()+gap)return false;
        for(var p:columns)if(Math.hypot(x+p.x-solid.x(),z+p.z-solid.z())<solid.r()+gap
                && Disc.level(y+p.bottom-gap,y+p.top+rise+gap,solid.bottom(),solid.top()))return true;
        return false;
    }
    public record Shape(List<Column> columns,Column[][] grid,int offset) {
        Column column(double x,double z){
            int ix=(int)Math.round(x/STEP)+offset,iz=(int)Math.round(z/STEP)+offset;
            return ix<0||iz<0||ix>=grid.length||iz>=grid.length?null:grid[ix][iz];
        }
    }
    private static Shape shape(String asset) {
            var source=GeometryJson.read("yukkuri/"+asset+".json");
            var model=source.data();var vertices=source.field("vertices").getAsJsonArray();
            var triangles=new ArrayList<double[]>();double width=0,height=0,extent=0;
            for(var value:model.getAsJsonArray("parts")){
                var part=value.getAsJsonObject();if(!part.get("name").getAsString().endsWith("_body_surface"))continue;
                int first=part.get("start").getAsInt(),end=first+part.get("count").getAsInt();
                for(int i=first;i<end;i+=3){
                    double[] t=new double[9];for(int j=0;j<9;j++){t[j]=vertices.get((i+j/3)*8+j%3).getAsDouble();if(j%3==0)width=Math.max(width,2*Math.abs(t[j]));if(j%3==1)height=Math.max(height,t[j]);else extent=Math.max(extent,Math.abs(t[j]));}triangles.add(t);
                }
            }
            source.check(!triangles.isEmpty(),"parts","missing *_body_surface");
            int cells=(int)Math.ceil(extent/width/STEP);
            var out=new ArrayList<Column>();var grid=new Column[2*cells+1][2*cells+1];
            for(int ix=-cells;ix<=cells;ix++)for(int iz=-cells;iz<=cells;iz++){
                double x=ix*STEP*width,z=iz*STEP*width,low=Double.POSITIVE_INFINITY,high=Double.NEGATIVE_INFINITY;
                for(var t:triangles){
                    double d=(t[5]-t[8])*(t[0]-t[6])+(t[6]-t[3])*(t[2]-t[8]);if(Math.abs(d)<1e-9)continue;
                    double a=((t[5]-t[8])*(x-t[6])+(t[6]-t[3])*(z-t[8]))/d,b=((t[8]-t[2])*(x-t[6])+(t[0]-t[6])*(z-t[8]))/d;
                    if(a<0||b<0||a+b>1)continue;
                    double y=a*t[1]+b*t[4]+(1-a-b)*t[7];low=Math.min(low,y);high=Math.max(high,y);
                }
                if(Double.isFinite(low)){var p=new Column(ix*STEP,iz*STEP,low/height,high/height);out.add(p);grid[ix+cells][iz+cells]=p;}
            }
            return new Shape(List.copyOf(out),grid,cells);
    }
}
