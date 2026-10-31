package io.github.longshou.mcyukkuri.motor.geom;

import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Body-only columns of the shared rounded body mesh, scaled to collision width/height. No soft accessories. */
public record BodyFootprint(List<Column> columns, double width, double height, double yaw) {
    public record Column(double x, double z, double bottom, double top) {}
    private static final double STEP = 1.0 / 24;
    private static final List<Column> SHAPE = shape();
    private static final Map<Long,Column> GRID = SHAPE.stream().collect(Collectors.toUnmodifiableMap(p -> key(p.x,p.z), p -> p));

    public static BodyFootprint of(double width, double height, double yaw) {
        var out = new ArrayList<Column>(SHAPE.size());
        double c=Math.cos(yaw), s=Math.sin(yaw);
        for(var p:SHAPE) out.add(new Column(width*(p.x*c-p.z*s), width*(p.z*c+p.x*s), height*p.bottom, height*p.top));
        return new BodyFootprint(List.copyOf(out), width, height, yaw);
    }
    public static BodyFootprint point() { return new BodyFootprint(List.of(new Column(0,0,0,0)),0,0,0); }
    public double radius() { return columns.stream().mapToDouble(p -> Math.hypot(p.x,p.z)).max().orElseThrow(); }
    /** Body collision at shared columns, including the curved underside and crown. */
    public boolean overlaps(double x,double y,double z,BodyFootprint other,double ox,double oy,double oz,double gap) {
        return overlaps(x,y,z,other,ox,oy,oz,gap,0);
    }
    public boolean overlaps(double x,double y,double z,BodyFootprint other,double ox,double oy,double oz,double gap,double rise) {
        return probes(x,y,z,other,ox,oy,oz,gap,rise,0) || other.probes(ox,oy,oz,this,x,y,z,gap,0,rise);
    }
    private boolean probes(double x,double y,double z,BodyFootprint other,double ox,double oy,double oz,double gap,double rise,double otherRise) {
        double c=Math.cos(other.yaw),s=Math.sin(other.yaw);
        for(var p:columns){
            double dx=x+p.x-ox,dz=z+p.z-oz;
            var q=column((dx*c+dz*s)/other.width,(dz*c-dx*s)/other.width);
            if(q!=null && y+p.bottom<oy+q.top*other.height+otherRise+gap && y+p.top+rise>oy+q.bottom*other.height-gap)return true;
        }
        return false;
    }
    public boolean overlaps(double x,double y,double z,Disc solid,double gap) {
        return overlaps(x,y,z,solid,gap,0);
    }
    public boolean overlaps(double x,double y,double z,Disc solid,double gap,double rise) {
        for(var p:columns)if(Math.hypot(x+p.x-solid.x(),z+p.z-solid.z())<solid.r()+gap
                && Disc.level(y+p.bottom-gap,y+p.top+rise+gap,solid.bottom(),solid.top()))return true;
        return false;
    }
    private static Column column(double x,double z){
        return GRID.get(key(x,z));
    }
    private static long key(double x,double z){return (Math.round(x/STEP)<<32) ^ (Math.round(z/STEP)&0xffffffffL);}
    private static List<Column> shape() {
        var stream=BodyFootprint.class.getResourceAsStream("/assets/mcyukkuri/yukkuri/marisa.json");
        if(stream==null)throw new IllegalStateException("Missing rounded body mesh");
        try(var reader=new InputStreamReader(stream,StandardCharsets.UTF_8)){
            var model=JsonParser.parseReader(reader).getAsJsonObject();var vertices=model.getAsJsonArray("vertices");
            var triangles=new ArrayList<double[]>();double width=0,height=0;
            for(var value:model.getAsJsonArray("parts")){
                var part=value.getAsJsonObject();if(!part.get("name").getAsString().equals("rounded_body_surface"))continue;
                int first=part.get("start").getAsInt(),end=first+part.get("count").getAsInt();
                for(int i=first;i<end;i+=3){
                    double[] t=new double[9];for(int j=0;j<9;j++){t[j]=vertices.get((i+j/3)*8+j%3).getAsDouble();if(j%3==0)width=Math.max(width,2*Math.abs(t[j]));if(j%3==1)height=Math.max(height,t[j]);}triangles.add(t);
                }
            }
            var out=new ArrayList<Column>();
            for(int ix=-12;ix<=12;ix++)for(int iz=-12;iz<=12;iz++){
                double x=ix*STEP*width,z=iz*STEP*width,low=Double.POSITIVE_INFINITY,high=Double.NEGATIVE_INFINITY;
                for(var t:triangles){
                    double d=(t[5]-t[8])*(t[0]-t[6])+(t[6]-t[3])*(t[2]-t[8]);if(Math.abs(d)<1e-9)continue;
                    double a=((t[5]-t[8])*(x-t[6])+(t[6]-t[3])*(z-t[8]))/d,b=((t[8]-t[2])*(x-t[6])+(t[0]-t[6])*(z-t[8]))/d;
                    if(a<0||b<0||a+b>1)continue;
                    double y=a*t[1]+b*t[4]+(1-a-b)*t[7];low=Math.min(low,y);high=Math.max(high,y);
                }
                if(Double.isFinite(low))out.add(new Column(ix*STEP,iz*STEP,low/height,high/height));
            }
            return List.copyOf(out);
        }catch(IOException e){throw new UncheckedIOException(e);}
    }
}
